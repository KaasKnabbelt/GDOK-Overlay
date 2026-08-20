package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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

        // Dev-workflow: in een Fabric-ontwikkelomgeving (runClient) staat de server-gate
        // open, zodat singleplayer/LAN-werelden bruikbaar zijn om te testen. ServerGate
        // logt hierbij een duidelijke waarschuwing, zodat een gelekte dev-jar herkenbaar is.
        ServerGate.getInstance().setDevBypass(FabricLoader.getInstance().isDevelopmentEnvironment());

        // Start WebSocket bridge server
        bridgeServer = new BridgeServer(4945, overlayManager);
        BridgeServer.setCommandRunner(command -> {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            client.execute(() -> {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatCommand(command);
                }
            });
        });
        bridgeServer.start();
        PlayerTracker.getInstance().setBridge(bridgeServer);

        // Register world render event for drawing overlays
        OverlayRenderer renderer = new OverlayRenderer(overlayManager);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(renderer::render);

        // BungeeCord plugin channel for sub-server detection
        BungeeCordChannel.register(serverName -> {
            ServerGate.getInstance().setCurrentSubserver(serverName);
            refreshPlayerTracking();
            bridgeServer.broadcastGateStatus(ServerGate.getInstance().isAllowed());
            // Sub-server kan gate van blocked → allowed brengen; vraag dan ook resync.
            if (ServerGate.getInstance().isAllowed()) {
                bridgeServer.requestOverlaySync();
            }
        });

        // Player join: reset gate state, detect server, ask proxy for sub-server name
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerGate gate = ServerGate.getInstance();
            gate.reset();
            overlayManager.clearCategory("all");

            // Detect server address (primary gate check)
            var serverEntry = client.getCurrentServerEntry();
            if (serverEntry != null) {
                gate.checkServerAddress(serverEntry.address);
            } else {
                LOGGER.info("[GeoCraft Overlay] Geen server entry (singleplayer/LAN)");
                gate.checkServerAddress(null);
            }

            // Capture player info for location sharing
            if (client.player != null) {
                PlayerTracker.getInstance().setPlayerInfo(
                        client.player.getUuid(),
                        client.player.getGameProfile().name()
                );
            }

            // Also try BungeeCord sub-server detection (secondary/refinement)
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

        // Keybinds + per-tick player tracking
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

            if (client.player != null && client.world != null) {
                PlayerTracker.getInstance().tick(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYaw(),
                        client.world.getRegistryKey().getValue().toString()
                );

                if (overlayManager.isOverBlockLimit()) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(Messages.blockLimitReached(overlayManager.getTotalBlocks())),
                            true
                    );
                }
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

    /**
     * Turn the player tracker on/off based on config + server gate.
     * Called whenever either input changes.
     */
    public static void refreshPlayerTracking() {
        boolean enabled = OverlayConfig.getInstance().isShareLocation()
                && ServerGate.getInstance().isAllowed();
        PlayerTracker.getInstance().setEnabled(enabled);
    }

    private void adjustHeight(OverlayManager overlayManager, int delta, net.minecraft.client.MinecraftClient client) {
        overlayManager.adjustAllY(delta);

        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(Messages.overlayYDelta(delta)), true);
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
