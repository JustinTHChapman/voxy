package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extend the render distance reported for fog out to the LOD frontier when an Iris shader pack is
 * active.
 *
 * <p>Under Iris, LOD terrain is blitted into Iris's GBuffer and then fogged by Iris's composite passes
 * using its {@code far} uniform, which Iris derives from {@link GameRenderer#getRenderDistance()}
 * (= {@code getEffectiveRenderDistance() * 16}). With a low vanilla render distance (e.g. 3 chunks =
 * 48 blocks) that whites out every LOD past 48 blocks. Voxy's {@code MixinFogRenderer} only extends
 * vanilla's fog far-plane, which Iris ignores — so we lift {@code getRenderDistance()} itself.</p>
 *
 * <p>This is safe and surgical: it does NOT change which chunks load ({@code getEffectiveRenderDistance})
 * nor the projection far-plane ({@code getDepthFar() = renderDistance * 4}) — both read different
 * values. Only the fog {@code far} follows the LOD frontier. Gated to Iris so the non-shader path
 * (handled by {@code MixinFogRenderer}) is untouched.</p>
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "getRenderDistance", at = @At("RETURN"), cancellable = true)
    private void voxy$extendFogFarForIris(CallbackInfoReturnable<Float> cir) {
        if (IGetVoxyRenderSystem.getNullable() == null) return;
        if (!IrisUtil.irisShaderPackEnabled()) return;
        float frontier = AutoGenerationService.INSTANCE.getFogFrontierBlockRadius();
        if (frontier > cir.getReturnValueF()) {
            cir.setReturnValue(frontier);
        }
    }
}
