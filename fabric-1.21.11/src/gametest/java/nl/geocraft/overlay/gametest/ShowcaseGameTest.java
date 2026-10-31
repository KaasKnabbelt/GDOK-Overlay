package nl.geocraft.overlay.gametest;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.world.WorldCreator;
import nl.geocraft.overlay.GdokChat;
import nl.geocraft.overlay.OverlayConfig;
import nl.geocraft.overlay.markers.MarkerManager;
import nl.geocraft.overlay.markers.MarkerPlanParser;
import nl.geocraft.overlay.render.RenderMode;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Screenshots of the in-game help for a plan (BOMEN.md §9.8): the frames through blocks, the
 * beacons once 10 or fewer are left, the bar at the top, the [GDOK] button behind /waarbenik and
 * /gdok. Jens chose from the first version of these on 31 October 2026 (then with variants);
 * keep it to look at a change with your own eyes. Measures nothing (OverlayRenderGameTest does).
 * Only runs with {@code gradlew :fabric-1.21.11:runClientGameTest -Pshowcase} (then the normal
 * gametest skips itself); screenshots in build/run/clientGameTest/screenshots.
 *
 * <p>Scene on the flat preset (grass at y -61): 20 markers in a 5 x 4 grid, a stone wall at
 * z 18 hides the back two rows, a hedge and two trees hide a few in front, two are placed.</p>
 */
