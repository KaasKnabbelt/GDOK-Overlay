package nl.geocraft.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * WebSocket server that receives overlay commands from the GDOK web application.
 * Runs on localhost:4945 and accepts JSON messages.
 */
public class BridgeServer extends WebSocketServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private final OverlayManager overlayManager;

    public BridgeServer(int port, OverlayManager overlayManager) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.overlayManager = overlayManager;
        setReuseAddr(true);
        setDaemon(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("[GeoCraft Overlay] GDOK verbonden vanuit browser");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("[GeoCraft Overlay] GDOK verbinding verbroken");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String type = msg.get("type").getAsString();

            if ("pong".equals(type)) return;

            if (!"overlay".equals(type)) return;

            String action = msg.get("action").getAsString();

            switch (action) {
                case "add" -> handleAdd(msg);
                case "remove" -> handleRemove(msg);
                case "clear" -> handleClear(msg);
                case "updateY" -> handleUpdateY(msg);
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
        String id = msg.get("id").getAsString();
        String category = msg.get("category").getAsString();
        String label = msg.has("label") ? msg.get("label").getAsString() : "";
        String tag = msg.has("tag") && !msg.get("tag").isJsonNull() ? msg.get("tag").getAsString() : "white_wool";

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

        overlayManager.addOverlay(new OverlayData(id, category, blocks, y, r, g, b, a, label, tag));
        LOGGER.debug("[GeoCraft Overlay] Overlay '{}' toegevoegd: {} blokken ({}) y={}", id, blocks.length, category, y);
    }

    private void handleRemove(JsonObject msg) {
        String id = msg.get("id").getAsString();
        overlayManager.removeOverlay(id);
    }

    private void handleClear(JsonObject msg) {
        String category = msg.get("category").getAsString();
        overlayManager.clearCategory(category);
    }

    private void handleUpdateY(JsonObject msg) {
        String id = msg.get("id").getAsString();
        int y = msg.get("y").getAsInt();
        overlayManager.updateOverlayY(id, y);
        LOGGER.debug("[GeoCraft Overlay] Overlay '{}' Y bijgewerkt naar {}", id, y);
    }
}
