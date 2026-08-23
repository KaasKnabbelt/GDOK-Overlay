package nl.geocraft.overlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
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

    /**
     * Eigen sectie "GDOK Overlay" in het toetsenscherm (label in assets/.../lang). De
     * keybind-namen zelf blijven de oude strings, zodat bestaande rebinds in options.txt
     * geldig blijven.
     */
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "gdok"));

    private static final KeyMapping SETTINGS_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KEY_CATEGORY)
    );

    private static final KeyMapping Y_UP_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Overlay omhoog", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, KEY_CATEGORY)
    );

    private static final KeyMapping Y_DOWN_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("GDOK Overlay omlaag", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, KEY_CATEGORY)
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GeoCraft Overlay] Mod wordt geladen...");

        OverlayConfig.init(FabricLoader.getInstance().getConfigDir());
        OverlayConfig.getInstance().load();

        OverlayManager overlayManager = OverlayManager.getInstance();

        // Dev-workflow: in een Fabric-ontwikkelomgeving (runClient) staat de server-gate
        // open, zodat singleplayer/LAN-werelden bruikbaar zijn om te testen. ServerGate
        // logt hierbij een duidelijke waarschuwing, zodat een gelekte dev-jar herkenbaar is.
        ServerGate.getInstance().setDevBypass(FabricLoader.getInstance().isDevelopmentEnvironment());

        // Poort is alleen voor de gametests instelbaar (zie build.gradle); de site verbindt altijd met 4945.
        int bridgePort = Integer.getInteger("geocraft.overlay.port", 4945);
        bridgeServer = new BridgeServer(bridgePort, overlayManager);
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
        rendererInstance = renderer;
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);
        // Occupancy-bits van net geladen chunks opnieuw scannen (zie OccupancyUpdater).
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                renderer.onChunkLoad(chunk.getPos().x(), chunk.getPos().z()));

        // Zelf een blok breken of plaatsen: markeer de chunk meteen dirty, zodat de overlay
        // binnen een tick omschakelt in plaats van te wachten op de trage round-robin-scan.
        net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register(
                (level, player, pos, state) -> renderer.onBlockChanged(pos.getX(), pos.getZ()));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) {
                // Het geplaatste blok landt op de aangeklikte positie of de buurpositie
                // aan de aangeklikte zijde; markeer beide (meestal dezelfde chunk).
                var pos = hitResult.getBlockPos();
                renderer.onBlockChanged(pos.getX(), pos.getZ());
                var placed = pos.relative(hitResult.getDirection());
                renderer.onBlockChanged(placed.getX(), placed.getZ());
            }
            return net.minecraft.world.InteractionResult.PASS;
        });

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

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerGate gate = ServerGate.getInstance();
            gate.reset();
            overlayManager.resetSession();

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
                armGKeyHint();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerGate.getInstance().reset();
            overlayManager.resetSession();
            PlayerTracker.getInstance().clear();
            bridgeServer.broadcastGateStatus(false);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            renderer.tick(client);

            while (SETTINGS_KEY.consumeClick()) {
                client.gui.setScreen(new OverlayManagerScreen());
            }
            while (Y_UP_KEY.consumeClick()) {
                adjustHeight(overlayManager, 1, client);
            }
            while (Y_DOWN_KEY.consumeClick()) {
                adjustHeight(overlayManager, -1, client);
            }

            if (gKeyHintTicks > 0 && client.player != null && --gKeyHintTicks == 0) {
                client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(Messages.gKeyHint()));
                OverlayConfig.getInstance().setGKeyHintShown(true);
                OverlayConfig.getInstance().save();
            }

            if (client.player != null && client.level != null) {
                PlayerTracker.getInstance().tick(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.level.dimension().identifier().toString()
                );

                // Actionbar-waarschuwing blijft ~2 s staan; 1x per 100 ticks volstaat.
                if (overlayManager.isOverBlockLimit() && ++blockLimitWarnTicks >= BLOCK_LIMIT_WARN_INTERVAL) {
                    blockLimitWarnTicks = 0;
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
     */
    public static void refreshPlayerTracking() {
        boolean enabled = OverlayConfig.getInstance().isShareLocation()
                && ServerGate.getInstance().isAllowed();
        PlayerTracker.getInstance().setEnabled(enabled);
    }

    private void adjustHeight(OverlayManager overlayManager, int delta, net.minecraft.client.Minecraft client) {
        // Zelfde niveau aan: alleen het gedeelde niveau schuift; uit: elke overlay zijn eigen Y.
        overlayManager.adjustAllY(delta);

        if (client.player != null) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(Messages.overlayYDelta(delta)));
        }

        // Hoogtes terugmelden aan de site (compat met een oudere site die ze toont).
        bridgeServer.broadcastOverlayYs();
    }
}
