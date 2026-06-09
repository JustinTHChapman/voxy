package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.InMemoryMappingStorage;
import me.cortex.voxy.common.config.VoxyCommonConfig;
import me.cortex.voxy.common.network.C2SLodSectionPacket;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
import me.cortex.voxy.common.network.S2CManifestPacket;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages server-side LOD voxelization and per-player delivery queues.
 *
 * <p>When a chunk is tracked by the server it is voxelized using the server's own
 * {@link Mapper} (backed by in-memory storage so no on-disk persistence is required
 * server-side).  The resulting mipmapped {@link VoxelizedSection}s are queued for each
 * connected player and delivered at a rate-limited N-sections-per-tick.</p>
 *
 * <p>Voxelization is deferred: {@link #onChunkWatch} enqueues chunks into a pending
 * queue, and {@link #onServerTick} drains that queue up to a dynamically-computed
 * time budget per tick.  This prevents MSPT spikes when many chunks become visible
 * simultaneously (e.g. on player join or teleport).</p>
 */
public class ServerLodTracker {

    private static int sectionsPerTick() {
        return VoxyCommonConfig.LOD_CHUNKS_PER_TICK.get();
    }

    /** Fraction of available tick headroom (50ms − smoothedMspt) to spend on voxelization. */
    private static final float VOXEL_HEADROOM_FRACTION = 0.30f;
    /**
     * Hard cap on the deferred voxelization deque.  Each entry holds a reference to a
     * full LevelChunk (up to several MB).  Exceeding this many queued chunks — which can
     * happen during extreme lag spikes or mass player joins — risks an OOM.  New work is
     * dropped (with a warning) once the cap is reached; the chunk will be re-queued on
     * the next onChunkWatch call from the server.
     */
    private static final int MAX_PENDING_VOXELIZATIONS = 512;

    /** Maximum number of chunk columns held in each dimension's section cache (LRU eviction). */
    private static final int SECTION_CACHE_MAX = 8192;

    // Per-dimension state
    private static final class DimState {
        final Mapper mapper = new Mapper(new InMemoryMappingStorage());
        /** LRU-bounded map: colKey → voxelized section packets. Evicts least-recently-used
         *  entries when the map exceeds SECTION_CACHE_MAX to prevent unbounded growth on
         *  long-running servers where players explore large areas. */
        final Map<Long, S2CLodSectionPacket[]> sectionCache = Collections.synchronizedMap(
            new LinkedHashMap<Long, S2CLodSectionPacket[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, S2CLodSectionPacket[]> eldest) {
                    return size() > SECTION_CACHE_MAX;
                }
            }
        );
    }

    /** Maps dimension resource-location string → state */
    private final ConcurrentHashMap<String, DimState> dims = new ConcurrentHashMap<>();

    /** Per-player delivery queue: packets waiting to be flushed */
    private final ConcurrentHashMap<ServerPlayer, Deque<S2CLodSectionPacket>> playerQueues = new ConcurrentHashMap<>();

    // ---- deferred voxelization ----------------------------------------

    private record PendingVoxelization(ServerLevel level, LevelChunk chunk, String dimId, long colKey) {}

    /** Chunks waiting to be voxelized (FIFO, closest-watched first). */
    private final ConcurrentLinkedDeque<PendingVoxelization> pendingVoxelizations = new ConcurrentLinkedDeque<>();

    /** O(1) size counter for {@link #pendingVoxelizations} (ConcurrentLinkedDeque.size() is O(n)). */
    private final AtomicInteger pendingVoxelizationCount = new AtomicInteger();

    /**
     * Players waiting for a specific chunk column to finish voxelization.
     * Keyed by the same colKey used in {@link DimState#sectionCache}.
     * Entry is removed once voxelization completes (or fails).
     */
    private final ConcurrentHashMap<Long, List<ServerPlayer>> pendingPlayers = new ConcurrentHashMap<>();

    /** EWMA-smoothed server tick duration (ms). Seed at 20 ms (a healthy server). */
    private float smoothedServerMspt = 20f;
    /** Nanotime of the start of the previous server tick, for EWMA update. */
    private long lastServerTickNano = 0;

    // ---- public API -------------------------------------------------

    public void onPlayerJoin(ServerPlayer player) {
        playerQueues.put(player, new ArrayDeque<>());

        if (!VoxyCommonConfig.LOD_SEND_ON_JOIN.get()) return;

        // Send a manifest so the client can do delta sync (only request what it's missing)
        String dimId = player.serverLevel().dimension().location().toString();
        DimState state = dims.get(dimId);
        if (state == null) return;

        // Collect (position, hash) for all cached sections.
        // Must hold the sectionCache monitor for the full iteration since the underlying
        // LinkedHashMap is not safe for concurrent structural access.
        var positions = new java.util.ArrayList<Long>();
        var hashes    = new java.util.ArrayList<Integer>();
        synchronized (state.sectionCache) {
            for (var entry : state.sectionCache.entrySet()) {
                S2CLodSectionPacket[] pkts = entry.getValue();
                if (pkts == null) continue;
                for (S2CLodSectionPacket p : pkts) {
                    if (p == null) continue;
                    long posKey = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(
                            0, p.sectionX(), p.sectionY(), p.sectionZ());
                    positions.add(posKey);
                    hashes.add(p.contentHash());
                }
            }
        }

        // Send in batches
        int total = positions.size();
        for (int start = 0; start < Math.max(total, 1); start += S2CManifestPacket.MAX_ENTRIES_PER_PACKET) {
            int end     = Math.min(start + S2CManifestPacket.MAX_ENTRIES_PER_PACKET, total);
            boolean fin = end >= total;
            long[] posArr  = new long[end - start];
            int[]  hashArr = new int[end - start];
            for (int i = 0; i < posArr.length; i++) {
                posArr[i]  = positions.get(start + i);
                hashArr[i] = hashes.get(start + i);
            }
            PacketDistributor.sendToPlayer(player, new S2CManifestPacket(fin, posArr, hashArr));
            if (fin) break;
        }
        Logger.info("[VoxyServer] Sent manifest of " + total + " sections to " + player.getGameProfile().getName());
    }

    /** Called when a client requests specific sections by position key. */
    public void onClientRequest(ServerPlayer player, long[] sectionPositions) {
        String dimId = player.serverLevel().dimension().location().toString();
        DimState state = dims.get(dimId);
        if (state == null) return;
        Deque<S2CLodSectionPacket> q = playerQueues.get(player);
        if (q == null) return;

        int maxQueue = VoxyCommonConfig.MAX_TRANSFER_QUEUE_PER_CLIENT.get();
        for (long posKey : sectionPositions) {
            if (q.size() >= maxQueue) break;
            int lvl = me.cortex.voxy.common.world.WorldEngine.getLevel(posKey);
            int cx  = me.cortex.voxy.common.world.WorldEngine.getX(posKey);
            int cy  = me.cortex.voxy.common.world.WorldEngine.getY(posKey);
            int cz  = me.cortex.voxy.common.world.WorldEngine.getZ(posKey);
            // Find matching cached packets for this chunk column
            long colKey = columnKey(cx, cz);
            S2CLodSectionPacket[] pkts = state.sectionCache.get(colKey);
            if (pkts != null) {
                for (S2CLodSectionPacket p : pkts) {
                    if (p != null && p.sectionY() == cy) q.addLast(p);
                }
            }
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        playerQueues.remove(player);
    }

    /** Called once per server tick to send queued packets and drain deferred voxelizations. */
    public void onServerTick() {
        // ── 1. Update smoothed server MSPT (EWMA α = 0.1, ~10 tick window) ──────────
        long now = System.nanoTime();
        if (lastServerTickNano > 0) {
            float tickMs = Math.min((now - lastServerTickNano) / 1_000_000f, 200f);
            smoothedServerMspt = 0.9f * smoothedServerMspt + 0.1f * tickMs;
        }
        lastServerTickNano = now;

        // ── 2. Drain deferred voxelizations within a dynamic time budget ───────────
        // Budget = VOXEL_HEADROOM_FRACTION × (50 ms tick target − smoothed MSPT).
        // Clamped to [1 ms, 15 ms] so we always make progress but never starve the server.
        float headroomMs = Math.max(1f, 50f - smoothedServerMspt);
        long budgetNanos = (long)(Math.min(headroomMs * VOXEL_HEADROOM_FRACTION, 15f) * 1_000_000L);
        long voxelStart = System.nanoTime();
        int voxelized = 0;
        while (!pendingVoxelizations.isEmpty() && (System.nanoTime() - voxelStart) < budgetNanos) {
            PendingVoxelization pv = pendingVoxelizations.pollFirst();
            if (pv == null) break;
            pendingVoxelizationCount.decrementAndGet();

            DimState state = dims.get(pv.dimId());
            List<ServerPlayer> waiters = pendingPlayers.remove(pv.colKey());

            if (state != null) {
                S2CLodSectionPacket[] pkts = voxelizeChunk(pv.level(), pv.chunk(), state.mapper, pv.dimId());
                if (pkts != null) {
                    state.sectionCache.put(pv.colKey(), pkts);
                    if (waiters != null) {
                        for (ServerPlayer p : waiters) {
                            Deque<S2CLodSectionPacket> q = playerQueues.get(p);
                            if (q != null) {
                                for (S2CLodSectionPacket pkt : pkts) {
                                    if (pkt != null) q.addLast(pkt);
                                }
                            }
                        }
                    }
                }
            }
            voxelized++;
        }
        if (voxelized > 0 && !pendingVoxelizations.isEmpty()) {
            Logger.debug("[VoxyServer] Voxelized " + voxelized + " chunk(s) this tick ("
                    + pendingVoxelizations.size() + " remaining, smoothed MSPT "
                    + String.format("%.1f", smoothedServerMspt) + " ms)");
        }

        // ── 3. Send queued LOD packets to each player ──────────────────────────────
        for (Map.Entry<ServerPlayer, Deque<S2CLodSectionPacket>> entry : playerQueues.entrySet()) {
            ServerPlayer player = entry.getKey();
            Deque<S2CLodSectionPacket> queue = entry.getValue();
            int sent = 0;
            while (sent < sectionsPerTick() && !queue.isEmpty()) {
                S2CLodSectionPacket pkt = queue.pollFirst();
                if (pkt != null) {
                    PacketDistributor.sendToPlayer(player, pkt);
                    sent++;
                }
            }
        }
    }

    /**
     * Called when the server sends a chunk to a player.  If the chunk has already been
     * voxelized, the cached packets are queued for this player immediately.  Otherwise
     * the chunk is enqueued for deferred voxelization (processed in {@link #onServerTick}
     * up to a dynamically-computed time budget) so that many simultaneous chunk-watch
     * events (e.g. on player join / teleport) do not spike server MSPT.
     */
    public void onChunkWatch(ServerLevel level, LevelChunk chunk, ServerPlayer player) {
        String dimId = level.dimension().location().toString();
        DimState state = dims.computeIfAbsent(dimId, k -> new DimState());

        long colKey = columnKey(chunk.getPos().x, chunk.getPos().z);

        S2CLodSectionPacket[] cached = state.sectionCache.get(colKey);
        if (cached != null) {
            // Fast path: already voxelized, just queue for this player.
            Deque<S2CLodSectionPacket> q = playerQueues.get(player);
            if (q != null) {
                for (S2CLodSectionPacket p : cached) {
                    if (p != null) q.addLast(p);
                }
            }
            return;
        }

        // Slow path: not yet voxelized — schedule deferred work.
        // putIfAbsent returns null only on the FIRST call for this colKey, meaning we
        // add the chunk to pendingVoxelizations exactly once even when many players watch
        // the same not-yet-voxelized chunk simultaneously.
        List<ServerPlayer> waiters = new ArrayList<>();
        List<ServerPlayer> existing = pendingPlayers.putIfAbsent(colKey, waiters);
        if (existing == null) {
            // First request for this column — schedule voxelization, unless the queue is full.
            if (pendingVoxelizationCount.get() >= MAX_PENDING_VOXELIZATIONS) {
                pendingPlayers.remove(colKey);
                Logger.warn("[VoxyServer] Dropped chunk voxelization (queue full at "
                        + MAX_PENDING_VOXELIZATIONS + "): " + chunk.getPos());
                return;
            }
            pendingVoxelizations.addLast(new PendingVoxelization(level, chunk, dimId, colKey));
            pendingVoxelizationCount.incrementAndGet();
        } else {
            waiters = existing;
        }
        waiters.add(player);
    }

    // ---- internals --------------------------------------------------

    private static long columnKey(int cx, int cz) {
        return ((long) (cx & 0xFFFFF) << 20) | (cz & 0xFFFFF);
    }

    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE =
            ThreadLocal.withInitial(VoxelizedSection::createEmpty);

    /** Returns one S2CLodSectionPacket per non-empty section in the chunk column, or null on failure. */
    private static S2CLodSectionPacket[] voxelizeChunk(ServerLevel level, LevelChunk chunk, Mapper mapper, String dimId) {
        try {
            var lightEngine = level.getLightEngine();
            var sections = chunk.getSections();
            var packets = new java.util.ArrayList<S2CLodSectionPacket>(sections.length);

            int sectionIdx = chunk.getMinSection() - 1;
            for (LevelChunkSection section : sections) {
                sectionIdx++;
                if (section == null) continue;

                var sectionPos = SectionPos.of(chunk.getPos(), sectionIdx);

                DataLayer blockLightData = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                DataLayer skyLightData  = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                ILightingSupplier lightSupplier = buildLightSupplier(blockLightData, skyLightData);

                VoxelizedSection vs = SECTION_CACHE.get().setPosition(chunk.getPos().x, sectionIdx, chunk.getPos().z);
                vs = WorldConversionFactory.convert(vs, mapper, section.getStates(), section.getBiomes(), lightSupplier);
                WorldVoxilizedSectionMipper.mipSection(vs, mapper);

                packets.add(S2CLodSectionPacket.fromSection(dimId, vs, mapper));
            }
            return packets.isEmpty() ? null : packets.toArray(new S2CLodSectionPacket[0]);
        } catch (Exception e) {
            Logger.warn("[VoxyServer] Failed to voxelize chunk " + chunk.getPos() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Called when a client uploads a client-generated LOD section.
     * Deduplicates by contentHash, stores in the cache if new, and
     * queues the update for all other players in the same dimension.
     */
    public void onClientUpload(ServerPlayer uploader, C2SLodSectionPacket pkt) {
        if (!VoxyCommonConfig.AUTO_GENERATION_ENABLED_SERVER.get()) return;

        String dimId = uploader.serverLevel().dimension().location().toString();
        DimState state = dims.computeIfAbsent(dimId, k -> new DimState());
        long colKey = columnKey(pkt.sectionX(), pkt.sectionZ());

        // Build an equivalent S2C packet so it can slot into the existing cache/queue system
        S2CLodSectionPacket s2c = new S2CLodSectionPacket(
                pkt.dimensionId(), pkt.sectionX(), pkt.sectionY(), pkt.sectionZ(),
                pkt.contentHash(), pkt.lutSize(), pkt.vanillaBlockStateIds(),
                pkt.biomeRls(), pkt.lights(), pkt.indices());

        S2CLodSectionPacket[] cached = state.sectionCache.get(colKey);
        if (cached != null) {
            // Check if any section at this Y already has the same hash — if so, discard
            for (S2CLodSectionPacket existing : cached) {
                if (existing != null && existing.sectionY() == pkt.sectionY()
                        && existing.contentHash() == pkt.contentHash()) {
                    return; // identical — discard
                }
            }
        }

        // Store the new section in the cache (replace the entry for this Y level)
        if (cached == null) {
            cached = new S2CLodSectionPacket[]{s2c};
        } else {
            var list = new java.util.ArrayList<S2CLodSectionPacket>(java.util.Arrays.asList(cached));
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != null && list.get(i).sectionY() == pkt.sectionY()) {
                    list.set(i, s2c);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(s2c);
            cached = list.toArray(new S2CLodSectionPacket[0]);
        }
        state.sectionCache.put(colKey, cached);

        // Broadcast to all other players in this dimension
        final S2CLodSectionPacket toSend = s2c;
        for (var entry : playerQueues.entrySet()) {
            ServerPlayer other = entry.getKey();
            if (other == uploader) continue;
            if (!other.serverLevel().dimension().location().toString().equals(dimId)) continue;
            Deque<S2CLodSectionPacket> q = entry.getValue();
            if (q != null && q.size() < VoxyCommonConfig.MAX_TRANSFER_QUEUE_PER_CLIENT.get()) {
                q.addLast(toSend);
            }
        }
    }

    private static ILightingSupplier buildLightSupplier(DataLayer bla, DataLayer sla) {
        boolean bl = bla != null && !bla.isEmpty();
        boolean sl = sla != null && !sla.isEmpty();
        if (bl && sl) {
            return (x, y, z) -> {
                int block = Math.min(15, bla.get(x, y, z));
                int sky   = Math.min(15, sla.get(x, y, z));
                return (byte) (sky | (block << 4));
            };
        } else if (bl) {
            return (x, y, z) -> (byte) (Math.min(15, bla.get(x, y, z)) << 4);
        } else if (sl) {
            return (x, y, z) -> (byte) Math.min(15, sla.get(x, y, z));
        }
        return (x, y, z) -> (byte) 0;
    }
}
