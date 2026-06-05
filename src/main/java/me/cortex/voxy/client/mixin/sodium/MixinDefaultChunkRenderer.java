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
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
            // Fog end is capped at the nearest ungenerated LOD chunk so empty sections
            // are always hidden.  getFogFrontierBlockRadius() shrinks as the player
            // approaches the LOD edge and grows as new chunks are generated.
            float fogEnd, fogStart;
            if (VoxyConfig.CONFIG.useEnvironmentalFog) {
                float frontier = AutoGenerationService.INSTANCE.getFogFrontierBlockRadius();
                fogEnd = Math.max(vanillaRD * 1.5f, frontier);
                // Same transition formula as vanilla terrain fog: clamp(fogEnd/10, 4, 64)
                float fogTransition = Mth.clamp(fogEnd / 10.0f, 4.0f, 64.0f);
                fogStart = Math.max(vanillaRD, fogEnd - fogTransition);
            } else {
                fogEnd = Float.MAX_VALUE;
                fogStart = Float.MAX_VALUE;
            }
            var fogParams = new VoxyFogParameters(fogColor[0], fogColor[1], fogColor[2], fogStart, fogEnd, 0);
            viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParams, camera.x, camera.y, camera.z);
        }
        renderer.renderOpaque(viewport);
        voxy$renderCount++;
    }
}
