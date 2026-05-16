package nl.geocraft.overlay;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Throttled broadcaster of the local player's position to the GDOK viewer.
 * Sends at most 5 Hz, and only when the player has meaningfully moved.
 */
public class PlayerTracker {

    private static final PlayerTracker INSTANCE = new PlayerTracker();

    private static final long UPDATE_INTERVAL_MS = 100L;
    private static final double POS_DELTA = 0.05;
    private static final float YAW_DELTA = 1.0f;

    private BridgeServer bridge;
    private boolean enabled = false;
    private boolean sentSinceClear = false;

    private UUID uuid;
    private String username;

    private long lastUpdateMs = 0L;
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private float lastYaw = Float.NaN;

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    public void setBridge(BridgeServer bridge) {
        this.bridge = bridge;
    }

    public void setPlayerInfo(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) sendRemove();
        resetDeltaState();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void tick(double x, double y, double z, float yaw, String dimension) {
        if (!enabled || bridge == null || uuid == null) return;

        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) return;

        float yawN = normalizeYaw(yaw);
        if (!changed(x, y, z, yawN)) return;

        lastUpdateMs = now;
        lastX = x; lastY = y; lastZ = z; lastYaw = yawN;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "player");
        msg.addProperty("action", "update");
        msg.addProperty("uuid", uuid.toString());
        msg.addProperty("username", username);
        msg.addProperty("x", x);
        msg.addProperty("y", y);
        msg.addProperty("z", z);
        msg.addProperty("yaw", yawN);
        msg.addProperty("dimension", dimension);
        bridge.broadcastMessage(msg);
        sentSinceClear = true;
    }

    /** Call when the player disconnects or leaves a world. */
    public void clear() {
        sendRemove();
        resetDeltaState();
        uuid = null;
        username = null;
    }

    private void sendRemove() {
        if (bridge == null || !sentSinceClear) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "player");
        msg.addProperty("action", "remove");
        bridge.broadcastMessage(msg);
        sentSinceClear = false;
    }

    private boolean changed(double x, double y, double z, float yaw) {
        if (Double.isNaN(lastX)) return true;
        return Math.abs(x - lastX) > POS_DELTA
                || Math.abs(y - lastY) > POS_DELTA
                || Math.abs(z - lastZ) > POS_DELTA
                || Math.abs(yawDiff(yaw, lastYaw)) > YAW_DELTA;
    }

    private void resetDeltaState() {
        lastUpdateMs = 0L;
        lastX = lastY = lastZ = Double.NaN;
        lastYaw = Float.NaN;
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw % 360f;
        if (y < 0) y += 360f;
        return y;
    }

    private static float yawDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        else if (d < -180f) d += 360f;
        return d;
    }
}
