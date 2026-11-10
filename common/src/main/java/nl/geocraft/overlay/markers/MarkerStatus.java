package nl.geocraft.overlay.markers;

/** What the scanner found at a marker spot (BOMEN.md §9.2). */
public enum MarkerStatus {
    /** Not scanned yet: the chunk was never loaded. Drawn as a ghost at the site's Y. */
    UNKNOWN,
    /** Free spot on the ground: the ghost shows which block goes here. */
    EMPTY,
    /** The right marker block stands on the spot. */
    PLACED,
    /** Water, or covered by something solid (a roof, a bridge): no tree can go here. */
    BLOCKED,
    /** A trunk or leaves on the spot while the marker was never seen: a tree already stands there. */
    TREE,
    /** A trunk where the marker stood before: geotools turned it into a tree. */
    PLANTED,
    /**
     * A loose block of the player's own choice on the spot. Only found while "Rechtsklik plaatst"
     * is off (see {@link MarkerScanner}); counts as placed, but is not part of the paste commands.
     */
    OWN;

    private static final MarkerStatus[] VALUES = values();

    public static MarkerStatus of(int ordinal) {
        return VALUES[ordinal];
    }

    /** Still needs a marker block from the player. */
    public boolean needsMarker() {
        return this == UNKNOWN || this == EMPTY;
    }

    /** A block for this tree stands on the spot: the plan's marker, or the player's own block. */
    public boolean isPlaced() {
        return this == PLACED || this == OWN;
    }
}
