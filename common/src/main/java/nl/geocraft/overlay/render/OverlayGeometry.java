package nl.geocraft.overlay.render;

/**
 * Shared geometry constants for the overlay renderer. Kept in one place so both
 * Minecraft modules (and the mesh builder) agree on the exact shape of an overlay block.
 */
public final class OverlayGeometry {

    private OverlayGeometry() {}

    /** Height of a carpet-style slab (0.1 = carpet-thin). */
    public static final float CARPET_HEIGHT = 0.1f;

    /** Height of a full-block overlay. */
    public static final float FULLBLOCK_HEIGHT = 1.0f;

    /** Small offset above the Y level to float above terrain and prevent z-fighting. */
    public static final float Y_OFFSET = 0.005f;

    /** Width of the flat border drawn on top of positions that are already occupied by a real block. */
    public static final float BORDER_WIDTH = 0.06f;

    /**
     * Margin added to the view distance when culling a bucket by its centre: a 16×16 bucket
     * has a half-diagonal of ~11.3 blocks, so a bucket whose centre is slightly beyond the
     * view distance can still have blocks inside it.
     */
    public static final double BUCKET_CULL_MARGIN = 12.0;

    public static float heightFor(RenderMode mode) {
        return mode == RenderMode.CARPET ? CARPET_HEIGHT : FULLBLOCK_HEIGHT;
    }
}
