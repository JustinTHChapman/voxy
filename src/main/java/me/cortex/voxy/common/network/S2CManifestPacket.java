package me.cortex.voxy.common.network;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: delivers a batch of (sectionPos, contentHash) pairs so the client
 * can determine which LOD sections it already has up-to-date copies of, and request
 * only the ones that differ.
 *
 * Large manifests are split into multiple packets; the last one in the sequence has
 * {@code isFinal = true}.  The client accumulates entries from all packets in the
 * sequence before acting on them.
 *
 * Wire format:
 * <pre>
 *   Boolean  isFinal     – true on the last packet of the manifest sequence
 *   VarInt   count       – number of entries in this packet
 *   for i in [0, count):
 *     Long   sectionPos  – WorldEngine-encoded position key
 *     Int    hash        – contentHash from SaveLoadSystem3
 * </pre>
 */
public record S2CManifestPacket(
        String  dimensionId,
        boolean isFinal,
        long[]  sectionPositions,
        int[]   contentHashes
) implements CustomPacketPayload {

    /** Maximum number of entries per packet to stay well under the 2 MB packet limit. */
    public static final int MAX_ENTRIES_PER_PACKET = 8_000;

    public static final CustomPacketPayload.Type<S2CManifestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "manifest"));

    public static final StreamCodec<FriendlyByteBuf, S2CManifestPacket> STREAM_CODEC =
            StreamCodec.of(S2CManifestPacket::encode, S2CManifestPacket::decode);

    private static void encode(FriendlyByteBuf buf, S2CManifestPacket pkt) {
        buf.writeUtf(pkt.dimensionId(), 96);
        buf.writeBoolean(pkt.isFinal());
        buf.writeVarInt(pkt.sectionPositions().length);
        for (int i = 0; i < pkt.sectionPositions().length; i++) {
            buf.writeLong(pkt.sectionPositions()[i]);
            buf.writeInt(pkt.contentHashes()[i]);
        }
    }

    private static S2CManifestPacket decode(FriendlyByteBuf buf) {
        String dimId = buf.readUtf(96);
        boolean isFinal = buf.readBoolean();
        int count = buf.readVarInt();
        long[] positions = new long[count];
        int[]  hashes    = new int[count];
        for (int i = 0; i < count; i++) {
            positions[i] = buf.readLong();
            hashes[i]    = buf.readInt();
        }
        return new S2CManifestPacket(dimId, isFinal, positions, hashes);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
