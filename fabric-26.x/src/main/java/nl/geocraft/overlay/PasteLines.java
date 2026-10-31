package nl.geocraft.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import nl.geocraft.overlay.markers.PasteChat;

import java.util.List;

/**
 * The paste commands in the chat (common {@link PasteChat}), only for the player: nothing goes
 * to the server. A command line is white, a click puts it in the chat box (SUGGEST_COMMAND) and
 * Enter runs it. Once the player sends such a command its line turns grey: the line's component
 * is kept, restyled, and the chat re-wraps its lines from the components ({@code rescaleChat}).
 *
 * <p>Mojmap twin of the yarn class in {@code fabric-1.21.11}; keep the modules in step.</p>
 */
public final class PasteLines {

    private static final PasteChat.Printed<MutableComponent> PRINTED = new PasteChat.Printed<>();

    private PasteLines() {}

    static void print(List<PasteChat.Line> lines) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        for (PasteChat.Line line : lines) {
            if (!line.isCommand()) {
                client.gui.hud.getChat().addClientSystemMessage(Component.literal(line.text()));
                continue;
            }
            MutableComponent c = Component.literal(line.text()).withStyle(style -> style
                    .withColor(PasteChat.COMMAND_COLOR)
                    .withClickEvent(new ClickEvent.SuggestCommand(line.command()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(Messages.pasteHover()))));
            PRINTED.add(line.command(), c);
            client.gui.hud.getChat().addClientSystemMessage(c);
        }
    }

    /** Fabric's ClientSendMessageEvents.COMMAND: grey out the printed line of this command. */
    static void onCommandSent(String command) {
        List<MutableComponent> done = PRINTED.executed(command);
        if (done.isEmpty()) return;
        for (MutableComponent c : done) c.withStyle(style -> style.withColor(PasteChat.DONE_COLOR));
        Minecraft.getInstance().gui.hud.getChat().rescaleChat();
    }

    /** "Commando" in the G menu: open the chat, so the lines can be clicked as soon as they come. */
    static void openChat(Minecraft client) {
        client.gui.setScreen(new ChatScreen("", false));
    }

    static void reset() {
        PRINTED.clear();
    }

    /** Diagnostics (gametests): the colour of each printed line of this command, oldest first. */
    public static List<Integer> lineColors(String command) {
        return PRINTED.executed(command).stream()
                .map(c -> c.getStyle().getColor() == null ? null : c.getStyle().getColor().getValue())
                .toList();
    }
}
