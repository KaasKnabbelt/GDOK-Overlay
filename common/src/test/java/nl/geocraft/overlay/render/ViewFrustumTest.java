package nl.geocraft.overlay.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFrustumTest {

    private static ViewFrustum identity(double fov, double aspect) {
        ViewFrustum f = new ViewFrustum();
        f.set(0, 0, 0, 0, 0, fov, aspect); // yaw 0, pitch 0: forward +z, up +y, left +x
        return f;
    }

    @Test
    void unsetFrustumAcceptsEverything() {
        ViewFrustum f = new ViewFrustum();
        assertTrue(f.intersectsSphere(0, 0, -100, 1));
    }

    @Test
    void sphereInFrontIsVisibleAndBehindIsNot() {
        ViewFrustum f = identity(70, 16.0 / 9);
        assertTrue(f.intersectsSphere(0, 0, 50, 1));
        assertFalse(f.intersectsSphere(0, 0, -50, 1));
        assertFalse(f.intersectsSphere(200, 0, 10, 5)); // far to the side
        assertFalse(f.intersectsSphere(0, 200, 10, 5)); // far above
    }

    @Test
    void marginKeepsEdgeSpheresVisible() {
        ViewFrustum f = identity(70, 16.0 / 9);
        // Exactly on the nominal vertical edge at distance 100: tan(35°)*100 ≈ 70
        assertTrue(f.intersectsSphere(0, 70, 100, 0.1));
        // Well outside the widened edge (1.25 margin → ~87.5) plus radius
        assertFalse(f.intersectsSphere(0, 100, 100, 0.1));
        // Big sphere straddling the edge still counts
        assertTrue(f.intersectsSphere(0, 100, 100, 20));
    }

    @Test
    void rotatedCameraLooksDown() {
        ViewFrustum f = new ViewFrustum();
        f.set(0, 50, 0, 0, 90, 70, 1.5); // pitch 90 = straight down
        assertTrue(f.intersectsSphere(0, 0, 0, 1));
        assertFalse(f.intersectsSphere(0, 100, 0, 1));
        assertFalse(f.intersectsSphere(0, 50, 100, 1)); // horizontal, out of the downward cone
    }

    @Test
    void yawConventionMatchesMinecraft() {
        ViewFrustum f = new ViewFrustum();
        f.set(0, 0, 0, 90, 0, 70, 1.5); // yaw 90 looks toward -x (west)
        assertTrue(f.intersectsSphere(-50, 0, 0, 1));
        assertFalse(f.intersectsSphere(50, 0, 0, 1));
        assertFalse(f.intersectsSphere(0, 0, 50, 1));
        f.set(0, 0, 0, 180, 0, 70, 1.5); // yaw 180 looks toward -z
        assertTrue(f.intersectsSphere(0, 0, -50, 1));
        assertFalse(f.intersectsSphere(0, 0, 50, 1));
    }
}
