package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.ServerGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * View-model of the "Bomen planten" confirmation screen (BOMEN.md §9.5, steps 2-3). Both
 * Minecraft modules draw the same lines and buttons from this: first the {@link PlantCheck}
 * runs, then the screen shows what it found, and only "Planten" starts the {@link PlantRunner}.
 *
 * <p>Marker blocks outside the plan make the button ask twice: they become trees as well, and
 * they are usually someone's WorldEdit placeholders. They are outlined red in the world
 * ({@link MarkerManager#setStrays}), so the player can go and look.</p>
 */
public final class PlantFlow {

    public static final int WHITE = 0xFFFFFFFF;
    public static final int GREY = 0xFFA0A0A0;
    public static final int GREEN = 0xFF55FF55;
    public static final int ORANGE = 0xFFFFAA00;
    public static final int RED = 0xFFFF5555;

    public record Line(String text, int color) {}

    private final String layerId;
    private final PlantCheck.BlockFinder finder;
    private final List<String> commands;
    private PlantCheck check;
    private boolean armed = false;
    private boolean finished = false;

    public PlantFlow(String layerId, PlantCheck.BlockFinder finder) {
        this.layerId = layerId;
        this.finder = finder;
        MarkerLayer layer = layer();
        this.commands = layer == null ? List.of() : PlantCommands.build(layer.plan);
        restart();
    }

    private MarkerLayer layer() {
        return MarkerManager.getInstance().get(layerId);
    }

    /** Start the check (again): after flying over unloaded parts, or after removing strays. */
    public void restart() {
        MarkerLayer layer = layer();
        check = layer == null ? null : new PlantCheck(layer, finder);
        armed = false;
        finished = false;
    }

    /** Client tick while the screen is open. */
    public void tick() {
        if (check == null || finished) return;
        if (check.layer() != layer()) { // the site sent a new version of the plan
            restart();
            return;
        }
        if (check.tick()) {
            finished = true;
            MarkerManager.getInstance().setStrays(layerId, check.strayPositions());
        }
    }

    public boolean isChecking() {
        return check != null && !finished;
    }

    public String title() {
        MarkerLayer layer = layer();
        return "Bomen planten: " + (layer == null ? layerId : layer.plan.label());
    }

    public List<Line> lines() {
        List<Line> out = new ArrayList<>();
        MarkerLayer layer = layer();
        if (layer == null) {
            out.add(new Line("Dit plan staat niet meer in Minecraft.", RED));
            return out;
        }
        MarkerLayer.Counts c = layer.counts();
        long sets = java.util.Arrays.stream(layer.plan.markers()).mapToInt(MarkerPlan.Marker::set).distinct().count();
        out.add(new Line(nl(c.total()) + " markers in " + sets + (sets == 1 ? " set" : " sets") + ", "
                + commands.size() + " commando's", WHITE));
        out.add(new Line(nl(c.placed()) + (c.placed() == 1 ? " staat op zijn plek · " : " staan op hun plek · ")
                + nl(c.toPlace()) + " nog niet · " + nl(c.blocked()) + " geblokkeerd", c.toPlace() > 0 ? ORANGE : GREEN));
        if (c.existing() + c.planted() > 0) {
            out.add(new Line(nl(c.existing() + c.planted()) + " plekken hebben al een boom en worden overgeslagen.", GREY));
        }
        if (isChecking()) {
            out.add(new Line("Gebied controleren… " + Math.round(check.progress() * 100) + "%", GREY));
            return out;
        }
        if (check.strays() > 0) {
            out.add(new Line(check.strays() == 1
                    ? "1 markerblok in het gebied hoort niet bij dit plan."
                    : nl(check.strays()) + " markerblokken in het gebied horen niet bij dit plan.", RED));
            out.add(new Line("Ook die worden bomen! Ze zijn rood omlijnd in de wereld.", RED));
        } else {
            out.add(new Line("Geen andere markerblokken in het gebied.", GREEN));
        }
        if (check.unloadedChunks() > 0) {
            out.add(new Line(Math.round(check.unloadedFraction() * 100) + "% van het gebied is niet geladen en kon niet"
                    + " gecontroleerd worden.", ORANGE));
        } else {
            out.add(new Line("Het hele gebied is geladen en gecontroleerd.", GREY));
        }
        String blocker = blocker();
        if (blocker != null) {
            out.add(new Line(blocker, RED));
        } else {
            int secs = Math.round(commands.size() * PlantRunner.TICKS_BETWEEN / 20f);
            out.add(new Line("Eén commando per seconde (~" + secs + " s). //undo draait het terug.", GREY));
        }
        return out;
    }

    /** Why planting is not possible now, or null. */
    private String blocker() {
        MarkerLayer layer = layer();
        if (layer == null) return "Dit plan staat niet meer in Minecraft.";
        if (!ServerGate.getInstance().isAllowed()) return "Planten kan alleen op GeoCraft.";
        if (PlantRunner.getInstance().isRunning()) return "Er loopt al een plant-opdracht.";
        if (layer.counts().placed() == 0) return "Er staat nog geen enkele marker, dus er valt niets te planten.";
        return null;
    }

    public boolean canPlant() {
        return !isChecking() && check != null && blocker() == null;
    }

    public String plantLabel() {
        if (isChecking()) return "Controleren…";
        if (check != null && check.strays() > 0) {
            return armed ? "Zeker weten? Klik nogmaals" : "Toch planten (" + nl(check.strays()) + " extra)";
        }
        return "Planten";
    }

    /** "Planten" pressed. Returns true when the commands started (the screen closes). */
    public boolean confirm() {
        if (!canPlant()) return false;
        if (check.strays() > 0 && !armed) {
            armed = true;
            return false;
        }
        return PlantRunner.getInstance().start(layerId, commands);
    }

    public List<String> commands() {
        return commands;
    }

    private static String nl(int n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', '.');
    }
}
