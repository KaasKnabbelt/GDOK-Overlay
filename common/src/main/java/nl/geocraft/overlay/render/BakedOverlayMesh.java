package nl.geocraft.overlay.render;

import nl.geocraft.overlay.OverlayData;

import java.util.Arrays;
import java.util.Objects;

/**
 * Pre-built geometry for one overlay. Built once per mutation by {@link OverlayMeshBuilder},
 * then replayed every frame into a {@link VertexSink} with nothing more than a translation.
 *
 * <p>Blocks are grouped into buckets of one chunk column (16x16). Per bucket the slab faces
 * are stored as an interleaved vertex array relative to the bucket origin with Y relative to
 * the overlay level, plus per-block quad ranges so that blocks hidden by a real block can be
 * skipped individually. The flat border quads (drawn at y+1 on top of occupied positions)
 * are kept separately. A 256-bit occupancy bitset per bucket says which blocks are currently
 * occupied by a real block; the scanner ({@link OccupancyUpdater}) keeps it up to date.</p>
 *
 * <p>The overlay Y level is <em>not</em> baked in: it is passed to the replay methods, so
 * PageUp/PageDown, level changes and menu steppers only cost an occupancy rescan.</p>
 *
 * <p>Cache identity (see {@link #matches}): the {@code blocks()} array reference, the tag, the
 * render mode and the UV snapshot of the sprite. {@link OverlayData#withY} reuses the array,
 * so a Y change never invalidates the geometry; a resource reload changes the UVs.</p>
 */
public final class BakedOverlayMesh {

    /** One chunk column worth of overlay blocks. */
    public static final class Bucket {
        public final int chunkX, chunkZ;
        public final int originX, originZ;
        public final double centerX, centerZ;

        /** Absolute block coordinates, in the same order as the quad ranges. */
        final int[] blockX, blockZ;
        /** Interleaved slab vertices: x, y, z, u, v (5 floats), relative to origin / overlay level. */
        final float[] verts;
        /** Face direction per slab quad (VertexSink.FACE_*). */
        final byte[] quadFace;
        /** Slab quad range per block: quads [quadStart[i], quadStart[i+1]). */
        final int[] quadStart;
        /** Interleaved border vertices: x, y, z (3 floats), relative to origin / overlay level. */
        final float[] borderVerts;
        /** Border quad range per block. */
        final int[] borderStart;

        /** Occupancy bitset, bit i = block i is occupied by a real block. */
        final long[] occupied = new long[4];
        /** True when the bitset must be rescanned. New buckets start dirty. */
        boolean dirty = true;
        /** Cached "any bit set" so the border pass can skip clean buckets quickly. */
        boolean anyOccupied = false;

        Bucket(int chunkX, int chunkZ, int[] blockX, int[] blockZ,
               float[] verts, byte[] quadFace, int[] quadStart,
               float[] borderVerts, int[] borderStart) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.originX = chunkX << 4;
            this.originZ = chunkZ << 4;
            this.centerX = originX + 8.0;
            this.centerZ = originZ + 8.0;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.verts = verts;
            this.quadFace = quadFace;
            this.quadStart = quadStart;
            this.borderVerts = borderVerts;
            this.borderStart = borderStart;
        }

        public int blockCount() {
            return blockX.length;
        }

        public int blockX(int i) {
            return blockX[i];
        }

        public int blockZ(int i) {
            return blockZ[i];
        }

        public int slabQuadCount() {
            return quadFace.length;
        }

        public int borderQuadCount() {
            return borderVerts.length / 12;
        }

        public boolean isDirty() {
            return dirty;
        }

        public boolean isOccupied(int i) {
            return (occupied[i >> 6] & (1L << (i & 63))) != 0;
        }

        /**
         * Rescan every block of this bucket at the given Y. Called by the scanner on the
         * client thread; the render pass runs on the same thread, so no synchronisation.
         */
        public void rescan(OccupancyProbe probe, int y) {
            long w0 = 0, w1 = 0, w2 = 0, w3 = 0;
            int n = blockX.length;
            for (int i = 0; i < n; i++) {
                if (probe.isOccupied(blockX[i], y, blockZ[i])) {
                    long bit = 1L << (i & 63);
                    switch (i >> 6) {
                        case 0 -> w0 |= bit;
                        case 1 -> w1 |= bit;
                        case 2 -> w2 |= bit;
                        default -> w3 |= bit;
                    }
                }
            }
            occupied[0] = w0;
            occupied[1] = w1;
            occupied[2] = w2;
            occupied[3] = w3;
            anyOccupied = (w0 | w1 | w2 | w3) != 0;
            dirty = false;
        }

