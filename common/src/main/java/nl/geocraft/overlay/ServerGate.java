package nl.geocraft.overlay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * Gate that restricts overlays + player-location sharing to the
 * official GeoCraft Nederland server. Detection uses two layers:
 *
 * 1. Server-address check (primary): if the player connected to an address
 *    containing "geocraft", the gate opens immediately.
 * 2. BungeeCord/Velocity sub-server name (optional refinement): if a proxy
 *    responds with "zuid", "midden" or "noord", the sub-server field is set.
 *
 * Either layer being positive is enough for {@link #isAllowed()} to return true.
 */
public class ServerGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final ServerGate INSTANCE = new ServerGate();

    /** Recognised GeoCraft sub-servers (compared lower-case). */
    private static final Set<String> KNOWN_SUBSERVERS = Set.of("zuid", "midden", "noord");

    /** Substring that must appear in the server address (compared lower-case). */
    private static final String ADDRESS_KEYWORD = "geocraft";

    private volatile String currentSubserver = null;
    private volatile boolean geocraftAddress = false;
    /**
     * Development bypass: opens the gate regardless of server (singleplayer/LAN included).
     * Only ever set by the module entrypoints when Fabric reports a development environment;
     * common/ has no Fabric classpath, so the detection itself lives there.
     */
    private volatile boolean devBypass = false;

    public static ServerGate getInstance() {
        return INSTANCE;
    }

    // ── Sub-server (BungeeCord / Velocity) ──────────────────────

    public void setCurrentSubserver(String name) {
        this.currentSubserver = name;
        LOGGER.info("[GeoCraft Overlay] Sub-server ingesteld: '{}'", name);
    }

    public String getCurrentSubserver() {
        return currentSubserver;
    }

    // ── Server address ──────────────────────────────────────────

    /**
     * Call with the multiplayer server address the client connected to
     * (e.g. "play.geocraft.nl"). If the address contains "geocraft",
     * the gate opens immediately.
     */
    public void checkServerAddress(String address) {
        if (address == null) {
            LOGGER.debug("[GeoCraft Overlay] Server adres is null (singleplayer?)");
            geocraftAddress = false;
            return;
        }
        String lower = address.toLowerCase(Locale.ROOT);
        geocraftAddress = lower.contains(ADDRESS_KEYWORD);
        LOGGER.info("[GeoCraft Overlay] Server adres: '{}' → geocraft={}", address, geocraftAddress);
    }

    // ── Development bypass ──────────────────────────────────────

    public void setDevBypass(boolean enabled) {
        this.devBypass = enabled;
        if (enabled) {
            LOGGER.warn("[GeoCraft Overlay] DEV-BYPASS ACTIEF: server-gate staat open voor elke wereld "
                    + "(singleplayer/LAN). Dit hoort alleen in een ontwikkelomgeving te gebeuren.");
        }
    }

    public boolean isDevBypass() {
        return devBypass;
    }

    // ── Gate logic ──────────────────────────────────────────────

    /**
     * Returns true when either:
     * - the server address contains "geocraft", OR
     * - the BungeeCord sub-server is one of the known ones.
     */
    public boolean isAllowed() {
        if (devBypass) return true;
        if (geocraftAddress) return true;
        if (currentSubserver != null
                && KNOWN_SUBSERVERS.contains(currentSubserver.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    /** Resets the per-connection state; the dev bypass is process-wide and survives. */
    public void reset() {
        this.currentSubserver = null;
        this.geocraftAddress = false;
    }
}
