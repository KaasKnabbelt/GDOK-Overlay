package nl.geocraft.overlay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import nl.geocraft.overlay.markers.PasteChat;
import nl.geocraft.overlay.markers.PlanShare;

import java.util.List;

/**
 * The paste commands in the chat (common {@link PasteChat}), only for the player: nothing goes
 * to the server. A command line is white, a click puts it in the chat box (SUGGEST_COMMAND) and
 * Enter runs it. Once the player sends such a command its line turns grey: the line's text is
 * kept, restyled, and the chat re-wraps its lines from the texts ({@code ChatHud.reset}).
 *
 * <p>Yarn twin of the mojmap class in {@code fabric-26.x}; keep the modules in step.</p>
 */
public final class PasteLines {

    private static final PasteChat.Printed<MutableText> PRINTED = new PasteChat.Printed<>();

    private PasteLines() {}

    static void print(List<PasteChat.Line> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        for (PasteChat.Line line : lines) {
            if (!line.isCommand()) {
                client.inGameHud.getChatHud().addMessage(Text.literal(line.text()));
                continue;
            }
            MutableText t = Text.literal(line.text()).styled(style -> style
                    .withColor(PasteChat.COMMAND_COLOR)
                    .withClickEvent(new ClickEvent.SuggestCommand(line.command()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(Messages.pasteHover()))));
            PRINTED.add(line.command(), t);
            client.inGameHud.getChatHud().addMessage(t);
        }
    }

    /** Fabric's ClientSendMessageEvents.COMMAND: grey out the printed line of this command. */
    static void onCommandSent(String command) {
        List<MutableText> done = PRINTED.executed(command);
        if (done.isEmpty()) return;
        for (MutableText t : done) t.styled(style -> style.withColor(PasteChat.DONE_COLOR));
        MinecraftClient.getInstance().inGameHud.getChatHud().reset();
    }

    /** "Commando" in the G menu: open the chat, so the lines can be clicked as soon as they come. */
    static void openChat(MinecraftClient client) {
        client.setScreen(new ChatScreen("", false));
    }

    static void reset() {
        PRINTED.clear();
        lastShare = null;
    }

    // -- "Deel" in the G menu (common PlanShare) ----------------------------

    private static String lastShare;

    /** How {@link PlanShare} shows things: a line for the player, or the link text in the chat box. */
    static final PlanShare.Ui SHARE_UI = new PlanShare.Ui() {
        @Override
        public void message(String text) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) client.inGameHud.getChatHud().addMessage(Text.literal(text));
        }

        /**
         * Into the chat box, ready to send. With another screen open (the player went on in the
         * meantime) a line he can click instead: closing his screen for him would be rude.
         */
        @Override
        public void chat(String text) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            lastShare = text;
            if (client.currentScreen == null || client.currentScreen instanceof ChatScreen) {
                client.setScreen(new ChatScreen(text, false));
                return;
            }
            client.inGameHud.getChatHud().addMessage(Text.literal(Messages.shareReady()).styled(style -> style
                    .withClickEvent(new ClickEvent.SuggestCommand(text))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(text)))));
        }
    };

    /** Diagnostics (gametests): the last text "Deel" put in the chat box, or null. */
    public static String lastShare() {
        return lastShare;
    }

    /** Diagnostics (gametests): the colour of each printed line of this command, oldest first. */
    public static List<Integer> lineColors(String command) {
        return PRINTED.executed(command).stream()
                .map(c -> c.getStyle().getColor() == null ? null : c.getStyle().getColor().getRgb())
                .toList();
    }
}
