package nl.geocraft.overlay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Het hoogtemodel van fase 3: één sessie-niveau i.p.v. een middeling per toevoeging, zodat
 * het niveau niet meer per penseelstreek kruipt en niet van de aankomstvolgorde afhangt.
 */
class OverlayManagerTest {

    private final OverlayManager mgr = OverlayManager.getInstance();

    @BeforeEach
    void reset() {
        mgr.resetSession();
        mgr.setSameLevel(true);
        assertNull(mgr.getLevelY(), "leeg = geen niveau");
    }

    private static OverlayData overlay(String id, int y, int blocks) {
        OverlayData.BlockPos[] pos = new OverlayData.BlockPos[blocks];
        for (int i = 0; i < blocks; i++) pos[i] = new OverlayData.BlockPos(i, 0);
        return new OverlayData(id, "paint", pos, y, 255, 255, 255, 80, id, "white_wool");
    }

    @Test
    void firstOverlaySetsTheLevelAndLaterOnesFollow() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10)); // DSM-uitschieter: mag het niveau niet meer vergiftigen
        mgr.addOverlay(overlay("click", 47, 1));

        assertEquals(40, mgr.getLevelY());
        for (OverlayData o : mgr.getOverlays()) {
            assertEquals(40, mgr.getRenderY(o), o.id());
        }
        // De eigen hint blijft bewaard.
        assertEquals(55, mgr.getOverlay("paint_b").y());
    }

    @Test
    void sameLevelOffRestoresIndividualHeights() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.setSameLevel(false);
        assertEquals(40, mgr.getRenderY(mgr.getOverlay("paint_a")));
        assertEquals(55, mgr.getRenderY(mgr.getOverlay("paint_b")));
        mgr.setSameLevel(true);
        assertEquals(40, mgr.getRenderY(mgr.getOverlay("paint_b")));
    }

    @Test
    void levelDoesNotCreepOnRepaint() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        for (int i = 0; i < 20; i++) {
            // Elke penseelstreek stuurt dezelfde groep opnieuw, met een (licht) andere hint.
            mgr.addOverlay(overlay("paint_a", 40 + (i % 3), 10 + i));
        }
        assertEquals(40, mgr.getLevelY());
        assertEquals(40, mgr.getRenderY(mgr.getOverlay("paint_a")));
    }

    @Test
    void pageUpShiftsOnlyTheLevelWhileSameLevelIsOn() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        long before = mgr.revision();
        mgr.adjustAllY(5);
        assertTrue(mgr.revision() > before);
        assertEquals(45, mgr.getLevelY());
        assertEquals(45, mgr.getRenderY(mgr.getOverlay("paint_b")));
        // Eigen Y's onaangeroerd: sameLevel uit = terug naar de hints.
        assertEquals(40, mgr.getOverlay("paint_a").y());
        assertEquals(55, mgr.getOverlay("paint_b").y());

        // Een nieuwe streek ná PageUp landt op het aangepaste niveau.
        mgr.addOverlay(overlay("paint_c", 41, 3));
        assertEquals(45, mgr.getRenderY(mgr.getOverlay("paint_c")));
    }

    @Test
    void pageUpShiftsEveryOverlayWhileSameLevelIsOff() {
        mgr.setSameLevel(false);
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        assertNull(mgr.getLevelY());
        mgr.adjustAllY(-2);
        assertEquals(38, mgr.getRenderY(mgr.getOverlay("paint_a")));
        assertEquals(53, mgr.getRenderY(mgr.getOverlay("paint_b")));
    }

    @Test
    void repaintKeepsAnAdjustedIndividualHeight() {
        mgr.setSameLevel(false);
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.adjustAllY(3);
        mgr.addOverlay(overlay("paint_a", 40, 12)); // re-send met dezelfde hint
        assertEquals(43, mgr.getOverlay("paint_a").y());
        assertEquals(12, mgr.getOverlay("paint_a").blocks().length);
    }

    @Test
    void levelIsIndependentOfArrivalOrder() {
        // Zelfde set, andere volgorde: zelfde niveau zodra sameLevel (opnieuw) bepaald wordt
        // vanuit de aanwezige overlays (de grootste wint, bij gelijke grootte de kleinste id).
        mgr.setSameLevel(false);
        mgr.addOverlay(overlay("click", 52, 1));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.addOverlay(overlay("paint_a", 40, 30));
        mgr.setSameLevel(true);
        int first = mgr.getLevelY();

        mgr.clearCategory("all");
        mgr.setSameLevel(false);
        mgr.addOverlay(overlay("paint_a", 40, 30));
        mgr.addOverlay(overlay("click", 52, 1));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.setSameLevel(true);
        assertEquals(first, mgr.getLevelY());
        assertEquals(40, first);
    }

    @Test
    void resetRestoresHintsAndRederivesTheLevel() {
        mgr.addOverlay(overlay("paint_a", 40, 30));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.adjustAllY(7);
        assertTrue(mgr.resetAllY());
        assertEquals(40, mgr.getLevelY());
        assertFalse(mgr.resetAllY(), "tweede reset verandert niets");

        mgr.setSameLevel(false);
        mgr.adjustAllY(2);
        assertTrue(mgr.resetAllY());
        assertEquals(40, mgr.getOverlay("paint_a").y());
        assertEquals(55, mgr.getOverlay("paint_b").y());
    }

    @Test
    void siteUpdateYMovesTheLevelOrTheOverlay() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.updateOverlayY("paint_b", 48); // oude site-stepper
        assertEquals(48, mgr.getLevelY());
        assertEquals(55, mgr.getOverlay("paint_b").y());

        mgr.setSameLevel(false);
        mgr.updateOverlayY("paint_b", 50);
        assertEquals(50, mgr.getOverlay("paint_b").y());
        assertEquals(40, mgr.getOverlay("paint_a").y());
    }

    @Test
    void emptyStoreForgetsTheLevel() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.adjustAllY(9);
        mgr.removeOverlay("paint_a");
        assertNull(mgr.getLevelY());

        // Nieuwe tekening elders op de kaart start van haar eigen hint.
        mgr.addOverlay(overlay("paint_a", 120, 10));
        assertEquals(120, mgr.getLevelY());

        mgr.clearCategory("paint");
        assertNull(mgr.getLevelY());
    }

    @Test
    void clearingOneCategoryKeepsTheLevelWhileOthersRemain() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(new OverlayData("click", "click", new OverlayData.BlockPos[]{new OverlayData.BlockPos(0, 0)},
                44, 74, 222, 128, 80, "Marker", null));
        mgr.adjustAllY(1);
        mgr.clearCategory("click");
        assertEquals(41, mgr.getLevelY());
    }

    // -- Fase 4: pins, verbergen, sessie ---------------------------------

    @Test
    void pinnedSurvivesClearButNotRemoveOrReset() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 41, 10));
        mgr.setPinned("paint_a", true);
        assertTrue(mgr.isPinned("paint_a"));

        mgr.clearCategory("paint"); // site-"Wissen"
        assertEquals(1, mgr.getOverlayCount());
        assertTrue(mgr.getOverlay("paint_a") != null);
        assertEquals(40, mgr.getLevelY(), "niveau blijft zolang er iets staat");

        mgr.addOverlay(overlay("paint_b", 41, 10));
        mgr.clearCategory("all"); // in-game "Alles wissen (behalve vastgezet)"
        assertEquals(1, mgr.getOverlayCount());

        mgr.removeOverlay("paint_a"); // kruisje = expliciet
        assertEquals(0, mgr.getOverlayCount());
        assertFalse(mgr.isPinned("paint_a"));

        mgr.addOverlay(overlay("paint_c", 40, 10));
        mgr.setPinned("paint_c", true);
        mgr.setHidden("paint_c", true);
        mgr.resetSession(); // join/disconnect
        assertEquals(0, mgr.getOverlayCount());
        assertFalse(mgr.isPinned("paint_c"));
        assertFalse(mgr.isHidden("paint_c"));
        assertNull(mgr.getLevelY());
    }

    @Test
    void hiddenAndPinnedNeedAnExistingOverlay() {
        mgr.setHidden("ghost", true);
        mgr.setPinned("ghost", true);
        assertFalse(mgr.isHidden("ghost"));
        assertFalse(mgr.isPinned("ghost"));
    }

    @Test
    void rowStepperMovesOnlyThatOverlay() {
        mgr.addOverlay(overlay("paint_a", 40, 10));
        mgr.addOverlay(overlay("paint_b", 55, 10));
        mgr.adjustOverlayY("paint_b", 3);
        assertEquals(58, mgr.getOverlay("paint_b").y());
        assertEquals(40, mgr.getOverlay("paint_a").y());
        assertEquals(40, mgr.getLevelY(), "het gedeelde niveau blijft");
    }

    @Test
    void menuStateRowsStripTheSiteCountAndSortMarkerFirst() {
        mgr.addOverlay(overlay("paint_b", 40, 10)); // label = id
        mgr.addOverlay(new OverlayData("paint_x", "paint", new OverlayData.BlockPos[]{new OverlayData.BlockPos(0, 0)},
                40, 1, 2, 3, 80, "Oranje wol (123)", "orange_wool"));
        mgr.addOverlay(new OverlayData("click", "click", new OverlayData.BlockPos[]{new OverlayData.BlockPos(0, 0)},
                44, 74, 222, 128, 80, "", null));
        mgr.setPinned("paint_x", true);

        OverlayMenuState state = new OverlayMenuState(mgr, OverlayConfig.getInstance());
        var rows = state.rows();
        assertEquals(3, rows.size());
        assertEquals("click", rows.get(0).id());
        assertTrue(rows.get(0).isMarker());
        assertEquals("Marker", rows.get(0).name());
        assertEquals("Oranje wol", rows.get(1).name());
        assertEquals(1, rows.get(1).blocks());
        assertTrue(rows.get(1).pinned());
        assertEquals("paint_b", rows.get(2).name());
        for (var r : rows) assertEquals(40, r.renderY());
        assertEquals(44, rows.get(0).ownY());
    }
}
