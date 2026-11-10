package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import nl.geocraft.overlay.markers.MarkerAssist;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The bar at the top for tree plans (BOMEN.md §9.8, B1): "St. Anna: nog 26 van 73" above a
 * green bar, laid out like a boss bar (title at y 3, bar of 182 x 5 at y 12). What it shows
 * comes from common {@link MarkerAssist}. Hidden with F1, without a visible plan within
 * {@link MarkerAssist#HUD_RANGE}, and when the player turns it off in the G menu.
 *
 * <p>Drawn after the vanilla boss bars and below them ({@link MarkerAssist#barOffset}). Until
 * 1.2.1 it stood at the spot of the first boss bar, on the assumption that GeoCraft shows none;
 * it does ("Welkom op: GeoCraft.nl", which a player can turn off), and the two were unreadable.
 * Vanilla keeps its boss bars in a map without a getter, so {@link #bossBars} reads the one Map
 * field of the boss overlay by its type.</p>
 *
 * <p>Mojmap twin of the yarn class in {@code fabric-1.21.11}; keep the modules in step.</p>
 */
final class MarkerHud {

    static final int BAR_W = 182, BAR_H = 5, BAR_Y = 12, TITLE_Y = 3;
    static final int GREEN = 0xFF55C155;
    private static final int BAR_BACK = 0xC0303030;

    private static Field barsField;
    private static boolean barsLooked;

    private MarkerHud() {}

    static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(GeoOverlayMod.MOD_ID, "bomenplan"), MarkerHud::extractRenderState);
    }

    private static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !OverlayConfig.getInstance().isMarkerHud()) return;
        MarkerAssist.Status s = MarkerAssist.status();
        if (s == null) return;
        int w = graphics.guiWidth(), x = (w - BAR_W) / 2;
        int top = offset(mc, graphics.guiHeight());
        graphics.centeredText(mc.font, MarkerAssist.barTitle(s), w / 2, top + TITLE_Y, 0xFFFFFFFF);
        graphics.fill(x, top + BAR_Y, x + BAR_W, top + BAR_Y + BAR_H, BAR_BACK);
        int done = s.total() == 0 ? 0 : (int) Math.round((double) BAR_W * s.done() / s.total());
        if (done > 0) graphics.fill(x, top + BAR_Y, x + done, top + BAR_Y + BAR_H, GREEN);
    }

    /** How far down the bar is drawn: below the boss bars of the server (also for the gametests). */
    static int offset(Minecraft mc, int scaledHeight) {
        return MarkerAssist.barOffset(bossBars(mc), scaledHeight);
    }

    /** Boss bars the client shows now; 0 when the field is not found (then the bar stays at the top). */
    private static int bossBars(Minecraft mc) {
        try {
            Object overlay = mc.gui.getBossOverlay();
            if (!barsLooked) {
                barsLooked = true;
                for (Field f : overlay.getClass().getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        barsField = f;
                        break;
                    }
                }
            }
            return barsField == null ? 0 : ((Map<?, ?>) barsField.get(overlay)).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }
}
