package nl.geocraft.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.function.Consumer;

/**
 * From the game to the GDOK viewer (common {@link GdokLink}). Only on GeoCraft ({@link ServerGate}).
 * <ul>
 *   <li>{@code /gdok}: is a viewer open that understands it, then that viewer flies to the
 *       player's block ({@link BridgeServer#sendGoto}); otherwise the browser opens the viewer
 *       there, with the link in the chat in case it does not (C3, chosen by Jens on 31 October
 *       2026: no pile of tabs).</li>
 *   <li>The Google Maps line of {@code /waarbenik} gets a [GDOK] button that opens the viewer on
 *       that spot (Fabric's ClientReceiveMessageEvents.MODIFY_GAME).</li>
 * </ul>
 *
 * <p>Mojmap twin of the yarn class in {@code fabric-1.21.11}; keep the modules in step.</p>
 */
public final class GdokChat {

    /** Opens a URL in the browser; the gametests replace it, so no real browser opens. */
    public static Consumer<String> opener = url -> Util.getPlatform().openUri(URI.create(url));

    private GdokChat() {}

    /** MODIFY_GAME: the [GDOK] button behind the Google Maps line of /waarbenik. */
    static Component onGameMessage(Component message, boolean overlay) {
        if (overlay || !ServerGate.getInstance().isAllowed()) return message;
        int[] line = GdokLink.blockFromWaarbenik(message.getString());
        if (line == null) return message;
        Minecraft mc = Minecraft.getInstance();
        int[] b = mc.player != null ? GdokLink.resolve(line, mc.player.getX(), mc.player.getZ()) : line;
        // A fresh root, so the button does not take over the style (or link) of the line itself.
        return Component.empty().append(message).append(" ").append(link(Messages.gdokButton(), GdokLink.viewerUrl(b[0], b[1])));
    }

    /** {@code /gdok}: the viewer on the player's block. */
    static int openViewer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        if (!ServerGate.getInstance().isAllowed()) {
            mc.player.sendSystemMessage(Component.literal(Messages.gdokOnlyOnGeoCraft()));
            return 1;
        }
        int x = (int) Math.floor(mc.player.getX()), z = (int) Math.floor(mc.player.getZ());
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null && bridge.sendGoto(x, z)) {
            mc.player.sendSystemMessage(Component.literal(Messages.gdokFlown(x, z)));
            return 1;
        }
        String url = GdokLink.viewerUrl(x, z);
        opener.accept(url);
        mc.player.sendSystemMessage(Component.literal(Messages.gdokOpening(x, z)).append(link(Messages.gdokLinkLabel(), url)));
        return 1;
    }

    private static MutableComponent link(String label, String url) {
        return Component.literal(label).withStyle(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Messages.gdokButtonHover()))));
    }
}
