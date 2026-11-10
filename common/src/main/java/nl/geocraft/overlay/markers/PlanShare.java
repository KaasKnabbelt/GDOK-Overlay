package nl.geocraft.overlay.markers;

import nl.geocraft.overlay.BridgeServer;
import nl.geocraft.overlay.Messages;

import java.util.regex.Pattern;

/**
 * "Deel" in the G menu (1.2.1, BOMEN.md §9.9): a link to the paste commands of a plan in the
 * player's chat box, to send to CVDK. Requested from the Discord: until then CVDK typed the block
 * types over by hand.
 *
 * <p>The mod has no link of its own; the open GDOK viewer makes one. {@link #request} asks every
 * viewer that announced {@code share} ({@code markers/share {id}}), the viewer stores the plan
 * and answers {@code markers/shared {id, url}} (or {@code error}). The link opens the viewer with
 * the window of paste commands on top ({@code /bomen/<slug>#paste}), also for someone without
 * the mod.</p>
 *
 * <p>Nothing is sent to the server: the text lands in the chat box and the player presses Enter
 * himself, as with the paste commands. Because it ends up there, only a link of exactly the
 * expected form is accepted ({@link #isShareUrl}), only as the answer to a request of the last
 * {@link #TIMEOUT_TICKS}, and the plan name loses everything but plain characters.</p>
 *
 * <p>Threading: the answer comes in on the WebSocket thread ({@link #submitAnswer}) and is
 * handled in {@link #tick} on the client thread.</p>
 */
public final class PlanShare {

    /** How the module shows things (client thread). */
    public interface Ui {
        /** A line in the chat, only for the player. */
        void message(String text);

        /** Put this text in the chat box (open the chat if it is not), ready to send. */
        void chat(String text);
    }

    /** The viewer has to store the plan on the server first; after this long the mod gives up. */
    public static final int TIMEOUT_TICKS = 200;

    private static final Pattern SHARE_URL = Pattern.compile(
            "(https://(www\\.)?gdok\\.nl|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?)/bomen/[A-Za-z0-9]{4,32}#paste");

    private static final PlanShare INSTANCE = new PlanShare();

    private record Answer(String id, String url, String error) {}

    private Ui ui = new Ui() {
        @Override
        public void message(String text) {}

        @Override
        public void chat(String text) {}
    };
    private String pendingId;
    private String pendingLabel;
    private int ticksLeft;
    private volatile Answer answer;

    public static PlanShare getInstance() {
        return INSTANCE;
    }

    public void setUi(Ui ui) {
        this.ui = ui;
    }

    /** "Deel" for one plan: ask the open viewer for a link. */
    public void request(String layerId) {
        MarkerLayer l = MarkerManager.getInstance().get(layerId);
        if (l == null) {
            pendingId = null;
            ui.message(Messages.pastePlanGone());
            return;
        }
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge == null || !bridge.requestShare(layerId)) {
            pendingId = null;
            ui.message(Messages.shareNeedsViewer());
            return;
        }
        answer = null;
        pendingId = layerId;
        pendingLabel = l.plan.label();
        ticksLeft = TIMEOUT_TICKS;
    }

    /** {@code markers/shared} from the site (WebSocket thread). */
    public void submitAnswer(String id, String url, String error) {
        answer = new Answer(id, url, error);
    }

    /** Client tick. */
    public void tick() {
        if (pendingId == null) {
            answer = null; // nobody asked: an unrequested link never reaches the chat box
            return;
        }
        Answer a = answer;
        if (a != null) {
            answer = null;
            if (!pendingId.equals(a.id())) return;
            String label = pendingLabel;
            pendingId = null;
            if (a.url() != null && isShareUrl(a.url())) ui.chat(Messages.shareChatText(plainLabel(label), a.url()));
            else ui.message(Messages.shareFailed(plain(a.error(), 80)));
            return;
        }
        if (--ticksLeft <= 0) {
            pendingId = null;
            ui.message(Messages.shareTimeout());
        }
    }

    public boolean isPending() {
        return pendingId != null;
    }

    /** Join/disconnect: nothing pending. */
    public void reset() {
        pendingId = null;
        answer = null;
    }

    /** Exactly a GDOK link to the paste commands of a plan (or the same on localhost, for development). */
    public static boolean isShareUrl(String url) {
        return url != null && url.length() <= 96 && SHARE_URL.matcher(url).matches();
    }

    /** The plan name as it may go into a chat message: plain characters, at most 40. */
    public static String plainLabel(String label) {
        String p = plain(label, 40);
        return p.isEmpty() ? "bomenplan" : p;
    }

    private static String plain(String text, int max) {
        if (text == null) return "";
        String p = text.replaceAll("\\s+", " ").replaceAll("[^\\p{L}\\p{N} .,'()_-]", "").replaceAll(" {2,}", " ").trim();
        return p.length() > max ? p.substring(0, max).trim() : p;
    }
}
