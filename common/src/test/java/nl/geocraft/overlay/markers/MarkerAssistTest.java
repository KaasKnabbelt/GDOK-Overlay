package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.render.VertexSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static nl.geocraft.overlay.markers.MarkerTestSupport.FakeWorld;
import static nl.geocraft.overlay.markers.MarkerTestSupport.plan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Help to finish a plan in the game (BOMEN.md §9.8): "still to do" per plan and per marker
 * block, the bar at the top, and the frames and beacons through blocks.
 */
class MarkerAssistTest {

    private final MarkerManager manager = MarkerManager.getInstance();
    private final FakeWorld world = new FakeWorld();
    private final List<int[]> reportedTodo = new ArrayList<>();

    @BeforeEach
    void reset() {
        manager.resetSession();
        manager.tick(null, 0, 0);
        MarkerAssist.reset();
        manager.setReporter(new MarkerManager.Reporter() {
            @Override
            public void progress(MarkerLayer layer) {
                reportedTodo.add(layer.todoPerBlock().clone());
            }

            @Override
            public void removed(String id) {
            }
        });
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) manager.tick(world, 0, 0);
    }

    /** red at 5,5 empty; red at 6,5 placed; lime at 7,5 empty; red at 8,5 in water (blocked). */
    private MarkerLayer fourMarkers() {
        world.set(6, 64, 5, "red_wool");
        world.set(8, 64, 5, "water");
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}, new int[]{6, 5, 64, 0, 0},
                new int[]{7, 5, 64, 1, 0}, new int[]{8, 5, 64, 0, 0}));
        ticks(MarkerManager.REPORT_INTERVAL + 1);
        return manager.get("plan-1");
    }

    @Test
    void theBarGoesBelowTheServersBossBars() {
        assertEquals(0, MarkerAssist.barOffset(0, 240), "geen bossbar: bovenaan, zoals altijd");
        assertEquals(19, MarkerAssist.barOffset(1, 240), "onder de welkomstbalk van GeoCraft");
        assertEquals(38, MarkerAssist.barOffset(2, 240));
        // Vanilla tekent tot een derde van het scherm: op 240 hoog vier balken (12, 31, 50, 69).
        assertEquals(76, MarkerAssist.barOffset(4, 240));
        assertEquals(76, MarkerAssist.barOffset(9, 240), "wat vanilla niet tekent, telt niet mee");
        assertEquals(19, MarkerAssist.barOffset(5, 60), "altijd minstens de eerste");
    }

    @Test
    void stillToDoPerMarkerBlockCountsEmptyAndBlocked() {
        MarkerLayer l = fourMarkers();
        assertEquals(1, l.counts().placed());
        assertEquals(1, l.counts().blocked());
        // red: 5,5 (empty) and 8,5 (blocked); lime: 7,5.
        assertArrayEquals(new int[]{2, 1}, l.todoPerBlock());
        assertArrayEquals(new int[]{2, 1}, reportedTodo.get(reportedTodo.size() - 1));
    }

    @Test
    void aChangeOnlyPerBlockIsReportedToo() {
        MarkerLayer l = fourMarkers();
        int before = reportedTodo.size();
        MarkerLayer.Counts counts = l.counts();
        // The red marker at 6,5 goes, the lime one at 7,5 is placed: the totals stay the same.
        world.blocks.remove(MarkerLayer.packPos(6, 64, 5));
        world.set(7, 64, 5, "lime_wool");
        manager.markChunkDirty(0, 0);
        ticks(MarkerManager.REPORT_INTERVAL + 1);
        assertEquals(counts.placed(), l.counts().placed());
        assertEquals(counts.toPlace(), l.counts().toPlace());
        assertArrayEquals(new int[]{3, 0}, l.todoPerBlock());
        assertEquals(before + 1, reportedTodo.size(), "niet gemeld terwijl alleen de verdeling per blok veranderde");
    }

    @Test
    void theBarShowsTheNearestVisiblePlanNearby() {
        fourMarkers();
        MarkerAssist.tick(manager, 20, 20);
        MarkerAssist.Status s = MarkerAssist.status();
        assertNotNull(s);
        assertEquals("Testplan", s.label());
        assertEquals(3, s.todo(), "nog te doen = neer te zetten plus geblokkeerd, zoals Nog N op de site");
        assertEquals(4, s.total());
        assertEquals(1, s.done());
        assertEquals("§fTestplan§7: §fnog 3 §7van 4", MarkerAssist.barTitle(s));

        // 40 is the edge of the plan; 48 further is just in range, 49 not.
        MarkerAssist.tick(manager, 40 + MarkerAssist.HUD_RANGE, 20);
        assertNotNull(MarkerAssist.status());
        MarkerAssist.tick(manager, 41 + MarkerAssist.HUD_RANGE, 20);
        assertNull(MarkerAssist.status());

        manager.setHidden("plan-1", true);
        manager.tick(null, 0, 0);
        MarkerAssist.tick(manager, 20, 20);
        assertNull(MarkerAssist.status(), "een verborgen plan krijgt geen balk");
    }

    @Test
    void barTitleWhenEverythingStands() {
        assertEquals("§fSt. Anna§7: alles staat", MarkerAssist.barTitle(new MarkerAssist.Status("St. Anna", 0, 73)));
        assertEquals("§fBos§7: §fnog 1.234 §7van 2.000", MarkerAssist.barTitle(new MarkerAssist.Status("Bos", 1234, 2000)));
    }

    @Test
    void beaconsOnlyWithTenOrFewerLeft() {
        MarkerLayer l = fourMarkers();
        assertTrue(MarkerAssist.beacons(l), "2 neer te zetten");

        manager.resetSession();
        manager.tick(null, 0, 0);
        int[][] many = new int[11][];
        for (int i = 0; i < many.length; i++) many[i] = new int[]{10 + i, 10, 64, 0, 0};
        manager.submitSet(plan(many));
        ticks(2);
        assertFalse(MarkerAssist.beacons(manager.get("plan-1")), "11 over: nog geen bakens");

        manager.resetSession();
        manager.tick(null, 0, 0);
        world.set(10, 64, 10, "red_wool");
        manager.submitSet(plan(new int[]{10, 10, 64, 0, 0}));
        ticks(2);
        assertFalse(MarkerAssist.beacons(manager.get("plan-1")), "niets meer neer te zetten");
    }

    @Test
    void framesNearbyAndBeaconsFarOnlyForMarkersStillToPutDown() {
        MarkerLayer l = fourMarkers();
        CountingSink sink = new CountingSink();
        // 30 blocks away: a frame (80 vertices) and a beacon (16) for each of the two empty
        // markers; the placed and the blocked one get nothing.
        int v = MarkerRenderCore.emitSeeThrough(List.of(l), sink, null, 6.5, 65, 35.5, 128);
        assertEquals(2 * (80 + 16), v);
        assertEquals(v, sink.flat);
        assertEquals(0, sink.textured);
        assertTrue(sink.minAlpha >= 26, "onder 10% gooit de see-through-shader het weg: " + sink.minAlpha);

        // 100 blocks away: no frames (beyond 48), beacons within view distance.
        sink = new CountingSink();
        assertEquals(2 * 16, MarkerRenderCore.emitSeeThrough(List.of(l), sink, null, 6.5, 65, 105.5, 128));

        // Right next to them: frames, and the beacons have faded out.
        sink = new CountingSink();
        assertEquals(2 * 80, MarkerRenderCore.emitSeeThrough(List.of(l), sink, null, 6.5, 66, 7.5, 128));

        // Hidden: nothing.
        manager.setHidden("plan-1", true);
        manager.tick(null, 0, 0);
        assertEquals(0, MarkerRenderCore.emitSeeThrough(List.of(l), new CountingSink(), null, 6.5, 65, 35.5, 128));
    }

    @Test
    void noBeaconsWithMoreThanTenLeft() {
        int[][] many = new int[12][];
        for (int i = 0; i < many.length; i++) many[i] = new int[]{2 + 3 * i, 10, 64, 0, 0};
        manager.submitSet(plan(many));
        ticks(2);
        MarkerLayer l = manager.get("plan-1");
        // 200 blocks away: beyond the frames, and no beacons with 12 left.
        assertEquals(0, MarkerRenderCore.emitSeeThrough(List.of(l), new CountingSink(), null, 20, 65, 210, 256));
    }

    private static final class CountingSink implements VertexSink {
        int textured, flat, alpha = 255, minAlpha = 255;

        @Override
        public void vertex(float x, float y, float z, float u, float v, int face) {
            textured++;
        }

        @Override
        public void flatVertex(float x, float y, float z) {
            flat++;
            minAlpha = Math.min(minAlpha, alpha);
        }

        @Override
        public void setColor(int r, int g, int b, int a) {
            alpha = a;
        }
    }
}
