package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.Duration;
import java.util.function.Consumer;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientLoginNetworkHandler {
    @Inject(method = "onGameJoin", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;<init>(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ClientPlayNetworkHandler;)V", shift = At.Shift.BY, by = 2))
    private void voxy$init(GameJoinS2CPacket packet, CallbackInfo ci) {
        if (VoxyCommon.isAvailable()) {
            VoxyClientInstance.isInGame = true;
            if (VoxyConfig.CONFIG.enabled) {
                if (VoxyCommon.getInstance() != null) {
                    VoxyCommon.shutdownInstance();
                }
                VoxyCommon.createInstance();
            }
        }
    }
}
