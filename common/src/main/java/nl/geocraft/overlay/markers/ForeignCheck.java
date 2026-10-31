package nl.geocraft.overlay.markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marker blocks in the plan area that are not part of the plan. Geotools turns <em>every</em>
 * marker block in CVDK's selection into a tree, over the full world height. Seven gdok markers
 * (lime, pink, blue and yellow wool, gold, diamond and emerald blocks) are also the WorldEdit
 * placeholders builders use. So the loaded chunks under the area are searched for the block
 * types of the paste command ({@link PasteCommands#blocks}: the placed markers), and every hit
 * that is not a marker of this plan on its spot is foreign: it would become a tree too.
 *
 * <p>The area is the selection ring the site offers ({@link PlanArea}) plus
 * {@link #CHECK_MARGIN}. {@link MarkerManager} runs a check over several ticks (the G menu
 * asks for one when it opens, {@link PasteChat} before it prints the commands); the probe checks
 * each chunk section's palette first, so sections without any target cost almost nothing.
 * Chunks that are not loaded cannot be checked; their share is reported.</p>
 */
public final class ForeignCheck {

    @FunctionalInterface
    public interface Hit {
        void accept(int x, int y, int z, int target);
    }

    /** Foreign positions kept for the renderer (the count goes on beyond this). */
    public static final int MAX_POSITIONS = 500;
    /** Chunks per tick; a palette check per section keeps this cheap. */
    public static final int CHUNKS_PER_TICK = 48;
    /** The check ring is one metre wider than the selection, for WorldEdit's own edge rounding. */
    public static final double CHECK_MARGIN = 1.0;

    private final MarkerLayer layer;
    private final double[][] ring;
    /** Paste blocks, alphabetical; a hit's target is an index into this list. */
    private final List<String> targets;
    /** Plan block index per target. */
    private final int[] blockOfTarget;
    private final Map<Long, Integer> markerByColumn = new HashMap<>();
    private final long[] chunks;
    private int next = 0;

    private int foreign = 0;
    private final List<Long> positions = new ArrayList<>();
    private final List<Integer> positionBlocks = new ArrayList<>();
    private int inPlan = 0;
    private int unloaded = 0;

    public ForeignCheck(MarkerLayer layer) {
        this.layer = layer;
        MarkerPlan plan = layer.plan;
        this.ring = PlanArea.selectionRing(plan.polygon(), PlanArea.OUTWARD + CHECK_MARGIN);
        this.targets = PasteCommands.blocks(layer);
        this.blockOfTarget = new int[targets.size()];
        for (int t = 0; t < targets.size(); t++) blockOfTarget[t] = plan.blocks().indexOf(targets.get(t));
        for (int i = 0; i < plan.markers().length; i++) {
            MarkerPlan.Marker m = plan.markers()[i];
            markerByColumn.put(MarkerLayer.columnKey(m.x(), m.z()), i);
        }

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : ring) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        List<Long> list = new ArrayList<>();
        for (int cx = (int) Math.floor(minX) >> 4; cx <= (int) Math.floor(maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(minZ) >> 4; cz <= (int) Math.floor(maxZ) >> 4; cz++) {
                if (touchesRing(cx, cz)) list.add(MarkerLayer.chunkKey(cx, cz));
            }
        }
        chunks = list.stream().mapToLong(Long::longValue).toArray();
    }

    /** Check up to {@link #CHUNKS_PER_TICK} chunks; returns true when the check is complete. */
    public boolean tick(MarkerProbe probe) {
        if (targets.isEmpty()) {
            next = chunks.length;
            return true;
        }
        // Every tick: the probe is shared with other checks and the scanner.
        probe.beginTargets(targets);
        int end = Math.min(chunks.length, next + CHUNKS_PER_TICK);
        for (; next < end; next++) {
            int cx = (int) (chunks[next] >> 32), cz = (int) chunks[next];
            if (!probe.isChunkLoaded(cx, cz)) {
                unloaded++;
                continue;
            }
            probe.scanChunk(cx, cz, this::onHit);
        }
        return isDone();
    }

    private void onHit(int x, int y, int z, int target) {
        if (!PlanArea.contains(ring, x + 0.5, z + 0.5)) return;
        Integer i = markerByColumn.get(MarkerLayer.columnKey(x, z));
        if (i != null && layer.plan.blockName(layer.plan.markers()[i]).equals(targets.get(target)) && isMarkerHeight(i, y)) {
            inPlan++;
            return;
        }
        foreign++;
        if (positions.size() < MAX_POSITIONS) {
            positions.add(MarkerLayer.packPos(x, y, z));
            positionBlocks.add(blockOfTarget[target]);
        }
    }

    /**
     * The marker block is at this plan marker's spot: exactly there once the scanner has seen
     * the column, anywhere in the scan range while it has not. A second marker block higher up
     * in the same column (a builder's placeholder on a roof) is foreign.
     */
    private boolean isMarkerHeight(int i, int y) {
        if (layer.status(i) == MarkerStatus.UNKNOWN) return Math.abs(y - layer.y(i)) <= MarkerScanner.RANGE;
        return y == layer.y(i);
    }

    public boolean isDone() {
        return next >= chunks.length;
    }

    public MarkerLayer layer() {
        return layer;
    }

    /** The paste blocks this check looks for. */
    public List<String> targets() {
        return targets;
    }

    public int foreign() {
        return foreign;
    }

    public long[] positions() {
        return positions.stream().mapToLong(Long::longValue).toArray();
    }

    /** Plan block index per position. */
    int[] positionBlocks() {
        return positionBlocks.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Marker blocks found on their plan spot. */
    public int inPlan() {
        return inPlan;
    }

    public int chunkCount() {
        return chunks.length;
    }

    public int unloadedChunks() {
        return unloaded;
    }

    public double unloadedFraction() {
        return chunks.length == 0 ? 0 : (double) unloaded / chunks.length;
    }

    /** Does the ring cover part of this chunk? A chunk corner inside, a ring corner inside, or crossing edges. */
    private boolean touchesRing(int cx, int cz) {
        double x0 = cx << 4, z0 = cz << 4, x1 = x0 + 16, z1 = z0 + 16;
        double[][] sq = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (double[] p : sq) if (PlanArea.contains(ring, p[0], p[1])) return true;
        for (double[] p : ring) if (p[0] >= x0 && p[0] <= x1 && p[1] >= z0 && p[1] <= z1) return true;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            for (int k = 0; k < 4; k++) {
                if (segmentsCross(ring[j], ring[i], sq[k], sq[(k + 1) % 4])) return true;
            }
        }
        return false;
    }

    private static boolean segmentsCross(double[] a, double[] b, double[] c, double[] d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double cross(double[] o, double[] p, double[] q) {
        return (p[0] - o[0]) * (q[1] - o[1]) - (p[1] - o[1]) * (q[0] - o[0]);
    }
}
