package nl.geocraft.overlay.markers;

/**
 * Finds the real spot of one marker and what stands there (BOMEN.md §9.2).
 *
 * <p>The site computes the marker Y from the AHN (ground level + 1). The GeoCraft world is
 * built from that same AHN, but a block or two off is normal, and under a bridge or next to a
 * wall the nearest free spot is somewhere else. So the scanner looks at the column around the
 * site's Y (±{@link #RANGE}) and takes the nearest <em>spot</em>: a position with solid ground
 * directly below it. What stands on that spot decides the status:</p>
 * <ul>
 *   <li>the marker block: {@link MarkerStatus#PLACED};</li>
 *   <li>air or a plant: {@link MarkerStatus#EMPTY}, unless something solid covers it within
 *       {@link #RANGE} blocks (a roof, a bridge), then {@link MarkerStatus#BLOCKED};</li>
 *   <li>water: {@link MarkerStatus#BLOCKED};</li>
 *   <li>a trunk or leaves: {@link MarkerStatus#PLANTED} when the marker was seen here before,
 *       otherwise {@link MarkerStatus#TREE} (a tree was already there);</li>
 *   <li>anything else solid is not a spot; the search goes on.</li>
 * </ul>
 * <p>After planting, geotools puts the lowest trunk block where the marker was. A log gives a
 * TREE spot straight away; a fence trunk is one of the plan's trunk blocks, which the probe
 * also reports as TREE.</p>
 */
public final class MarkerScanner {

    /** How far above and below the site's Y the spot may be. */
    public static final int RANGE = 12;

    private static final int LOW = RANGE + 1;              // one extra below: the ground under the lowest spot
    private static final int SPAN = LOW + RANGE + RANGE + 1; // up to the cover check above the highest spot

    private MarkerScanner() {}

    /**
     * Scan marker {@code i} of the layer. The caller has called {@link MarkerProbe#beginPlan}
     * for this layer. An unloaded chunk leaves the marker as it was (the last known status
     * stays the best information).
     *
     * @param kinds scratch buffer of at least {@link #bufferSize()} entries
     */
    public static void scan(MarkerLayer layer, int i, MarkerProbe probe, int[] kinds) {
        MarkerPlan.Marker m = layer.plan.markers()[i];
        if (!probe.isChunkLoaded(m.x() >> 4, m.z() >> 4)) return;
        int e = m.siteY() != MarkerPlan.NO_Y ? m.siteY() : probe.surfaceY(m.x(), m.z());

        int base = e - LOW;
        for (int k = 0; k < SPAN; k++) kinds[k] = probe.kind(m.x(), base + k, m.z(), m.block());

        for (int step = 0; step <= 2 * RANGE; step++) {
            int d = (step + 1) / 2 * (step % 2 == 1 ? 1 : -1); // 0, +1, -1, +2, -2, ...
            int at = LOW + d;
            if (kinds[at - 1] != MarkerProbe.SOLID) continue;
            MarkerStatus found;
            switch (kinds[at]) {
                case MarkerProbe.MARKER -> {
                    found = MarkerStatus.PLACED;
                    layer.wasPlaced[i] = true;
                }
                case MarkerProbe.AIR, MarkerProbe.REPLACEABLE -> found = covered(kinds, at) ? MarkerStatus.BLOCKED : MarkerStatus.EMPTY;
                case MarkerProbe.WATER -> found = MarkerStatus.BLOCKED;
                case MarkerProbe.TREE -> found = layer.wasPlaced[i] ? MarkerStatus.PLANTED : MarkerStatus.TREE;
                default -> {
                    continue; // solid on solid: not a spot
                }
            }
            layer.y[i] = e + d;
            layer.status[i] = (byte) found.ordinal();
            return;
        }
        // No spot near the site's Y (a deep pond, a cliff): keep the site's Y, ghost stays.
        layer.y[i] = e;
        layer.status[i] = (byte) MarkerStatus.UNKNOWN.ordinal();
    }

    public static int bufferSize() {
        return SPAN;
    }

    private static boolean covered(int[] kinds, int at) {
        for (int k = at + 1; k <= at + RANGE; k++) {
            if (kinds[k] == MarkerProbe.SOLID || kinds[k] == MarkerProbe.MARKER) return true;
        }
        return false;
    }
}
