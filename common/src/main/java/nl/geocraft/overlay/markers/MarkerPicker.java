package nl.geocraft.overlay.markers;

import java.util.List;

/**
 * Middle-click on a ghost marker (BOMEN.md §9.3): which ghost is under the crosshair? A ray
 * from the eye against the unit cubes of the markers that still need a block, within reach.
 * The module compares the distance with vanilla's own target: a real block in front of the
 * ghost wins, so ordinary pick-block keeps working everywhere else.
 */
public final class MarkerPicker {

    /** The ghost that was hit: its block name and the distance along the ray. */
    public record Hit(String block, double distance) {}

    private MarkerPicker() {}

    /**
     * @param dx, dy, dz look direction (need not be normalised)
     * @param reach      maximum distance from the eye
     */
    public static Hit pick(List<MarkerLayer> layers, double ex, double ey, double ez,
                           double dx, double dy, double dz, double reach) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return null;
        dx /= len;
        dy /= len;
        dz /= len;
        double reachSq = (reach + 2) * (reach + 2);
        Hit best = null;
        for (MarkerLayer l : layers) {
            if (l.isHidden()) continue;
            for (int b = 0; b < l.bucketCount(); b++) {
                double bx = l.bucketCenterX(b) - ex, bz = l.bucketCenterZ(b) - ez;
                if (bx * bx + bz * bz > (reach + 12) * (reach + 12)) continue;
                for (int k = l.bucketStart(b); k < l.bucketEnd(b); k++) {
                    int i = l.markerAt(k);
                    if (!l.status(i).needsMarker()) continue;
                    MarkerPlan.Marker m = l.plan.markers()[i];
                    double cx = m.x() + 0.5 - ex, cy = l.y(i) + 0.5 - ey, cz = m.z() + 0.5 - ez;
                    if (cx * cx + cy * cy + cz * cz > reachSq) continue;
                    double t = rayBox(ex, ey, ez, dx, dy, dz, m.x(), l.y(i), m.z());
                    if (t >= 0 && t <= reach && (best == null || t < best.distance())) {
                        best = new Hit(l.plan.blockName(m), t);
                    }
                }
            }
        }
        return best;
    }

    /** Slab test of a ray against the unit cube at (x, y, z); distance to the entry point, or -1. */
    static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, int x, int y, int z) {
        double tMin = 0, tMax = Double.MAX_VALUE;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        int[] lo = {x, y, z};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > lo[a] + 1) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (lo[a] + 1 - o[a]) / d[a];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        return tMin;
    }
}
