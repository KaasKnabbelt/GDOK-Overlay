package nl.geocraft.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The link to the viewer: the /waarbenik line, WGS84 to RD, and the player's own block. */
class GdokLinkTest {

    // RD from proj4 with the definition of the viewer (player-marker.js), 31 October 2026. The
    // approximation is 0.3 m off everywhere; the edges of the country are the real test.
    private static final double[][] PROJ4 = {
            // lat, lon, rdX, rdY
            {52.37453253, 4.88352559, 120700.91, 487525.73},  // Westertoren
            {52.09063, 5.12142, 136785.07, 455852.35},       // Utrecht
            {51.84, 5.86, 187583.62, 428040.68},             // Nijmegen
            {51.5, 3.6, 30911.44, 391629.93},                // Zeeland
            {53.2, 4.8, 115759.51, 579421.25},               // Texel
            {50.8, 5.7, 177053.26, 312280.65},               // Maastricht
            {53.4, 6.9, 255621.04, 602578.16},               // Groningen-oost
            {50.75, 6.0, 198250.29, 306851.99},              // Vaals
            {53.45, 3.4, 22982.61, 608908.58},               // Noordzee, noordwest
    };
    private static final double ALLOWED_M = 0.5;

    @Test
    void viewerUrlUsesBlockCoordinates() {
        assertEquals("https://gdok.nl/viewer#mc=120700,-487526", GdokLink.viewerUrl(120700, -487526));
    }

    @Test
    void wgs84ToRdMatchesProj4() {
        for (double[] p : PROJ4) {
            double[] rd = GdokLink.wgs84ToRd(p[0], p[1]);
            double d = Math.hypot(rd[0] - p[2], rd[1] - p[3]);
            assertTrue(d <= ALLOWED_M, "afwijking " + d + " m bij " + p[0] + ", " + p[1]);
        }
    }

    @Test
    void readsTheGoogleMapsLineOfWaarbenik() {
        int[] b = GdokLink.blockFromWaarbenik("Klik hier voor een Google Maps link naar de coords: 52.37453253, 4.88352559");
        assertNotNull(b);
        // Block X = RD x, block Z = -RD y.
        assertEquals(120700, b[0]);
        assertEquals(-487526, b[1]);
    }

    @Test
    void acceptsCommaDecimalsAndExtraText() {
        int[] dot = GdokLink.blockFromWaarbenik("Klik hier voor een Google Maps link naar de coords: 52.09063, 5.12142");
        int[] comma = GdokLink.blockFromWaarbenik("[GeoNavigate] Klik hier voor een Google Maps link naar de coords: 52,09063, 5,12142 ");
        assertNotNull(dot);
        assertArrayEquals(dot, comma);
    }

    @Test
    void ignoresOtherLinesAndPlacesOutsideTheNetherlands() {
        assertNull(GdokLink.blockFromWaarbenik("[GeoNavigate] Je bent hier: Dam 1, Amsterdam"));
        assertNull(GdokLink.blockFromWaarbenik("Klik hier voor een Google Maps link naar de coords: 48.85, 2.35"));
        assertNull(GdokLink.blockFromWaarbenik(null));
        assertNull(GdokLink.blockFromWaarbenik("Klik hier voor een Google Maps link naar de coords: abc"));
    }

    @Test
    void thePlayersOwnBlockWinsNearby() {
        int[] line = {120700, -487526};
        // A few metres off (the server rounds, the player stepped aside): the player's block, exact.
        assertArrayEquals(new int[]{120703, -487522}, GdokLink.resolve(line, 120703.6, -487521.2));
        // Just beyond 10 m: the line's point.
        assertArrayEquals(line, GdokLink.resolve(line, 120711.5, -487526));
        // Far away (the line is about somewhere else): the line's point.
        assertArrayEquals(line, GdokLink.resolve(line, 130000, -480000));
        // No usable line: the player's block.
        assertArrayEquals(new int[]{-3, 7}, GdokLink.resolve(null, -2.5, 7.9));
    }
}
