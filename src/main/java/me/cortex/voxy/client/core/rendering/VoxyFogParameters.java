package me.cortex.voxy.client.core.rendering;

/**
 * Local fog parameter POJO replacing Sodium 0.7's net.caffeinemc.mods.sodium.client.util.VoxyFogParameters,
 * which is not present in Sodium 0.6.13 (the latest stable for NeoForge 1.21.1).
 * Mirrors the field layout so Voxy's pipeline code can use it without changes.
 */
public record VoxyFogParameters(
        float red,
        float green,
        float blue,
        float start,
        float end,
        int shape
) {
    public static final VoxyFogParameters NONE = new VoxyFogParameters(0f, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0);

    // Aliases matching Voxy upstream / MC 1.26 FogParameters method names.
    public float environmentalStart() { return start; }
    public float environmentalEnd() { return end; }
    public float alpha() { return 1.0f; }
}
