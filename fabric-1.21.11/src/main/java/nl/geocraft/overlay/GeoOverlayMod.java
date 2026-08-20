package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeoOverlayMod implements ClientModInitializer {
    public static final String MOD_ID = "geocraft-overlay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BridgeServer bridgeServer;
    private static OverlayRenderer rendererInstance;

    private static final int BLOCK_LIMIT_WARN_INTERVAL = 100;
    private int blockLimitWarnTicks = BLOCK_LIMIT_WARN_INTERVAL;
    /** Ticks until the one-time "druk op G" chat hint; 0 = not armed. Delayed so the chat HUD is up. */
    private int gKeyHintTicks = 0;

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
        OverlayConfig.init(FabricLoader.getInstance().getConfigDir());
        OverlayConfig.getInstance().load();

        OverlayManager overlayManager = OverlayManager.getInstance();

        // Dev-workflow: in een Fabric-ontwikkelomgeving (runClient) staat de server-gate
        // open, zodat singleplayer/LAN-werelden bruikbaar zijn om te testen. ServerGate
        // logt hierbij een duidelijke waarschuwing, zodat een gelekte dev-jar herkenbaar is.
        ServerGate.getInstance().setDevBypass(FabricLoader.getInstance().isDevelopmentEnvironment());

        // Start WebSocket bridge server
        // Poort is alleen voor de gametests instelbaar (zie build.gradle); de site verbindt altijd met 4945.
        int bridgePort = Integer.getInteger("geocraft.overlay.port", 4945);
        bridgeServer = new BridgeServer(bridgePort, overlayManager);
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
        rendererInstance = renderer;
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(renderer::render);
        // Occupancy-bits van net geladen chunks opnieuw scannen (zie OccupancyUpdater).
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                renderer.onChunkLoad(chunk.getPos().x, chunk.getPos().z));

        // BungeeCord plugin channel for sub-server detection
        BungeeCordChannel.register(serverName -> {
            ServerGate.getInstance().setCurrentSubserver(serverName);
            refreshPlayerTracking();
            bridgeServer.broadcastGateStatus(ServerGate.getInstance().isAllowed());
            // Sub-server kan gate van blocked → allowed brengen; vraag dan ook resync.
            if (ServerGate.getInstance().isAllowed()) {
                bridgeServer.requestOverlaySync();
                armGKeyHint();
            }
        });

        // Player join: reset gate state, detect server, ask proxy for sub-server name
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerGate gate = ServerGate.getInstance();
            gate.reset();
            overlayManager.resetSession();

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
                armGKeyHint();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerGate.getInstance().reset();
            overlayManager.resetSession();
            PlayerTracker.getInstance().clear();
            bridgeServer.broadcastGateStatus(false);
        });

        // Keybinds + per-tick player tracking
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            renderer.tick(client);

            while (SETTINGS_KEY.wasPressed()) {
                client.setScreen(new OverlayManagerScreen());
            }
            while (Y_UP_KEY.wasPressed()) {
                adjustHeight(overlayManager, 1, client);
            }
            while (Y_DOWN_KEY.wasPressed()) {
                adjustHeight(overlayManager, -1, client);
            }

            if (gKeyHintTicks > 0 && client.player != null && --gKeyHintTicks == 0) {
                client.player.sendMessage(net.minecraft.text.Text.literal(Messages.gKeyHint()), false);
                OverlayConfig.getInstance().setGKeyHintShown(true);
                OverlayConfig.getInstance().save();
            }

            if (client.player != null && client.world != null) {
                PlayerTracker.getInstance().tick(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYaw(),
                        client.world.getRegistryKey().getValue().toString()
                );

                // Actionbar-waarschuwing blijft ~2 s staan; 1x per 100 ticks volstaat.
                if (overlayManager.isOverBlockLimit() && ++blockLimitWarnTicks >= BLOCK_LIMIT_WARN_INTERVAL) {
                    blockLimitWarnTicks = 0;
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

        LOGGER.info("[GeoCraft Overlay] WebSocket bridge actief op poort {}", bridgePort);
    }

    /** Schedule the one-time G-menu hint after the first allowed join (persisted flag, see OverlayConfig). */
    private void armGKeyHint() {
        if (OverlayConfig.getInstance().isGKeyHintShown() || gKeyHintTicks > 0) return;
        gKeyHintTicks = 60;
    }

    /** The active renderer (diagnostics/gametests). */
    public static OverlayRenderer getRenderer() {
        return rendererInstance;
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
        // Zelfde niveau aan: alleen het gedeelde niveau schuift; uit: elke overlay zijn eigen Y.
        overlayManager.adjustAllY(delta);

        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(Messages.overlayYDelta(delta)), true);
        }

        // Hoogtes terugmelden aan de site (compat met een oudere site die ze toont).
        bridgeServer.broadcastOverlayYs();
    }
}
