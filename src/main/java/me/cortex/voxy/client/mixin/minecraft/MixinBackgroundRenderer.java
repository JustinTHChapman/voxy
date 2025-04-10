package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(BackgroundRenderer.class)
public class MixinBackgroundRenderer {
    @WrapMethod(method = "applyFog")
    private static Fog voxy$overrideFog(Camera camera, BackgroundRenderer.FogType fogType, Vector4f color, float viewDistance, boolean thickenFog, float tickProgress, Operation<Fog> original) {
        var vrs = (IGetVoxyRenderSystem)MinecraftClient.getInstance().worldRenderer;
        if (VoxyConfig.CONFIG.renderVanillaFog || vrs == null || vrs.getVoxyRenderSystem() == null) {
            return original.call(camera, fogType, color, viewDistance, thickenFog, tickProgress);
        } else {
            return Fog.DUMMY;
        }
    }
}
