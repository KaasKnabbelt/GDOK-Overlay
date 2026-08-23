package nl.geocraft.overlay.render;

import nl.geocraft.overlay.OverlayData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static nl.geocraft.overlay.render.VertexSink.FACE_DOWN;
import static nl.geocraft.overlay.render.VertexSink.FACE_EAST;
import static nl.geocraft.overlay.render.VertexSink.FACE_NORTH;
import static nl.geocraft.overlay.render.VertexSink.FACE_SOUTH;
import static nl.geocraft.overlay.render.VertexSink.FACE_UP;
import static nl.geocraft.overlay.render.VertexSink.FACE_WEST;

/**
 * Builds a {@link BakedOverlayMesh} from an overlay: pure geometry, no Minecraft types.
 *
 * <p>Neighbour lookups use a sorted {@code long[]} of packed positions and
 * {@link Arrays#binarySearch}: no boxing, no dependencies. The top and bottom faces are
 * always emitted (an overlay can float above the player, so the underside must be closed
 * too); side faces only where there is no overlay neighbour (same rule as the pre-1.1.0
 * renderer, so the face culling is identical). Carpet mode gives a 0.1-high slab with the
 * side V coordinates scaled to the height; full-block mode a 1.0-high block.</p>
 */
public final class OverlayMeshBuilder {

    private OverlayMeshBuilder() {}

    /**
     * @param uv sprite UVs as {u0, u1, v0, v1}
     */
    public static BakedOverlayMesh build(OverlayData overlay, RenderMode mode, float[] uv) {
        OverlayData.BlockPos[] blocks = overlay.blocks();
        float u0 = uv[0], u1 = uv[1], v0 = uv[2], v1 = uv[3];
        RenderMode effectiveMode = overlay.tag() == null ? RenderMode.CARPET : mode;
        float h = OverlayGeometry.heightFor(effectiveMode);

        // 1. Sorted, de-duplicated packed positions (x-major) for neighbour lookups.
        long[] sorted = new long[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            sorted[i] = packPos(blocks[i].x(), blocks[i].z());
        }
        Arrays.sort(sorted);
        int n = dedupe(sorted);

        // 2. Group into chunk-column buckets. Because the array is x-major, chunkX is
        //    monotonic; within one chunkX run we re-sort z-major so chunkZ is monotonic too,
        //    which makes every (chunkX, chunkZ) bucket a contiguous range.
        long[] grouped = Arrays.copyOf(sorted, n);
        int runStart = 0;
        while (runStart < n) {
            int cx = unpackX(grouped[runStart]) >> 4;
            int runEnd = runStart + 1;
            while (runEnd < n && (unpackX(grouped[runEnd]) >> 4) == cx) runEnd++;
            for (int i = runStart; i < runEnd; i++) {
                long p = grouped[i];
                grouped[i] = packPos(unpackZ(p), unpackX(p)); // z-major within the run
            }
            Arrays.sort(grouped, runStart, runEnd);
            for (int i = runStart; i < runEnd; i++) {
                long p = grouped[i];
                grouped[i] = packPos(unpackZ(p), unpackX(p)); // back to (x, z)
            }
            runStart = runEnd;
        }

        // 3. Emit one bucket per contiguous (chunkX, chunkZ) range.
        List<BakedOverlayMesh.Bucket> buckets = new ArrayList<>();
        FloatBuf verts = new FloatBuf(64 * 20);
        ByteBuf faces = new ByteBuf(64);
        FloatBuf border = new FloatBuf(16 * 12);
        int bStart = 0;
        while (bStart < n) {
            int cx = unpackX(grouped[bStart]) >> 4;
            int cz = unpackZ(grouped[bStart]) >> 4;
            int bEnd = bStart + 1;
            while (bEnd < n
                    && (unpackX(grouped[bEnd]) >> 4) == cx
                    && (unpackZ(grouped[bEnd]) >> 4) == cz) {
                bEnd++;
            }
            buckets.add(buildBucket(grouped, bStart, bEnd, cx, cz, sorted, n, h,
                    u0, u1, v0, v1, verts, faces, border));
            bStart = bEnd;
        }

        BakedOverlayMesh.Bucket[] arr = buckets.toArray(new BakedOverlayMesh.Bucket[0]);
        Arrays.sort(arr, (a, b) -> Long.compare(
                BakedOverlayMesh.chunkKey(a.chunkX, a.chunkZ),
                BakedOverlayMesh.chunkKey(b.chunkX, b.chunkZ)));
        long[] keys = new long[arr.length];
        for (int i = 0; i < arr.length; i++) {
            keys[i] = BakedOverlayMesh.chunkKey(arr[i].chunkX, arr[i].chunkZ);
        }
        return new BakedOverlayMesh(blocks, overlay.tag(), mode, u0, u1, v0, v1, arr, keys, n);
    }

