package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.render.OverlayGeometry;
import nl.geocraft.overlay.render.VertexSink;
import nl.geocraft.overlay.render.ViewFrustum;

import java.util.List;

import static nl.geocraft.overlay.render.VertexSink.FACE_EAST;
import static nl.geocraft.overlay.render.VertexSink.FACE_NORTH;
import static nl.geocraft.overlay.render.VertexSink.FACE_SOUTH;
import static nl.geocraft.overlay.render.VertexSink.FACE_UP;
import static nl.geocraft.overlay.render.VertexSink.FACE_WEST;

/**
 * Draws the marker layers into a {@link VertexSink}; the modules only supply the buffers.
 *
 * <ul>
 *   <li><b>Ghost</b> (a spot that still needs its marker, {@link MarkerStatus#needsMarker}): a
 *       full block with the marker's texture, see-through like the overlays. Middle-click on it
 *       puts that block in your hand.</li>
 *   <li><b>Placed</b>: a thin green frame on top of the real marker block.</li>
 *   <li><b>Blocked</b>: an orange see-through box on the spot.</li>
 *   <li><b>Stray</b> (after a plant check): a red box around a marker block outside the plan.</li>
 *   <li>A tree on the spot (existing or planted): nothing.</li>
 * </ul>
 *
 * <p>Unlike the overlays nothing is baked: a plan has at most a few thousand markers and only
 * the buckets within view distance and inside the frustum are visited. A ghost is five quads
 * (no bottom face: it sits on the ground).</p>
 */
public final class MarkerRenderCore {

    /** Sprite UVs {u0, u1, v0, v1} for a block name (module-specific, see MeshCache.UvLookup). */
    @FunctionalInterface
    public interface UvLookup {
        float[] uv(String block);
    }

    private static final float FRAME = 0.07f;
    /** A bucket's markers vary in height; the frustum sphere gets extra room for that. */
    private static final double BUCKET_SPHERE = ViewFrustum.BUCKET_RADIUS + 8.0;

    private MarkerRenderCore() {}

    /** Forget all cached sprite UVs (after a resource reload the atlas may have moved). */
    public static void invalidateUvs(List<MarkerLayer> layers) {
        for (MarkerLayer l : layers) l.uvCache = null;
    }

    /** Textured pass (entity-translucent on the block atlas): the ghosts. Returns the vertex count. */
    public static int emitGhosts(List<MarkerLayer> layers, VertexSink sink, ViewFrustum frustum,
                                 double camX, double camY, double camZ, double maxDist, int alpha, UvLookup uvs) {
        int vertices = 0;
        sink.setColor(255, 255, 255, alpha);
        double cullSq = square(maxDist + OverlayGeometry.BUCKET_CULL_MARGIN);
        for (MarkerLayer l : layers) {
            if (l.isHidden()) continue;
            if (l.uvCache == null) l.uvCache = new float[l.plan.blocks().size()][];
            for (int b = 0; b < l.bucketCount(); b++) {
                if (!bucketVisible(l, b, frustum, camX, camZ, cullSq)) continue;
                for (int k = l.bucketStart(b); k < l.bucketEnd(b); k++) {
                    int i = l.markerAt(k);
                    if (!l.status(i).needsMarker()) continue;
                    MarkerPlan.Marker m = l.plan.markers()[i];
                    float[] uv = l.uvCache[m.block()];
                    if (uv == null) uv = l.uvCache[m.block()] = uvs.uv(l.plan.blockName(m));
                    ghost(sink, (float) (m.x() - camX), (float) (l.y(i) - camY) + OverlayGeometry.Y_OFFSET,
                            (float) (m.z() - camZ), uv);
                    vertices += 20;
                }
            }
        }
        return vertices;
    }

    /** Flat-colour pass (debug quads): frames of placed markers, blocked boxes, strays. */
    public static void emitFrames(List<MarkerLayer> layers, VertexSink sink, ViewFrustum frustum,
                                  double camX, double camY, double camZ, double maxDist, int alpha) {
        double cullSq = square(maxDist + OverlayGeometry.BUCKET_CULL_MARGIN);
        int boxAlpha = Math.max(1, alpha * 2 / 5);
        for (MarkerLayer l : layers) {
            if (l.isHidden()) continue;
            for (int b = 0; b < l.bucketCount(); b++) {
                if (!bucketVisible(l, b, frustum, camX, camZ, cullSq)) continue;
                for (int k = l.bucketStart(b); k < l.bucketEnd(b); k++) {
                    int i = l.markerAt(k);
                    MarkerStatus s = l.status(i);
                    if (s != MarkerStatus.PLACED && s != MarkerStatus.BLOCKED) continue;
                    MarkerPlan.Marker m = l.plan.markers()[i];
                    float x = (float) (m.x() - camX), y = (float) (l.y(i) - camY), z = (float) (m.z() - camZ);
                    if (s == MarkerStatus.PLACED) {
                        sink.setColor(85, 255, 85, alpha);
                        topFrame(sink, x, y + 1f + OverlayGeometry.Y_OFFSET * 2, z);
                    } else {
                        sink.setColor(255, 150, 0, boxAlpha);
                        box(sink, x + 0.05f, y + 0.05f, z + 0.05f, 0.9f);
                    }
                }
            }
            long[] strays = l.strays();
            if (strays.length > 0) {
                sink.setColor(255, 40, 40, Math.max(boxAlpha, 110));
                for (long p : strays) {
                    int sx = MarkerLayer.unpackX(p), sy = MarkerLayer.unpackY(p), sz = MarkerLayer.unpackZ(p);
                    double dx = sx - camX, dz = sz - camZ;
                    if (dx * dx + dz * dz > cullSq) continue;
                    box(sink, (float) (sx - camX) - 0.03f, (float) (sy - camY) - 0.03f, (float) (sz - camZ) - 0.03f, 1.06f);
                }
            }
        }
    }

