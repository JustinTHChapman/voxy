package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    @Redirect(method = "setupFog", at = @At(value = "FIELD", target ="Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F", opcode = Opcodes.PUTFIELD), require = 0)
    private void voxy$modifyFog(FogData instance, float distance) {
        var vrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;

        if (VoxyConfig.CONFIG.renderVanillaFog || vrs == null || vrs.getVoxyRenderSystem() == null) {
            instance.renderDistanceEnd = distance;
        } else {
            instance.renderDistanceStart = 999999999;
            instance.renderDistanceEnd = 999999999;
            if (!VoxyConfig.CONFIG.useEnvironmentalFog) {
                instance.environmentalStart = 99999999;
                instance.environmentalEnd = 99999999;
            }
        }
    }
}
