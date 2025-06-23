package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkManager;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientChunkManager.class)
public class MixinClientChunkManager implements ICheekyClientChunkManager {
    @Shadow volatile ClientChunkManager.ClientChunkMap chunks;

    @Override
    public WorldChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        return this.chunks.getChunk(this.chunks.getIndex(x, z));
    }
}
