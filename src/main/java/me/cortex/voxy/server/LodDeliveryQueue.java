package me.cortex.voxy.server;

import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.storage.SqliteSectionStorage;
import me.cortex.voxy.network.payload.LodSectionDataPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Throttled per-player LOD section delivery queue.
 *
 * <p>Each connected client has its own ordered queue of section keys to be delivered.
 * On every server tick the queue ticks at most {@code lodChunksPerTick} sections.
 * If the connection drops, the queue is discarded.
 *
 * <p>This mirrors the {@code LodDeliveryQueue} / {@code PlayerTask} pattern from the
 * legacy {@code voxy_server_lod} companion mod.
 */
public final class LodDeliveryQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(LodDeliveryQueue.class);

    /** Per-player task. Public so tools / mods can inspect queued work. */
    public static final class PlayerTask {
        public final UUID playerId;
        public final ResourceLocation dimension;
        public final Queue<Long> queue;

        public PlayerTask(UUID playerId, ResourceLocation dimension, Queue<Long> queue) {
            this.playerId  = playerId;
            this.dimension = dimension;
            this.queue     = queue;
        }

        public int remaining() {
            return queue.size();
        }
    }

    /** Public so other mods (e.g. KubeJS) can inspect / mutate the queue map. */
    public static final Map<UUID, PlayerTask> TASKS = new HashMap<>();

    private final ServerLodManager lodManager;

    public LodDeliveryQueue(ServerLodManager lodManager) {
        this.lodManager = lodManager;
    }

    // -------------------------------------------------------------------------
    // Queue management
    // -------------------------------------------------------------------------

    /**
     * Enqueues a set of section keys for delivery to the given player. Replaces
     * any existing queue for that player (sections already queued are dropped).
     */
    public synchronized void enqueue(ServerPlayer player, ResourceLocation dimension, List<Long> sectionKeys) {
        enqueue(player.getUUID(), player.getName().getString(), dimension, sectionKeys);
    }

    /** For unit tests only. Bypasses {@link ServerPlayer} dependency. */
    public synchronized void enqueue(UUID playerId, String playerName, ResourceLocation dimension, List<Long> sectionKeys) {
        Queue<Long> q = new ArrayDeque<>(sectionKeys);
        TASKS.put(playerId, new PlayerTask(playerId, dimension, q));
        LOGGER.debug("[Voxy] Queued {} sections for player {}", sectionKeys.size(), playerName);
    }

    /**
     * Appends sections to an existing queue (or creates one if none exists).
     */
    public synchronized void enqueueAdditional(ServerPlayer player, ResourceLocation dimension, List<Long> sectionKeys) {
        enqueueAdditional(player.getUUID(), player.getName().getString(), dimension, sectionKeys);
    }

    /** For unit tests only. Bypasses {@link ServerPlayer} dependency. */
    public synchronized void enqueueAdditional(UUID playerId, String playerName, ResourceLocation dimension, List<Long> sectionKeys) {
        PlayerTask task = TASKS.get(playerId);
        if (task == null || !task.dimension.equals(dimension)) {
            enqueue(playerId, playerName, dimension, sectionKeys);
        } else {
            task.queue.addAll(sectionKeys);
        }
    }

    public synchronized void clear(UUID playerId) {
        TASKS.remove(playerId);
    }

    public synchronized int getRemaining(UUID playerId) {
        PlayerTask t = TASKS.get(playerId);
        return t != null ? t.remaining() : 0;
    }

    // -------------------------------------------------------------------------
    // Server tick: deliver up to maxPerPlayer sections per task
    // -------------------------------------------------------------------------

    /**
     * Drains up to {@code maxPerPlayer} section keys from every active task and
     * sends them to the corresponding online player.
     *
     * @param playerLookup function mapping {@link UUID} → {@link ServerPlayer} (or null if offline)
     * @param maxPerPlayer per-task ceiling for this tick
     */
    public synchronized void tickDeliver(java.util.function.Function<UUID, ServerPlayer> playerLookup,
                                         int maxPerPlayer) {
        if (TASKS.isEmpty()) return;

        var iter = TASKS.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            PlayerTask task = entry.getValue();
            ServerPlayer player = playerLookup.apply(task.playerId);
            if (player == null) {
                iter.remove();
                continue;
            }

            int sent = 0;
            while (sent < maxPerPlayer && !task.queue.isEmpty()) {
                Long key = task.queue.poll();
                deliverOne(player, task.dimension, key);
                sent++;
            }

            if (task.queue.isEmpty()) {
                iter.remove();
            }
        }
    }

    private void deliverOne(ServerPlayer player, ResourceLocation dimension, long sectionKey) {
        SqliteSectionStorage storage = lodManager.getStorageManager().get(dimension.toString());
        storage.load(sectionKey).ifPresent(section -> {
            LodSectionDataPayload pkt = new LodSectionDataPayload(
                    dimension, section.key, section.hash, section.toBytes());
            PacketDistributor.sendToPlayer(player, pkt);
        });
    }

    // -------------------------------------------------------------------------

    /**
     * Builds a list of section keys covering a square radius around the given chunk position,
     * for LOD level 0. Useful for the on-join push.
     */
    public static List<Long> radiusKeys(int centerChunkX, int centerChunkZ, int radius) {
        int r = Math.max(1, radius);
        java.util.ArrayList<Long> out = new java.util.ArrayList<>(r * r * 4);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue; // circular
                out.add(SectionKey.encode(0, centerChunkX + dx, centerChunkZ + dz));
            }
        }
        return out;
    }
}
