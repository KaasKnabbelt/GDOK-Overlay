package nl.geocraft.overlay;

import nl.geocraft.overlay.render.RenderMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * View-model for the in-game overlay menu (G). Both Minecraft modules render the same
 * state through their own widget toolkit; everything that is not widget layout lives here:
 * row snapshots, labels, and the actions that delegate to {@link OverlayManager},
 * {@link OverlayConfig} and {@link BridgeServer}.
 *
 * <p>Read {@link #revision()} each tick: when it changed (the site painted, a key was
 * pressed) the screen rebuilds its rows from {@link #rows()}.</p>
 */
public final class OverlayMenuState {

    /** Immutable snapshot of one overlay row. */
    public record Row(
            String id,
            /** Display name without the site's trailing "(n)" count. */
            String name,
            int blocks,
            int red, int green, int blue,
            /** Minecraft block tag ({@code null} for the tinted click marker). */
            String tag,
            boolean hidden,
            boolean pinned,
            /** The Y this row is drawn at (shared level or its own). */
            int renderY,
            /** The row's own Y (what the per-row stepper edits). */
            int ownY
    ) {
        /** Marker rows (no block tag) are drawn as a colour swatch instead of a block icon. */
        public boolean isMarker() {
            return tag == null;
        }
    }

    private final OverlayManager manager;
    private final OverlayConfig config;

    public OverlayMenuState() {
        this(OverlayManager.getInstance(), OverlayConfig.getInstance());
    }

    public OverlayMenuState(OverlayManager manager, OverlayConfig config) {
        this.manager = manager;
        this.config = config;
    }

    // -- Snapshot -------------------------------------------------------

    public long revision() {
        return manager.revision();
    }

    /** Rows sorted for display: the click marker first, then alphabetically by name, then id. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (OverlayData o : manager.getOverlays()) {
            rows.add(new Row(
                    o.id(), displayName(o), o.blocks().length,
                    o.red(), o.green(), o.blue(), o.tag(),
                    manager.isHidden(o.id()), manager.isPinned(o.id()),
                    manager.getRenderY(o), o.y()));
        }
        rows.sort(Comparator.comparing((Row r) -> !r.isMarker())
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::id));
        return rows;
    }

    /**
     * The site labels paint groups as "Oranje wol (123)"; the count is shown separately in the
     * menu, so strip it. Falls back to the category when the label is empty.
     */
    static String displayName(OverlayData o) {
        String label = o.label() == null ? "" : o.label().trim();
        label = label.replaceAll("\\s*\\(\\d+\\)$", "");
        if (!label.isEmpty()) return label;
        return switch (o.category()) {
            case "click" -> "Marker";
            case "paint" -> "Blokken";
            default -> o.category();
        };
    }

    public int totalBlocks() {
        return manager.getTotalBlocks();
    }

    public int maxBlocks() {
        return OverlayManager.MAX_TOTAL_BLOCKS;
    }

    public boolean overBlockLimit() {
        return manager.isOverBlockLimit();
    }

    public boolean viewerConnected() {
        BridgeServer bridge = BridgeServer.getInstance();
        return bridge != null && bridge.hasClients();
    }

    public boolean sameLevel() {
        return manager.isSameLevel();
    }

    /** Shared level while same-level is on; {@code null} when there is nothing to level. */
    public Integer levelY() {
        return manager.getLevelY();
    }

    public int opacityPercent() {
        return config.getOpacityPercent();
    }

    public RenderMode renderMode() {
        return config.getRenderMode();
    }

    public boolean shareLocation() {
        return config.isShareLocation();
    }

    /** Advanced menu mode (per-layer steppers/pins, shared level, location, keybinds). */
    public boolean advancedMenu() {
        return config.isAdvancedMenu();
    }

    public static String renderModeLabel(RenderMode mode) {
        return mode == RenderMode.CARPET ? "Dun tapijt" : "Volledige blokken";
    }

    // -- Actions: rows ---------------------------------------------------

    public void setHidden(String id, boolean hidden) {
        manager.setHidden(id, hidden);
        broadcastState(id);
    }

    public void setPinned(String id, boolean pinned) {
        manager.setPinned(id, pinned);
        broadcastState(id);
    }

    /** The row's cross: always removes, pinned or not, and tells the site to drop it too. */
    public void remove(String id) {
        manager.removeOverlay(id);
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null) bridge.broadcastRemoved(id);
    }

    /** Per-row stepper: moves that overlay's own Y (pins it first if it still followed the level). */
    public void shiftRow(String id, int delta) {
        manager.adjustOverlayY(id, delta);
        OverlayData o = manager.getOverlay(id);
        BridgeServer bridge = BridgeServer.getInstance();
        if (o != null && bridge != null) {
            bridge.broadcastOverlayY(id, manager.getRenderY(o));
            bridge.broadcastState(id);
        }
    }

    // -- Actions: global -------------------------------------------------

    public void setSameLevel(boolean value) {
        config.setSameLevel(value);
        broadcastHeights();
    }

    /** Global stepper (same-level on): moves the shared level, like PageUp/PageDown. */
    public void shiftLevel(int delta) {
        manager.adjustAllY(delta);
        broadcastHeights();
    }

    public void resetHeights() {
        if (manager.resetAllY()) broadcastHeights();
    }

    public void setOpacityPercent(int percent) {
        config.setOpacityPercent(percent);
    }

    public void setRenderMode(RenderMode mode) {
        config.setRenderMode(mode);
    }

    /** Caller must also refresh the player tracker (module-specific). */
    public void setShareLocation(boolean value) {
        config.setShareLocation(value);
    }

    /** The screen watches this via its tick and rebuilds its rows on a change. */
    public void setAdvancedMenu(boolean value) {
        config.setAdvancedMenu(value);
    }

    /** "Alles wissen (behalve vastgezet)": also tells the site which drawings to drop. */
    public void clearUnpinned() {
        List<String> dropped = manager.unpinnedIds("all");
        manager.clearCategory("all");
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null) for (String id : dropped) bridge.broadcastRemoved(id);
    }

    /** Persist the config when the screen closes. */
    public void save() {
        config.save();
    }

    private static void broadcastHeights() {
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null) bridge.broadcastOverlayYs();
    }

    private static void broadcastState(String id) {
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null) bridge.broadcastState(id);
    }
}
