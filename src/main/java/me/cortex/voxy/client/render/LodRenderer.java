package me.cortex.voxy.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.cortex.voxy.VoxyConstants;
import me.cortex.voxy.client.ClientLodManager;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple LOD terrain renderer.
 *
 * <p>Renders flat-shaded coloured quads for LOD sections that lie outside the
 * vanilla render distance.  LOD 0 is skipped (vanilla renders those chunks).
 * LOD 1-4 sections are rendered beyond the vanilla chunk boundary.
 *
 * <p>This is an intentionally minimal first-pass renderer: no lighting, no
 * textures, no frustum culling beyond distance filtering.  The primary goal is
 * to verify the full data pipeline (voxelise → network → client cache → GPU)
 * before a more sophisticated shader-driven renderer is built.
 */
public final class LodRenderer {

    private LodRenderer() {}

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(LodRenderer::onRenderLevelStage);
    }

    // -------------------------------------------------------------------------

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;

        ClientLodManager mgr = VoxyClient.getLodManager();
        if (mgr == null) return;

        Map<Long, LodSection> liveSections = mgr.getLiveSections();
        if (liveSections.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var camera = event.getCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        // Vanilla render distance in blocks.  We skip any LOD section whose
        // centre is closer than this to avoid overdrawing vanilla terrain.
        float vanillaRadiusBlocks = mc.options.renderDistance().get() * 16.0f;

        // Collect (section, quad-count) for sections that need rendering.
        // We need at least one quad to start the BufferBuilder.
        List<LodSection> toRender = new ArrayList<>();
        for (Map.Entry<Long, LodSection> entry : liveSections.entrySet()) {
            if (shouldRender(entry.getKey(), camX, camZ, vanillaRadiusBlocks)) {
                toRender.add(entry.getValue());
            }
        }

        if (toRender.isEmpty()) return;

        // -----------------------------------------------------------------------
        // Upload and draw
        // -----------------------------------------------------------------------
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull(); // render top face regardless of winding order
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // The PoseStack at AFTER_SOLID_BLOCKS already has the camera transform applied
        // (camera is at origin 0,0,0 in pose-stack space).  Emit vertices as
        // camera-relative coordinates to place them correctly in world space.
        // Doing the subtraction in double before casting to float also avoids float
        // precision loss at large world coordinates.
        Matrix4f matrix = event.getPoseStack().last().pose();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer  = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (LodSection section : toRender) {
            int lodLevel  = SectionKey.lodLevel(section.key);
            int cellSize  = 1 << lodLevel;
            int secX      = SectionKey.sectionX(section.key);
            int secZ      = SectionKey.sectionZ(section.key);
            int sectionBlocksWide = VoxyConstants.SECTION_SIZE * cellSize;

            double secWorldX = (double) secX * sectionBlocksWide;
            double secWorldZ = (double) secZ * sectionBlocksWide;

            for (int ci = 0; ci < VoxyConstants.SECTION_SIZE * VoxyConstants.SECTION_SIZE; ci++) {
                int blockStateId = section.blockStates[ci];
                if (blockStateId == 0) continue; // air

                int cx = ci % VoxyConstants.SECTION_SIZE;
                int cz = ci / VoxyConstants.SECTION_SIZE;

                float[] rel = cameraRelativePos(
                        secWorldX + (double)(cx * cellSize),
                        section.heights[ci] + 1.0,
                        secWorldZ + (double)(cz * cellSize),
                        camX, camY, camZ);
                float rx = rel[0], ry = rel[1], rz = rel[2];
                float s  = cellSize;

                int color = blockColorFor(blockStateId);
                float r = ((color >> 16) & 0xFF) / 255.0f;
                float g = ((color >>  8) & 0xFF) / 255.0f;
                float b = ( color        & 0xFF) / 255.0f;
                float bright = 0.7f + 0.3f * Math.min(1.0f, (section.heights[ci] + 1.0f) / 128.0f);
                r *= bright;
                g *= bright;
                b *= bright;

                // Top face (Y-up), CCW winding from above
                buffer.addVertex(matrix, rx,     ry, rz    ).setColor(r, g, b, 1.0f);
                buffer.addVertex(matrix, rx,     ry, rz + s).setColor(r, g, b, 1.0f);
                buffer.addVertex(matrix, rx + s, ry, rz + s).setColor(r, g, b, 1.0f);
                buffer.addVertex(matrix, rx + s, ry, rz    ).setColor(r, g, b, 1.0f);
            }
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.enableCull();
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a world-space position to camera-relative float coordinates.
     *
     * <p>The subtraction is performed in double precision before casting so that
     * large world coordinates (e.g. far from origin) do not lose precision.
     * All vertex positions fed to the GPU must be camera-relative because the
     * PoseStack at {@code AFTER_SOLID_BLOCKS} already has the camera transform applied.
     *
     * <p>Package-private so unit tests can verify the math directly.
     */
    public static float[] cameraRelativePos(double worldX, double worldY, double worldZ,
                                            double camX,   double camY,   double camZ) {
        return new float[] {
            (float)(worldX - camX),
            (float)(worldY - camY),
            (float)(worldZ - camZ),
        };
    }

    // -------------------------------------------------------------------------
    // Section visibility filter
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given section key should be included in the render pass.
     *
     * <ul>
     *   <li>LOD-0 sections are always excluded (vanilla renders those chunks).
     *   <li>Sections whose centre is closer than {@code vanillaRadiusBlocks} plus half the
     *       section width are excluded to avoid overdrawing vanilla terrain.
     * </ul>
     *
     * <p>Package-private so unit tests can verify the culling logic directly.
     */
    public static boolean shouldRender(long sectionKey, double camX, double camZ, float vanillaRadiusBlocks) {
        int lodLevel = SectionKey.lodLevel(sectionKey);
        if (lodLevel == 0) return false; // vanilla renders LOD-0 territory

        int cellSize          = 1 << lodLevel;
        int sectionBlocksWide = VoxyConstants.SECTION_SIZE * cellSize;

        double secWorldX = (double) SectionKey.sectionX(sectionKey) * sectionBlocksWide;
        double secWorldZ = (double) SectionKey.sectionZ(sectionKey) * sectionBlocksWide;
        double centerX   = secWorldX + sectionBlocksWide * 0.5;
        double centerZ   = secWorldZ + sectionBlocksWide * 0.5;
        double dist      = Math.sqrt((centerX - camX) * (centerX - camX)
                                   + (centerZ - camZ) * (centerZ - camZ));

        return dist >= vanillaRadiusBlocks + sectionBlocksWide * 0.5;
    }

    // -------------------------------------------------------------------------
    // Block colour palette
    // -------------------------------------------------------------------------

    private static int blockColorFor(int blockStateId) {
        try {
            BlockState state = Block.stateById(blockStateId);
            return paletteColor(state);
        } catch (Exception e) {
            return 0x888888;
        }
    }

    private static int paletteColor(BlockState state) {
        Block block = state.getBlock();

        // Water / lava
        if (block == Blocks.WATER)                                               return 0x3F76E4;
        if (block == Blocks.LAVA)                                                return 0xE04000;

        // Grass-family surface blocks
        if (block == Blocks.GRASS_BLOCK)                                         return 0x5D9B3E;
        if (block == Blocks.MYCELIUM)                                            return 0x7A6080;
        if (block == Blocks.PODZOL)                                              return 0x7A5530;

        // Dirt
        if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT || block == Blocks.MUD)          return 0x97694F;

        // Sand / gravel / clay
        if (block == Blocks.SAND || block == Blocks.SANDSTONE
                || block == Blocks.SMOOTH_SANDSTONE)                             return 0xDDB74D;
        if (block == Blocks.RED_SAND || block == Blocks.RED_SANDSTONE)          return 0xB4613B;
        if (block == Blocks.GRAVEL)                                              return 0x8A8A8A;
        if (block == Blocks.CLAY)                                                return 0x9BA5B0;

        // Stone family
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE
                || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.SMOOTH_STONE)                                 return 0x9B9B9B;
        if (block == Blocks.GRANITE || block == Blocks.POLISHED_GRANITE)        return 0xAA6347;
        if (block == Blocks.DIORITE || block == Blocks.POLISHED_DIORITE)        return 0xCACACA;
        if (block == Blocks.ANDESITE || block == Blocks.POLISHED_ANDESITE)      return 0x8C8C8C;
        if (block == Blocks.DEEPSLATE || block == Blocks.COBBLED_DEEPSLATE)     return 0x5A5A6A;
        if (block == Blocks.BEDROCK)                                             return 0x3C3C3C;
        if (block == Blocks.TUFF)                                                return 0x7A7A60;
        if (block == Blocks.CALCITE)                                             return 0xE0DDD8;

        // Ores (show as stone + slight tint)
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE)    return 0x606060;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)    return 0x967B6A;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE)    return 0xD4B942;
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return 0x48C8D8;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return 0x2DBD68;

        // Snow / ice
        if (block == Blocks.SNOW || block == Blocks.SNOW_BLOCK)                return 0xEAF2FF;
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE)                                     return 0xB0CCFF;

        // Leaves (any leaf tag)
        if (state.is(net.minecraft.tags.BlockTags.LEAVES))                      return 0x3A6B2A;

        // Logs / wood (any log tag)
        if (state.is(net.minecraft.tags.BlockTags.LOGS))                        return 0x6B4F2A;
        if (state.is(net.minecraft.tags.BlockTags.PLANKS))                      return 0xAA8044;

        // Nether
        if (block == Blocks.NETHERRACK)                                          return 0x7A2020;
        if (block == Blocks.NETHER_BRICKS || block == Blocks.CRACKED_NETHER_BRICKS) return 0x3A1A1A;
        if (block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL)             return 0x4A3A2A;
        if (block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM)    return 0x7A1A3A;
        if (block == Blocks.BASALT || block == Blocks.SMOOTH_BASALT
                || block == Blocks.POLISHED_BASALT)                              return 0x4A4A5A;
        if (block == Blocks.BLACKSTONE)                                          return 0x2A2A3A;
        if (block == Blocks.GLOWSTONE)                                           return 0xD0A020;
        if (block == Blocks.MAGMA_BLOCK)                                         return 0x8A3010;

        // End
        if (block == Blocks.END_STONE || block == Blocks.END_STONE_BRICKS)      return 0xD8D898;

        // Fallback: medium grey
        return 0x888888;
    }
}
