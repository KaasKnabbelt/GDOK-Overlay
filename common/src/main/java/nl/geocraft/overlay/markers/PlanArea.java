package nl.geocraft.overlay.markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The area of a plan as CVDK selects it for geotools, and the geometry around it.
 *
 * <p>The site offers the selection for a plan as text ({@code resources/js/gdok/bomen/commandos.js}):
 * {@code //sel poly} over the polygon simplified to at most {@link #MAX_POINTS} corners and pushed
 * {@link #OUTWARD} m outwards, so markers on the edge are surely inside. {@link ForeignCheck}
 * checks that same ring plus a metre; keep the numbers in step with commandos.js.</p>
 *
 * <p>Works in the plan's Minecraft space ({@code [x, z]} with {@code z = -rdY}); the site works
 * in RD. Simplifying and offsetting commute with that mirror, so both produce the same ring.</p>
 */
public final class PlanArea {

    public static final int MAX_POINTS = 16;
    public static final double OUTWARD = 2.0;

    private PlanArea() {}

    /** The simplified ring, pushed {@code outward} metres out: what the selection covers. */
    public static double[][] selectionRing(double[][] polygon, double outward) {
        return outward(simplify(polygon, MAX_POINTS), outward);
    }

    /** Ray casting on a point (block centres: {@code x + 0.5}). */
    public static boolean contains(double[][] ring, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], zi = ring[i][1], xj = ring[j][0], zj = ring[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside;
        }
        return inside;
    }

    /** Distance from a point to the polygon: 0 inside, else to the nearest edge. For "/gdok paste". */
    public static double distance(double[][] polygon, double x, double z) {
        if (contains(polygon, x, z)) return 0;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            best = Math.min(best, distanceToSegment(new double[]{x, z}, polygon[j], polygon[i]));
        }
        return best;
    }

    /** Simplify a closed ring to at most {@code max} points: Douglas-Peucker with a doubling tolerance. */
    static double[][] simplify(double[][] ring, int max) {
        if (ring.length <= max) return ring;
        double[][] closed = Arrays.copyOf(ring, ring.length + 1);
        closed[ring.length] = ring[0];
        for (double eps = 0.5; eps < 1e5; eps *= 2) {
            List<double[]> open = dp(Arrays.asList(closed), eps);
            open = open.subList(0, open.size() - 1);
            if (open.size() <= max && open.size() >= 3) return open.toArray(new double[0][]);
        }
        return Arrays.copyOf(ring, max);
    }

    private static List<double[]> dp(List<double[]> pts, double eps) {
        if (pts.size() < 3) return pts;
        double max = 0;
        int idx = 0;
        double[] a = pts.get(0), b = pts.get(pts.size() - 1);
        for (int i = 1; i < pts.size() - 1; i++) {
            double d = distanceToSegment(pts.get(i), a, b);
            if (d > max) {
                max = d;
                idx = i;
            }
        }
        if (max <= eps) return List.of(a, b);
        List<double[]> left = dp(pts.subList(0, idx + 1), eps);
        List<double[]> right = dp(pts.subList(idx, pts.size()), eps);
        List<double[]> out = new ArrayList<>(left.subList(0, left.size() - 1));
        out.addAll(right);
        return out;
    }

    private static double distanceToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
    }

    /** Every corner {@code d} metres outwards along the bisector of its two edges. */
    static double[][] outward(double[][] ring, double d) {
        double area2 = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            area2 += (ring[j][0] - ring[i][0]) * (ring[j][1] + ring[i][1]);
        }
        boolean ccw = area2 > 0;
        int n = ring.length;
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] prev = ring[(i - 1 + n) % n], p = ring[i], next = ring[(i + 1) % n];
            double[] n1 = normal(prev, p, ccw), n2 = normal(p, next, ccw);
            double bx = n1[0] + n2[0], by = n1[1] + n2[1];
            double l = Math.hypot(bx, by);
            if (l == 0) l = 1;
            bx /= l;
            by /= l;
            // Along the bisector d / cos(half angle) is needed; capped at sharp corners.
            double cos = Math.max(0.35, bx * n1[0] + by * n1[1]);
            out[i] = new double[]{p[0] + bx * d / cos, p[1] + by * d / cos};
        }
        return out;
    }

    private static double[] normal(double[] a, double[] b, boolean ccw) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double l = Math.hypot(dx, dy);
        if (l == 0) l = 1;
        // Outwards: right of the direction of travel for a counter-clockwise ring.
        return ccw ? new double[]{dy / l, -dx / l} : new double[]{-dy / l, dx / l};
    }
}
