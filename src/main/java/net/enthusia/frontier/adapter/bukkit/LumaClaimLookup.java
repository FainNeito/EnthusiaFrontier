package net.enthusia.frontier.adapter.bukkit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import net.enthusia.frontier.domain.ChunkKey;

/** Reads committed LumaGuilds land partitions immediately before cleanup on the server thread. */
public final class LumaClaimLookup implements AutoCloseable {
    private final Connection connection;
    private final PreparedStatement lookup;

    public LumaClaimLookup(Path database) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri().toASCIIString()
                + "?mode=ro&busy_timeout=0");
        try {
            lookup = connection.prepareStatement(
                    "SELECT 1 FROM claims c JOIN claim_partitions p ON p.claim_id=c.id "
                    + "WHERE c.world_id=? AND p.lower_position_x<=? AND p.upper_position_x>=? "
                    + "AND p.lower_position_z<=? AND p.upper_position_z>=? LIMIT 1");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    public boolean isClaimed(ChunkKey key) {
        long x = (long) key.x() * 16;
        long z = (long) key.z() * 16;
        try {
            lookup.setString(1, key.worldUuid());
            lookup.setLong(2, x + 15);
            lookup.setLong(3, x);
            lookup.setLong(4, z + 15);
            lookup.setLong(5, z);
            try (var rows = lookup.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not verify LumaGuilds ownership for " + key, exception);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