        void clearOccupancy() {
            Arrays.fill(occupied, 0L);
            anyOccupied = false;
            dirty = true;
        }
    }

    private final OverlayData.BlockPos[] blocksRef;
    private final String tag;
    private final RenderMode mode;
    private final float u0, u1, v0, v1;
    private final Bucket[] buckets;
    /** Chunk keys of {@link #buckets}, sorted ascending (same order as the buckets). */
    private final long[] bucketKeys;
    private final int blockCount;

    /** Y level the occupancy bits were scanned for; {@code Integer.MIN_VALUE} = never. */
    private int scannedY = Integer.MIN_VALUE;

    BakedOverlayMesh(OverlayData.BlockPos[] blocksRef, String tag, RenderMode mode,
                     float u0, float u1, float v0, float v1,
                     Bucket[] buckets, long[] bucketKeys, int blockCount) {
        this.blocksRef = blocksRef;
        this.tag = tag;
        this.mode = mode;
        this.u0 = u0;
        this.u1 = u1;
        this.v0 = v0;
        this.v1 = v1;
        this.buckets = buckets;
        this.bucketKeys = bucketKeys;
        this.blockCount = blockCount;
    }

    // -- Identity -------------------------------------------------

    /**
     * True when this mesh still represents the overlay under the given mode and sprite UVs
     * ({@code uv} = {u0, u1, v0, v1}, or {@code null} to skip the UV check).
     */
    public boolean matches(OverlayData overlay, RenderMode mode, float[] uv) {
        return overlay.blocks() == blocksRef
                && Objects.equals(overlay.tag(), tag)
                && this.mode == mode
                && (uv == null || (uv[0] == u0 && uv[1] == u1 && uv[2] == v0 && uv[3] == v1));
    }

    public RenderMode mode() {
        return mode;
    }

    public String tag() {
        return tag;
    }

    /** Overlays without a block tag (the click marker) are always drawn tinted (no texture to show). */
    public boolean isTinted() {
        return mode == RenderMode.CARPET || tag == null;
    }

    public int blockCount() {
        return blockCount;
    }

    public Bucket[] buckets() {
        return buckets;
    }

    // -- Occupancy bookkeeping ------------------------------------

    /**
     * Tell the mesh which Y it is rendered at. A change invalidates the occupancy bits:
     * they are cleared (blocks render as unoccupied until rescanned) and every bucket is
     * marked dirty.
     */
    public void setRenderY(int y) {
        if (y == scannedY) return;
        scannedY = y;
        for (Bucket b : buckets) b.clearOccupancy();
    }

    public int renderY() {
        return scannedY;
    }

    public void markAllDirty() {
        for (Bucket b : buckets) b.dirty = true;
    }

    /** Mark the bucket for the given chunk dirty, if this overlay has blocks there. */
    public void markChunkDirty(int chunkX, int chunkZ) {
        int idx = Arrays.binarySearch(bucketKeys, chunkKey(chunkX, chunkZ));
        if (idx >= 0) buckets[idx].dirty = true;
    }

    public boolean hasDirtyBuckets() {
        for (Bucket b : buckets) if (b.dirty) return true;
        return false;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // -- Replay ---------------------------------------------------

    /**
     * "No skip position" sentinel for the replay methods ({@code Integer.MIN_VALUE} is never
     * a real block coordinate: the world border sits at ±30M).
     */
    public static final int SKIP_NONE = Integer.MIN_VALUE;

    /** See {@link #replaySlabs(VertexSink, ViewFrustum, double, double, double, int, double, int, int)}. */
    public void replaySlabs(VertexSink sink, ViewFrustum frustum, double camX, double camY, double camZ, int renderY, double maxDist) {
        replaySlabs(sink, frustum, camX, camY, camZ, renderY, maxDist, SKIP_NONE, 0);
    }

    /**
     * Emit the slab faces of every visible block within {@code maxDist} of the camera and,
     * when {@code frustum} is given, inside the view frustum (tested per bucket).
     * Occupied blocks are skipped (they get a border instead, see {@link #replayBorders}).
     *
     * <p>{@code skipX}/{@code skipZ} (or {@code skipX == SKIP_NONE} for none) name one block
     * position that is left out entirely: the click marker renders there instead, and it
     * must not disappear inside another overlay on the same level (marker priority).</p>
     */
    public void replaySlabs(VertexSink sink, ViewFrustum frustum, double camX, double camY, double camZ, int renderY, double maxDist,
                            int skipX, int skipZ) {
        double cullSq = square(maxDist + OverlayGeometry.BUCKET_CULL_MARGIN);
        float oy = (float) (renderY - camY) + OverlayGeometry.Y_OFFSET;
        double sphereY = renderY + 0.5;
        for (Bucket b : buckets) {
            double dx = b.centerX - camX, dz = b.centerZ - camZ;
            if (dx * dx + dz * dz > cullSq) continue;
            if (frustum != null && !frustum.intersectsSphere(b.centerX, sphereY, b.centerZ, ViewFrustum.BUCKET_RADIUS)) continue;
            boolean maySkip = skipX != SKIP_NONE && (skipX >> 4) == b.chunkX && (skipZ >> 4) == b.chunkZ;
            float ox = (float) (b.originX - camX);
            float oz = (float) (b.originZ - camZ);
            float[] v = b.verts;
            byte[] faces = b.quadFace;
            int[] qs = b.quadStart;
            int n = b.blockX.length;
            boolean anyOcc = b.anyOccupied;
            for (int i = 0; i < n; i++) {
                if (anyOcc && b.isOccupied(i)) continue;
                if (maySkip && b.blockX[i] == skipX && b.blockZ[i] == skipZ) continue;
                int qEnd = qs[i + 1];
                for (int q = qs[i]; q < qEnd; q++) {
                    int face = faces[q];
                    int p = q * 20;
                    sink.vertex(v[p] + ox, v[p + 1] + oy, v[p + 2] + oz, v[p + 3], v[p + 4], face);
                    sink.vertex(v[p + 5] + ox, v[p + 6] + oy, v[p + 7] + oz, v[p + 8], v[p + 9], face);
                    sink.vertex(v[p + 10] + ox, v[p + 11] + oy, v[p + 12] + oz, v[p + 13], v[p + 14], face);
                    sink.vertex(v[p + 15] + ox, v[p + 16] + oy, v[p + 17] + oz, v[p + 18], v[p + 19], face);
                }
            }
        }
    }

    /** See {@link #replayBorders(VertexSink, ViewFrustum, double, double, double, int, double, int, int)}. */
    public void replayBorders(VertexSink sink, ViewFrustum frustum, double camX, double camY, double camZ, int renderY, double maxDist) {
        replayBorders(sink, frustum, camX, camY, camZ, renderY, maxDist, SKIP_NONE, 0);
    }

    /**
     * Emit the flat border quads (at y+1) for every occupied block within range.
     * {@code skipX}/{@code skipZ} as in {@link #replaySlabs}: the click marker draws its own
     * border on that position, so other overlays leave it out.
     */
    public void replayBorders(VertexSink sink, ViewFrustum frustum, double camX, double camY, double camZ, int renderY, double maxDist,
                              int skipX, int skipZ) {
        double cullSq = square(maxDist + OverlayGeometry.BUCKET_CULL_MARGIN);
        float oy = (float) (renderY - camY) + OverlayGeometry.Y_OFFSET;
        double sphereY = renderY + 1.0;
        for (Bucket b : buckets) {
            if (!b.anyOccupied) continue;
            double dx = b.centerX - camX, dz = b.centerZ - camZ;
            if (dx * dx + dz * dz > cullSq) continue;
            if (frustum != null && !frustum.intersectsSphere(b.centerX, sphereY, b.centerZ, ViewFrustum.BUCKET_RADIUS)) continue;
            boolean maySkip = skipX != SKIP_NONE && (skipX >> 4) == b.chunkX && (skipZ >> 4) == b.chunkZ;
            float ox = (float) (b.originX - camX);
            float oz = (float) (b.originZ - camZ);
            float[] v = b.borderVerts;
            int[] bs = b.borderStart;
            int n = b.blockX.length;
            for (int i = 0; i < n; i++) {
                if (!b.isOccupied(i)) continue;
                if (maySkip && b.blockX[i] == skipX && b.blockZ[i] == skipZ) continue;
                int qEnd = bs[i + 1];
                for (int q = bs[i]; q < qEnd; q++) {
                    int p = q * 12;
                    sink.flatVertex(v[p] + ox, v[p + 1] + oy, v[p + 2] + oz);
                    sink.flatVertex(v[p + 3] + ox, v[p + 4] + oy, v[p + 5] + oz);
                    sink.flatVertex(v[p + 6] + ox, v[p + 7] + oy, v[p + 8] + oz);
                    sink.flatVertex(v[p + 9] + ox, v[p + 10] + oy, v[p + 11] + oz);
                }
            }
        }
    }

    private static double square(double d) {
        return d * d;
    }
}
