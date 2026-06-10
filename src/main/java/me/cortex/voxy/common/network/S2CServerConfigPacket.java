package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: pushes the server's rate/radius config limits to the connecting client.
 *
 * Sent once on player join, before any LOD data.  The client stores these values in
 * {@code ServerConfigOverride} and uses them to clamp its own auto-generation rate and
 * LOD radius for the duration of the session. Client-side handling lives in
 * {@code me.cortex.voxy.client.network.ClientPacketHandlers}.
 */
public record S2CServerConfigPacket(
        int lodsPerTick,
        int generationRateCap,
        boolean autoGenerationEnabled,
        int lodRadiusMax
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CServerConfigPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "server_config"));

    public static final StreamCodec<FriendlyByteBuf, S2CServerConfigPacket> STREAM_CODEC =
            StreamCodec.of(S2CServerConfigPacket::encode, S2CServerConfigPacket::decode);

    private static void encode(FriendlyByteBuf buf, S2CServerConfigPacket pkt) {
        buf.writeVarInt(pkt.lodsPerTick());
        buf.writeVarInt(pkt.generationRateCap());
        buf.writeBoolean(pkt.autoGenerationEnabled());
        buf.writeVarInt(pkt.lodRadiusMax());
    }

    private static S2CServerConfigPacket decode(FriendlyByteBuf buf) {
        int lodsPerTick          = buf.readVarInt();
        int generationRateCap    = buf.readVarInt();
        boolean autoGenEnabled   = buf.readBoolean();
        int lodRadiusMax         = buf.readVarInt();
        return new S2CServerConfigPacket(lodsPerTick, generationRateCap, autoGenEnabled, lodRadiusMax);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
