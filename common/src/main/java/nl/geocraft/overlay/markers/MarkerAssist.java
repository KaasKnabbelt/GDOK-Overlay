package nl.geocraft.overlay.markers;

import java.util.List;
import java.util.Locale;

/**
 * Help to finish a plan in the game (BOMEN.md §9.8): which plan the player is working on and
 * how many of its markers are still to do, for the bar at the top of the screen (the modules'
 * {@code MarkerHud}). {@link #tick} computes it once per client tick.
 *
 * <p>"Still to do" is the number the site shows as "Nog N": markers still to put down plus the
 * blocked ones (total minus placed, planted and "already a tree").</p>
 *
 * <p>Chosen by Jens on 31 October 2026 from real screenshots (ShowcaseGameTest in fabric-1.21.11):
 * a bar like a boss bar (B1), and a yellow frame through blocks (A2, see
 * {@link MarkerRenderCore#emitSeeThrough}) with beacons once {@link #BEACON_MAX_LEFT} or fewer
 * are left.</p>
 */
public final class MarkerAssist {

    /** The bar shows the nearest visible plan within this distance of its edge (0 = inside). */
    public static final double HUD_RANGE = 48.0;
    /** Frames show through blocks up to this distance from the camera, fading out towards it. */
    public static final double SEE_THROUGH_RANGE = 48.0;
    /**
     * A plan with this many markers or fewer left to put down also gets beacons: with hundreds
     * the sky would be full of them, for the last few they are what you need.
     */
    public static final int BEACON_MAX_LEFT = 10;

    /** What the bar shows. */
    public record Status(String label, int todo, int total) {
        public int done() {
            return total - todo;
        }
    }

    private static Status status;

    private MarkerAssist() {}

    /** The bar of the last tick, or null (no visible plan near). */
    public static Status status() {
        return status;
    }

    public static void tick(MarkerManager manager, double px, double pz) {
        status = compute(manager.layers(), px, pz);
    }

    public static void reset() {
        status = null;
    }

    static Status compute(List<MarkerLayer> layers, double px, double pz) {
        MarkerLayer plan = null;
        double planDistance = Double.MAX_VALUE;
        for (MarkerLayer l : layers) {
            if (l.isHidden() || l.size() == 0) continue;
            double d = PlanArea.distance(l.plan.polygon(), px, pz);
            if (d < planDistance) {
                planDistance = d;
                plan = l;
            }
        }
        if (plan == null || planDistance > HUD_RANGE) return null;
        MarkerLayer.Counts c = plan.counts();
        return new Status(plan.plan.label(), c.toPlace() + c.blocked(), c.total());
    }

    /** Beacons for this plan: something left to put down, but no more than {@link #BEACON_MAX_LEFT}. */
    public static boolean beacons(MarkerLayer layer) {
        int left = layer.counts().toPlace();
        return left > 0 && left <= BEACON_MAX_LEFT;
    }

    /** Above the bar (legacy section codes, as in Messages): "St. Anna: nog 26 van 73". */
    public static String barTitle(Status s) {
        if (s.todo() == 0) return "§f" + s.label() + "§7: alles staat";
        return "§f" + s.label() + "§7: §fnog " + nl(s.todo()) + " §7van " + nl(s.total());
    }

    private static String nl(int n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', '.');
    }
}
