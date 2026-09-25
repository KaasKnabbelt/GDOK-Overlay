package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.ServerGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the planting commands one by one (BOMEN.md §9.5, step 4): about one per
 * {@link #TICKS_BETWEEN} ticks, because servers kick players who send too many commands per
 * second. Ticked on the client thread; stops by itself when the server gate closes (another
 * server, disconnect) or on {@link #stop}.
 *
 * <p>The commands never come from the site: {@link PlantCommands} builds them in the mod from
 * the plan, after the player confirmed the check screen. That is why this path does not go
 * through the bridge's {@code command/run}, which only accepts {@code tppos}.</p>
 */
public final class PlantRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final PlantRunner INSTANCE = new PlantRunner();

    /** ~1.1 s between commands; the exact pace is tuned in the first test on GeoCraft. */
    public static final int TICKS_BETWEEN = 22;

    public enum State { RUNNING, DONE, STOPPED }

    /** Progress callback (bridge → site, module → actionbar). */
    public interface Listener {
        void planting(String layerId, int step, int total, State state);
    }

    private Consumer<String> sender;
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String layerId;
    private List<String> commands = List.of();
    private int step = 0;
    private int wait = 0;
    private boolean running = false;

    public static PlantRunner getInstance() {
        return INSTANCE;
    }

    /** The module's way to send a chat command (without the leading slash) on the client thread. */
    public void setSender(Consumer<String> sender) {
        this.sender = sender;
    }

    /** Bridge (to the site) and module (actionbar) both listen. */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public boolean start(String layerId, List<String> commands) {
        if (running || sender == null || commands.isEmpty()) return false;
        this.layerId = layerId;
        this.commands = List.copyOf(commands);
        this.step = 0;
        this.wait = 0;
        this.running = true;
        LOGGER.info("[GeoCraft Overlay] Bomen planten gestart voor '{}': {} commando's", layerId, commands.size());
        notifyListener(State.RUNNING);
        return true;
    }

    public void stop() {
        if (!running) return;
        running = false;
        LOGGER.info("[GeoCraft Overlay] Bomen planten gestopt na {} van {} commando's", step, commands.size());
        notifyListener(State.STOPPED);
    }

    /** Client tick: send the next command when it is time. */
    public void tick() {
        if (!running) return;
        if (!ServerGate.getInstance().isAllowed()) {
            stop();
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        String cmd = commands.get(step);
        // A command packet carries no leading slash: "//sel poly" is the command "/sel poly".
        sender.accept(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        step++;
        wait = TICKS_BETWEEN - 1;
        if (step >= commands.size()) {
            running = false;
            LOGGER.info("[GeoCraft Overlay] Bomen planten klaar voor '{}'", layerId);
            notifyListener(State.DONE);
        } else {
            notifyListener(State.RUNNING);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String layerId() {
        return layerId;
    }

    public int step() {
        return step;
    }

    public int total() {
        return commands.size();
    }

    private void notifyListener(State state) {
        for (Listener l : listeners) l.planting(layerId, step, commands.size(), state);
    }
}
