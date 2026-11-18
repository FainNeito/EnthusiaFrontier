package net.enthusia.frontier.adapter.paper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.enthusia.frontier.domain.RegionKey;

/** Minimal fail-closed reader for the Anvil location table and external .mcc sidecars. */
public final class McaRegionFileInspector {
    static final int LOCATION_TABLE_BYTES = 4096;
    static final int HEADER_BYTES = 8192;
    private static final Pattern EXTERNAL_CHUNK = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final Pattern REGION_ENTRY = Pattern.compile("(r\\.-?\\d+\\.-?\\d+)\\.(.*)");

    private McaRegionFileInspector() {
    }

    /**
     * Negative-only screening for the asynchronous candidate scan. No result here
     * authorizes deletion: the live storage adapter still checks the cache and disk.
     */
    public static List<RegionKey> reclaimCandidates(Path worldFolder, List<RegionKey> candidates)
            throws IOException {
        if (candidates.isEmpty()) return List.of();
        var byPrefix = new HashMap<String, RegionKey>();
        for (RegionKey key : candidates) {
            byPrefix.put("r." + key.x() + "." + key.z(), key);
        }
        Set<RegionKey> existing = new HashSet<>();
        Set<RegionKey> occupied = new HashSet<>();
        Set<RegionKey> needsLiveCheck = new HashSet<>();
        for (String storage : List.of("region", "entities", "poi")) {
            Path folder = worldFolder.resolve(storage);
            if (Files.notExists(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid region storage directory: " + folder);
            }
            try (var entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    String name = entryName(entry);
                    if (name.startsWith("c.")) {
                        Matcher sidecar = EXTERNAL_CHUNK.matcher(name);
                        if (sidecar.matches()) {
                            int x = Integer.parseInt(sidecar.group(1)) >> 5;
                            int z = Integer.parseInt(sidecar.group(2)) >> 5;
                            RegionKey key = byPrefix.get("r." + x + "." + z);
                            if (key != null) occupied.add(key);
                        }
                        continue;
                    }
                    Matcher container = REGION_ENTRY.matcher(name);
                    if (!container.matches()) continue;
                    RegionKey key = byPrefix.get(container.group(1));
                    if (key == null) continue;
                    if (!container.group(2).equals("mca")) {
                        needsLiveCheck.add(key);
                        continue;
                    }
                    existing.add(key);
                    if (!occupied.contains(key)) {
                        try {
                            if (hasOccupiedHeader(entry)) occupied.add(key);
                        } catch (IOException ignored) {
                            // A concurrent create/remove or an invalid header requires live validation.
                            needsLiveCheck.add(key);
                        }
                    }
                }
            }
        }
        return candidates.stream().filter(key -> needsLiveCheck.contains(key)
                || (existing.contains(key) && !occupied.contains(key))).toList();
    }

    private static String entryName(Path entry) throws IOException {
        Path name = entry.getFileName();
        if (name == null) throw new IOException("Region storage entry has no filename: " + entry);
        return name.toString();
    }

    private static boolean hasOccupiedHeader(Path file) throws IOException {
        ByteBuffer header = readLocationTable(file);
        while (header.hasRemaining()) {
            if (header.getInt() != 0) return true;
        }
        return false;
    }

    enum Contents { EMPTY, ABSENT, OCCUPIED, UNSUPPORTED }

    /** Fresh check immediately before unlink; never uses the discovery result as evidence of emptiness. */
    static Contents inspectForReclaim(Path folder, RegionKey region) throws IOException {
        if (Files.notExists(folder, LinkOption.NOFOLLOW_LINKS)) return Contents.ABSENT;
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid storage: " + folder);
        String prefix = "r." + region.x() + "." + region.z() + ".";
        String mca = prefix + "mca";
        boolean sidecarPresent = false;
        try (var entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entryName(entry);
                if (name.startsWith(prefix) && !name.equals(mca)) return Contents.UNSUPPORTED;
                if (!name.startsWith("c.")) continue;
                Matcher match = EXTERNAL_CHUNK.matcher(name);
                if (match.matches() && (Integer.parseInt(match.group(1)) >> 5) == region.x()
                        && (Integer.parseInt(match.group(2)) >> 5) == region.z()) sidecarPresent = true;
            }
        }
        if (sidecarPresent) return Contents.OCCUPIED;
        Path file = folder.resolve(mca);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Contents.ABSENT;
        return hasOccupiedHeader(file) ? Contents.OCCUPIED : Contents.EMPTY;
    }

    public static boolean containsChunk(Path regionFile, int chunkX, int chunkZ) throws IOException {
        if (!Files.exists(regionFile, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        ByteBuffer header = readLocationTable(regionFile);
        int index = (chunkX & 31) + ((chunkZ & 31) << 5);
        return header.getInt(index * Integer.BYTES) != 0;
    }

    public static List<Coordinate> occupiedChunks(
            Path folder, Path regionFile, int regionX, int regionZ) throws IOException {
        Set<Coordinate> occupied = new LinkedHashSet<>();
        if (Files.exists(regionFile, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer header = readLocationTable(regionFile);
            for (int index = 0; index < 1024; index++) {
                if (header.getInt(index * Integer.BYTES) == 0) {
                    continue;
                }
                int localX = index & 31;
                int localZ = index >> 5;
                occupied.add(new Coordinate((regionX << 5) + localX, (regionZ << 5) + localZ));
            }
        }
        occupied.addAll(externalChunkSidecars(folder, regionX, regionZ));
        return List.copyOf(occupied);
    }

    public static boolean isCompletelyEmpty(Path folder, Path regionFile, int regionX, int regionZ) throws IOException {
        return occupiedChunks(folder, regionFile, regionX, regionZ).isEmpty();
    }

    public static boolean hasAlternativeContainer(Path folder, int regionX, int regionZ) throws IOException {
        String mcaName = regionName(regionX, regionZ);
        String prefix = "r." + regionX + "." + regionZ + ".";
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, prefix + "*")) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName == null) {
                    throw new IOException("region directory entry has no filename");
                }
                if (!fileName.toString().equals(mcaName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String regionName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    private static ByteBuffer readLocationTable(Path regionFile) throws IOException {
        if (Files.isSymbolicLink(regionFile)
                || !Files.isRegularFile(regionFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(regionFile) < HEADER_BYTES) {
            throw new IOException("region container is not a valid regular MCA file");
        }
        ByteBuffer header = ByteBuffer.allocate(LOCATION_TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    throw new IOException("truncated MCA location table");
                }
            }
        }
        header.flip();
        return header;
    }

    private static List<Coordinate> externalChunkSidecars(Path folder, int regionX, int regionZ) throws IOException {
        List<Coordinate> result = new ArrayList<>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "c.*.mcc")) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName == null) {
                    throw new IOException("external chunk sidecar has no filename");
                }
                Matcher matcher = EXTERNAL_CHUNK.matcher(fileName.toString());
                if (!matcher.matches()) {
                    continue;
                }
                int chunkX;
                int chunkZ;
                try {
                    chunkX = Integer.parseInt(matcher.group(1));
                    chunkZ = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException exception) {
                    throw new IOException("invalid external chunk sidecar name", exception);
                }
                if ((chunkX >> 5) == regionX && (chunkZ >> 5) == regionZ) {
                    result.add(new Coordinate(chunkX, chunkZ));
                }
            }
        }
        return result;
    }

    public record Coordinate(int x, int z) {
    }
}
