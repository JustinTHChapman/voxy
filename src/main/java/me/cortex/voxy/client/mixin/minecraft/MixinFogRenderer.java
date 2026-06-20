package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = net.minecraft.client.renderer.FogRenderer.class, priority = 900)
public class MixinFogRenderer {

    @ModifyVariable(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("HEAD"), argsOnly = true, index = 2)
    private static float voxy$extendFarPlane(float farPlaneDistance) {
        if (IGetVoxyRenderSystem.getNullable() == null) return farPlaneDistance;
        // Extend vanilla fog to the LOD frontier so the sky shows fog colour (not clear sky)
        // where LOD hasn't loaded yet. Use the frontier (derived from the LOD data actually in
        // the file) rather than the voxelization radius, which only tracks source chunks this
        // session and would leave the sky fog hugging the player while file LODs render far out.
        // When nothing is loaded yet (radius == 0) we leave vanilla fog unchanged.
        float loaded = AutoGenerationService.INSTANCE.getFogFrontierBlockRadius();
        return loaded > 0 ? Math.max(farPlaneDistance, loaded) : farPlaneDistance;
    }

    // The 'shouldCreateFog' branch of vanilla setupFog computes end = min(farPlaneDistance, 192) * 0.5
    // — clamped at 96 blocks, so it IGNORES the extended far-plane above and hugs the chunk edge with a
    // close haze (the white band over the near, non-LOD chunks). It's enabled in foggy/custom dimensions.
    // When voxy's environmental fog is on we force it off so setupFog falls through to the mode-based
    // branches, which DO honour the extended far-plane — letting voxy's LOD fog own the distance instead.
    @ModifyVariable(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("HEAD"), argsOnly = true, index = 3)
    private static boolean voxy$dropClampedNearFog(boolean shouldCreateFog) {
        if (IGetVoxyRenderSystem.getNullable() == null) return shouldCreateFog;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog) return shouldCreateFog;
        if (AutoGenerationService.INSTANCE.getFogFrontierBlockRadius() <= 0) return shouldCreateFog;
        return false;
    }
}
