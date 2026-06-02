package me.cortex.voxy.test;

import me.cortex.voxy.client.ClientCacheManager;
import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientCacheManager}.
 *
 * Uses the package-private {@code ClientCacheManager(Path)} constructor to bypass
 * {@link net.minecraft.client.Minecraft#getInstance()} so these run in the unit-test environment.
 */
class ClientCacheManagerTest {

    @TempDir Path tempDir;

    private ClientCacheManager cache;
    private static final ResourceLocation DIM = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeEach
    void setUp() {
        cache = new ClientCacheManager(tempDir);
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    // -------------------------------------------------------------------------

    @Test
    void storeAndLoadRoundTrip() {
        LodSection section = makeSection(0, 3, 7, 42);
        cache.store(DIM, section);

        var loaded = cache.load(DIM, section.key);
        assertTrue(loaded.isPresent(), "Stored section must be loadable");
        assertEquals(section, loaded.get(), "Loaded section must equal stored section");
    }

    @Test
    void loadReturnsEmptyForMissingSection() {
        long absentKey = SectionKey.encode(0, 99, 99);
        assertTrue(cache.load(DIM, absentKey).isEmpty(), "Load of unstored key must return empty");
    }

    @Test
    void removeDeletesSection() {
        LodSection section = makeSection(0, 1, 1, 7);
        cache.store(DIM, section);
        cache.remove(DIM, section.key);

        assertTrue(cache.load(DIM, section.key).isEmpty(), "Section must be absent after remove");
    }

    @Test
    void storeUpdatesMemoryIndex() {
        LodSection section = makeSection(0, 5, 5, 10);
        cache.store(DIM, section);

        Map<Long, Long> index = cache.getLocalHashIndex(DIM);
        assertTrue(index.containsKey(section.key), "Memory index must contain stored key");
        assertEquals(section.hash, index.get(section.key), "Stored hash must match section hash");
    }

    @Test
    void removeUpdatesMemoryIndex() {
        LodSection section = makeSection(0, 2, 8, 3);
        cache.store(DIM, section);
        cache.remove(DIM, section.key);

        Map<Long, Long> index = cache.getLocalHashIndex(DIM);
        assertFalse(index.containsKey(section.key), "Memory index must not contain key after remove");
    }

    @Test
    void getLocalHashIndexReturnsCopyNotReference() {
        LodSection section = makeSection(0, 0, 0, 1);
        cache.store(DIM, section);

        Map<Long, Long> index1 = cache.getLocalHashIndex(DIM);
        // Mutate the returned map
        index1.put(SectionKey.encode(0, 999, 999), 0xDEADBEEFL);

        // A fresh call must not reflect the external mutation
        Map<Long, Long> index2 = cache.getLocalHashIndex(DIM);
        assertFalse(index2.containsKey(SectionKey.encode(0, 999, 999)),
                "getLocalHashIndex must return a snapshot copy, not the live map");
    }

    @Test
    void warmIndexLoadsFromDisk() {
        // Store a section via the first cache instance
        LodSection section = makeSection(0, 4, 6, 55);
        cache.store(DIM, section);
        cache.close();

        // Open a new cache on the same directory (simulates a fresh JVM / reconnect)
        ClientCacheManager fresh = new ClientCacheManager(tempDir);
        try {
            // Before warming, index is empty
            Map<Long, Long> before = fresh.getLocalHashIndex(DIM);
            assertTrue(before.isEmpty(), "Fresh cache index must be empty before warmIndex");

            // After warming, index reflects what was on disk
            fresh.warmIndex(DIM);
            Map<Long, Long> after = fresh.getLocalHashIndex(DIM);
            assertTrue(after.containsKey(section.key), "warmIndex must load persisted sections");
            assertEquals(section.hash, after.get(section.key), "warmIndex must load correct hash");
        } finally {
            fresh.close();
        }
    }

    @Test
    void multiDimensionIsolation() {
        ResourceLocation dimB = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
        LodSection s = makeSection(0, 0, 0, 1);
        cache.store(DIM, s);

        Map<Long, Long> indexA = cache.getLocalHashIndex(DIM);
        Map<Long, Long> indexB = cache.getLocalHashIndex(dimB);

        assertTrue(indexA.containsKey(s.key),  "Section must appear in its dimension index");
        assertFalse(indexB.containsKey(s.key), "Section must NOT appear in another dimension's index");
    }

    @Test
    void multipleStoredSectionsAllAppearInIndex() {
        for (int i = 0; i < 20; i++) {
            cache.store(DIM, makeSection(0, i, 0, i + 1));
        }
        Map<Long, Long> index = cache.getLocalHashIndex(DIM);
        assertEquals(20, index.size(), "All stored sections must appear in the index");
    }

    // -------------------------------------------------------------------------

    private static LodSection makeSection(int lod, int x, int z, int blockFill) {
        int cells = 16 * 16;
        int[]   bs = new int[cells];
        short[] ht = new short[cells];
        byte[]  lt = new byte[cells];
        int[]   bi = new int[cells];
        for (int i = 0; i < cells; i++) bs[i] = blockFill;
        return new LodSection(SectionKey.encode(lod, x, z), bs, ht, lt, bi);
    }
}
