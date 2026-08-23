package nl.geocraft.overlay.render;

import nl.geocraft.overlay.OverlayData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baked mesh must emit exactly the same quads as the reference renderer below: a straight
 * port of the pre-1.1.0 per-frame renderer (top face always, side faces only on exterior
 * edges, border quads at y+1 on occupied positions) plus the bottom face that was added for
 * 1.1.1 (the pre-1.1.0 renderer had none, which left full blocks open at the underside).
 */
class OverlayMeshBuilderTest {

    private static final float[] UV = {0.25f, 0.5f, 0.125f, 0.375f};
    private static final double CAM_X = 3.5, CAM_Y = 70.2, CAM_Z = -7.25;

    /** Collects emitted quads as canonical strings, independent of emission order. */
    private static final class CollectingSink implements VertexSink {
        final List<String> current = new ArrayList<>();
        final Set<String> quads = new HashSet<>();
        int vertexCount = 0;

        @Override
        public void vertex(float x, float y, float z, float u, float v, int face) {
            current.add(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.5f,%.5f,f%d", x, y, z, u, v, face));
            flush();
        }

        @Override
        public void flatVertex(float x, float y, float z) {
            current.add(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,flat", x, y, z));
            flush();
        }

        private void flush() {
            vertexCount++;
            if (current.size() == 4) {
                quads.add(String.join("|", current));
                current.clear();
            }
        }
    }

    // -- reference renderer (port of the old OverlayRenderer) -------------------

    private static long packPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void referenceSlabs(OverlayData overlay, float h, Set<Long> occupied, VertexSink sink, int overlayY) {
        float u0 = UV[0], u1 = UV[1], v0 = UV[2], v1 = UV[3];
        Set<Long> positionSet = new HashSet<>();
        for (OverlayData.BlockPos pos : overlay.blocks()) positionSet.add(packPos(pos.x(), pos.z()));
        Set<Long> done = new HashSet<>();
        for (OverlayData.BlockPos pos : overlay.blocks()) {
            if (!done.add(packPos(pos.x(), pos.z()))) continue;
            if (occupied.contains(packPos(pos.x(), pos.z()))) continue;
            float x = (float) (pos.x() - CAM_X);
            float z = (float) (pos.z() - CAM_Z);
            float y0 = (float) (overlayY - CAM_Y) + OverlayGeometry.Y_OFFSET;
            float y1 = y0 + h;
            boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
            boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
            boolean hasWest = positionSet.contains(packPos(pos.x() - 1, pos.z()));
            boolean hasEast = positionSet.contains(packPos(pos.x() + 1, pos.z()));
            float vSide0 = v0;
            float vSide1 = v0 + (v1 - v0) * h;

            sink.vertex(x, y1, z, u0, v0, VertexSink.FACE_UP);
            sink.vertex(x, y1, z + 1, u0, v1, VertexSink.FACE_UP);
            sink.vertex(x + 1, y1, z + 1, u1, v1, VertexSink.FACE_UP);
            sink.vertex(x + 1, y1, z, u1, v0, VertexSink.FACE_UP);
            sink.vertex(x, y0, z, u0, v0, VertexSink.FACE_DOWN);
            sink.vertex(x + 1, y0, z, u1, v0, VertexSink.FACE_DOWN);
            sink.vertex(x + 1, y0, z + 1, u1, v1, VertexSink.FACE_DOWN);
            sink.vertex(x, y0, z + 1, u0, v1, VertexSink.FACE_DOWN);
            if (!hasNorth) {
                sink.vertex(x, y1, z, u1, vSide0, VertexSink.FACE_NORTH);
                sink.vertex(x + 1, y1, z, u0, vSide0, VertexSink.FACE_NORTH);
                sink.vertex(x + 1, y0, z, u0, vSide1, VertexSink.FACE_NORTH);
                sink.vertex(x, y0, z, u1, vSide1, VertexSink.FACE_NORTH);
            }
            if (!hasSouth) {
                sink.vertex(x + 1, y1, z + 1, u1, vSide0, VertexSink.FACE_SOUTH);
                sink.vertex(x, y1, z + 1, u0, vSide0, VertexSink.FACE_SOUTH);
                sink.vertex(x, y0, z + 1, u0, vSide1, VertexSink.FACE_SOUTH);
                sink.vertex(x + 1, y0, z + 1, u1, vSide1, VertexSink.FACE_SOUTH);
            }
            if (!hasWest) {
                sink.vertex(x, y1, z + 1, u1, vSide0, VertexSink.FACE_WEST);
                sink.vertex(x, y1, z, u0, vSide0, VertexSink.FACE_WEST);
                sink.vertex(x, y0, z, u0, vSide1, VertexSink.FACE_WEST);
                sink.vertex(x, y0, z + 1, u1, vSide1, VertexSink.FACE_WEST);
            }
            if (!hasEast) {
                sink.vertex(x + 1, y1, z, u1, vSide0, VertexSink.FACE_EAST);
                sink.vertex(x + 1, y1, z + 1, u0, vSide0, VertexSink.FACE_EAST);
                sink.vertex(x + 1, y0, z + 1, u0, vSide1, VertexSink.FACE_EAST);
                sink.vertex(x + 1, y0, z, u1, vSide1, VertexSink.FACE_EAST);
            }
        }
    }

    private static void referenceBorders(OverlayData overlay, Set<Long> occupied, VertexSink sink, int overlayY) {
        Set<Long> positionSet = new HashSet<>();
        for (OverlayData.BlockPos pos : overlay.blocks()) positionSet.add(packPos(pos.x(), pos.z()));
        Set<Long> done = new HashSet<>();
        float w = OverlayGeometry.BORDER_WIDTH;
        for (OverlayData.BlockPos pos : overlay.blocks()) {
            if (!done.add(packPos(pos.x(), pos.z()))) continue;
            if (!occupied.contains(packPos(pos.x(), pos.z()))) continue;
            float x = (float) (pos.x() - CAM_X);
            float z = (float) (pos.z() - CAM_Z);
            float yt = (float) (overlayY + 1 - CAM_Y) + OverlayGeometry.Y_OFFSET;
            boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
            boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
            boolean hasWest = positionSet.contains(packPos(pos.x() - 1, pos.z()));
            boolean hasEast = positionSet.contains(packPos(pos.x() + 1, pos.z()));
            if (!hasNorth) {
                sink.flatVertex(x, yt, z);
                sink.flatVertex(x, yt, z + w);
                sink.flatVertex(x + 1, yt, z + w);
                sink.flatVertex(x + 1, yt, z);
            }
            if (!hasSouth) {
                sink.flatVertex(x, yt, z + 1 - w);
                sink.flatVertex(x, yt, z + 1);
                sink.flatVertex(x + 1, yt, z + 1);
                sink.flatVertex(x + 1, yt, z + 1 - w);
            }
            if (!hasWest) {
                sink.flatVertex(x, yt, z);
                sink.flatVertex(x + w, yt, z);
                sink.flatVertex(x + w, yt, z + 1);
                sink.flatVertex(x, yt, z + 1);
            }
            if (!hasEast) {
                sink.flatVertex(x + 1 - w, yt, z);
                sink.flatVertex(x + 1, yt, z);
                sink.flatVertex(x + 1, yt, z + 1);
                sink.flatVertex(x + 1 - w, yt, z + 1);
            }
        }
    }

    // -- helpers ---------------------------------------------------------------

    private static OverlayData overlay(String tag, OverlayData.BlockPos... blocks) {
        return new OverlayData("id", "paint", blocks, 64, 200, 30, 30, 255, "label", tag);
    }

    private static OverlayData randomOverlay(long seed, int count, int spread) {
        Random rnd = new Random(seed);
        OverlayData.BlockPos[] blocks = new OverlayData.BlockPos[count];
        for (int i = 0; i < count; i++) {
            blocks[i] = new OverlayData.BlockPos(rnd.nextInt(spread) - spread / 2 - 40, rnd.nextInt(spread) - spread / 2 + 17);
        }
        return overlay("stone", blocks);
    }

    private static Set<Long> randomOccupied(OverlayData overlay, long seed, double fraction) {
        Random rnd = new Random(seed);
        Set<Long> occ = new HashSet<>();
        for (OverlayData.BlockPos p : overlay.blocks()) {
            if (rnd.nextDouble() < fraction) occ.add(packPos(p.x(), p.z()));
        }
        return occ;
    }

    private static void scanAll(BakedOverlayMesh mesh, Set<Long> occupied, int y) {
        mesh.setRenderY(y);
        OccupancyProbe probe = (x, yy, z) -> yy == y && occupied.contains(packPos(x, z));
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) b.rescan(probe, y);
    }

