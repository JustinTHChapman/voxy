package me.cortex.voxy.test;

import me.cortex.voxy.common.StorageConfigUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: the default storage backend must NOT be a backend whose
 * native dependency is excluded from the published artifact (e.g. RocksDB).
 *
 * Background: when a player launches voxy on a fresh world with no
 * config.json, voxy writes the DEFAULT_STORAGE_CONFIG to disk and then loads
 * it. If the default references RocksDB but rocksdbjni is excluded from the
 * jar, the player gets NoClassDefFoundError on first world join and MC
 * crashes. Acceptable defaults must be backends that are always loadable.
 */
class DefaultStorageBackendTest {

    @Test
    void defaultBackendIsNotRocksDB() {
        var cfg = StorageConfigUtil.createDefaultSerializer();
        String json = serializeNoNulls(cfg);
        assertFalse(json.contains("\"RocksDB\""),
            "Default storage backend must not be RocksDB (native is excluded). Config was:\n" + json);
    }

    @Test
    void defaultBackendIsAlwaysAvailable() {
        // LMDB is the only currently-available embedded backend in the build.
        var cfg = StorageConfigUtil.createDefaultSerializer();
        String json = serializeNoNulls(cfg);
        assertTrue(json.contains("\"LMDB\"") || json.contains("\"Memory\"") || json.contains("\"Sqlite\""),
            "Default storage backend should be one of {LMDB, Memory, Sqlite}. Config was:\n" + json);
    }

    private static String serializeNoNulls(Object o) {
        // Lightweight — avoid pulling in GSON/Serialization at test time which
        // would require MC class graph. Use toString-style introspection.
        StringBuilder sb = new StringBuilder();
        introspect(o, sb, 0);
        return sb.toString();
    }

    private static void introspect(Object o, StringBuilder sb, int depth) {
        if (o == null || depth > 10) { sb.append("null"); return; }
        Class<?> c = o.getClass();
        sb.append('{').append('"').append(simpleTypeName(c)).append('"');
        for (var f : c.getFields()) {
            try {
                Object v = f.get(o);
                if (v == null) continue;
                sb.append(',').append(f.getName()).append('=');
                if (v.getClass().getName().startsWith("me.cortex.voxy")) {
                    introspect(v, sb, depth + 1);
                } else {
                    sb.append(v);
                }
            } catch (IllegalAccessException ignored) {}
        }
        sb.append('}');
    }

    private static String simpleTypeName(Class<?> c) {
        // Backends expose a static getConfigTypeName() — best effort.
        try {
            var m = c.getDeclaredMethod("getConfigTypeName");
            Object r = m.invoke(null);
            if (r != null) return r.toString();
        } catch (Exception ignored) {}
        return c.getSimpleName();
    }
}
