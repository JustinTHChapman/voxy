package me.cortex.voxy.commonImpl;

import me.cortex.voxy.client.core.WorldImportWrapper;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.world.ClientWorld;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

//TODO: add thread access verification (I.E. only accessible on a single thread)
public class VoxyInstance {
    private final ServiceThreadPool threadPool;
    private final SectionSavingService savingService;
    private final VoxelIngestService ingestService;
    private final Set<WorldEngine> activeWorlds = new HashSet<>();

    public VoxyInstance(int threadCount) {
        this.threadPool = new ServiceThreadPool(threadCount);
        this.savingService = new SectionSavingService(this.threadPool);
        this.ingestService = new VoxelIngestService(this.threadPool);
    }

    public void addDebug(List<String> debug) {
        debug.add("Voxy Core: " + VoxyCommon.MOD_VERSION);
        debug.add("MemoryBuffer, Count/Size (mb): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize()/1_000_000));
        debug.add("I/S: " + this.ingestService.getTaskCount() + "/" + this.savingService.getTaskCount());
    }

    public void shutdown() {
        Logger.info("Shutdown voxy instance");
        try {this.ingestService.shutdown();} catch (Exception e) {Logger.error(e);}
        try {this.savingService.shutdown();} catch (Exception e) {Logger.error(e);}
        try {this.threadPool.shutdown();} catch (Exception e) {Logger.error(e);}
    }

    public ServiceThreadPool getThreadPool() {
        return this.threadPool;
    }

    public VoxelIngestService getIngestService() {
        return this.ingestService;
    }

    public SectionSavingService getSavingService() {
        return this.savingService;
    }

    public void flush() {
        try {
            while (this.ingestService.getTaskCount() != 0) {
                Thread.sleep(10);
            }
            while (this.savingService.getTaskCount() != 0) {
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private WorldEngine createWorld(SectionStorage storage) {
        var world = new WorldEngine(storage, 1024);
        world.setSaveCallback(this.savingService::enqueueSave);
        this.activeWorlds.add(world);
        return world;
    }

    private static final ContextSelectionSystem SELECTOR = new ContextSelectionSystem();

    public WorldEngine getOrMakeWorld(ClientWorld world) {
        var vworld = ((IVoxyWorldGetter)world).getWorldEngine();
        if (vworld == null) {
            vworld = new WorldEngine(SELECTOR.getBestSelectionOrCreate(world).createSectionStorageBackend(), 1024);
            vworld.setSaveCallback(this.savingService::enqueueSave);
            ((IVoxyWorldSetter)world).setWorldEngine(vworld);
            this.importWrapper = new WorldImportWrapper(this.threadPool, vworld);
        }
        return vworld;
    }


    public WorldImportWrapper importWrapper;

    public void stopWorld(WorldEngine world) {
        if (this.importWrapper != null) {
            this.importWrapper.stopImporter();
        }
        this.flush();
        world.shutdown();
    }
}