package nl.geocraft.overlay.render;

import java.util.List;

/**
 * Budgeted occupancy scanner. Each client tick it rescans up to {@link #DIRTY_BUDGET} dirty
 * buckets (new/rebuilt meshes, Y changes, chunk loads, block edits reported by the module's
 * event hooks) plus {@link #ROUND_ROBIN_BUDGET} arbitrary buckets as a safety net for world
 * changes no event covers (pistons, other players out of the event's reach).
 *
 * <p>Dirty buckets near the player (within {@link #NEAR_RADIUS} blocks) are rescanned before
 * the far ones, so after a Y shift, which dirties every bucket of a mesh, the area the player
 * is looking at is correct within a tick or two even on a 100k-block overlay.</p>
 *
 * <p>A bucket holds at most 256 blocks, so one tick costs at most ~{@code (DIRTY_BUDGET +
 * ROUND_ROBIN_BUDGET) * 256} block-state reads (~17k, well under a millisecond). Runs on the
 * client thread; the render pass runs on the same thread, so no synchronisation is needed.</p>
 */
public final class OccupancyUpdater {

    /** Dirty buckets rescanned per tick. */
    public static final int DIRTY_BUDGET = 64;
    /** Clean buckets re-polled per tick (round-robin over all meshes). */
    public static final int ROUND_ROBIN_BUDGET = 4;
    /** Dirty buckets whose centre is within this many blocks of the player go first. */
    public static final double NEAR_RADIUS = 64.0;

    private int rrEntry = 0;
    private int rrBucket = 0;

    /**
     * @param px player/camera X, used to prioritise nearby dirty buckets
     * @param pz player/camera Z
     */
    public void tick(List<MeshCache.Entry> entries, OccupancyProbe probe, double px, double pz) {
        if (entries.isEmpty()) return;

        // 1. Dirty buckets, near the player first: pass 0 takes only buckets within
        //    NEAR_RADIUS, pass 1 the rest. The extra iteration is just a flag check.
        int budget = DIRTY_BUDGET;
        double nearSq = NEAR_RADIUS * NEAR_RADIUS;
        passes:
        for (int pass = 0; pass < 2; pass++) {
            boolean wantNear = pass == 0;
            for (MeshCache.Entry e : entries) {
                BakedOverlayMesh mesh = e.mesh;
                int y = mesh.renderY();
                if (y == Integer.MIN_VALUE) continue; // not rendered yet: Y unknown
                for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
                    if (!b.dirty) continue;
                    double dx = b.centerX - px, dz = b.centerZ - pz;
                    if ((dx * dx + dz * dz <= nearSq) != wantNear) continue;
                    b.rescan(probe, y);
                    if (--budget == 0) break passes;
                }
            }
        }

        // 2. Slow round-robin re-poll for world edits no event covered.
        for (int k = 0; k < ROUND_ROBIN_BUDGET; k++) {
            if (rrEntry >= entries.size()) {
                rrEntry = 0;
                rrBucket = 0;
            }
            BakedOverlayMesh mesh = entries.get(rrEntry).mesh;
            BakedOverlayMesh.Bucket[] buckets = mesh.buckets();
            if (rrBucket >= buckets.length || mesh.renderY() == Integer.MIN_VALUE) {
                rrEntry++;
                rrBucket = 0;
                continue;
            }
            buckets[rrBucket].rescan(probe, mesh.renderY());
            rrBucket++;
        }
    }
}
