package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {

    @Shadow @Final private ClientLevel level;

    private static final org.slf4j.Logger VOXY_DIAG = org.slf4j.LoggerFactory.getLogger("VoxyDiag");
    private static int voxy$ingestCallCount = 0;
    private static int voxy$ingestNullChunkCount = 0;
    private static int voxy$ingestQueuedCount = 0;
    private static int voxy$ingestNotLevelChunkCount = 0;
    private static long voxy$ingestLastLog = 0L;

    @Inject(method = "onChunkAdded", at = @At("HEAD"), remap = false, require = 0)
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        voxy$ingestCallCount++;
        if (!VoxyConfig.CONFIG.generateChunks) return;
        if (this.level == null) return;
        ChunkAccess chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        boolean queued = false;
        if (chunk == null) {
            voxy$ingestNullChunkCount++;
        } else if (chunk instanceof LevelChunk lc) {
            queued = VoxelIngestService.tryAutoIngestChunk(lc);
            if (queued) voxy$ingestQueuedCount++;
        } else {
            voxy$ingestNotLevelChunkCount++;
        }
        long now = System.currentTimeMillis();
        if (now - voxy$ingestLastLog > 2000) {
            voxy$ingestLastLog = now;
            VOXY_DIAG.info("Sodium ingest hook: calls={} nullChunk={} notLevelChunk={} queued={}",
                    voxy$ingestCallCount, voxy$ingestNullChunkCount, voxy$ingestNotLevelChunkCount, voxy$ingestQueuedCount);
        }
    }
}
