package nl.geocraft.overlay;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;

/**
 * Meldt in de chat als er een nieuwere mod-versie op gdok.nl staat.
 *
 * <p>Dunne shim: de check zelf (HTTP, JSON, versievergelijking, de jar voor deze
 * Minecraft-versie kiezen) zit in {@link UpdateCheckCore} in common/. Hier alleen de thread
 * en het chatbericht met click-event, want {@code ClickEvent}/{@code Component} zijn per mapping anders.</p>
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
        Minecraft mc = Minecraft.getInstance();

        mc.execute(() -> {
            if (mc.player == null) return;

            MutableComponent message = Component.literal(Messages.PREFIX)
                    .append(Component.literal(Messages.updateAvailableIntro()))
                    .append(Component.literal(Messages.updateAvailableLatest(result.latestVersion())))
                    .append(Component.literal(Messages.updateAvailableCurrent(result.currentVersion())));

            if (result.hasFileForThisMinecraft()) {
                message.append(link(Messages.updateDownloadLabel(), result.downloadUrl()));
            } else {
                message.append(Component.literal(Messages.updateNotForThisMinecraft(minecraftVersion)))
                        .append(link(Messages.updateDownloadsPageLabel(), UpdateCheckCore.DOWNLOADS_PAGE_URL));
            }

            mc.player.sendSystemMessage(message);
        });
    }

    private static MutableComponent link(String label, String url) {
        return Component.literal(label).withStyle(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
    }
}
