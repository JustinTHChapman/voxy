package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.InMemoryMappingStorage;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
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

    /** Maximum LOD section packets sent to a single player per server tick. */
    private static final int SECTIONS_PER_TICK = 16;

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
        // Queue all already-voxelized sections for this player
        String dimId = player.serverLevel().dimension().location().toString();
        DimState state = dims.get(dimId);
        if (state != null) {
            Deque<S2CLodSectionPacket> q = playerQueues.get(player);
            if (q != null) {
                for (S2CLodSectionPacket[] pkts : state.sectionCache.values()) {
                    for (S2CLodSectionPacket p : pkts) {
                        if (p != null) q.addLast(p);
                    }
                }
                Logger.info("[VoxyServer] Queued " + q.size() + " LOD sections for " + player.getGameProfile().getName());
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
            while (sent < SECTIONS_PER_TICK && !queue.isEmpty()) {
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
