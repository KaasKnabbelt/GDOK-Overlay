package nl.geocraft.overlay;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links from the game to the GDOK viewer: {@code https://gdok.nl/viewer#mc=<x>,<z>} opens the
 * viewer on that block (the viewer reads the hash, see docs/viewer-interface.md in the site repo).
 * Used by {@code /gdok} and by the [GDOK] button behind the Google Maps line of {@code /waarbenik}.
 *
 * <p>GeoCraft maps RD one to one: block X = RD x, block Z = -RD y. {@code /waarbenik} (also
 * {@code /wb}, {@code /rgc}) answers with two chat lines; the second reads
 * "Klik hier voor een Google Maps link naar de coords: 52.37453, 4.88353". Those WGS84
 * coordinates are turned back into RD with the approximation of Schreutelkamp and Strang van
 * Hees: 0.3 m from proj4 with the viewer's RD definition, all over the country (GdokLinkTest; a
 * first version had two terms of the y series wrong and was off by up to 7 m at the edges). The
 * line is about where the player stands, so {@link #resolve} takes the player's own block when
 * it lies within {@link #PLAYER_NEAR} of that point: exact, also when the server rounds the
 * coordinates. The conversion only counts when the line is about somewhere else.</p>
 */
public final class GdokLink {

    public static final String VIEWER = "https://gdok.nl/viewer";

    /** The coordinates in the second line of {@code /waarbenik}. Dot or comma as decimal sign. */
    private static final Pattern MAPS_LINE = Pattern.compile(
            "Google Maps link naar de coords:\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*[,;]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)");

    // Roughly the Netherlands plus its waters; outside it the approximation is meaningless.
    private static final double LAT_MIN = 50.0, LAT_MAX = 54.5, LON_MIN = 2.0, LON_MAX = 8.0;

    private GdokLink() {}

    /** The viewer on block (x, z). */
    public static String viewerUrl(int x, int z) {
        return VIEWER + "#mc=" + x + "," + z;
    }

    /**
     * The block (x, z) a {@code /waarbenik} Google Maps line points at, or {@code null} when the
     * text is not that line or the coordinates lie outside the Netherlands.
     */
    public static int[] blockFromWaarbenik(String plain) {
        if (plain == null) return null;
        Matcher m = MAPS_LINE.matcher(plain);
        if (!m.find()) return null;
        double lat = parse(m.group(1)), lon = parse(m.group(2));
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) return null;
        double[] rd = wgs84ToRd(lat, lon);
        return new int[]{(int) Math.floor(rd[0]), (int) Math.floor(-rd[1])};
    }

    /** Within this distance of the converted point the player's own block wins. */
    public static final double PLAYER_NEAR = 10.0;

    /** The block for the link: the player's own block if it is near the line's point, else that point. */
    public static int[] resolve(int[] fromLine, double playerX, double playerZ) {
        int bx = (int) Math.floor(playerX), bz = (int) Math.floor(playerZ);
        if (fromLine == null) return new int[]{bx, bz};
        double d = Math.hypot(fromLine[0] - bx, fromLine[1] - bz);
        return d <= PLAYER_NEAR ? new int[]{bx, bz} : fromLine;
    }

    /** WGS84 (degrees) to RD {x, y} in metres. */
    public static double[] wgs84ToRd(double lat, double lon) {
        double dp = 0.36 * (lat - 52.15517440);
        double dl = 0.36 * (lon - 5.38720621);
        double dp2 = dp * dp, dp3 = dp2 * dp, dl2 = dl * dl, dl3 = dl2 * dl, dl4 = dl3 * dl;
        double x = 155000
                + 190094.945 * dl
                - 11832.228 * dp * dl
                - 114.221 * dp2 * dl
                - 32.391 * dl3
                - 0.705 * dp
                - 2.340 * dp3 * dl
                - 0.608 * dp * dl3
                - 0.008 * dl2
                + 0.148 * dp2 * dl3;
        double y = 463000
                + 309056.544 * dp
                + 3638.893 * dl2
                + 73.077 * dp2
                - 157.984 * dp * dl2
                + 59.788 * dp3
                + 0.433 * dl
                - 6.439 * dp2 * dl2
                - 0.032 * dp * dl
                + 0.092 * dl4
                - 0.054 * dp * dl4;
        return new double[]{x, y};
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** For log lines: "x, z". */
    public static String format(int x, int z) {
        return String.format(Locale.ROOT, "%d, %d", x, z);
    }
}
