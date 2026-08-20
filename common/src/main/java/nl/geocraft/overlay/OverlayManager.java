package nl.geocraft.overlay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for active overlays received from the GDOK bridge.
 *
 * <p>Mutations come from the WebSocket thread (add/remove/clear/updateY) and from the client
 * thread (keybinds, menu, join/disconnect); they are serialised with {@code synchronized} so
 * the cached totals stay consistent. Reads ({@link #getOverlays()}, {@link #revision()},
 * {@link #getTotalBlocks()}) are lock-free and may run every frame.</p>
 *
 * <p>Every mutation bumps {@link #revision()}; the renderer only reconciles its mesh cache
 * when the revision changed.</p>
 */
public class OverlayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final OverlayManager INSTANCE = new OverlayManager();
    private static final int MAX_OVERLAYS = 200;
    public static final int MAX_TOTAL_BLOCKS = 100_000;

    private final ConcurrentHashMap<String, OverlayData> overlays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> originalYs = new ConcurrentHashMap<>();
    /** Overlays the player hid in-game (session-only). Hook for the renderer; managed by the menu. */
    private final Set<String> hiddenIds = ConcurrentHashMap.newKeySet();
    private volatile boolean sameLevel = true;

    private volatile long revision = 0;
    private volatile int totalBlocks = 0;

    public static OverlayManager getInstance() {
        return INSTANCE;
    }

    /** Monotonic counter, bumped on every mutation that can affect rendering. */
    public long revision() {
        return revision;
    }

    private void bump() {
        revision++;
    }

    private void recomputeTotals() {
        int total = 0;
        for (OverlayData overlay : overlays.values()) {
            total += overlay.blocks().length;
        }
        totalBlocks = total;
    }

    public synchronized void setSameLevel(boolean value) {
        if (this.sameLevel == value) return;
        this.sameLevel = value;
        bump();
    }

    public boolean isSameLevel() {
        return sameLevel;
    }

    public synchronized boolean addOverlay(OverlayData overlay) {
        boolean isNew = !overlays.containsKey(overlay.id());
        if (isNew && overlays.size() >= MAX_OVERLAYS) {
            LOGGER.warn("[GeoCraft Overlay] Overlay '{}' geweigerd: max {} overlays bereikt",
                    overlay.id(), MAX_OVERLAYS);
            return false;
        }

        // Onthoud de originele Y (zoals door GDOK gegenereerd) voor de reset-knop.
        originalYs.put(overlay.id(), overlay.y());

        OverlayData toStore = overlay;
        if (isNew && sameLevel && !overlays.isEmpty()) {
            // Zet nieuwe overlay op de gemiddelde Y van bestaande overlays.
            int avgY = (int) Math.round(overlays.values().stream()
                    .mapToInt(OverlayData::y).average().orElse(overlay.y()));
            toStore = overlay.withY(avgY);
        } else if (!isNew) {
            // Behoud de huidige (mogelijk aangepaste) Y bij een re-send van GDOK.
            toStore = overlay.withY(overlays.get(overlay.id()).y());
        }
        overlays.put(overlay.id(), toStore);
        recomputeTotals();
        bump();
        return true;
    }

    /**
     * Zet alle overlays terug op hun originele Y (zoals GDOK ze oorspronkelijk stuurde).
     * Retourneert een map id → nieuwe Y voor de overlays die daadwerkelijk gewijzigd zijn,
     * zodat de aanroeper deze waarden terug naar GDOK kan syncen.
     */
    public synchronized Map<String, Integer> resetAllY() {
        Map<String, Integer> changes = new HashMap<>();
        overlays.replaceAll((id, overlay) -> {
            Integer originalY = originalYs.get(id);
            if (originalY == null || originalY.intValue() == overlay.y()) return overlay;
            changes.put(id, originalY);
            return overlay.withY(originalY);
        });
        if (!changes.isEmpty()) bump();
        return changes;
    }

    /** Cached; O(1). */
    public int getTotalBlocks() {
        return totalBlocks;
    }

    public boolean isOverBlockLimit() {
        return totalBlocks > MAX_TOTAL_BLOCKS;
    }

    public synchronized void removeOverlay(String id) {
        if (overlays.remove(id) == null) return;
        originalYs.remove(id);
        hiddenIds.remove(id);
        recomputeTotals();
        bump();
    }

    public synchronized void updateOverlayY(String id, int newY) {
        OverlayData existing = overlays.get(id);
        if (existing == null || existing.y() == newY) return;
        overlays.put(id, existing.withY(newY));
        bump();
    }

    public synchronized void clearCategory(String category) {
        if ("all".equals(category)) {
            overlays.clear();
            originalYs.clear();
            hiddenIds.clear();
        } else {
            overlays.entrySet().removeIf(e -> {
                if (e.getValue().category().equals(category)) {
                    originalYs.remove(e.getKey());
                    hiddenIds.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }
        recomputeTotals();
        bump();
    }

    public synchronized void adjustAllY(int delta) {
        if (delta == 0) return;
        overlays.replaceAll((key, overlay) -> overlay.withY(overlay.y() + delta));
        bump();
    }

    // -- Visibility (hook for the in-game menu) --------------------

    public boolean isHidden(String id) {
        return hiddenIds.contains(id);
    }

    public synchronized void setHidden(String id, boolean hidden) {
        boolean changed = hidden ? hiddenIds.add(id) : hiddenIds.remove(id);
        if (changed) bump();
    }

    // -- Rendering -------------------------------------------------

    /** The Y level an overlay is drawn at. Only the renderer should use this. */
    public int getRenderY(OverlayData overlay) {
        return overlay.y();
    }

    public Collection<OverlayData> getOverlays() {
        return overlays.values();
    }

    public OverlayData getOverlay(String id) {
        return overlays.get(id);
    }
}
