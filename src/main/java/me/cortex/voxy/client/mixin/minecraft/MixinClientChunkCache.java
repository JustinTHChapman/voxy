package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = ModList.get().isLoaded("bobby");

    @Unique
    private static Field voxy$storageField;

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        try {
            if (voxy$storageField == null) {
                Field f = ClientChunkCache.class.getDeclaredField("storage");
                f.setAccessible(true);
                voxy$storageField = f;
            }
            var storage = voxy$storageField.get(this);
            if (storage == null) return null;
            var clazz = storage.getClass();
            var getIndex = clazz.getDeclaredMethod("getIndex", int.class, int.class);
            getIndex.setAccessible(true);
            var getChunk = clazz.getDeclaredMethod("getChunk", int.class);
            getChunk.setAccessible(true);
            int idx = (int) getIndex.invoke(storage, x, z);
            var chunk = (LevelChunk) getChunk.invoke(storage, idx);
            if (chunk == null) return null;
            if (chunk.getPos().x == x && chunk.getPos().z == z) {
                return chunk;
            }
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
