package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import java.util.WeakHashMap;

/**
 * Converts a Minecraft chunk section (PalettedContainer of BlockStates + biome data + lighting)
 * into Voxy's flat {@link VoxelizedSection} format.
 *
 * Design overview
 * ───────────────
 * A Minecraft section stores 4096 block states in a palette-compressed bitfield.  Reading each
 * block via the public API (PalettedContainer.get(x,y,z)) is safe but slow because it locks and
 * does index arithmetic on every call.  Instead we:
 *   1. Build a local int[] palette cache (palette index → Voxy blockId) once per section.
 *   2. Read the raw backing BitStorage directly in a tight loop, unpacking bits manually.
 *   3. Combine block id, biome id and lighting into a single long per block via Mapper.composeMappingId().
 *
 * This produces a flat long[4096] that the renderer and mesh-builder can consume without
 * touching any Minecraft internals.
 *
 * Thread safety
 * ─────────────
 * All mutable state lives in a ThreadLocal Cache, so convert() is safe to call from any
 * thread without synchronisation as long as each thread has its own Cache instance.
 */
public class WorldConversionFactory {

    /**
     * Per-thread scratch storage.  Allocated once per thread and reused across calls to
     * avoid GC pressure from temporary int[] / long[] allocations.
     */
    private static final class Cache {
        /** Biome ids for the 4×4×4 biome grid of a single section. */
        private final int[] biomeCache = new int[4*4*4];

        /**
         * Per-Mapper block-state → Voxy blockId cache.  Keyed by identity so different
         * Mappers (different worlds) keep separate caches.  WeakHashMap so the cache
         * doesn't prevent GC of a Mapper after a world unload.
         */
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();

        /** Palette entry → Voxy blockId translation table.  Grown on demand. */
        private int[] paletteCache = new int[1024];

