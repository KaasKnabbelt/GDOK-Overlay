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
