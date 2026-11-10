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
 *       puts that block in your hand, right-click puts it on the spot.</li>
 *   <li><b>Placed</b>: a thin green frame on top of the real marker block.</li>
 *   <li><b>Blocked</b>: an orange see-through box on the spot.</li>
 *   <li><b>Foreign</b> (after a {@link ForeignCheck}): a red box around a marker block in the
 *       area that is not part of the plan; geotools would make a tree of it too.</li>
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

    /** Flat-colour pass (debug quads): frames of placed markers, blocked boxes, foreign marker blocks. */
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
                    if (!s.isPlaced() && s != MarkerStatus.BLOCKED) continue;
                    MarkerPlan.Marker m = l.plan.markers()[i];
                    float x = (float) (m.x() - camX), y = (float) (l.y(i) - camY), z = (float) (m.z() - camZ);
                    if (s.isPlaced()) {
                        sink.setColor(85, 255, 85, alpha);
                        topFrame(sink, x, y + 1f + OverlayGeometry.Y_OFFSET * 2, z);
                    } else {
                        sink.setColor(255, 150, 0, boxAlpha);
                        box(sink, x + 0.05f, y + 0.05f, z + 0.05f, 0.9f);
                    }
                }
            }
            int foreign = l.foreignPositions();
            if (foreign > 0) {
                sink.setColor(255, 40, 40, Math.max(boxAlpha, 110));
                for (int k = 0; k < foreign; k++) {
                    long p = l.foreignPos(k);
                    int sx = MarkerLayer.unpackX(p), sy = MarkerLayer.unpackY(p), sz = MarkerLayer.unpackZ(p);
                    double dx = sx - camX, dz = sz - camZ;
                    if (dx * dx + dz * dz > cullSq) continue;
                    box(sink, (float) (sx - camX) - 0.03f, (float) (sy - camY) - 0.03f, (float) (sz - camZ) - 0.03f, 1.06f);
                }
            }
        }
    }

    // -- Through blocks (BOMEN.md §9.8) ---------------------------------------------------
    // Drawn without a depth test (the see-through text layers of vanilla, which name tags use),
    // and only for markers still to put down (UNKNOWN, EMPTY). Their shaders drop anything under
    // 10% alpha, so every alpha here stays above 26. Chosen by Jens on 31 October 2026 (A2): a
    // yellow frame; a see-through ghost (A1) and beacons on every marker were the
    // other variants.

    private static final int SEE_ALPHA_NEAR = 170, SEE_ALPHA_FAR = 70;
    private static final int FRAME_R = 255, FRAME_G = 214, FRAME_B = 51;
    private static final float BEACON_HALF = 0.12f, BEACON_HEIGHT = 16f;
    /** A beacon fades in between these distances: close by you see the marker itself. */
    private static final double BEACON_FADE_FROM = 8, BEACON_FADE_TO = 24;

    /**
     * Flat colour through blocks: a yellow frame on the faces of every spot still to fill within
     * {@link MarkerAssist#SEE_THROUGH_RANGE}, fading out with distance; and for a plan with
     * {@link MarkerAssist#BEACON_MAX_LEFT} or fewer left, a beacon on each of those: a thin
     * column {@code BEACON_HEIGHT} blocks up from the top of the spot, within view distance,
     * fading in from {@code BEACON_FADE_FROM}. Returns the vertex count.
     */
    public static int emitSeeThrough(List<MarkerLayer> layers, VertexSink sink, ViewFrustum frustum,
                                     double camX, double camY, double camZ, double maxDist) {
        int vertices = 0;
        double frameRange = MarkerAssist.SEE_THROUGH_RANGE;
        for (MarkerLayer l : layers) {
            if (l.isHidden()) continue;
            boolean beacons = MarkerAssist.beacons(l);
            double range = beacons ? Math.max(maxDist, frameRange) : frameRange;
            double cullSq = square(range + OverlayGeometry.BUCKET_CULL_MARGIN);
            for (int b = 0; b < l.bucketCount(); b++) {
                if (!bucketVisible(l, b, frustum, camX, camZ, cullSq)) continue;
                for (int k = l.bucketStart(b); k < l.bucketEnd(b); k++) {
                    int i = l.markerAt(k);
                    if (!l.status(i).needsMarker()) continue;
                    MarkerPlan.Marker m = l.plan.markers()[i];
                    double d = distance(m, l.y(i), camX, camY, camZ);
                    if (d > range) continue;
                    float x = (float) (m.x() - camX), y = (float) (l.y(i) - camY), z = (float) (m.z() - camZ);
                    if (d <= frameRange) {
                        sink.setColor(FRAME_R, FRAME_G, FRAME_B, Math.min(230, fade(d, frameRange) + 60));
                        frames(sink, x, y + OverlayGeometry.Y_OFFSET, z);
                        vertices += 80;
                    }
                    if (beacons) {
                        double fadeIn = Math.max(0, Math.min(1, (d - BEACON_FADE_FROM) / (BEACON_FADE_TO - BEACON_FADE_FROM)));
                        int bottom = (int) Math.round(190 * fadeIn);
                        if (bottom >= 40) {
                            beacon(sink, x + 0.5f, y + 1f, z + 0.5f, bottom);
                            vertices += 16;
                        }
                    }
                }
            }
        }
        return vertices;
    }

    private static int fade(double d, double range) {
        double t = Math.max(0, Math.min(1, d / range));
        return (int) Math.round(SEE_ALPHA_NEAR + (SEE_ALPHA_FAR - SEE_ALPHA_NEAR) * t);
    }

    private static double distance(MarkerPlan.Marker m, int y, double camX, double camY, double camZ) {
        double dx = m.x() + 0.5 - camX, dy = y + 0.5 - camY, dz = m.z() + 0.5 - camZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A thin frame along the edges of the five faces of a ghost (no bottom). */
    private static void frames(VertexSink s, float x, float y, float z) {
        float x1 = x + 1, y1 = y + 1, z1 = z + 1, w = 0.08f;
        // Corners per face in the order of ghost(): p0, p1, (p2), p3.
        faceFrame(s, x, y1, z, x, y1, z1, x1, y1, z, w);       // up
        faceFrame(s, x, y1, z, x1, y1, z, x, y, z, w);         // north
        faceFrame(s, x1, y1, z1, x, y1, z1, x1, y, z1, w);     // south
        faceFrame(s, x, y1, z1, x, y1, z, x, y, z1, w);        // west
        faceFrame(s, x1, y1, z, x1, y1, z1, x1, y, z, w);      // east
    }

    /**
     * Four strips along the border of the face p0 p1 p2 p3 (p2 = p1 + p3 - p0), with the same
     * winding, so face culling keeps them on the outside.
     */
    private static void faceFrame(VertexSink s, float ax, float ay, float az, float bx, float by, float bz,
                                  float dx, float dy, float dz, float w) {
        float ux = bx - ax, uy = by - ay, uz = bz - az; // p0 -> p1
        float vx = dx - ax, vy = dy - ay, vz = dz - az; // p0 -> p3
        strip(s, ax, ay, az, ux, uy, uz, vx, vy, vz, 0, 1, 0, w);
        strip(s, ax, ay, az, ux, uy, uz, vx, vy, vz, 0, 1, 1 - w, 1);
        strip(s, ax, ay, az, ux, uy, uz, vx, vy, vz, 0, w, w, 1 - w);
        strip(s, ax, ay, az, ux, uy, uz, vx, vy, vz, 1 - w, 1, w, 1 - w);
    }

    private static void strip(VertexSink s, float ax, float ay, float az, float ux, float uy, float uz,
                              float vx, float vy, float vz, float s0, float s1, float t0, float t1) {
        s.flatVertex(ax + ux * s0 + vx * t0, ay + uy * s0 + vy * t0, az + uz * s0 + vz * t0);
        s.flatVertex(ax + ux * s1 + vx * t0, ay + uy * s1 + vy * t0, az + uz * s1 + vz * t0);
        s.flatVertex(ax + ux * s1 + vx * t1, ay + uy * s1 + vy * t1, az + uz * s1 + vz * t1);
        s.flatVertex(ax + ux * s0 + vx * t1, ay + uy * s0 + vy * t1, az + uz * s0 + vz * t1);
    }

    /** A column of four outward faces, {@code bottom} alpha at the foot and fading out at the top. */
    private static void beacon(VertexSink s, float cx, float y0, float cz, int bottom) {
        float x0 = cx - BEACON_HALF, x1 = cx + BEACON_HALF, z0 = cz - BEACON_HALF, z1 = cz + BEACON_HALF;
        float y1 = y0 + BEACON_HEIGHT;
        int r = FRAME_R, g = FRAME_G, b = FRAME_B, top = 28;
        // north, south, west, east: top two vertices first, as in ghost()
        s.setColor(r, g, b, top); s.flatVertex(x0, y1, z0); s.flatVertex(x1, y1, z0);
        s.setColor(r, g, b, bottom); s.flatVertex(x1, y0, z0); s.flatVertex(x0, y0, z0);
        s.setColor(r, g, b, top); s.flatVertex(x1, y1, z1); s.flatVertex(x0, y1, z1);
        s.setColor(r, g, b, bottom); s.flatVertex(x0, y0, z1); s.flatVertex(x1, y0, z1);
        s.setColor(r, g, b, top); s.flatVertex(x0, y1, z1); s.flatVertex(x0, y1, z0);
        s.setColor(r, g, b, bottom); s.flatVertex(x0, y0, z0); s.flatVertex(x0, y0, z1);
        s.setColor(r, g, b, top); s.flatVertex(x1, y1, z0); s.flatVertex(x1, y1, z1);
        s.setColor(r, g, b, bottom); s.flatVertex(x1, y0, z1); s.flatVertex(x1, y0, z0);
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
