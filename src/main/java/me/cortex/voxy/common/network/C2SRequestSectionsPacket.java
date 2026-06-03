package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: requests specific LOD sections by their encoded position keys.
 *
 * Sent after the client has processed an {@link S2CManifestPacket} sequence and
 * identified which sections it needs (missing or hash mismatch).
 *
 * Wire format:
 * <pre>
 *   VarInt  count
 *   for i in [0, count):
 *     Long  sectionPos  – WorldEngine-encoded position key
 * </pre>
 */
public record C2SRequestSectionsPacket(long[] sectionPositions) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SRequestSectionsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "request_sections"));

    public static final StreamCodec<FriendlyByteBuf, C2SRequestSectionsPacket> STREAM_CODEC =
            StreamCodec.of(C2SRequestSectionsPacket::encode, C2SRequestSectionsPacket::decode);

    private static void encode(FriendlyByteBuf buf, C2SRequestSectionsPacket pkt) {
        buf.writeVarInt(pkt.sectionPositions().length);
        for (long pos : pkt.sectionPositions()) buf.writeLong(pos);
    }

    private static C2SRequestSectionsPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) positions[i] = buf.readLong();
        return new C2SRequestSectionsPacket(positions);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
