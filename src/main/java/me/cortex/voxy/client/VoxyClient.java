package me.cortex.voxy.client;

import me.cortex.voxy.client.core.generation.AutoGenerationService;
import me.cortex.voxy.client.network.ClientPacketHandlers;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.VoxyCommonConfig;
import me.cortex.voxy.common.network.C2SLodSectionPacket;
import me.cortex.voxy.common.network.C2SRequestSectionsPacket;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
import me.cortex.voxy.common.network.S2CManifestPacket;
import me.cortex.voxy.common.network.S2CServerConfigPacket;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;

@Mod(value = "voxy", dist = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;

    public VoxyClient(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, VoxyCommonConfig.CLIENT_SPEC);
        modBus.addListener(this::onClientSetup);
        modBus.addListener(VoxyClient::registerPayloads);
        NeoForge.EVENT_BUS.addListener(VoxyClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(VoxyClient::onRegisterClientCommands);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        AutoGenerationService.INSTANCE.tick();
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        me.cortex.voxy.client.command.VoxyCommands.register(event.getDispatcher());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional(): voxy on the server is NOT required. Without it, a client with required payloads
        // is refused by any NeoForge server that lacks voxy ("mismatched mod channel list"). Optional
        // channels negotiate as absent instead, so a voxy client can join vanilla / non-voxy servers —
        // LODs still build from normally-received chunks; only server-assisted features (LOD streaming,
        // manifest sync, distant auto-generation) are unavailable. Sends are guarded by
        // ClientPacketHandlers.serverHasVoxy().
        final PayloadRegistrar registrar = event.registrar("voxy").optional();
        registrar.playToClient(S2CLodSectionPacket.TYPE, S2CLodSectionPacket.STREAM_CODEC,
                ClientPacketHandlers::handleLodSection);
        registrar.playToClient(S2CServerConfigPacket.TYPE, S2CServerConfigPacket.STREAM_CODEC,
                ClientPacketHandlers::handleServerConfig);
        registrar.playToClient(S2CManifestPacket.TYPE, S2CManifestPacket.STREAM_CODEC,
                ClientPacketHandlers::handleManifest);
        registrar.playToServer(C2SRequestSectionsPacket.TYPE, C2SRequestSectionsPacket.STREAM_CODEC,
                (pkt, ctx) -> { /* clients never receive this packet */ });
        registrar.playToServer(C2SLodSectionPacket.TYPE, C2SLodSectionPacket.STREAM_CODEC,
                (pkt, ctx) -> { /* clients never receive this packet */ });
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(VoxyClient::initVoxyClient);
    }

    public static void initVoxyClient() {
        Logger.DEBUG = VoxyCommonConfig.DEBUG_LOGGING.get();
        // The verbose "VoxyDiag" logger (RDT / ModelFactory / AutoGen / node-manager counters) is INFO
        // spam used while debugging. Mute it unless debug logging is on; set the logger to INFO to restore.
        if (!Logger.DEBUG) {
            org.apache.logging.log4j.core.config.Configurator.setLevel("VoxyDiag", org.apache.logging.log4j.Level.WARN);
        }
        Capabilities.init();

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
            Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }
        }

        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }
        }

        DebugEntries.init();
        // TODO: /voxy commands via RegisterClientCommandsEvent
        // TODO: FREX flawless-frames integration
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;
    }
}
