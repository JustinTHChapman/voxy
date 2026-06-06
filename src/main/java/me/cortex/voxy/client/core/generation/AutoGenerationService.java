package me.cortex.voxy.client.core.generation;

import me.cortex.voxy.client.config.ServerConfigOverride;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.VoxyCommonConfig;
import me.cortex.voxy.common.network.C2SLodSectionPacket;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Background LOD auto-generation service.
 *
 * Each client tick, scans for Minecraft chunks within the configured LOD radius that
 * have not yet been voxelized into LOD data.  Chunks are processed in distance order
 * (closest to the player first).  The generation rate is capped by both the client
 * config and any server-enforced ceiling received via {@link ServerConfigOverride}.
 *
 * Call {@link #tick()} once per client tick.  Call {@link #reset()} on session end.
 */
public final class AutoGenerationService {

    public static final AutoGenerationService INSTANCE = new AutoGenerationService();

    /** Rebuild the candidate queue when the player moves more than this many chunks. */
    private static final int REBUILD_THRESHOLD_CHUNKS = 4;

    /** Lerp factor when fog frontier needs to shrink (player approaching edge). */
    private static final float FOG_LERP_SHRINK = 0.15f;
    /** Lerp factor when fog frontier needs to grow (new chunks generated). */
    private static final float FOG_LERP_GROW   = 0.05f;

    private final PriorityQueue<long[]> candidateQueue =
            new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
    private final Set<Long> submitted = new HashSet<>();

    /** Chunks pending upload to server after local generation. */
    private final Deque<LevelChunk> uploadQueue = new ArrayDeque<>();

    /** Max C2S section uploads per tick (separate budget from generation). */
    private static final int MAX_UPLOADS_PER_TICK = 4;

    // Dynamic throttle (dedicated-server fallback only).
    // On integrated server we use iServer.getAverageTickTime() directly.
    // This EWMA is only consulted on a dedicated server where we have no direct
    // server-side timing and must proxy through client-tick interval.
    private float smoothedMspt = 50f;
    private long lastTickNano = 0;

    private int lastPlayerCX = Integer.MIN_VALUE;
    private int lastPlayerCZ = Integer.MIN_VALUE;

    /** Chebyshev chunk radius of farthest successfully submitted chunk. */
    private volatile int estimatedLoadedChunkRadius = 0;

    /**
     * Euclidean chunk radius to the nearest unsubmitted chunk as of the last rebuildQueue call.
     * Shrinks as the player approaches the LOD edge, grows as new chunks are generated.
     */
    private volatile int fogFrontierChunks = 0;

    /** Current player chunk position, updated every tick for inter-rebuild fog adjustment. */
    private volatile int currentPlayerCX = 0;
    private volatile int currentPlayerCZ = 0;

    /**
     * Smoothed fog frontier in blocks, lerped toward the raw frontier each tick.
     * Shrinks faster than it grows so empty LOD sections are hidden promptly.
     */
    private volatile float smoothedFogFrontierBlocks = 0f;

    /** True once submitted has been pre-populated from sections.db for the current session. */
    private boolean dbPopulated = false;

    private AutoGenerationService() {}

    public void reset() {
        candidateQueue.clear();
        submitted.clear();
        uploadQueue.clear();
        lastPlayerCX = Integer.MIN_VALUE;
        lastPlayerCZ = Integer.MIN_VALUE;
        estimatedLoadedChunkRadius = 0;
        fogFrontierChunks = 0;
        currentPlayerCX = 0;
        currentPlayerCZ = 0;
        smoothedFogFrontierBlocks = 0f;
        smoothedMspt = 50f;
        lastTickNano = 0;
        dbPopulated = false;
    }

    /** Block radius (in world units) of the farthest successfully generated chunk from the player. */
    public float getEstimatedLoadedBlockRadius() {
        return estimatedLoadedChunkRadius * 16f;
    }

    /** Smoothed block radius to the LOD generation frontier, for use as fog end distance. */
    public float getFogFrontierBlockRadius() {
        return smoothedFogFrontierBlocks;
    }

    /**
     * One-time population of the submitted set from sections.db so the fog frontier reflects
     * chunks with actual stored LOD data, not just chunks ingested this session.
     * Called on the first tick after a world join.
     */
    private void populateSubmittedFromDB(WorldEngine engine, int playerCX, int playerCZ) {
        int scanRadius = Math.min(ServerConfigOverride.INSTANCE.effectiveLodRadius(), 256);
        long scanRadiusSq = (long) scanRadius * scanRadius;
        // WorldEngine stores level-N sections at position chunkX >> (N+1) in each axis.
        // Level-0 section x = chunkX / 2, so one level-0 section covers 2 chunk columns
        // per axis (a 2×2 chunk area).  Multiply back by 2 to recover chunk coordinates.
        // Without this correction, the submitted keys were at half the chunk scale and
        // never matched the colKey(chunkX, chunkZ) entries checked in rebuildQueue.
        engine.storage.iteratePositions(0, pos -> {
            int sx = WorldEngine.getX(pos); // level-0 section x  = chunkX / 2
            int sz = WorldEngine.getZ(pos); // level-0 section z  = chunkZ / 2
            for (int dcx = 0; dcx < 2; dcx++) {
                for (int dcz = 0; dcz < 2; dcz++) {
                    int cx = sx * 2 + dcx;
                    int cz = sz * 2 + dcz;
                    long dx = cx - playerCX;
                    long dz = cz - playerCZ;
                    if (dx * dx + dz * dz <= scanRadiusSq) {
                        submitted.add(colKey(cx, cz));
                    }
                }
            }
        });
    }

    /** Raw fog distance in blocks from the frontier (distance to nearest unsubmitted chunk). */
    private float computeRawFogBlocks() {
        return fogFrontierChunks * 16f;
    }

    /** Called once per client tick from the NeoForge ClientTickEvent listener. */
    public void tick() {
        if (!VoxyCommonConfig.AUTO_GENERATION_ENABLED_CLIENT.get()) return;
        if (!ServerConfigOverride.INSTANCE.autoGenerationEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var instance = VoxyCommon.getInstance();
        if (instance == null) return;

        WorldEngine engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) return;

        int playerCX = (int) mc.player.getX() >> 4;
        int playerCZ = (int) mc.player.getZ() >> 4;

        currentPlayerCX = playerCX;
        currentPlayerCZ = playerCZ;

        // On first tick after world join, pre-populate submitted from sections.db so the
        // fog frontier reflects actual stored LOD data, not just chunks re-ingested this session.
        if (!dbPopulated) {
            populateSubmittedFromDB(engine, playerCX, playerCZ);
            dbPopulated = true;
        }

        // Rebuild the candidate queue when the player has moved significantly
        int dcx = playerCX - lastPlayerCX;
        int dcz = playerCZ - lastPlayerCZ;
        if (candidateQueue.isEmpty() || dcx * dcx + dcz * dcz >= REBUILD_THRESHOLD_CHUNKS * REBUILD_THRESHOLD_CHUNKS) {
            rebuildQueue(playerCX, playerCZ);
        }

        // When the frontier grows (new chunks confirmed), snap immediately so the fog
        // always reaches the actual data edge.  When it shrinks (player approaching a gap),
        // lerp so the fog fades in smoothly rather than jumping to opaque.
        float rawFrontier = computeRawFogBlocks();
        float current = smoothedFogFrontierBlocks;
        if (rawFrontier < current) {
            smoothedFogFrontierBlocks = current + (rawFrontier - current) * FOG_LERP_SHRINK;
        } else {
            smoothedFogFrontierBlocks = rawFrontier;
        }

        // Integrated-server reference — used only for throttle MSPT, no longer for chunk loading
        var iServer = mc.getSingleplayerServer();

        // Determine effective server MSPT for throttling.
        // Singleplayer: use the integrated server's own rolling average (accurate).
        // Dedicated server: fall back to client-side EWMA as a proxy.
        float effectiveMspt;
        if (iServer != null) {
            effectiveMspt = iServer.getAverageTickTimeNanos() / 1_000_000f;
        } else {
            long now = System.nanoTime();
            if (lastTickNano > 0) {
                float tickMs = Math.min((now - lastTickNano) / 1_000_000f, 200f);
                smoothedMspt = 0.9f * smoothedMspt + 0.1f * tickMs;
            }
            lastTickNano = now;
            effectiveMspt = smoothedMspt;
        }

        // Throttle factor: 1.0 at ≤30 ms/tick (≥33 TPS), 0.0 at ≥80 ms/tick (≤12.5 TPS).
        // Tighter bounds than before: we start backing off earlier so the server never
        // reaches the 37%-of-tick situation seen in the profiler.
        float throttle = Math.max(0f, Math.min(1f, (80f - effectiveMspt) / 50f));
        int rate = Math.max(1, Math.round(ServerConfigOverride.INSTANCE.effectiveGenerationRate() * throttle));
        int generated = 0;

        while (generated < rate && !candidateQueue.isEmpty()) {
            long[] entry = candidateQueue.poll();
            int cx = (int) entry[1];
            int cz = (int) entry[2];
            long colKey = colKey(cx, cz);

            if (submitted.contains(colKey)) continue;

            // 1. Try client chunk cache first (chunks within vanilla render distance)
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);

            if (chunk == null) continue; // not in client cache — skip

            boolean ok = instance.getIngestService().enqueueIngest(engine, chunk);
            if (ok) {
                submitted.add(colKey);
                generated++;
                uploadQueue.addLast(chunk);
                int d = Math.max(Math.abs(cx - playerCX), Math.abs(cz - playerCZ));
                if (d > estimatedLoadedChunkRadius) estimatedLoadedChunkRadius = d;
            }
            // If enqueueIngest returns false (lighting not ready), the entry is dropped
            // from the queue.  It will be re-added on the next rebuildQueue() call when
            // the player moves or the current batch empties.
        }

        // Upload client-generated sections to server (rate-limited, dedicated server only).
        // In singleplayer the integrated server already owns the chunk data — uploading it
        // back would be a complete no-op that wastes convert + mip + packet-serialize time.
        if (iServer == null && !uploadQueue.isEmpty() && ServerConfigOverride.INSTANCE.autoGenerationEnabled()) {
            drainUploads(mc, engine);
        } else {
            uploadQueue.clear(); // singleplayer: discard immediately, no upload needed
        }
    }

    private static final ThreadLocal<VoxelizedSection> VS_CACHE =
            ThreadLocal.withInitial(VoxelizedSection::createEmpty);

    private void drainUploads(Minecraft mc, WorldEngine engine) {
        Mapper mapper = engine.getMapper();
        String dimId = mc.level.dimension().location().toString();
        int uploaded = 0;
        while (uploaded < MAX_UPLOADS_PER_TICK && !uploadQueue.isEmpty()) {
            LevelChunk chunk = uploadQueue.pollFirst();
            if (chunk == null) continue;
            try {
                var lightEngine = mc.level.getLightEngine();
                int sectionIdx = chunk.getMinSection() - 1;
                for (LevelChunkSection sec : chunk.getSections()) {
                    sectionIdx++;
                    if (sec == null) continue;
                    var spos = SectionPos.of(chunk.getPos(), sectionIdx);
                    DataLayer blData = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(spos);
                    DataLayer slData = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(spos);
                    ILightingSupplier light = buildLight(blData, slData);
                    VoxelizedSection vs = VS_CACHE.get().setPosition(chunk.getPos().x, sectionIdx, chunk.getPos().z);
                    vs = WorldConversionFactory.convert(vs, mapper, sec.getStates(), sec.getBiomes(), light);
                    WorldVoxilizedSectionMipper.mipSection(vs, mapper);
                    S2CLodSectionPacket s2c = S2CLodSectionPacket.fromSection(dimId, vs, mapper);
                    PacketDistributor.sendToServer(C2SLodSectionPacket.fromS2C(s2c));
                }
                uploaded++;
            } catch (Exception e) {
                Logger.warn("[AutoGen] Upload failed for chunk " + chunk.getPos() + ": " + e.getMessage());
            }
        }
    }

    private static ILightingSupplier buildLight(DataLayer bl, DataLayer sl) {
        boolean hbl = bl != null && !bl.isEmpty();
        boolean hsl = sl != null && !sl.isEmpty();
        if (hbl && hsl) return (x, y, z) -> (byte) (Math.min(15, sl.get(x,y,z)) | (Math.min(15, bl.get(x,y,z)) << 4));
        if (hbl)        return (x, y, z) -> (byte) (Math.min(15, bl.get(x,y,z)) << 4);
        if (hsl)        return (x, y, z) -> (byte)  Math.min(15, sl.get(x,y,z));
        return (x, y, z) -> (byte) 0;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void rebuildQueue(int playerCX, int playerCZ) {
        candidateQueue.clear();
        lastPlayerCX = playerCX;
        lastPlayerCZ = playerCZ;

        int radius = ServerConfigOverride.INSTANCE.effectiveLodRadius();
        // Clamp scan radius to avoid extremely large queues
        int scanRadius = Math.min(radius, 256);

        // Scan the full radius in one pass. The priority queue sorts by Euclidean
        // distance squared so chunks are popped closest-first, giving a circular
        // generation front rather than the square front that a Chebyshev-ordered
        // scan would produce.
        // Frontier = nearest unsubmitted chunk (Euclidean distance from player).
        // Shrinks when the player approaches an ungenerated edge, grows as generation progresses.
        long fogFrontierDistSq = (long) scanRadius * scanRadius + 1;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                long dist = (long) dx * dx + (long) dz * dz;
                if (dist > (long) scanRadius * scanRadius) continue; // circular clip
                int cx = playerCX + dx;
                int cz = playerCZ + dz;
                long key = colKey(cx, cz);
                if (submitted.contains(key)) continue;
                if (dist < fogFrontierDistSq) fogFrontierDistSq = dist;
                candidateQueue.add(new long[]{dist, cx, cz});
            }
        }
        this.fogFrontierChunks = (int) Math.ceil(Math.sqrt(fogFrontierDistSq));
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
