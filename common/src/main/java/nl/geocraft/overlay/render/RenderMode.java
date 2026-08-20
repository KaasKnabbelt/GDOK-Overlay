package nl.geocraft.overlay.render;

/**
 * How overlay blocks are drawn in the world.
 *
 * <ul>
 *   <li>{@link #FULLBLOCK}: a full 1×1×1 block with the real block texture, untinted. Default.</li>
 *   <li>{@link #CARPET}: a thin 0.1-high slab, tinted with the overlay colour (the pre-1.1.0 look).</li>
 * </ul>
 *
 * Overlays without a block tag (the click marker) are always drawn as a tinted carpet,
 * whatever the configured mode: there is no block texture to show for them.
 */
public enum RenderMode {
    FULLBLOCK("fullblock"),
    CARPET("carpet");

    private final String configValue;

    RenderMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /** Lenient parse of the config string; anything unknown falls back to {@link #FULLBLOCK}. */
    public static RenderMode fromConfig(String value) {
        if (value != null && "carpet".equalsIgnoreCase(value.trim())) return CARPET;
        return FULLBLOCK;
    }

    public RenderMode next() {
        return this == FULLBLOCK ? CARPET : FULLBLOCK;
    }
}
