package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make Iris treat voxy's LOD distance like Distant Horizons' so its fog (and far-plane) follow the LOD
 * edge instead of the vanilla render distance.
 *
 * <p>Iris derives its fog {@code far} uniform and projection far-plane from
 * {@code net.irisshaders.iris.compat.dh.DHCompat.getRenderDistance()} — its Distant Horizons hook.
 * Without DH installed that method just returns {@code options.getEffectiveRenderDistance()} (e.g. 3
 * chunks), so with a low vanilla render distance Iris's composite fog whites out every LOD past ~48
 * blocks. Voxy is functionally the same as DH here (it extends the visible world via LODs), so we lift
 * the value to voxy's current LOD fog frontier (in chunks) — exactly the integration point DH uses.</p>
 *
 * <p>String target + {@code remap = false} + {@code require = 0} so this is a no-op when Iris is absent
 * (the class won't exist) or if the method shape changes in a future Iris; the config is non-required.</p>
 */
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompat", remap = false)
public class MixinDHCompat {

    @Inject(method = "getRenderDistance()I", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void voxy$extendForLodFog(CallbackInfoReturnable<Integer> cir) {
        if (IGetVoxyRenderSystem.getNullable() == null) return;
        // getRenderDistance() is in CHUNKS here (matches the getEffectiveRenderDistance() fallback).
        // Use the LOD fog frontier so Iris's fog tracks where LOD data actually runs out.
        int frontierChunks = (int) (AutoGenerationService.INSTANCE.getFogFrontierBlockRadius() / 16f);
        if (frontierChunks > cir.getReturnValueI()) {
            cir.setReturnValue(frontierChunks);
        }
    }
}
