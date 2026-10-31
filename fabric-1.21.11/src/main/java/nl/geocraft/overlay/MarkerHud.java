package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import nl.geocraft.overlay.markers.MarkerAssist;

/**
 * The bar at the top for tree plans (BOMEN.md §9.8, B1): "St. Anna: nog 26 van 73" above a
 * green bar, laid out like a boss bar (title at y 3, bar of 182 x 5 at y 12). What it shows
 * comes from common {@link MarkerAssist}. Hidden with F1, without a visible plan within
 * {@link MarkerAssist#HUD_RANGE}, and when the player turns it off in the G menu.
 *
 * <p>Drawn after the vanilla boss bars, at the same spot: GeoCraft shows none there.</p>
 *
 * <p>Yarn twin of the mojmap class in {@code fabric-26.x}; keep the modules in step.</p>
 */
final class MarkerHud {

    static final int BAR_W = 182, BAR_H = 5, BAR_Y = 12, TITLE_Y = 3;
    static final int GREEN = 0xFF55C155;
    private static final int BAR_BACK = 0xC0303030;

    private MarkerHud() {}

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.of(GeoOverlayMod.MOD_ID, "bomenplan"), MarkerHud::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !OverlayConfig.getInstance().isMarkerHud()) return;
        MarkerAssist.Status s = MarkerAssist.status();
        if (s == null) return;
        int w = ctx.getScaledWindowWidth(), x = (w - BAR_W) / 2;
        ctx.drawCenteredTextWithShadow(mc.textRenderer, MarkerAssist.barTitle(s), w / 2, TITLE_Y, 0xFFFFFFFF);
        ctx.fill(x, BAR_Y, x + BAR_W, BAR_Y + BAR_H, BAR_BACK);
        int done = s.total() == 0 ? 0 : (int) Math.round((double) BAR_W * s.done() / s.total());
        if (done > 0) ctx.fill(x, BAR_Y, x + done, BAR_Y + BAR_H, GREEN);
    }
}
