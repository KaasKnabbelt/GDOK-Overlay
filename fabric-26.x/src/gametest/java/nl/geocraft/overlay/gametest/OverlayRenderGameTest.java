package nl.geocraft.overlay.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import nl.geocraft.overlay.GeoOverlayMod;
import nl.geocraft.overlay.OverlayConfig;
import nl.geocraft.overlay.OverlayData;
import nl.geocraft.overlay.OverlayManager;
import nl.geocraft.overlay.render.RenderMode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end check of the overlay renderer in a real client: flat world, camera straight
 * down, overlays injected through the WebSocket bridge and the manager, screenshots
 * measured on pixel level. Run with {@code gradlew :fabric-26.x:runClientGameTest}.
 *
 * <p>Surface of the flat preset: grass at y=-61, so the overlay level is y=-60.</p>
 */
public class OverlayRenderGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay-gametest");
    private static final int OVERLAY_Y = -60;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            OverlayConfig.getInstance().setOpacityPercent(100);
            OverlayConfig.getInstance().setRenderMode(RenderMode.FULLBLOCK);
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            // Zonder dit zakt een venster zonder focus naar 60 fps en zegt de meting niets.
            mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            mc.options.renderDistance().set(8);
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            sp.getServer().runCommand("gamemode spectator @a");
            sp.getServer().runCommand("tp @a 8.5 -47 8.5 0 90");
            // Fabric's waitForChunksDownload eist het hele render-vierkant; wij hebben alleen de
            // chunks rond de overlay nodig.
            ctx.waitFor(mc -> mc.level != null
                    && mc.level.hasChunk(0, 0) && mc.level.hasChunk(1, 1)
                    && mc.level.hasChunk(-1, -1) && mc.level.hasChunk(1, -1) && mc.level.hasChunk(-1, 1), 1200);
            ctx.waitTicks(100);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); // HUD weg
            ctx.waitTicks(20);

            Path baseline = ctx.takeScreenshot("00-baseline");
            double magentaBase = ScreenshotProbe.centralFraction(baseline, ScreenshotProbe::isMagenta);
            assertThat(magentaBase < 0.02, "baseline zonder overlay bevat magenta: " + magentaBase);

            // 1. Volledige blokken via de echte WebSocket-bridge (JSON-pad).
            sendViaBridge(addMessage("paint_magenta_wool", "paint", 0, 0, 16, OVERLAY_Y, "magenta_wool", 255, 0, 255));
            ctx.waitFor(mc -> OverlayManager.getInstance().getOverlay("paint_magenta_wool") != null);
            ctx.waitTicks(20);
            Path full = ctx.takeScreenshot("01-fullblock");
            double magentaFull = ScreenshotProbe.centralFraction(full, ScreenshotProbe::isMagenta);
            LOGGER.info("[gametest] fullblock magenta-fractie = {}", magentaFull);
            assertThat(magentaFull > 0.5, "fullblock-overlay niet zichtbaar: " + magentaFull);

            // 2. Dun tapijt (getint) zonder herstart.
            ctx.runOnClient(mc -> OverlayConfig.getInstance().setRenderMode(RenderMode.CARPET));
            ctx.waitTicks(10);
            Path carpet = ctx.takeScreenshot("02-carpet");
            double magentaCarpet = ScreenshotProbe.centralFraction(carpet, ScreenshotProbe::isMagenta);
            LOGGER.info("[gametest] carpet magenta-fractie = {}", magentaCarpet);
            assertThat(magentaCarpet > 0.5, "carpet-overlay niet zichtbaar: " + magentaCarpet);
            ctx.runOnClient(mc -> OverlayConfig.getInstance().setRenderMode(RenderMode.FULLBLOCK));

            // 3. Verbergen/tonen.
            ctx.runOnClient(mc -> OverlayManager.getInstance().setHidden("paint_magenta_wool", true));
            ctx.waitTicks(10);
            Path hidden = ctx.takeScreenshot("03-hidden");
            double magentaHidden = ScreenshotProbe.centralFraction(hidden, ScreenshotProbe::isMagenta);
            assertThat(magentaHidden < 0.02, "verborgen overlay nog zichtbaar: " + magentaHidden);
            ctx.runOnClient(mc -> OverlayManager.getInstance().setHidden("paint_magenta_wool", false));
            ctx.waitTicks(10);

            // 4. Echte blokken in de westelijke helft (x 0..7): slab weg (occupancy-scan), oostelijke
            //    helft blijft. De camera kijkt met yaw 0 naar +z, dus west staat RECHTS in beeld.
            sp.getServer().runCommand("fill 0 -60 0 7 -60 15 minecraft:stone");
            ctx.waitTicks(60);
            Path occupied = ctx.takeScreenshot("04-occupied");
            double east = ScreenshotProbe.halfFraction(occupied, true, ScreenshotProbe::isMagenta);
            double west = ScreenshotProbe.halfFraction(occupied, false, ScreenshotProbe::isMagenta);
            LOGGER.info("[gametest] occupied: oost(vrij)={} west(steen)={}", east, west);
            assertThat(east > 0.5, "oostelijke helft (vrij) toont geen overlay: " + east);
            assertThat(west < 0.25, "westelijke helft (steen) toont nog de slab: " + west);

            // 5. Blokken weer weg: slab komt terug binnen enkele seconden (round-robin-herpoll).
            sp.getServer().runCommand("fill 0 -60 0 7 -60 15 minecraft:air");
            int waited = 0;
            double westFreed = 0;
            while (waited < 400) {
                ctx.waitTicks(20);
                waited += 20;
                westFreed = ScreenshotProbe.halfFraction(ctx.takeScreenshot("05-freed-probe"), false, ScreenshotProbe::isMagenta);
                if (westFreed > 0.5) break;
            }
            LOGGER.info("[gametest] slab terug na {} ticks (fractie {})", waited, westFreed);
            assertThat(westFreed > 0.5, "slab komt niet terug na weghalen van de blokken: " + westFreed);

            // 6. Hoogte-wissel zonder rebuild: PageUp-equivalent. Op y-59 zweeft de laag een blok hoger.
            ctx.runOnClient(mc -> OverlayManager.getInstance().adjustAllY(1));
            ctx.waitTicks(10);
            Path raised = ctx.takeScreenshot("06-raised");
            double magentaRaised = ScreenshotProbe.centralFraction(raised, ScreenshotProbe::isMagenta);
            assertThat(magentaRaised > 0.5, "verhoogde overlay niet zichtbaar: " + magentaRaised);
            ctx.runOnClient(mc -> OverlayManager.getInstance().adjustAllY(-1));

            // 7. Prestatie: 100.000 blokken (316x316) mogen de framerate niet slopen.
            ctx.runOnClient(mc -> OverlayManager.getInstance().clearCategory("all"));
            ctx.waitTicks(40);
            int fpsBaseline = averageFps(ctx, 60);
            ctx.runOnClient(mc -> OverlayManager.getInstance().addOverlay(
                    square("paint_big", "paint", -150, -150, 316, OVERLAY_Y, "magenta_wool", 255, 0, 255)));
            ctx.waitTicks(40);
            int fpsBig = averageFps(ctx, 60);
            Path big = ctx.takeScreenshot("07-big");
            double magentaBig = ScreenshotProbe.centralFraction(big, ScreenshotProbe::isMagenta);
            LOGGER.info("[gametest] FPS zonder overlay = {}, met 99.856 blokken = {}, zichtbaar = {}", fpsBaseline, fpsBig, magentaBig);
            assertThat(magentaBig > 0.5, "grote overlay niet zichtbaar: " + magentaBig);
            assertThat(fpsBig >= 60 || fpsBig * 2 >= fpsBaseline,
                    "framerate stort in met 100k blokken: " + fpsBaseline + " -> " + fpsBig);

            // 8. Frustum-culling: met de rug naar de overlay worden er geen vertices uitgezonden.
            ctx.runOnClient(mc -> {
                OverlayManager.getInstance().clearCategory("all");
                OverlayManager.getInstance().addOverlay(
                        square("paint_small", "paint", 0, 0, 16, OVERLAY_Y, "magenta_wool", 255, 0, 255));
            });
            sp.getServer().runCommand("tp @a 8.5 -58 -30.5 0 0"); // kijkt naar +z, overlay in beeld
            ctx.waitTicks(10);
            long facing = ctx.computeOnClient(mc -> GeoOverlayMod.getRenderer().lastSlabVertices());
            sp.getServer().runCommand("tp @a 8.5 -58 -30.5 180 0"); // kijkt naar -z, overlay achter de rug
            ctx.waitTicks(10);
            long away = ctx.computeOnClient(mc -> GeoOverlayMod.getRenderer().lastSlabVertices());
            LOGGER.info("[gametest] culling: vertices met overlay in beeld = {}, achter de rug = {}", facing, away);
            assertThat(facing > 0, "overlay in beeld maar geen vertices uitgezonden");
            assertThat(away == 0, "overlay achter de camera maar toch " + away + " vertices uitgezonden");

            // 9. Resource-reload (F3+T): de atlas verschuift, de sprite-canary moet de UV's verversen.
            sp.getServer().runCommand("tp @a 8.5 -47 8.5 0 90");
            java.util.concurrent.CompletableFuture<Void> reload = ctx.computeOnClient(mc -> mc.reloadResourcePacks());
            ctx.waitFor(mc -> reload.isDone(), 1200);
            ctx.waitTicks(40);
            Path reloaded = ctx.takeScreenshot("09-after-reload");
            double magentaReloaded = ScreenshotProbe.centralFraction(reloaded, ScreenshotProbe::isMagenta);
            LOGGER.info("[gametest] na resource-reload magenta-fractie = {}", magentaReloaded);
            assertThat(magentaReloaded > 0.5, "overlay kapot na resource-reload: " + magentaReloaded);

            ctx.runOnClient(mc -> OverlayManager.getInstance().clearCategory("all"));
            ctx.waitTicks(5);
        }
    }

    // -- helpers ----------------------------------------------------------

    private static int averageFps(ClientGameTestContext ctx, int ticks) {
        long sum = 0;
        int samples = 0;
        for (int i = 0; i < ticks; i += 10) {
            ctx.waitTicks(10);
            sum += ctx.computeOnClient(mc -> mc.getFps());
            samples++;
        }
        return (int) (sum / Math.max(1, samples));
    }

    static OverlayData square(String id, String category, int x0, int z0, int size, int y, String tag, int r, int g, int b) {
        OverlayData.BlockPos[] blocks = new OverlayData.BlockPos[size * size];
        int i = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                blocks[i++] = new OverlayData.BlockPos(x0 + x, z0 + z);
            }
        }
        return new OverlayData(id, category, blocks, y, r, g, b, 255, id, tag);
    }

    static String addMessage(String id, String category, int x0, int z0, int size, int y, String tag, int r, int g, int b) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"overlay\",\"action\":\"add\",\"id\":\"").append(id)
                .append("\",\"category\":\"").append(category)
                .append("\",\"label\":\"").append(id)
                .append("\",\"tag\":\"").append(tag)
                .append("\",\"y\":").append(y)
                .append(",\"color\":[").append(r).append(',').append(g).append(',').append(b).append(",255],\"blocks\":[");
        boolean first = true;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"x\":").append(x0 + x).append(",\"z\":").append(z0 + z).append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Sends one message through the real bridge on 127.0.0.1:4945, like the GDOK viewer does. */
    static void sendViaBridge(String json) {
        CountDownLatch opened = new CountDownLatch(1);
        WebSocketClient client = new WebSocketClient(URI.create("ws://127.0.0.1:4945")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                opened.countDown();
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
                LOGGER.warn("[gametest] bridge-fout", ex);
            }
        };
        try {
            client.connect();
            assertThat(opened.await(5, TimeUnit.SECONDS), "bridge op 4945 niet bereikbaar");
            client.send(json);
            Thread.sleep(200);
            client.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
