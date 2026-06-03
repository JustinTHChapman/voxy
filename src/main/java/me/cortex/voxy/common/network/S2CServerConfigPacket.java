package me.cortex.voxy.common.network;

import me.cortex.voxy.client.config.ServerConfigOverride;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: pushes the server's rate/radius config limits to the connecting client.
 *
 * Sent once on player join, before any LOD data.  The client stores these values in
 * {@link ServerConfigOverride} and uses them to clamp its own auto-generation rate and
 * LOD radius for the duration of the session.
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

    /** Client-side handler — stores the received limits in {@link ServerConfigOverride}. */
    public static void handle(S2CServerConfigPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ServerConfigOverride.INSTANCE.apply(pkt));
    }
}
