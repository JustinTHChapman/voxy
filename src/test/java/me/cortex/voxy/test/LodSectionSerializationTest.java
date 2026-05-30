package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodSectionSerializationTest {

    private static final int CELLS = 16 * 16;

    private static LodSection makeRandom(long key, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        int[]   bs = new int[CELLS];
        short[] ht = new short[CELLS];
        byte[]  lt = new byte[CELLS];
        int[]   bi = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            bs[i] = rng.nextInt();
            ht[i] = (short) rng.nextInt();
            lt[i] = (byte) rng.nextInt();
            bi[i] = rng.nextInt(1024);
        }
        return new LodSection(key, bs, ht, lt, bi);
    }

    @Test
    void byteRoundTripPreservesAllData() {
        LodSection original = makeRandom(SectionKey.encode(0, 12, -34), 42L);
        byte[] bytes = original.toBytes();
        LodSection restored = LodSection.fromBytes(original.key, bytes);

        assertEquals(original, restored, "round-trip must yield equal section");
        assertEquals(original.hash, restored.hash, "hash must survive round-trip");
    }

    @Test
    void byteSizeIsDeterministic() {
        LodSection a = makeRandom(SectionKey.encode(0, 0, 0), 1);
        LodSection b = makeRandom(SectionKey.encode(0, 0, 0), 2);
        assertEquals(a.toBytes().length, b.toBytes().length,
                "serialized payload size must be constant per LOD section");
    }

    @Test
    void byteSizeMatchesExpectedFormat() {
        // 256 cells * (4 + 2 + 1 + 4) bytes = 256 * 11 = 2816
        LodSection s = makeRandom(SectionKey.encode(0, 0, 0), 1);
        assertEquals(CELLS * 11, s.toBytes().length);
    }

    @Test
    void invalidArrayLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LodSection(0L, new int[10], new short[CELLS], new byte[CELLS], new int[CELLS]));
        assertThrows(IllegalArgumentException.class,
                () -> new LodSection(0L, new int[CELLS], new short[10], new byte[CELLS], new int[CELLS]));
    }

    @Test
    void equalsIsConsistentWithHashCode() {
        LodSection a = makeRandom(SectionKey.encode(0, 1, 1), 7);
        LodSection b = LodSection.fromBytes(a.key, a.toBytes());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentSectionsAreNotEqual() {
        LodSection a = makeRandom(SectionKey.encode(0, 1, 1), 7);
        LodSection b = makeRandom(SectionKey.encode(0, 1, 1), 8);
        assertNotEquals(a, b);
    }
}
