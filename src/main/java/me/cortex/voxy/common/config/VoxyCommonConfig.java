package me.cortex.voxy.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config specs for Voxy.
 *
 * SERVER spec  – stored in serverconfig/ on a dedicated server.  Values are
 * pushed to each connecting client via {@link me.cortex.voxy.common.network.S2CServerConfigPacket}
 * so the client can clamp its own behaviour to whatever the server allows.
 *
 * CLIENT spec  – stored in config/ on the player's machine.  These are the
 * player's personal preferences; they are clamped by any server-provided limits
 * at runtime but are never sent to the server.
 */
public class VoxyCommonConfig {

    // ── SERVER ───────────────────────────────────────────────────────────────

    public static final ModConfigSpec SERVER_SPEC;

    /** Maximum LOD section packets sent to a single player per server tick. */
    public static final ModConfigSpec.IntValue LOD_CHUNKS_PER_TICK;

    /** Maximum chunks the client is allowed to auto-generate per tick (server-enforced cap). */
    public static final ModConfigSpec.IntValue LOD_GENERATION_RATE_CAP;

    /** Whether clients are permitted to auto-generate LOD and upload results to the server. */
    public static final ModConfigSpec.BooleanValue AUTO_GENERATION_ENABLED_SERVER;

    /** Server-side ceiling on how large a LOD radius the server will honour. */
    public static final ModConfigSpec.IntValue LOD_RADIUS_MAX;

    /** Push an LOD manifest to each client on world join (pull-based delta sync). */
    public static final ModConfigSpec.BooleanValue LOD_SEND_ON_JOIN;

    /** Maximum LOD packets that may be queued for a single client before back-pressure kicks in. */
    public static final ModConfigSpec.IntValue MAX_TRANSFER_QUEUE_PER_CLIENT;

    // ── CLIENT ───────────────────────────────────────────────────────────────

    public static final ModConfigSpec CLIENT_SPEC;

    /** Allow background LOD auto-generation on this client. */
    public static final ModConfigSpec.BooleanValue AUTO_GENERATION_ENABLED_CLIENT;

    /** Target LOD sections to auto-generate per tick (server may cap this lower). */
    public static final ModConfigSpec.IntValue AUTO_GENERATION_RATE;

    /** LOD render radius in world-sections (server may cap this lower). */
    public static final ModConfigSpec.IntValue LOD_RADIUS;

    static {
        // ── build SERVER spec ─────────────────────────────────────────────
        var sb = new ModConfigSpec.Builder();
        sb.comment("Voxy server settings – controls bandwidth, generation, and sync behaviour").push("server");

        LOD_CHUNKS_PER_TICK = sb
                .comment("Maximum LOD section packets sent to a single player per server tick.")
                .defineInRange("lod_chunks_per_tick", 16, 1, 128);

        LOD_GENERATION_RATE_CAP = sb
                .comment("Maximum LOD sections a client may auto-generate per tick (server-enforced cap).")
                .defineInRange("lod_generation_rate_cap", 8, 1, 64);

        AUTO_GENERATION_ENABLED_SERVER = sb
                .comment("When true, clients may auto-generate LOD and upload results to the server.")
                .define("auto_generation_enabled", true);

        LOD_RADIUS_MAX = sb
                .comment("Maximum LOD render radius (in sections) the server will honour from clients.")
                .defineInRange("lod_radius_max", 4096, 1, 8192);

        LOD_SEND_ON_JOIN = sb
                .comment("Send an LOD manifest to each connecting client so it can perform delta sync.")
                .define("lod_send_on_join", true);

        MAX_TRANSFER_QUEUE_PER_CLIENT = sb
                .comment("Maximum LOD packets queued per client before new sections are dropped.")
                .defineInRange("max_transfer_queue_per_client", 2048, 64, 65536);

        sb.pop();
        SERVER_SPEC = sb.build();

        // ── build CLIENT spec ─────────────────────────────────────────────
        var cb = new ModConfigSpec.Builder();
        cb.comment("Voxy client settings – personal preferences, clamped by server limits at runtime").push("client");

        AUTO_GENERATION_ENABLED_CLIENT = cb
                .comment("Enable background LOD auto-generation for chunks near the player.")
                .define("auto_generation_enabled", true);

        AUTO_GENERATION_RATE = cb
                .comment("Target LOD sections to auto-generate per tick. Server may enforce a lower cap.")
                .defineInRange("auto_generation_rate", 2, 1, 32);

        LOD_RADIUS = cb
                .comment("LOD render radius in sections. Server may enforce a lower maximum.")
                .defineInRange("lod_radius", 256, 8, 8192);

        cb.pop();
        CLIENT_SPEC = cb.build();
    }
}
