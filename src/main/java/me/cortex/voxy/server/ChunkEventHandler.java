package me.cortex.voxy.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Listens for NeoForge server-side chunk, level, player and tick events and routes them to
 * {@link ServerLodManager}.
 */
public final class ChunkEventHandler {

    private final ServerLodManager lodManager;
    /** Set to false when the server stops so stale handlers silently no-op. */
    private volatile boolean active = true;

    public ChunkEventHandler(ServerLodManager lodManager, IEventBus neoForgeBus) {
        this.lodManager = lodManager;
        neoForgeBus.addListener(this::onChunkLoad);
        neoForgeBus.addListener(this::onLevelUnload);
        neoForgeBus.addListener(this::onPlayerLoggedIn);
        neoForgeBus.addListener(this::onPlayerChangedDimension);
        neoForgeBus.addListener(this::onPlayerLoggedOut);
        neoForgeBus.addListener(this::onServerTickPost);
    }

    /** Called by {@link VoxyServer} when the server stops to prevent stale dispatch. */
    public void shutdown() {
        active = false;
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        if (!active) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        lodManager.onChunkLoaded(serverLevel, chunk);
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (!active) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        String dimKey = serverLevel.dimension().location().toString();
        lodManager.getStorageManager().unload(dimKey);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!active) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        lodManager.onPlayerJoin(player);
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!active) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        lodManager.onPlayerJoin(player);
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!active) return;
        lodManager.getDeliveryQueue().clear(event.getEntity().getUUID());
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        if (!active) return;
        lodManager.tickDelivery();
    }
}
