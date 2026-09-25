package nl.geocraft.overlay.markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The commands that turn a plan's markers into trees (BOMEN.md §9.5), in the order they are run.
 * The same sequence as {@code resources/js/gdok/bomen/commandos.js} on the site, which offers it
 * as text for whoever plants without the beta mod; keep the two in step.
 *
 * <ol>
 *   <li>Selection: {@code //sel poly}, {@code //pos1} on the first corner, {@code //pos2} per
 *       further corner, then {@code //expand vert} (else markers above or below the corners'
 *       Y would be missed). The ring is first simplified to at most {@link #MAX_POINTS} corners
 *       and pushed {@link #OUTWARD} m outwards, so markers on the edge are surely inside.</li>
 *   <li>Per set {@code /geotools paste <markers> -a -r [-s <set>]}, split so that no command is
 *       longer than {@link #MAX_CHARS} characters (the chat limit).</li>
 * </ol>
 *
 * <p>Works in the plan's Minecraft space ({@code [x, z]} with {@code z = -rdY}); the site works
 * in RD. Simplifying and offsetting commute with that mirror, and {@code floor(z) == -ceil(rdY)},
 * so both produce the same corners.</p>
 */
public final class PlantCommands {

    public static final int MAX_POINTS = 16;
    public static final double OUTWARD = 2.0;
    public static final int MAX_CHARS = 256;
    private static final int DEFAULT_Y = 64;

    private PlantCommands() {}

    /** The full sequence, with the leading slashes as a player types them. */
    public static List<String> build(MarkerPlan plan) {
        double[][] ring = selectionRing(plan.polygon(), OUTWARD);
        int y = medianY(plan);
        List<String> cmds = new ArrayList<>();
        cmds.add("//sel poly");
        for (int i = 0; i < ring.length; i++) {
            cmds.add("//pos" + (i == 0 ? 1 : 2) + " " + (int) Math.floor(ring[i][0]) + "," + y + "," + (int) Math.floor(ring[i][1]));
        }
        cmds.add("//expand vert");

        // Markers per set; sets in the order of the plan, markers alphabetically: the same plan
        // always gives the same text.
        List<TreeSet<String>> perSet = new ArrayList<>();
        for (int s = 0; s < plan.sets().size(); s++) perSet.add(new TreeSet<>());
        for (MarkerPlan.Marker m : plan.markers()) perSet.get(m.set()).add(plan.blockName(m));
        for (int s = 0; s < plan.sets().size(); s++) {
            if (!perSet.get(s).isEmpty()) cmds.addAll(pasteCommands(plan.sets().get(s), plan.defaultSet(), perSet.get(s)));
        }
        return cmds;
    }

    /** The simplified ring, pushed {@code outward} metres out: what the selection covers. */
    public static double[][] selectionRing(double[][] polygon, double outward) {
        return outward(simplify(polygon, MAX_POINTS), outward);
    }

    static List<String> pasteCommands(String set, String defaultSet, Iterable<String> markers) {
        String tail = " -a -r" + (set.equals(defaultSet) ? "" : " -s " + set);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String m : markers) {
            int length = "/geotools paste ".length() + current.length() + (current.isEmpty() ? 0 : 1) + m.length() + tail.length();
            if (!current.isEmpty() && length > MAX_CHARS) {
                out.add("/geotools paste " + current + tail);
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(',');
            current.append(m);
        }
        if (!current.isEmpty()) out.add("/geotools paste " + current + tail);
        return out;
    }

    /** Median site Y of the markers (null-filtered), or 64. //expand vert makes it uncritical. */
    static int medianY(MarkerPlan plan) {
        int[] ys = Arrays.stream(plan.markers()).mapToInt(MarkerPlan.Marker::siteY).filter(v -> v != MarkerPlan.NO_Y).sorted().toArray();
        return ys.length == 0 ? DEFAULT_Y : ys[ys.length / 2];
    }

    // -- Geometry (ported from commandos.js) --------------------------

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

    /** Ray casting on block centres; used by the plant check with the same ring as the selection. */
    public static boolean inRing(double x, double z, double[][] ring) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], zi = ring[i][1], xj = ring[j][0], zj = ring[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside;
        }
        return inside;
    }
}
