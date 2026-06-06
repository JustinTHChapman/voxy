package me.cortex.voxy.client.core.util;

import java.lang.reflect.Method;

import static org.lwjgl.opengl.GL33C.glBindSampler;

/**
 * Reflection-based Iris/Oculus detection and state queries.
 *
 * No compile-time dependency on Iris — all access goes through cached Method handles
 * obtained on first use.  If Iris is not on the classpath every method returns its
 * safe default so the rest of the pipeline behaves as if Iris is absent.
 *
 * API surface: net.irisshaders.iris.api.v0.IrisApi (stable across Iris 1.6+).
 */
public final class IrisUtil {
    private IrisUtil() {}

    // ── detection ──────────────────────────────────────────────────────────
    private static Boolean irisLoadedCache;

    public static boolean irisIsLoaded() {
        if (irisLoadedCache == null) {
            try {
                Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisLoadedCache = true;
            } catch (ClassNotFoundException ignored) {
                irisLoadedCache = false;
            }
        }
        return irisLoadedCache;
    }

    // ── cached API method handles (resolved once on first call) ─────────────
    private static boolean apiResolved = false;
    private static Object apiInstance;
    private static Method mIsShaderPackInUse;
    private static Method mIsRenderingShadowPass;

    private static void resolveApi() {
        if (apiResolved) return;
        apiResolved = true;
        if (!irisIsLoaded()) return;
        try {
            Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            apiInstance          = cls.getMethod("getInstance").invoke(null);
            mIsShaderPackInUse   = cls.getMethod("isShaderPackInUse");
            mIsRenderingShadowPass = cls.getMethod("isRenderingShadowPass");
        } catch (Exception e) {
            // Iris version incompatibility — degrade gracefully
        }
    }

    // ── public queries ──────────────────────────────────────────────────────

    /** True when Iris is on the classpath AND a shader pack is currently loaded and active. */
    public static boolean irisShaderPackEnabled() {
        if (!irisIsLoaded()) return false;
        resolveApi();
        if (mIsShaderPackInUse == null) return false;
        try {
            return (boolean) mIsShaderPackInUse.invoke(apiInstance);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when Iris is currently rendering the shadow map pass. */
    public static boolean isRenderingShadowMap() {
        if (!irisIsLoaded()) return false;
        resolveApi();
        if (mIsRenderingShadowPass == null) return false;
        try {
            return (boolean) mIsRenderingShadowPass.invoke(apiInstance);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean irisActive()       { return irisShaderPackEnabled(); }
    public static boolean irisShadowActive() { return isRenderingShadowMap(); }

    // ── GL state restoration ────────────────────────────────────────────────

    /**
     * Clears sampler objects from texture units that Voxy may have left bound.
     * Iris tracks sampler state internally; after Voxy's custom render pass corrupts
     * those bindings we unbind them so Iris can re-apply its own samplers cleanly
     * on the next Iris render stage.  Called once per frame after Voxy renders.
     */
    public static void clearIrisSamplers() {
        if (!irisIsLoaded()) return;
        // Voxy's loop covers units 0-11; extend to 16 to cover any extra Iris units.
        for (int i = 12; i < 16; i++) {
            glBindSampler(i, 0);
        }
    }

    // ── stubs ───────────────────────────────────────────────────────────────
    // Iris doesn't expose a public "disable shaders" API, so this is a no-op.
    // The caller (MixinLevelRenderer) uses this as a last-resort fallback when
    // Voxy's renderer fails to initialise with Iris active; without a real
    // disable call the user sees no LOD rather than a crash, which is acceptable.
    public static void disableIrisShaders()  {}
    public static void onWorldRenderStart()  {}
    public static void onWorldRenderEnd()    {}
    public static void onRenderShadow()      {}
}
