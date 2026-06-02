package me.cortex.voxy.test;

import me.cortex.voxy.client.render.LodRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link LodRenderer#cameraRelativePos}.
 *
 * <p>These tests pin the fix for the "geometry locked to camera" bug where
 * world-space coordinates were passed directly to the GPU instead of being
 * offset relative to the camera position.
 *
 * <p>The PoseStack at {@code AFTER_SOLID_BLOCKS} has the camera transform
 * baked in (camera = origin).  Every vertex must therefore be expressed as
 * {@code worldPos - cameraPos}, not as raw world coordinates.
 */
class LodRendererCoordTest {

    private static final float EPSILON = 1e-4f;

    // -------------------------------------------------------------------------
    // Basic correctness
    // -------------------------------------------------------------------------

    @Test
    void cameraAtOrigin_vertexEqualsWorldPos() {
        // When camera is at origin, camera-relative == world coords.
        float[] rel = LodRenderer.cameraRelativePos(100.0, 64.0, 200.0,
                                                    0.0,   0.0,  0.0);
        assertEquals(3, rel.length);
        assertEquals(100.0f, rel[0], EPSILON);
        assertEquals( 64.0f, rel[1], EPSILON);
        assertEquals(200.0f, rel[2], EPSILON);
    }

    @Test
    void cameraOffset_isSubtractedFromWorldPos() {
        // Vertex at world (100, 64, 200), camera at (90, 60, 180).
        // Expected camera-relative: (10, 4, 20).
        float[] rel = LodRenderer.cameraRelativePos(100.0, 64.0, 200.0,
                                                    90.0,  60.0, 180.0);
        assertEquals( 10.0f, rel[0], EPSILON);
        assertEquals(  4.0f, rel[1], EPSILON);
        assertEquals( 20.0f, rel[2], EPSILON);
    }

    @Test
    void vertexBehindCamera_isNegative() {
        // Vertex at world (50, 64, 50), camera at (100, 64, 100).
        // Geometry is "behind / to the side of" the camera.
        // Camera-relative coords must be negative, not world-positive.
        float[] rel = LodRenderer.cameraRelativePos(50.0, 64.0, 50.0,
                                                    100.0, 64.0, 100.0);
        assertEquals(-50.0f, rel[0], EPSILON);
        assertEquals(  0.0f, rel[1], EPSILON);
        assertEquals(-50.0f, rel[2], EPSILON);
    }

    // -------------------------------------------------------------------------
    // The original bug: world-space coords were used without cam subtraction
    // -------------------------------------------------------------------------

    @Test
    void worldSpaceCoords_wouldGiveWrongResult() {
        // This test explicitly documents the old (wrong) behaviour.
        // If cameraRelativePos were broken and returned raw world coords,
        // moving the camera would not change the output.
        double worldX = 500.0, worldY = 64.0, worldZ = 300.0;
        double cam1X  = 100.0, cam1Y  = 64.0, cam1Z  = 100.0;
        double cam2X  = 400.0, cam2Y  = 64.0, cam2Z  = 250.0;

        float[] rel1 = LodRenderer.cameraRelativePos(worldX, worldY, worldZ, cam1X, cam1Y, cam1Z);
        float[] rel2 = LodRenderer.cameraRelativePos(worldX, worldY, worldZ, cam2X, cam2Y, cam2Z);

        // Moving the camera must change the camera-relative vertex position.
        // (If the function returned world coords they'd be the same.)
        assertNotEquals(rel1[0], rel2[0], EPSILON,
                "X must differ when camera moves — returning world coords is the bug");
        assertNotEquals(rel1[2], rel2[2], EPSILON,
                "Z must differ when camera moves — returning world coords is the bug");
    }

    // -------------------------------------------------------------------------
    // Double-precision subtraction before float cast
    // -------------------------------------------------------------------------

    @Test
    void largeWorldCoords_retainPrecision() {
        // At far-from-origin coordinates, subtracting in double before casting to float
        // avoids precision loss.  If the subtraction were done in float, the small
        // difference between two large floats would lose mantissa bits.
        //
        // Camera at 30_000_000.5, vertex 1 block away at 30_000_001.5.
        // Expected camera-relative X = 1.0 exactly.
        double camX   = 30_000_000.5;
        double worldX = 30_000_001.5;

        float[] rel = LodRenderer.cameraRelativePos(worldX, 64.0, 0.0,
                                                    camX,   64.0, 0.0);
        // If subtraction were done in float first this would be ~0 or garbage.
        assertEquals(1.0f, rel[0], EPSILON,
                "Camera-relative X must be 1.0 at large world coordinates");
    }
}
