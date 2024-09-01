package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.client.Voxy;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorldGetter;
import me.cortex.voxy.commonImpl.IVoxyWorldSetter;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(World.class)
public class MixinWorld implements IVoxyWorldGetter, IVoxyWorldSetter {
    @Unique private WorldEngine voxyWorld;

    @Inject(method = "close", at = @At("HEAD"))
    private void closeVoxyWorld(CallbackInfo ci) {
        if (this.voxyWorld != null) {
            try {this.voxyWorld.shutdown();this.voxyWorld = null;} catch (Exception e) {
                Logger.logError("Failed to shutdown voxy  world engine.", e);
            }
        }
    }

    @Override
    public WorldEngine getWorldEngine() {
        return this.voxyWorld;
    }

    @Override
    public void setWorldEngine(WorldEngine engine) {
        if (this.voxyWorld != null) {
            throw new IllegalStateException("WorldEngine not null");
        }
        this.voxyWorld = engine;
    }
}
