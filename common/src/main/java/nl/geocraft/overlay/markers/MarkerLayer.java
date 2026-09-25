package nl.geocraft.overlay.markers;

import java.util.Arrays;

/**
 * One marker layer in the mod: the plan from the site plus what the scanner found per marker.
 * Only touched from the client thread ({@link MarkerManager} applies the site's messages there),
 * so the arrays need no locking; the render pass runs on the same thread.
 *
 * <p>Markers are grouped per chunk column ("buckets"), like the overlay meshes: a chunk load or
 * a block edit only rescans the markers of that chunk, and the renderer culls per bucket.</p>
 */
public final class MarkerLayer {

    /** Marker counts per status, as reported to the site and shown in the G menu. */
    public record Counts(int total, int unknown, int empty, int placed, int blocked, int existing, int planted) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0, 0);

        /** Markers the player still has to put down. */
        public int toPlace() {
            return unknown + empty;
        }
    }

    public final MarkerPlan plan;

    /** Current Y per marker: the site's value until the scanner found the real spot. */
    final int[] y;
    /** {@link MarkerStatus} ordinal per marker. */
    final byte[] status;
    /** The marker block was seen on the spot at some point (turns a later trunk into PLANTED). */
    final boolean[] wasPlaced;

    /** Sorted chunk keys, one per bucket. */
    final long[] bucketKeys;
    /** Markers of bucket b: {@code order[bucketStart[b]] .. order[bucketStart[b + 1] - 1]}. */
    final int[] bucketStart;
    final int[] order;
    final boolean[] bucketDirty;
    final double[] bucketCenterX, bucketCenterZ;

    private boolean hidden;
    private Counts counts = Counts.EMPTY;
    /** Positions (see {@link #packPos}) of marker blocks outside the plan, from the last plant check. */
    private long[] strays = new long[0];

    /** Sprite UVs per block index ({u0, u1, v0, v1}), filled by the renderer; null = not looked up. */
    public float[][] uvCache;

    /** Last counts sent to the site, and ticks since; see {@link MarkerManager#tick}. */
    Counts reported;
    int ticksSinceReport;

    MarkerLayer(MarkerPlan plan) {
        this.plan = plan;
        int n = plan.markers().length;
        y = new int[n];
        status = new byte[n];
        wasPlaced = new boolean[n];
        for (int i = 0; i < n; i++) y[i] = plan.markers()[i].siteY();

        // Buckets: marker indices sorted by chunk key, then one bucket per distinct key.
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        MarkerPlan.Marker[] m = plan.markers();
        Arrays.sort(idx, (a, b) -> Long.compare(chunkKey(m[a].x() >> 4, m[a].z() >> 4), chunkKey(m[b].x() >> 4, m[b].z() >> 4)));
        order = new int[n];
        long[] keys = new long[n];
        int[] starts = new int[n + 1];
        int buckets = 0;
        long last = 0;
        for (int i = 0; i < n; i++) {
            order[i] = idx[i];
            long key = chunkKey(m[idx[i]].x() >> 4, m[idx[i]].z() >> 4);
            if (i == 0 || key != last) {
                keys[buckets] = key;
                starts[buckets] = i;
                buckets++;
                last = key;
            }
        }
        starts[buckets] = n;
        bucketKeys = Arrays.copyOf(keys, buckets);
        bucketStart = Arrays.copyOf(starts, buckets + 1);
        bucketDirty = new boolean[buckets];
        Arrays.fill(bucketDirty, true);
        bucketCenterX = new double[buckets];
        bucketCenterZ = new double[buckets];
        for (int b = 0; b < buckets; b++) {
            bucketCenterX[b] = ((int) (bucketKeys[b] >> 32) << 4) + 8.0;
            bucketCenterZ[b] = ((int) bucketKeys[b] << 4) + 8.0;
        }
    }

    /**
     * Carry the "was placed" memory over from the previous version of this plan (the site
     * re-sends a plan after every edit): a trunk on a spot where the marker stood before still
     * counts as planted. Matched per column and block.
     */
    void inheritFrom(MarkerLayer old) {
        hidden = old.hidden;
        java.util.HashMap<Long, String> placed = new java.util.HashMap<>();
        for (int i = 0; i < old.size(); i++) {
            if (!old.wasPlaced[i]) continue;
            MarkerPlan.Marker m = old.plan.markers()[i];
            placed.put(columnKey(m.x(), m.z()), old.plan.blockName(m));
        }
        if (placed.isEmpty()) return;
        for (int i = 0; i < size(); i++) {
            MarkerPlan.Marker m = plan.markers()[i];
            if (plan.blockName(m).equals(placed.get(columnKey(m.x(), m.z())))) wasPlaced[i] = true;
        }
    }

    public int size() {
        return plan.markers().length;
    }

    public String id() {
        return plan.id();
    }

    public MarkerStatus status(int i) {
        return MarkerStatus.of(status[i]);
    }

    public int y(int i) {
        return y[i];
    }

    public int bucketCount() {
        return bucketKeys.length;
    }

    public int bucketStart(int b) {
        return bucketStart[b];
    }

    public int bucketEnd(int b) {
        return bucketStart[b + 1];
    }

    /** Marker index at position {@code k} of the chunk-sorted order. */
    public int markerAt(int k) {
        return order[k];
    }

    public double bucketCenterX(int b) {
        return bucketCenterX[b];
    }

    public double bucketCenterZ(int b) {
        return bucketCenterZ[b];
    }

    public boolean isHidden() {
        return hidden;
    }

    void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public Counts counts() {
        return counts;
    }

    public long[] strays() {
        return strays;
    }

    void setStrays(long[] strays) {
        this.strays = strays;
    }

    void markChunkDirty(int chunkX, int chunkZ) {
        int b = Arrays.binarySearch(bucketKeys, chunkKey(chunkX, chunkZ));
        if (b >= 0) bucketDirty[b] = true;
    }

    void markAllDirty() {
        Arrays.fill(bucketDirty, true);
    }

    void recount() {
        int unknown = 0, empty = 0, placed = 0, blocked = 0, existing = 0, planted = 0;
        for (byte s : status) {
            switch (MarkerStatus.of(s)) {
                case UNKNOWN -> unknown++;
                case EMPTY -> empty++;
                case PLACED -> placed++;
                case BLOCKED -> blocked++;
                case TREE -> existing++;
                case PLANTED -> planted++;
            }
        }
        counts = new Counts(size(), unknown, empty, placed, blocked, existing, planted);
    }

    // -- Keys ---------------------------------------------------------

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Block position in one long: 26 bits x, 26 bits z, 12 bits y (all signed). */
    public static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }
}
