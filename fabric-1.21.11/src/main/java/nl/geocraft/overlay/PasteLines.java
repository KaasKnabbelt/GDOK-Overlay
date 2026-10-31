package nl.geocraft.overlay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import nl.geocraft.overlay.markers.PasteChat;

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
    }

    /** Diagnostics (gametests): the colour of each printed line of this command, oldest first. */
    public static List<Integer> lineColors(String command) {
        return PRINTED.executed(command).stream()
                .map(c -> c.getStyle().getColor() == null ? null : c.getStyle().getColor().getRgb())
                .toList();
    }
}
