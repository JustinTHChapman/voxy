package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorldGetter;
import me.cortex.voxy.commonImpl.IVoxyWorldSetter;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.world.World;
import net.minecraft.world.block.NeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public class MixinWorld implements IVoxyWorldGetter, IVoxyWorldSetter {
    @Shadow @Final protected NeighborUpdater neighborUpdater;
    @Unique private WorldEngine voxyWorld;

    @Override
    public WorldEngine getWorldEngine() {
        return this.voxyWorld;
    }

    @Override
    public void setWorldEngine(WorldEngine engine) {
        if (engine != null && this.voxyWorld != null) {
            throw new IllegalStateException("WorldEngine not null");
        }
        this.voxyWorld = engine;
    }
}
