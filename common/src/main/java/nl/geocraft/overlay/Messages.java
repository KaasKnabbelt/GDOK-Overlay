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

    // Bomenplannen (markerlagen, BOMEN.md §9)

    /** Actionbar na een middelklik op een spookmarker. */
    public static String markerPicked(String block) {
        return PREFIX + "§fMarker in je hand: §a" + block;
    }

    /** Middelklik op een spookmarker buiten creative. */
    public static String markerPickNeedsCreative() {
        return PREFIX + "§7Een marker oppakken kan alleen in creative.";
    }

    /** Actionbar zolang je naar een spookblok kijkt: welk blok daar hoort (rechtsklik zet het neer). */
    public static String markerAim(String block) {
        return PREFIX + "§f" + block;
    }

    /** Actionbar na een rechtsklik op een spookblok. */
    public static String markerCounter(int placed, int total) {
        return PREFIX + "§f" + nl(placed) + " / " + nl(total) + " geplaatst";
    }

    // Paste-commando's in de chat (Commando in het G-menu, /gdok paste). Alleen voor de speler zelf.

    public static String pasteHeader(String plan) {
        return PREFIX + "§f" + plan + ": klik een regel en druk op Enter.";
    }

    public static String pasteNothingPlaced(String plan) {
        return PREFIX + "§f" + plan + ": nog geen geplaatste markers, dus nog geen commando.";
    }

    public static String pasteForeign(int count) {
        return count == 1
                ? "§cLet op: 1 markerblok in dit gebied hoort niet bij het plan."
                : "§cLet op: " + nl(count) + " markerblokken in dit gebied horen niet bij het plan.";
    }

    public static String pasteUnloaded(int percent) {
        return "§6Let op: " + percent + "% van het gebied is niet geladen en dus niet gecontroleerd.";
    }

    public static String pasteNoPlan() {
        return PREFIX + "§7Er staat geen bomenplan in Minecraft.";
    }

    public static String pastePlanGone() {
        return PREFIX + "§7Dit bomenplan staat niet meer in Minecraft.";
    }

    /** Hover-tekst van een commando-regel. */
    public static String pasteHover() {
        return "Klik: zet dit commando in je chatbalk";
    }

    private static String nl(int n) {
        return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', '.');
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
