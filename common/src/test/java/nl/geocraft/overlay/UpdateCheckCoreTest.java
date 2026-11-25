package nl.geocraft.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De update-check van fase 6: de jar voor déze Minecraft-versie kiezen uit files[], en een
 * nette fallback als de nieuwe versie er voor deze Minecraft-versie (nog) niet is.
 */
class UpdateCheckCoreTest {

    /** Zoals generateModInfo hem schrijft, met een versie die hoger is dan elke testjar. */
    private static final String MOD_INFO = """
            {
                "version": "9.9.9",
                "released": "2026-08-21",
                "files": [
                    { "filename": "GDOK-Overlay-9.9.9+mc1.21.11.jar", "minecraft": "1.21.11" },
                    { "filename": "GDOK-Overlay-9.9.9+mc26.2.jar", "minecraft": "26.2" }
                ],
                "loader": "Fabric",
                "java": ">= 21 (1.21.11) / >= 25 (26.2)"
            }
            """;

    @Test
    void picksTheJarForThisMinecraftVersion() {
        UpdateCheckCore.Result r = UpdateCheckCore.evaluate(MOD_INFO, "1.1.0+mc26.2");
        assertNotNull(r);
        assertEquals("9.9.9", r.latestVersion());
        assertEquals("1.1.0", r.currentVersion());
        assertTrue(r.hasFileForThisMinecraft());
        assertEquals("https://gdok.nl/download/GDOK-Overlay-9.9.9+mc26.2.jar", r.downloadUrl());

        UpdateCheckCore.Result r2 = UpdateCheckCore.evaluate(MOD_INFO, "1.1.0+mc1.21.11");
        assertNotNull(r2);
        assertEquals("https://gdok.nl/download/GDOK-Overlay-9.9.9+mc1.21.11.jar", r2.downloadUrl());
    }

    @Test
    void fallsBackWhenThisMinecraftVersionIsNotInTheRelease() {
        // Een 26.1.2-jar (1.0.6) ziet 1.1.0 verschijnen, maar die levert geen 26.1.2-bestand meer.
        UpdateCheckCore.Result r = UpdateCheckCore.evaluate(MOD_INFO, "1.0.6+mc26.1.2");
        assertNotNull(r, "er is wel een nieuwere versie");
        assertFalse(r.hasFileForThisMinecraft());
        assertNull(r.downloadUrl());
    }

    @Test
    void noUpdateWhenUpToDateOrNewer() {
        assertNull(UpdateCheckCore.evaluate(MOD_INFO, "9.9.9+mc26.2"));
        assertNull(UpdateCheckCore.evaluate(MOD_INFO, "10.0.0+mc26.2"));
    }

    @Test
    void oldSingleFileFormatDoesNotCrash() {
        // Het formaat van vóór 1.0.4 (één jar, veld "filename"): wel melden, geen directe link.
        String old = "{ \"version\": \"9.9.9\", \"filename\": \"GDOK-overlay-9.9.9.jar\" }";
        UpdateCheckCore.Result r = UpdateCheckCore.evaluate(old, "1.1.0+mc1.21.11");
        assertNotNull(r);
        assertNull(r.downloadUrl());
    }

    @Test
    void minecraftTargetComesFromBuildMetadata() {
        assertEquals("1.21.11", UpdateCheckCore.minecraftTarget("1.1.0+mc1.21.11"));
        assertEquals("26.2", UpdateCheckCore.minecraftTarget("1.1.0+mc26.2"));
        assertNull(UpdateCheckCore.minecraftTarget("1.1.0"));
        assertNull(UpdateCheckCore.minecraftTarget("1.1.0+build.4"));
        assertNull(UpdateCheckCore.minecraftTarget(null));
        assertEquals("1.1.0", UpdateCheckCore.stripBuildMetadata("1.1.0+mc26.2"));
        assertEquals("0.0.0", UpdateCheckCore.stripBuildMetadata(null));
    }

    @Test
    void versionComparisonIsNumericPerPart() {
        assertTrue(UpdateCheckCore.isNewer("1.1.0", "1.0.6"));
        assertTrue(UpdateCheckCore.isNewer("1.0.10", "1.0.9"));
        assertTrue(UpdateCheckCore.isNewer("1.1", "1.0.6"));
        assertFalse(UpdateCheckCore.isNewer("1.0.6", "1.0.6"));
        assertFalse(UpdateCheckCore.isNewer("1.0.6", "1.1.0"));
        assertFalse(UpdateCheckCore.isNewer("1.0.6+mc26.2", "1.0.6+mc1.21.11"), "build-metadata telt niet mee");
    }

    @Test
    void preReleasesComeBeforeTheirRelease() {
        // De beta mag de publieke 1.1.1 niet als update zien, en moet 1.1.2 wel zien.
        assertFalse(UpdateCheckCore.isNewer("1.1.1", "1.1.2-beta.1+mc26.2"));
        assertTrue(UpdateCheckCore.isNewer("1.1.2", "1.1.2-beta.3+mc26.2"));
        assertTrue(UpdateCheckCore.isNewer("1.1.2-beta.1", "1.1.1"));
        assertTrue(UpdateCheckCore.isNewer("1.1.2-beta.10", "1.1.2-beta.9"), "numeriek, niet als tekst");
        assertTrue(UpdateCheckCore.isNewer("1.1.2-beta.1.1", "1.1.2-beta.1"));
        assertTrue(UpdateCheckCore.isNewer("1.1.2-rc.1", "1.1.2-beta.4"));
        assertTrue(UpdateCheckCore.isNewer("1.1.2-beta", "1.1.2-1"), "tekst komt na een getal");
        assertEquals(0, UpdateCheckCore.compareVersions("1.1.2-beta.1+mc26.2", "1.1.2-beta.1+mc1.21.11"));

        String publiek = "{ \"version\": \"1.1.1\", \"files\": [] }";
        assertNull(UpdateCheckCore.evaluate(publiek, "1.1.2-beta.1+mc26.2"), "een beta krijgt geen melding over 1.1.1");
    }

