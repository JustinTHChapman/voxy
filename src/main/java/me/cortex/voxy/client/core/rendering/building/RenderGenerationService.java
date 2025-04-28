package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.thread.ServiceSlice;
import me.cortex.voxy.common.thread.ServiceThreadPool;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

//TODO: Add a render cache


//TODO: to add remove functionallity add a "defunked" variable to the build task and set it to true on remove
// and process accordingly
public class RenderGenerationService {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final class BuildTask {
        WorldSection section;
        final long position;
        boolean hasDoneModelRequestInner;
        boolean hasDoneModelRequestOuter;
        int attempts;
        int addin;
        long priority = Long.MIN_VALUE;
        private BuildTask(long position) {
            this.position = position;
        }
        private void updatePriority() {
            int unique = COUNTER.incrementAndGet();
            int lvl = WorldEngine.MAX_LOD_LAYER-WorldEngine.getLevel(this.position);
            lvl = Math.min(lvl, 3);//Make the 2 highest quality have equal priority
            this.priority = (((lvl*3L + Math.min(this.attempts, 4))*2 + this.addin) <<32) + Integer.toUnsignedLong(unique);
            this.addin = 0;
        }
    }

    private final PriorityBlockingQueue<BuildTask> taskQueue = new PriorityBlockingQueue<>(320000, (a,b)-> Long.compareUnsigned(a.priority, b.priority));
    private final Long2ObjectOpenHashMap<BuildTask> taskMap = new Long2ObjectOpenHashMap<>(320000);

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

    //TODO: add a generated render data cache
    private void processJob(RenderDataFactory45 factory, IntOpenHashSet seenMissedIds) {
        BuildTask task = this.taskQueue.poll();

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

        synchronized (this.taskMap) {
            var rtask = this.taskMap.remove(task.position);
            if (rtask != task) {
                throw new IllegalStateException();
            }
        }

        try {
            mesh = factory.generateMesh(section);
        } catch (IdNotYetComputedException e) {
            {
                BuildTask other;
                synchronized (this.taskMap) {
                    other = this.taskMap.putIfAbsent(task.position, task);
                }
                if (other != null) {//Weve been replaced
                    //Request the block
                    if (e.isIdBlockId) {
                        //TODO: maybe move this to _after_ task as been readded to queue??
                        if (!this.modelBakery.factory.hasModelForBlockId(e.id)) {
                            if (seenMissedIds.add(e.id)) {
                                this.modelBakery.requestBlockBake(e.id);
                            }
                        }
                    }
                    //Exchange info
                    if (task.hasDoneModelRequestInner) {
                        other.hasDoneModelRequestInner = true;
                    }
                    if (task.hasDoneModelRequestOuter) {
                        other.hasDoneModelRequestOuter = true;
                    }
                    task.section = null;
                    shouldFreeSection = true;
                    task = null;
                }
            }
            if (task != null) {
                //This is our task

                //Request the block
                if (e.isIdBlockId) {
                    //TODO: maybe move this to _after_ task as been readded to queue??
                    if (!this.modelBakery.factory.hasModelForBlockId(e.id)) {
                        if (seenMissedIds.add(e.id)) {
                            this.modelBakery.requestBlockBake(e.id);
                        }
                    }
                }

                if (task.hasDoneModelRequestInner && task.hasDoneModelRequestOuter) {
                    task.attempts++;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                } else {
                    if (!task.hasDoneModelRequestInner) {
                        //The reason for the extra id parameter is that we explicitly add/check against the exception id due to e.g. requesting accross a chunk boarder wont be captured in the request
                        if (e.auxData == null)//the null check this is because for it to be, the inner must already be computed
                            this.computeAndRequestRequiredModels(seenMissedIds, section);
                        task.hasDoneModelRequestInner = true;
                    }
                    //If this happens... aahaha painnnn
                    if (task.hasDoneModelRequestOuter) {
                        task.attempts++;
                    }

                    if ((!task.hasDoneModelRequestOuter) && e.auxData != null) {
                        this.computeAndRequestRequiredModels(seenMissedIds, e.auxBitMsk, e.auxData);
                        task.hasDoneModelRequestOuter = true;
                    }

                    task.addin = WorldEngine.getLevel(task.position)>2?3:0;//Single time addin which gives the models time to bake before the task executes
                }

                //Keep the lock on the section, and attach it to the task, this prevents needing to re-aquire it later
                task.section = section;
                shouldFreeSection = false;

                task.updatePriority();
                this.taskQueue.add(task);

                if (this.threads.isAlive()) {//Only execute if were not dead
                    this.threads.execute();//Since we put in queue, release permit
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
        boolean[] isOurs = new boolean[1];
        BuildTask task;
        synchronized (this.taskMap) {
            task = this.taskMap.computeIfAbsent(pos, p->{
                isOurs[0] = true;
                return new BuildTask(p);
            });
        }
        if (isOurs[0]) {//If its not ours we dont care about it
            //Set priority and insert into queue and execute
            task.updatePriority();
            this.taskQueue.add(task);
            this.threads.execute();
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

            synchronized (this.taskMap) {
                for (int j = 0; j < i; j++) {
                    var task = this.taskQueue.remove();
                    if (task.section != null) {
                        task.section.release();
                    }
                    if (this.taskMap.remove(task.position) != task) {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        //Shutdown the threads
        this.threads.shutdown();

        //Cleanup any remaining data
        while (!this.taskQueue.isEmpty()) {
            var task = this.taskQueue.remove();
            if (task.section != null) {
                task.section.release();
            }
            synchronized (this.taskMap) {
                if (this.taskMap.remove(task.position) != task) {
                    throw new IllegalStateException();
                }
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