    private static void assertSameGeometry(OverlayData overlay, RenderMode mode, Set<Long> occupied) {
        float h = OverlayGeometry.heightFor(mode);
        int y = overlay.y();

        CollectingSink expected = new CollectingSink();
        referenceSlabs(overlay, h, occupied, expected, y);
        referenceBorders(overlay, occupied, expected, y);

        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay, mode, UV);
        scanAll(mesh, occupied, y);
        CollectingSink actual = new CollectingSink();
        mesh.replaySlabs(actual, null, CAM_X, CAM_Y, CAM_Z, y, 1_000_000);
        mesh.replayBorders(actual, null, CAM_X, CAM_Y, CAM_Z, y, 1_000_000);

        assertEquals(expected.vertexCount, actual.vertexCount, "vertex count");
        assertEquals(expected.quads, actual.quads, "quad set");
    }

    // -- tests -----------------------------------------------------------------

    @Test
    void singleBlockCarpetHasTopBottomAndFourSides() {
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay("stone", new OverlayData.BlockPos(5, 5)), RenderMode.CARPET, UV);
        assertEquals(1, mesh.buckets().length);
        assertEquals(6, mesh.buckets()[0].slabQuadCount());
        assertEquals(4, mesh.buckets()[0].borderQuadCount());
        assertEquals(1, mesh.blockCount());
    }

    @Test
    void twoAdjacentBlocksShareNoInteriorFace() {
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(
                overlay("stone", new OverlayData.BlockPos(0, 0), new OverlayData.BlockPos(1, 0)), RenderMode.FULLBLOCK, UV);
        // 2 tops + 2 bottoms + 2*3 exterior sides = 10 quads; interior east/west pair culled
        assertEquals(10, mesh.buckets()[0].slabQuadCount());
        assertEquals(6, mesh.buckets()[0].borderQuadCount());
    }

    @Test
    void duplicatesAreCollapsed() {
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(
                overlay("stone", new OverlayData.BlockPos(0, 0), new OverlayData.BlockPos(0, 0)), RenderMode.FULLBLOCK, UV);
        assertEquals(1, mesh.blockCount());
    }

    @Test
    void blocksAreBucketedPerChunkColumnAcrossNegativeCoordinates() {
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay("stone",
                new OverlayData.BlockPos(-1, -1), new OverlayData.BlockPos(0, 0),
                new OverlayData.BlockPos(-1, 0), new OverlayData.BlockPos(0, -1),
                new OverlayData.BlockPos(15, 15), new OverlayData.BlockPos(16, 16)), RenderMode.CARPET, UV);
        assertEquals(5, mesh.buckets().length);
        Set<String> chunks = new HashSet<>();
        int blocks = 0;
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
            chunks.add(b.chunkX + "," + b.chunkZ);
            blocks += b.blockCount();
            for (int i = 0; i < b.blockCount(); i++) {
                assertEquals(b.chunkX, b.blockX(i) >> 4);
                assertEquals(b.chunkZ, b.blockZ(i) >> 4);
            }
        }
        assertEquals(Set.of("-1,-1", "0,0", "-1,0", "0,-1", "1,1"), chunks);
        assertEquals(6, blocks);
        // chunk keys are sorted, so markChunkDirty finds the bucket via binary search
        mesh.markChunkDirty(1, 1);
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) b.dirty = false;
        mesh.markChunkDirty(-1, 0);
        int dirty = 0;
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) if (b.isDirty()) dirty++;
        assertEquals(1, dirty);
    }

    @Test
    void replayMatchesReferenceRenderer_carpet() {
        OverlayData overlay = randomOverlay(1, 3000, 90);
        assertSameGeometry(overlay, RenderMode.CARPET, Set.of());
        assertSameGeometry(overlay, RenderMode.CARPET, randomOccupied(overlay, 2, 0.3));
    }

    @Test
    void replayMatchesReferenceRenderer_fullblock() {
        OverlayData overlay = randomOverlay(3, 5000, 120);
        assertSameGeometry(overlay, RenderMode.FULLBLOCK, Set.of());
        assertSameGeometry(overlay, RenderMode.FULLBLOCK, randomOccupied(overlay, 4, 0.5));
    }

    @Test
    void nullTagFollowsRenderModeAndStaysTinted() {
        OverlayData overlay = new OverlayData("click", "click", new OverlayData.BlockPos[]{
                new OverlayData.BlockPos(10, 10), new OverlayData.BlockPos(11, 10)}, 64, 255, 0, 0, 255, "", null);
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay, RenderMode.FULLBLOCK, UV);
        assertTrue(mesh.isTinted());
        // The click marker uses the mode's height like any overlay (full block in fullblock mode).
        assertSameGeometry(overlay, RenderMode.FULLBLOCK, Set.of());
        assertSameGeometry(overlay, RenderMode.CARPET, Set.of());
    }

    @Test
    void replaySkipsThePriorityPosition() {
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(
                overlay("stone", new OverlayData.BlockPos(5, 5), new OverlayData.BlockPos(6, 5)), RenderMode.FULLBLOCK, UV);
        scanAll(mesh, Set.of(), 64);
        CollectingSink all = new CollectingSink();
        mesh.replaySlabs(all, null, CAM_X, CAM_Y, CAM_Z, 64, 1_000_000);
        assertEquals(10, all.quads.size());

        // Skip (5,5): only (6,5) remains, whose west face stays culled against the skipped neighbour.
        CollectingSink skipped = new CollectingSink();
        mesh.replaySlabs(skipped, null, CAM_X, CAM_Y, CAM_Z, 64, 1_000_000, 5, 5);
        assertEquals(5, skipped.quads.size());
        assertTrue(all.quads.containsAll(skipped.quads));

        // Borders: both occupied, the skipped position emits no border either (3 exterior edges of (6,5)).
        scanAll(mesh, Set.of(packPos(5, 5), packPos(6, 5)), 64);
        CollectingSink borders = new CollectingSink();
        mesh.replayBorders(borders, null, CAM_X, CAM_Y, CAM_Z, 64, 1_000_000, 5, 5);
        assertEquals(3, borders.quads.size());
    }

    @Test
    void yChangeKeepsGeometryButDirtiesOccupancy() {
        OverlayData overlay = randomOverlay(5, 500, 40);
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay, RenderMode.FULLBLOCK, UV);
        Set<Long> occ = randomOccupied(overlay, 6, 0.5);
        scanAll(mesh, occ, 64);
        assertFalse(mesh.hasDirtyBuckets());
        mesh.setRenderY(70);
        assertTrue(mesh.hasDirtyBuckets());
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
            for (int i = 0; i < b.blockCount(); i++) assertFalse(b.isOccupied(i));
        }
        // Same mesh is still valid for the overlay at another Y (array identity)
        assertTrue(mesh.matches(overlay.withY(70), RenderMode.FULLBLOCK, UV));
        assertFalse(mesh.matches(overlay, RenderMode.CARPET, UV));
        assertFalse(mesh.matches(overlay, RenderMode.FULLBLOCK, new float[]{0, 1, 0, 1}));
    }

    @Test
    void distanceCullingSkipsFarBuckets() {
        OverlayData overlay = overlay("stone", new OverlayData.BlockPos(0, 0), new OverlayData.BlockPos(5000, 5000));
        BakedOverlayMesh mesh = OverlayMeshBuilder.build(overlay, RenderMode.CARPET, UV);
        scanAll(mesh, Set.of(), 64);
        CollectingSink sink = new CollectingSink();
        mesh.replaySlabs(sink, null, 0, 64, 0, 64, 256);
        assertEquals(6, sink.quads.size());
    }

    @Test
    void meshCacheReusesMeshesAcrossYChangesAndRebuildsOnTagChange() {
        MeshCache cache = new MeshCache();
        OverlayData a = randomOverlay(7, 100, 30);
        List<OverlayData> list = new ArrayList<>(List.of(a));
        cache.reconcile(list, RenderMode.FULLBLOCK, tag -> UV, false);
        assertEquals(1, cache.rebuildCount());
        BakedOverlayMesh first = cache.entries().get(0).mesh;

        list.set(0, a.withY(99));
        cache.reconcile(list, RenderMode.FULLBLOCK, tag -> UV, false);
        assertEquals(1, cache.rebuildCount());
        assertTrue(first == cache.entries().get(0).mesh);

        OverlayData b = new OverlayData(a.id(), a.category(), a.blocks(), a.y(), 1, 2, 3, 4, "", "dirt");
        list.set(0, b);
        cache.reconcile(list, RenderMode.FULLBLOCK, tag -> UV, false);
        assertEquals(2, cache.rebuildCount());

        // UV drift after a resource reload
        cache.reconcile(list, RenderMode.FULLBLOCK, tag -> new float[]{0, 1, 0, 1}, true);
        assertEquals(3, cache.rebuildCount());

        list.clear();
        cache.reconcile(list, RenderMode.FULLBLOCK, tag -> UV, false);
        assertTrue(cache.isEmpty());
    }

    @Test
    void occupancyUpdaterScansDirtyBucketsWithinBudget() {
        OverlayData overlay = randomOverlay(8, 4000, 400); // many buckets
        MeshCache cache = new MeshCache();
        cache.reconcile(List.of(overlay), RenderMode.FULLBLOCK, tag -> UV, false);
        BakedOverlayMesh mesh = cache.entries().get(0).mesh;
        mesh.setRenderY(64);
        int buckets = mesh.buckets().length;
        assertTrue(buckets > OccupancyUpdater.DIRTY_BUDGET * 2);

        OccupancyUpdater updater = new OccupancyUpdater();
        updater.tick(cache.entries(), (x, y, z) -> true, 0, 0);
        int clean = 0;
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) if (!b.isDirty()) clean++;
        assertTrue(clean >= OccupancyUpdater.DIRTY_BUDGET && clean <= OccupancyUpdater.DIRTY_BUDGET + OccupancyUpdater.ROUND_ROBIN_BUDGET);

        int ticks = 0;
        while (mesh.hasDirtyBuckets() && ticks < 10_000) {
            updater.tick(cache.entries(), (x, y, z) -> true, 0, 0);
            ticks++;
        }
        assertFalse(mesh.hasDirtyBuckets());
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
            for (int i = 0; i < b.blockCount(); i++) assertTrue(b.isOccupied(i));
        }
    }

    @Test
    void occupancyUpdaterScansNearbyBucketsFirst() {
        OverlayData overlay = randomOverlay(9, 4000, 400); // far more buckets than one tick's budget
        MeshCache cache = new MeshCache();
        cache.reconcile(List.of(overlay), RenderMode.FULLBLOCK, tag -> UV, false);
        BakedOverlayMesh mesh = cache.entries().get(0).mesh;
        mesh.setRenderY(64);

        double px = -40, pz = 17; // centre of the random spread (see randomOverlay)
        new OccupancyUpdater().tick(cache.entries(), (x, y, z) -> true, px, pz);

        // Every dirty bucket near the player must be rescanned in the very first tick.
        double nearSq = OccupancyUpdater.NEAR_RADIUS * OccupancyUpdater.NEAR_RADIUS;
        int nearTotal = 0;
        for (BakedOverlayMesh.Bucket b : mesh.buckets()) {
            double dx = b.centerX - px, dz = b.centerZ - pz;
            if (dx * dx + dz * dz <= nearSq) {
                nearTotal++;
                assertFalse(b.isDirty(), "nearby bucket still dirty after one tick");
            }
        }
        assertTrue(nearTotal > 0, "test premise: player stands inside the overlay");
        assertTrue(nearTotal <= OccupancyUpdater.DIRTY_BUDGET, "test premise: near set fits one tick's budget");
        assertTrue(mesh.hasDirtyBuckets(), "test premise: far buckets remain for later ticks");
    }
}
