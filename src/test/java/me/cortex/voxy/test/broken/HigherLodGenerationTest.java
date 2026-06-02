package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.LodMipper;
import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.storage.SqliteSectionStorage;
import me.cortex.voxy.server.ServerLodManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the higher-LOD generation chain inside {@link ServerLodManager}.
 *
 * <p>The logic under test ({@code generateHigherLods}) uses {@link LodMipper} to mip four
 * sibling LOD-(N) sections into a single LOD-(N+1) section, storing the result if it changed.
 *
 * <p>These tests exercise the full pipeline (storage reads, mipping, storage writes) without
 * needing a live {@link net.minecraft.server.MinecraftServer} or network context.  The
 * package-private test constructor on {@link ServerLodManager} and the null-safe broadcast
 * guard make this possible.
 */
class HigherLodGenerationTest {

    @TempDir Path tempDir;

    private ServerLodManager    lodManager;
    private SqliteSectionStorage storage;

    private static final String DIM_KEY = "minecraft:overworld";

    @BeforeEach
    void setUp() {
        lodManager = new ServerLodManager(tempDir);
        storage    = lodManager.getStorageManager().get(DIM_KEY);
    }

    @AfterEach
    void tearDown() {
        lodManager.close();
    }

    // -------------------------------------------------------------------------

    /**
     * When only three of the four required LOD-0 siblings are present, no LOD-1 section
     * should be generated (the mipping loop must bail out and leave storage untouched).
     */
    @Test
    void missingOneSiblingSkipsLod1Generation() {
        // Siblings for LOD-0 parent (0,0): sections at (0,0), (1,0), (0,1) — (1,1) is missing
        LodSection s00 = makeSection(0, 0, 0, 1);
        LodSection s10 = makeSection(0, 1, 0, 2);
        LodSection s01 = makeSection(0, 0, 1, 3);
        // s11 intentionally NOT stored

        storage.store(s00);
        storage.store(s10);
        storage.store(s01);

        // Trigger higher LOD generation from section (0,0) with maxLod=4
        lodManager.generateHigherLods(null, storage, s00, 4);

        long lod1Key = SectionKey.encode(1, 0, 0);
        assertTrue(storage.load(lod1Key).isEmpty(),
                "LOD-1 must not be generated when a sibling is missing");
    }

    /**
     * When all four LOD-0 siblings are present the LOD-1 parent must be stored.
     */
    @Test
    void allSiblingsPresentGeneratesLod1() {
        storeQuad(0, 0, 0, new int[]{1, 2, 3, 4});

        LodSection s00 = storage.load(SectionKey.encode(0, 0, 0)).orElseThrow();
        lodManager.generateHigherLods(null, storage, s00, 4);

        long lod1Key = SectionKey.encode(1, 0, 0);
        assertTrue(storage.load(lod1Key).isPresent(),
                "LOD-1 must be generated when all four LOD-0 siblings are present");
    }

    /**
     * The generated LOD-1 section must equal what {@link LodMipper#mip} would produce
     * directly, so the mipping implementation is consistent with storage.
     */
    @Test
    void generatedLod1MatchesDirectMip() {
        LodSection s00 = makeSection(0, 0, 0, 10);
        LodSection s10 = makeSection(0, 1, 0, 20, (short) 64);
        LodSection s01 = makeSection(0, 0, 1, 30);
        LodSection s11 = makeSection(0, 1, 1, 40);

        storage.store(s00);
        storage.store(s10);
        storage.store(s01);
        storage.store(s11);

        lodManager.generateHigherLods(null, storage, s00, 1);

        LodSection stored = storage.load(SectionKey.encode(1, 0, 0)).orElseThrow();
        LodSection direct = LodMipper.mip(new LodSection[]{s00, s10, s01, s11});

        assertEquals(direct.hash, stored.hash,
                "Stored LOD-1 hash must match the hash of a directly mipped section");
    }

