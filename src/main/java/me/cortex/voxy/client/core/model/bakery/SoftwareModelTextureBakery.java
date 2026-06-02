package me.cortex.voxy.client.core.model.bakery;

import net.minecraft.world.level.block.state.BlockState;

/**
 * MC 1.21.1 port stub. Upstream Voxy uses MC 1.26's FluidStateModelSet / BlockStateModelPart /
 * BakedQuad.materialInfo / ChunkSectionLayer / CardinalLighting which do not exist in 1.21.1.
 *
 * TODO(voxy-port-1.21.1): re-implement against 1.21.1 BakedModel/BakedQuad/FluidRenderer.
 */
public class SoftwareModelTextureBakery {

    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        // No-op: block atlas sampling not implemented.
    }

    public int renderToOutput(BlockState state, long outputBuffer) {
        return 0;
    }

    public void free() {
    }
}
