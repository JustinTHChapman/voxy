package me.cortex.voxy.server;

import me.cortex.voxy.common.config.VoxyCommonConfig;
import me.cortex.voxy.common.network.C2SLodSectionPacket;
import me.cortex.voxy.common.network.C2SRequestSectionsPacket;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
import me.cortex.voxy.common.network.S2CManifestPacket;
import me.cortex.voxy.common.network.S2CServerConfigPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server-side LOD companion: voxelizes chunks as they are tracked by players and
 * streams the resulting LOD sections back to each player via
 * {@link S2CLodSectionPacket}.
 *
 * <p>This allows players who join a dedicated server to receive pre-built LOD sections
 * for the world areas that have already been explored by other players.</p>
 */
@Mod(value = "voxy", dist = Dist.DEDICATED_SERVER)
public class VoxyServer {

    private static final ServerLodTracker TRACKER = new ServerLodTracker();

    public VoxyServer(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, VoxyCommonConfig.SERVER_SPEC);
        // Register the network payload on the server side so it can be sent
        modBus.addListener(VoxyServer::registerPayloads);

        // Register NeoForge (game) bus events
        NeoForge.EVENT_BUS.addListener(VoxyServer::onChunkSent);
        NeoForge.EVENT_BUS.addListener(VoxyServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(VoxyServer::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(VoxyServer::onPlayerLeave);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("voxy");
        registrar.playToClient(S2CLodSectionPacket.TYPE, S2CLodSectionPacket.STREAM_CODEC,
                (pkt, ctx) -> { /* server never receives this packet */ });
        registrar.playToClient(S2CServerConfigPacket.TYPE, S2CServerConfigPacket.STREAM_CODEC,
                (pkt, ctx) -> { /* server never receives this packet */ });
        registrar.playToClient(S2CManifestPacket.TYPE, S2CManifestPacket.STREAM_CODEC,
                (pkt, ctx) -> { /* server never receives this packet */ });
        registrar.playToServer(C2SRequestSectionsPacket.TYPE, C2SRequestSectionsPacket.STREAM_CODEC,
                (pkt, ctx) -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        TRACKER.onClientRequest(sp, pkt.sectionPositions());
                    }
                });
        registrar.playToServer(C2SLodSectionPacket.TYPE, C2SLodSectionPacket.STREAM_CODEC,
                (pkt, ctx) -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        TRACKER.onClientUpload(sp, pkt);
                    }
                });
    }

    private static void onChunkSent(ChunkWatchEvent.Sent event) {
        TRACKER.onChunkWatch(event.getLevel(), event.getChunk(), event.getPlayer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        TRACKER.onServerTick();
    }

    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new S2CServerConfigPacket(
                    VoxyCommonConfig.LOD_CHUNKS_PER_TICK.get(),
                    VoxyCommonConfig.LOD_GENERATION_RATE_CAP.get(),
                    VoxyCommonConfig.AUTO_GENERATION_ENABLED_SERVER.get(),
                    VoxyCommonConfig.LOD_RADIUS_MAX.get()
            ));
            TRACKER.onPlayerJoin(sp);
        }
    }

    private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            TRACKER.onPlayerLeave(sp);
        }
    }
}