    private static BakedOverlayMesh.Bucket buildBucket(long[] grouped, int from, int to, int cx, int cz,
                                                       long[] sorted, int n, float h,
                                                       float u0, float u1, float v0, float v1,
                                                       FloatBuf verts, ByteBuf faces, FloatBuf border) {
        int count = to - from;
        int[] bx = new int[count];
        int[] bz = new int[count];
        int[] quadStart = new int[count + 1];
        int[] borderStart = new int[count + 1];
        verts.clear();
        faces.clear();
        border.clear();

        int originX = cx << 4, originZ = cz << 4;
        float vSide0 = v0;
        float vSide1 = v0 + (v1 - v0) * h;
        float w = OverlayGeometry.BORDER_WIDTH;

        for (int i = 0; i < count; i++) {
            long p = grouped[from + i];
            int ax = unpackX(p), az = unpackZ(p);
            bx[i] = ax;
            bz[i] = az;
            quadStart[i] = faces.size;
            borderStart[i] = border.size / 12;

            boolean hasNorth = contains(sorted, n, ax, az - 1);
            boolean hasSouth = contains(sorted, n, ax, az + 1);
            boolean hasWest = contains(sorted, n, ax - 1, az);
            boolean hasEast = contains(sorted, n, ax + 1, az);

            float x = ax - originX;
            float z = az - originZ;
            float y0 = 0f, y1 = h;

            // Top face (always)
            faces.add(FACE_UP);
            verts.add(x, y1, z, u0, v0);
            verts.add(x, y1, z + 1, u0, v1);
            verts.add(x + 1, y1, z + 1, u1, v1);
            verts.add(x + 1, y1, z, u1, v0);

            // Bottom face (always; counter-clockwise seen from below)
            faces.add(FACE_DOWN);
            verts.add(x, y0, z, u0, v0);
            verts.add(x + 1, y0, z, u1, v0);
            verts.add(x + 1, y0, z + 1, u1, v1);
            verts.add(x, y0, z + 1, u0, v1);

            // Side faces only on exterior edges
            if (!hasNorth) {
                faces.add(FACE_NORTH);
                verts.add(x, y1, z, u1, vSide0);
                verts.add(x + 1, y1, z, u0, vSide0);
                verts.add(x + 1, y0, z, u0, vSide1);
                verts.add(x, y0, z, u1, vSide1);
            }
            if (!hasSouth) {
                faces.add(FACE_SOUTH);
                verts.add(x + 1, y1, z + 1, u1, vSide0);
                verts.add(x, y1, z + 1, u0, vSide0);
                verts.add(x, y0, z + 1, u0, vSide1);
                verts.add(x + 1, y0, z + 1, u1, vSide1);
            }
            if (!hasWest) {
                faces.add(FACE_WEST);
                verts.add(x, y1, z + 1, u1, vSide0);
                verts.add(x, y1, z, u0, vSide0);
                verts.add(x, y0, z, u0, vSide1);
                verts.add(x, y0, z + 1, u1, vSide1);
            }
            if (!hasEast) {
                faces.add(FACE_EAST);
                verts.add(x + 1, y1, z, u1, vSide0);
                verts.add(x + 1, y1, z + 1, u0, vSide0);
                verts.add(x + 1, y0, z + 1, u0, vSide1);
                verts.add(x + 1, y0, z, u1, vSide1);
            }

            // Border quads on top of the real block (y+1), exterior edges only
            float yt = 1f;
            if (!hasNorth) {
                border.add3(x, yt, z);
                border.add3(x, yt, z + w);
                border.add3(x + 1, yt, z + w);
                border.add3(x + 1, yt, z);
            }
            if (!hasSouth) {
                border.add3(x, yt, z + 1 - w);
                border.add3(x, yt, z + 1);
                border.add3(x + 1, yt, z + 1);
                border.add3(x + 1, yt, z + 1 - w);
            }
            if (!hasWest) {
                border.add3(x, yt, z);
                border.add3(x + w, yt, z);
                border.add3(x + w, yt, z + 1);
                border.add3(x, yt, z + 1);
            }
            if (!hasEast) {
                border.add3(x + 1 - w, yt, z);
                border.add3(x + 1, yt, z);
                border.add3(x + 1, yt, z + 1);
                border.add3(x + 1 - w, yt, z + 1);
            }
        }
        quadStart[count] = faces.size;
        borderStart[count] = border.size / 12;

        return new BakedOverlayMesh.Bucket(cx, cz, bx, bz,
                verts.toArray(), faces.toArray(), quadStart,
                border.toArray(), borderStart);
    }

    // -- helpers --------------------------------------------------

    static long packPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int unpackX(long p) {
        return (int) (p >> 32);
    }

    static int unpackZ(long p) {
        return (int) p;
    }

    private static boolean contains(long[] sorted, int n, int x, int z) {
        return Arrays.binarySearch(sorted, 0, n, packPos(x, z)) >= 0;
    }

    /** Removes duplicates from a sorted array in place; returns the new length. */
    private static int dedupe(long[] sorted) {
        if (sorted.length == 0) return 0;
        int n = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[n - 1]) sorted[n++] = sorted[i];
        }
        return n;
    }

    private static final class FloatBuf {
        float[] data;
        int size;

        FloatBuf(int cap) {
            data = new float[cap];
        }

        void clear() {
            size = 0;
        }

        void add(float a, float b, float c, float d, float e) {
            ensure(5);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
            data[size++] = d;
            data[size++] = e;
        }

        void add3(float a, float b, float c) {
            ensure(3);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        private void ensure(int extra) {
            if (size + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
            }
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class ByteBuf {
        byte[] data;
        int size;

        ByteBuf(int cap) {
            data = new byte[cap];
        }

        void clear() {
            size = 0;
        }

        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = (byte) v;
        }

        byte[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
