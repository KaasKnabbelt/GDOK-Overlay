package nl.geocraft.overlay.markers;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static nl.geocraft.overlay.markers.MarkerTestSupport.FakeWorld;
import static nl.geocraft.overlay.markers.MarkerTestSupport.SQUARE;
import static nl.geocraft.overlay.markers.MarkerTestSupport.message;
import static nl.geocraft.overlay.markers.MarkerTestSupport.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The marker store: site messages applied on the client tick, scanning, progress to the site. */
class MarkerManagerTest {

    private final MarkerManager manager = MarkerManager.getInstance();
    private final FakeWorld world = new FakeWorld();
    private final List<MarkerLayer.Counts> reported = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();

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
                removed.add(id);
            }
        });
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) manager.tick(world, 0, 0);
    }

    @Test
    void siteMessagesApplyOnTheClientTick() {
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}));
        assertNull(manager.get("plan-1"), "nog niet toegepast: dat gebeurt op de client-thread");
        manager.tick(null, 0, 0);
        assertNotNull(manager.get("plan-1"));
        manager.submitRemove("plan-1");
        manager.tick(null, 0, 0);
        assertNull(manager.get("plan-1"));
        assertTrue(removed.isEmpty(), "een verwijdering door de site gaat niet terug naar de site");
    }

    @Test
    void scanningCountsAndReportsProgress() {
        world.set(6, 64, 5, "red_wool");
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}, new int[]{6, 5, 64, 0, 0}, new int[]{7, 5, 64, 1, 1}));
        ticks(MarkerManager.REPORT_INTERVAL + 1);
        MarkerLayer.Counts c = manager.get("plan-1").counts();
        assertEquals(3, c.total());
        assertEquals(1, c.placed());
        assertEquals(2, c.toPlace());
        assertEquals(1, reported.size(), "één melding: daarna veranderde er niets");

        world.set(5, 64, 5, "red_wool");
        manager.markChunkDirty(0, 0);
        ticks(MarkerManager.REPORT_INTERVAL + 1);
        assertEquals(2, manager.get("plan-1").counts().placed());
        assertEquals(2, reported.size());
    }

    @Test
    void aResentPlanRemembersWhereMarkersStood() {
        world.set(5, 64, 5, "red_wool");
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}));
        ticks(2);
        assertEquals(1, manager.get("plan-1").counts().placed());

        // Planted: the trunk replaced the marker. The site re-sends the plan (an edit, a resync).
        world.set(5, 64, 5, "oak_log");
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}, new int[]{20, 20, 64, 0, 0}));
        ticks(2);
        assertEquals(1, manager.get("plan-1").counts().planted(), "geplant, niet 'al een boom'");
    }

    @Test
    void thePlayerRemovingALayerTellsTheSite() {
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}));
        manager.tick(null, 0, 0);
        manager.removeByPlayer("plan-1");
        assertNull(manager.get("plan-1"));
        assertEquals(List.of("plan-1"), removed);
    }

    @Test
    void hiddenLayersBumpTheRevision() {
        manager.submitSet(plan(new int[]{5, 5, 64, 0, 0}));
        manager.tick(null, 0, 0);
        long rev = manager.revision();
        manager.setHidden("plan-1", true);
        assertTrue(manager.get("plan-1").isHidden());
        assertTrue(manager.revision() > rev);
    }

    @Test
    void tooManyMarkersInTotalAreRefused() {
        int[][] many = new int[MarkerPlanParser.MAX_MARKERS][];
        for (int i = 0; i < many.length; i++) many[i] = new int[]{i % 40, i / 40, 64, 0, 0};
        for (int p = 0; p < 3; p++) {
            JsonObject msg = message("plan-" + p, SQUARE, List.of("red_wool"), List.of("gdok"), many);
            manager.submitSet(MarkerPlanParser.parse(msg));
        }
        manager.tick(null, 0, 0);
        assertEquals(2, manager.layers().size(), "de derde gaat over de " + MarkerManager.MAX_TOTAL_MARKERS);
    }

    @Test
    void theParserRefusesNamesThatCouldInjectCommandFlags() {
        assertThrows(IllegalArgumentException.class, () -> MarkerPlanParser.parse(
                message("x", SQUARE, List.of("red_wool -s other"), List.of("gdok"), new int[]{0, 0, 64, 0, 0})));
        assertThrows(IllegalArgumentException.class, () -> MarkerPlanParser.parse(
                message("x", SQUARE, List.of("red_wool,/op"), List.of("gdok"), new int[]{0, 0, 64, 0, 0})));
        assertThrows(IllegalArgumentException.class, () -> MarkerPlanParser.parse(
                message("x", SQUARE, List.of("red_wool"), List.of("gdok"), new int[]{0, 0, 64, 3, 0})),
                "blokindex buiten de lijst");
        assertThrows(IllegalArgumentException.class, () -> MarkerPlanParser.parse(
                message("x", SQUARE, List.of("red_wool"), List.of("gdok"), new int[]{0, 0, 64})),
                "marker zonder blokindex");
        MarkerPlan ok = MarkerPlanParser.parse(
                message("x", SQUARE, List.of("minecraft:red_wool"), List.of("gdok"), new int[]{0, 0, Integer.MIN_VALUE, 0, 0}));
        assertEquals("red_wool", ok.blocks().get(0), "namespace eraf");
        assertEquals(MarkerPlan.NO_Y, ok.markers()[0].siteY());
    }
}
