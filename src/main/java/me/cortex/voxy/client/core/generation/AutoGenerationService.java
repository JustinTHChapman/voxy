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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

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
    /** Movement in a single tick beyond this many chunks is treated as a teleport. */
    private static final int TELEPORT_THRESHOLD_CHUNKS = 32;
    /** Auto-expiring chunk-load ticket: triggers async loading without blocking; self-cleans once we stop refreshing it. */
    private static final TicketType<ChunkPos> VOXY_LOAD_TICKET =
            TicketType.create("voxy_autogen_load", Comparator.comparingLong(ChunkPos::toLong), 60);
    /**
     * STOPGAP (time-based): once a chunk is FULL but its lighting still isn't correct, wait at most
     * this long before voxelizing it anyway, so a never-settling {@code isLightCorrect()} can't leave
     * a permanent LOD gap. To be replaced by a deterministic light-ready signal (drive the MC light
     * engine, or compute light ourselves) on branch {@code lightengine-driven-lod}.
     */
    private static final long LIGHT_FALLBACK_NANOS = 2_000_000_000L; // 2s

    /** Lerp factor when fog frontier needs to shrink (player approaching edge). */
    private static final float FOG_LERP_SHRINK = 0.15f;
    /** Lerp factor when fog frontier needs to grow (new chunks generated). */
    private static final float FOG_LERP_GROW   = 0.05f;

    /** Radial samples used to estimate the fog frontier each tick. */
    private static final int FOG_DIRECTIONS = 16;
    /** Consecutive empty columns along a ray that mark the true LOD edge (ignores small interior holes). */
    private static final int FOG_EDGE_EMPTY_RUN = 3;
    private static final float[] FOG_DIR_X = new float[FOG_DIRECTIONS];
    private static final float[] FOG_DIR_Z = new float[FOG_DIRECTIONS];
    static {
        for (int i = 0; i < FOG_DIRECTIONS; i++) {
            double a = (2 * Math.PI * i) / FOG_DIRECTIONS;
            FOG_DIR_X[i] = (float) Math.cos(a);
            FOG_DIR_Z[i] = (float) Math.sin(a);
        }
    }

    // Candidate chunks packed as a single long: (dist² << 20) | ((dx+256) << 10) | (dz+256)
    // where dx/dz are chunk offsets from lastPlayerCX/CZ (both ±256 max → 10 bits each after bias).
    // Natural long ordering gives closest-first (dist in high bits) with zero per-entry allocation.
    private final LongHeapPriorityQueue candidateQueue = new LongHeapPriorityQueue();
    private final Set<Long> submitted    = new LongOpenHashSet();
    private final Set<Long> pendingLoad  = new LongOpenHashSet();   // requested from server thread, not yet back

    /** Chunks loaded by the server thread and ready to ingest on the client tick. */
    private final ConcurrentLinkedDeque<LevelChunk> serverReadyChunks = new ConcurrentLinkedDeque<>();

    /** Column keys whose async server load failed; drained on the client tick to free pendingLoad slots. */
    private final ConcurrentLinkedDeque<Long> failedServerLoads = new ConcurrentLinkedDeque<>();

    /** Client→server handoff of columns to force-load (drained on the integrated-server thread). */
    private final ConcurrentLinkedQueue<Long> loadRequests = new ConcurrentLinkedQueue<>();
    /** Server-thread-only: columns we currently hold a load ticket for → request time (nanos), for timeout. */
    private final Long2LongOpenHashMap ticketed = new Long2LongOpenHashMap();
    /** Server-thread-only: dimension of the last server poll, to detect dimension changes. */
    private ResourceKey<Level> serverPollDim = null;

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

    /** Current player chunk position, updated every tick for inter-rebuild fog adjustment. */
    private volatile int currentPlayerCX = 0;
    private volatile int currentPlayerCZ = 0;

    /**
     * Smoothed fog frontier in blocks, lerped toward the raw frontier each tick.
     * Shrinks faster than it grows so empty LOD sections are hidden promptly.
     */
    private volatile float smoothedFogFrontierBlocks = 0f;

    /**
     * Holds the result of the background DB scan.  Null = not started / reset.
     * While a scan is running this holds the per-launch sentinel object; once
     * the scan thread completes it replaces the sentinel with the real result via
     * compareAndSet so stale results from a previous session cannot corrupt a new one.
     */
    private final AtomicReference<LongOpenHashSet> dbScanResult = new AtomicReference<>(null);
    /**
     * The sentinel object used for the currently active DB scan.  Each launch
     * creates a fresh instance so that a lingering scan thread from a previous
     * session can detect (via compareAndSet) that its result is stale.
     */
    private volatile LongOpenHashSet activeScanSentinel = null;
    /** True once the DB scan result has been merged into {@link #submitted}. */
    private boolean dbPopulated = false;
    /** Dimension resource-location string as of the last tick, for dimension-change detection. */
    private String lastDimension = null;

    /**
     * Set while {@code /voxy regen} is wiping storage off-thread. {@link #tick()} short-circuits so
     * generation does not run — and, crucially, so the background DB pre-scan does not launch and read
     * the half-wiped table, which would re-mark every about-to-be-deleted column as {@link #submitted}
     * and stop it ever regenerating. Cleared by {@link #reset()} once the wipe has fully completed.
     */
    private volatile boolean wiping = false;

    private AutoGenerationService() {}

    /**
     * Pause auto-generation while {@code /voxy regen} wipes storage off-thread. Must be paired with a
     * {@link #reset()} once the wipe completes (reset clears the pause and re-scans the now-empty DB).
     */
    public void beginWipe() {
        this.wiping = true;
    }

    public void reset() {
        candidateQueue.clear();
        submitted.clear();
        pendingLoad.clear();
        serverReadyChunks.clear();
        failedServerLoads.clear();
        loadRequests.clear();
        uploadQueue.clear();
        lastPlayerCX = Integer.MIN_VALUE;
        lastPlayerCZ = Integer.MIN_VALUE;
        currentPlayerCX = 0;
        currentPlayerCZ = 0;
        smoothedFogFrontierBlocks = 0f;
        smoothedMspt = 50f;
        lastTickNano = 0;
        activeScanSentinel = null;
        dbScanResult.set(null);
        dbPopulated = false;
        lastDimension = null;
        // Resume generation: a fresh DB pre-scan will run next tick. When reset() follows a regen wipe
        // the table is now empty, so the scan finds nothing pre-submitted and the world regenerates.
        wiping = false;
    }

    /** Smoothed block radius to the LOD generation frontier, for use as fog end distance. */
    public float getFogFrontierBlockRadius() {
        return smoothedFogFrontierBlocks;
    }

    /**
     * Estimate the generated-LOD frontier radius (in chunks) around the player by walking
     * outward along {@link #FOG_DIRECTIONS} compass directions and finding where the data runs
     * out. A run of {@link #FOG_EDGE_EMPTY_RUN} consecutive empty columns marks the real edge,
     * so isolated interior holes don't collapse the frontier. Averaged across directions for a
     * smooth, isotropic radius. Cheap enough to call every tick.
     */
    private float computeFrontierChunks(int pcx, int pcz, int scanRadius) {
        float sum = 0f;
        for (int d = 0; d < FOG_DIRECTIONS; d++) {
            float ex = FOG_DIR_X[d];
            float ez = FOG_DIR_Z[d];
            int frontier = scanRadius;
            int emptyRun = 0;
            for (int r = 1; r <= scanRadius; r++) {
                long key = colKey(pcx + Math.round(ex * r), pcz + Math.round(ez * r));
                if (submitted.contains(key) || pendingLoad.contains(key)) {
                    emptyRun = 0;
                } else if (++emptyRun >= FOG_EDGE_EMPTY_RUN) {
                    frontier = r - FOG_EDGE_EMPTY_RUN + 1;
                    break;
                }
            }
            sum += frontier;
        }
        return sum / FOG_DIRECTIONS;
    }

    /** Called once per client tick from the NeoForge ClientTickEvent listener. */
    public void tick() {
        // A regen wipe is deleting storage off-thread; do nothing (and don't launch the DB pre-scan)
        // until reset() clears this once the wipe has finished. Prevents the pre-scan from reading the
        // half-wiped table and re-marking wiped columns as already generated.
        if (this.wiping) return;
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

        // Dimension-change detection: reset DB scan state and submitted cache so we
        // don't carry over column keys from the previous dimension (e.g. after portals).
        String dimId = mc.level.dimension().location().toString();
        if (!dimId.equals(lastDimension)) {
            lastDimension = dimId;
            activeScanSentinel = null;      // invalidate any in-flight scan from the old dimension
            dbScanResult.set(null);
            dbPopulated = false;
            submitted.clear();
            pendingLoad.clear();
            candidateQueue.clear();
            // Drop any old-dimension work still queued across ticks so it can't be ingested
            // into the new dimension's engine.
            serverReadyChunks.clear();
            uploadQueue.clear();
            failedServerLoads.clear();
            loadRequests.clear();
            lastPlayerCX = Integer.MIN_VALUE;
        }

        // Non-blocking DB pre-scan: kick off a daemon thread on first tick (per dimension),
        // merge its result into submitted on a subsequent tick once it completes.
        // A per-launch sentinel object (activeScanSentinel) lets the scan thread detect
        // via compareAndSet that its result is stale if a reset or dimension change occurred
        // while it was running — preventing old results from corrupting a new session.
        if (!dbPopulated) {
            LongOpenHashSet scanResult = dbScanResult.get();
            if (scanResult == null) {
                // First tick for this dimension — launch a background scan.
                LongOpenHashSet sentinel = new LongOpenHashSet(0);
                activeScanSentinel = sentinel;
                dbScanResult.set(sentinel);
                final WorldEngine eng = engine;
                Thread t = new Thread(() -> {
                    LongOpenHashSet result = new LongOpenHashSet();
                    eng.storage.iteratePositions(0, pos -> {
                        if (dbScanResult.get() != sentinel) return; // aborted by reset/dimension change
                        // LOD0 storage is keyed by raw chunk coords (one section = one chunk),
                        // so a stored position maps directly to one candidate column.
                        result.add(colKey(WorldEngine.getX(pos), WorldEngine.getZ(pos)));
                    });
                    // Only publish the result if our sentinel is still the active one;
                    // a concurrent reset or dimension change will have replaced it with null.
                    if (dbScanResult.compareAndSet(sentinel, result)) {
                        Logger.debug("[AutoGen] DB pre-scan complete: " + result.size() + " columns pre-submitted");
                    }
                }, "voxy-db-scan");
                t.setDaemon(true);
                t.start();
            } else if (scanResult != activeScanSentinel) {
                // Scan finished (result is not the sentinel) — merge on the client thread.
                var it = scanResult.longIterator();
                while (it.hasNext()) submitted.add(it.nextLong());
                dbPopulated = true;
                // The frontier is only recomputed by rebuildQueue (on movement / empty queue), so the
                // file LODs we just merged wouldn't reach the fog until the player moves. Rebuild now so
                // the fog snaps out to the file-LOD edge immediately instead of hugging the player.
                rebuildQueue(playerCX, playerCZ);
            }
            // else: scan still in progress — nothing to do this tick.
        }

        // Detect teleports: movement larger than TELEPORT_THRESHOLD in a single tick.
        // Reset the fog frontier so it reflects the new position, not the old one
        // (otherwise fog lingers at the pre-teleport distance for several seconds).
        int dcx = playerCX - lastPlayerCX;
        int dcz = playerCZ - lastPlayerCZ;
        if (lastPlayerCX != Integer.MIN_VALUE
                && (Math.abs(dcx) > TELEPORT_THRESHOLD_CHUNKS || Math.abs(dcz) > TELEPORT_THRESHOLD_CHUNKS)) {
            smoothedFogFrontierBlocks = 0f;
        }

        // Rebuild the candidate queue when the player has moved significantly
        if (candidateQueue.isEmpty() || dcx * dcx + dcz * dcz >= REBUILD_THRESHOLD_CHUNKS * REBUILD_THRESHOLD_CHUNKS) {
            rebuildQueue(playerCX, playerCZ);
        }

        // Live fog frontier: estimate the LOD edge around the player every tick and lerp toward
        // it (faster when shrinking than growing) so the fog grows and shrinks smoothly instead
        // of snapping at discrete queue rebuilds.
        int fogScanRadius = Math.min(ServerConfigOverride.INSTANCE.effectiveLodRadius(), 256);
        // Pull the fog in by one chunk: the frontier estimate sits at the first empty column, i.e. one
        // chunk past the last column that actually has data, so without this the fog ends one chunk
        // beyond the real LOD edge and you can see the hard cutoff.
        float rawFrontier = Math.max(0f, computeFrontierChunks(playerCX, playerCZ, fogScanRadius) - 1f) * 16f;
        float current = smoothedFogFrontierBlocks;
        float lerp = (rawFrontier < current) ? FOG_LERP_SHRINK : FOG_LERP_GROW;
        smoothedFogFrontierBlocks = current + (rawFrontier - current) * lerp;

        // Free pending-load slots for async server loads that failed (couldn't load/generate).
        Long failedKey;
        while ((failedKey = failedServerLoads.poll()) != null) {
            pendingLoad.remove(failedKey.longValue());
        }

        // Drain any chunks the server thread has finished loading
        int drained = 0;
        LevelChunk ready;
        while ((ready = serverReadyChunks.poll()) != null) {
            long k = colKey(ready.getPos().x, ready.getPos().z);
            pendingLoad.remove(k);
            // Discard chunks from a different dimension — a late async getChunkFuture callback can
            // fire after a portal switch. Compare by dimension location, NOT WorldIdentifier:
            // biomeSeed can differ between the integrated-server level and the client level.
            if (!ready.getLevel().dimension().location().toString().equals(dimId)) continue;
            if (!submitted.contains(k)) {
                // Use enqueueIngestServer: bypasses the LIGHT_AND_DATA gate that
                // causes server-side chunks to be silently dropped when accessed
                // from the client thread.
                boolean ok = instance.getIngestService().enqueueIngestServer(engine, ready);
                if (ok) {
                    submitted.add(k);
                    uploadQueue.addLast(ready);
                    drained++;
                }
            }
        }
        if (drained > 0) {
            Logger.debug("[AutoGen] Ingested " + drained + " server-loaded chunk(s) near (" + playerCX + "," + playerCZ + ")");
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
        int maxPending = VoxyCommonConfig.AUTO_GEN_MAX_PENDING_LOADS.get();

        while ((generated + requested) < rate && !candidateQueue.isEmpty()) {
            long packed = candidateQueue.dequeueLong();
            int dx = (int)((packed >> 10) & 0x3FF) - 256;
            int dz = (int)(packed & 0x3FF) - 256;
            int cx = lastPlayerCX + dx;
            int cz = lastPlayerCZ + dz;
            long colKey = colKey(cx, cz);

            if (submitted.contains(colKey) || pendingLoad.contains(colKey)) continue;

            // Check if LOD data already exists in the DB for this column.
            // If so, mark submitted and skip server chunk request — the rendering system
            // will load the stored data on demand without needing the source chunk.
            if (engine.storage.containsColumn(0, cx, cz,
                    mc.level.getMinSection(), mc.level.getMaxSection())) {
                submitted.add(colKey);
                continue;
            }

            // 1. Try client chunk cache first (chunks within vanilla render distance)
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);

            if (chunk == null && iServer != null) {
                // 2. Singleplayer: register the column for an async force-load. serverPoll() (on the
                //    integrated-server thread) adds a transient auto-expiring ticket so the server
                //    loads/generates it over the next ticks, then we pick it up via getChunkNow once
                //    ready — NEVER calling getChunk/getChunkFuture(create=true) on the server thread,
                //    which would block it via managedBlock. Concurrent loads are capped by config.
                if (pendingLoad.size() >= maxPending) {
                    // At the concurrent-load cap; put this entry back and stop for this tick.
                    candidateQueue.enqueue(packed);
                    break;
                }
                pendingLoad.add(colKey);
                loadRequests.add(colKey);
                requested++;
                continue;
            }

            if (chunk == null) continue; // dedicated server — nothing we can do client-side

            boolean ok = instance.getIngestService().enqueueIngest(engine, chunk);
            if (ok) {
                submitted.add(colKey);
                generated++;
                uploadQueue.addLast(chunk);
            }
            // If enqueueIngest returns false (lighting not ready), the entry is dropped
            // from the queue.  It will be re-added on the next rebuildQueue() call when
            // the player moves or the current batch empties.
        }

        // Bound the upload backlog so it can never pin LevelChunks unbounded.
        int maxUpload = VoxyCommonConfig.AUTO_GEN_MAX_UPLOAD_QUEUE.get();
        while (uploadQueue.size() > maxUpload) uploadQueue.pollFirst();
        // Upload client-generated sections to server (rate-limited)
        if (!uploadQueue.isEmpty() && ServerConfigOverride.INSTANCE.autoGenerationEnabled()) {
            drainUploads(mc, engine);
        }

        // Drive the async chunk-load tickets on the integrated-server thread (non-blocking):
        // intake new requests, (re)add load tickets, and hand back any chunks now loaded.
        if (iServer != null && !pendingLoad.isEmpty()) {
            final MinecraftServer server = iServer;
            final ResourceKey<Level> pollDim = mc.level.dimension();
            iServer.execute(() -> serverPoll(server, pollDim));
        }
    }

    /**
     * Runs on the integrated-server thread once per client tick. Adds a transient, auto-expiring
     * load ticket for each outstanding column so the server loads/generates it asynchronously, and
     * hands back any chunk that has reached FULL. Never blocks — no managedBlock, no join. Tickets
     * we stop refreshing (loaded / failed / dimension change) expire on their own, so chunks unload.
     */
    private void serverPoll(MinecraftServer server, ResourceKey<Level> dim) {
        ServerLevel sl = server.getLevel(dim);
        if (sl == null) return;
        // Dimension changed since the last poll: abandon old tickets (they auto-expire on the old
        // level) so we never add tickets for old-dimension columns onto the new level.
        if (!dim.equals(serverPollDim)) {
            ticketed.clear();
            serverPollDim = dim;
        }
        var cc = sl.getChunkSource();

        // Intake newly-requested columns.
        Long req;
        while ((req = loadRequests.poll()) != null) {
            ticketed.putIfAbsent(req.longValue(), System.nanoTime());
        }

        // (Re)add a load ticket for each outstanding column and collect any that are ready.
        long pollNow = System.nanoTime();
        var it = ticketed.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long ck = e.getLongKey();
            int ccx = (int) (ck >> 32);
            int ccz = (int) ck;
            ChunkPos cp = new ChunkPos(ccx, ccz);
            // radius 0 → ticket level 33 (FULL); triggers async load over subsequent server ticks.
            cc.addRegionTicket(VOXY_LOAD_TICKET, cp, 0, cp);
            LevelChunk lc = cc.getChunkNow(ccx, ccz);
            // Prefer to hand the chunk back once it is FULL *and* the threaded light engine has
            // finished lighting it: getChunkNow can return a FULL chunk whose lighting is still in
            // flight (isLightCorrect() == false), and voxelizing it then bakes a DARK LOD. So we keep
            // refreshing the ticket and re-poll until the light is ready — but only up to a fallback.
            //
            // TODO(stopgap): these time-based fallbacks are a hack. The proper fix is to determine LOD
            // light deterministically — drive MC's light engine to a real completion signal (or compute
            // light ourselves) rather than waiting a fixed duration. Planned on branch
            // `lightengine-driven-lod`. Until then, the fallbacks below just prevent permanent gaps.
            long age = pollNow - e.getLongValue();
            if (lc != null && (lc.isLightCorrect() || age > LIGHT_FALLBACK_NANOS)) {
                // Hand it back when the light is correct, OR after a short fallback wait. The fallback
                // matters: in some modded worlds isLightCorrect() NEVER flips true on these non-ticking
                // border chunks, so without it the column would fail forever — a permanent LOD gap that
                // also pins the fog frontier. A possibly-dark LOD beats a hole; it re-bakes correctly
                // when the player gets near or via /voxy regen.
                //
                // setUnsaved(false): voxy only force-loaded this chunk to voxelize it; it must NOT enter
                // Minecraft's chunk-save path (bloats/hangs the save). The LOD lives in voxy's storage
                // and the source chunk regenerates deterministically if the player ever visits. These
                // are distant chunks outside the client render distance, so no player edits are at stake.
                lc.setUnsaved(false);
                serverReadyChunks.addLast(lc);
                it.remove();
            }
            // NO load timeout: if the chunk hasn't reached FULL yet, we just keep refreshing the ticket
            // and re-poll next tick. A column is NEVER skipped/dropped — slow distant worldgen (heavy
            // modpacks) can legitimately take a long time, and dropping it leaves a permanent gap that
            // also breaks neighbour face-culling at the hole's edges. It stays pending until it loads.
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
            // Skip chunks not belonging to the current dimension (stale across a portal switch).
            if (!chunk.getLevel().dimension().location().toString().equals(dimId)) continue;
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

        // Trim the submitted set when a user-configured limit is exceeded.
        // The set is only a performance cache (avoids DB round-trips for known-generated
        // columns); clearing it is safe — containsColumn() re-validates at dequeue time.
        int submittedLimit = VoxyCommonConfig.AUTO_GEN_SUBMITTED_LIMIT.get();
        if (submittedLimit > 0 && submitted.size() > submittedLimit) {
            submitted.clear();
        }

        int radius = ServerConfigOverride.INSTANCE.effectiveLodRadius();
        // Clamp scan radius to avoid extremely large queues
        int scanRadius = Math.min(radius, 256);

        // Scan the full radius in one pass. The priority queue sorts by Euclidean
        // distance squared so chunks are popped closest-first, giving a circular
        // generation front rather than the square front that a Chebyshev-ordered
        // scan would produce. (The fog frontier is computed separately, per-tick.)
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                long dist = (long) dx * dx + (long) dz * dz;
                if (dist > (long) scanRadius * scanRadius) continue; // circular clip
                int cx = playerCX + dx;
                int cz = playerCZ + dz;
                long key = colKey(cx, cz);
                if (submitted.contains(key) || pendingLoad.contains(key)) continue;
                candidateQueue.enqueue((dist << 20) | ((long)(dx + 256) << 10) | (long)(dz + 256));
            }
        }
    }

    private static long colKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
