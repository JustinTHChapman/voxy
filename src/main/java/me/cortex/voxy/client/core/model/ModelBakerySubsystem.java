package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.util.RawDownloadStream;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.opengl.ARBFramebufferObject.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking


    private final RawDownloadStream textureDownStream = new RawDownloadStream(8*1024*1024);//8mb downstream
    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final IntLinkedOpenHashSet blockIdQueue = new IntLinkedOpenHashSet();
    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();

    public ModelBakerySubsystem(Mapper mapper) {
        this.factory = new ModelFactory(mapper, this.storage, this.textureDownStream);
    }

    public void tick() {
        //Upload all biomes
        while (!this.biomeQueue.isEmpty()) {
            var biome = this.biomeQueue.poll();
            var biomeReg = MinecraftClient.getInstance().world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
            this.factory.addBiome(biome.id, biomeReg.get(Identifier.of(biome.biome)));
        }


        //There should be a method to access the frame time IIRC, if the user framecap is unlimited lock it to like 60 fps for computation
        int BUDGET = 10;//TODO: make this computed based on the remaining free time in a frame (and like div by 2 to reduce overhead) (with a min of 1)
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
        }

        //Submit is effectively free if nothing is submitted
        this.textureDownStream.submit();

        //Tick the download stream
        this.textureDownStream.tick();
    }

    public void shutdown() {
        this.factory.free();
        this.storage.free();
        this.textureDownStream.free();
    }

    public void requestBlockBake(int blockId) {
        synchronized (this.blockIdQueue) {
            if (this.blockIdQueue.add(blockId)) {
                VarHandle.fullFence();//Ensure memory coherancy
            }
        }
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.biomeQueue.add(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add("MQ/IF/MC: " + this.blockIdQueue.size() + "/" + this.factory.getInflightCount() + "/" + this.factory.getBakedCount());//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdQueue.isEmpty() && this.factory.getInflightCount() == 0 && this.biomeQueue.isEmpty();
    }
}
