package me.cortex.voxy.client;

import me.cortex.voxy.client.core.WorldImportWrapper;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorldGetter;
import me.cortex.voxy.commonImpl.IVoxyWorldSetter;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.client.world.ClientWorld;

public class VoxyClientInstance extends VoxyInstance {
    private static final ContextSelectionSystem SELECTOR = new ContextSelectionSystem();
    public WorldImportWrapper importWrapper;

    public VoxyClientInstance() {
        super(12);
    }

    @Override
    public void stopWorld(WorldEngine world) {
        if (this.importWrapper != null) {
            this.importWrapper.stopImporter();
            this.importWrapper = null;
        }
        super.stopWorld(world);
    }

    public WorldEngine getOrMakeRenderWorld(ClientWorld world) {
        var vworld = ((IVoxyWorldGetter)world).getWorldEngine();
        if (vworld == null) {
            vworld = this.createWorld(SELECTOR.getBestSelectionOrCreate(world).createSectionStorageBackend());
            ((IVoxyWorldSetter)world).setWorldEngine(vworld);
            this.importWrapper = new WorldImportWrapper(this.threadPool, vworld);
        } else {
            if (!this.activeWorlds.contains(vworld)) {
                throw new IllegalStateException("World referenced does not exist in instance");
            }
        }
        return vworld;
    }
}
