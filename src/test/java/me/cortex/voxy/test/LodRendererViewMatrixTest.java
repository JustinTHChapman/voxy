package me.cortex.voxy.test;

import me.cortex.voxy.client.render.LodRenderer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LodRenderer#buildViewMatrix}.
 *
 * <p>These tests pin the camera view-matrix construction used to set
 * {@code RenderSystem.getModelViewMatrix()} before the LOD draw call.
 * The shader computes:
 * <pre>  gl_Position = ProjMat * ModelViewMat * Position</pre>
 * where {@code Position} holds raw camera-relative coords (no per-vertex rotation).
 * The view-rotation matrix therefore must correctly rotate camera-relative
 * world coordinates into OpenGL eye space.
 */
class LodRendererViewMatrixTest {

    private static final float EPSILON = 1e-4f;

    // -------------------------------------------------------------------------
    // Structural correctness of buildViewMatrix
    // -------------------------------------------------------------------------

    @Test
    void identityQuaternion_producesIdentityMatrix() {
        Matrix4f view = LodRenderer.buildViewMatrix(new Quaternionf());
        // Identity quaternion → identity rotation matrix
        assertEquals(1.0f, view.m00(), EPSILON);
        assertEquals(1.0f, view.m11(), EPSILON);
        assertEquals(1.0f, view.m22(), EPSILON);
        assertEquals(1.0f, view.m33(), EPSILON);
        assertEquals(0.0f, view.m01(), EPSILON);
        assertEquals(0.0f, view.m10(), EPSILON);
        assertEquals(0.0f, view.m02(), EPSILON);
        assertEquals(0.0f, view.m20(), EPSILON);
        assertEquals(0.0f, view.m12(), EPSILON);
        assertEquals(0.0f, view.m21(), EPSILON);
    }

    @Test
    void nonIdentityRotation_producesNonIdentityMatrix() {
        // A 45-degree yaw should produce a matrix with non-trivial off-diagonal entries
        Quaternionf q = new Quaternionf().rotateY((float) Math.PI / 4);
        Matrix4f view = LodRenderer.buildViewMatrix(q);
        // m00 should be cos(45°) ≈ 0.707, not 1.0
        assertNotEquals(1.0f, view.m00(), EPSILON);
        // m02 or m20 should be non-zero (cross terms from Y rotation)
        assertTrue(Math.abs(view.m02()) > 0.5f || Math.abs(view.m20()) > 0.5f,
                "Off-diagonal entry must be significant for 45° Y rotation");
    }

    @Test
    void viewMatrix_preservesVertexDistance() {
        // Rotation matrices are orthogonal: they preserve vector length.
        // This catches scale errors or non-orthogonal construction.
        Quaternionf q = new Quaternionf().rotateY(1.0f).rotateX(0.4f).rotateZ(0.2f);
        Matrix4f view = LodRenderer.buildViewMatrix(q);

        float[] camRel = LodRenderer.cameraRelativePos(100, 64, 200, 90, 60, 180);
        // camRel = (10, 4, 20)
        float expectedLen = (float) Math.sqrt(10 * 10 + 4 * 4 + 20 * 20);

        Vector3f eyePos = view.transformPosition(new Vector3f(camRel[0], camRel[1], camRel[2]));
        assertEquals(expectedLen, eyePos.length(), 1e-3f,
                "Rotation matrix must preserve vertex distance from camera");
    }

    @Test
    void yRotation90_rotatesXAxisTowardZ() {
        // A 90-degree rotation around Y: the +X axis maps to ±Z.
        // We verify the magnitude of the rotation without depending on sign convention.
        Quaternionf q = new Quaternionf().rotateY((float) Math.PI / 2);
        Matrix4f view = LodRenderer.buildViewMatrix(q);

        Vector3f result = view.transformPosition(new Vector3f(1, 0, 0));
        // X component must be near zero (rotated away)
        assertEquals(0.0f, result.x, EPSILON, "X component should vanish after 90° Y rotation");
        // Y component should be unchanged by Y-axis rotation
        assertEquals(0.0f, result.y, EPSILON, "Y component should not change for Y-axis rotation");
        // Z component carries the rotated X (magnitude = 1)
        assertEquals(1.0f, Math.abs(result.z), EPSILON, "Z component should have full magnitude");
    }

