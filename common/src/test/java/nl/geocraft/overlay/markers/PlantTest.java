package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.ServerGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static nl.geocraft.overlay.markers.MarkerTestSupport.FakeWorld;
import static nl.geocraft.overlay.markers.MarkerTestSupport.SQUARE;
import static nl.geocraft.overlay.markers.MarkerTestSupport.message;
import static nl.geocraft.overlay.markers.MarkerTestSupport.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planting flow: commands, the check before planting, the command pacing, picking a ghost. */
class PlantTest {

    @AfterEach
    void closeGate() {
        PlantRunner.getInstance().stop();
        ServerGate.getInstance().setDevBypass(false);
    }

    @Test
    void commandsFollowTheSite() {
        MarkerPlan p = plan(new int[]{5, 5, 64, 0, 0}, new int[]{6, 7, 66, 1, 1}, new int[]{9, 9, 65, 0, 0});
        List<String> cmds = PlantCommands.build(p);
        // Square 0..40, pushed 2 m out along the bisector (2·√2 on the diagonal): -2..42.
        assertEquals(List.of(
                "//sel poly",
                "//pos1 -2,65,-2",
                "//pos2 42,65,-2",
                "//pos2 42,65,42",
                "//pos2 -2,65,42",
                "//expand vert",
                "/geotools paste red_wool -a -r",
                "/geotools paste lime_wool -a -r -s ragratis"), cmds);
    }

    @Test
    void pasteCommandsStayUnderTheChatLimit() {
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < 60; i++) markers.add("light_blue_stained_glass_" + i);
        List<String> cmds = PlantCommands.pasteCommands("groveden", "bomenalles", markers);
        assertTrue(cmds.size() > 1);
        int total = 0;
        for (String c : cmds) {
            assertTrue(c.length() <= PlantCommands.MAX_CHARS, c.length() + ": " + c);
            assertTrue(c.endsWith(" -a -r -s groveden"));
            total += c.substring("/geotools paste ".length(), c.indexOf(" -a")).split(",").length;
        }
        assertEquals(60, total, "geen marker kwijt bij het opknippen");
    }

    @Test
    void manyCornersAreSimplifiedToSixteen() {
        double[][] circle = new double[64][];
        for (int i = 0; i < 64; i++) {
            double a = 2 * Math.PI * i / 64;
            circle[i] = new double[]{100 * Math.cos(a), 100 * Math.sin(a)};
        }
        double[][] ring = PlantCommands.selectionRing(circle, PlantCommands.OUTWARD);
        assertTrue(ring.length <= PlantCommands.MAX_POINTS && ring.length >= 3);
        for (double[] c : circle) {
            assertTrue(PlantCommands.inRing(c[0] * 0.999, c[1] * 0.999, ring), "de selectie omvat het gebied");
        }
    }

    @Test
    void theCheckFindsMarkerBlocksOutsideThePlan() {
        FakeWorld world = new FakeWorld();
        MarkerManager.getInstance().resetSession();
        MarkerManager.getInstance().submitSet(plan(new int[]{5, 5, 64, 0, 0}, new int[]{30, 30, 64, 1, 1}));
        world.set(5, 64, 5, "red_wool");      // on its spot
        world.set(30, 64, 30, "lime_wool");   // on its spot
        world.set(20, 64, 20, "red_wool");    // a stray inside the area
        world.set(5, 90, 5, "red_wool");      // same column, but high up in a building: stray too
        world.set(200, 64, 200, "red_wool");  // far outside the selection: not pasted, not reported
        world.set(12, 64, 12, "blue_wool");   // not a marker of this plan
        for (int i = 0; i < 3; i++) MarkerManager.getInstance().tick(world, 0, 0);
        MarkerLayer layer = MarkerManager.getInstance().get("plan-1");

        PlantCheck check = new PlantCheck(layer, world);
        while (!check.tick()) { /* a few ticks */ }
        assertEquals(2, check.inPlan());
        assertEquals(2, check.strays());
        assertEquals(0, check.unloadedChunks());
    }

    @Test
    void unloadedChunksAreReported() {
        FakeWorld world = new FakeWorld();
        world.unloaded.add(MarkerLayer.chunkKey(2, 2));
        MarkerManager.getInstance().resetSession();
        MarkerManager.getInstance().submitSet(plan(new int[]{5, 5, 64, 0, 0}));
        MarkerManager.getInstance().tick(world, 0, 0);
        PlantCheck check = new PlantCheck(MarkerManager.getInstance().get("plan-1"), world);
        while (!check.tick()) { }
        assertEquals(1, check.unloadedChunks());
        assertTrue(check.unloadedFraction() > 0 && check.unloadedFraction() < 0.5);
    }

    @Test
    void theRunnerPacesCommandsAndStopsWhenTheGateCloses() {
        ServerGate.getInstance().setDevBypass(true);
        List<String> sent = new ArrayList<>();
        PlantRunner r = PlantRunner.getInstance();
        r.setSender(sent::add);
        assertTrue(r.start("plan-1", List.of("//sel poly", "//pos1 1,2,3", "/geotools paste red_wool -a -r")));
        assertFalse(r.start("plan-2", List.of("x")), "één plant-opdracht tegelijk");

        r.tick();
        assertEquals(List.of("/sel poly"), sent, "zonder één leidende slash");
        for (int i = 0; i < PlantRunner.TICKS_BETWEEN - 1; i++) r.tick();
        assertEquals(1, sent.size(), "nog niet: tussen twee commando's zit ~1,1 s");
        r.tick();
        assertEquals("/pos1 1,2,3", sent.get(1));

        ServerGate.getInstance().setDevBypass(false);
        for (int i = 0; i < 100; i++) r.tick();
        assertEquals(2, sent.size(), "gestopt zodra de server-gate dichtgaat");
        assertFalse(r.isRunning());
    }

    @Test
    void middleClickFindsTheGhostUnderTheCrosshair() {
        MarkerManager.getInstance().resetSession();
        MarkerManager.getInstance().submitSet(plan(new int[]{5, 5, 64, 0, 0}, new int[]{8, 5, 64, 1, 1}));
        MarkerManager.getInstance().tick(new FakeWorld(), 0, 0);
        List<MarkerLayer> layers = MarkerManager.getInstance().layers();

        // Eye 3 blocks west of the first ghost, looking east at its centre.
        MarkerPicker.Hit hit = MarkerPicker.pick(layers, 2.5, 64.5, 5.5, 1, 0, 0, 5);
        assertNotNull(hit);
        assertEquals("red_wool", hit.block());
        assertEquals(2.5, hit.distance(), 1e-9);

        assertEquals(null, MarkerPicker.pick(layers, 2.5, 64.5, 5.5, -1, 0, 0, 5), "de andere kant op");
        assertEquals(null, MarkerPicker.pick(layers, -10, 64.5, 5.5, 1, 0, 0, 5), "buiten bereik");
    }

    @Test
    void siteAndModAgreeOnADefaultSetOfTheirOwn() {
        MarkerPlan p = MarkerPlanParser.parse(message("p", SQUARE, List.of("red_wool"), List.of("groveden"), new int[]{1, 1, 64, 0, 0}));
        assertEquals("/geotools paste red_wool -a -r -s groveden", PlantCommands.build(p).get(6));
    }
}
