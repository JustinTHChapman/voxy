package me.cortex.voxy.common.network;

import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server→Client packet carrying a fully voxelized (and mipmapped) LOD section.
 *
 * <p>The section data is encoded using vanilla block-state registry IDs + biome resource
 * locations so that client and server Mapper instances are irrelevant: the client remaps
 * each cell to its own Mapper IDs before storing.</p>
 *
 * <p>Wire format (inside FriendlyByteBuf):
 * <pre>
 *   String    dimensionId    – e.g. "minecraft:overworld"
 *   VarInt    sectionX
 *   VarInt    sectionY
 *   VarInt    sectionZ
 *   Short     lutSize        – number of unique entries in the LUT
 *   for i in [0, lutSize):
 *     Int     vanillaBlockStateId
 *     Utf8    biomeRl        (max 96 chars)
 *     Byte    light
 *   Short[VoxelizedSection.DATA_SIZE]  indices into the LUT
 * </pre>
 * </p>
 */
public record S2CLodSectionPacket(
        String dimensionId,
        int sectionX,
        int sectionY,
        int sectionZ,
        short lutSize,
        int[] vanillaBlockStateIds,
        String[] biomeRls,
        byte[] lights,
        short[] indices) implements CustomPacketPayload {

    /** Total number of longs in a VoxelizedSection (L0-L4 inclusive). */
    public static final int DATA_SIZE = 16 * 16 * 16 + 8 * 8 * 8 + 4 * 4 * 4 + 2 * 2 * 2 + 1;

    public static final CustomPacketPayload.Type<S2CLodSectionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "lod_section"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CLodSectionPacket> STREAM_CODEC =
            StreamCodec.of(S2CLodSectionPacket::encode, S2CLodSectionPacket::decode);

    // ---- encoding -------------------------------------------------------

    private static void encode(RegistryFriendlyByteBuf buf, S2CLodSectionPacket pkt) {
        buf.writeUtf(pkt.dimensionId(), 96);
        buf.writeVarInt(pkt.sectionX());
        buf.writeVarInt(pkt.sectionY());
        buf.writeVarInt(pkt.sectionZ());
        buf.writeShort(pkt.lutSize());
        for (int i = 0; i < (pkt.lutSize() & 0xFFFF); i++) {
            buf.writeInt(pkt.vanillaBlockStateIds()[i]);
            buf.writeUtf(pkt.biomeRls()[i], 96);
            buf.writeByte(pkt.lights()[i]);
        }
        short[] idx = pkt.indices();
        for (int i = 0; i < DATA_SIZE; i++) {
            buf.writeShort(idx[i]);
        }
    }

    private static S2CLodSectionPacket decode(RegistryFriendlyByteBuf buf) {
        String dimId = buf.readUtf(96);
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int lutSize = buf.readShort() & 0xFFFF;
        int[] vsIds = new int[lutSize];
        String[] brls = new String[lutSize];
        byte[] lts = new byte[lutSize];
        for (int i = 0; i < lutSize; i++) {
            vsIds[i] = buf.readInt();
            brls[i] = buf.readUtf(96);
            lts[i] = buf.readByte();
        }
        short[] indices = new short[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            indices[i] = buf.readShort();
        }
        return new S2CLodSectionPacket(dimId, x, y, z, (short) lutSize, vsIds, brls, lts, indices);
    }

    // ---- factory (server-side) ------------------------------------------

    /**
     * Build a packet from a server-side VoxelizedSection.
     *
     * @param dimensionId resource location of the dimension (e.g. "minecraft:overworld")
     * @param vs          already mipmapped VoxelizedSection (section data uses serverMapper IDs)
     * @param serverMapper the server's Mapper so we can translate IDs to vanilla IDs
     */
    public static S2CLodSectionPacket fromSection(String dimensionId, VoxelizedSection vs, Mapper serverMapper) {
        long[] sectionData = vs.section;
        // Build a LUT: server-local long → short LUT index
        Map<Long, Short> lutMap = new LinkedHashMap<>();
        short[] indices = new short[DATA_SIZE];
        for (int i = 0; i < DATA_SIZE; i++) {
            long val = sectionData[i];
            Short idx = lutMap.get(val);
            if (idx == null) {
                idx = (short) lutMap.size();
                lutMap.put(val, idx);
            }
            indices[i] = idx;
        }

        int lutSize = lutMap.size();
        int[] vsIds = new int[lutSize];
        String[] brls = new String[lutSize];
        byte[] lts = new byte[lutSize];

        int ei = 0;
        for (Map.Entry<Long, Short> entry : lutMap.entrySet()) {
            long serverLong = entry.getKey();
            int serverBlockId = Mapper.getBlockId(serverLong);
            int serverBiomeId = Mapper.getBiomeId(serverLong);
            int light = Mapper.getLightId(serverLong);

            BlockState blockState = (serverBlockId == 0)
                    ? Blocks.AIR.defaultBlockState()
                    : serverMapper.getBlockStateFromBlockId(serverBlockId);
            vsIds[ei] = Block.BLOCK_STATE_REGISTRY.getId(blockState);

            // Biome resource location
            String biomeRl = "minecraft:plains";
            if (serverBiomeId > 0) {
                Mapper.BiomeEntry[] biomeEntries = serverMapper.getBiomeEntries();
                if (serverBiomeId < biomeEntries.length) {
                    String b = biomeEntries[serverBiomeId].biome;
                    if (b != null && !b.isEmpty()) biomeRl = b;
                }
            }
            brls[ei] = biomeRl;
            lts[ei] = (byte) light;
            ei++;
        }
        return new S2CLodSectionPacket(dimensionId, vs.x, vs.y, vs.z, (short) lutSize, vsIds, brls, lts, indices);
    }

    // ---- handler (client-side) ------------------------------------------

    public static void handle(S2CLodSectionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var instance = VoxyCommon.getInstance();
            if (instance == null) return;

            var level = Minecraft.getInstance().level;
            if (level == null) return;

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
            for (int i = 0; i < DATA_SIZE; i++) {
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

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
