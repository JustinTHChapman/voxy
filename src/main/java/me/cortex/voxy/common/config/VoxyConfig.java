package me.cortex.voxy.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * NeoForge common (server+client) configuration for Voxy.
 * Loaded from {@code config/voxy-common.toml}.
 *
 * <p>Config naming intentionally mirrors the legacy {@code voxy_server_lod} companion mod
 * (used on World-of-Titans-style servers) so existing server admins can carry settings over.
 */
public final class VoxyConfig {

    public static final VoxyConfig INSTANCE;
    public static final ModConfigSpec SPEC;

    static {
        Pair<VoxyConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(VoxyConfig::new);
        INSTANCE = specPair.getLeft();
        SPEC     = specPair.getRight();
    }

    public enum GenerationMode {
        /** Only deliver LOD sections that already exist on disk. Zero new chunk generation. */
        DISK_ONLY,
        /** Deliver disk sections first, then generate any missing chunks within the radius. */
        GENERATE
    }

    // -------------------------------------------------------------------------
    // Server settings
    // -------------------------------------------------------------------------

    /** Maximum LOD level generated on the server. 0 = finest only; 4 = up to 16x coarsening. */
    public final ModConfigSpec.IntValue serverMaxLodLevel;

    /** Number of chunks to voxelize per tick on the server worker thread. */
    public final ModConfigSpec.IntValue voxelizeRatePerTick;

    /** Per-player LOD section delivery throttle (chunks per server tick). */
    public final ModConfigSpec.IntValue lodChunksPerTick;

    /** Server-enforced cap on the LOD radius (in sections at LOD 0) per client. */
    public final ModConfigSpec.IntValue lodRadiusMax;

    /** If true, the server pushes the manifest to each client on world-join automatically. */
    public final ModConfigSpec.BooleanValue lodSendOnJoin;

    /** How missing-on-disk chunks are handled when delivering to clients. */
    public final ModConfigSpec.EnumValue<GenerationMode> lodGenerationMode;

    /** Maximum number of section-data packets queued for a single client. */
    public final ModConfigSpec.IntValue serverMaxTransferQueuePerClient;

    // -------------------------------------------------------------------------
    // Client settings
    // -------------------------------------------------------------------------

    /** Client-side LOD render radius in sections at LOD 0. Server may clamp this. */
    public final ModConfigSpec.IntValue clientLodRadius;

    /** Whether to show a HUD overlay with LOD sync statistics. */
    public final ModConfigSpec.BooleanValue clientShowSyncHud;

    // -------------------------------------------------------------------------

    private VoxyConfig(ModConfigSpec.Builder builder) {
        builder.comment(
                "Voxy NeoForge — common config",
                "Field naming mirrors the legacy 'voxy_server_lod' companion mod for familiarity."
        );

        builder.push("server");

        serverMaxLodLevel = builder
                .comment("Maximum LOD level the server generates. Higher = more data pre-generated.",
                         "0 = LOD 0 only (finest). 4 = levels 0-4.")
                .defineInRange("max_lod_level", 4, 0, 7);

        voxelizeRatePerTick = builder
                .comment("How many chunks are voxelized per server tick. Lower = less CPU impact.")
                .defineInRange("voxelize_rate_per_tick", 8, 1, 64);

        lodChunksPerTick = builder
                .comment("Number of chunks delivered to each client per server tick.",
                         "Lower = less TPS impact but slower initial load.")
                .defineInRange("lod_chunks_per_tick", 4, 1, 64);

        lodRadiusMax = builder
                .comment("Maximum LOD radius (in sections) the server will honour regardless of",
                         "what the client requests. Prevents overloading the server.")
                .defineInRange("lod_radius_max", 4096, 1, 8192);

        lodSendOnJoin = builder
                .comment("If true, server pushes the manifest to each client on world-join.",
                         "If false, clients must request the manifest themselves.")
                .define("lod_send_on_join", true);

        lodGenerationMode = builder
                .comment("How chunks NOT already on disk are handled when delivering to clients:",
                         "  DISK_ONLY (default) — silently skip; zero chunk generation.",
                         "  GENERATE            — generate them on demand. May cause TPS lag.")
                .defineEnum("lod_generation_mode", GenerationMode.DISK_ONLY);

        serverMaxTransferQueuePerClient = builder
                .comment("Maximum pending LOD section packets queued per connected client.",
                         "Reduces memory use when clients are on slow connections.")
                .defineInRange("max_transfer_queue_per_client", 2048, 64, 65536);

        builder.pop().push("client");

        clientLodRadius = builder
                .comment("LOD render radius in sections (at LOD 0). Each section = 1 chunk.",
                         "The server clamps this to its lod_radius_max value.")
                .defineInRange("lod_radius", 256, 8, 8192);

        clientShowSyncHud = builder
                .comment("Display LOD sync progress in the HUD debug overlay.")
                .define("show_sync_hud", true);

        builder.pop();
    }
}

