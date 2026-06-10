package me.cortex.voxy.common.network;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
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
        int contentHash,
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
        buf.writeInt(pkt.contentHash());
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
        int hash = buf.readInt();
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
        return new S2CLodSectionPacket(dimId, x, y, z, hash, (short) lutSize, vsIds, brls, lts, indices);
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
        // Compute a wire-stable hash over the LUT + indices so manifest comparison works
        // cross-server (both sides encode via vanilla block-state IDs, so hash is stable).
        int hash = 1;
        for (int i = 0; i < lutSize; i++) {
            hash = 31 * hash + vsIds[i];
            hash = 31 * hash + brls[i].hashCode();
            hash = 31 * hash + (lts[i] & 0xFF);
        }
        for (short idx : indices) hash = 31 * hash + Short.toUnsignedInt(idx);

        return new S2CLodSectionPacket(dimensionId, vs.x, vs.y, vs.z, hash, (short) lutSize, vsIds, brls, lts, indices);
    }

    // Client-side handling lives in me.cortex.voxy.client.network.ClientPacketHandlers
    // (a client-only class) so this common record stays loadable on a dedicated server.

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
