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
 *
 * <p><b>Twee kanalen (sinds 1.2.1-beta.1).</b> De gewone versie staat in mod-info.json, een open
 * beta in {@code beta-info.json} ernaast (zelfde vorm; {@code gradlew publishBeta}). Wie een
 * gewone versie draait leest alleen mod-info.json en hoort dus nooit iets over een beta. Wie zelf
 * een beta draait (een {@code -} in de eigen versie, zie {@link #isPreRelease}) leest beide en
 * krijgt de hoogste: een nieuwere beta, of de gewone versie zodra die uit is
 * ({@code 1.2.1-beta.1 < 1.2.1-beta.2 < 1.2.1}). Een beta installeren is dus de aanmelding;
 * er is geen schakelaar. De jars t/m 1.2.0 kennen beta-info.json niet: daar hoeft niets voor.</p>
 */
public final class UpdateCheckCore {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");

    public static final String SITE = "https://gdok.nl";
    public static final String VERSION_URL = SITE + "/downloads/mod-info.json";
    /** De open beta, als die er is; bestaat het bestand niet, dan geeft de site een 404. */
    public static final String BETA_URL = SITE + "/downloads/beta-info.json";
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
     * @param beta             de nieuwste versie is zelf een beta (het bericht zegt dat erbij)
     */
    public record Result(String latestVersion, String currentVersion, String downloadUrl, boolean beta) {
        public boolean hasFileForThisMinecraft() {
            return downloadUrl != null;
        }
    }

    /**
     * Haalt mod-info.json op (en als de eigen versie een beta is ook beta-info.json) en
     * vergelijkt. Blokkeert; aanroepen vanaf een eigen thread.
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
            String modInfo = fetch(client, VERSION_URL, modVersion);
            // Een gewone versie vraagt het beta-bestand niet eens op.
            String betaInfo = isPreRelease(modVersion) ? fetch(client, BETA_URL, modVersion) : null;
            return evaluate(modInfo, betaInfo, modVersion);
        } catch (Exception e) {
            LOGGER.debug("[GeoCraft Overlay] Update check mislukt: {}", e.toString());
            return null;
        }
    }

    /** De tekst van een van de twee bestanden, of {@code null} als het er niet is of niet lukt. */
    private static String fetch(HttpClient client, String url, String modVersion) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "GDOK-Overlay/" + stripBuildMetadata(modVersion))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.debug("[GeoCraft Overlay] Update check {}: HTTP {}", url, response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.debug("[GeoCraft Overlay] Update check {} mislukt: {}", url, e.toString());
            return null;
        }
    }

    /**
     * Het pure deel: beoordeelt een mod-info.json-tekst tegen de eigen versie.
     *
     * @return het resultaat als de site een nieuwere versie heeft, anders {@code null}
     */
    public static Result evaluate(String modInfoJson, String modVersion) {
        return candidate(modInfoJson, modVersion);
    }

    /**
     * Beide kanalen: de gewone versie en, alleen als de eigen versie een beta is, de open beta.
     * De hoogste van de twee wint. Een kapot of ontbrekend bestand telt als "niets nieuws" en
     * houdt het andere kanaal niet tegen.
     *
     * @param modInfoJson  de tekst van mod-info.json, of {@code null}
     * @param betaInfoJson de tekst van beta-info.json, of {@code null} (geen beta, of niet opgehaald)
     */
    public static Result evaluate(String modInfoJson, String betaInfoJson, String modVersion) {
        Result stable = safeCandidate(modInfoJson, modVersion);
        if (!isPreRelease(modVersion)) return stable;

        Result beta = safeCandidate(betaInfoJson, modVersion);
        if (stable == null) return beta;
        if (beta == null) return stable;
        return isNewer(beta.latestVersion(), stable.latestVersion()) ? beta : stable;
    }

    private static Result safeCandidate(String infoJson, String modVersion) {
        if (infoJson == null) return null;
        try {
            return candidate(infoJson, modVersion);
        } catch (RuntimeException e) {
            LOGGER.debug("[GeoCraft Overlay] Update check: onleesbaar antwoord ({})", e.toString());
            return null;
        }
    }

    /** Eén bestand (mod-info of beta-info, zelfde vorm) tegen de eigen versie. */
    private static Result candidate(String infoJson, String modVersion) {
        JsonObject json = JsonParser.parseString(infoJson).getAsJsonObject();
        if (!json.has("version")) return null;

        String latest = stripBuildMetadata(json.get("version").getAsString());
        String current = stripBuildMetadata(modVersion);
        if (!isNewer(latest, current)) return null;

        String filename = findFileFor(json, minecraftTarget(modVersion));
        return new Result(latest, current, filename == null ? null : DOWNLOAD_FILE_URL + filename,
                isPreRelease(latest));
    }

    /** Een pre-release heeft een {@code -} in de versie: {@code 1.2.1-beta.1}, ook met {@code +mc...}. */
    public static boolean isPreRelease(String version) {
        return stripBuildMetadata(version).indexOf('-') >= 0;
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

    /** Is {@code latest} nieuwer dan {@code current}? Semver-volgorde, zie {@link #compareVersions}. */
    public static boolean isNewer(String latest, String current) {
        return compareVersions(latest, current) > 0;
    }

    /**
     * Semver-volgorde zonder build-metadata: eerst de kern numeriek per punt-gescheiden onderdeel
     * (ontbrekende onderdelen tellen als 0), dan de pre-release. Een versie met pre-release komt
     * vóór dezelfde kern zonder ({@code 1.1.1 < 1.1.2-beta.3 < 1.1.2}); twee pre-releases gaan per
     * punt-gescheiden onderdeel, numeriek waar beide getallen zijn, anders als tekst.
     *
     * <p>Zonder die pre-release-regel zag een beta zichzelf als {@code 1.1.0} (de
     * {@code 2-beta} werd een 0) en bood hij een "update" naar de publieke 1.1.1 aan.</p>
     */
    public static int compareVersions(String a, String b) {
        String va = stripBuildMetadata(a).trim();
        String vb = stripBuildMetadata(b).trim();
        int da = va.indexOf('-'), db = vb.indexOf('-');
        String coreA = da < 0 ? va : va.substring(0, da), preA = da < 0 ? null : va.substring(da + 1);
        String coreB = db < 0 ? vb : vb.substring(0, db), preB = db < 0 ? null : vb.substring(db + 1);

        String[] l = coreA.split("\\.");
        String[] c = coreB.split("\\.");
        for (int i = 0, len = Math.max(l.length, c.length); i < len; i++) {
            int cmp = Integer.compare(i < l.length ? parseIntSafe(l[i]) : 0, i < c.length ? parseIntSafe(c[i]) : 0);
            if (cmp != 0) return cmp;
        }
        if (preA == null || preB == null) {
            return preA == null ? (preB == null ? 0 : 1) : -1;
        }
        String[] pa = preA.split("\\.");
        String[] pb = preB.split("\\.");
        for (int i = 0, len = Math.min(pa.length, pb.length); i < len; i++) {
            boolean na = pa[i].matches("\\d+"), nb = pb[i].matches("\\d+");
            int cmp;
            if (na && nb) cmp = Integer.compare(parseIntSafe(pa[i]), parseIntSafe(pb[i]));
            else if (na != nb) cmp = na ? -1 : 1; // numeriek komt vóór tekst
            else cmp = pa[i].compareTo(pb[i]);
            if (cmp != 0) return Integer.signum(cmp);
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