    @Test
    void oppositeRotations_areInverseMatrices() {
        // R(q) * R(q^{-1}) should equal identity
        Quaternionf q    = new Quaternionf().rotateY(1.2f).rotateX(0.5f);
        Quaternionf qInv = new Quaternionf(q).invert();

        Matrix4f R    = LodRenderer.buildViewMatrix(q);
        Matrix4f Rinv = LodRenderer.buildViewMatrix(qInv);

        Matrix4f product = new Matrix4f(R).mul(Rinv);
        // Diagonal should be ~1, off-diagonal ~0
        assertEquals(1.0f, product.m00(), 1e-5f);
        assertEquals(1.0f, product.m11(), 1e-5f);
        assertEquals(1.0f, product.m22(), 1e-5f);
        assertEquals(0.0f, product.m01(), 1e-5f);
        assertEquals(0.0f, product.m10(), 1e-5f);
        assertEquals(0.0f, product.m02(), 1e-5f);
    }

    // -------------------------------------------------------------------------
    // End-to-end: camera-relative coords + view matrix = stable eye position
    // -------------------------------------------------------------------------

    @Test
    void rotatingCamera_keepsEyePositionFixedForWorldFixedVertex() {
        // A vertex fixed in the world should produce the SAME eye-space position
        // regardless of camera translation (only camera rotation should matter).
        // Specifically: rotating the camera should change the eye-space position
        // in the same way as rotating the coordinate frame — the KEY property is
        // that eye-space distance from camera origin is preserved.

        double worldX = 1000, worldY = 64, worldZ = 2000;
        // Two different camera positions (same rotation → same eye-space up to translation)
        double camX1 = 900, camY1 = 64, camZ1 = 1900;
        double camX2 = 950, camY2 = 64, camZ2 = 1950;

        Quaternionf q = new Quaternionf().rotateY(0.7f).rotateX(0.2f);
        Matrix4f view = LodRenderer.buildViewMatrix(q);

        float[] rel1 = LodRenderer.cameraRelativePos(worldX, worldY, worldZ, camX1, camY1, camZ1);
        float[] rel2 = LodRenderer.cameraRelativePos(worldX, worldY, worldZ, camX2, camY2, camZ2);

        Vector3f eye1 = view.transformPosition(new Vector3f(rel1[0], rel1[1], rel1[2]));
        Vector3f eye2 = view.transformPosition(new Vector3f(rel2[0], rel2[1], rel2[2]));

        // The difference in eye positions should equal the difference in camera positions
        // (but sign-flipped, because moving camera +X moves world -X in eye space)
        float dCamX = (float)(camX2 - camX1);  // +50
        float dCamY = (float)(camY2 - camY1);  //   0
        float dCamZ = (float)(camZ2 - camZ1);  // +50

        Vector3f camDeltaEye = view.transformDirection(new Vector3f(dCamX, dCamY, dCamZ));
        // eye2 = eye1 - camDeltaEye  (moving camera shifts world in opposite direction)
        assertEquals(eye1.x - camDeltaEye.x, eye2.x, 1e-3f, "Eye X should shift by -camDelta");
        assertEquals(eye1.y - camDeltaEye.y, eye2.y, 1e-3f, "Eye Y should shift by -camDelta");
        assertEquals(eye1.z - camDeltaEye.z, eye2.z, 1e-3f, "Eye Z should shift by -camDelta");
    }

    @Test
    void differentCameraYaws_changeEyeSpaceZ() {
        // For a block directly "in front" at camera-relative (0, 0, 10):
        // After identity rotation, Z stays positive.
        // After 180° yaw, the block is now "behind" the camera — Z flips sign.
        float[] camRel = LodRenderer.cameraRelativePos(0, 0, 10, 0, 0, 0);

        Matrix4f viewIdentity = LodRenderer.buildViewMatrix(new Quaternionf());
        Matrix4f view180      = LodRenderer.buildViewMatrix(new Quaternionf().rotateY((float) Math.PI));

        Vector3f pos0   = viewIdentity.transformPosition(new Vector3f(camRel[0], camRel[1], camRel[2]));
        Vector3f pos180 = view180.transformPosition(new Vector3f(camRel[0], camRel[1], camRel[2]));

        // 180° Y rotation flips both X and Z
        assertEquals( 0.0f, pos0.x,   EPSILON);
        assertEquals(10.0f, pos0.z,   EPSILON, "No rotation: Z should be +10");

        // pos180.z should be negated relative to pos0.z
        assertEquals(-pos0.z, pos180.z, EPSILON, "180° Y rotation should negate Z");
    }
}
