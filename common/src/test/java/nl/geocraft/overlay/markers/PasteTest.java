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

/** The paste commands (C1): what goes in, the flags from the site, the chat lines, picking a ghost. */
class PasteTest {

    private final MarkerManager manager = MarkerManager.getInstance();
    private final FakeWorld world = new FakeWorld();
    private final List<List<PasteChat.Line>> printed = new ArrayList<>();

    @BeforeEach
    void reset() {
        manager.resetSession();
        manager.tick(null, 0, 0);
        PasteChat.getInstance().reset();
        PasteChat.getInstance().setPrinter(printed::add);
    }

    private MarkerLayer scanned(MarkerPlan p) {
        manager.submitSet(p);
        for (int i = 0; i < 3; i++) manager.tick(world, 0, 0);
        return manager.get(p.id());
    }

    @Test
    void onlyThePlacedBlocksGoInUniqueAndAlphabetical() {
        world.set(5, 64, 5, "red_wool");
        world.set(6, 64, 5, "red_wool");
        world.set(7, 64, 5, "lime_wool");
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}, new int[]{6, 5, 64, 0}, new int[]{7, 5, 64, 1}, new int[]{8, 5, 64, 1}));
        assertEquals(List.of("lime_wool", "red_wool"), PasteCommands.blocks(l));
        assertEquals(List.of("/geotools paste lime_wool,red_wool -a -r -s gdok"),
                PasteCommands.build(PasteCommands.blocks(l), l.plan.pasteFlags()));
    }

    @Test
    void commandsStayUnderTheChatLimit() {
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < 110; i++) blocks.add("light_blue_stained_glass_pane_" + i);
        List<String> cmds = PasteCommands.build(blocks, "-a -r -s gdok");
        assertTrue(cmds.size() > 1);
        List<String> back = new ArrayList<>();
        for (String c : cmds) {
            assertTrue(c.length() <= PasteCommands.MAX_CHARS, c.length() + ": " + c);
            assertTrue(c.startsWith("/geotools paste ") && c.endsWith(" -a -r -s gdok"), c);
            back.addAll(List.of(c.substring("/geotools paste ".length(), c.indexOf(" -a")).split(",")));
        }
        assertEquals(blocks, back, "geen blok kwijt of verwisseld bij het opknippen");
        assertEquals(List.of(), PasteCommands.build(List.of(), "-a -r -s gdok"));
    }

    @Test
    void theFlagsComeFromTheSiteOrTheDefault() {
        JsonObject msg = message("p", SQUARE, List.of("red_wool"), List.of("gdok"), new int[]{1, 1, 64, 0});
        assertEquals(PasteCommands.DEFAULT_FLAGS, MarkerPlanParser.parse(msg).pasteFlags(), "oude site zonder paste");

        JsonObject paste = new JsonObject();
        paste.addProperty("flags", "  -a  -r -s groveden ");
        msg.add("paste", paste);
        assertEquals("-a -r -s groveden", MarkerPlanParser.parse(msg).pasteFlags());
        assertEquals(List.of("/geotools paste red_wool -a -r -s groveden"),
                PasteCommands.build(List.of("red_wool"), MarkerPlanParser.parse(msg).pasteFlags()));
    }

    @Test
    void flagsThatCouldSmuggleACommandInAreRefused() {
        for (String bad : new String[]{"-a; /op me", "-a\n/op me", "-a -s Gdok", "/kill", "-a,b", "", "   ", "-a".repeat(33)}) {
            JsonObject msg = message("p", SQUARE, List.of("red_wool"), List.of("gdok"), new int[]{1, 1, 64, 0});
            JsonObject paste = new JsonObject();
            paste.addProperty("flags", bad);
            msg.add("paste", paste);
            assertThrows(IllegalArgumentException.class, () -> MarkerPlanParser.parse(msg), "'" + bad + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> PasteCommands.build(List.of("red_wool -s x"), "-a"));
    }

    @Test
    void setsAndDefaultSetAreIgnored() {
        // As the site sends it for beta.2 and beta.3: sets, a fifth marker element, no defaultSet.
        MarkerPlan withSets = MarkerPlanParser.parse(message("p", SQUARE, List.of("red_wool"), List.of("gdok"), new int[]{1, 1, 64, 0, 0}));
        assertEquals(1, withSets.markers().length);
        // No sets at all, four elements.
        MarkerPlan without = MarkerPlanParser.parse(message("p", SQUARE, List.of("red_wool"), null, new int[]{1, 1, 64, 0}));
        assertEquals(0, without.markers()[0].block());
        // An empty list and an empty defaultSet (beta.2 would refuse those).
        JsonObject msg = message("p", SQUARE, List.of("red_wool"), List.of(), new int[]{1, 1, 64, 0, 7});
        msg.addProperty("defaultSet", "");
        assertEquals(1, MarkerPlanParser.parse(msg).markers().length);
    }

    @Test
    void theChatLinesWarnAboveTheCommands() {
        world.set(5, 64, 5, "red_wool");
        world.set(20, 64, 20, "red_wool"); // foreign
        world.set(21, 64, 20, "red_wool"); // foreign
        MarkerLayer l = scanned(plan(new int[]{5, 5, 64, 0}, new int[]{30, 30, 64, 1}));

        PasteChat.getInstance().request("plan-1");
        assertTrue(printed.isEmpty(), "eerst de controle");
        for (int i = 0; i < 5 && printed.isEmpty(); i++) {
            manager.tick(world, 0, 0);
            PasteChat.getInstance().tick();
        }
        assertEquals(1, printed.size());
        List<PasteChat.Line> lines = printed.get(0);
        assertTrue(lines.get(0).text().contains("Testplan: klik een regel"), lines.get(0).text());
        assertTrue(lines.get(1).text().contains("Let op: 2 markerblokken in dit gebied horen niet bij het plan."), lines.get(1).text());
        PasteChat.Line cmd = lines.get(lines.size() - 1);
        assertTrue(cmd.isCommand());
        assertEquals("/geotools paste red_wool -a -r -s gdok", cmd.command());
        assertEquals(cmd.command(), cmd.text());
        assertEquals(2, l.counts().foreign(), "de teller voor de site en het G-menu");
    }

    @Test
    void withoutPlacedMarkersThereIsNoCommand() {
        scanned(plan(new int[]{5, 5, 64, 0}));
        PasteChat.getInstance().request("plan-1");
        for (int i = 0; i < 3; i++) {
            manager.tick(world, 0, 0);
            PasteChat.getInstance().tick();
        }
        assertEquals(1, printed.size());
        assertEquals(1, printed.get(0).size());
        assertTrue(printed.get(0).get(0).text().contains("nog geen geplaatste markers"));
        assertTrue(printed.get(0).stream().noneMatch(PasteChat.Line::isCommand));
    }

    @Test
    void gdokPasteTakesThePlanThePlayerIsIn() {
        manager.submitSet(plan(new int[]{5, 5, 64, 0}));
        manager.submitSet(MarkerPlanParser.parse(message("plan-far", new double[][]{{100, 0}, {140, 0}, {140, 40}, {100, 40}},
                List.of("red_wool"), null, new int[]{110, 5, 64, 0})));
        manager.tick(null, 0, 0);
        assertEquals("plan-1", manager.nearest(20, 20).id(), "erin");
        assertEquals("plan-far", manager.nearest(90, 20).id(), "dichter bij de rand van het verre plan");
        assertEquals("plan-1", manager.nearest(60, 20).id());

        manager.resetSession();
        manager.tick(null, 0, 0);
        PasteChat.getInstance().requestNearest(0, 0);
        assertTrue(printed.get(0).get(0).text().contains("geen bomenplan"));
    }

    @Test
    void aSentCommandFindsItsPrintedLine() {
        PasteChat.Printed<String> lines = new PasteChat.Printed<>();
        lines.add("/geotools paste red_wool -a -r -s gdok", "regel 1");
        lines.add("/geotools paste zwart -a -r -s gdok", "regel 2");
        assertEquals(List.of("regel 1"), lines.executed("geotools paste red_wool -a -r -s gdok"), "de client stuurt geen slash mee");
        assertEquals(List.of(), lines.executed("geotools paste red_wool -a -r"), "aangepast commando: geen regel");
        for (int i = 0; i < PasteChat.Printed.MAX; i++) lines.add("/x " + i, "oud");
        assertEquals(List.of(), lines.executed("geotools paste red_wool -a -r -s gdok"), "oude regels vallen eraf");
    }

    @Test
    void theCrosshairFindsTheGhost() {
        manager.submitSet(plan(new int[]{5, 5, 64, 0}, new int[]{8, 5, 64, 1}));
        manager.tick(world, 0, 0);
        List<MarkerLayer> layers = manager.layers();

        // Eye 3 blocks west of the first ghost, looking east at its centre.
        MarkerPicker.Hit hit = MarkerPicker.pick(layers, 2.5, 64.5, 5.5, 1, 0, 0, 5, true);
        assertNotNull(hit);
        assertEquals("red_wool", hit.block());
        assertEquals(2.5, hit.distance(), 1e-9);
        assertEquals(5, hit.x());
        assertEquals(64, hit.y());

        assertNull(MarkerPicker.pick(layers, 2.5, 64.5, 5.5, -1, 0, 0, 5, false), "de andere kant op");
        assertNull(MarkerPicker.pick(layers, -10, 64.5, 5.5, 1, 0, 0, 5, false), "buiten bereik");

        // Looking down at the ghost: the click lands on the top face of the ground, in the column.
        MarkerPicker.Hit down = MarkerPicker.pick(layers, 5.2, 66.5, 4.0, 0, -1, 0.6, 5, true);
        assertNotNull(down);
        double[] p = MarkerPicker.groundHitPoint(down, 5.2, 66.5, 4.0, 0, -1, 0.6);
        assertEquals(64.0, p[1], 1e-9);
        assertTrue(p[0] > 5 && p[0] < 6 && p[2] > 5 && p[2] < 6, p[0] + "," + p[2]);
    }

    @Test
    void rightClickOnlyPlacesOnSpotsTheScannerKnows() {
        world.unloaded.add(MarkerLayer.chunkKey(0, 0));
        manager.submitSet(plan(new int[]{5, 5, 64, 0}));
        manager.tick(world, 0, 0);
        assertEquals(MarkerStatus.UNKNOWN, manager.get("plan-1").status(0));
        assertNotNull(MarkerPicker.pick(manager.layers(), 2.5, 64.5, 5.5, 1, 0, 0, 5, false), "middelklik mag");
        assertNull(MarkerPicker.pick(manager.layers(), 2.5, 64.5, 5.5, 1, 0, 0, 5, true), "rechtsklik niet");
    }
}
