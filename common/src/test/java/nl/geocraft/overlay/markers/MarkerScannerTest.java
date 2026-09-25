package nl.geocraft.overlay.markers;

import org.junit.jupiter.api.Test;

import static nl.geocraft.overlay.markers.MarkerTestSupport.FakeWorld;
import static nl.geocraft.overlay.markers.MarkerTestSupport.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where does a marker really go, and what stands there (BOMEN.md §9.2)? */
class MarkerScannerTest {

    private final FakeWorld world = new FakeWorld();
    private final int[] buf = new int[MarkerScanner.bufferSize()];

    private MarkerLayer scanOne(int siteY) {
        MarkerLayer layer = new MarkerLayer(plan(new int[]{5, 5, siteY, 0, 0}));
        world.beginPlan(layer.plan.blocks(), layer.plan.trunkBlocks());
        MarkerScanner.scan(layer, 0, world, buf);
        return layer;
    }

    @Test
    void freeGroundIsEmptyOnTheFirstAirBlock() {
        MarkerLayer l = scanOne(64);
        assertEquals(MarkerStatus.EMPTY, l.status(0));
        assertEquals(64, l.y(0));
    }

    @Test
    void theSpotIsFoundWhenTheSiteIsOffByAFewBlocks() {
        MarkerLayer low = scanOne(61);
        assertEquals(64, low.y(0), "site te laag");
        MarkerLayer high = scanOne(70);
        assertEquals(64, high.y(0), "site te hoog");
        assertEquals(MarkerStatus.EMPTY, high.status(0));
    }

    @Test
    void aPlantOnTheSpotIsStillFree() {
        world.set(5, 64, 5, "short_grass");
        assertEquals(MarkerStatus.EMPTY, scanOne(64).status(0));
    }

    @Test
    void theMarkerBlockOnTheSpotIsPlaced() {
        world.set(5, 64, 5, "red_wool");
        MarkerLayer l = scanOne(64);
        assertEquals(MarkerStatus.PLACED, l.status(0));
        assertEquals(64, l.y(0));
    }

    @Test
    void anotherBlockThanTheMarkerIsNotPlaced() {
        world.set(5, 64, 5, "lime_wool"); // solid, but not this marker: the spot moves up
        MarkerLayer l = scanOne(64);
        assertEquals(MarkerStatus.EMPTY, l.status(0));
        assertEquals(65, l.y(0));
    }

    @Test
    void waterIsBlocked() {
        world.set(5, 64, 5, "water");
        world.set(5, 65, 5, "water");
        assertEquals(MarkerStatus.BLOCKED, scanOne(64).status(0));
    }

    @Test
    void aRoofAboveIsBlocked() {
        world.set(5, 68, 5, "stone"); // a bridge deck four blocks above the ground
        MarkerLayer l = scanOne(64);
        assertEquals(MarkerStatus.BLOCKED, l.status(0));
        assertEquals(64, l.y(0), "de dichtste plek blijft onder de brug, niet op het dek");
    }

    @Test
    void leavesAboveDoNotBlock() {
        world.set(5, 70, 5, "oak_leaves"); // a neighbouring crown hangs over
        assertEquals(MarkerStatus.EMPTY, scanOne(64).status(0));
    }

    @Test
    void aTrunkOnTheSpotIsAnExistingTree() {
        world.set(5, 64, 5, "oak_log");
        world.set(5, 65, 5, "oak_log");
        assertEquals(MarkerStatus.TREE, scanOne(64).status(0));
    }

    @Test
    void aTrunkWhereTheMarkerStoodIsPlanted() {
        MarkerLayer l = new MarkerLayer(plan(new int[]{5, 5, 64, 0, 0}));
        world.beginPlan(l.plan.blocks(), l.plan.trunkBlocks());
        world.set(5, 64, 5, "red_wool");
        MarkerScanner.scan(l, 0, world, buf);
        assertEquals(MarkerStatus.PLACED, l.status(0));

        // geotools puts the lowest trunk block (here a fence, one of the plan's trunks) on the spot
        world.set(5, 64, 5, "spruce_fence");
        world.set(5, 65, 5, "spruce_fence");
        world.set(5, 66, 5, "oak_leaves");
        MarkerScanner.scan(l, 0, world, buf);
        assertEquals(MarkerStatus.PLANTED, l.status(0));
    }

    @Test
    void anUnloadedChunkKeepsTheLastStatus() {
        world.unloaded.add(MarkerLayer.chunkKey(0, 0));
        MarkerLayer l = scanOne(64);
        assertEquals(MarkerStatus.UNKNOWN, l.status(0));
        assertEquals(64, l.y(0), "de site-Y blijft staan");
    }

    @Test
    void withoutASiteYTheHeightmapIsUsed() {
        MarkerLayer l = scanOne(Integer.MIN_VALUE);
        assertEquals(MarkerStatus.EMPTY, l.status(0));
        assertEquals(64, l.y(0));
    }
}
