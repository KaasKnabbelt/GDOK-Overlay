package nl.geocraft.overlay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store for active overlays received from the GDOK bridge.
 *
 * <p>Mutations come from the WebSocket thread (add/remove/clear/updateY) and from the client
 * thread (keybinds, menu, join/disconnect); they are serialised with {@code synchronized} so
 * the cached totals stay consistent. Reads ({@link #getOverlays()}, {@link #revision()},
 * {@link #getTotalBlocks()}, {@link #getRenderY(OverlayData)}) are lock-free and may run
 * every frame.</p>
 *
 * <p>Every mutation bumps {@link #revision()}; the renderer only reconciles its mesh cache
 * when the revision changed.</p>
 *
 * <h2>Height model</h2>
 * <p>Each overlay keeps the Y it arrived with (GDOK's AHN-based hint); that value is never
 * rewritten by levelling. On top of that there is one session-only {@link #levelY}: the
 * height every overlay is drawn at while "same level" is on. It is initialised from the
 * first overlay that arrives (or, when the toggle is switched on later, from the largest
 * overlay present) and only moves when the player moves it (PageUp/PageDown, menu). Because
 * the per-overlay hints stay untouched, switching "same level" off restores the individual
 * heights, and the result no longer depends on the order in which overlays arrive.</p>
 *
 * <p>The level is forgotten as soon as the store runs empty (site "Wissen", join/disconnect):
 * a fresh drawing somewhere else on the map should start from its own hint, not from a level
 * that belonged to the previous location.</p>
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
    /** Shared render height while {@link #sameLevel} is on; {@code null} = not determined yet. */
    private volatile Integer levelY = null;

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

    // -- Same level ------------------------------------------------

    public synchronized void setSameLevel(boolean value) {
        if (this.sameLevel == value) return;
        this.sameLevel = value;
        if (value && levelY == null) initLevelY();
        bump();
    }

    public boolean isSameLevel() {
        return sameLevel;
    }

    /** The shared level, or {@code null} while none is set (store empty, or same-level never on). */
    public Integer getLevelY() {
        return levelY;
    }

    /**
     * Pick a level from the overlays present: the hint of the largest overlay (most blocks),
     * ties broken by id so the outcome never depends on arrival order. No-op on an empty store.
     */
    private void initLevelY() {
        OverlayData best = null;
        for (OverlayData o : overlays.values()) {
            if (best == null
                    || o.blocks().length > best.blocks().length
                    || (o.blocks().length == best.blocks().length && o.id().compareTo(best.id()) < 0)) {
                best = o;
            }
        }
        levelY = best == null ? null : best.y();
    }

    private void forgetLevelIfEmpty() {
        if (overlays.isEmpty()) levelY = null;
    }

    // -- Mutations -------------------------------------------------

    /**
     * Add or replace an overlay. A re-send under an existing id (the site re-paints a group)
     * replaces the blocks but keeps the Y the overlay currently has, so an in-game adjustment
     * survives further painting. The hint Y is remembered for {@link #resetAllY()}.
     */
    public synchronized boolean addOverlay(OverlayData overlay) {
        OverlayData existing = overlays.get(overlay.id());
        if (existing == null && overlays.size() >= MAX_OVERLAYS) {
            LOGGER.warn("[GeoCraft Overlay] Overlay '{}' geweigerd: max {} overlays bereikt",
                    overlay.id(), MAX_OVERLAYS);
            return false;
        }

        originalYs.put(overlay.id(), overlay.y());

        OverlayData toStore = existing == null ? overlay : overlay.withY(existing.y());
        overlays.put(overlay.id(), toStore);
        if (sameLevel && levelY == null) levelY = toStore.y();
        recomputeTotals();
        bump();
        return true;
    }

    /**
     * Put every overlay back on the Y GDOK originally sent and re-derive the shared level
     * from those hints. Returns whether anything changed (callers broadcast the new heights).
     */
    public synchronized boolean resetAllY() {
        boolean[] changed = {false};
        overlays.replaceAll((id, overlay) -> {
            Integer originalY = originalYs.get(id);
            if (originalY == null || originalY.intValue() == overlay.y()) return overlay;
            changed[0] = true;
            return overlay.withY(originalY);
        });
        Integer before = levelY;
        levelY = null;
        if (sameLevel) initLevelY();
        if (before == null ? levelY != null : !before.equals(levelY)) changed[0] = true;
        if (changed[0]) bump();
        return changed[0];
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
        forgetLevelIfEmpty();
        recomputeTotals();
        bump();
    }

    /**
     * Height update from the site for one overlay. With "same level" on the request is read
     * as "the level should be this Y" (so an older site's stepper still does something
     * visible); otherwise only that overlay moves.
     */
    public synchronized void updateOverlayY(String id, int newY) {
        OverlayData existing = overlays.get(id);
        if (existing == null) return;
        if (sameLevel && levelY != null) {
            if (levelY == newY) return;
            levelY = newY;
        } else {
            if (existing.y() == newY) return;
            overlays.put(id, existing.withY(newY));
        }
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
        forgetLevelIfEmpty();
        recomputeTotals();
        bump();
    }

    /**
     * PageUp/PageDown: shift the drawn height by {@code delta}. With "same level" on only the
     * shared level moves (one field, no per-overlay writes, no mesh rebuilds); otherwise every
     * overlay's own Y shifts.
     */
    public synchronized void adjustAllY(int delta) {
        if (delta == 0) return;
        if (sameLevel && levelY != null) {
            levelY = levelY + delta;
        } else {
            overlays.replaceAll((key, overlay) -> overlay.withY(overlay.y() + delta));
        }
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

    /** The Y level an overlay is drawn at: the shared level while "same level" is on, else its own Y. */
    public int getRenderY(OverlayData overlay) {
        Integer level = levelY; // volatile: read once
        return sameLevel && level != null ? level : overlay.y();
    }

    public Collection<OverlayData> getOverlays() {
        return overlays.values();
    }

    public OverlayData getOverlay(String id) {
        return overlays.get(id);
    }
}
