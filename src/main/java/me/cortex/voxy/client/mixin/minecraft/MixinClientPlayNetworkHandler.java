package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPlayNetworkHandler {
    @Inject(method = "onGameJoin", at = @At(value = "NEW", target = "(Lnet/minecraft/client/network/ClientPlayNetworkHandler;Lnet/minecraft/client/world/ClientWorld$Properties;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/registry/entry/RegistryEntry;IILnet/minecraft/client/render/WorldRenderer;ZJI)Lnet/minecraft/client/world/ClientWorld;", shift = At.Shift.BEFORE))
    private void voxy$init(GameJoinS2CPacket packet, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.enabled) {
            VoxyCommon.createInstance();
        }
    }
}
