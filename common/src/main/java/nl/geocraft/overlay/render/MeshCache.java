package nl.geocraft.overlay.render;

import nl.geocraft.overlay.OverlayData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds one {@link BakedOverlayMesh} per overlay id and reconciles them against the
 * overlay store whenever the store's revision changes. Between reconciliations the render
 * loop iterates {@link #entries()}: a plain list, no hashing per frame.
 *
 * <p>Only ever touched from the client/render thread.</p>
 */
public final class MeshCache {

    /** Looks up the sprite UVs ({u0, u1, v0, v1}) for a block tag; {@code null} tag = default block. */
    @FunctionalInterface
    public interface UvLookup {
        float[] uv(String tag);
    }

    /** An overlay together with its baked mesh, as of the last reconciliation. */
    public static final class Entry {
        public final OverlayData overlay;
        public final BakedOverlayMesh mesh;

        Entry(OverlayData overlay, BakedOverlayMesh mesh) {
            this.overlay = overlay;
            this.mesh = mesh;
        }
    }

    private final Map<String, BakedOverlayMesh> meshes = new HashMap<>();
    private List<Entry> entries = new ArrayList<>();
    private int rebuildCount = 0;

    /**
     * Bring the cache in line with the given overlays. Meshes whose identity still matches
     * are kept (including their occupancy bits); everything else is rebuilt. Meshes for
     * overlays that no longer exist are dropped.
     *
     * @param checkUv also compare the sprite UVs (after a resource reload the atlas may have moved)
     */
    public void reconcile(Collection<OverlayData> overlays, RenderMode mode, UvLookup uvLookup, boolean checkUv) {
        List<Entry> next = new ArrayList<>(overlays.size());
        Set<String> seen = new HashSet<>(overlays.size() * 2);
        for (OverlayData overlay : overlays) {
            seen.add(overlay.id());
            BakedOverlayMesh mesh = meshes.get(overlay.id());
            float[] uv = null;
            boolean valid = mesh != null && mesh.matches(overlay, mode, null);
            if (valid && checkUv) {
                uv = uvLookup.uv(overlay.tag());
                valid = mesh.matches(overlay, mode, uv);
            }
            if (!valid) {
                if (uv == null) uv = uvLookup.uv(overlay.tag());
                mesh = OverlayMeshBuilder.build(overlay, mode, uv);
                meshes.put(overlay.id(), mesh);
                rebuildCount++;
            }
            next.add(new Entry(overlay, mesh));
        }
        if (meshes.size() != seen.size()) {
            meshes.keySet().retainAll(seen);
        }
        entries = next;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        meshes.clear();
        entries = new ArrayList<>();
    }

    /** Chunk (un)load hook: occupancy of that column must be rescanned. */
    public void markChunkDirty(int chunkX, int chunkZ) {
        for (Entry e : entries) e.mesh.markChunkDirty(chunkX, chunkZ);
    }

    public void markAllDirty() {
        for (Entry e : entries) e.mesh.markAllDirty();
    }

    /** Number of mesh builds since creation (diagnostics/tests). */
    public int rebuildCount() {
        return rebuildCount;
    }
}
