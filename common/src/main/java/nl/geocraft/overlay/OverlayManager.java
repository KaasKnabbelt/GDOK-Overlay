package nl.geocraft.overlay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for active overlays received from the GDOK bridge.
 */
public class OverlayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final OverlayManager INSTANCE = new OverlayManager();
    private static final int MAX_OVERLAYS = 200;
    public static final int MAX_TOTAL_BLOCKS = 100_000;

    private final ConcurrentHashMap<String, OverlayData> overlays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> originalYs = new ConcurrentHashMap<>();
    private volatile boolean sameLevel = true;

    public static OverlayManager getInstance() {
        return INSTANCE;
    }

    public void setSameLevel(boolean value) {
        this.sameLevel = value;
    }

    public boolean isSameLevel() {
        return sameLevel;
    }

    public boolean addOverlay(OverlayData overlay) {
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
        return true;
    }

    /**
     * Zet alle overlays terug op hun originele Y (zoals GDOK ze oorspronkelijk stuurde).
     * Retourneert een map id → nieuwe Y voor de overlays die daadwerkelijk gewijzigd zijn,
     * zodat de aanroeper deze waarden terug naar GDOK kan syncen.
     */
    public java.util.Map<String, Integer> resetAllY() {
        java.util.Map<String, Integer> changes = new java.util.HashMap<>();
        overlays.replaceAll((id, overlay) -> {
            Integer originalY = originalYs.get(id);
            if (originalY == null || originalY.intValue() == overlay.y()) return overlay;
            changes.put(id, originalY);
            return overlay.withY(originalY);
        });
        return changes;
    }

    public int getTotalBlocks() {
        int total = 0;
        for (OverlayData overlay : overlays.values()) {
            total += overlay.blocks().length;
        }
        return total;
    }

    public boolean isOverBlockLimit() {
        return getTotalBlocks() > MAX_TOTAL_BLOCKS;
    }

    public void removeOverlay(String id) {
        overlays.remove(id);
        originalYs.remove(id);
    }

    public void updateOverlayY(String id, int newY) {
        overlays.computeIfPresent(id, (key, existing) -> existing.withY(newY));
    }

    public void clearCategory(String category) {
        if ("all".equals(category)) {
            overlays.clear();
            originalYs.clear();
        } else {
            overlays.entrySet().removeIf(e -> {
                if (e.getValue().category().equals(category)) {
                    originalYs.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    public void adjustAllY(int delta) {
        overlays.replaceAll((key, overlay) -> overlay.withY(overlay.y() + delta));
    }

    public Collection<OverlayData> getOverlays() {
        return overlays.values();
    }
}
