package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.lang.reflect.Field;

import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class LightMapHelper {
    private static Field lightTextureField;

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        try {
            var lt = Minecraft.getInstance().gameRenderer.lightTexture();
            if (lightTextureField == null) {
                // getDeclaredField only searches the immediate class; walk the hierarchy
                // in case lt is a Mixin-instrumented subtype.
                Class<?> cls = lt.getClass();
                while (cls != null) {
                    try {
                        Field f = cls.getDeclaredField("lightTexture");
                        f.setAccessible(true);
                        lightTextureField = f;
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
            }
            if (lightTextureField != null) {
                DynamicTexture tex = (DynamicTexture) lightTextureField.get(lt);
                if (tex != null) return tex.getId();
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
