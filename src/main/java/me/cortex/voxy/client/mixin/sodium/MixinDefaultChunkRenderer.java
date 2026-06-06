package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.VoxyFogParameters;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {
    private static long voxy$lastDiagLog = 0L;
    private static long voxy$callCount = 0L;
    private static long voxy$renderCount = 0L;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
                    shift = At.Shift.BEFORE
            ),
            remap = false,
            require = 0
    )
    private void voxy$injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        voxy$callCount++;
        if (renderPass != DefaultTerrainRenderPasses.CUTOUT) return;
        VoxyRenderSystem renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        long now = System.currentTimeMillis();
        if (now - voxy$lastDiagLog > 2000) {
            voxy$lastDiagLog = now;
            org.slf4j.LoggerFactory.getLogger("VoxyDiag").info("Sodium render hook: calls={} cutout-renders={} renderer={} cam=({},{},{}) HRS={} VS={} QC={}",
                    voxy$callCount, voxy$renderCount, renderer == null ? "null" : renderer.getClass().getSimpleName(),
                    camera.x, camera.y, camera.z,
                    java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.hierarchicalRenderSections),
                    java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.visibleSections),
                    java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.quadCount));
        }
        if (renderer == null) return;
        Viewport<?> viewport;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = renderer.getViewport();
        } else {
            float[] fogColor = RenderSystem.getShaderFogColor();
            float vanillaRD = VoxyRenderSystem.getRenderDistance();
            // Fog end tracks the actual generation frontier so it expands out as
            // auto-generation fills in chunks — hiding the unloaded boundary dynamically.
            float loadedBlockRadius = AutoGenerationService.INSTANCE.getEstimatedLoadedBlockRadius();
            float fogEnd   = Math.max(vanillaRD * 1.5f, loadedBlockRadius);
            float fogStart = Math.max(vanillaRD, fogEnd * 0.7f);
            float fogR = fogColor[0], fogG = fogColor[1], fogB = fogColor[2];

            // When the camera is submerged, extend the fluid tint through the full LOD distance.
            // We check the camera fluid type directly rather than relying on RenderSystem fog end
            // because Sodium manages its own fog state and getShaderFogEnd() may be stale.
            var mc2 = Minecraft.getInstance();
            var fluidInCamera = mc2.gameRenderer.getMainCamera().getFluidInCamera();
            if (fluidInCamera == FogType.WATER) {
                // Use biome water fog colour (not the shader fog colour which Sodium may override).
                if (mc2.level != null && mc2.player != null) {
                    int wfc = mc2.level.getBiome(mc2.player.blockPosition()).value().getWaterFogColor();
                    fogR = ((wfc >> 16) & 0xFF) / 255.0f;
                    fogG = ((wfc >>  8) & 0xFF) / 255.0f;
                    fogB =  (wfc        & 0xFF) / 255.0f;
                }
                fogStart = 0.0f;
            } else if (fluidInCamera == FogType.LAVA) {
                fogStart = 0.0f;
            }

            var fogParams = new VoxyFogParameters(fogR, fogG, fogB, fogStart, fogEnd, 0);
            viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParams, camera.x, camera.y, camera.z);
        }
        renderer.renderOpaque(viewport);
        voxy$renderCount++;
    }
}
