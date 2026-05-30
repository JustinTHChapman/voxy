package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.storage.SectionStorageManager;
import me.cortex.voxy.common.storage.SqliteSectionStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SectionStorageManagerTest {

    @TempDir
    Path tempDir;

    SectionStorageManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new SectionStorageManager(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (mgr != null) mgr.close();
    }

    private static LodSection makeSection(int x, int z, int fill) {
        int c = 16 * 16;
        int[] bs = new int[c];
        short[] h = new short[c];
        byte[] l = new byte[c];
        int[] b = new int[c];
        for (int i = 0; i < c; i++) bs[i] = fill;
        return new LodSection(SectionKey.encode(0, x, z), bs, h, l, b);
    }

    @Test
    void differentDimensionsAreIsolated() {
        SqliteSectionStorage overworld = mgr.get("minecraft:overworld");
        SqliteSectionStorage nether    = mgr.get("minecraft:the_nether");

        long key = SectionKey.encode(0, 0, 0);
        overworld.store(makeSection(0, 0, 1));
        nether.store(makeSection(0, 0, 99));

        assertEquals(1, overworld.load(key).orElseThrow().blockStates[0]);
        assertEquals(99, nether.load(key).orElseThrow().blockStates[0]);
    }

    @Test
    void repeatedGetReturnsSameInstance() {
        SqliteSectionStorage a = mgr.get("minecraft:overworld");
        SqliteSectionStorage b = mgr.get("minecraft:overworld");
        assertSame(a, b, "Storage should be cached per dimension");
    }

    @Test
    void unloadReleasesStorage() {
        mgr.get("minecraft:overworld").store(makeSection(5, 5, 7));
        mgr.unload("minecraft:overworld");
        // After unload, get() returns a new instance but data persists on disk
        SqliteSectionStorage reopened = mgr.get("minecraft:overworld");
        assertEquals(7, reopened.load(SectionKey.encode(0, 5, 5)).orElseThrow().blockStates[0]);
    }

    @Test
    void sanitizesDimensionKeyInPath() {
        // A weird namespace must not produce invalid file paths
        assertDoesNotThrow(() -> mgr.get("my-mod:weird/space\\path"));
    }
}
