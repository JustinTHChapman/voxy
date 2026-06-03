package me.cortex.voxy.client.config;

import me.cortex.voxy.common.config.VoxyCommonConfig;
import me.cortex.voxy.common.network.S2CServerConfigPacket;

/**
 * Stores the server-pushed config limits for the current session.
 *
 * Populated by {@link S2CServerConfigPacket} on world join and reset to
 * client-config defaults when the session ends.  All values are the
 * effective server-side ceiling; client code must clamp its own settings
 * to these before acting.
 */
public final class ServerConfigOverride {

    public static final ServerConfigOverride INSTANCE = new ServerConfigOverride();

    private volatile int lodsPerTick;
    private volatile int generationRateCap;
    private volatile boolean autoGenerationEnabled;
    private volatile int lodRadiusMax;

    private ServerConfigOverride() {
        reset();
    }

    /** Apply limits received from the server. Called on the main client thread. */
    public void apply(S2CServerConfigPacket pkt) {
        this.lodsPerTick           = pkt.lodsPerTick();
        this.generationRateCap     = pkt.generationRateCap();
        this.autoGenerationEnabled = pkt.autoGenerationEnabled();
        this.lodRadiusMax          = pkt.lodRadiusMax();
    }

    /** Reset to defaults derived from the local client config (call on session end). */
    public void reset() {
        this.lodsPerTick           = 16;
        this.generationRateCap     = 8;
        this.autoGenerationEnabled = true;
        this.lodRadiusMax          = 8192;
    }

    /** Effective max LOD sections sent per tick (server ceiling). */
    public int lodsPerTick() { return lodsPerTick; }

    /**
     * Effective max chunks the client may auto-generate per tick.
     * Returns the lower of the server cap and the client config preference.
     */
    public int effectiveGenerationRate() {
        int clientRate = VoxyCommonConfig.AUTO_GENERATION_RATE.get();
        return Math.min(clientRate, generationRateCap);
    }

    /** Whether the server permits client-side auto-generation and upload. */
    public boolean autoGenerationEnabled() { return autoGenerationEnabled; }

    /**
     * Effective LOD radius.
     * Returns the lower of the server maximum and the client config preference.
     */
    public int effectiveLodRadius() {
        int clientRadius = VoxyCommonConfig.LOD_RADIUS.get();
        return Math.min(clientRadius, lodRadiusMax);
    }
}
