package nl.geocraft.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOMEN.md §9.6: which pages may connect, and which commands the site may run. */
class BridgeSecurityTest {

    @Test
    void onlyGdokAndLocalhostMayConnect() {
        assertTrue(BridgeServer.isAllowedOrigin("https://gdok.nl"));
        assertTrue(BridgeServer.isAllowedOrigin("https://www.gdok.nl"));
        assertTrue(BridgeServer.isAllowedOrigin("http://localhost:8000"));
        assertTrue(BridgeServer.isAllowedOrigin("http://127.0.0.1:5173"));
        assertTrue(BridgeServer.isAllowedOrigin("http://localhost"));
        assertTrue(BridgeServer.isAllowedOrigin(null), "geen webpagina (gametest, lokaal hulpmiddel)");

        assertFalse(BridgeServer.isAllowedOrigin("https://evil.example"));
        assertFalse(BridgeServer.isAllowedOrigin("https://gdok.nl.evil.example"));
        assertFalse(BridgeServer.isAllowedOrigin("http://gdok.nl"), "alleen https");
        assertFalse(BridgeServer.isAllowedOrigin("null"), "sandboxed iframe of file://");
        assertFalse(BridgeServer.isAllowedOrigin("https://localhost.evil.example"));
    }

    @Test
    void onlyTpposMayRun() {
        assertTrue(BridgeServer.isAllowedCommand("tppos 187000.5, -428000.5"));
        assertTrue(BridgeServer.isAllowedCommand("tppos 12, 64, -30"));
        assertFalse(BridgeServer.isAllowedCommand("op Someone"));
        assertFalse(BridgeServer.isAllowedCommand("tppos 1, 2; op me"));
        assertFalse(BridgeServer.isAllowedCommand("tppos 1, 2 && stop"));
        assertFalse(BridgeServer.isAllowedCommand("/sel poly"));
        assertFalse(BridgeServer.isAllowedCommand("geotools paste red_wool -a -r"));
        assertFalse(BridgeServer.isAllowedCommand("tppos"));
    }
}
