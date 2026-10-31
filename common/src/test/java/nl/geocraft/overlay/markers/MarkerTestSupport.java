package nl.geocraft.overlay.markers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A fake world and plan builders for the marker tests. */
final class MarkerTestSupport {

    private MarkerTestSupport() {}

    /** Flat world: solid up to groundY (inclusive), air above; plus individual blocks. */
    static final class FakeWorld implements MarkerProbe {
        int groundY = 63;
        final Map<Long, String> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();
        private List<String> markerBlocks = List.of();
        private Set<String> trunks = Set.of();
        private List<String> targets = List.of();

        void set(int x, int y, int z, String block) {
            blocks.put(MarkerLayer.packPos(x, y, z), block);
        }

        String get(int x, int y, int z) {
            String b = blocks.get(MarkerLayer.packPos(x, y, z));
            if (b != null) return b;
            return y <= groundY ? "grass_block" : "air";
        }

        @Override
        public void beginPlan(List<String> markerBlocks, Set<String> trunkBlocks) {
            this.markerBlocks = markerBlocks;
            this.trunks = trunkBlocks;
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return !unloaded.contains(MarkerLayer.chunkKey(chunkX, chunkZ));
        }

        @Override
        public int kind(int x, int y, int z, int markerBlock) {
            String b = get(x, y, z);
            if (b.equals(markerBlocks.get(markerBlock))) return MARKER;
            return switch (b) {
                case "air" -> AIR;
                case "short_grass", "poppy" -> REPLACEABLE;
                case "water" -> WATER;
                case "oak_log", "oak_leaves" -> TREE;
                default -> trunks.contains(b) ? TREE : SOLID;
            };
        }

        @Override
        public int surfaceY(int x, int z) {
            return groundY + 1;
        }

        @Override
        public void beginTargets(List<String> targets) {
            this.targets = targets;
        }

        @Override
        public void scanChunk(int chunkX, int chunkZ, ForeignCheck.Hit out) {
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                long p = e.getKey();
                int x = MarkerLayer.unpackX(p), z = MarkerLayer.unpackZ(p);
                if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                int t = targets.indexOf(e.getValue());
                if (t >= 0) out.accept(x, MarkerLayer.unpackY(p), z, t);
            }
        }
    }

    /**
     * A plan message as the site sends it; markers as {x, z, y, blockIndex} or, as the site does
     * for beta.2, {x, z, y, blockIndex, setIndex}. {@code sets} null leaves the field out.
     */
    static JsonObject message(String id, double[][] polygon, List<String> blocks, List<String> sets, int[]... markers) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "markers");
        msg.addProperty("action", "set");
        msg.addProperty("id", id);
        msg.addProperty("label", "Testplan");
        JsonArray poly = new JsonArray();
        for (double[] p : polygon) {
            JsonArray pt = new JsonArray();
            pt.add(p[0]);
            pt.add(p[1]);
            poly.add(pt);
        }
        msg.add("polygon", poly);
        JsonArray b = new JsonArray();
        blocks.forEach(b::add);
        msg.add("blocks", b);
        if (sets != null) {
            JsonArray s = new JsonArray();
            sets.forEach(s::add);
            msg.add("sets", s);
        }
        JsonArray trunks = new JsonArray();
        trunks.add("spruce_fence");
        msg.add("trunks", trunks);
        JsonArray arr = new JsonArray();
        for (int[] m : markers) {
            JsonArray a = new JsonArray();
            a.add(m[0]);
            a.add(m[1]);
            if (m[2] == Integer.MIN_VALUE) a.add((String) null);
            else a.add(m[2]);
            for (int k = 3; k < m.length; k++) a.add(m[k]);
            arr.add(a);
        }
        msg.add("markers", arr);
        return msg;
    }

    static final double[][] SQUARE = {{0, 0}, {40, 0}, {40, 40}, {0, 40}};

    static MarkerPlan plan(int[]... markers) {
        return MarkerPlanParser.parse(message("plan-1", SQUARE, List.of("red_wool", "lime_wool"), List.of("gdok"), markers));
    }
}
