package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.mixin.minecraft.MixinLightTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class LightMapHelper {
    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        try {
            DynamicTexture tex = ((MixinLightTexture) Minecraft.getInstance().gameRenderer.lightTexture())
                    .voxy$getLightTexture();
            if (tex != null) return tex.getId();
        } catch (Throwable ignored) {}
        return 0;
    }
}