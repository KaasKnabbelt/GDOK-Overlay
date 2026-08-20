package nl.geocraft.overlay.render;

/**
 * World access needed by the occupancy scanner: is there a real (non-air) block at the
 * given position? Implemented per Minecraft module. Must return {@code false} for
 * positions in chunks that are not loaded; the chunk-load event re-marks those buckets dirty.
 */
@FunctionalInterface
public interface OccupancyProbe {
    boolean isOccupied(int x, int y, int z);
}
