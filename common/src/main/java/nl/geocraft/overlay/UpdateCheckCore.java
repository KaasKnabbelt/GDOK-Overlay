package nl.geocraft.overlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * De update-check tegen {@code gdok.nl/downloads/mod-info.json}, zonder Minecraft-klassen.
 *
 * <p>De modules ({@code UpdateChecker} per Minecraft-versie) doen alleen nog de thread, de
 * chattekst en het click-event; alles wat fout kan gaan (HTTP, JSON, versievergelijking,
 * de juiste jar voor déze Minecraft-versie kiezen) zit hier en is in {@code common/} getest.</p>
 *
 * <p>Welke Minecraft-versie deze jar bedient staat in de eigen modversie: de build zet er
 * {@code +mc<versie>} achter ({@code 1.1.0+mc1.21.11}), en dat is betrouwbaarder dan de
 * draaiende Minecraft-versie, want de 1.21.11-jar mag ook op een 1.21.x-patch draaien en moet
 * dan nog steeds naar de 1.21.11-download wijzen.</p>
 *
 * <p>De oude checker (t/m 1.0.6) las een veld {@code filename} dat niet meer bestaat sinds
 * mod-info.json meerdere jars per versie levert, en volgde geen redirects, waardoor hij na de
 * verhuizing naar gdok.nl stilviel. Daarom hier: {@code files[]} lezen én redirects volgen.</p>
 */
public final class UpdateCheckCore {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");

    public static final String SITE = "https://gdok.nl";
    public static final String VERSION_URL = SITE + "/downloads/mod-info.json";
    /** Downloadpagina, de fallback-link als er geen jar voor deze Minecraft-versie is. */
    public static final String DOWNLOADS_PAGE_URL = SITE + "/downloads";
    /** Directe download via de route die meetelt in de downloadteller van de site. */
    private static final String DOWNLOAD_FILE_URL = SITE + "/download/";

    private UpdateCheckCore() {}

    /**
     * Uitkomst van een check waarbij een nieuwere versie bestaat.
     *
     * @param latestVersion    de nieuwste versie op de site (zonder build-metadata)
     * @param currentVersion   de eigen versie (zonder build-metadata)
     * @param downloadUrl      directe jar-download voor deze Minecraft-versie, of {@code null}
     *                         als de nieuwe versie (nog) geen jar voor deze Minecraft-versie heeft
     */
    public record Result(String latestVersion, String currentVersion, String downloadUrl) {
        public boolean hasFileForThisMinecraft() {
            return downloadUrl != null;
        }
    }

    /**
     * Haalt mod-info.json op en vergelijkt. Blokkeert; aanroepen vanaf een eigen thread.
     *
     * @param modVersion de eigen modversie zoals Fabric die geeft, inclusief {@code +mc...}
     * @return het resultaat als er een nieuwere versie is, anders {@code null} (ook bij elke fout:
     *         een update-check mag het spel nooit storen)
     */
    public static Result check(String modVersion) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VERSION_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "GDOK-Overlay/" + stripBuildMetadata(modVersion))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.debug("[GeoCraft Overlay] Update check: HTTP {}", response.statusCode());
                return null;
            }
            return evaluate(response.body(), modVersion);
        } catch (Exception e) {
            LOGGER.debug("[GeoCraft Overlay] Update check mislukt: {}", e.toString());
            return null;
        }
    }

    /**
     * Het pure deel: beoordeelt een mod-info.json-tekst tegen de eigen versie.
     *
     * @return het resultaat als de site een nieuwere versie heeft, anders {@code null}
     */
    public static Result evaluate(String modInfoJson, String modVersion) {
        JsonObject json = JsonParser.parseString(modInfoJson).getAsJsonObject();
        if (!json.has("version")) return null;

        String latest = stripBuildMetadata(json.get("version").getAsString());
        String current = stripBuildMetadata(modVersion);
        if (!isNewer(latest, current)) return null;

        String filename = findFileFor(json, minecraftTarget(modVersion));
        return new Result(latest, current, filename == null ? null : DOWNLOAD_FILE_URL + filename);
    }

    /** {@code "1.1.0+mc26.2"} → {@code "26.2"}; geen {@code +mc}-deel → {@code null}. */
    public static String minecraftTarget(String modVersion) {
        if (modVersion == null) return null;
        int plus = modVersion.indexOf('+');
        if (plus < 0) return null;
        String meta = modVersion.substring(plus + 1);
        if (!meta.startsWith("mc") || meta.length() == 2) return null;
        return meta.substring(2);
    }

    /** {@code "1.1.0+mc26.2"} → {@code "1.1.0"}. */
    public static String stripBuildMetadata(String version) {
        if (version == null) return "0.0.0";
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    /** Kiest uit {@code files[]} de jar waarvan {@code minecraft} exact gelijk is aan het doel. */
    private static String findFileFor(JsonObject json, String minecraftTarget) {
        if (minecraftTarget == null || !json.has("files") || !json.get("files").isJsonArray()) return null;
        JsonArray files = json.getAsJsonArray("files");
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            if (!f.has("minecraft") || !f.has("filename")) continue;
            if (minecraftTarget.equals(f.get("minecraft").getAsString())) {
                String name = f.get("filename").getAsString();
                return name.isBlank() ? null : name;
            }
        }
        return null;
    }

    /** Numerieke vergelijking per punt-gescheiden onderdeel; ontbrekende onderdelen tellen als 0. */
    public static boolean isNewer(String latest, String current) {
        String[] l = stripBuildMetadata(latest).split("\\.");
        String[] c = stripBuildMetadata(current).split("\\.");
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
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
