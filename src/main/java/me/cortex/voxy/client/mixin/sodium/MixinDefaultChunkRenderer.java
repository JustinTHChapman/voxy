package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import org.lwjgl.opengl.GL11;
import me.cortex.voxy.client.config.VoxyConfig;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {
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
        if (renderPass != DefaultTerrainRenderPasses.CUTOUT) return;
        VoxyRenderSystem renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (IrisUtil.isRenderingShadowMap()) {
            if (renderer == null) return;
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            renderer.renderShadow(matrices.projection(), matrices.modelView(), vp[2], vp[3], camera.x, camera.y, camera.z);
            return;
        }
        if (renderer == null) return;

        float[] fogColor = RenderSystem.getShaderFogColor();
        float vanillaRD = VoxyRenderSystem.getRenderDistance();
        float fogEnd, fogStart;
        if (VoxyConfig.CONFIG.useEnvironmentalFog) {
            // Frontier shrinks as player approaches ungenerated LOD edge, grows as
            // chunks are generated — smoothed each tick so the transition is gradual.
            float frontier = AutoGenerationService.INSTANCE.getFogFrontierBlockRadius();
            fogEnd = Math.max(vanillaRD * 1.5f, frontier);
            float fogTransition = Mth.clamp(fogEnd / 10.0f, 4.0f, 64.0f);
            fogStart = Math.max(vanillaRD, fogEnd - fogTransition);
        } else {
            fogEnd   = Float.MAX_VALUE;
            fogStart = Float.MAX_VALUE;
        }
        float fogR = fogColor[0], fogG = fogColor[1], fogB = fogColor[2];

        // When the camera is submerged, match vanilla's short fog distance so LOD terrain
        // doesn't appear unnaturally clear at depths that should be opaque with fluid fog.
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
            // Use vanilla's underwater fog end; fall back to 32 blocks if Sodium's terrain
            // fog state is stale (returns a value as large as the render distance).
            float vanillaFogEnd = RenderSystem.getShaderFogEnd();
            fogEnd = (vanillaFogEnd < vanillaRD) ? vanillaFogEnd : 32.0f;
        } else if (fluidInCamera == FogType.LAVA) {
            fogStart = 0.0f;
            float vanillaFogEnd = RenderSystem.getShaderFogEnd();
            fogEnd = (vanillaFogEnd < vanillaRD) ? vanillaFogEnd : 4.0f;
        }

        var fogParams = new VoxyFogParameters(fogR, fogG, fogB, fogStart, fogEnd, 0);
        Viewport<?> viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParams, camera.x, camera.y, camera.z);
        renderer.renderOpaque(viewport);
    }
}
