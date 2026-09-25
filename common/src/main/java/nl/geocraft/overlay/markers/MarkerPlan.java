package nl.geocraft.overlay.markers;

import java.util.List;
import java.util.Set;

/**
 * A marker layer as the GDOK viewer sends it: one tree plan. Every marker is a block the
 * player puts on the ground; a CVDK member later turns every marker into a tree with
 * {@code /geotools paste}. Immutable; built by {@link MarkerPlanParser}.
 *
 * <p>Coordinates are Minecraft block coordinates. The polygon is the plan area in the same
 * space, as doubles ({@code [x, z]}): the site converts RD with {@code x = rdX}, {@code z = -rdY},
 * so a block column is {@code (floor(x), floor(z))}.</p>
 *
 * @param blocks      marker block ids without namespace ({@code red_wool}); markers refer to them by index
 * @param sets        tree sets ({@code bomenalles}), in the order the paste commands are issued
 * @param defaultSet  the set geotools uses without {@code -s}; its paste commands leave the flag out
 * @param trunkBlocks trunk blocks of the chosen trees: at a marker position they mean "a tree
 *                    stands here" (planted, or already there), not "something else is in the way"
 */
public record MarkerPlan(
        String id,
        String label,
        double[][] polygon,
        List<String> blocks,
        List<String> sets,
        String defaultSet,
        Set<String> trunkBlocks,
        Marker[] markers
) {

    /** "No height from the site" (the site had no AHN value for this spot). */
    public static final int NO_Y = Integer.MIN_VALUE;

    /**
     * One marker.
     *
     * @param siteY expected Y of the marker (the first free block above the ground, from the
     *              AHN), or {@link #NO_Y}; the scanner corrects it to the real surface
     * @param block index into {@link MarkerPlan#blocks()}
     * @param set   index into {@link MarkerPlan#sets()}
     */
    public record Marker(int x, int z, int siteY, int block, int set) {
    }

    public String blockName(Marker m) {
        return blocks.get(m.block());
    }
}
