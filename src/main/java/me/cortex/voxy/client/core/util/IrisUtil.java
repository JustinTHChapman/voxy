package me.cortex.voxy.client.core.util;

import java.lang.reflect.Method;

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

    public static boolean irisIsLoaded_field() { return irisIsLoaded(); }
    public static boolean irisActive()          { return irisShaderPackEnabled(); }
    public static boolean irisShadowActive()    { return isRenderingShadowMap(); }

    // ── stubs retained for API compatibility ────────────────────────────────
    public static void disableIrisShaders()  {}
    public static void clearIrisSamplers()   {}
    public static void onWorldRenderStart()  {}
    public static void onWorldRenderEnd()    {}
    public static void onRenderShadow()      {}
}
