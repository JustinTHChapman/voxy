package me.cortex.voxy.client.network;

import me.cortex.voxy.client.config.ServerConfigOverride;
import me.cortex.voxy.client.sync.ManifestSyncHandler;
import me.cortex.voxy.common.network.S2CLodSectionPacket;
import me.cortex.voxy.common.network.S2CManifestPacket;
import me.cortex.voxy.common.network.S2CServerConfigPacket;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side receivers for Voxy's server→client packets.
 *
 * <p>These live in the client source tree (never loaded on a dedicated server) because
 * they touch client-only classes like {@link Minecraft}. Keeping them out of the common
 * packet records is what lets those records be linked on the dedicated server — which has
 * to load them to register/send the packets — without the RuntimeDistCleaner rejecting a
 * client class. Registered from {@code VoxyClient.registerPayloads}.</p>
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    /** Stores the server's rate/radius limits for the session. */
    public static void handleServerConfig(S2CServerConfigPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ServerConfigOverride.INSTANCE.apply(pkt));
    }

    /** Accumulates manifest entries for delta sync. */
    public static void handleManifest(S2CManifestPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ManifestSyncHandler.INSTANCE.onManifestPacket(pkt));
    }

    /** Remaps a received LOD section to client-local IDs and inserts it into the world engine. */
    public static void handleLodSection(S2CLodSectionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var instance = VoxyCommon.getInstance();
            if (instance == null) return;

            var level = Minecraft.getInstance().level;
            if (level == null) return;

            // Drop sections for a different dimension than the one we're in — prevents old-dimension
            // LOD leaking into the new engine after a portal switch. The server re-sends via the
            // join manifest, so a dropped section is not lost.
            if (!pkt.dimensionId().equals(level.dimension().location().toString())) return;

            // Use the currently-active world engine for the client's current dimension
            var worldId = WorldIdentifier.of(level);
            if (worldId == null) return;
            var worldEngine = instance.getOrCreate(worldId);
            if (worldEngine == null) return;

            var clientMapper = worldEngine.getMapper();
            var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

            // Build client-local LUT from vanilla IDs → client Mapper IDs
            int sz = pkt.lutSize() & 0xFFFF;
            long[] clientLUT = new long[sz];
            for (int i = 0; i < sz; i++) {
                BlockState blockState = Block.BLOCK_STATE_REGISTRY.byId(pkt.vanillaBlockStateIds()[i]);
                if (blockState == null) blockState = Blocks.AIR.defaultBlockState();

                int clientBlockId = blockState.isAir() ? 0 : clientMapper.getIdForBlockState(blockState);

                Holder<Biome> biomeHolder = null;
                try {
                    var biomeKey = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(pkt.biomeRls()[i]));
                    biomeHolder = biomeRegistry.getHolder(biomeKey).orElse(null);
                } catch (Exception ignored) {}
                int clientBiomeId = (biomeHolder != null) ? clientMapper.getIdForBiome(biomeHolder) : 0;

                clientLUT[i] = Mapper.composeMappingId(pkt.lights()[i], clientBlockId, clientBiomeId);
            }

            // Reconstruct VoxelizedSection with client-local IDs
            VoxelizedSection vs = VoxelizedSection.createEmpty().setPosition(pkt.sectionX(), pkt.sectionY(), pkt.sectionZ());
            for (int i = 0; i < S2CLodSectionPacket.DATA_SIZE; i++) {
                vs.section[i] = clientLUT[pkt.indices()[i] & 0xFFFF];
            }
            int nonAir = 0;
            for (int i = 0; i < 16 * 16 * 16; i++) {
                if (!Mapper.isAir(vs.section[i])) nonAir++;
            }
            vs.lvl0NonAirCount = nonAir;

            WorldUpdater.insertUpdate(worldEngine, vs);
        });
    }

    // Memoized per connection: channel negotiation is fixed for a connection's lifetime, so the result
    // can't change until a NEW connection object exists. Keying the cache on the connection instance
    // makes invalidation automatic (reconnect → different instance → recompute) — no disconnect hooks.
    private static Object cachedConnForVoxyCheck = null;
    private static boolean cachedServerHasVoxy = false;

    /**
     * Whether the server we're connected to has voxy (its network channels were negotiated).
     * True in singleplayer (the integrated server always has them). False on vanilla servers and
     * NeoForge servers without voxy — every client→server send MUST be gated on this, since sending
     * an un-negotiated payload throws. Voxy's channels are registered optional, so joining such
     * servers works; server-assisted features (LOD streaming, manifest sync, uploads) just turn off.
     * Resolved once per connection (memoized); subsequent calls are a reference compare.
     */
    public static boolean serverHasVoxy() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return false;
        if (conn != cachedConnForVoxyCheck) {
            cachedServerHasVoxy = conn.hasChannel(me.cortex.voxy.common.network.C2SLodSectionPacket.TYPE);
            cachedConnForVoxyCheck = conn;
        }
        return cachedServerHasVoxy;
    }
}
