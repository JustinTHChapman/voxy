package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make Iris's shader {@code far} uniform follow voxy's LOD edge instead of the vanilla render distance.
 *
 * <p>As of Iris 1.8.14 the {@code far} uniform is fed by
 * {@code net.irisshaders.iris.uniforms.CameraUniforms.getRenderDistanceInBlocks()}
 * (= {@code options.getEffectiveRenderDistance() * 16}, in blocks). Earlier Iris (1.8.12) instead drove
 * {@code far} from {@code DHCompat.getRenderDistance()} — see {@link MixinDHCompat}. Iris keeps moving
 * this source between versions, so voxy hooks BOTH: whichever one the running Iris uses for {@code far},
 * we lift it to the current LOD fog frontier; the other is harmless.</p>
 *
 * <p>String target + {@code remap = false} + {@code require = 0} so it is a no-op when Iris is absent or
 * the method shape changes; the config is non-required.</p>
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms", remap = false)
public class MixinIrisCameraUniforms {

    @Inject(method = "getRenderDistanceInBlocks()I", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void voxy$extendFarForLodFog(CallbackInfoReturnable<Integer> cir) {
        if (IGetVoxyRenderSystem.getNullable() == null) return;
        // This value is in BLOCKS, so the fog frontier (also blocks) is used directly.
        int frontierBlocks = (int) AutoGenerationService.INSTANCE.getFogFrontierBlockRadius();
        if (frontierBlocks > cir.getReturnValueI()) {
            cir.setReturnValue(frontierBlocks);
        }
    }
}
