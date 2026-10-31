package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import nl.geocraft.overlay.markers.MarkerAssist;

/**
 * The bar at the top for tree plans (BOMEN.md §9.8, B1): "St. Anna: nog 26 van 73" above a
 * green bar, laid out like a boss bar (title at y 3, bar of 182 x 5 at y 12). What it shows
 * comes from common {@link MarkerAssist}. Hidden with F1, without a visible plan within
 * {@link MarkerAssist#HUD_RANGE}, and when the player turns it off in the G menu.
 *
 * <p>Drawn after the vanilla boss bars, at the same spot: GeoCraft shows none there.</p>
 *
 * <p>Mojmap twin of the yarn class in {@code fabric-1.21.11}; keep the modules in step.</p>
 */
final class MarkerHud {

    static final int BAR_W = 182, BAR_H = 5, BAR_Y = 12, TITLE_Y = 3;
    static final int GREEN = 0xFF55C155;
    private static final int BAR_BACK = 0xC0303030;

    private MarkerHud() {}

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(GeoOverlayMod.MOD_ID, "bomenplan"), MarkerHud::extractRenderState);
    }

    private static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        // F1: on 26.2 the HUD keeps the flag itself (Options.hideGui is gone).
        if (mc.player == null || mc.gui.hud.isHidden() || !OverlayConfig.getInstance().isMarkerHud()) return;
        MarkerAssist.Status s = MarkerAssist.status();
        if (s == null) return;
        int w = graphics.guiWidth(), x = (w - BAR_W) / 2;
        graphics.centeredText(mc.font, MarkerAssist.barTitle(s), w / 2, TITLE_Y, 0xFFFFFFFF);
        graphics.fill(x, BAR_Y, x + BAR_W, BAR_Y + BAR_H, BAR_BACK);
        int done = s.total() == 0 ? 0 : (int) Math.round((double) BAR_W * s.done() / s.total());
        if (done > 0) graphics.fill(x, BAR_Y, x + done, BAR_Y + BAR_H, GREEN);
    }
}
