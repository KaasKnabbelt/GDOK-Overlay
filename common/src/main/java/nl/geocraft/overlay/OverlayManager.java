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
    private static final int MAX_BLOCKS_PER_OVERLAY = 10_000;

    private final ConcurrentHashMap<String, OverlayData> overlays = new ConcurrentHashMap<>();

    public static OverlayManager getInstance() {
        return INSTANCE;
    }

    public boolean addOverlay(OverlayData overlay) {
        if (overlay.blocks().length > MAX_BLOCKS_PER_OVERLAY) {
            LOGGER.warn("[GeoCraft Overlay] Overlay '{}' geweigerd: {} blokken overschrijdt limiet van {}",
                    overlay.id(), overlay.blocks().length, MAX_BLOCKS_PER_OVERLAY);
            return false;
        }
        if (!overlays.containsKey(overlay.id()) && overlays.size() >= MAX_OVERLAYS) {
            LOGGER.warn("[GeoCraft Overlay] Overlay '{}' geweigerd: max {} overlays bereikt",
                    overlay.id(), MAX_OVERLAYS);
            return false;
        }
        overlays.put(overlay.id(), overlay);
        return true;
    }

    public void removeOverlay(String id) {
        overlays.remove(id);
    }

    public void updateOverlayY(String id, int newY) {
        overlays.computeIfPresent(id, (key, existing) -> existing.withY(newY));
    }

    public void clearCategory(String category) {
        if ("all".equals(category)) {
            overlays.clear();
        } else {
            overlays.entrySet().removeIf(e -> e.getValue().category().equals(category));
        }
    }

    public void adjustAllY(int delta) {
        overlays.replaceAll((key, overlay) -> overlay.withY(overlay.y() + delta));
    }

    public Collection<OverlayData> getOverlays() {
        return overlays.values();
    }
}
