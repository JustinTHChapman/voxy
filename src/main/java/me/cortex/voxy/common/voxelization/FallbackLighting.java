package me.cortex.voxy.common.voxelization;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Approximate per-section light, used only as a fallback when a chunk's real light nibbles aren't
 * available.
 *
 * <p>Distant chunks force-loaded for LOD generation are returned at the non-ticking FULL border
 * (ticket level 33) before the server's threaded light engine finalizes them, so their sky/block
 * {@link DataLayer}s come back {@code null} and the LOD would otherwise bake fully black. Real
 * nibbles are always preferred; this only fills the gap so distant LODs are never dark and
 * generation never has to wait on the light engine.</p>
 */
public final class FallbackLighting {
    private FallbackLighting() {}

    /**
     * Sky light derived from the chunk heightmap: full sky (15) at or above the surface column,
     * 0 below. Approximate — no cross-chunk occlusion, and caves/overhangs read as sky-lit — but
     * never dark, which is acceptable at LOD distance.
     *
     * @param sectionY  the section's Y index (world Y of its bottom block is {@code sectionY << 4})
     * @param heightmap a primed {@link Heightmap} (e.g. {@code MOTION_BLOCKING}) for the chunk
     */
    public static DataLayer skyLightFromHeightmap(int sectionY, Heightmap heightmap) {
        DataLayer layer = new DataLayer();
        int baseY = sectionY << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int surfaceY = heightmap.getFirstAvailable(x, z); // first sky-exposed Y of this column
                for (int y = 0; y < 16; y++) {
                    if (baseY + y >= surfaceY) layer.set(x, y, z, 15);
                }
            }
        }
        return layer;
    }

    /**
     * Block light from emissive blocks in the section — each block's own emission at its own
     * position (no propagation; sufficient at LOD scale). Returns {@code null} when the section has
     * no light-emitting blocks, so callers leave block light at 0.
     */
    public static DataLayer blockLightFromEmission(LevelChunkSection section) {
        if (!section.maybeHas(s -> s.getLightEmission() > 0)) return null;
        DataLayer layer = new DataLayer();
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int e = section.getBlockState(x, y, z).getLightEmission();
                    if (e > 0) {
                        layer.set(x, y, z, e);
                        any = true;
                    }
                }
            }
        }
        return any ? layer : null;
    }
}
