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
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(LodRenderer.class);
    /** Timestamp of last per-frame diagnostic log (throttled to once per 2 s). */
    private static volatile long lastDiagLogMs = 0;

    /**
     * Per-block-state sprite cache.  Populated lazily on the render thread.
     * The map is intentionally never invalidated here — sprites are stable across
     * a session unless the user changes resource packs (a full reload clears MC's
     * model system, so old references would become stale; a future improvement is
     * to hook into {@code RegisterClientReloadListenersEvent} to clear this).
     */
    private static final ConcurrentHashMap<Integer, TextureAtlasSprite> SPRITE_CACHE =
            new ConcurrentHashMap<>();

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
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        @SuppressWarnings("deprecation")
        var blockAtlasLoc = TextureAtlas.LOCATION_BLOCKS;
        RenderSystem.setShaderTexture(0, blockAtlasLoc);

        // -----------------------------------------------------------------------
        // Set up shader matrices
        // -----------------------------------------------------------------------
        // The POSITION_COLOR shader computes:
        //   gl_Position = ProjMat * ModelViewMat * Position
        //
        // After renderChunkLayer, RenderSystem.getModelViewMatrix() may have been
        // dirtied by chunk rendering.  We rebuild the view-rotation matrix directly
        // from camera.rotation() — the same quaternion MC applies to its PoseStack
        // to set up the view transform — so we are independent of RenderSystem state.
        //
        // Correct pattern:
        //   - Position (in buffer) = raw camera-relative coords  (identity per-vertex)
        //   - ModelViewMat         = camera rotation (from camera.rotation() quaternion)
        //   - ProjMat              = perspective     (already set by MC, we leave it)
        //
        // Camera-relative subtraction is done in double precision to avoid float
        // precision loss at large world coordinates.

        // Build view-rotation from the camera quaternion (authoritative)
        Matrix4f viewRotation = buildViewMatrix(camera.rotation());

        // Diagnostic logging (throttled to once per 2 s)
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDiagLogMs >= 2000L) {
            lastDiagLogMs = nowMs;
            Matrix4f rsMV = RenderSystem.getModelViewMatrix();
            Matrix4f evMV = event.getModelViewMatrix();
            // Full equality: evMV vs viewRot — if these disagree, camera.rotation() is wrong convention
            boolean evMatchesViewRot = evMV.equals(viewRotation, 1e-3f);
            boolean rsMVMatchesEvMV  = rsMV.equals(evMV, 1e-3f);
            LOGGER.info("[LOD diag] yaw={} pitch={} sections={} | evMV=rsMV={} evMV=viewRot={}",
                    String.format("%.1f", camera.getYRot()),
                    String.format("%.1f", camera.getXRot()),
                    toRender.size(),
                    rsMVMatchesEvMV,
                    evMatchesViewRot);
            LOGGER.info("[LOD diag] evMV diag=({},{},{}) viewRot diag=({},{},{}) rsMV diag=({},{},{})",
                    String.format("%.4f", evMV.m00()), String.format("%.4f", evMV.m11()), String.format("%.4f", evMV.m22()),
                    String.format("%.4f", viewRotation.m00()), String.format("%.4f", viewRotation.m11()), String.format("%.4f", viewRotation.m22()),
                    String.format("%.4f", rsMV.m00()), String.format("%.4f", rsMV.m11()), String.format("%.4f", rsMV.m22()));
            LOGGER.info("[LOD diag] camPos=({},{},{})",
                    String.format("%.1f", camX), String.format("%.1f", camY), String.format("%.1f", camZ));
        }

        // Use event.getModelViewMatrix() — NeoForge explicitly provides this for mods
        // rendering at this stage.  It is the exact matrix LevelRenderer received from
        // GameRenderer for the current frame, independent of RenderSystem state.
        // Save and restore so subsequent rendering is unaffected.
        Matrix4f savedModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        RenderSystem.getModelViewMatrix().set(event.getModelViewMatrix());

        Matrix4f identity = new Matrix4f(); // identity — camera rotation is in ModelViewMat above

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer  = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        for (LodSection section : toRender) {
            int lodLevel  = SectionKey.lodLevel(section.key);
            int cellSize  = 1 << lodLevel;
            int secX      = SectionKey.sectionX(section.key);
            int secZ      = SectionKey.sectionZ(section.key);
            int sectionBlocksWide = VoxyConstants.SECTION_SIZE * cellSize;

            double secWorldX = (double) secX * sectionBlocksWide;
            double secWorldZ = (double) secZ * sectionBlocksWide;

            int SIZE = VoxyConstants.SECTION_SIZE;
            for (int ci = 0; ci < SIZE * SIZE; ci++) {
                int blockStateId = section.blockStates[ci];
                if (blockStateId == 0) continue; // air

                int cx = ci % SIZE;
                int cz = ci / SIZE;

                // Fetch the top-face sprite and apply any block-specific tint.
                TextureAtlasSprite topSprite = getTopSpriteForState(blockStateId, mc);
                float tu0 = topSprite.getU0(), tu1 = topSprite.getU1();
                float tv0 = topSprite.getV0(), tv1 = topSprite.getV1();

                TextureAtlasSprite sideSprite = getSideSpriteForState(blockStateId, mc);
                float su0 = sideSprite.getU0(), su1 = sideSprite.getU1();
                float sv0 = sideSprite.getV0(), sv1 = sideSprite.getV1();

                int tint = blockTintFor(blockStateId);
                float tr = ((tint >> 16) & 0xFF) / 255.0f;
                float tg = ((tint >>  8) & 0xFF) / 255.0f;
                float tb = ( tint        & 0xFF) / 255.0f;

                float bright = 0.7f + 0.3f * Math.min(1.0f, (section.heights[ci] + 1.0f) / 128.0f);

                float[] rel = cameraRelativePos(
                        secWorldX + (double)(cx * cellSize),
                        section.heights[ci] + 1.0,
                        secWorldZ + (double)(cz * cellSize),
                        camX, camY, camZ);
                float rx = rel[0], ry = rel[1], rz = rel[2];
                float s = (float) cellSize;

                // Top face — full brightness
                float rt = tr * bright, gt = tg * bright, bt = tb * bright;
                buffer.addVertex(identity, rx,     ry, rz    ).setUv(tu0, tv0).setColor(rt, gt, bt, 1.0f);
                buffer.addVertex(identity, rx,     ry, rz + s).setUv(tu0, tv1).setColor(rt, gt, bt, 1.0f);
                buffer.addVertex(identity, rx + s, ry, rz + s).setUv(tu1, tv1).setColor(rt, gt, bt, 1.0f);
                buffer.addVertex(identity, rx + s, ry, rz    ).setUv(tu1, tv0).setColor(rt, gt, bt, 1.0f);

                // Side faces — 65 % brightness for depth cue
                float rs = tr * bright * 0.65f;
                float gs = tg * bright * 0.65f;
                float bs = tb * bright * 0.65f;

                // +X (east) side
                float eny = neighborTopY(section, cx + 1, cz, SIZE, ry, s, camY);
                if (eny < ry) {
                    buffer.addVertex(identity, rx+s, ry,  rz    ).setUv(su1, sv0).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx+s, eny, rz    ).setUv(su1, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx+s, eny, rz + s).setUv(su0, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx+s, ry,  rz + s).setUv(su0, sv0).setColor(rs, gs, bs, 1.0f);
                }

                // -X (west) side
                float wny = neighborTopY(section, cx - 1, cz, SIZE, ry, s, camY);
                if (wny < ry) {
                    buffer.addVertex(identity, rx, ry,  rz + s).setUv(su0, sv0).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx, wny, rz + s).setUv(su0, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx, wny, rz    ).setUv(su1, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx, ry,  rz    ).setUv(su1, sv0).setColor(rs, gs, bs, 1.0f);
                }

                // +Z (south) side
                float sny = neighborTopY(section, cx, cz + 1, SIZE, ry, s, camY);
                if (sny < ry) {
                    buffer.addVertex(identity, rx + s, ry,  rz + s).setUv(su1, sv0).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx + s, sny, rz + s).setUv(su1, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx,     sny, rz + s).setUv(su0, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx,     ry,  rz + s).setUv(su0, sv0).setColor(rs, gs, bs, 1.0f);
                }

                // -Z (north) side
                float nny = neighborTopY(section, cx, cz - 1, SIZE, ry, s, camY);
                if (nny < ry) {
                    buffer.addVertex(identity, rx,     ry,  rz).setUv(su0, sv0).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx,     nny, rz).setUv(su0, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx + s, nny, rz).setUv(su1, sv1).setColor(rs, gs, bs, 1.0f);
                    buffer.addVertex(identity, rx + s, ry,  rz).setUv(su1, sv0).setColor(rs, gs, bs, 1.0f);
                }
            }
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        // Restore RenderSystem model-view so subsequent rendering is unaffected
        RenderSystem.getModelViewMatrix().set(savedModelView);
        RenderSystem.enableCull();
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the camera-relative top-Y of a neighboring cell in the same section.
     * If the neighbor is out of bounds or air, returns {@code currentTopY - defaultDepth}
     * so a face is always drawn at section/air boundaries.
     * Returns {@code currentTopY} if the neighbor is at or above the current cell
     * (caller skips the face in that case).
     */
    private static float neighborTopY(LodSection section, int nx, int nz, int size,
                                       float currentTopY, float defaultDepth, double camY) {
        if (nx >= 0 && nx < size && nz >= 0 && nz < size) {
            int nci = nz * size + nx;
            if (section.blockStates[nci] != 0) {
                return (float)(section.heights[nci] + 1.0 - camY);
            }
        }
        return currentTopY - defaultDepth;
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
     * <p>Public for unit-test access.
     */
    public static float[] cameraRelativePos(double worldX, double worldY, double worldZ,
                                            double camX,   double camY,   double camZ) {
        return new float[] {
            (float)(worldX - camX),
            (float)(worldY - camY),
            (float)(worldZ - camZ),
        };
    }

    /**
     * Builds the camera view-rotation matrix from the camera's orientation quaternion.
     *
     * <p>This is the same rotation Minecraft applies to its PoseStack in
     * {@code GameRenderer.renderLevel()} via {@code poseStack.mulPose(camera.rotation())}.
     * The resulting matrix, when stored in {@code RenderSystem.getModelViewMatrix()},
     * transforms camera-relative world coordinates into eye space for the shader:
     * <pre>  gl_Position = ProjMat * ModelViewMat * Position</pre>
     *
     * <p>Public for unit-test access.
     */
    public static Matrix4f buildViewMatrix(org.joml.Quaternionf cameraRotation) {
        return new Matrix4f().rotation(cameraRotation);
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
    // Sprite / texture helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a cached top-face {@link TextureAtlasSprite} for the given block-state ID.
     * Falls back to the particle icon when no up-facing baked quad is available.
     */
    @SuppressWarnings("deprecation")
    private static TextureAtlasSprite getTopSpriteForState(int blockStateId, Minecraft mc) {
        return SPRITE_CACHE.computeIfAbsent(blockStateId, id -> {
            try {
                BlockState state = Block.stateById(id);
                var model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
                var quads = model.getQuads(state, Direction.UP, RandomSource.create(),
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                if (!quads.isEmpty()) {
                    return quads.get(0).getSprite();
                }
            } catch (Exception ignored) {}
            try {
                return mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(Block.stateById(id));
            } catch (Exception ignored) {}
            return mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
        });
    }

    /**
     * Returns a side-face sprite for the given block-state ID.
     * Tries the NORTH face first; falls back to the top sprite.
     */
    private static TextureAtlasSprite getSideSpriteForState(int blockStateId, Minecraft mc) {
        // Re-use SPRITE_CACHE keyed by a negative-offset sentinel so side and top
        // can be cached independently without a second map.
        int sideKey = -(blockStateId + 1);
        return SPRITE_CACHE.computeIfAbsent(sideKey, id -> {
            int bsid = -(id + 1);
            try {
                BlockState state = Block.stateById(bsid);
                var model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
                var quads = model.getQuads(state, Direction.NORTH, RandomSource.create(),
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                if (!quads.isEmpty()) {
                    return quads.get(0).getSprite();
                }
            } catch (Exception ignored) {}
            // Fall back to the top sprite
            return getTopSpriteForState(bsid, mc);
        });
    }

    // -------------------------------------------------------------------------
    // Block tint palette  (applied as colour multiplier on top of the sprite)
    // -------------------------------------------------------------------------

    /**
     * Returns an RGB tint (packed as {@code 0xRRGGBB}) to multiply against the
     * block's sprite texture.  Returns {@code 0xFFFFFF} (white, no tint) for
     * blocks whose atlas texture already carries the right colour.
     *
     * <p>Tinting is needed for blocks whose atlas texture is grayscale and relies
     * on a biome/block colour provider at render time (grass tops, leaves, water).
     */
    private static int blockTintFor(int blockStateId) {
        try {
            BlockState state = Block.stateById(blockStateId);
            Block block = state.getBlock();

            // Water / lava — tint the animated texture
            if (block == Blocks.WATER)                              return 0x3F76E4;
            if (block == Blocks.LAVA)                               return 0xFFFFFF; // lava texture is self-coloured

            // Grass top is a grayscale texture — apply plains biome colour
            if (block == Blocks.GRASS_BLOCK)                        return 0x91BD59;

            // Leaves are grayscale in the atlas, need foliage tint
            if (state.is(net.minecraft.tags.BlockTags.LEAVES))      return 0x77AB2F;

            // Vine / lily pad / seagrass
            if (block == Blocks.VINE || block == Blocks.LILY_PAD)   return 0x77AB2F;

            // Everything else: sprite texture provides the colour
            return 0xFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }
}
