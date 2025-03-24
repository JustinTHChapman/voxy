package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.WorldImportWrapper;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorldGetter;
import me.cortex.voxy.commonImpl.IVoxyWorldSetter;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import net.minecraft.client.world.ClientWorld;

import java.io.File;

public class VoxyClientInstance extends VoxyInstance {
    private static final ContextSelectionSystem SELECTOR = new ContextSelectionSystem();

    public VoxyClientInstance() {
        super(VoxyConfig.CONFIG.serviceThreads);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    public WorldEngine getOrMakeRenderWorld(ClientWorld world) {
        var vworld = ((IVoxyWorldGetter)world).getWorldEngine();
        if (vworld == null) {
            vworld = this.createWorld(SELECTOR.getBestSelectionOrCreate(world).createSectionStorageBackend());
            ((IVoxyWorldSetter)world).setWorldEngine(vworld);
        } else {
            if (!this.activeWorlds.contains(vworld)) {
                throw new IllegalStateException("World referenced does not exist in instance");
            }
        }
        return vworld;
    }
}
