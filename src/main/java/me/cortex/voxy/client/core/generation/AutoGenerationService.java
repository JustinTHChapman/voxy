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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import java.util.concurrent.ConcurrentLinkedDeque;

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

    /** How many chunks to scan ahead into the pending queue each rebuild. */
    private static final int SCAN_BATCH = 16;
    /** Rebuild the candidate queue when the player moves more than this many chunks. */
    private static final int REBUILD_THRESHOLD_CHUNKS = 4;
    /** Max outstanding server-thread chunk requests. Prevents flooding the integrated server. */
    private static final int MAX_PENDING_SERVER_LOADS = 4;

    private final PriorityQueue<long[]> candidateQueue =
            new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
    private final Set<Long> submitted    = new HashSet<>();
    private final Set<Long> pendingLoad  = new HashSet<>();   // requested from server thread, not yet back

    /** Chunks loaded by the server thread and ready to ingest on the client tick. */
    private final ConcurrentLinkedDeque<LevelChunk> serverReadyChunks = new ConcurrentLinkedDeque<>();

    /** Chunks pending upload to server after local generation. */
    private final Deque<LevelChunk> uploadQueue = new ArrayDeque<>();

    /** Max C2S section uploads per tick (separate budget from generation). */
    private static final int MAX_UPLOADS_PER_TICK = 4;

    // Dynamic throttle: reduce generation when the game is struggling.
    // Measures wall-clock time between successive tick() calls and smooths it
    // with an EWMA (α=0.1). Works on both singleplayer and dedicated server.
    private float smoothedMspt = 50f;  // starts at 50 ms (20 TPS) — no pre-throttle before data arrives
    private long lastTickNano = 0;

    private int lastPlayerCX = Integer.MIN_VALUE;
    private int lastPlayerCZ = Integer.MIN_VALUE;

    /** Chebyshev chunk radius of farthest successfully submitted chunk — used for fog. */
    private volatile int estimatedLoadedChunkRadius = 0;

    private AutoGenerationService() {}

    public void reset() {
        candidateQueue.clear();
        submitted.clear();
        pendingLoad.clear();
        serverReadyChunks.clear();
        uploadQueue.clear();
        lastPlayerCX = Integer.MIN_VALUE;
        lastPlayerCZ = Integer.MIN_VALUE;
        estimatedLoadedChunkRadius = 0;
        smoothedMspt = 50f;
        lastTickNano = 0;
    }

    /** Block radius (in world units) of the farthest successfully generated chunk from the player. */
    public float getEstimatedLoadedBlockRadius() {
        return estimatedLoadedChunkRadius * 16f;
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

        // Rebuild the candidate queue when the player has moved significantly
        int dcx = playerCX - lastPlayerCX;
        int dcz = playerCZ - lastPlayerCZ;
        if (candidateQueue.isEmpty() || dcx * dcx + dcz * dcz >= REBUILD_THRESHOLD_CHUNKS * REBUILD_THRESHOLD_CHUNKS) {
            rebuildQueue(playerCX, playerCZ);
        }

        // Drain any chunks the server thread has finished loading
        int drained = 0;
        LevelChunk ready;
        while ((ready = serverReadyChunks.poll()) != null) {
            long k = colKey(ready.getPos().x, ready.getPos().z);
            pendingLoad.remove(k);
            if (!submitted.contains(k)) {
                // Use enqueueIngestServer: bypasses the LIGHT_AND_DATA gate that
                // causes server-side chunks to be silently dropped when accessed
                // from the client thread.
                boolean ok = instance.getIngestService().enqueueIngestServer(engine, ready);
                if (ok) {
                    submitted.add(k);
                    uploadQueue.addLast(ready);
                    drained++;
                    int d = Math.max(Math.abs(ready.getPos().x - playerCX), Math.abs(ready.getPos().z - playerCZ));
                    if (d > estimatedLoadedChunkRadius) estimatedLoadedChunkRadius = d;
                }
            }
        }
        if (drained > 0) {
            Logger.info("[AutoGen] Ingested " + drained + " server-loaded chunk(s) near (" + playerCX + "," + playerCZ + ")");
        }

        // Integrated-server reference (null on dedicated server or when not yet ready)
        var iServer = mc.getSingleplayerServer();

        // Measure wall-clock time between ticks (EWMA α=0.1, ~10 tick window).
        // Capped at 200 ms so a single GC pause does not permanently crater the rate.
        long now = System.nanoTime();
        if (lastTickNano > 0) {
            float tickMs = Math.min((now - lastTickNano) / 1_000_000f, 200f);
            smoothedMspt = 0.9f * smoothedMspt + 0.1f * tickMs;
        }
        lastTickNano = now;

        // Throttle factor: 1.0 at ≤40 ms/tick (≥25 TPS effective), 0.0 at ≥100 ms/tick (≤10 TPS).
        // Linear ramp between those bounds so generation backs off smoothly before the
        // server completely bogs down. We clamp to a minimum of 1 so generation never
        // stops entirely (the player still needs nearby chunks).
        float throttle = Math.max(0f, Math.min(1f, (100f - smoothedMspt) / 60f));
        int rate = Math.max(1, Math.round(ServerConfigOverride.INSTANCE.effectiveGenerationRate() * throttle));
        int generated = 0;
        int requested = 0;

        while ((generated + requested) < rate && !candidateQueue.isEmpty()) {
            long[] entry = candidateQueue.poll();
            int cx = (int) entry[1];
            int cz = (int) entry[2];
            long colKey = colKey(cx, cz);

            if (submitted.contains(colKey) || pendingLoad.contains(colKey)) continue;

            // 1. Try client chunk cache first (chunks within vanilla render distance)
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);

            if (chunk == null && iServer != null) {
                // 2. Singleplayer: request the chunk from the integrated server asynchronously.
                //    Cap outstanding requests so we don't flood the server thread with disk I/O.
                if (pendingLoad.size() >= MAX_PENDING_SERVER_LOADS) {
                    // Server load slots full; put this entry back and stop for this tick.
                    // The entry stays at the front of the queue (minimum distance), so it
                    // is the very next thing processed once a slot frees up.
                    candidateQueue.add(entry);
                    break;
                }
                pendingLoad.add(colKey);
                final int fcx = cx, fcz = cz;
                final var dim = mc.level.dimension();
                iServer.execute(() -> {
                    try {
                        ServerLevel sl = iServer.getLevel(dim);
                        if (sl == null) { pendingLoad.remove(colKey(fcx, fcz)); return; }
                        // getChunk with ChunkStatus.FULL and create=true forces the chunk to load.
                        // On an already-generated world this is fast (just reads region file).
                        var c = sl.getChunkSource().getChunk(fcx, fcz, ChunkStatus.FULL, true);
                        if (c instanceof LevelChunk lc) {
                            serverReadyChunks.addLast(lc);
                        } else {
                            pendingLoad.remove(colKey(fcx, fcz));
                        }
                    } catch (Exception e) {
                        pendingLoad.remove(colKey(fcx, fcz));
                    }
                });
                requested++;
                continue;
            }

            if (chunk == null) continue; // dedicated server — nothing we can do client-side

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

        if (generated > 0 || requested > 0) {
            Logger.info("[AutoGen] generated=" + generated + " requested=" + requested
                    + " pending=" + pendingLoad.size() + " near (" + playerCX + "," + playerCZ + ")");
        }

        // Upload client-generated sections to server (rate-limited)
        if (!uploadQueue.isEmpty() && ServerConfigOverride.INSTANCE.autoGenerationEnabled()) {
            drainUploads(mc, engine);
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

        int count = 0;
        for (int r = 0; r <= scanRadius && count < SCAN_BATCH; r++) {
            // Iterate the perimeter of the square at Chebyshev distance r
            for (int dx = -r; dx <= r && count < SCAN_BATCH; dx++) {
                for (int dz = -r; dz <= r && count < SCAN_BATCH; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // only perimeter
                    int cx = playerCX + dx;
                    int cz = playerCZ + dz;
                    if (submitted.contains(colKey(cx, cz))) continue;
                    long dist = (long) dx * dx + (long) dz * dz;
                    candidateQueue.add(new long[]{dist, cx, cz});
                    count++;
                }
            }
        }
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