    // Twee kanalen: mod-info.json (gewone versie) en beta-info.json (open beta).

    private static String info(String version) {
        return "{ \"version\": \"" + version + "\", \"files\": ["
                + "{ \"filename\": \"GDOK-Overlay-" + version + "+mc26.2.jar\", \"minecraft\": \"26.2\" } ] }";
    }

    @Test
    void aStableVersionNeverHearsAboutABeta() {
        // 1.2.0 met een open beta 1.2.1-beta.1 ernaast: geen melding, ook niet als het
        // beta-bestand toch wordt meegegeven.
        assertNull(UpdateCheckCore.evaluate(info("1.2.0"), info("1.2.1-beta.1"), "1.2.0+mc26.2"));
        assertNull(UpdateCheckCore.evaluate(info("1.2.0"), info("1.3.0-beta.1"), "1.2.0+mc26.2"));

        // De gewone versie zelf meldt hij wel, zonder het woord beta.
        UpdateCheckCore.Result r = UpdateCheckCore.evaluate(info("1.2.1"), info("1.2.2-beta.1"), "1.2.0+mc26.2");
        assertNotNull(r);
        assertEquals("1.2.1", r.latestVersion());
        assertFalse(r.beta());
    }

    @Test
    void aBetaHearsAboutANewerBeta() {
        UpdateCheckCore.Result r = UpdateCheckCore.evaluate(info("1.2.0"), info("1.2.1-beta.2"), "1.2.1-beta.1+mc26.2");
        assertNotNull(r);
        assertEquals("1.2.1-beta.2", r.latestVersion());
        assertEquals("1.2.1-beta.1", r.currentVersion());
        assertTrue(r.beta());
        assertEquals("https://gdok.nl/download/GDOK-Overlay-1.2.1-beta.2+mc26.2.jar", r.downloadUrl());

        // Een beta van een volgende versie telt ook.
        UpdateCheckCore.Result next = UpdateCheckCore.evaluate(info("1.2.0"), info("1.2.2-beta.1"), "1.2.1-beta.3+mc26.2");
        assertNotNull(next);
        assertEquals("1.2.2-beta.1", next.latestVersion());

        // Dezelfde beta, of een oudere die is blijven staan: niets.
        assertNull(UpdateCheckCore.evaluate(info("1.2.0"), info("1.2.1-beta.1"), "1.2.1-beta.1+mc26.2"));
        assertNull(UpdateCheckCore.evaluate(info("1.2.0"), info("1.2.1-beta.1"), "1.2.1-beta.2+mc26.2"));
    }

    @Test
    void aBetaGetsTheHighestOfBothChannels() {
        // De gewone 1.2.1 is uit en het beta-bestand staat er nog: de gewone versie wint.
        UpdateCheckCore.Result uit = UpdateCheckCore.evaluate(info("1.2.1"), info("1.2.1-beta.2"), "1.2.1-beta.1+mc26.2");
        assertNotNull(uit);
        assertEquals("1.2.1", uit.latestVersion());
        assertFalse(uit.beta());

        // De gewone versie is nieuwer dan de eigen beta, maar er is al een beta van de volgende.
        UpdateCheckCore.Result volgende = UpdateCheckCore.evaluate(info("1.2.1"), info("1.2.2-beta.1"), "1.2.1-beta.1+mc26.2");
        assertNotNull(volgende);
        assertEquals("1.2.2-beta.1", volgende.latestVersion());
        assertTrue(volgende.beta());
    }

    @Test
    void aMissingOrBrokenChannelDoesNotBlockTheOther() {
        // Geen beta op de site (404) of een kapot bestand: de gewone versie blijft gemeld.
        assertEquals("1.2.1", UpdateCheckCore.evaluate(info("1.2.1"), null, "1.2.1-beta.1+mc26.2").latestVersion());
        assertEquals("1.2.1", UpdateCheckCore.evaluate(info("1.2.1"), "<html>404</html>", "1.2.1-beta.1+mc26.2").latestVersion());
        // En andersom: mod-info.json niet te halen, de beta wel.
        assertEquals("1.2.1-beta.2", UpdateCheckCore.evaluate(null, info("1.2.1-beta.2"), "1.2.1-beta.1+mc26.2").latestVersion());
        assertEquals("1.2.1-beta.2", UpdateCheckCore.evaluate("kapot", info("1.2.1-beta.2"), "1.2.1-beta.1+mc26.2").latestVersion());
        assertNull(UpdateCheckCore.evaluate(null, null, "1.2.1-beta.1+mc26.2"));
        assertNull(UpdateCheckCore.evaluate(null, null, "1.2.0+mc26.2"));
    }

    @Test
    void preReleaseIsReadFromTheOwnVersion() {
        assertTrue(UpdateCheckCore.isPreRelease("1.2.1-beta.1+mc26.2"));
        assertTrue(UpdateCheckCore.isPreRelease("1.2.1-rc.1"));
        assertFalse(UpdateCheckCore.isPreRelease("1.2.1+mc26.2"));
        assertFalse(UpdateCheckCore.isPreRelease("1.2.1+mc1.21.11"), "de streepjes van build-metadata tellen niet");
        assertFalse(UpdateCheckCore.isPreRelease(null));
    }
}
