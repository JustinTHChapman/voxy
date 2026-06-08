package me.cortex.voxy.common.config.storage.sqlite;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.function.LongConsumer;

/**
 * SQLite-backed storage backend for LOD section data and id mappings.
 * Uses org.xerial:sqlite-jdbc which is bundled via jarJar.
 */
public class SQLiteStorageBackend extends StorageBackend {

    private final Connection connection;
    private final PreparedStatement stmtGetSection;
    private final PreparedStatement stmtPutSection;
    private final PreparedStatement stmtDeleteSection;
    private final PreparedStatement stmtGetMapping;
    private final PreparedStatement stmtPutMapping;
    private final PreparedStatement stmtGetAllMappings;
    private final PreparedStatement stmtIterateAll;
    private final PreparedStatement stmtIterateLevel;
    private final PreparedStatement stmtContainsSection;

    public SQLiteStorageBackend(String path) {
        try {
            // Use SQLiteDataSource directly to avoid NeoForge modular classloader
            // issues with DriverManager's ServiceLoader-based driver discovery.
            org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + path);
            this.connection = ds.getConnection();

            // Performance pragmas
            try (Statement st = this.connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA cache_size=-65536"); // 64 MB cache
                st.execute("PRAGMA temp_store=MEMORY");
                st.execute("CREATE TABLE IF NOT EXISTS world_sections (key INTEGER PRIMARY KEY, data BLOB NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS id_mappings (key INTEGER PRIMARY KEY, data BLOB NOT NULL)");
            }

            this.stmtGetSection    = this.connection.prepareStatement("SELECT data FROM world_sections WHERE key=?");
            this.stmtPutSection    = this.connection.prepareStatement("INSERT OR REPLACE INTO world_sections(key,data) VALUES(?,?)");
            this.stmtDeleteSection = this.connection.prepareStatement("DELETE FROM world_sections WHERE key=?");
            this.stmtGetMapping    = this.connection.prepareStatement("SELECT data FROM id_mappings WHERE key=?");
            this.stmtPutMapping    = this.connection.prepareStatement("INSERT OR REPLACE INTO id_mappings(key,data) VALUES(?,?)");
            this.stmtGetAllMappings= this.connection.prepareStatement("SELECT key,data FROM id_mappings");
            this.stmtIterateAll      = this.connection.prepareStatement("SELECT key FROM world_sections");
            // Level occupies the top 4 bits (bits 60-63) of the 64-bit key
            // level range: key >= (level<<60), key < ((level+1)<<60)
            this.stmtIterateLevel    = this.connection.prepareStatement(
                    "SELECT key FROM world_sections WHERE key >= ? AND key < ?");
            this.stmtContainsSection = this.connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM world_sections WHERE key=?)");

            Logger.info("SQLiteStorageBackend opened database: " + path);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open SQLite database at: " + path, e);
        }
    }

    @Override
    public synchronized MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        try {
            this.stmtGetSection.setLong(1, key);
            try (ResultSet rs = this.stmtGetSection.executeQuery()) {
                if (!rs.next()) return null;
                byte[] bytes = rs.getBytes(1);
                if (bytes == null || bytes.length == 0) return null;
                if (bytes.length > scratch.size) {
                    // Shouldn't normally happen; caller provides adequate scratch
                    return null;
                }
                MemoryUtil.memByteBuffer(scratch.address, bytes.length).put(bytes);
                return scratch.subSize(bytes.length);
            }
        } catch (SQLException e) {
            Logger.error("SQLite getSectionData failed for key " + key, e);
            return null;
        }
    }

    @Override
    public synchronized void setSectionData(long key, MemoryBuffer data) {
        try {
            byte[] bytes = new byte[(int) data.size];
            MemoryUtil.memByteBuffer(data.address, (int) data.size).get(bytes);
            this.stmtPutSection.setLong(1, key);
            this.stmtPutSection.setBytes(2, bytes);
            this.stmtPutSection.executeUpdate();
        } catch (SQLException e) {
            Logger.error("SQLite setSectionData failed for key " + key, e);
        }
    }

    @Override
    public synchronized void deleteSectionData(long key) {
        try {
            this.stmtDeleteSection.setLong(1, key);
            this.stmtDeleteSection.executeUpdate();
        } catch (SQLException e) {
            Logger.error("SQLite deleteSectionData failed for key " + key, e);
        }
    }

    @Override
    public synchronized boolean containsSection(long key) {
        try {
            stmtContainsSection.setLong(1, key);
            try (ResultSet rs = stmtContainsSection.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            Logger.error("SQLite containsSection failed for key " + key, e);
            return false;
        }
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        try {
            ResultSet rs;
            if (level == -1) {
                rs = this.stmtIterateAll.executeQuery();
            } else {
                long minKey = (long) level << 60;
                long maxKey = (long) (level + 1) << 60;
                this.stmtIterateLevel.setLong(1, minKey);
                this.stmtIterateLevel.setLong(2, maxKey);
                rs = this.stmtIterateLevel.executeQuery();
            }
            try (rs) {
                while (rs.next()) {
                    consumer.accept(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            Logger.error("SQLite iteratePositions failed for level " + level, e);
        }
    }

    @Override
    public synchronized void putIdMapping(int id, ByteBuffer data) {
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            data.rewind();
            this.stmtPutMapping.setInt(1, id);
            this.stmtPutMapping.setBytes(2, bytes);
            this.stmtPutMapping.executeUpdate();
        } catch (SQLException e) {
            Logger.error("SQLite putIdMapping failed for id " + id, e);
        }
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap<>();
        try (ResultSet rs = this.stmtGetAllMappings.executeQuery()) {
            while (rs.next()) {
                int key = rs.getInt(1);
                byte[] data = rs.getBytes(2);
                if (data != null) out.put(key, data);
            }
        } catch (SQLException e) {
            Logger.error("SQLite getIdMappingsData failed", e);
        }
        return out;
    }

    @Override
    public synchronized void flush() {
        // WAL mode flushes on commit; explicit checkpoint here for safety
        try (Statement st = this.connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException e) {
            Logger.error("SQLite flush/checkpoint failed", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            this.stmtGetSection.close();
            this.stmtPutSection.close();
            this.stmtDeleteSection.close();
            this.stmtGetMapping.close();
            this.stmtPutMapping.close();
            this.stmtGetAllMappings.close();
            this.stmtIterateAll.close();
            this.stmtIterateLevel.close();
            this.stmtContainsSection.close();
            this.connection.close();
        } catch (SQLException e) {
            Logger.error("SQLite close failed", e);
        }
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            String path = ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath()));
            return new SQLiteStorageBackend(path + "/sections.db");
        }

        public static String getConfigTypeName() {
            return "SQLite";
        }
    }
}