        /**
         * Used only when shouldZoom=true: holds the "zoomed" biome assignment for each of
         * the 5×5×5 virtual cells used to smooth biome borders during LOD mip generation.
         * Currently unused (computeZoomCells is a stub).
         */
        private final long[] zoomCellCache = new long[5*5*5];

        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }

        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    /**
     * Translate a section's palette into a flat int[] of Voxy blockIds.
     *
     * Why the palette type dispatch?
     * ──────────────────────────────
     * Minecraft uses different Palette implementations depending on how many unique block
     * states are in the section:
     *   SingleValuePalette — only one state (e.g. completely stone or completely air).
     *   LinearPalette      — small number of states (≤16), stored as a simple array.
     *   HashMapPalette     — larger sections; internal map from int → BlockState.
     *   GlobalPalette      — the full block state registry; used when the section has more
     *                        unique states than the max palette can hold.
     *
     * Mods (e.g. Lithium) can replace HashMapPalette with a custom implementation.  The
     * original code threw IllegalStateException on any unknown type.  The generic fallback
     * at the end uses the Palette<T> interface contract (getSize() + valueFor(int)) which
     * all implementations must honour, so we handle any mod-replaced palette safely.
     *
     * @param vp          The palette to translate.
     * @param blockCache  Per-thread BlockState → Voxy blockId cache (avoids repeated Mapper lookups).
     * @param mapper      The Mapper for the current world/dimension.
     * @param pc          Output: pc[i] = Voxy blockId for palette entry i.
     * @return The number of valid palette entries (= vp.getSize()).
     */
    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (vp instanceof LinearPalette<BlockState>) {
            // Linear palette: entries are densely packed in order — no gaps, safe to iterate.
            for (int i = 0; i < vp.getSize(); i++) {
                var state = vp.valueFor(i);
                int blockId = -1;
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof HashMapPalette<BlockState> pal) {
            //var map = pal.map;
            //TODO: heavily optimize this by reading the map directly

            // HashMapPalette may have holes (valueFor can return null for unused slots).
            // We try-catch per entry because some modded implementations can throw on
            // out-of-bounds indices when the palette has been partially invalidated.
            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }

        } else if (vp instanceof SingleValuePalette<BlockState>) {
            // Only one block state in the whole section; only entry 0 matters.
            int blockId = -1;
            var state = vp.valueFor(0);
            if (state != null) {
                blockId = blockCache.getOrDefault(state, -1);
                if (blockId == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
            }
            pc[0] = blockId;
        } else {
            // Generic fallback for mod-replaced palettes (e.g. Lithium's LithiumHashPalette).
            // All Palette implementations expose getSize() + valueFor(int), so we use those directly.
            // We wrap valueFor in a try-catch for the same reason as HashMapPalette above.
            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception ignored) {}
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }
        }
        return c;
    }

    /** Convert without biome zoom (the common case). */
    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, blockContainer, biomeContainer, lightSupplier, false, 0);
    }

    /**
     * Convert a Minecraft section into Voxy's flat voxel format.
     *
     * @param section         Output section to write into (reused across calls to avoid alloc).
     * @param stateMapper     World-specific block/biome ID registry.
     * @param blockContainer  Raw Minecraft PalettedContainer with block states.
     * @param biomeContainer  4×4×4 biome grid (each biome cell covers a 4×4×4 block region).
     * @param lightSupplier   Provides packed sky+block light for each (x,y,z) in [0,15]³.
     * @param shouldZoom      True if biome borders should be zoomed (currently a stub).
     * @param zoomSeed        Seed for biome zoom RNG (unused until stub is implemented).
     */
    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        //Cheat by creating a local pallet then read the data directly
        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;      // the 4096-element flat array we are filling
        var zoomCells = cache.zoomCellCache;

        // Translate the section's palette into the flat pc[] array.
        // If the palette is GlobalPalette (full registry), skip pc[] and query the mapper inline.
        var vp = blockContainer.data.palette();
        var pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;

        int pcc = 0;
        if (blockContainer.data.palette() instanceof GlobalPalette<BlockState> _bps) {
            // GlobalPalette: section has more unique states than palette can compress.
            // Each raw bitfield value IS the global block state ID — map each one individually.
            bps = _bps;
            pcc = bps.getSize();
        } else {
            pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
            // pcc-1 = max valid palette index (used as a clamp guard below when accessing pc[]).
            pcc = Math.max(0,pcc-1);
        }

        // ── Biome grid ─────────────────────────────────────────────────────────────────────────
        // The biome container is 4×4×4 cells (each cell = 4×4×4 blocks within the 16×16×16 section).
        // We pre-bake the 64-cell grid into biomes[] using Voxy biome IDs so the inner block
        // loop can index it with a single Integer.compress() call.
        {
            int i = 0;
            int inital = -1;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int bid = stateMapper.getIdForBiome(biomeContainer.get(x, y, z));
                        biomes[i++] = bid;
                        if (inital==-1) inital = bid;
                        // Optimisation: if all 64 biome cells are identical, skip the zoom pass.
                        // Evil hacky trick, we only need to zoom if on a biome boarder
                        shouldZoom &= inital == bid;
                    }
                }
            }

            if (shouldZoom) {
                // computeZoomCells is currently a stub; zoom is disabled pending implementation.
                computeZoomCells(biomes, zoomSeed, zoomCells);
            }
        }


        // ── Block data fast path: SimpleBitStorage ─────────────────────────────────────────────
        // SimpleBitStorage stores N-bit palette indices in packed longs.  We decode them manually
        // to avoid the per-call overhead of the public PalettedContainer.get(x,y,z) API:
        //   - 'bDat'        : the raw backing long array.
        //   - 'eBits'       : bits per entry (e.g. 4 for a 16-entry palette).
        //   - 'iterPerLong' : how many entries fit in one long minus 1 (used as a down-counter).
        //   - 'MSK'         : mask for extracting one entry (= (1<<eBits)-1).
        //   - 'sample'      : the current 64-bit word being unpacked.
        //   - 'dec'         : countdown; when it hits 0 we load the next word.
        //
        // Biome index via Integer.compress():
        //   The block index i runs 0..4095 encoded as (x | (y<<8) | (z<<4)).
        //   The biome grid is 4×4×4, so the biome cell for block (x,y,z) uses bits 2,3 of x,y,z.
        //   Integer.compress(i, 0b1100_1100_1100) extracts bits 2,3,6,7,10,11 of i into a
        //   6-bit index, mapping the 16³ block grid to the 4³ biome grid.
        int nonZeroCnt = 0;
        if (blockContainer.data.storage() instanceof SimpleBitStorage bStor) {
            var bDat = bStor.getRaw();
            int iterPerLong = (64 / bStor.getBits()) - 1;

            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                // Load the next long word when the current one is exhausted.
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId;
                if (bps == null) {
                    // Local palette: clamp sample to valid range (pcc = max index) to guard against
                    // out-of-bounds reads if the bit storage has trailing garbage bits.
                    bId = pc[Math.min((int) (sample & MSK), pcc)];
                } else {
                    // Global palette: the raw index IS the global block state ID.
                    bId = stateMapper.getIdForBlockState(bps.valueFor((int) (sample&MSK)));
                }
                sample >>>= eBits; // advance to the next entry within this long

                // Lighting: lightSupplier.supply(x, y, z) returns packed sky+block nibbles.
                // Block index layout: i = x | (y<<8) | (z<<4)  → extract each axis.
                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                // Combine block id, lighting and biome id into the long packed format Voxy uses.
                // Integer.compress extracts the high bits of each coordinate to index the 4×4×4 biome grid.
                data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
            }
        } else {
            // ── ZeroBitStorage fast path ───────────────────────────────────────────────────────
            // ZeroBitStorage means ALL 4096 blocks have the same single palette entry (pc[0]).
            // We don't need to unpack anything; just fill every slot uniformly.
            if (!(blockContainer.data.storage() instanceof ZeroBitStorage)) {
                throw new IllegalStateException();
            }
            int bId = pc[0];
            if (bId == 0) {
                // Section is entirely air — write air+light for each block.
                for (int i = 0; i <= 0xFFF; i++) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF));
                }
            } else {
                // Section is uniformly filled with a single non-air block (e.g. a stone sphere region).
                nonZeroCnt = 4096;
                for (int i = 0; i <= 0xFFF; i++) {
                    byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                    data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;

        // Fix thin-block-on-slab height: blocks like snow placed on half-height slabs
        // (e.g. from the Terrain Slabs mod) appear one full LOD-unit too high because the
        // LOD has no sub-voxel Y resolution.  Collapse thin surface blocks (topFaceY < 0.3)
        // that sit directly on partial-height blocks (0.4 < topFaceY < 0.95) down one Y
        // level so the surface texture appears at the correct elevation.
        // Block index encoding: i = x | (y << 8) | (z << 4)  → one Y step = +256.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 15; y++) {
                    int idx      = x | (y << 8) | (z << 4);
                    int idxAbove = idx + 256;

                    int bId      = Mapper.getBlockId(data[idx]);
                    int bIdAbove = Mapper.getBlockId(data[idxAbove]);
                    if (bId == 0 || bIdAbove == 0) continue;

                    var entry      = stateMapper.getStateEntry(bId);
                    var entryAbove = stateMapper.getStateEntry(bIdAbove);

                    // slab-like block below, thin surface block above
                    if (entry.topFaceY > 0.4f && entry.topFaceY < 0.95f
                            && entryAbove.topFaceY > 0.0f && entryAbove.topFaceY < 0.3f) {
                        // Replace the slab voxel with the thin block's id/biome, keep slab's light.
                        int  light  = Mapper.getLightId(data[idx]);
                        int  biome  = Mapper.getBiomeId(data[idx]);
                        data[idx]      = Mapper.composeMappingId((byte) light, bIdAbove, biome);
                        data[idxAbove] = Mapper.airWithLight(Mapper.getLightId(data[idxAbove]));
                    }
                }
            }
        }

        return section;
    }


    /**
     * Stub: compute zoomed biome assignments for the 5×5×5 virtual cell grid.
     * Zoom smooths biome borders at LOD level boundaries so high-LOD sections
     * don't show hard biome-colour edges. Not yet implemented.
     */
    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy<4; cy++) {
            for (int cz = 0; cz<4; cz++) {
                for (int cx = 0; cx<4; cx++) {
                    // TODO: implement Minecraft-style zoom interpolation
                }
            }
        }
    }

    /** Compatibility shim for external callers that use the old entry point. */
    @Deprecated(forRemoval = true)
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }
}
