package me.cortex.voxy.client.core.util;

/**
 * Stub for upstream IrisUtil — Iris integration is deferred and will be ported in a final phase.
 * All methods return safe defaults so the rest of the rendering pipeline can compile and run.
 */
public final class IrisUtil {
    private IrisUtil() {}

    public static final boolean IRIS_INSTALLED = false;
    public static final boolean SHADER_SUPPORT = false;

    public static boolean irisIsLoaded() { return false; }
    public static boolean irisActive() { return false; }
    public static boolean irisShadowActive() { return false; }
    public static boolean isRenderingShadowMap() { return false; }
    public static boolean irisShaderPackEnabled() { return false; }
    public static void disableIrisShaders() {}
    public static void clearIrisSamplers() {}
    public static void onWorldRenderStart() {}
    public static void onWorldRenderEnd() {}
    public static void onRenderShadow() {}
}
