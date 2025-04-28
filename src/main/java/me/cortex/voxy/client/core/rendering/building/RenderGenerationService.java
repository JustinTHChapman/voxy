package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.thread.ServiceSlice;
import me.cortex.voxy.common.thread.ServiceThreadPool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

//TODO: Add a render cache
public class RenderGenerationService {
    private static final class BuildTask {
        WorldSection section;
        final long position;
        boolean hasDoneModelRequestInner;
        boolean hasDoneModelRequestOuter;
        private BuildTask(long position) {
            this.position = position;
        }
    }

    private final Long2ObjectLinkedOpenHashMap<BuildTask> taskQueue = new Long2ObjectLinkedOpenHashMap<>();

    private final WorldEngine world;
    private final ModelBakerySubsystem modelBakery;
    private final Consumer<BuiltSection> resultConsumer;
    private final boolean emitMeshlets;

    private final ServiceSlice threads;


    public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceThreadPool serviceThreadPool, Consumer<BuiltSection> consumer, boolean emitMeshlets) {
        this(world, modelBakery, serviceThreadPool, consumer, emitMeshlets, ()->true);
    }

    public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceThreadPool serviceThreadPool, Consumer<BuiltSection> consumer, boolean emitMeshlets, BooleanSupplier taskLimiter) {
        this.emitMeshlets = emitMeshlets;
        this.world = world;
        this.modelBakery = modelBakery;
        this.resultConsumer = consumer;

        this.threads = serviceThreadPool.createService("Section mesh generation service", 100, ()->{
            //Thread local instance of the factory
            var factory = new RenderDataFactory45(this.world, this.modelBakery.factory, this.emitMeshlets);
            IntOpenHashSet seenMissed = new IntOpenHashSet(128);
            return new Pair<>(() -> {
                this.processJob(factory, seenMissed);
            }, factory::free);
        }, taskLimiter);
    }

    //NOTE: the biomes are always fully populated/kept up to date

    //Asks the Model system to bake all blocks that currently dont have a model
    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, int bitMsk, long[] auxData) {
        final var factory = this.modelBakery.factory;
        for (int i = 0; i < 6; i++) {
            if ((bitMsk&(1<<i))==0) continue;
            for (int j = 0; j < 32*32; j++) {
                int block = Mapper.getBlockId(auxData[j+(i*32*32)]);
                if (block != 0 && !factory.hasModelForBlockId(block)) {
                    if (seenMissedIds.add(block)) {
                        this.modelBakery.requestBlockBake(block);
                    }
                }
            }
        }
    }

    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, WorldSection section) {
        //Know this is... very much not safe, however it reduces allocation rates and other garbage, am sure its "fine"
        final var factory = this.modelBakery.factory;
        for (long state : section._unsafeGetRawDataArray()) {
            int block = Mapper.getBlockId(state);
            if (block != 0 && !factory.hasModelForBlockId(block)) {
                if (seenMissedIds.add(block)) {
                    this.modelBakery.requestBlockBake(block);
                }
            }
        }
    }

    private WorldSection acquireSection(long pos) {
        return this.world.acquireIfExists(pos);
    }

    private static boolean putTaskFirst(long pos) {
        //Level 3 or 4
        return WorldEngine.getLevel(pos) > 2;
    }

    public static final AtomicInteger FC = new AtomicInteger(0);
    //TODO: add a generated render data cache
    private void processJob(RenderDataFactory45 factory, IntOpenHashSet seenMissedIds) {
        BuildTask task;
        synchronized (this.taskQueue) {
            task = this.taskQueue.removeFirst();
            //task = (Math.random() < 0.1)?this.taskQueue.removeLast():this.taskQueue.removeFirst();
        }
        //long time = BuiltSection.getTime();
        boolean shouldFreeSection = true;

        WorldSection section;
        if (task.section == null) {
            section = this.acquireSection(task.position);
        } else {
            section = task.section;
        }

        if (section == null) {
            this.resultConsumer.accept(BuiltSection.empty(task.position));
            return;
        }
        section.assertNotFree();
        BuiltSection mesh = null;
        try {
            mesh = factory.generateMesh(section);
        } catch (IdNotYetComputedException e) {
            if (e.isIdBlockId) {
                //TODO: maybe move this to _after_ task as been readded to queue??
                if (!this.modelBakery.factory.hasModelForBlockId(e.id)) {
                    if (seenMissedIds.add(e.id)) {
                        this.modelBakery.requestBlockBake(e.id);
                    }
                }
            }

            if (task.hasDoneModelRequestInner && task.hasDoneModelRequestOuter) {
                FC.addAndGet(1);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }

            if (!task.hasDoneModelRequestInner) {
                //The reason for the extra id parameter is that we explicitly add/check against the exception id due to e.g. requesting accross a chunk boarder wont be captured in the request
                if (e.auxData == null)//the null check this is because for it to be, the inner must already be computed
                    this.computeAndRequestRequiredModels(seenMissedIds, section);
                task.hasDoneModelRequestInner = true;
            }
            if ((!task.hasDoneModelRequestOuter) && e.auxData != null) {
                this.computeAndRequestRequiredModels(seenMissedIds, e.auxBitMsk, e.auxData);
                task.hasDoneModelRequestOuter = true;
            }


            {//Keep the lock on the section, and attach it to the task, this prevents needing to re-aquire it later
                task.section = section;
            }
            {
                //We need to reinsert the build task into the queue
                BuildTask queuedTask;
                synchronized (this.taskQueue) {
                    queuedTask = this.taskQueue.putIfAbsent(section.key, task);
                    if (queuedTask == null) {
                        queuedTask = task;
                    }

                    if (queuedTask.hasDoneModelRequestInner && queuedTask.hasDoneModelRequestOuter && putTaskFirst(section.key)) {//Force higher priority
                        this.taskQueue.getAndMoveToFirst(section.key);
                    }
                }

                if (queuedTask == task) {//use the == not .equal to see if we need to release a permit
                    if (this.threads.isAlive()) {//Only execute if were not dead
                        this.threads.execute();//Since we put in queue, release permit
                    }

                    //If we did put it in the queue, dont release the section
                    shouldFreeSection = false;
                } else {
                    //Mark (or remark) the section as having models requested
                    if (task.hasDoneModelRequestInner)
                        queuedTask.hasDoneModelRequestInner = true;

                    if (task.hasDoneModelRequestOuter)
                        queuedTask.hasDoneModelRequestOuter = true;

                    //Things went bad, set section to null and ensure section is freed
                    task.section = null;
                    shouldFreeSection = true;
                }
            }
        }

        if (shouldFreeSection) {
            section.release();
        }

        if (mesh != null) {//If the mesh is null it means it didnt finish, so dont submit
            this.resultConsumer.accept(mesh);
        }
    }


    public void enqueueTask(long pos) {
        synchronized (this.taskQueue) {
            this.taskQueue.computeIfAbsent(pos, key->{
                this.threads.execute();
                return new BuildTask(key);
            });
            //Prioritize lower detail builds
            if (putTaskFirst(pos)) {
                this.taskQueue.getAndMoveToFirst(pos);
            }
        }
    }

    public void removeTask(long pos) {
        BuildTask task;
        synchronized (this.taskQueue) {
            task = this.taskQueue.remove(pos);
        }
        if (task != null) {
            if (!this.threads.steal()) {
                throw new IllegalStateException("Failed to steal a task!!!");
            }
        }
    }

    /*
    public void enqueueTask(int lvl, int x, int y, int z) {
        this.enqueueTask(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }
    */

    public void shutdown() {
        //Steal and free as much work as possible
        while (this.threads.hasJobs()) {
            int i = this.threads.drain();
            if (i == 0) break;

            synchronized (this.taskQueue) {
                for (int j = 0; j < i; j++) {
                    var task = this.taskQueue.removeFirst();
                    if (task.section != null) {
                        task.section.release();
                    }
                }
            }
        }

        //Shutdown the threads
        this.threads.shutdown();

        //Cleanup any remaining data
        while (!this.taskQueue.isEmpty()) {
            var task = this.taskQueue.removeFirst();
            if (task.section != null) {
                task.section.release();
            }
        }
    }

    public void addDebugData(List<String> debug) {
        debug.add("RSSQ: " + this.taskQueue.size());//render section service queue
    }

    public int getTaskCount() {
        return this.taskQueue.size();
    }
}
