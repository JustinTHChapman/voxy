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
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages server-side LOD voxelization and per-player delivery queues.
 *
 * <p>When a chunk is tracked by the server it is voxelized using the server's own
 * {@link Mapper} (backed by in-memory storage so no on-disk persistence is required
 * server-side).  The resulting mipmapped {@link VoxelizedSection}s are queued for each
 * connected player and delivered at a rate-limited N-sections-per-tick.</p>
 */
public class ServerLodTracker {

    private static int sectionsPerTick() {
        return VoxyCommonConfig.LOD_CHUNKS_PER_TICK.get();
    }

    // Per-dimension state
    private static final class DimState {
        final Mapper mapper = new Mapper(new InMemoryMappingStorage());
        /** key = (sectionY << 40 | chunkZ << 20 | chunkX) – stored serialized sections per chunk column */
        final ConcurrentHashMap<Long, S2CLodSectionPacket[]> sectionCache = new ConcurrentHashMap<>();
    }

    /** Maps dimension resource-location string → state */
    private final ConcurrentHashMap<String, DimState> dims = new ConcurrentHashMap<>();

    /** Per-player delivery queue: packets waiting to be flushed */
    private final ConcurrentHashMap<ServerPlayer, Deque<S2CLodSectionPacket>> playerQueues = new ConcurrentHashMap<>();

    // ---- public API -------------------------------------------------

    public void onPlayerJoin(ServerPlayer player) {
        playerQueues.put(player, new ArrayDeque<>());

        if (!VoxyCommonConfig.LOD_SEND_ON_JOIN.get()) return;

        // Send a manifest so the client can do delta sync (only request what it's missing)
        String dimId = player.serverLevel().dimension().location().toString();
        DimState state = dims.get(dimId);
        if (state == null) return;

        // Collect (position, hash) for all cached sections
        var positions = new java.util.ArrayList<Long>();
        var hashes    = new java.util.ArrayList<Integer>();
        for (var entry : state.sectionCache.entrySet()) {
            S2CLodSectionPacket[] pkts = entry.getValue();
            if (pkts == null) continue;
            for (S2CLodSectionPacket p : pkts) {
                if (p == null) continue;
                // Encode the section position as a WorldEngine key for the manifest
                long posKey = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(
                        0, p.sectionX(), p.sectionY(), p.sectionZ());
                positions.add(posKey);
                hashes.add(p.contentHash());
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

    /** Called once per server tick to send queued packets. */
    public void onServerTick() {
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
     * Voxelizes the given chunk and enqueues resulting LOD section packets for all
     * currently-tracked players in the same dimension.
     */
    public void onChunkWatch(ServerLevel level, LevelChunk chunk, ServerPlayer player) {
        String dimId = level.dimension().location().toString();
        DimState state = dims.computeIfAbsent(dimId, k -> new DimState());

        long colKey = columnKey(chunk.getPos().x, chunk.getPos().z);

        // Only voxelize once per chunk column; cache and re-use
        S2CLodSectionPacket[] cached = state.sectionCache.get(colKey);
        if (cached == null) {
            cached = voxelizeChunk(level, chunk, state.mapper, dimId);
            if (cached != null) {
                state.sectionCache.put(colKey, cached);
            }
        }

        if (cached != null) {
            Deque<S2CLodSectionPacket> q = playerQueues.get(player);
            if (q != null) {
                for (S2CLodSectionPacket p : cached) {
                    if (p != null) q.addLast(p);
                }
            }
        }
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
