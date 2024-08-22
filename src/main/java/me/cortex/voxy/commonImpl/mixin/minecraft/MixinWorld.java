package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.client.Voxy;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public class MixinWorld {
    @Unique private WorldEngine voxyWorldEngine;


    @Inject(method = "close", at = @At("HEAD"))
    private void closeVoxyWorld(CallbackInfo ci) {
        if (this.voxyWorldEngine != null) {
            try {this.voxyWorldEngine.shutdown();} catch (Exception e) {
            }
        }
    }
}
