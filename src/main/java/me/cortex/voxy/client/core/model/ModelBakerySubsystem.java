package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;
    private final AtomicInteger blockIdCount = new AtomicInteger();
    private final ConcurrentLinkedDeque<Integer> blockIdQueue = new ConcurrentLinkedDeque<>();//TODO: replace with custom DS
    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();

    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
    }

    public void tick(long totalBudget) {
        //Upload all biomes
        while (!this.biomeQueue.isEmpty()) {
            var biome = this.biomeQueue.poll();
            var biomeReg = MinecraftClient.getInstance().world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
            this.factory.addBiome(biome.id, biomeReg.get(Identifier.of(biome.biome)));
        }


        /*
        //There should be a method to access the frame time IIRC, if the user framecap is unlimited lock it to like 60 fps for computation
        int BUDGET = 16;//TODO: make this computed based on the remaining free time in a frame (and like div by 2 to reduce overhead) (with a min of 1)
        if (!this.blockIdQueue.isEmpty()) {
            int[] est = new int[Math.min(this.blockIdQueue.size(), BUDGET)];
            int i = 0;
            synchronized (this.blockIdQueue) {
                for (;i < est.length && !this.blockIdQueue.isEmpty(); i++) {
                    int blockId = this.blockIdQueue.removeFirstInt();
                    if (blockId == -1) {
                        i--;
                        continue;
                    }
                    est[i] = blockId;
                }
            }

            for (int j = 0; j < i; j++) {
                this.factory.addEntry(est[j]);
            }
        }*/
        //TimingStatistics.modelProcess.start();
        if (this.blockIdCount.get() != 0) {
            //Always do 1 iteration minimum
            Integer i = this.blockIdQueue.poll();
            int j = 0;
            if (i != null) {
                int fbBinding = glGetInteger(GL_FRAMEBUFFER_BINDING);

                do {
                    this.factory.addEntry(i);
                    j++;
                    if (24<j)//budget<(System.nanoTime() - start)+1000
                        break;
                    i = this.blockIdQueue.poll();
                } while (i != null);

                glBindFramebuffer(GL_FRAMEBUFFER, fbBinding);//This is done here as stops needing to set then unset the fb in the thing 1000x
            }
            this.blockIdCount.addAndGet(-j);
        }

        this.factory.tick();

        long start = System.nanoTime();
        while (this.factory.processResult()) {
            if (totalBudget<(System.nanoTime()-start))
                break;
        }
        this.factory.processUploads();
        //TimingStatistics.modelProcess.stop();
    }

    public void shutdown() {
        this.factory.free();
        this.storage.free();
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);//TODO: move to a lock free concurrent hashmap
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() < blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return;
        }
        this.seenIdsLock.unlock();
        this.blockIdQueue.add(blockId);
        this.blockIdCount.incrementAndGet();
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.biomeQueue.add(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("MQ/IF/MC: %04d, %03d, %04d", this.blockIdCount.get(), this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdCount.get()==0 && this.factory.getInflightCount() == 0 && this.biomeQueue.isEmpty();
    }

    public int getProcessingCount() {
        return this.blockIdCount.get() + this.factory.getInflightCount();
    }
}
