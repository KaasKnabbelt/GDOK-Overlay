package nl.geocraft.overlay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Checks for mod updates against gdok.tectabuilds.nl/downloads/mod-info.json
 * and notifies the player in chat when a newer version is available.
 */
public class UpdateChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final String VERSION_URL = "https://gdok.tectabuilds.nl/downloads/mod-info.json";
    private static final String DOWNLOAD_URL = "https://gdok.tectabuilds.nl/downloads/";

    private static boolean checked = false;

    public static void onPlayerJoin() {
        if (checked) return;
        checked = true;

        Thread thread = new Thread(UpdateChecker::checkForUpdate, "GDOK-UpdateChecker");
        thread.setDaemon(true);
        thread.start();
    }

    private static void checkForUpdate() {
        try {
            String currentVersion = FabricLoader.getInstance()
                    .getModContainer(GeoOverlayMod.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("0.0.0");

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VERSION_URL))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return;

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String latestVersion = json.get("version").getAsString();
            String filename = json.has("filename") ? json.get("filename").getAsString() : "";

            if (isNewer(latestVersion, currentVersion)) {
                String downloadLink = DOWNLOAD_URL + filename;
                notifyPlayer(currentVersion, latestVersion, downloadLink);
            }

        } catch (Exception e) {
            LOGGER.debug("[GeoCraft Overlay] Update check mislukt: {}", e.getMessage());
        }
    }

    private static boolean isNewer(String latest, String current) {
        String[] l = latest.split("\\.");
        String[] c = current.split("\\.");
        int len = Math.max(l.length, c.length);
        for (int i = 0; i < len; i++) {
            int lv = i < l.length ? parseIntSafe(l[i]) : 0;
            int cv = i < c.length ? parseIntSafe(c[i]) : 0;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static void notifyPlayer(String current, String latest, String downloadUrl) {
        MinecraftClient mc = MinecraftClient.getInstance();

        mc.execute(() -> {
            if (mc.player == null) return;

            MutableText prefix = Text.literal("[GDOK] ").formatted(Formatting.GREEN, Formatting.BOLD);

            MutableText message = Text.literal("Er is een nieuwe versie beschikbaar: ")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal("v" + latest).formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(" (jij hebt v" + current + "). ").formatted(Formatting.YELLOW))
                    .append(Text.literal("[Download]")
                            .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                            .styled(style -> style
                                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(downloadUrl)))
                                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(downloadUrl)))));

            mc.player.sendMessage(prefix.append(message), false);
        });
    }
}
