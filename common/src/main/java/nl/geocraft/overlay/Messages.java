package nl.geocraft.overlay;

/**
 * Centrale plek voor alle in-game tekst die naar de speler gaat (chat + actionbar).
 *
 * Alle strings gebruiken legacy §-codes (sectionsign-formatting) zodat ze in beide
 * Fabric-loaders werken — zowel Component.literal (Mojang mappings) als Text.literal
 * (Yarn mappings) interpreteren §-codes hetzelfde.
 *
 * UI-labels van schermen (zoals OverlayManagerScreen) horen hier NIET — alleen
 * berichten die we naar de speler sturen.
 */
public final class Messages {

    private Messages() {}

    /** §a§l = green + bold, §f = reset to white. Begin elke message met PREFIX. */
    public static final String PREFIX = "§a§l[GDOK] §r";

    // Actionbar-berichten (boven de hotbar)

    public static String overlayYDelta(int delta) {
        String sign = delta > 0 ? "+" : "";
        return PREFIX + "§fOverlay Y " + sign + delta;
    }

    public static String terrainLoading() {
        return PREFIX + "§7Hoogtegegevens laden…";
    }

    public static String blockLimitReached(int total) {
        return PREFIX + "§cMaximum aantal blokken bereikt (" + total + "), minder dit aantal om overlays weer te geven.";
    }

    // Chat-berichten

    /** Eenmalige hint na de eerste toegestane join (zie OverlayConfig.gKeyHintShown). */
    public static String gKeyHint() {
        return PREFIX + "§fDruk op §a§lG§r§f om je overlays te beheren (zichtbaarheid, hoogte, vastzetten).";
    }

    // Update-bericht (zie UpdateCheckCore). Tekst-delen zonder click-event; het klikbare label
    // wordt per loader gebouwd, want ClickEvent is per mapping anders.

    public static String updateAvailableIntro() {
        return "§eEr is een nieuwe versie beschikbaar: ";
    }

    public static String updateAvailableLatest(String latest) {
        return "§a§lv" + latest;
    }

    public static String updateAvailableCurrent(String current) {
        return "§e (jij hebt v" + current + "). ";
    }

    /** Label van de directe jar-download voor deze Minecraft-versie. */
    public static String updateDownloadLabel() {
        return "§b§n[Download]";
    }

    /** Als de nieuwe versie (nog) geen jar voor deze Minecraft-versie heeft. */
    public static String updateNotForThisMinecraft(String minecraftVersion) {
        String mc = minecraftVersion == null ? "jouw Minecraft-versie" : "Minecraft " + minecraftVersion;
        return "§7Nog niet beschikbaar voor " + mc + "; kijk op de downloadpagina welke versies er zijn. ";
    }

    /** Label van de link naar de downloadpagina (fallback). */
    public static String updateDownloadsPageLabel() {
        return "§b§n[Downloadpagina]";
    }
}
