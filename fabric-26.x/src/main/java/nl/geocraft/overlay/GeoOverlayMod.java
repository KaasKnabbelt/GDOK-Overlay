package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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

        // Dev-workflow: in een Fabric-ontwikkelomgeving (runClient) staat de server-gate
        // open, zodat singleplayer/LAN-werelden bruikbaar zijn om te testen. ServerGate
        // logt hierbij een duidelijke waarschuwing, zodat een gelekte dev-jar herkenbaar is.
        ServerGate.getInstance().setDevBypass(FabricLoader.getInstance().isDevelopmentEnvironment());

        bridgeServer = new BridgeServer(4945, overlayManager);
        BridgeServer.setCommandRunner(command -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            client.execute(() -> {
                if (client.player != null && client.player.connection != null) {
                    client.player.connection.sendCommand(command);
                }
            });
        });
        bridgeServer.start();
        PlayerTracker.getInstance().setBridge(bridgeServer);

        OverlayRenderer renderer = new OverlayRenderer(overlayManager);
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);

        BungeeCordChannel.register(serverName -> {
            ServerGate.getInstance().setCurrentSubserver(serverName);
            refreshPlayerTracking();
            bridgeServer.broadcastGateStatus(ServerGate.getInstance().isAllowed());
            // Sub-server kan gate van blocked → allowed brengen; vraag dan ook resync.
            if (ServerGate.getInstance().isAllowed()) {
                bridgeServer.requestOverlaySync();
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerGate gate = ServerGate.getInstance();
            gate.reset();
            overlayManager.clearCategory("all");

            // Detect server address (primary gate check)
            var serverData = client.getCurrentServer();
            if (serverData != null) {
                gate.checkServerAddress(serverData.ip);
            } else {
                LOGGER.info("[GeoCraft Overlay] Geen server entry (singleplayer/LAN)");
                gate.checkServerAddress(null);
            }

            if (client.player != null) {
                PlayerTracker.getInstance().setPlayerInfo(
                        client.player.getUUID(),
                        client.player.getGameProfile().name()
                );
            }

            BungeeCordChannel.requestServerName();
            refreshPlayerTracking();
            UpdateChecker.onPlayerJoin();
            bridgeServer.broadcastGateStatus(gate.isAllowed());
            LOGGER.info("[GeoCraft Overlay] Gate status na join: allowed={}", gate.isAllowed());

            // Vraag de viewer alle overlays opnieuw te sturen, zodat ze direct
            // zichtbaar zijn na server-join zonder dat de speler een tekening hoeft
            // aan te raken.
            if (gate.isAllowed()) {
                bridgeServer.requestOverlaySync();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerGate.getInstance().reset();
            overlayManager.clearCategory("all");
            PlayerTracker.getInstance().clear();
            bridgeServer.broadcastGateStatus(false);
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

            if (client.player != null && client.level != null) {
                PlayerTracker.getInstance().tick(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.level.dimension().identifier().toString()
                );

                if (overlayManager.isOverBlockLimit()) {
                    client.player.sendOverlayMessage(
                            net.minecraft.network.chat.Component.literal(Messages.blockLimitReached(overlayManager.getTotalBlocks()))
                    );
                }
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

    /**
     * Turn the player tracker on/off based on config + server gate.
     */
    public static void refreshPlayerTracking() {
        boolean enabled = OverlayConfig.getInstance().isShareLocation()
                && ServerGate.getInstance().isAllowed();
        PlayerTracker.getInstance().setEnabled(enabled);
    }

    private void adjustHeight(OverlayManager overlayManager, int delta, net.minecraft.client.Minecraft client) {
        overlayManager.adjustAllY(delta);

        if (client.player != null) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(Messages.overlayYDelta(delta)));
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
