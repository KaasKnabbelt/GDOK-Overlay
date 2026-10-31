package nl.geocraft.overlay.markers;

import java.util.List;
import java.util.Set;

/**
 * World access for the marker scanner and the foreign check, implemented per Minecraft module.
 * The code in {@code common/} only sees these coarse block kinds and hits, so the height, status
 * and check logic is the same for every Minecraft version and testable without a game.
 */
public interface MarkerProbe {

    /** Air (also cave air). */
    int AIR = 0;
    /** Something a placed block replaces: grass, flowers, ferns, a snow layer. */
    int REPLACEABLE = 1;
    /** Water, also waterlogged blocks. */
    int WATER = 2;
    /** Log, wood, leaves, or one of the plan's trunk blocks: part of a tree. */
    int TREE = 3;
    /** Everything else: ground, paths, buildings. */
    int SOLID = 4;
    /** Exactly the marker block that was asked about. */
    int MARKER = 5;

    /**
     * Called before the markers of one plan are scanned; the module resolves the names to its
     * own block objects once instead of per lookup.
     */
    void beginPlan(List<String> markerBlocks, Set<String> trunkBlocks);

    boolean isChunkLoaded(int chunkX, int chunkZ);

    /**
     * The kind of block at a position. {@code markerBlock} is an index into the list given to
     * {@link #beginPlan}: when the block there is exactly that marker, the answer is {@link #MARKER}.
     */
    int kind(int x, int y, int z, int markerBlock);

    /** First free Y above the surface in this column (heightmap), for markers without a site Y. */
    int surfaceY(int x, int z);

    /** The block types {@link ForeignCheck} looks for; the module resolves them to its block objects. */
    void beginTargets(List<String> blocks);

    /**
     * Report every block of the chunk, all heights, that is one of the targets ({@code target} =
     * index into the list given to {@link #beginTargets}). Check each section's palette first.
     */
    void scanChunk(int chunkX, int chunkZ, ForeignCheck.Hit out);
}