public class ShowcaseGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay-showcase");
    private static final int Y = -60;
    private static final String[] BLOCKS = {"lime_wool", "orange_terracotta", "light_blue_concrete", "magenta_wool", "yellow_concrete"};
    private static final int[] XS = {2, 7, 12, 17, 22}, ZS = {4, 10, 24, 30};
    /** Camera close by: the front rows at 6 to 12 blocks, the wall at 20, the back rows behind it. */
    private static final String CLOSE = "12.5 -59 -2.5 0 9";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("geocraft.showcase")) return;
        ctx.runOnClient(mc -> {
            OverlayConfig.getInstance().setOpacityPercent(50);
            OverlayConfig.getInstance().setRenderMode(RenderMode.FULLBLOCK);
            mc.options.getViewDistance().setValue(8);
            mc.options.getGuiScale().setValue(3);
            mc.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.MINIMIZED);
        });

        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .adjustSettings(c -> c.setGameMode(WorldCreator.Mode.CREATIVE))
                .create()) {
            var server = sp.getServer();
            server.runCommand("time set 1000");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a " + CLOSE);
            ctx.waitFor(mc -> mc.world != null && mc.world.isChunkLoaded(0, 0) && mc.world.isChunkLoaded(1, 1)
                    && mc.world.isChunkLoaded(0, 2) && mc.world.isChunkLoaded(1, 2), 1200);

            // The scene: a wall in the middle, a hedge and two trees in front.
            server.runCommand("fill -4 " + Y + " 18 28 " + (Y + 4) + " 18 minecraft:stone_bricks");
            server.runCommand("fill 5 " + Y + " 7 9 " + (Y + 1) + " 7 minecraft:oak_leaves[persistent=true]");
            tree(sp, 16, 8);
            tree(sp, 21, 12);
            ctx.waitTicks(40);

            StringBuilder plan = new StringBuilder("{\"type\":\"markers\",\"action\":\"set\",\"id\":\"plan-showcase\","
                    + "\"label\":\"St. Anna\",\"polygon\":[[-2,0],[26,0],[26,34],[-2,34]],\"blocks\":[");
            for (int i = 0; i < BLOCKS.length; i++) plan.append(i == 0 ? "" : ",").append('"').append(BLOCKS[i]).append('"');
            plan.append("],\"trunks\":[],\"paste\":{\"flags\":\"-a -r -s gdok\"},\"markers\":[");
            int n = 0;
            for (int zi = 0; zi < ZS.length; zi++) {
                for (int xi = 0; xi < XS.length; xi++) {
                    if (n > 0) plan.append(',');
                    plan.append('[').append(XS[xi]).append(',').append(ZS[zi]).append(',').append(Y).append(',').append((xi + zi) % BLOCKS.length).append(']');
                    n++;
                }
            }
            plan.append("]}");
            String json = plan.toString();
            ctx.runOnClient(mc -> MarkerManager.getInstance().submitSet(MarkerPlanParser.parse(JsonParser.parseString(json).getAsJsonObject())));
            ctx.waitFor(mc -> {
                var l = MarkerManager.getInstance().get("plan-showcase");
                return l != null && l.counts().toPlace() == 20 && l.counts().unknown() == 0;
            }, 400);
            // Two placed in the front row.
            server.runCommand("setblock 2 " + Y + " 4 minecraft:" + BLOCKS[0]);
            server.runCommand("setblock 12 " + Y + " 4 minecraft:" + BLOCKS[2]);
            ctx.waitFor(mc -> MarkerManager.getInstance().get("plan-showcase").counts().placed() == 2, 400);
            ctx.waitTicks(40);

            // Through blocks, without HUD.
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
            ctx.waitTicks(10);
            shot(ctx, "x0-uit", false, false);
            shot(ctx, "x1-kaders", true, false);
            // Ten or fewer left: beacons too. Place the two front rows: the ten behind the wall are left.
            for (int zi = 0; zi < 2; zi++) {
                for (int xi = 0; xi < XS.length; xi++) {
                    server.runCommand("setblock " + XS[xi] + " " + Y + " " + ZS[zi] + " minecraft:" + BLOCKS[(xi + zi) % BLOCKS.length]);
                }
            }
            ctx.waitFor(mc -> MarkerManager.getInstance().get("plan-showcase").counts().toPlace() <= 10, 400);
            ctx.waitTicks(20);
            shot(ctx, "x2-kaders-en-bakens", true, false);
            server.runCommand("tp @a 12.5 -50 -40.5 0 14");
            ctx.waitTicks(40);
            shot(ctx, "x3-bakens-ver", true, false);

            // The bar at the top, close to the plan, with an empty chat.
            server.runCommand("tp @a " + CLOSE);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.inGameHud.getChatHud().clear(false));
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
            ctx.waitTicks(10);
            shot(ctx, "h1-balk", true, true);

            // The [GDOK] button behind /waarbenik: the two lines as the server sends them.
            ctx.runOnClient(mc -> mc.inGameHud.getChatHud().clear(false));
            server.runCommand("tellraw @a {\"text\":\"[GeoNavigate] Je bent hier: Sint Annastraat 120, Nijmegen\",\"color\":\"gold\"}");
            server.runCommand("tellraw @a {\"text\":\"Klik hier voor een Google Maps link naar de coords: 51.82788, 5.85391\",\"color\":\"aqua\"}");
            // /gdok, without opening a real browser.
            AtomicReference<String> opened = new AtomicReference<>();
            GdokChat.opener = opened::set;
            ctx.runOnClient(mc -> mc.player.networkHandler.sendChatCommand("gdok"));
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> mc.setScreen(new net.minecraft.client.gui.screen.ChatScreen("", false)));
            ctx.waitTicks(10);
            ctx.takeScreenshot("c1-chat");
            LOGGER.info("[showcase] /gdok opende {}", opened.get());
            ctx.runOnClient(mc -> mc.setScreen(null));
        }
    }

    private static void shot(ClientGameTestContext ctx, String name, boolean seeThrough, boolean hud) {
        ctx.runOnClient(mc -> {
            OverlayConfig.getInstance().setMarkerSeeThrough(seeThrough);
            OverlayConfig.getInstance().setMarkerHud(hud);
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot(name);
    }

    private static void tree(TestSingleplayerContext sp, int x, int z) {
        var s = sp.getServer();
        s.runCommand("fill " + (x - 2) + " " + (Y + 3) + " " + (z - 2) + " " + (x + 2) + " " + (Y + 5) + " " + (z + 2) + " minecraft:oak_leaves[persistent=true]");
        s.runCommand("fill " + x + " " + Y + " " + z + " " + x + " " + (Y + 4) + " " + z + " minecraft:oak_log");
    }
}
