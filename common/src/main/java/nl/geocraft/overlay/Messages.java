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
        return overlayYDelta(delta, 0, 1);
    }

    /**
     * Page Up/Down. Vastgezette lagen schuiven niet mee; dat staat erbij, want een laag raakt ook
     * vast door de hoogteknop van zijn rij (gemeld op 10 november 2026: "Page Up doet niets").
     *
     * @param pinned vastgezette lagen (blijven staan)
     * @param moved  lagen die wel meeschoven
     */
    public static String overlayYDelta(int delta, int pinned, int moved) {
        String sign = delta > 0 ? "+" : "";
        if (pinned > 0 && moved == 0) {
            return PREFIX + (pinned == 1 ? "§7De laag staat vast en blijft staan (G-menu: Vast)." : "§7Alle " + pinned + " lagen staan vast en blijven staan (G-menu: Vast).");
        }
        String text = PREFIX + "§fOverlay Y " + sign + delta;
        if (pinned > 0) text += " §7· " + (pinned == 1 ? "1 vastgezette laag blijft staan" : pinned + " vastgezette lagen blijven staan");
        return text;
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

    // Deel in het G-menu (PlanShare): een link naar de paste-commando's in je chatbalk, voor CVDK.

    /** Wat er in de chatbalk komt; de speler drukt zelf op Enter (of past het eerst aan). */
    public static String shareChatText(String plan, String url) {
        return "Bomenplan " + plan + ", paste-commando's: " + url;
    }

    public static String shareNeedsViewer() {
        return PREFIX + "§7Open de GDOK-viewer in je browser (gdok.nl/viewer): die maakt de link.";
    }

    public static String shareTimeout() {
        return PREFIX + "§7De viewer gaf geen link. Probeer het nog eens.";
    }

    public static String shareFailed(String reason) {
        return PREFIX + "§cLink maken mislukt" + (reason == null || reason.isEmpty() ? "." : ": " + reason);
    }

    /** De link staat klaar, maar de chat kon niet open (er stond een ander scherm open). */
    public static String shareReady() {
        return PREFIX + "§fDe link staat klaar: klik om hem in je chatbalk te zetten.";
    }

    /** Hover-tekst van een commando-regel. */
    public static String pasteHover() {
        return "Klik: zet dit commando in je chatbalk";
    }

    // Naar de GDOK-viewer (GdokLink): /gdok en de knop achter de Google Maps-regel van /waarbenik.

    /** De knop achter de Google Maps-regel van /waarbenik. */
    public static String gdokButton() {
        return "§a§l[GDOK]";
    }

    public static String gdokButtonHover() {
        return "Open deze plek in de GDOK-viewer";
    }

    /** /gdok: de viewer gaat open; de link erachter voor als de browser niet vanzelf opent. */
    public static String gdokOpening(int x, int z) {
        return PREFIX + "§fDe viewer gaat open op " + x + ", " + z + ". ";
    }

    /** /gdok met een open viewer: die vliegt naar je plek (geen nieuw tabblad). */
    public static String gdokFlown(int x, int z) {
        return PREFIX + "§fDe viewer in je browser staat nu op " + x + ", " + z + ".";
    }

    public static String gdokLinkLabel() {
        return "§b§n[Link]";
    }

    public static String gdokOnlyOnGeoCraft() {
        return PREFIX + "§7Dit werkt alleen op GeoCraft.";
    }

    private static String nl(int n) {
        return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', '.');
    }

    // Update-bericht (zie UpdateCheckCore). Tekst-delen zonder click-event; het klikbare label
    // wordt per loader gebouwd, want ClickEvent is per mapping anders.

    /** @param beta de nieuwe versie is een beta (alleen wie zelf een beta draait krijgt die te zien) */
    public static String updateAvailableIntro(boolean beta) {
        return beta ? "§eEr is een nieuwe beta beschikbaar: " : "§eEr is een nieuwe versie beschikbaar: ";
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
