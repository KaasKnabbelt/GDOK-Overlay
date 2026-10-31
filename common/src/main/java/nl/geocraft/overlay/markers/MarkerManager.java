package nl.geocraft.overlay.markers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The marker layers of the tree plans (BOMEN.md §9). Stands apart from the overlays: an overlay
 * has one height and one block for all its cells, which suits painting; a tree plan needs a
 * height and a block per marker, and a status per marker.
 *
 * <p>Also runs the {@link ForeignCheck}s (one at a time, queued by {@link #requestForeignCheck})
 * and slowly re-polls the foreign positions found, so a placeholder a builder removes drops off
 * the count and loses its red box without a new check.</p>
 *
 * <p>Threading: the WebSocket thread only <em>submits</em> ({@link #submitSet},
 * {@link #submitRemove}); {@link #tick} applies those on the client thread, where everything
 * else (scanner, renderer, menu) runs. So a layer's arrays are never touched from two threads.</p>
 *
 * <p>Layers are session-only, like the overlays: {@link #resetSession()} on join/disconnect,
 * after which the viewer re-sends the plans that are "in Minecraft".</p>
 */
public final class MarkerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final MarkerManager INSTANCE = new MarkerManager();

    public static final int MAX_LAYERS = 20;
    public static final int MAX_TOTAL_MARKERS = 50_000;

    /** Markers rescanned per tick (dirty chunks first, near the player first). ~38 block reads each. */
    public static final int SCAN_BUDGET = 256;
    /** Markers re-polled per tick for world changes no event covered (other players, pistons). */
    public static final int ROUND_ROBIN_BUDGET = 16;
    public static final double NEAR_RADIUS = 64.0;
    /** Progress to the site at most this often per layer (ticks), and only when the counts changed. */
    public static final int REPORT_INTERVAL = 10;
    /** Foreign positions re-polled per layer per tick. */
    public static final int FOREIGN_BUDGET = 8;

    /** Callbacks to the site (set by the bridge). */
    public interface Reporter {
        void progress(MarkerLayer layer);

        void removed(String id);
    }

    private sealed interface Op permits SetOp, RemoveOp, ClearOp {}

    private record SetOp(MarkerPlan plan) implements Op {}

    private record RemoveOp(String id) implements Op {}

    private record ClearOp() implements Op {}

    private final ConcurrentLinkedQueue<Op> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, MarkerLayer> layers = new LinkedHashMap<>();
    private List<MarkerLayer> list = List.of();
    private volatile long revision = 0;
    private Reporter reporter;
    private final int[] kinds = new int[MarkerScanner.bufferSize()];
    private int rrLayer = 0, rrMarker = 0;
    private MarkerProbe begunProbe;
    private MarkerPlan begunPlan;
    private final ArrayDeque<String> checkQueue = new ArrayDeque<>();
    private ForeignCheck check;

    public static MarkerManager getInstance() {
        return INSTANCE;
    }

    public void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    // -- From the site (any thread) --------------------------------

    public void submitSet(MarkerPlan plan) {
        pending.add(new SetOp(plan));
    }

    public void submitRemove(String id) {
        pending.add(new RemoveOp(id));
    }

    // -- Client thread -----------------------------------------------

    /** Join/disconnect: forget every layer (session-only). */
    public void resetSession() {
        pending.clear();
        pending.add(new ClearOp());
    }

    /** Bumped when layers come or go, or a layer is hidden; the menu rebuilds on a change. */
    public long revision() {
        return revision;
    }

    public List<MarkerLayer> layers() {
        return list;
    }

    public MarkerLayer get(String id) {
        return layers.get(id);
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public int totalMarkers() {
        int n = 0;
        for (MarkerLayer l : list) n += l.size();
        return n;
    }

    /** The player removed a layer in-game: gone here, and the site stops treating it as sent. */
    public void removeByPlayer(String id) {
        if (layers.remove(id) == null) return;
        changed();
        if (reporter != null) reporter.removed(id);
    }

    public void setHidden(String id, boolean hidden) {
        MarkerLayer l = layers.get(id);
        if (l == null || l.isHidden() == hidden) return;
        l.setHidden(hidden);
        revision++;
    }

    public void markChunkDirty(int chunkX, int chunkZ) {
        for (MarkerLayer l : list) l.markChunkDirty(chunkX, chunkZ);
    }

    public void markAllDirty() {
        for (MarkerLayer l : list) l.markAllDirty();
    }

    /**
     * Check this plan's area for foreign marker blocks ({@link ForeignCheck}), on the next ticks.
     * A check that is already queued or running for the plan counts; {@link MarkerLayer#checks()}
     * goes up when it is done.
     */
    public void requestForeignCheck(String id) {
        if (check != null && check.layer().id().equals(id)) return;
        if (!checkQueue.contains(id)) checkQueue.add(id);
    }

    /** The G menu opened: check every plan. */
    public void requestForeignChecks() {
        for (MarkerLayer l : list) requestForeignCheck(l.id());
    }

    /** The plan the player stands in, else the one with the nearest edge; null without plans. */
    public MarkerLayer nearest(double x, double z) {
        MarkerLayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MarkerLayer l : list) {
            double d = PlanArea.distance(l.plan.polygon(), x, z);
            if (d < bestDistance) {
                bestDistance = d;
                best = l;
            }
        }
        return best;
    }

    /**
     * Once per client tick: apply the site's messages, rescan markers within budget, run the
     * foreign check and send changed counts to the site.
     *
     * @param probe  world access, or {@code null} when there is no world (only the queue is applied)
     * @param px, pz player position, to scan nearby markers first
     */
    public void tick(MarkerProbe probe, double px, double pz) {
        applyPending();
        if (probe == null || list.isEmpty()) return;

        int budget = SCAN_BUDGET;
        double nearSq = NEAR_RADIUS * NEAR_RADIUS;
        passes:
        for (int pass = 0; pass < 2; pass++) {
            boolean wantNear = pass == 0;
            for (MarkerLayer l : list) {
                for (int b = 0; b < l.bucketCount(); b++) {
                    if (!l.bucketDirty[b]) continue;
                    double dx = l.bucketCenterX[b] - px, dz = l.bucketCenterZ[b] - pz;
                    if ((dx * dx + dz * dz <= nearSq) != wantNear) continue;
                    MarkerPlan.Marker first = l.plan.markers()[l.order[l.bucketStart[b]]];
                    if (!probe.isChunkLoaded(first.x() >> 4, first.z() >> 4)) {
                        l.bucketDirty[b] = false; // the chunk-load event marks it dirty again
                        continue;
                    }
                    begin(probe, l);
                    for (int k = l.bucketStart[b]; k < l.bucketStart[b + 1]; k++) {
                        MarkerScanner.scan(l, l.order[k], probe, kinds);
                    }
                    l.bucketDirty[b] = false;
                    budget -= l.bucketStart[b + 1] - l.bucketStart[b];
                    if (budget <= 0) break passes;
                }
            }
        }

        // Slow round-robin for changes no event reported.
        for (int k = 0; k < ROUND_ROBIN_BUDGET && !list.isEmpty(); k++) {
            if (rrLayer >= list.size()) {
                rrLayer = 0;
                rrMarker = 0;
            }
            MarkerLayer l = list.get(rrLayer);
            if (rrMarker >= l.size()) {
                rrLayer++;
                rrMarker = 0;
                continue;
            }
            begin(probe, l);
            MarkerScanner.scan(l, rrMarker++, probe, kinds);
        }

        runForeignCheck(probe);
        for (MarkerLayer l : list) pollForeign(l, probe);

        for (MarkerLayer l : list) {
            l.recount();
            l.ticksSinceReport++;
            if (reporter != null && !l.counts().equals(l.reported) && l.ticksSinceReport >= REPORT_INTERVAL) {
                l.reported = l.counts();
                l.ticksSinceReport = 0;
                reporter.progress(l);
            }
        }
    }

    private void applyPending() {
        Op op;
        boolean any = false;
        while ((op = pending.poll()) != null) {
            any = true;
            switch (op) {
                case ClearOp c -> {
                    layers.clear();
                    checkQueue.clear();
                    check = null;
                }
                case RemoveOp r -> layers.remove(r.id());
                case SetOp s -> set(s.plan());
            }
        }
        if (any) changed();
    }

    private void set(MarkerPlan plan) {
        MarkerLayer old = layers.get(plan.id());
        if (old == null && layers.size() >= MAX_LAYERS) {
            LOGGER.warn("[GeoCraft Overlay] Bomenplan '{}' geweigerd: max {} plannen", plan.id(), MAX_LAYERS);
            return;
        }
        int total = plan.markers().length;
        for (MarkerLayer l : layers.values()) if (!l.id().equals(plan.id())) total += l.size();
        if (total > MAX_TOTAL_MARKERS) {
            LOGGER.warn("[GeoCraft Overlay] Bomenplan '{}' geweigerd: meer dan {} markers in totaal", plan.id(), MAX_TOTAL_MARKERS);
            return;
        }
        MarkerLayer layer = new MarkerLayer(plan);
        if (old != null) layer.inheritFrom(old);
        layers.put(plan.id(), layer);
    }

    private void changed() {
        list = Collections.unmodifiableList(new ArrayList<>(layers.values()));
        rrLayer = 0;
        rrMarker = 0;
        revision++;
    }

    private void runForeignCheck(MarkerProbe probe) {
        if (check != null && layers.get(check.layer().id()) != check.layer()) {
            // The site sent a new version of the plan (or removed it) during the check.
            String id = check.layer().id();
            check = null;
            if (layers.containsKey(id)) checkQueue.addFirst(id);
        }
        if (check == null) {
            String id = checkQueue.peek();
            MarkerLayer l = id == null ? null : layers.get(id);
            if (id != null && l == null) {
                checkQueue.poll();
            } else if (l != null && !l.hasDirtyBucket()) {
                // Only once the scanner is through: the targets are the placed markers.
                checkQueue.poll();
                check = new ForeignCheck(l);
            }
        }
        if (check != null && check.tick(probe)) {
            check.layer().setForeign(check);
            check = null;
        }
    }

    /** Re-poll a few foreign positions: one that is no longer a marker block drops off. */
    private void pollForeign(MarkerLayer l, MarkerProbe probe) {
        if (l.foreignN == 0) return;
        begin(probe, l);
        for (int k = 0; k < FOREIGN_BUDGET && l.foreignN > 0; k++) {
            if (l.foreignCursor >= l.foreignN) l.foreignCursor = 0;
            int at = l.foreignCursor;
            long p = l.foreignPos[at];
            int x = MarkerLayer.unpackX(p), y = MarkerLayer.unpackY(p), z = MarkerLayer.unpackZ(p);
            if (probe.isChunkLoaded(x >> 4, z >> 4) && probe.kind(x, y, z, l.foreignBlock[at]) != MarkerProbe.MARKER) {
                l.dropForeign(at); // the last one moved into this slot: look at it next
            } else {
                l.foreignCursor++;
            }
        }
    }

    /** beginPlan only when the probe is not already prepared for this layer's plan. */
    private void begin(MarkerProbe probe, MarkerLayer l) {
        if (begunProbe == probe && begunPlan == l.plan) return;
        probe.beginPlan(l.plan.blocks(), l.plan.trunkBlocks());
        begunProbe = probe;
        begunPlan = l.plan;
    }
}
