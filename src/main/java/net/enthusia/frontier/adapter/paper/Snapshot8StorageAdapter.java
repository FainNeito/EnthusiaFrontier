package net.enthusia.frontier.adapter.paper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.frontier.application.RegionReclaimResult;
import net.enthusia.frontier.application.StorageReclaimPort;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Chunk deletion through the three live IOWorkers on the snapshot-8 Frontier fork. */
public final class Snapshot8StorageAdapter implements StorageReclaimPort {
    private static final String STORAGE = "net.minecraft.world.level.chunk.storage.";
    private final Class<?> level = Class.forName("net.minecraft.server.level.ServerLevel");
    private final Class<?> chunkMap = Class.forName("net.minecraft.server.level.ChunkMap");
    private final Class<?> section = Class.forName(STORAGE + "SectionStorage");
    private final Class<?> simple = Class.forName(STORAGE + "SimpleRegionStorage");
    private final Class<?> io = Class.forName(STORAGE + "IOWorker");
    private final Class<?> pos = Class.forName("net.minecraft.world.level.ChunkPos");
    private final Constructor<?> position = pos.getConstructor(int.class, int.class);
    private final Method source = level.getMethod("getChunkSource");
    private final Field terrain = source.getReturnType().getField("chunkMap");
    private final Method entities = level.getMethod("getEntityManager");
    private final Field permanent = entities.getReturnType().getField("permanentStorage");
    private final Field entityStorage = field(Class.forName(STORAGE + "EntityStorage"), "simpleRegionStorage");
    private final Method poi = level.getMethod("getPoiManager");
    private final Field poiStorage = field(section, "simpleRegionStorage");
    private final Field worker = field(simple, "worker");
    private final Field files = field(io, "storage");
    private final Field directory = field(files.getType(), "folder");
    private final Method store = io.getMethod("store", pos, Class.forName("net.minecraft.nbt.CompoundTag"));
    private final Method sync = io.getMethod("synchronize", boolean.class);
    private final Field updating = field(chunkMap, "updatingChunkMap");
    private final Field unloading = field(chunkMap, "pendingUnloads");
    private final Field writing = field(chunkMap, "activeChunkWrites");
    private final Field entityStatuses = field(entities.getReturnType(), "chunkLoadStatuses");
    private final Field loadedPoi = field(section, "loadedChunks");
    private final Field pendingPoi = field(section, "pendingLoads");
    private final Field poiLock = field(section, "loadLock");

    public Snapshot8StorageAdapter() throws ReflectiveOperationException { }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private Object handle(String name, String expected) throws ReflectiveOperationException {
        World world = Bukkit.getWorld(name);
        if (!Bukkit.isPrimaryThread() || world == null || !world.getUID().equals(UUID.fromString(expected))) {
            throw new IllegalStateException("Cleanup requires the expected world on its server thread");
        }
        return level.cast(world.getClass().getMethod("getHandle").invoke(world));
    }

    private List<Object> workers(Object world) throws ReflectiveOperationException {
        Object chunks = terrain.get(source.invoke(world));
        Object entity = entityStorage.get(permanent.get(entities.invoke(world)));
        Object points = poiStorage.get(poi.invoke(world));
        return List.of(worker.get(chunks), worker.get(entity), worker.get(points));
    }

    @Override
    public boolean isStorageIdle(String name, ChunkKey key) throws Exception {
        Object world = handle(name, key.worldUuid());
        Object chunks = terrain.get(source.invoke(world));
        long packed = Integer.toUnsignedLong(key.x()) | ((long) key.z() << 32);
        Object points = poi.invoke(world);
        synchronized (poiLock.get(points)) {
            return !((Map<?, ?>) updating.get(chunks)).containsKey(packed)
                    && !((Map<?, ?>) unloading.get(chunks)).containsKey(packed)
                    && ((AtomicInteger) writing.get(chunks)).get() == 0
                    && !((Map<?, ?>) entityStatuses.get(entities.invoke(world))).containsKey(packed)
                    && !((Set<?>) loadedPoi.get(points)).contains(packed)
                    && !((Map<?, ?>) pendingPoi.get(points)).containsKey(packed);
        }
    }

    @Override
    public void clearChunk(String name, ChunkKey key) throws Exception {
        Object coordinate = position.newInstance(key.x(), key.z());
        List<Object> stores = workers(handle(name, key.worldUuid()));
        for (Object target : stores) {
            folder(target, RegionKey.fromChunk(key));
        }
        // Remove auxiliary records first. Only report success after all three durable flushes.
        for (int index : List.of(1, 2, 0)) {
            Object target = stores.get(index);
            ((CompletableFuture<?>) store.invoke(target, coordinate, null)).join();
            ((CompletableFuture<?>) sync.invoke(target, true)).join();
        }
    }

    private Path folder(Object target, RegionKey region) throws Exception {
        Path path = (Path) directory.get(files.get(target));
        if (McaRegionFileInspector.hasAlternativeContainer(path, region.x(), region.z())) {
            throw new IllegalStateException("Unsupported chunk container under " + path);
        }
        return path;
    }

    @Override
    public void flushWorld(String name, String uuid) throws Exception {
        for (Object target : workers(handle(name, uuid))) {
            ((CompletableFuture<?>) sync.invoke(target, true)).join();
        }
    }

    @Override
    public boolean primaryChunkDataPresent(String name, ChunkKey key) throws Exception {
        RegionKey region = RegionKey.fromChunk(key);
        Path path = folder(workers(handle(name, key.worldUuid())).getFirst(), region);
        return McaRegionFileInspector.containsChunk(
                path.resolve(McaRegionFileInspector.regionName(region.x(), region.z())), key.x(), key.z());
    }

    @Override
    public List<ChunkKey> occupiedChunks(String name, RegionKey region) throws Exception {
        flushWorld(name, region.worldUuid());
        Set<ChunkKey> result = new LinkedHashSet<>();
        for (Object target : workers(handle(name, region.worldUuid()))) {
            Path path = folder(target, region);
            Path file = path.resolve(McaRegionFileInspector.regionName(region.x(), region.z()));
            for (var coordinate : McaRegionFileInspector.occupiedChunks(path, file, region.x(), region.z())) {
                result.add(new ChunkKey(region.worldUuid(), coordinate.x(), coordinate.z()));
            }
        }
        return List.copyOf(result);
    }

    @Override public String adapterName() { return "frontier-snapshot8-io-worker"; }
    @Override public boolean supportsPhysicalReclaim() { return false; }
    @Override public RegionReclaimResult reclaimEmptyRegion(String name, RegionKey region) {
        return RegionReclaimResult.UNSUPPORTED;
    }
}
