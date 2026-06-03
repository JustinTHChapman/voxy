package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: uploads a client-generated LOD section so the server can cache it
 * and broadcast it to other players, saving the server from re-generating the same data.
 *
 * The server deduplicates by {@code contentHash}: if the hash matches what it already
 * has for that position, the packet is silently discarded.
 *
 * Wire format mirrors {@link S2CLodSectionPacket} with an additional leading sectionPos
 * key so the server can route to the correct DimState cache without decoding all fields:
 * <pre>
 *   Long    sectionPosKey  – WorldEngine-encoded position (for fast server-side lookup)
 *   Int     contentHash    – wire-stable hash computed same way as S2CLodSectionPacket
 *   String  dimensionId    – e.g. "minecraft:overworld"
 *   VarInt  sectionX
 *   VarInt  sectionY
 *   VarInt  sectionZ
 *   Short   lutSize
 *   for i in [0, lutSize):
 *     Int     vanillaBlockStateId
 *     Utf8    biomeRl  (max 96 chars)
 *     Byte    light
 *   Short[DATA_SIZE]  indices
 * </pre>
 */
public record C2SLodSectionPacket(
        long sectionPosKey,
        int contentHash,
        String dimensionId,
        int sectionX,
        int sectionY,
        int sectionZ,
        short lutSize,
        int[] vanillaBlockStateIds,
        String[] biomeRls,
        byte[] lights,
        short[] indices
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SLodSectionPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "c2s_lod_section"));

    public static final StreamCodec<FriendlyByteBuf, C2SLodSectionPacket> STREAM_CODEC =
            StreamCodec.of(C2SLodSectionPacket::encode, C2SLodSectionPacket::decode);

    private static void encode(FriendlyByteBuf buf, C2SLodSectionPacket pkt) {
        buf.writeLong(pkt.sectionPosKey());
        buf.writeInt(pkt.contentHash());
        buf.writeUtf(pkt.dimensionId(), 96);
        buf.writeVarInt(pkt.sectionX());
        buf.writeVarInt(pkt.sectionY());
        buf.writeVarInt(pkt.sectionZ());
        buf.writeShort(pkt.lutSize());
        int lut = pkt.lutSize() & 0xFFFF;
        for (int i = 0; i < lut; i++) {
            buf.writeInt(pkt.vanillaBlockStateIds()[i]);
            buf.writeUtf(pkt.biomeRls()[i], 96);
            buf.writeByte(pkt.lights()[i]);
        }
        for (int i = 0; i < S2CLodSectionPacket.DATA_SIZE; i++) buf.writeShort(pkt.indices()[i]);
    }

    private static C2SLodSectionPacket decode(FriendlyByteBuf buf) {
        long posKey     = buf.readLong();
        int  hash       = buf.readInt();
        String dimId    = buf.readUtf(96);
        int x           = buf.readVarInt();
        int y           = buf.readVarInt();
        int z           = buf.readVarInt();
        int lutSize     = buf.readShort() & 0xFFFF;
        int[]    vsIds  = new int[lutSize];
        String[] brls   = new String[lutSize];
        byte[]   lts    = new byte[lutSize];
        for (int i = 0; i < lutSize; i++) {
            vsIds[i] = buf.readInt();
            brls[i]  = buf.readUtf(96);
            lts[i]   = buf.readByte();
        }
        short[] indices = new short[S2CLodSectionPacket.DATA_SIZE];
        for (int i = 0; i < S2CLodSectionPacket.DATA_SIZE; i++) indices[i] = buf.readShort();
        return new C2SLodSectionPacket(posKey, hash, dimId, x, y, z, (short) lutSize, vsIds, brls, lts, indices);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build a C2S upload packet from an existing S2C packet (same wire content, just different direction). */
    public static C2SLodSectionPacket fromS2C(S2CLodSectionPacket s2c) {
        long posKey = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(
                0, s2c.sectionX(), s2c.sectionY(), s2c.sectionZ());
        return new C2SLodSectionPacket(posKey, s2c.contentHash(), s2c.dimensionId(),
                s2c.sectionX(), s2c.sectionY(), s2c.sectionZ(),
                s2c.lutSize(), s2c.vanillaBlockStateIds(), s2c.biomeRls(), s2c.lights(), s2c.indices());
    }
}
