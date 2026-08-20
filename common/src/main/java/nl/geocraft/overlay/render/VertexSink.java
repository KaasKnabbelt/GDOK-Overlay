package nl.geocraft.overlay.render;

/**
 * Minimal vertex output used by {@link BakedOverlayMesh#replaySlabs} and
 * {@link BakedOverlayMesh#replayBorders}. Each Minecraft module provides one
 * reusable implementation that forwards to the game's {@code VertexConsumer}
 * and keeps the per-frame constants (matrix, colour, light, overlay UV) as fields,
 * so replaying a mesh allocates nothing.
 *
 * <p>Vertices arrive in quads (groups of four, counter-clockwise when seen from outside).
 * Coordinates are already translated relative to the camera.</p>
 */
public interface VertexSink {

    int FACE_UP = 0;
    /** -Z */
    int FACE_NORTH = 1;
    /** +Z */
    int FACE_SOUTH = 2;
    /** -X */
    int FACE_WEST = 3;
    /** +X */
    int FACE_EAST = 4;

    /** Textured vertex of a slab face. {@code face} is one of the {@code FACE_*} constants (drives the normal). */
    void vertex(float x, float y, float z, float u, float v, int face);

    /** Untextured, flat-coloured vertex (the border quads on the debug layer). */
    void flatVertex(float x, float y, float z);
}
