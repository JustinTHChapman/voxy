package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Shadow @Final private ClientWorld level;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientWorld level, int renderDistance, CommandList commandList, CallbackInfo ci) {
        if (level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$trackChunkAdd(int x, int z, CallbackInfo ci) {
        if (this.level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.addChunk(ChunkPos.toLong(x, z));
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$trackChunkRemove(int x, int z, CallbackInfo ci) {
        if (this.level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.removeChunk(ChunkPos.toLong(x, z));
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        var instance = VoxyCommon.getInstance();
        if (instance != null && VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = this.level.getChunk(x, z);
            var world = chunk.getWorld();
            if (world instanceof ClientWorld cw) {
                var engine = ((VoxyClientInstance)instance).getOrMakeRenderWorld(cw);
                if (engine != null) {
                    instance.getIngestService().enqueueIngest(engine, chunk);
                }
            }
        }
    }


    @ModifyReturnValue(method = "getSearchDistance", at = @At("RETURN"))
    private float voxy$increaseSearchDistanceFix(float searchDistance) {
        if (((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem() == null) {
            return searchDistance;
        }
        return searchDistance + 20;
    }
}
