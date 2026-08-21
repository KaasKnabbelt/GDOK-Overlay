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
}
