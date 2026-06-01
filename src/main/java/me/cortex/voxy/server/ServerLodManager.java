package me.cortex.voxy.server;

import me.cortex.voxy.VoxyMod;
import me.cortex.voxy.common.config.VoxyConfig;
import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.LodMipper;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.lod.WorldManifest;
import me.cortex.voxy.common.storage.SectionStorageManager;
import me.cortex.voxy.common.storage.SqliteSectionStorage;
import me.cortex.voxy.common.voxelization.ChunkVoxelizer;
import me.cortex.voxy.network.payload.LodSectionDataPayload;
import me.cortex.voxy.network.payload.SectionRemovePayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Central server-side LOD manager.
 *
 * <ul>
 *   <li>Listens for chunk load events and voxelizes new/changed chunks.
 *   <li>Stores LOD sections per dimension in {@link SectionStorageManager}.
 *   <li>Generates higher LOD levels (1–N) by mipping.
 *   <li>Pushes live section updates to all connected clients.
 * </ul>
 *
 * <p>One instance exists per logical server lifetime, created in {@link VoxyServer}.
 */
public final class ServerLodManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerLodManager.class);

    private final MinecraftServer server;
    private final SectionStorageManager storageManager;
    private final ExecutorService voxelizerPool;
    private final LodDeliveryQueue deliveryQueue;

    /** Pending voxelization tasks submitted from the server thread. */
    private final ConcurrentLinkedQueue<LevelChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    public ServerLodManager(MinecraftServer server) {
        this.server = server;
        Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("voxy");
        this.storageManager = new SectionStorageManager(worldDir);
        this.deliveryQueue  = new LodDeliveryQueue(this);

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.voxelizerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "voxy-voxelizer");
            t.setDaemon(true);
            return t;
        });

        LOGGER.info("[Voxy] Server LOD manager started (voxelizer threads: {})", threads);
    }

    public LodDeliveryQueue getDeliveryQueue() {
        return deliveryQueue;
    }

    // -------------------------------------------------------------------------
    // Chunk events
    // -------------------------------------------------------------------------

    /** Called when a chunk is fully loaded on the server. */
    public void onChunkLoaded(ServerLevel level, LevelChunk chunk) {
        pendingChunks.add(chunk);
        voxelizerPool.submit(() -> processChunk(level, chunk));
    }

    private void processChunk(ServerLevel level, LevelChunk chunk) {
        try {
            LodSection lod0 = ChunkVoxelizer.voxelize(chunk);
            String dimKey = dimensionKey(level);
            SqliteSectionStorage storage = storageManager.get(dimKey);

            // Only store + broadcast if data actually changed
            if (!storage.isCurrent(lod0.key, lod0.hash)) {
                storage.store(lod0);
                broadcastSectionUpdate(level, lod0);
                generateHigherLods(level, storage, lod0);
            }
        } catch (Exception e) {
            LOGGER.error("[Voxy] Failed to voxelize chunk {}", chunk.getPos(), e);
        }
    }

    /**
     * Generates LOD levels 1 through {@code maxLodLevel} by mipping upward from the
     * newly updated LOD-0 section.  Only stores and broadcasts levels that actually change.
     */
    private void generateHigherLods(ServerLevel level, SqliteSectionStorage storage, LodSection lod0) {
        int maxLod = VoxyConfig.INSTANCE.serverMaxLodLevel.get();
        String dimKey = dimensionKey(level);
        LodSection current = lod0;

        for (int lodLevel = 1; lodLevel <= maxLod; lodLevel++) {
            int childLod = lodLevel - 1;
            int parentX  = SectionKey.sectionX(current.key) >> 1;
            int parentZ  = SectionKey.sectionZ(current.key) >> 1;

            // Load the other 3 siblings needed for mipping
            int baseX = parentX * 2;
            int baseZ = parentZ * 2;
            LodSection[] children = new LodSection[4];
            boolean allPresent = true;

            for (int dz = 0; dz <= 1; dz++) {
                for (int dx = 0; dx <= 1; dx++) {
                    long childKey = SectionKey.encode(childLod, baseX + dx, baseZ + dz);
                    var opt = storage.load(childKey);
                    if (opt.isEmpty()) { allPresent = false; break; }
                    children[dx + dz * 2] = opt.get();
                }
                if (!allPresent) break;
            }

            if (!allPresent) break; // can't mip yet

            LodSection mipped = LodMipper.mip(children);
            if (!storage.isCurrent(mipped.key, mipped.hash)) {
                storage.store(mipped);
                broadcastSectionUpdate(level, mipped);
            }
            current = mipped;
        }
    }

    // -------------------------------------------------------------------------
    // Manifest / transfer
    // -------------------------------------------------------------------------

    /** Builds and returns the current manifest for the given dimension. */
    public WorldManifest buildManifest(ResourceLocation dimension) {
        return storageManager.get(dimension.toString()).buildManifest();
    }

    /**
     * Sends all requested sections to the specified player.
     * Called from {@link ServerNetworkHandler} when the client requests sections.
     * Routes through the per-player {@link LodDeliveryQueue} for throttling.
     */
    public void sendSectionsToPlayer(ServerPlayer player, ResourceLocation dimension,
                                     java.util.List<Long> requestedKeys) {
        int maxQueue = VoxyConfig.INSTANCE.serverMaxTransferQueuePerClient.get();
        java.util.List<Long> capped = requestedKeys.size() <= maxQueue
                ? requestedKeys
                : requestedKeys.subList(0, maxQueue);
        if (capped.size() < requestedKeys.size()) {
            LOGGER.warn("[Voxy] Capping request from player {} to {} sections (requested {})",
                    player.getName().getString(), maxQueue, requestedKeys.size());
        }
        deliveryQueue.enqueueAdditional(player, dimension, capped);
    }

    /**
     * Pushes the radius-filtered manifest + section data to a newly-joined player
     * when {@code lodSendOnJoin} is enabled.
     */
    public void onPlayerJoin(ServerPlayer player) {
        if (!VoxyConfig.INSTANCE.lodSendOnJoin.get()) return;

        ResourceLocation dim = player.level().dimension().location();
        int requestedRadius = VoxyConfig.INSTANCE.clientLodRadius.get();
        int radius = Math.min(requestedRadius, VoxyConfig.INSTANCE.lodRadiusMax.get());

        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;

        java.util.List<Long> radiusKeys = LodDeliveryQueue.radiusKeys(cx, cz, radius);
        SqliteSectionStorage storage = storageManager.get(dim.toString());


        java.util.List<Long> existing = new java.util.ArrayList<>();
        java.util.List<Long> missing = new java.util.ArrayList<>();
        for (Long key : radiusKeys) {
            if (storage.load(key).isPresent()) {
                existing.add(key);
            } else {
                missing.add(key);
            }
        }

        // If GENERATE mode, schedule missing LOD-0 chunks for async loading via the server
        // tick executor — avoids blocking the event handler thread.
        if (VoxyConfig.INSTANCE.lodGenerationMode.get() == VoxyConfig.GenerationMode.GENERATE && !missing.isEmpty()) {
            ServerLevel level = (ServerLevel) player.level();
            int scheduleCount = 0;
            for (Long key : missing) {
                if (SectionKey.lodLevel(key) != 0) continue; // only request LOD-0 (base chunks)
                int x = SectionKey.sectionX(key);
                int z = SectionKey.sectionZ(key);
                // Queue on server thread so chunk loading uses the normal async machinery
                server.execute(() -> level.getChunk(x, z));
                scheduleCount++;
            }
            if (scheduleCount > 0) {
                LOGGER.info("[Voxy] Player {} joined — scheduled {} missing chunks for generation (radius {})",
                        player.getName().getString(), scheduleCount, radius);
            }
        }

        LOGGER.info("[Voxy] Player {} joined — queuing {} existing sections (radius {})", player.getName().getString(), existing.size(), radius);
        deliveryQueue.enqueue(player, dim, existing);
    }

    /**
     * Called from the server tick event. Drains the per-player delivery queues at the
     * configured rate.
     */
    public void tickDelivery() {
        int perTick = VoxyConfig.INSTANCE.lodChunksPerTick.get();
        deliveryQueue.tickDeliver(
                uuid -> server.getPlayerList().getPlayer(uuid),
                perTick
        );
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private void broadcastSectionUpdate(ServerLevel level, LodSection section) {
        ResourceLocation dim = level.dimension().location();
        LodSectionDataPayload pkt = new LodSectionDataPayload(
                dim, section.key, section.hash, section.toBytes());
        // Send to all players currently in this dimension
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    public void broadcastSectionRemove(ServerLevel level, long sectionKey) {
        ResourceLocation dim = level.dimension().location();
        SectionRemovePayload pkt = new SectionRemovePayload(dim, sectionKey);
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    /** Exposed for use by {@link ChunkEventHandler} on dimension unload. */
    public SectionStorageManager getStorageManager() {
        return storageManager;
    }

    // -------------------------------------------------------------------------

    private static String dimensionKey(ServerLevel level) {
        return level.dimension().location().toString();
    }

    @Override
    public void close() {
        voxelizerPool.shutdown();
        try {
            if (!voxelizerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                voxelizerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        storageManager.close();
        LOGGER.info("[Voxy] Server LOD manager stopped");
    }
}
