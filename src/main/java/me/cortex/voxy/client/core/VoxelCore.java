package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.*;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory4;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.post.PostProcessing;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.client.taskbar.Taskbar;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL30C.*;

//Core class that ingests new data from sources and updates the required systems

//3 primary services:
// ingest service: this takes in unloaded chunk events from the client, processes the chunk and critically also updates the lod view of the world
// render data builder service: this service builds the render data from build requests it also handles the collecting of build data for the selected region (only axis aligned single lod tasks)
// serialization service: serializes changed world data and ensures that the database and any loaded data are in sync such that the database can never be more updated than loaded data, also performs compression on serialization

//there are multiple subsystems
//player tracker system (determines what lods are loaded and used by the player)
//updating system (triggers render data rebuilds when something from the ingest service causes an LOD change)
//the render system simply renders what data it has, its responsable for gpu memory layouts in arenas and rendering in an optimal way, it makes no requests back to any of the other systems or services, it just applies render data updates

//There is strict forward only dataflow
//Ingest -> world engine -> raw render data -> render data



//REDESIGN THIS PIECE OF SHIT SPAGETTY SHIT FUCK
// like Get rid of interactor and renderer being seperate just fucking put them together
// fix the callback bullshit spagetti
//REMOVE setRenderGen like holy hell
public class VoxelCore {
    private final WorldEngine world;

    private final RenderService renderer;
    private final PostProcessing postProcessing;
    private final ServiceThreadPool serviceThreadPool;

    private WorldImporter importer;
    private UUID importerBossBarUUID;

    public VoxelCore(ContextSelectionSystem.Selection worldSelection) {
        var cfg = worldSelection.getConfig();
        this.serviceThreadPool = new ServiceThreadPool(VoxyConfig.CONFIG.serviceThreads);

        this.world = worldSelection.createEngine(this.serviceThreadPool);
        Logger.info("Initializing voxy core");

        //Trigger the shared index buffer loading
        SharedIndexBuffer.INSTANCE.id();
        Capabilities.init();//Ensure clinit is called

        this.renderer = new RenderService(this.world, this.serviceThreadPool);
        Logger.info("Using " + this.renderer.getClass().getSimpleName());
        this.postProcessing = new PostProcessing();

        Logger.info("Voxy core initialized");

        //this.verifyTopNodeChildren(0,0,0);

        //this.testMeshingPerformance();

        //this.testDbPerformance();
        //this.testFullMesh();
    }

    public void enqueueIngest(WorldChunk worldChunk) {
        this.world.ingestService.enqueueIngest(worldChunk);
    }

    public void renderSetup(Frustum frustum, Camera camera) {
        this.renderer.setup(camera);
        PrintfDebugUtil.tick();
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = MinecraftClient.getInstance();
        var gameRenderer = client.gameRenderer;//tickCounter.getTickDelta(true);

        float fov = gameRenderer.getFov(gameRenderer.getCamera(), client.getRenderTickCounter().getTickDelta(true), true);

        projection.setPerspective(fov * 0.01745329238474369f,
                (float) client.getWindow().getFramebufferWidth() / (float)client.getWindow().getFramebufferHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat() {
        return new Matrix4f(RenderSystem.getProjectionMatrix()).mulLocal(
                makeProjectionMatrix(0.05f, MinecraftClient.getInstance().gameRenderer.getFarPlaneDistance()).invert()
        ).mulLocal(makeProjectionMatrix(16, 16*3000));
    }

    public void renderOpaque(MatrixStack matrices, double cameraX, double cameraY, double cameraZ) {
        if (IrisUtil.irisShadowActive()) {
            return;
        }

        if (false) {
            float CHANGE_PER_SECOND = 30;
            //Auto fps targeting
            if (MinecraftClient.getInstance().getCurrentFps() < 45) {
                VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + CHANGE_PER_SECOND / Math.max(1f, MinecraftClient.getInstance().getCurrentFps()), 256);
            }

            if (55 < MinecraftClient.getInstance().getCurrentFps()) {
                VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - CHANGE_PER_SECOND / Math.max(1f, MinecraftClient.getInstance().getCurrentFps()), 30);
            }
        }

        //Do some very cheeky stuff for MiB
        if (false) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        matrices.push();
        matrices.translate(-cameraX, -cameraY, -cameraZ);
        matrices.pop();

        var projection = computeProjectionMat();//RenderSystem.getProjectionMatrix();
        //var projection = RenderSystem.getProjectionMatrix();

        var viewport = this.renderer.getViewport();
        viewport
                .setProjection(projection)
                .setModelView(matrices.peek().getPositionMatrix())
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(MinecraftClient.getInstance().getFramebuffer().textureWidth, MinecraftClient.getInstance().getFramebuffer().textureHeight);
        viewport.frameId++;

        int boundFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }
        //TODO: use the raw depth buffer texture instead
        //int boundDepthBuffer = glGetNamedFramebufferAttachmentParameteri(boundFB, GL_DEPTH_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

        //TODO:FIXME!!! ??
        this.postProcessing.setup(MinecraftClient.getInstance().getFramebuffer().textureWidth, MinecraftClient.getInstance().getFramebuffer().textureHeight, boundFB);

