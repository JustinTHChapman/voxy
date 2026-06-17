package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Computes LOD light levels for a section directly from its block data + the chunk heightmap, rather
 * than reading Minecraft's light engine.
 *
 * <p>Why: LOD light must reflect the environment (sky exposure, emissive blocks) and is then animated
 * by Minecraft's live lightmap at render time (time-of-day, gamma, dimension, ...). The vanilla light
 * engine is unreliable as a source for LODs — distant chunks are force-loaded at the non-ticking FULL
 * border and returned before their light is finalized, so the nibbles come back null and LODs bake
 * black. Computing the levels ourselves is independent of chunk-load/light-engine state, never dark,
 * and can never stall generation.</p>
 *
 * <p>The result is the same {@code (sky, block)} nibble levels the renderer expects; the cross-chunk
 * exactness of the vanilla engine is traded for consistency and reliability at LOD distance (caves and
 * overhangs that span unloaded neighbours read brighter than they truly are).</p>
 *
 * <p>Index convention matches the rest of voxy: {@code i = x | (y<<8) | (z<<4)}.</p>
 */
public final class LodLighting {
    private LodLighting() {}

    /** Computed sky + block light layers for one section; either layer may be null (all zero). */
    public record SectionLight(DataLayer skyLight, DataLayer blockLight) {}

    private static final boolean[] ALL_TRANSMIT = new boolean[4096];
    static { java.util.Arrays.fill(ALL_TRANSMIT, true); }

    public static SectionLight compute(LevelChunkSection section, int sectionY, Heightmap heightmap) {
        if (section.hasOnlyAir()) {
            // All air: light passes through every cell and there are no emitters — only skylight,
            // and we can skip the per-cell occlusion scan entirely.
            return new SectionLight(computeSkyLight(sectionY, heightmap, ALL_TRANSMIT), null);
        }
        boolean[] transmits = transmitMask(section);
        return new SectionLight(
                computeSkyLight(sectionY, heightmap, transmits),
                computeBlockLight(section, transmits));
    }

    /** Per-cell: does light pass through this cell? (air, or any non-fully-occluding block: water, glass, leaves, slabs). */
    private static boolean[] transmitMask(LevelChunkSection section) {
        boolean[] t = new boolean[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var s = section.getBlockState(x, y, z);
                    t[x | (y << 8) | (z << 4)] = s.isAir() || !s.canOcclude();
                }
            }
        }
        return t;
    }

    /** Sky light: every transmitting cell at or above the column's surface starts at 15, then floods
     *  (decrement per step) through transmitting cells so it dims into caves/overhangs. */
    private static DataLayer computeSkyLight(int sectionY, Heightmap heightmap, boolean[] transmits) {
        byte[] light = new byte[4096];
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        int baseY = sectionY << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int surfaceY = heightmap.getFirstAvailable(x, z); // first sky-exposed Y of this column
                for (int y = 0; y < 16; y++) {
                    int i = x | (y << 8) | (z << 4);
                    if (transmits[i] && baseY + y >= surfaceY) {
                        light[i] = 15;
                        queue.enqueue(i);
                    }
                }
            }
        }
        flood(light, transmits, queue);
        return toDataLayer(light);
    }

    /** Block light: each light-emitting block seeds its emission, then floods (decrement per step)
     *  through transmitting cells. Returns null when the section has no light-emitting blocks. */
    private static DataLayer computeBlockLight(LevelChunkSection section, boolean[] transmits) {
        if (!section.maybeHas(s -> s.getLightEmission() > 0)) return null;
        byte[] light = new byte[4096];
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int e = section.getBlockState(x, y, z).getLightEmission();
                    if (e > 0) {
                        int i = x | (y << 8) | (z << 4);
                        light[i] = (byte) e;
                        queue.enqueue(i);
                    }
                }
            }
        }
        flood(light, transmits, queue);
        return toDataLayer(light);
    }

    /** Standard decrement flood bounded to the 16³ section. */
    private static void flood(byte[] light, boolean[] transmits, IntArrayFIFOQueue queue) {
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            int nv = light[i] - 1;
            if (nv <= 0) continue;
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
            if (x > 0)  prop(light, transmits, queue, i - 1,   nv);
            if (x < 15) prop(light, transmits, queue, i + 1,   nv);
            if (z > 0)  prop(light, transmits, queue, i - 16,  nv);
            if (z < 15) prop(light, transmits, queue, i + 16,  nv);
            if (y > 0)  prop(light, transmits, queue, i - 256, nv);
            if (y < 15) prop(light, transmits, queue, i + 256, nv);
        }
    }

    private static void prop(byte[] light, boolean[] transmits, IntArrayFIFOQueue queue, int ni, int nv) {
        if (transmits[ni] && light[ni] < nv) {
            light[ni] = (byte) nv;
            queue.enqueue(ni);
        }
    }

    private static DataLayer toDataLayer(byte[] light) {
        DataLayer dl = null;
        for (int i = 0; i < 4096; i++) {
            if (light[i] > 0) {
                if (dl == null) dl = new DataLayer();
                dl.set(i & 15, (i >> 8) & 15, (i >> 4) & 15, light[i]);
            }
        }
        return dl;
    }
}
