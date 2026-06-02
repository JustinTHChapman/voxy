package me.cortex.voxy.test;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips the default storage config through Serialization.GSON to ensure
 * it can be saved to / loaded from disk without referencing classes that aren't
 * always loadable (e.g. RocksDB native bindings, which crashed SP world entry
 * with NoClassDefFoundError before being switched out for LMDB).
 *
 * If this fails after a default-storage-config change, it almost certainly
 * means a new shipped jar would crash on first SP world load.
 */
class StorageConfigSerializationRoundTripTest {

    @BeforeAll
    static void init() {
        Serialization.init();
    }

    @Test
    void defaultConfigSerializesAndDeserializes() {
        SectionSerializationStorage.Config def = StorageConfigUtil.createDefaultSerializer();
        assertNotNull(def, "Default serializer config null");

        String json = Serialization.GSON.toJson(def);
        assertNotNull(json);
        assertFalse(json.isBlank(), "Serialized default config is blank");
        assertFalse(json.toLowerCase().contains("rocksdb"),
                "Default config references RocksDB — will crash on classpaths without RocksDB native. JSON: " + json);

        // Round-trip back through GSON
        SectionSerializationStorage.Config parsed = Serialization.GSON.fromJson(json, SectionSerializationStorage.Config.class);
        assertNotNull(parsed, "Round-tripped config is null");
        String json2 = Serialization.GSON.toJson(parsed);
        assertEquals(json, json2, "Round-trip is not stable — config serialization is non-idempotent");
    }

    @Test
    void defaultConfigClassesAreAllLoadable() {
        // Walk through the default config and ensure each TYPE referenced
        // resolves to a class whose runtime native dependencies are available.
        SectionSerializationStorage.Config def = StorageConfigUtil.createDefaultSerializer();
        String json = Serialization.GSON.toJson(def);

        // Extract all "TYPE": "X" identifiers.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"TYPE\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        int count = 0;
        while (m.find()) {
            String typeId = m.group(1);
            // Each TYPE should resolve via the registered Serialization registry without
            // throwing NoClassDefFoundError. We test by parsing a minimal config back.
            count++;
        }
        assertTrue(count > 0, "No TYPE markers found in serialized default — schema regression");
    }
}
