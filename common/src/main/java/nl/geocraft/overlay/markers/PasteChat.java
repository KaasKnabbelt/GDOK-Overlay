package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.Messages;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The paste commands of a plan as clickable lines in the chat ("Commando" in the G menu, and
 * the client command {@code /gdok paste}). Only shown to the player, nothing goes to the server:
 * a click puts a command in the chat box (the module's SUGGEST_COMMAND click event) and Enter
 * runs it. CVDK makes the WorldEdit selection himself.
 *
 * <p>A request first lets {@link MarkerManager} check the area ({@link ForeignCheck}, a few
 * ticks), so the warning about foreign marker blocks above the commands is current; then the
 * lines go to the module's printer. The module also keeps the printed command lines in a
 * {@link Printed} and turns a line grey once the player sent that command.</p>
 */
public final class PasteChat {

    /** A chat line: plain text (may hold § codes), or a command the player can click. */
    public record Line(String text, String command) {
        public boolean isCommand() {
            return command != null;
        }
    }

    /** Colour of a command line, and of one the player already ran. */
    public static final int COMMAND_COLOR = 0xFFFFFF;
    public static final int DONE_COLOR = 0x808080;

    private static final PasteChat INSTANCE = new PasteChat();

    private Consumer<List<Line>> printer = lines -> {};
    private String pendingId;
    private MarkerLayer pendingLayer;
    private int pendingChecks;

    public static PasteChat getInstance() {
        return INSTANCE;
    }

    /** The module's way to put lines in the chat (client thread). */
    public void setPrinter(Consumer<List<Line>> printer) {
        this.printer = printer;
    }

    /** "Commando" for one plan: check the area again, then print. */
    public void request(String layerId) {
        MarkerLayer l = MarkerManager.getInstance().get(layerId);
        if (l == null) {
            pendingId = null;
            printer.accept(List.of(new Line(Messages.pastePlanGone(), null)));
            return;
        }
        pendingId = layerId;
        pendingLayer = l;
        pendingChecks = l.checks();
        MarkerManager.getInstance().requestForeignCheck(layerId);
    }

    /** "/gdok paste": the plan the player stands in, else the nearest one. */
    public void requestNearest(double x, double z) {
        MarkerLayer l = MarkerManager.getInstance().nearest(x, z);
        if (l == null) {
            pendingId = null;
            printer.accept(List.of(new Line(Messages.pasteNoPlan(), null)));
            return;
        }
        request(l.id());
    }

    /** Client tick, after {@link MarkerManager#tick}: print once the requested check is done. */
    public void tick() {
        if (pendingId == null) return;
        MarkerLayer l = MarkerManager.getInstance().get(pendingId);
        if (l == null) {
            pendingId = null;
            printer.accept(List.of(new Line(Messages.pastePlanGone(), null)));
        } else if (l != pendingLayer) {
            request(pendingId); // the site sent a new version of the plan: check that one
        } else if (l.checks() > pendingChecks) {
            pendingId = null;
            printer.accept(lines(l));
        }
    }

    public boolean isPending() {
        return pendingId != null;
    }

    /** Join/disconnect: nothing pending. */
    public void reset() {
        pendingId = null;
        pendingLayer = null;
    }

    /**
     * What goes in the chat after a check: a header, the warnings (foreign marker blocks, a part
     * of the area that was not loaded) and one line per command. The commands paste the blocks
     * the check looked for, so warning and commands always belong together.
     */
    public static List<Line> lines(MarkerLayer layer) {
        List<Line> out = new ArrayList<>();
        String label = layer.plan.label();
        List<String> blocks = layer.checkedBlocks();
        if (blocks.isEmpty()) {
            out.add(new Line(Messages.pasteNothingPlaced(label), null));
            return out;
        }
        out.add(new Line(Messages.pasteHeader(label), null));
        if (layer.foreignCount() > 0) out.add(new Line(Messages.pasteForeign(layer.foreignCount()), null));
        int unloaded = (int) Math.ceil(layer.foreignUnloaded() * 100);
        if (unloaded > 0) out.add(new Line(Messages.pasteUnloaded(unloaded), null));
        for (String cmd : PasteCommands.build(blocks, layer.plan.pasteFlags())) out.add(new Line(cmd, cmd));
        return out;
    }

    /**
     * The command lines the module printed, with its own handle per line (the chat component),
     * so it can grey out the line of a command the player sent. Bounded: old lines drop off,
     * like they do in the chat.
     */
    public static final class Printed<T> {
        public static final int MAX = 64;

        private record Entry<T>(String command, T line) {}

        private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

        public void add(String command, T line) {
            entries.addLast(new Entry<>(normalize(command), line));
            while (entries.size() > MAX) entries.removeFirst();
        }

        /** The lines of this command as the client sends it (without the leading slash). */
        public List<T> executed(String sent) {
            String s = normalize(sent);
            List<T> out = new ArrayList<>();
            for (Entry<T> e : entries) if (e.command().equals(s)) out.add(e.line());
            return out;
        }

        public void clear() {
            entries.clear();
        }

        static String normalize(String command) {
            String c = command == null ? "" : command.trim();
            if (c.startsWith("/")) c = c.substring(1);
            return c.replaceAll("\\s+", " ");
        }
    }
}
