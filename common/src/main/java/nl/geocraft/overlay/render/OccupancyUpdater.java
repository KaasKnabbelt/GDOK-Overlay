package nl.geocraft.overlay.render;

import java.util.List;

/**
 * Budgeted occupancy scanner. Each client tick it rescans up to {@link #DIRTY_BUDGET} dirty
 * buckets (new/rebuilt meshes, Y changes, chunk loads) plus {@link #ROUND_ROBIN_BUDGET}
 * arbitrary buckets so that blocks placed or removed in-world inside an overlay are picked
 * up within a few seconds without any world event hook.
 *
 * <p>A bucket holds at most 256 blocks, so one tick costs at most ~{@code (DIRTY_BUDGET +
 * ROUND_ROBIN_BUDGET) * 256} block-state reads. Runs on the client thread; the render pass
 * runs on the same thread, so no synchronisation is needed.</p>
 */
public final class OccupancyUpdater {

    /** Dirty buckets rescanned per tick. */
    public static final int DIRTY_BUDGET = 8;
    /** Clean buckets re-polled per tick (round-robin over all meshes). */
    public static final int ROUND_ROBIN_BUDGET = 1;

    private int rrEntry = 0;
    private int rrBucket = 0;

    public void tick(List<MeshCache.Entry> entries, OccupancyProbe probe) {
        if (entries.isEmpty()) return;

        // 1. Dirty buckets first, in overlay order.
        int budget = DIRTY_BUDGET;
        outer:
        for (MeshCache.Entry e : entries) {
            BakedOverlayMesh mesh = e.mesh;
            int y = mesh.renderY();
            if (y == Integer.MIN_VALUE) continue; // not rendered yet: Y unknown
            for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
                if (!b.dirty) continue;
                b.rescan(probe, y);
                if (--budget == 0) break outer;
            }
        }

        // 2. Slow round-robin re-poll for in-world edits.
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
