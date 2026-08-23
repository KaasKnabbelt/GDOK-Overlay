package nl.geocraft.overlay;

/**
 * Immutable record for a single overlay sent from GDOK.
 * The y field is the Minecraft Y level (from AHN height).
 */
public record OverlayData(
        String id,
        String category,
        BlockPos[] blocks,
        int y,
        int red,
        int green,
        int blue,
        int alpha,
        String label,
        String tag
) {

    /**
     * Stable id of the click marker the site sends for the clicked cell (one block, no
     * block tag). The renderer gives this overlay priority: other overlays on the same
     * level skip the marker's cell so it never drowns in their surface.
     */
    public static final String CLICK_ID = "click";

    /**
     * Return a copy with a different Y level.
     */
    public OverlayData withY(int newY) {
        return new OverlayData(id, category, blocks, newY, red, green, blue, alpha, label, tag);
    }

    /**
     * Simple block position (Minecraft coordinates).
     */
    public record BlockPos(int x, int z) {
    }
}
