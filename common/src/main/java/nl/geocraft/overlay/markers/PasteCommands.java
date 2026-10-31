package nl.geocraft.overlay.markers;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The geotools commands that turn a plan's placed markers into trees:
 * {@code /geotools paste <blok1>,<blok2>,... <flags>}. The mod never runs them; CVDK selects the
 * area himself and runs them from the chat ({@link PasteChat}).
 *
 * <p>Only the block types of markers that stand on their spot ({@link MarkerStatus#PLACED})
 * go in, unique and alphabetical, so the same plan always gives the same text. The commands are
 * split so that none is longer than {@link #MAX_CHARS} characters (the chat input limit).</p>
 *
 * <p>The flags come from the site ({@code markers/set} field {@code paste.flags}), so a new set
 * name does not need a new mod. Block names and flags end up in a command the player runs, so
 * both are held to a narrow pattern: no slash, no semicolon, no line break can get in.</p>
 */
public final class PasteCommands {

    /** Without the site's flags (an older site): the gdok set. Without -s geotools pastes nothing. */
    public static final String DEFAULT_FLAGS = "-a -r -s gdok";
    public static final int MAX_CHARS = 256;

    private static final String HEAD = "/geotools paste ";
    private static final Pattern FLAGS = Pattern.compile("[a-z0-9 _-]{1,64}");
    private static final Pattern BLOCK = Pattern.compile("[a-z0-9_]{1,48}");

    private PasteCommands() {}

    /** The site's flags, trimmed and single-spaced; only lower case letters, digits, space, '_' and '-'. */
    public static String flags(String raw) {
        String f = raw == null ? "" : raw.trim().replaceAll(" {2,}", " ");
        if (!FLAGS.matcher(f).matches()) throw new IllegalArgumentException("ongeldige paste-vlaggen: " + raw);
        return f;
    }

    /** Block types of the placed markers, unique and alphabetical: what the commands paste. */
    public static List<String> blocks(MarkerLayer layer) {
        TreeSet<String> out = new TreeSet<>();
        for (int i = 0; i < layer.size(); i++) {
            if (layer.status(i) == MarkerStatus.PLACED) out.add(layer.plan.blockName(layer.plan.markers()[i]));
        }
        return List.copyOf(out);
    }

    /**
     * The commands for these blocks (in the given order), each at most {@link #MAX_CHARS}
     * characters including the leading slash. Empty when there are no blocks.
     */
    public static List<String> build(List<String> blocks, String flags) {
        String tail = " " + flags(flags);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String b : blocks) {
            if (!BLOCK.matcher(b).matches()) throw new IllegalArgumentException("ongeldige bloknaam: " + b);
            int length = HEAD.length() + current.length() + (current.isEmpty() ? 0 : 1) + b.length() + tail.length();
            if (!current.isEmpty() && length > MAX_CHARS) {
                out.add(HEAD + current + tail);
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(',');
            current.append(b);
        }
        if (!current.isEmpty()) out.add(HEAD + current + tail);
        return out;
    }
}
