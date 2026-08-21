package nl.geocraft.overlay;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.net.URI;

/**
 * Meldt in de chat als er een nieuwere mod-versie op gdok.nl staat.
 *
 * <p>Dunne shim: de check zelf (HTTP, JSON, versievergelijking, de jar voor deze
 * Minecraft-versie kiezen) zit in {@link UpdateCheckCore} in common/. Hier alleen de thread
 * en het chatbericht met click-event, want {@code ClickEvent}/{@code Text} zijn per mapping anders.</p>
 */
public class UpdateChecker {

    private static boolean checked = false;

    public static void onPlayerJoin() {
        if (checked) return;
        checked = true;

        Thread thread = new Thread(UpdateChecker::checkForUpdate, "GDOK-UpdateChecker");
        thread.setDaemon(true);
        thread.start();
    }

    private static void checkForUpdate() {
        String modVersion = FabricLoader.getInstance()
                .getModContainer(GeoOverlayMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");

        UpdateCheckCore.Result result = UpdateCheckCore.check(modVersion);
        if (result != null) notifyPlayer(result, UpdateCheckCore.minecraftTarget(modVersion));
    }

    private static void notifyPlayer(UpdateCheckCore.Result result, String minecraftVersion) {
        MinecraftClient mc = MinecraftClient.getInstance();

        mc.execute(() -> {
            if (mc.player == null) return;

            MutableText message = Text.literal(Messages.PREFIX)
                    .append(Text.literal(Messages.updateAvailableIntro()))
                    .append(Text.literal(Messages.updateAvailableLatest(result.latestVersion())))
                    .append(Text.literal(Messages.updateAvailableCurrent(result.currentVersion())));

            if (result.hasFileForThisMinecraft()) {
                message.append(link(Messages.updateDownloadLabel(), result.downloadUrl()));
            } else {
                message.append(Text.literal(Messages.updateNotForThisMinecraft(minecraftVersion)))
                        .append(link(Messages.updateDownloadsPageLabel(), UpdateCheckCore.DOWNLOADS_PAGE_URL));
            }

            mc.player.sendMessage(message, false);
        });
    }

    private static MutableText link(String label, String url) {
        return Text.literal(label).styled(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(url))));
    }
}
