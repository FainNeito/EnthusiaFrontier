package net.enthusia.frontier.adapter.bukkit;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import net.enthusia.frontier.domain.ChunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LumaClaimLookupTest {
    @Test
    void readsLiveOwnershipAcrossNegativeChunkBoundaries(@TempDir Path directory) throws Exception {
        Path db = directory.resolve("claims.db");
        assertThrows(SQLException.class, () -> new LumaClaimLookup(db));
        try (var writer = DriverManager.getConnection("jdbc:sqlite:" + db);
             var sql = writer.createStatement()) {
            sql.execute("CREATE TABLE claims(id TEXT, world_id TEXT)");
            sql.execute("CREATE TABLE claim_partitions(claim_id TEXT, lower_position_x INT, upper_position_x INT, lower_position_z INT, upper_position_z INT)");
            sql.execute("INSERT INTO claims VALUES ('claim','world')");
            var left = new ChunkKey("world", -2, 0);
            try (var guard = new LumaClaimLookup(db)) {
                assertFalse(guard.isClaimed(left));
                sql.execute("INSERT INTO claim_partitions VALUES ('claim',-17,-16,15,15)");
                assertTrue(guard.isClaimed(left));
                assertTrue(guard.isClaimed(new ChunkKey("world", -1, 0)));
                assertFalse(guard.isClaimed(new ChunkKey("other", -2, 0)));
                assertFalse(guard.isClaimed(new ChunkKey("world", -2, 1)));
                sql.execute("DELETE FROM claim_partitions");
                assertFalse(guard.isClaimed(left));
                sql.execute("DROP TABLE claims");
                assertThrows(IllegalStateException.class, () -> guard.isClaimed(left));
            }
        }
    }
}
