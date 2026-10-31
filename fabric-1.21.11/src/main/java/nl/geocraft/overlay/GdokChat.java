package nl.geocraft.overlay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
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
 * <p>Yarn twin of the mojmap class in {@code fabric-26.x}; keep the modules in step.</p>
 */
public final class GdokChat {

    /** Opens a URL in the browser; the gametests replace it, so no real browser opens. */
    public static Consumer<String> opener = url -> Util.getOperatingSystem().open(URI.create(url));

    private GdokChat() {}

    /** MODIFY_GAME: the [GDOK] button behind the Google Maps line of /waarbenik. */
    static Text onGameMessage(Text message, boolean overlay) {
        if (overlay || !ServerGate.getInstance().isAllowed()) return message;
        int[] line = GdokLink.blockFromWaarbenik(message.getString());
        if (line == null) return message;
        MinecraftClient mc = MinecraftClient.getInstance();
        int[] b = mc.player != null ? GdokLink.resolve(line, mc.player.getX(), mc.player.getZ()) : line;
        // A fresh root, so the button does not take over the style (or link) of the line itself.
        return Text.empty().append(message).append(" ").append(link(Messages.gdokButton(), GdokLink.viewerUrl(b[0], b[1])));
    }

    /** {@code /gdok}: the viewer on the player's block. */
    static int openViewer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        if (!ServerGate.getInstance().isAllowed()) {
            mc.player.sendMessage(Text.literal(Messages.gdokOnlyOnGeoCraft()), false);
            return 1;
        }
        int x = (int) Math.floor(mc.player.getX()), z = (int) Math.floor(mc.player.getZ());
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge != null && bridge.sendGoto(x, z)) {
            mc.player.sendMessage(Text.literal(Messages.gdokFlown(x, z)), false);
            return 1;
        }
        String url = GdokLink.viewerUrl(x, z);
        opener.accept(url);
        mc.player.sendMessage(Text.literal(Messages.gdokOpening(x, z)).append(link(Messages.gdokLinkLabel(), url)), false);
        return 1;
    }

    private static MutableText link(String label, String url) {
        return Text.literal(label).styled(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(Messages.gdokButtonHover()))));
    }
}
