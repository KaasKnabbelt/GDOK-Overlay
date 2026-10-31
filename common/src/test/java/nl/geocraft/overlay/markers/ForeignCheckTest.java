package nl.geocraft.overlay.markers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static nl.geocraft.overlay.markers.MarkerTestSupport.FakeWorld;
import static nl.geocraft.overlay.markers.MarkerTestSupport.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marker blocks in the plan area that are not part of the plan (V1). */
class ForeignCheckTest {

    private final MarkerManager manager = MarkerManager.getInstance();
    private final FakeWorld world = new FakeWorld();
    private final List<MarkerLayer.Counts> reported = new ArrayList<>();

    @BeforeEach
    void reset() {
        manager.resetSession();
        manager.tick(null, 0, 0);
        manager.setReporter(new MarkerManager.Reporter() {
            @Override
            public void progress(MarkerLayer layer) {
                reported.add(layer.counts());
            }

            @Override
            public void removed(String id) {
            }
        });
    }

    private MarkerLayer scanned(MarkerPlan p) {
        manager.submitSet(p);
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        return manager.get(p.id());
    }

    private ForeignCheck run(MarkerLayer l) {
        ForeignCheck check = new ForeignCheck(l);
        while (!check.tick(world)) { /* a few ticks */ }
        return check;
    }

    @Test
    void markerBlocksOutsideThePlanAreFound() {
        world.set(5, 64, 5, "red_wool");      // on its spot
        world.set(30, 64, 30, "lime_wool");   // on its spot
        world.set(20, 64, 20, "red_wool");    // a placeholder inside the area
        world.set(5, 90, 5, "red_wool");      // same column, high up in a building: foreign too
        world.set(200, 64, 200, "red_wool");  // far outside the area: not pasted, not counted
        world.set(12, 64, 12, "blue_wool");   // not a marker of this plan
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}, new int[]{30, 30, 64, 1}));

        ForeignCheck check = run(l);
        assertEquals(List.of("lime_wool", "red_wool"), check.targets());
        assertEquals(2, check.inPlan());
        assertEquals(2, check.foreign());
        assertEquals(2, check.positions().length);
        assertEquals(0, check.unloadedChunks());
    }

    @Test
    void onlyTheBlockTypesOfThePasteCommandCount() {
        world.set(5, 64, 5, "red_wool");      // placed: red is in the command
        world.set(12, 64, 12, "lime_wool");   // lime is a marker type of the plan, but none is placed yet
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}, new int[]{30, 30, 64, 1}));
        ForeignCheck check = run(l);
        assertEquals(List.of("red_wool"), check.targets());
        assertEquals(0, check.foreign(), "lime staat niet in het commando, dus wordt geen boom");
    }

    @Test
    void unloadedChunksAreReported() {
        world.unloaded.add(MarkerLayer.chunkKey(2, 2));
        world.set(5, 64, 5, "red_wool");
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}));
        ForeignCheck check = run(l);
        assertEquals(1, check.unloadedChunks());
        assertTrue(check.unloadedFraction() > 0 && check.unloadedFraction() < 0.5);
    }

    @Test
    void theManagerRunsTheCheckAndDropsRemovedBlocks() {
        world.set(5, 64, 5, "red_wool");
        world.set(20, 64, 20, "red_wool");
        world.set(22, 64, 20, "red_wool");
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}));
        assertEquals(-1, l.counts().foreign(), "nog niet gecontroleerd");

        manager.requestForeignCheck("plan-1");
        for (int i = 0; i < MarkerManager.REPORT_INTERVAL + 2; i++) manager.tick(world, 0, 0);
        assertEquals(1, l.checks());
        assertEquals(2, l.counts().foreign());
        assertEquals(2, l.foreignPositions());
        assertEquals(2, reported.get(reported.size() - 1).foreign(), "de site hoort het aantal");

        // A builder takes one placeholder away: off the count without a new check.
        world.blocks.remove(MarkerLayer.packPos(20, 64, 20));
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        assertEquals(1, l.counts().foreign());
        assertEquals(1, l.foreignPositions());
        assertEquals(MarkerLayer.packPos(22, 64, 20), l.foreignPos(0));
    }

    @Test
    void aNewVersionOfThePlanStartsUnchecked() {
        world.set(5, 64, 5, "red_wool");
        world.set(20, 64, 20, "red_wool");
        scanned(plan(new int[]{5, 5, 64, 0}));
        manager.requestForeignCheck("plan-1");
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        assertEquals(1, manager.get("plan-1").counts().foreign());

        manager.submitSet(plan(new int[]{5, 5, 64, 0}, new int[]{20, 20, 64, 0}));
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        assertEquals(-1, manager.get("plan-1").counts().foreign());
        manager.requestForeignCheck("plan-1");
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        assertEquals(0, manager.get("plan-1").counts().foreign(), "nu hoort hij bij het plan");
    }

    @Test
    void manyCornersAreSimplifiedToSixteen() {
        double[][] circle = new double[64][];
        for (int i = 0; i < 64; i++) {
            double a = 2 * Math.PI * i / 64;
            circle[i] = new double[]{100 * Math.cos(a), 100 * Math.sin(a)};
        }
        double[][] ring = PlanArea.selectionRing(circle, PlanArea.OUTWARD);
        assertTrue(ring.length <= PlanArea.MAX_POINTS && ring.length >= 3);
        for (double[] c : circle) {
            assertTrue(PlanArea.contains(ring, c[0] * 0.999, c[1] * 0.999), "de selectie omvat het gebied");
        }
        assertEquals(0, PlanArea.distance(circle, 0, 0));
        assertEquals(50, PlanArea.distance(new double[][]{{0, 0}, {10, 0}, {10, 10}, {0, 10}}, 60, 5), 1e-9);
    }
}
