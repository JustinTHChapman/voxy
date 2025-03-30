package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ColourDepthTextureData;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelTextureBakery;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory45;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.post.PostProcessing;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.RawDownloadStream;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.RingTracker;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;

public class VoxyRenderSystem {
    private final RenderService renderer;
    private final PostProcessing postProcessing;
    private final WorldEngine worldIn;
    private final RenderDistanceTracker renderDistanceTracker;

    public VoxyRenderSystem(WorldEngine world, ServiceThreadPool threadPool) {
        //Trigger the shared index buffer loading
        SharedIndexBuffer.INSTANCE.id();
        Capabilities.init();//Ensure clinit is called

        this.worldIn = world;
        this.renderer = new RenderService(world, threadPool);
        this.postProcessing = new PostProcessing();

        this.renderDistanceTracker = new RenderDistanceTracker(10,
                MinecraftClient.getInstance().world.getBottomSectionCoord()>>5,
                (MinecraftClient.getInstance().world.getTopSectionCoord()-1)>>5,
                this.renderer::addTopLevelNode,
                this.renderer::removeTopLevelNode);

        this.renderDistanceTracker.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistanceTracker.setRenderDistance(renderDistance);
    }

    public void renderSetup(Frustum frustum, Camera camera) {
        this.renderDistanceTracker.setCenterAndProcess(camera.getBlockPos().getX(), camera.getBlockPos().getZ());

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

    //private static final ModelTextureBakery mtb = new ModelTextureBakery(16, 16);
    //private static final RawDownloadStream downstream = new RawDownloadStream(1<<20);
    public void renderOpaque(MatrixStack matrices, double cameraX, double cameraY, double cameraZ) {
        /*
        int allocation = downstream.download(2*4*6*16*16, ptr->{
            ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];
            final int FACE_SIZE = 16*16;
            for (int face = 0; face < 6; face++) {
                long faceDataPtr = ptr + (FACE_SIZE*4)*face*2;
                int[] colour = new int[FACE_SIZE];
                int[] depth = new int[FACE_SIZE];

                //Copy out colour
                for (int i = 0; i < FACE_SIZE; i++) {
                    //De-interpolate results
                    colour[i] = MemoryUtil.memGetInt(faceDataPtr+ (i*4*2));
                    depth[i] = MemoryUtil.memGetInt(faceDataPtr+ (i*4*2)+4);
                }

                textureData[face] = new ColourDepthTextureData(colour, depth, 16, 16);
            }

            int a = 0;
        });
        mtb.renderFacesToStream(Blocks.GRASS_BLOCK.getDefaultState(), 123456, false, downstream.getBufferId(), allocation);
        downstream.submit();
        downstream.tick();
         */

        //if (true) return;


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
        debug.add("GlBuffer, Count/Size (mb): " + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000));
        this.renderer.addDebugData(debug);
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {this.renderer.shutdown();} catch (Exception e) {Logger.error("Error shutting down renderer", e);}
        Logger.info("Shutting down post processor");
        if (this.postProcessing!=null){try {this.postProcessing.shutdown();} catch (Exception e) {Logger.error("Error shutting down post processor", e);}}
    }







    private void testMeshingPerformance() {
        var modelService = new ModelBakerySubsystem(this.worldIn.getMapper());
        var factory = new RenderDataFactory45(this.worldIn, modelService.factory, false);

        List<WorldSection> sections = new ArrayList<>();

        System.out.println("Loading sections");
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                for (int y = -1; y <= 4; y++) {
                    var section = this.worldIn.acquire(0, x, y, z);

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

    private void testFullMesh() {
        var modelService = new ModelBakerySubsystem(this.worldIn.getMapper());
        var completedCounter = new AtomicInteger();
        var generationService = new RenderGenerationService(this.worldIn, modelService, VoxyCommon.getInstance().getThreadPool(), a-> {completedCounter.incrementAndGet(); a.free();}, false);


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
            System.out.println("Time "+delta+"ms count: " + completedCounter.get() + " avg per mesh: " + ((double)delta/completedCounter.get()) + "ms");
            if (false)
                break;
        }
        generationService.shutdown();
        modelService.shutdown();
    }
}
