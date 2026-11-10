package nl.geocraft.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nl.geocraft.overlay.markers.MarkerLayer;
import nl.geocraft.overlay.markers.MarkerManager;
import nl.geocraft.overlay.markers.MarkerPlanParser;
import nl.geocraft.overlay.markers.PlanShare;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * WebSocket server that receives overlay commands from the GDOK web application.
 * Runs on localhost:4945 and accepts JSON messages.
 *
 * <h2>Protocol</h2>
 * <p>Site → mod: {@code overlay/add|remove|clear|updateY|setHidden}, {@code command/run},
 * {@code pong}. Mod → site: {@code hello} (on connect, with {@link #PROTOCOL}),
 * {@code world/allowed|blocked}, {@code overlay/requestSync}, {@code overlay/updateY}
 * (legacy height echo), {@code overlay/state} (hidden/pinned/y of one overlay),
 * {@code overlay/removed} (the player removed it in-game; the site drops it too), {@code ping},
 * {@code player/update|remove}. Protocol 2 (mod 1.1.0) added hello, setHidden, state and
 * removed; a site that never sees {@code hello} falls back to the 1.0.x behaviour.</p>
 *
 * <p>Protocol 3 (1.1.2, tree plans, BOMEN.md §9.1) adds {@code features} to the hello and the
 * marker layers. Site → mod: {@code markers/set} (see {@link MarkerPlanParser}; since beta.3
 * with {@code paste: {flags}}), {@code markers/remove {id}}. Mod → site:
 * {@code markers/progress {id, total, placed, planted, blocked, existing, empty, foreign?, nog}}
 * ({@code nog} since beta.4: per marker block, in the order of the plan's {@code blocks}, the
 * markers still to do: to put down plus blocked)
 * ({@code foreign}: marker blocks in the area that are not part of the plan, once checked) and
 * {@code markers/removed {id}}. The features since beta.3: {@code markers}, {@code place}
 * (right-click on a ghost places it) and {@code paste} (the paste commands in the chat). Beta.1
 * and beta.2 announced {@code plant} and ran the commands themselves; beta.3 runs nothing.</p>
 *
 * <p>Since beta.4 the site answers the hello with {@code site/hello {features}}; a viewer that
 * names {@code goto} gets {@code view/goto {x, z}} from {@code /gdok} and flies to that block
 * ({@link #sendGoto}). Older mods ignore {@code site/hello}; an older site never sends it, and
 * then {@code /gdok} opens the browser.</p>
 *
 * <p>Since 1.2.1 a viewer that names {@code share} can be asked for a link to the paste commands
 * of a plan: {@code markers/share {id}} from the mod, answered with
 * {@code markers/shared {id, url}} or {@code {id, error}} ({@link PlanShare}). The mod names
 * {@code share} in its own hello too.</p>
 *
 * <h2>Security</h2>
 * <p>Any web page can try to open {@code ws://127.0.0.1:4945}. The handshake therefore only
 * accepts the GDOK origins ({@link #isAllowedOrigin}), and {@code command/run} only runs
 * {@code tppos} ({@link #isAllowedCommand}), the one command the site sends.</p>
 */
public class BridgeServer extends WebSocketServer {

    /** Bumped when the site needs to know about new message types; announced in {@code hello}. */
    public static final int PROTOCOL = 3;
    /** What this mod can do beyond the overlays; the site shows its buttons only for these. */
    public static final List<String> FEATURES = List.of("markers", "place", "paste", "share");

    private static final Pattern ALLOWED_ORIGIN = Pattern.compile(
            "https://(www\\.)?gdok\\.nl|http://(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d{1,5})?");
    /** {@code tppos x, z} or {@code tppos x, y, z} as coordinates.js sends it. */
    private static final Pattern ALLOWED_COMMAND = Pattern.compile(
            "tppos(\\s*,?\\s*-?\\d{1,8}(\\.\\d{1,3})?){2,3}");

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private final OverlayManager overlayManager;
    private static java.util.function.Consumer<String> commandRunner;
    private static BridgeServer instance;
    /** Connections whose viewer understands {@code view/goto} (it said so in {@code site/hello}). */
    private final Set<WebSocket> gotoViewers = ConcurrentHashMap.newKeySet();
    /** Connections whose viewer can make a share link ({@code share} in {@code site/hello}). */
    private final Set<WebSocket> shareViewers = ConcurrentHashMap.newKeySet();

    /**
     * Only these pages may connect: gdok.nl, and localhost/127.0.0.1 for development. A client
     * without an Origin header is not a web page (the gametests, a local tool) and may too.
     */
    public static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return true;
        return ALLOWED_ORIGIN.matcher(origin.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** The whitelist of {@code command/run} (without leading slash): only {@code tppos}. */
    public static boolean isAllowedCommand(String command) {
        return command != null && command.length() <= 64 && ALLOWED_COMMAND.matcher(command.trim()).matches();
    }

    public static BridgeServer getInstance() {
        return instance;
    }

    /**
     * Register a callback that runs a chat command in-game. The callback is
     * version-specific (different MC API per loader/MC version) and is responsible
     * for dispatching to the client thread.
     */
    public static void setCommandRunner(java.util.function.Consumer<String> runner) {
        commandRunner = runner;
    }

    public BridgeServer(int port, OverlayManager overlayManager) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.overlayManager = overlayManager;
        setReuseAddr(true);
        setDaemon(true);
        instance = this;
        MarkerManager.getInstance().setReporter(new MarkerManager.Reporter() {
            @Override
            public void progress(MarkerLayer layer) {
                broadcastMarkerProgress(layer);
            }

            @Override
            public void removed(String id) {
                broadcastMarkersRemoved(id);
            }
        });
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        String origin = request.getFieldValue("Origin");
        if (!isAllowedOrigin(origin)) {
            LOGGER.warn("[GeoCraft Overlay] Verbinding geweigerd van origin '{}'", origin);
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin niet toegestaan");
        }
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        builder.put("Access-Control-Allow-Private-Network", "true");
        return builder;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("[GeoCraft Overlay] GDOK verbonden vanuit browser");
        // Hello first: the site waits for it before re-sending its overlays, so it knows
        // whether setHidden/state/removed are understood.
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", PROTOCOL);
        JsonArray features = new JsonArray();
        FEATURES.forEach(features::add);
        hello.add("features", features);
        conn.send(hello.toString());
        // Send current gate status so the browser knows immediately
        conn.send(buildGateMessage(ServerGate.getInstance().isAllowed()).toString());
        // De speler staat vaak stil als een viewer opent, en PlayerTracker stuurt alleen
        // bij beweging. Zonder deze zet verscheen de playerhead pas na de eerste stap.
        PlayerTracker.getInstance().forceResend();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        gotoViewers.remove(conn);
        shareViewers.remove(conn);
        LOGGER.info("[GeoCraft Overlay] GDOK verbinding verbroken");
    }

    /**
     * {@code /gdok} (C3, chosen by Jens on 31 October 2026): every open viewer that understands
     * {@code view/goto} flies to block (x, z). False when there is none; the caller then opens
     * the browser.
     */
    public boolean sendGoto(int x, int z) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "view");
        msg.addProperty("action", "goto");
        msg.addProperty("x", x);
        msg.addProperty("z", z);
        String json = msg.toString();
        boolean sent = false;
        for (WebSocket conn : gotoViewers) {
            if (!conn.isOpen()) continue;
            try {
                conn.send(json);
                sent = true;
            } catch (RuntimeException e) {
                // The tab closed between isOpen and send; the next viewer, or the browser, then.
                LOGGER.debug("[GeoCraft Overlay] goto niet verstuurd: {}", e.getMessage());
            }
        }
        return sent;
    }

    /**
     * Ask the open viewers for a link to the paste commands of this plan ({@link PlanShare}).
     * False when no viewer is connected that can (none open, or an older site).
     */
    public boolean requestShare(String id) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "markers");
        msg.addProperty("action", "share");
        msg.addProperty("id", id);
        String json = msg.toString();
        boolean sent = false;
        for (WebSocket conn : shareViewers) {
            if (!conn.isOpen()) continue;
            try {
                conn.send(json);
                sent = true;
            } catch (RuntimeException e) {
                LOGGER.debug("[GeoCraft Overlay] share niet verstuurd: {}", e.getMessage());
            }
        }
        return sent;
    }

    /**
     * Broadcast the current server gate status to all connected browsers.
     */
    public void broadcastGateStatus(boolean allowed) {
        broadcastMessage(buildGateMessage(allowed));
    }

    /**
     * Vraag verbonden GDOK-viewers om alle actieve overlays opnieuw te sturen.
     * Wordt gebruikt na een server-join: de mod heeft zijn overlay-store gewist, dus
     * de viewer moet de tekenwerk opnieuw insturen zodat alles direct zichtbaar is.
     */
    public void requestOverlaySync() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "overlay");
        msg.addProperty("action", "requestSync");
        broadcastMessage(msg);
    }

    /**
     * Stuur de site voor elke overlay de hoogte waarop hij nu getekend wordt
     * ({@link OverlayManager#getRenderY}). Na PageUp/Down, "Reset hoogtes" en een
     * zelfde-niveau-wissel; alleen nog voor compatibiliteit met een oudere site, die
     * de hoogte in zijn paneel toont. De mod is eigenaar van de hoogte.
     */
    public void broadcastOverlayYs() {
        for (OverlayData overlay : overlayManager.getOverlays()) {
            broadcastOverlayY(overlay.id(), overlayManager.getRenderY(overlay));
        }
    }

    private JsonObject buildGateMessage(boolean allowed) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "world");
        msg.addProperty("action", allowed ? "allowed" : "blocked");
        if (!allowed) msg.addProperty("reason", "not-geocraft-world");
        return msg;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String type = msg.get("type").getAsString();

            if ("pong".equals(type)) return;

            String action = msg.get("action").getAsString();

            if ("overlay".equals(type)) {
                switch (action) {
                    case "add" -> handleAdd(msg);
                    case "remove" -> handleRemove(msg);
                    case "clear" -> handleClear(msg);
                    case "updateY" -> handleUpdateY(msg);
                    case "setHidden" -> handleSetHidden(msg);
                }
            } else if ("command".equals(type)) {
                if ("run".equals(action)) {
                    handleCommand(msg);
                }
            } else if ("site".equals(type)) {
                if ("hello".equals(action)) handleSiteHello(conn, msg);
            } else if ("markers".equals(type)) {
                switch (action) {
                    case "set" -> handleMarkersSet(msg);
                    case "remove" -> MarkerManager.getInstance().submitRemove(msg.get("id").getAsString());
                    case "shared" -> PlanShare.getInstance().submitAnswer(
                            msg.get("id").getAsString(), optString(msg, "url"), optString(msg, "error"));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[GeoCraft Overlay] Fout bij verwerken bericht: {}", e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.error("[GeoCraft Overlay] WebSocket fout", ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("[GeoCraft Overlay] Bridge server gestart op poort {}", getPort());
    }

    /** Is at least one GDOK viewer (browser tab) connected? Shown in the in-game menu. */
    public boolean hasClients() {
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) return true;
        }
        return false;
    }

    /**
     * Send a JSON message to all connected GDOK clients.
     */
    public void broadcastMessage(JsonObject msg) {
        String json = msg.toString();
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    private void handleAdd(JsonObject msg) {
        if (!ServerGate.getInstance().isAllowed()) {
            JsonObject reject = new JsonObject();
            reject.addProperty("type", "world");
            reject.addProperty("action", "blocked");
            reject.addProperty("reason", "not-geocraft-world");
            broadcastMessage(reject);
            return;
        }

        String id = msg.get("id").getAsString();
        String category = msg.get("category").getAsString();
        String label = msg.has("label") ? msg.get("label").getAsString() : "";
        // Geen tag (bijv. de klik-marker) blijft null: de renderer tekent zo'n overlay altijd
        // als getint tapijt, ook in fullblock-modus, want er is geen bloktexture om te tonen.
        String tag = msg.has("tag") && !msg.get("tag").isJsonNull() ? msg.get("tag").getAsString() : null;
        if (tag != null && tag.isBlank()) tag = null;

        // Parse Y level (MC Y from AHN height)
        int y = msg.has("y") ? msg.get("y").getAsInt() : 64;

        // Parse color [r, g, b, a]
        JsonArray colorArr = msg.getAsJsonArray("color");
        int r = colorArr.get(0).getAsInt();
        int g = colorArr.get(1).getAsInt();
        int b = colorArr.get(2).getAsInt();
        int a = colorArr.get(3).getAsInt();

        // Parse blocks [{x, z}, ...]
        JsonArray blocksArr = msg.getAsJsonArray("blocks");
        OverlayData.BlockPos[] blocks = new OverlayData.BlockPos[blocksArr.size()];
        for (int i = 0; i < blocksArr.size(); i++) {
            JsonObject block = blocksArr.get(i).getAsJsonObject();
            blocks[i] = new OverlayData.BlockPos(
                    block.get("x").getAsInt(),
                    block.get("z").getAsInt()
            );
        }

        OverlayData overlay = new OverlayData(id, category, blocks, y, r, g, b, a, label, tag);
        if (!overlayManager.addOverlay(overlay)) return;
        LOGGER.debug("[GeoCraft Overlay] Overlay '{}' toegevoegd: {} blokken ({}) y={}", id, blocks.length, category, y);

        // De mod is eigenaar van de hoogte: meld terug waarop deze overlay écht getekend wordt
        // (gedeeld niveau, of een eerder in-game aangepaste Y bij een herzending). Anders
        // toont het site-paneel na elke penseelstreek weer de AHN-hint.
        OverlayData stored = overlayManager.getOverlay(id);
        int renderY = overlayManager.getRenderY(stored == null ? overlay : stored);
        if (renderY != y) broadcastOverlayY(id, renderY);
        // Na een herzending (site-F5, penseelstreek) weet de site zo weer of de laag hier
        // verborgen of vastgezet is.
        if (overlayManager.isHidden(id) || overlayManager.isPinned(id)) broadcastState(id);
    }

    /** Mirror of one overlay's in-game state (hidden, pinned, drawn Y) for the site panel. */
    public void broadcastState(String id) {
        OverlayData overlay = overlayManager.getOverlay(id);
        if (overlay == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "overlay");
        msg.addProperty("action", "state");
        msg.addProperty("id", id);
        msg.addProperty("hidden", overlayManager.isHidden(id));
        msg.addProperty("pinned", overlayManager.isPinned(id));
        msg.addProperty("y", overlayManager.getRenderY(overlay));
        broadcastMessage(msg);
    }

    /** The player removed an overlay in-game: the site drops the drawing too (one system). */
    public void broadcastRemoved(String id) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "overlay");
        msg.addProperty("action", "removed");
        msg.addProperty("id", id);
        broadcastMessage(msg);
    }

    public void broadcastOverlayY(String id, int y) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "overlay");
        msg.addProperty("action", "updateY");
        msg.addProperty("id", id);
        msg.addProperty("y", y);
        broadcastMessage(msg);
    }

    private void handleRemove(JsonObject msg) {
        String id = msg.get("id").getAsString();
        overlayManager.removeOverlay(id);
    }

    // -- Marker layers (protocol 3) -----------------------------------

    private void handleMarkersSet(JsonObject msg) {
        if (!ServerGate.getInstance().isAllowed()) {
            broadcastMessage(buildGateMessage(false));
            return;
        }
        try {
            MarkerManager.getInstance().submitSet(MarkerPlanParser.parse(msg));
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            LOGGER.warn("[GeoCraft Overlay] Bomenplan geweigerd: {}", e.getMessage());
        }
    }

    private void handleSiteHello(WebSocket conn, JsonObject msg) {
        boolean go = false, share = false;
        if (msg.has("features") && msg.get("features").isJsonArray()) {
            for (var f : msg.getAsJsonArray("features")) {
                if (!f.isJsonPrimitive()) continue;
                if ("goto".equals(f.getAsString())) go = true;
                if ("share".equals(f.getAsString())) share = true;
            }
        }
        if (go) gotoViewers.add(conn);
        else gotoViewers.remove(conn);
        if (share) shareViewers.add(conn);
        else shareViewers.remove(conn);
    }

    private static String optString(JsonObject msg, String key) {
        return msg.has(key) && msg.get(key).isJsonPrimitive() ? msg.get(key).getAsString() : null;
    }

    public void broadcastMarkerProgress(MarkerLayer layer) {
        MarkerLayer.Counts c = layer.counts();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "markers");
        msg.addProperty("action", "progress");
        msg.addProperty("id", layer.id());
        msg.addProperty("total", c.total());
        msg.addProperty("placed", c.placed());
        msg.addProperty("planted", c.planted());
        msg.addProperty("blocked", c.blocked());
        msg.addProperty("existing", c.existing());
        msg.addProperty("empty", c.toPlace());
        if (c.foreign() >= 0) msg.addProperty("foreign", c.foreign());
        // Per marker block (same order as the plan's blocks): still to do, for "nog 6 van 9".
        com.google.gson.JsonArray nog = new com.google.gson.JsonArray();
        for (int n : layer.todoPerBlock()) nog.add(n);
        msg.add("nog", nog);
        broadcastMessage(msg);
    }

    public void broadcastMarkersRemoved(String id) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "markers");
        msg.addProperty("action", "removed");
        msg.addProperty("id", id);
        broadcastMessage(msg);
    }

    private void handleClear(JsonObject msg) {
        String category = msg.get("category").getAsString();
        overlayManager.clearCategory(category);
    }

    private void handleSetHidden(JsonObject msg) {
        String id = msg.get("id").getAsString();
        boolean hidden = msg.get("hidden").getAsBoolean();
        overlayManager.setHidden(id, hidden);
        broadcastState(id); // bevestigt aan alle tabs, ook de afzender
    }

    private void handleUpdateY(JsonObject msg) {
        String id = msg.get("id").getAsString();
        int y = msg.get("y").getAsInt();
        overlayManager.updateOverlayY(id, y);
        LOGGER.debug("[GeoCraft Overlay] Overlay '{}' Y bijgewerkt naar {}", id, y);
    }

    private void handleCommand(JsonObject msg) {
        // Alleen op de GeoCraft server: voorkomt dat een geopende GDOK-tab
        // op een andere server (ander commando-namespace) iets onverwachts doet.
        if (!ServerGate.getInstance().isAllowed()) {
            LOGGER.debug("[GeoCraft Overlay] Command genegeerd: niet op GeoCraft server");
            return;
        }
        if (commandRunner == null) {
            LOGGER.warn("[GeoCraft Overlay] Command ontvangen maar geen runner geregistreerd");
            return;
        }
        String command = msg.get("command").getAsString().trim();
        // sendChatCommand verwacht het commando zonder leidende slash.
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) return;
        // Whitelist: een willekeurige pagina mag hier geen commando's laten uitvoeren (zie klassedoc).
        if (!isAllowedCommand(command)) {
            LOGGER.warn("[GeoCraft Overlay] Command geweigerd (niet op de whitelist): /{}", command);
            return;
        }
        commandRunner.accept(command);
        LOGGER.debug("[GeoCraft Overlay] Command uitgevoerd: /{}", command);
    }
}
