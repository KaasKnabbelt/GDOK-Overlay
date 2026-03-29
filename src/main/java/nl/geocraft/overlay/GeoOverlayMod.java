package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeoOverlayMod implements ClientModInitializer {
    public static final String MOD_ID = "geocraft-overlay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BridgeServer bridgeServer;

    private static final KeyBinding SETTINGS_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("GDOK Settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyBinding.Category.MISC)
    );

    private static final KeyBinding Y_UP_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("GDOK Overlay omhoog", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, KeyBinding.Category.MISC)
    );

    private static final KeyBinding Y_DOWN_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("GDOK Overlay omlaag", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, KeyBinding.Category.MISC)
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeoCraft Overlay] Mod wordt geladen...");

        // Load config
        OverlayConfig.getInstance().load();

        OverlayManager overlayManager = OverlayManager.getInstance();

        // Start WebSocket bridge server
        bridgeServer = new BridgeServer(4945, overlayManager);
        bridgeServer.start();

        // Register world render event for drawing overlays
        OverlayRenderer renderer = new OverlayRenderer(overlayManager);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(renderer::render);

        // Check for updates when player joins a world/server
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UpdateChecker.onPlayerJoin();
        });

        // Keybinds
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (SETTINGS_KEY.wasPressed()) {
                client.setScreen(new OverlaySettingsScreen());
            }
            while (Y_UP_KEY.wasPressed()) {
                adjustHeight(overlayManager, 1, client);
            }
            while (Y_DOWN_KEY.wasPressed()) {
                adjustHeight(overlayManager, -1, client);
            }
        });

        // Clean up on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (bridgeServer != null) {
                    bridgeServer.stop(1000);
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while stopping bridge server", e);
            }
        }));

        LOGGER.info("[GeoCraft Overlay] WebSocket bridge actief op poort 4945");
    }

    private void adjustHeight(OverlayManager overlayManager, int delta, net.minecraft.client.MinecraftClient client) {
        overlayManager.adjustAllY(delta);

        // Notify player
        if (client.player != null) {
            String sign = delta > 0 ? "+" : "";
            client.player.sendMessage(net.minecraft.text.Text.literal("§b[GDOK] §fOverlay Y " + sign + delta), true);
        }

        // Sync back to GDOK website
        for (OverlayData overlay : overlayManager.getOverlays()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "overlay");
            msg.addProperty("action", "updateY");
            msg.addProperty("id", overlay.id());
            msg.addProperty("y", overlay.y());
            bridgeServer.broadcastMessage(msg);
        }
    }
}
