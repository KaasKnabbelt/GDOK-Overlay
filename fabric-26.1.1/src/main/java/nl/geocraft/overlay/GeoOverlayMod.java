package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeoOverlayMod implements ClientModInitializer {
    public static final String MOD_ID = "geocraft-overlay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BridgeServer bridgeServer;

    private static final KeyMapping SETTINGS_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC)
    );

    private static final KeyMapping Y_UP_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Overlay omhoog", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, KeyMapping.Category.MISC)
    );

    private static final KeyMapping Y_DOWN_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Overlay omlaag", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, KeyMapping.Category.MISC)
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeoCraft Overlay] Mod wordt geladen...");

        OverlayConfig.getInstance().load();

        OverlayManager overlayManager = OverlayManager.getInstance();

        bridgeServer = new BridgeServer(4945, overlayManager);
        bridgeServer.start();

        OverlayRenderer renderer = new OverlayRenderer(overlayManager);
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UpdateChecker.onPlayerJoin();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (SETTINGS_KEY.consumeClick()) {
                client.setScreen(new OverlaySettingsScreen());
            }
            while (Y_UP_KEY.consumeClick()) {
                adjustHeight(overlayManager, 1, client);
            }
            while (Y_DOWN_KEY.consumeClick()) {
                adjustHeight(overlayManager, -1, client);
            }
        });

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

    private void adjustHeight(OverlayManager overlayManager, int delta, net.minecraft.client.Minecraft client) {
        overlayManager.adjustAllY(delta);

        if (client.player != null) {
            String sign = delta > 0 ? "+" : "";
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("\u00A7a\u00A7l[GDOK] \u00A7fOverlay Y " + sign + delta));
        }

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