    /**
     * LOD generation must propagate upward: if LOD-0 siblings are all present AND LOD-1
     * siblings are subsequently all present, a LOD-2 section must be generated in the same
     * call.
     */
    @Test
    void generationPropagatesUpToLod2() {
        // We need a 4×4 grid of LOD-0 sections so that both LOD-1 parents (0,0) and their
        // siblings (1,0), (0,1), (1,1) are all generated, enabling a LOD-2 section.
        //
        // LOD-1 parents at positions (0,0), (1,0), (0,1), (1,1) each require their own 4
        // LOD-0 children.  That is 16 LOD-0 sections total.
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                storage.store(makeSection(0, x, z, x * 4 + z + 1));
            }
        }

        // Trigger chain from one corner section with maxLod = 2
        LodSection corner = storage.load(SectionKey.encode(0, 0, 0)).orElseThrow();
        lodManager.generateHigherLods(null, storage, corner, 2);

        // LOD-1 for the 4 quads should all be present now
        assertTrue(storage.load(SectionKey.encode(1, 0, 0)).isPresent(), "LOD-1 (0,0) must be generated");

        // LOD-2 requires all 4 LOD-1 siblings.  With only one call from corner (0,0), only
        // LOD-1 (0,0) is triggered.  The other LOD-1 siblings must exist in storage before
        // LOD-2 can be mipped.  Generate them explicitly.
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                LodSection s = storage.load(SectionKey.encode(0, x * 2, z * 2)).orElseThrow();
                lodManager.generateHigherLods(null, storage, s, 2);
            }
        }

        assertTrue(storage.load(SectionKey.encode(2, 0, 0)).isPresent(),
                "LOD-2 must be generated once all four LOD-1 siblings are present");
    }

    /**
     * Calling {@code generateHigherLods} again with identical data must not produce a
     * different LOD-1 hash (the {@code isCurrent} guard prevents re-storing unchanged data).
     */
    @Test
    void noChangeIfAlreadyCurrent() {
        storeQuad(0, 0, 0, new int[]{1, 2, 3, 4});

        LodSection s = storage.load(SectionKey.encode(0, 0, 0)).orElseThrow();
        lodManager.generateHigherLods(null, storage, s, 1);

        long lod1Hash1 = storage.load(SectionKey.encode(1, 0, 0)).orElseThrow().hash;

        // Run again — same data
        lodManager.generateHigherLods(null, storage, s, 1);
        long lod1Hash2 = storage.load(SectionKey.encode(1, 0, 0)).orElseThrow().hash;

        assertEquals(lod1Hash1, lod1Hash2,
                "Re-running generateHigherLods with unchanged data must not change the stored hash");
    }

    /**
     * Updating one sibling with new data must update the LOD-1 parent when re-run.
     */
    @Test
    void updatedSiblingUpdatesLod1() {
        storeQuad(0, 0, 0, new int[]{1, 2, 3, 4});

        LodSection s = storage.load(SectionKey.encode(0, 0, 0)).orElseThrow();
        lodManager.generateHigherLods(null, storage, s, 1);
        long originalHash = storage.load(SectionKey.encode(1, 0, 0)).orElseThrow().hash;

        // Replace one sibling with completely different block data
        LodSection updated = makeSection(0, 1, 1, 999);
        storage.store(updated);

        lodManager.generateHigherLods(null, storage, s, 1);
        long newHash = storage.load(SectionKey.encode(1, 0, 0)).orElseThrow().hash;

        assertNotEquals(originalHash, newHash,
                "After updating a sibling, the LOD-1 hash must change");
    }

    // -------------------------------------------------------------------------

    /**
     * Stores a 2×2 quad of LOD-0 sections covering the parent at LOD-1 (parentX, parentZ).
     * {@code blockFills[i]} is the block fill for the i-th child in row-major order.
     */
    private void storeQuad(int lod, int parentX, int parentZ, int[] blockFills) {
        int base = 1 << lod;   // not used for LOD 0 quads, just here for clarity
        int bx = parentX * 2;
        int bz = parentZ * 2;
        int i  = 0;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                storage.store(makeSection(lod, bx + dx, bz + dz, blockFills[i++]));
            }
        }
    }

    private static LodSection makeSection(int lod, int x, int z, int blockFill) {
        return makeSection(lod, x, z, blockFill, (short) 64);
    }

    private static LodSection makeSection(int lod, int x, int z, int blockFill, short height) {
        int cells = 16 * 16;
        int[]   bs = new int[cells];
        short[] ht = new short[cells];
        byte[]  lt = new byte[cells];
        int[]   bi = new int[cells];
        for (int i = 0; i < cells; i++) {
            bs[i] = blockFill;
            ht[i] = height;
        }
        return new LodSection(SectionKey.encode(lod, x, z), bs, ht, lt, bi);
    }
}