        this.renderer.renderFarAwayOpaque(viewport);

        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(projection, matrices);

        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport);


        this.postProcessing.renderPost(projection, RenderSystem.getProjectionMatrix(), boundFB);

    }

    public void addDebugInfo(List<String> debug) {
        debug.add("");
        debug.add("");
        debug.add("Voxy Core: " + VoxyCommon.MOD_VERSION);
        debug.add("MemoryBuffer, Count/Size (mb): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize()/1_000_000));
        debug.add("GlBuffer, Count/Size (mb): " + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000));
        /*
        debug.add("Ingest service tasks: " + this.world.ingestService.getTaskCount());
        debug.add("Saving service tasks: " + this.world.savingService.getTaskCount());
        debug.add("Render service tasks: " + this.renderGen.getTaskCount());
         */
        debug.add("I/S tasks: " + this.world.ingestService.getTaskCount() + "/"+this.world.savingService.getTaskCount());
        this.world.addDebugData(debug);
        this.renderer.addDebugData(debug);

        PrintfDebugUtil.addToOut(debug);
    }

    //Note: when doing translucent rendering, only need to sort when generating the geometry, or when crossing into the center zone
    // cause in 99.99% of cases the sections dont need to be sorted
    // since they are AABBS crossing the normal is impossible without one of the axis being equal

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //if (Thread.currentThread() != this.shutdownThread) {
        //    Runtime.getRuntime().removeShutdownHook(this.shutdownThread);
        //}

        //this.world.getMapper().forceResaveStates();
        if (this.importer != null) {
            Logger.info("Shutting down importer");
            try {this.importer.shutdown();this.importer = null;} catch (Exception e) {Logger.error("Error shutting down importer", e);}
        }
        Logger.info("Shutting down rendering");
        try {this.renderer.shutdown();} catch (Exception e) {Logger.error("Error shutting down renderer", e);}
        Logger.info("Shutting down post processor");
        if (this.postProcessing!=null){try {this.postProcessing.shutdown();} catch (Exception e) {Logger.error("Error shutting down post processor", e);}}
        Logger.info("Shutting down world engine");
        try {this.world.shutdown();} catch (Exception e) {Logger.error("Error shutting down world engine", e);}
        Logger.info("Shutting down service thread pool");
        this.serviceThreadPool.shutdown();
        Logger.info("Voxel core shut down");
        //Remove bossbar
        if (this.importerBossBarUUID != null) {
            MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.remove(this.importerBossBarUUID);
            Taskbar.INSTANCE.setIsNone();
        }
    }

    public boolean createWorldImporter(World mcWorld, File worldPath) {
        if (this.importer == null) {
            this.importer = new WorldImporter(this.world, mcWorld, this.serviceThreadPool);
        }
        if (this.importer.isBusy()) {
            return false;
        }

        Taskbar.INSTANCE.setProgress(0,10000);
        Taskbar.INSTANCE.setIsProgression();

        this.importerBossBarUUID = MathHelper.randomUuid();
        var bossBar = new ClientBossBar(this.importerBossBarUUID, Text.of("Voxy world importer"), 0.0f, BossBar.Color.GREEN, BossBar.Style.PROGRESS, false, false, false);
        MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.put(bossBar.getUuid(), bossBar);
        long start = System.currentTimeMillis();
        this.importer.importWorldAsyncStart(worldPath, (a,b)->
                MinecraftClient.getInstance().executeSync(()-> {
                    Taskbar.INSTANCE.setProgress(a, b);
                    bossBar.setPercent(((float) a)/((float) b));
                    bossBar.setName(Text.of("Voxy import: "+ a+"/"+b + " chunks"));
                }),
                chunkCount -> {
                    MinecraftClient.getInstance().executeSync(()-> {
                        MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.remove(this.importerBossBarUUID);
                        this.importerBossBarUUID = null;
                        long delta = System.currentTimeMillis() - start;

                        String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (chunkCount/(delta/1000)) + " chunks per second";
                        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(msg));
                        Logger.info(msg);
                        Taskbar.INSTANCE.setIsNone();
                    });
                });
        return true;
    }

    public WorldEngine getWorldEngine() {
        return this.world;
    }





    private void verifyTopNodeChildren(int X, int Y, int Z) {
        for (int lvl = 0; lvl < 5; lvl++) {
            for (int y = (Y<<5)>>lvl; y < ((Y+1)<<5)>>lvl; y++) {
                for (int x = (X<<5)>>lvl; x < ((X+1)<<5)>>lvl; x++) {
                    for (int z = (Z<<5)>>lvl; z < ((Z+1)<<5)>>lvl; z++) {
                        if (lvl == 0) {
                            var own = this.world.acquire(lvl, x, y, z);
                            if ((own.getNonEmptyChildren() != 0) ^ (own.getNonEmptyBlockCount() != 0)) {
                                Logger.error("Lvl 0 node not marked correctly " + WorldEngine.pprintPos(own.key));
                            }
                            own.release();
                        } else {
                            byte msk = 0;
                            for (int child = 0; child < 8; child++) {
                                var section = this.world.acquire(lvl-1, (child&1)+(x<<1), ((child>>2)&1)+(y<<1), ((child>>1)&1)+(z<<1));
                                msk |= (byte) (section.getNonEmptyBlockCount()!=0?(1<<child):0);
                                section.release();
                            }
                            var own = this.world.acquire(lvl, x, y, z);
                            if (own.getNonEmptyChildren() != msk) {
                                Logger.error("Section empty child mask not correct " + WorldEngine.pprintPos(own.key) + " got: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(own.getNonEmptyChildren()))).replace(' ', '0') + " expected: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                            }
                            own.release();
                        }
                    }
                }
            }
        }
    }






    private void testMeshingPerformance() {
        var modelService = new ModelBakerySubsystem(this.world.getMapper());
        var factory = new RenderDataFactory4(this.world, modelService.factory, false);

        List<WorldSection> sections = new ArrayList<>();

        System.out.println("Loading sections");
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                for (int y = -1; y <= 4; y++) {
                    var section = this.world.acquire(0, x, y, z);

                    int nonAir = 0;
                    for (long state : section.copyData()) {
                        nonAir += Mapper.isAir(state)?0:1;
                        modelService.requestBlockBake(Mapper.getBlockId(state));
                    }

                    if (nonAir > 500 && Math.abs(x) <= 16 && Math.abs(z) <= 16) {
                        sections.add(section);
                    } else {
                        section.release();
                    }
                }
            }
        }

        System.out.println("Baking models");
        {
            //Bake everything
            while (!modelService.areQueuesEmpty()) {
                modelService.tick();
                glFinish();
            }
        }

        System.out.println("Ready!");

        {
            int iteration = 0;
            while (true) {
                long start = System.currentTimeMillis();
                for (var section : sections) {
                    var mesh = factory.generateMesh(section);

                    mesh.free();
                }
                long delta = System.currentTimeMillis() - start;
                System.out.println("Iteration: " + (iteration++) + " took " + delta + "ms, for an average of " + ((float)delta/sections.size()) + "ms per section");
                //System.out.println("Quad count: " + factory.quadCount);
            }
        }

    }


    private void testDbPerformance() {
        Random r = new Random(123456);
        r.nextLong();
        long start = System.currentTimeMillis();
        int c = 0;
        for (int i = 0; i < 500_000; i++) {
            if (i == 20_000) {
                c = 0;
                start = System.currentTimeMillis();
            }
            c++;
            int x = (r.nextInt(256*2+2)-256)>>1;//-32
            int z = (r.nextInt(256*2+2)-256)>>1;//-32
            int y = 0;
            int lvl = 0;//r.nextInt(5);
            this.world.acquire(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl)).release();
        }
        long delta = System.currentTimeMillis() - start;
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average" );
    }



    private void testFullMesh() {
        var modelService = new ModelBakerySubsystem(this.world.getMapper());
        var completedCounter = new AtomicInteger();
        var generationService = new RenderGenerationService(this.world, modelService, this.serviceThreadPool, a-> {completedCounter.incrementAndGet(); a.free();}, false);


        var r = new Random(12345);
        {
            for (int i = 0; i < 10_000; i++) {
                int x = (r.nextInt(256*2+2)-256)>>1;//-32
                int z = (r.nextInt(256*2+2)-256)>>1;//-32
                int y = r.nextInt(10)-2;
                int lvl = 0;//r.nextInt(5);
                long key = WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl);
                generationService.enqueueTask(key);
            }
            int i = 0;
            while (true) {
                modelService.tick();
                if (i++%5000==0)
                    System.out.println(completedCounter.get());
                glFinish();
                List<String> a = new ArrayList<>();
                generationService.addDebugData(a);
                if (a.getFirst().endsWith(" 0")) {
                    break;
                }
            }
        }

        System.out.println("Running benchmark");
        while (true)
        {
            completedCounter.set(0);
            long start = System.currentTimeMillis();
            int C = 200_000;
            for (int i = 0; i < C; i++) {
                int x = (r.nextInt(256 * 2 + 2) - 256) >> 1;//-32
                int z = (r.nextInt(256 * 2 + 2) - 256) >> 1;//-32
                int y = r.nextInt(10) - 2;
                int lvl = 0;//r.nextInt(5);
                long key = WorldEngine.getWorldSectionId(lvl, x >> lvl, y >> lvl, z >> lvl);
                generationService.enqueueTask(key);
            }
            //int i = 0;
            while (true) {
                //if (i++%5000==0)
                //    System.out.println(completedCounter.get());
                modelService.tick();
                glFinish();
                List<String> a = new ArrayList<>();
                generationService.addDebugData(a);
                if (a.getFirst().endsWith(" 0")) {
                    break;
                }
            }
            long delta = (System.currentTimeMillis()-start);
            System.out.println("Time "+delta+"ms count: " + completedCounter.get() + " avg per mesh: " + ((double)delta/completedCounter.get()));
            if (false)
                break;
        }
        generationService.shutdown();
        modelService.shutdown();
    }
}
