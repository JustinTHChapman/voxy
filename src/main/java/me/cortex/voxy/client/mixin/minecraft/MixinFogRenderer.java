package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = net.minecraft.client.renderer.FogRenderer.class, priority = 900)
public class MixinFogRenderer {

    @ModifyVariable(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("HEAD"), argsOnly = true, index = 2)
    private static float voxy$extendFarPlane(float farPlaneDistance) {
        if (IGetVoxyRenderSystem.getNullable() == null) return farPlaneDistance;
        float voxyDist = VoxyConfig.CONFIG.sectionRenderDistance * 32f * 16f;
        return Math.max(farPlaneDistance, voxyDist);
    }
}
