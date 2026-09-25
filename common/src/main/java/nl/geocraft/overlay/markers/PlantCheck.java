package nl.geocraft.overlay.markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The check before planting (BOMEN.md §9.5, step 2). Geotools turns <em>every</em> marker block
 * in the selection into a tree, over the full world height ({@code //expand vert}). Several
 * markers (lime, pink, blue and yellow wool, gold, diamond and emerald blocks) are also the
 * WorldEdit placeholders builders use. So before anything is run, the loaded chunks under the
 * selection are searched for the plan's marker blocks, and every one that is not a marker of
 * this plan is reported: it would become a tree too.
 *
 * <p>Runs over several ticks ({@link #tick}); the module's {@link BlockFinder} checks each
 * chunk section's palette first, so sections without any marker block cost almost nothing.
 * Chunks that are not loaded cannot be checked; their share is reported.</p>
 */
public final class PlantCheck {

    /** World access for the check, implemented per Minecraft module. */
    public interface BlockFinder {
        /** Called once; the module resolves the names to its block objects. */
        void begin(List<String> targets);

        boolean isChunkLoaded(int chunkX, int chunkZ);

        /** Report every block of the chunk, all heights, that is one of the targets ({@code target} = index). */
        void scanChunk(int chunkX, int chunkZ, Hit out);
    }

    @FunctionalInterface
    public interface Hit {
        void accept(int x, int y, int z, int target);
    }

    /** Stray positions kept for the renderer (the count goes on beyond this). */
    public static final int MAX_STRAY_POSITIONS = 500;
    /** Chunks per tick; a palette check per section keeps this cheap. */
    public static final int CHUNKS_PER_TICK = 48;
    /** The check ring is one metre wider than the selection, for WorldEdit's own edge rounding. */
    public static final double CHECK_MARGIN = 1.0;

    private final MarkerLayer layer;
    private final BlockFinder finder;
    private final double[][] ring;
    private final List<String> targets;
    /** Target index per plan block index (-1 = not a target). */
    private final Map<Long, Integer> markerByColumn = new HashMap<>();
    private final int[] targetOfBlock;
    private final long[] chunks;
    private int next = 0;

    private int strays = 0;
    private final List<Long> strayPositions = new ArrayList<>();
    private int inPlan = 0;
    private int unloaded = 0;

    public PlantCheck(MarkerLayer layer, BlockFinder finder) {
        this.layer = layer;
        this.finder = finder;
        MarkerPlan plan = layer.plan;
        this.ring = PlantCommands.selectionRing(plan.polygon(), PlantCommands.OUTWARD + CHECK_MARGIN);

        // Only blocks that markers actually use are pasted (and so matter).
        Map<String, Integer> t = new LinkedHashMap<>();
        targetOfBlock = new int[plan.blocks().size()];
        java.util.Arrays.fill(targetOfBlock, -1);
        for (int i = 0; i < plan.markers().length; i++) {
            MarkerPlan.Marker m = plan.markers()[i];
            String name = plan.blockName(m);
            Integer ti = t.get(name);
            if (ti == null) {
                ti = t.size();
                t.put(name, ti);
            }
            targetOfBlock[m.block()] = ti;
            markerByColumn.put(MarkerLayer.columnKey(m.x(), m.z()), i);
        }
        this.targets = List.copyOf(t.keySet());

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
        if (!targets.isEmpty()) finder.begin(targets);
    }

    /** Check up to {@link #CHUNKS_PER_TICK} chunks; returns true when the check is complete. */
    public boolean tick() {
        if (targets.isEmpty()) {
            next = chunks.length;
            return true;
        }
        int end = Math.min(chunks.length, next + CHUNKS_PER_TICK);
        for (; next < end; next++) {
            int cx = (int) (chunks[next] >> 32), cz = (int) chunks[next];
            if (!finder.isChunkLoaded(cx, cz)) {
                unloaded++;
                continue;
            }
            finder.scanChunk(cx, cz, this::onHit);
        }
        return isDone();
    }

    private void onHit(int x, int y, int z, int target) {
        if (!PlantCommands.inRing(x + 0.5, z + 0.5, ring)) return;
        Integer i = markerByColumn.get(MarkerLayer.columnKey(x, z));
        if (i != null && targetOfBlock[layer.plan.markers()[i].block()] == target && isMarkerHeight(i, y)) {
            inPlan++;
            return;
        }
        strays++;
        if (strayPositions.size() < MAX_STRAY_POSITIONS) strayPositions.add(MarkerLayer.packPos(x, y, z));
    }

    /** The marker block is at this plan marker's spot (exactly, once the scanner saw it there). */
    private boolean isMarkerHeight(int i, int y) {
        if (layer.status(i) == MarkerStatus.PLACED) return y == layer.y(i);
        return Math.abs(y - layer.y(i)) <= MarkerScanner.RANGE;
    }

    public boolean isDone() {
        return next >= chunks.length;
    }

    /** 0..1 */
    public double progress() {
        return chunks.length == 0 ? 1 : (double) next / chunks.length;
    }

    public int strays() {
        return strays;
    }

    public long[] strayPositions() {
        return strayPositions.stream().mapToLong(Long::longValue).toArray();
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

    public MarkerLayer layer() {
        return layer;
    }

    /** Does the ring cover part of this chunk? A chunk corner inside, a ring corner inside, or crossing edges. */
    private boolean touchesRing(int cx, int cz) {
        double x0 = cx << 4, z0 = cz << 4, x1 = x0 + 16, z1 = z0 + 16;
        double[][] sq = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (double[] p : sq) if (PlantCommands.inRing(p[0], p[1], ring)) return true;
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