    private static boolean bucketVisible(MarkerLayer l, int b, ViewFrustum frustum, double camX, double camZ, double cullSq) {
        double dx = l.bucketCenterX(b) - camX, dz = l.bucketCenterZ(b) - camZ;
        if (dx * dx + dz * dz > cullSq) return false;
        if (frustum == null) return true;
        double sy = l.y(l.markerAt(l.bucketStart(b))) + 0.5;
        return frustum.intersectsSphere(l.bucketCenterX(b), sy, l.bucketCenterZ(b), BUCKET_SPHERE);
    }

    /** Five textured faces of a unit block at (x, y, z), camera-relative; counter-clockwise from outside. */
    private static void ghost(VertexSink s, float x, float y, float z, float[] uv) {
        float u0 = uv[0], u1 = uv[1], v0 = uv[2], v1 = uv[3];
        float y1 = y + 1;
        s.vertex(x, y1, z, u0, v0, FACE_UP);
        s.vertex(x, y1, z + 1, u0, v1, FACE_UP);
        s.vertex(x + 1, y1, z + 1, u1, v1, FACE_UP);
        s.vertex(x + 1, y1, z, u1, v0, FACE_UP);

        s.vertex(x, y1, z, u1, v0, FACE_NORTH);
        s.vertex(x + 1, y1, z, u0, v0, FACE_NORTH);
        s.vertex(x + 1, y, z, u0, v1, FACE_NORTH);
        s.vertex(x, y, z, u1, v1, FACE_NORTH);

        s.vertex(x + 1, y1, z + 1, u1, v0, FACE_SOUTH);
        s.vertex(x, y1, z + 1, u0, v0, FACE_SOUTH);
        s.vertex(x, y, z + 1, u0, v1, FACE_SOUTH);
        s.vertex(x + 1, y, z + 1, u1, v1, FACE_SOUTH);

        s.vertex(x, y1, z + 1, u1, v0, FACE_WEST);
        s.vertex(x, y1, z, u0, v0, FACE_WEST);
        s.vertex(x, y, z, u0, v1, FACE_WEST);
        s.vertex(x, y, z + 1, u1, v1, FACE_WEST);

        s.vertex(x + 1, y1, z, u1, v0, FACE_EAST);
        s.vertex(x + 1, y1, z + 1, u0, v0, FACE_EAST);
        s.vertex(x + 1, y, z + 1, u0, v1, FACE_EAST);
        s.vertex(x + 1, y, z, u1, v1, FACE_EAST);
    }

    /** Thin frame around the top of a block (like the overlay borders), at height {@code y}. */
    private static void topFrame(VertexSink s, float x, float y, float z) {
        float w = FRAME;
        quadUp(s, x, y, z, x + 1, z + w);
        quadUp(s, x, y, z + 1 - w, x + 1, z + 1);
        quadUp(s, x, y, z + w, x + w, z + 1 - w);
        quadUp(s, x + 1 - w, y, z + w, x + 1, z + 1 - w);
    }

    private static void quadUp(VertexSink s, float x0, float y, float z0, float x1, float z1) {
        s.flatVertex(x0, y, z0);
        s.flatVertex(x0, y, z1);
        s.flatVertex(x1, y, z1);
        s.flatVertex(x1, y, z0);
    }

    /** Flat-colour cube of edge {@code size} with its lowest corner at (x, y, z). */
    private static void box(VertexSink s, float x, float y, float z, float size) {
        float x1 = x + size, y1 = y + size, z1 = z + size;
        // up, down, north, south, west, east (counter-clockwise from outside)
        s.flatVertex(x, y1, z); s.flatVertex(x, y1, z1); s.flatVertex(x1, y1, z1); s.flatVertex(x1, y1, z);
        s.flatVertex(x, y, z); s.flatVertex(x1, y, z); s.flatVertex(x1, y, z1); s.flatVertex(x, y, z1);
        s.flatVertex(x, y1, z); s.flatVertex(x1, y1, z); s.flatVertex(x1, y, z); s.flatVertex(x, y, z);
        s.flatVertex(x1, y1, z1); s.flatVertex(x, y1, z1); s.flatVertex(x, y, z1); s.flatVertex(x1, y, z1);
        s.flatVertex(x, y1, z1); s.flatVertex(x, y1, z); s.flatVertex(x, y, z); s.flatVertex(x, y, z1);
        s.flatVertex(x1, y1, z); s.flatVertex(x1, y1, z1); s.flatVertex(x1, y, z1); s.flatVertex(x1, y, z);
    }

    private static double square(double d) {
        return d * d;
    }
}
