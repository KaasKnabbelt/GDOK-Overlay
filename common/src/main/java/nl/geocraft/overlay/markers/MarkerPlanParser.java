package nl.geocraft.overlay.markers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a {@code markers/set} message into a {@link MarkerPlan}, strictly.
 *
 * <p>Block names and the paste flags end up in the paste commands CVDK runs
 * ({@code /geotools paste red_wool,lime_wool -a -r -s gdok}), so they are held to a narrow
 * pattern: a name with a space or a dash could otherwise smuggle extra flags or commands in.
 * Everything else is range-checked too; a bad message is refused as a whole.</p>
 *
 * <p>Compact format (the markers are arrays, a plan can hold thousands):</p>
 * <pre>{
 *   "type": "markers", "action": "set", "id": "bomenplan-abc", "label": "Park",
 *   "polygon": [[x, z], ...],
 *   "blocks": ["red_wool", ...], "trunks": ["oak_log", ...],
 *   "paste": {"flags": "-a -r -s gdok"},
 *   "markers": [[x, z, y|null, blockIndex], ...]
 * }</pre>
 *
 * <p>Since 1.1.2-beta.3 there is one tree set and the mod does not use sets. The site still
 * sends {@code sets} and a fifth marker element (the set index) for beta.2; both are ignored,
 * and missing or empty {@code sets} and {@code defaultSet} are fine. Without {@code paste.flags}
 * (an older site) the flags are {@link PasteCommands#DEFAULT_FLAGS}.</p>
 */
public final class MarkerPlanParser {

    public static final int MAX_MARKERS = 20_000;
    public static final int MAX_POLYGON = 2_000;
    public static final int MAX_BLOCKS = 256;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,48}");
    /** Within the world border (±30M), with room to spare. */
    private static final int MAX_COORD = 29_000_000;

    private MarkerPlanParser() {}

    public static MarkerPlan parse(JsonObject msg) {
        String id = string(msg, "id");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("ongeldig id");
        String label = msg.has("label") && !msg.get("label").isJsonNull() ? msg.get("label").getAsString() : id;
        if (label.length() > 80) label = label.substring(0, 80);

        List<String> blocks = names(msg, "blocks", MAX_BLOCKS);
        if (blocks.isEmpty()) throw new IllegalArgumentException("geen blokken");
        Set<String> trunks = new LinkedHashSet<>(msg.has("trunks") ? names(msg, "trunks", MAX_BLOCKS) : List.of());
        String flags = pasteFlags(msg);

        JsonArray poly = array(msg, "polygon");
        if (poly.size() < 3 || poly.size() > MAX_POLYGON) throw new IllegalArgumentException("ongeldig gebied");
        double[][] polygon = new double[poly.size()][];
        for (int i = 0; i < poly.size(); i++) {
            JsonArray p = poly.get(i).getAsJsonArray();
            double x = p.get(0).getAsDouble(), z = p.get(1).getAsDouble();
            if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD) {
                throw new IllegalArgumentException("ongeldig gebied");
            }
            polygon[i] = new double[]{x, z};
        }

        JsonArray arr = array(msg, "markers");
        if (arr.size() > MAX_MARKERS) throw new IllegalArgumentException("te veel markers");
        MarkerPlan.Marker[] markers = new MarkerPlan.Marker[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonArray m = arr.get(i).getAsJsonArray();
            if (m.size() < 4) throw new IllegalArgumentException("ongeldige marker " + i);
            int x = m.get(0).getAsInt(), z = m.get(1).getAsInt();
            JsonElement ye = m.get(2);
            int y = ye == null || ye.isJsonNull() ? MarkerPlan.NO_Y : ye.getAsInt();
            int b = m.get(3).getAsInt(); // m[4], the set index of beta.2, is ignored
            if (Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD
                    || (y != MarkerPlan.NO_Y && (y < -2048 || y > 2047))
                    || b < 0 || b >= blocks.size()) {
                throw new IllegalArgumentException("ongeldige marker " + i);
            }
            markers[i] = new MarkerPlan.Marker(x, z, y, b);
        }
        return new MarkerPlan(id, label, polygon, List.copyOf(blocks), flags, Set.copyOf(trunks), markers);
    }

    /** {@code paste.flags}, held to {@link PasteCommands#flags}; the default when the site sends none. */
    private static String pasteFlags(JsonObject msg) {
        JsonElement paste = msg.get("paste");
        if (paste == null || !paste.isJsonObject()) return PasteCommands.DEFAULT_FLAGS;
        JsonElement flags = paste.getAsJsonObject().get("flags");
        if (flags == null || flags.isJsonNull()) return PasteCommands.DEFAULT_FLAGS;
        return PasteCommands.flags(flags.getAsString());
    }

    /** Block name without namespace, held to {@link #NAME}. */
    static String name(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException("ongeldige naam: " + raw);
        return n;
    }

    private static List<String> names(JsonObject msg, String key, int max) {
        JsonArray a = array(msg, key);
        if (a.size() > max) throw new IllegalArgumentException("te veel " + key);
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(name(e.getAsString()));
        return out;
    }

    private static JsonArray array(JsonObject msg, String key) {
        if (!msg.has(key) || !msg.get(key).isJsonArray()) throw new IllegalArgumentException(key + " ontbreekt");
        return msg.getAsJsonArray(key);
    }

    private static String string(JsonObject msg, String key) {
        if (!msg.has(key) || msg.get(key).isJsonNull()) throw new IllegalArgumentException(key + " ontbreekt");
        return msg.get(key).getAsString();
    }
}
