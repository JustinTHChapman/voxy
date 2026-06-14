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

    /** Enable verbose debug logging via {@link me.cortex.voxy.common.Logger#debug}. */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    /**
     * Maximum chunk columns retained in the auto-generation submitted set.
     * 0 = no limit (default). When exceeded the set is cleared and re-populated
     * from the database on the next rebuild cycle.
     */
    public static final ModConfigSpec.IntValue AUTO_GEN_SUBMITTED_LIMIT;

    /** Max chunks force-loaded from the integrated server at once for LOD auto-generation. */
    public static final ModConfigSpec.IntValue AUTO_GEN_MAX_PENDING_LOADS;

    /** Max LOD upload backlog (generated chunks awaiting upload) before the oldest are dropped. */
    public static final ModConfigSpec.IntValue AUTO_GEN_MAX_UPLOAD_QUEUE;

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

        DEBUG_LOGGING = cb
                .comment("Enable verbose debug logging for Voxy. Requires DEBUG log level in log4j config to appear in the log file.")
                .define("debug_logging", false);

        AUTO_GEN_SUBMITTED_LIMIT = cb
                .comment("Maximum chunk columns tracked in the auto-generation submitted set. 0 = no limit (default).",
                         "When the limit is exceeded the set is cleared and re-populated from the database on the next rebuild cycle.")
                .defineInRange("auto_gen_submitted_limit", 0, 0, Integer.MAX_VALUE);

        AUTO_GEN_MAX_PENDING_LOADS = cb
                .comment("Max chunks being force-loaded from the integrated server at once for LOD generation.",
                         "Higher = faster distant generation but more server load.")
                .defineInRange("auto_gen_max_pending_loads", 4, 1, 64);

        AUTO_GEN_MAX_UPLOAD_QUEUE = cb
                .comment("Max LOD upload backlog (generated chunks awaiting upload) before the oldest are dropped.",
                         "Bounds memory; does not affect generation rate.")
                .defineInRange("auto_gen_max_upload_queue", 256, 16, 8192);

        cb.pop();
        CLIENT_SPEC = cb.build();
    }
}
