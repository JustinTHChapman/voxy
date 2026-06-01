package me.cortex.voxy.test;

import me.cortex.voxy.VoxyConstants;
import me.cortex.voxy.client.render.LodRenderer;
import me.cortex.voxy.common.lod.SectionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LodRenderer#shouldRender} — the section visibility filter.
 *
 * <p>These tests are pure math (no MC API, no OpenGL, no block registry) and verify
 * that the right LOD sections are included/excluded from each render pass.
 */
class LodRendererFilterTest {

    // Camera sitting at world origin for most tests
    private static final double CAM_X = 0.0;
    private static final double CAM_Z = 0.0;
    private static final float  VANILLA_RADIUS = 16 * 16.0f; // render distance 16 → 256 blocks

    // -------------------------------------------------------------------------
    // LOD-0 is always excluded
    // -------------------------------------------------------------------------

    @Test
    void lod0SectionIsNeverRendered() {
        // LOD-0, even far away, must be excluded (vanilla renders it)
        long key = SectionKey.encode(0, 100, 100);
        assertFalse(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS),
                "LOD-0 sections must never be rendered by LodRenderer");
    }

    @Test
    void lod0AtOriginIsNeverRendered() {
        long key = SectionKey.encode(0, 0, 0);
        assertFalse(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS));
    }

    // -------------------------------------------------------------------------
    // Distance culling for LOD ≥ 1
    // -------------------------------------------------------------------------

    @Test
    void lod1SectionFarAwayIsRendered() {
        // LOD-1 section size = 16 cells × 2 blocks/cell = 32 blocks
        // Place it at section (100, 0) → world X = 3200..3232 → centre at X=3216
        // Distance from origin = 3216 >> well beyond 256 vanilla radius
        long key = SectionKey.encode(1, 100, 0);
        assertTrue(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS),
                "LOD-1 section far from camera must be rendered");
    }

    @Test
    void lod1SectionDirectlyUnderCameraIsNotRendered() {
        // LOD-1 section at (0,0) has world coords 0..32, centre at 16.
        // Distance = 16, vanilla radius = 256 → clearly inside → skip
        long key = SectionKey.encode(1, 0, 0);
        assertFalse(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS),
                "LOD-1 section directly under camera must be culled (inside vanilla range)");
    }

    @Test
    void lod2SectionJustBeyondVanillaEdgeIsRendered() {
        // LOD-2 section size = 16 × 4 = 64 blocks.
        // Cull threshold = vanillaRadius + sectionWidth/2 = 256 + 32 = 288 blocks.
        // Place section at (5, 0): world X = 5*64 = 320..384, centre at 352.
        // Distance = 352 > 288 → rendered.
        long key = SectionKey.encode(2, 5, 0);
        assertTrue(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS),
                "LOD-2 section just beyond cull threshold must be rendered");
    }

    @Test
    void lod2SectionJustInsideVanillaEdgeIsNotRendered() {
        // LOD-2 section size = 64 blocks, threshold = 256 + 32 = 288.
        // Section at (4, 0): world X = 256..320, centre at 288.
        // Distance = 288, condition: dist >= threshold → 288 >= 288 → rendered (boundary).
        long boundaryKey = SectionKey.encode(2, 4, 0);
        // Boundary is inclusive
        assertTrue(LodRenderer.shouldRender(boundaryKey, CAM_X, CAM_Z, VANILLA_RADIUS),
                "Section exactly at cull threshold must be rendered (inclusive boundary)");

        // Section at (3, 0): centre at 224 → 224 < 288 → culled
        long insideKey = SectionKey.encode(2, 3, 0);
        assertFalse(LodRenderer.shouldRender(insideKey, CAM_X, CAM_Z, VANILLA_RADIUS),
                "Section inside cull threshold must NOT be rendered");
    }

    @Test
    void higherLodLevelsHaveLargerCullThreshold() {
        // LOD 1: threshold = 256 + 16 = 272
        // LOD 2: threshold = 256 + 32 = 288
        // LOD 3: threshold = 256 + 64 = 320
        // A section placed at world distance 300 should be rendered for LOD-1 and LOD-2
        // but the section SIZE at LOD-3 means even nearby sections are kept.
        // Key insight: higher LOD sections are bigger, so the half-section hysteresis grows.

        // LOD-1 section 10 cells away from camera (world: 10*32=320, centre 336) — rendered
        long lod1Far = SectionKey.encode(1, 10, 0);
        assertTrue(LodRenderer.shouldRender(lod1Far, CAM_X, CAM_Z, VANILLA_RADIUS));

        // LOD-3 section (size=128) at position (3,0): world 384..512, centre 448 — rendered
        long lod3Far = SectionKey.encode(3, 3, 0);
        assertTrue(LodRenderer.shouldRender(lod3Far, CAM_X, CAM_Z, VANILLA_RADIUS));
    }

    // -------------------------------------------------------------------------
    // Camera offset
    // -------------------------------------------------------------------------

    @Test
    void cullThresholdIsRelativeToCamera() {
        // Move camera to (1000, 0).  A LOD-1 section at world position 0 should now
        // be far from camera (distance ≈ 1000) and must be rendered.
        long key = SectionKey.encode(1, 0, 0); // centre at world (16, 16)
        assertTrue(LodRenderer.shouldRender(key, 1000.0, 0.0, VANILLA_RADIUS),
                "Section far from displaced camera must be rendered");
    }

    @Test
    void sectionNearCameraWithOffsetIsCulled() {
        // Camera at (1000, 0). Section at sectionX=62 → world 62*32=1984..2016, centre=2000.
        // But section at sectionX=31 → world 992..1024, centre=1008, dist=8 → culled.
        long key = SectionKey.encode(1, 31, 0);
        assertFalse(LodRenderer.shouldRender(key, 1000.0, 0.0, VANILLA_RADIUS),
                "Section very near displaced camera must be culled");
    }

    // -------------------------------------------------------------------------
    // All LOD levels 1-7 must be renderable when far enough away
    // -------------------------------------------------------------------------

    @Test
    void allLodLevels1To7AreRenderableWhenFarEnough() {
        for (int lod = 1; lod <= 7; lod++) {
            int cellSize   = 1 << lod;
            int sectionW   = VoxyConstants.SECTION_SIZE * cellSize;
            float threshold = VANILLA_RADIUS + sectionW * 0.5f;
            // Place section so its centre is well beyond the threshold
            // sectionX=10 → worldX = 10 * sectionW, centre at 10*sectionW + sectionW/2
            double centreX = 10.0 * sectionW + sectionW * 0.5;
            assertTrue(centreX > threshold,
                    "Test setup: centre must be beyond threshold for LOD " + lod);

            long key = SectionKey.encode(lod, 10, 0);
            assertTrue(LodRenderer.shouldRender(key, CAM_X, CAM_Z, VANILLA_RADIUS),
                    "LOD-" + lod + " section far from camera must be rendered");
        }
    }
}
