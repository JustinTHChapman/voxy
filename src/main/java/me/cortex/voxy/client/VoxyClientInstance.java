package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.client.world.ClientWorld;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;

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
        var vworld = ((IVoxyWorld)world).getWorldEngine();
        if (vworld == null) {
            vworld = this.createWorld(SELECTOR.getBestSelectionOrCreate(world).createSectionStorageBackend());
            ((IVoxyWorld)world).setWorldEngine(vworld);
            //testDbPerformance2(vworld);
        } else {
            if (!this.activeWorlds.contains(vworld)) {
                throw new IllegalStateException("World referenced does not exist in instance");
            }
        }
        return vworld;
    }



    private static void testDbPerformance(WorldEngine engine) {
        Random r = new Random(123456);
        r.nextLong();
        long start = System.currentTimeMillis();
        int c = 0;
        long tA = 0;
        long tR = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (i == 20_000) {
                c = 0;
                start = System.currentTimeMillis();
            }
            c++;
            int x = (r.nextInt(256*2+2)-256);//-32
            int z = (r.nextInt(256*2+2)-256);//-32
            int y = r.nextInt(2)-1;
            int lvl = 0;//r.nextInt(5);
            long t = System.nanoTime();
            var sec = engine.acquire(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl));
            tA += System.nanoTime()-t;
            t = System.nanoTime();
            sec.release();
            tR += System.nanoTime()-t;
        }
        long delta = System.currentTimeMillis() - start;
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average tA: " + tA + " tR: " + tR);
    }
    private static void testDbPerformance2(WorldEngine engine) {
        Random r = new Random(123456);
        r.nextLong();
        ConcurrentLinkedDeque<Long> queue = new ConcurrentLinkedDeque<>();
        var ser = engine.instanceIn.getThreadPool().createServiceNoCleanup("aa", 1, ()-> () ->{
            var sec = engine.acquire(queue.poll());
            sec.release();
        });
        int priming = 1_000_000;
        for (int i = 0; i < 2_000_000+priming; i++) {
            int x = (r.nextInt(256*2+2)-256)>>2;//-32
            int z = (r.nextInt(256*2+2)-256)>>2;//-32
            int y = r.nextInt(2)-1;
            int lvl = 0;//r.nextInt(5);
            queue.add(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl));
        }
        for (int i = 0; i < priming; i++) {
            ser.execute();
        }
        ser.blockTillEmpty();
        int c = queue.size();
        long start = System.currentTimeMillis();
        for (int i = 0; i < c; i++) {
            ser.execute();
        }
        ser.blockTillEmpty();
        long delta = System.currentTimeMillis() - start;
        ser.shutdown();
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average total, avg wrt threads: " + (((double)delta/c)*engine.instanceIn.getThreadPool().getThreadCount()) + "ms");
    }
}
