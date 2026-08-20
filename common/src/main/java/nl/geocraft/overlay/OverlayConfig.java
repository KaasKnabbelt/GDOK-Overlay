package nl.geocraft.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nl.geocraft.overlay.render.RenderMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration, saved as {@code config/geocraft-overlay.json}.
 *
 * <p>Lives in common/ so both Minecraft modules share one implementation; the module
 * entrypoints inject the config directory via {@link #init(Path)} before calling
 * {@link #load()} (common/ has no Fabric classpath to ask for it).</p>
 */
public class OverlayConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final OverlayConfig INSTANCE = new OverlayConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "geocraft-overlay.json";

    private static volatile Path configDir;

    private int opacityPercent = 50;
    private boolean shareLocation = false;
    private boolean sameLevel = true;
    private volatile RenderMode renderMode = RenderMode.FULLBLOCK;
    /** The one-time "druk op G" chat hint has been shown (persisted, so it really is one-time). */
    private volatile boolean gKeyHintShown = false;

    public static OverlayConfig getInstance() {
        return INSTANCE;
    }

    /** Must be called once by the module entrypoint before {@link #load()}/{@link #save()}. */
    public static void init(Path directory) {
        configDir = directory;
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    public void setOpacityPercent(int percent) {
        this.opacityPercent = Math.max(0, Math.min(100, percent));
    }

    public float getOpacityMultiplier() {
        return opacityPercent / 100f;
    }

    public boolean isShareLocation() {
        return shareLocation;
    }

    public void setShareLocation(boolean value) {
        this.shareLocation = value;
    }

    public boolean isSameLevel() {
        return sameLevel;
    }

    public void setSameLevel(boolean value) {
        this.sameLevel = value;
        OverlayManager.getInstance().setSameLevel(value);
    }

    /** How overlay blocks are drawn: full textured blocks (default) or a thin tinted carpet. */
    public RenderMode getRenderMode() {
        return renderMode;
    }

    public void setRenderMode(RenderMode mode) {
        this.renderMode = mode == null ? RenderMode.FULLBLOCK : mode;
    }

    public boolean isGKeyHintShown() {
        return gKeyHintShown;
    }

    public void setGKeyHintShown(boolean value) {
        this.gKeyHintShown = value;
    }

    private static Path configPath() {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("OverlayConfig.init(configDir) is nog niet aangeroepen");
        }
        return dir.resolve(FILE_NAME);
    }

    public void load() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            if (data != null) {
                opacityPercent = Math.max(0, Math.min(100, data.opacityPercent));
                shareLocation = data.shareLocation;
                sameLevel = data.sameLevel;
                renderMode = RenderMode.fromConfig(data.renderMode);
                gKeyHintShown = data.gKeyHintShown;
                OverlayManager.getInstance().setSameLevel(sameLevel);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[GeoCraft Overlay] Kon config niet laden: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            ConfigData data = new ConfigData();
            data.opacityPercent = opacityPercent;
            data.shareLocation = shareLocation;
            data.sameLevel = sameLevel;
            data.renderMode = renderMode.configValue();
            data.gKeyHintShown = gKeyHintShown;
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[GeoCraft Overlay] Kon config niet opslaan: {}", e.getMessage());
        }
    }

    private static class ConfigData {
        int opacityPercent = 50;
        boolean shareLocation = false;
        boolean sameLevel = true;
        String renderMode = "fullblock";
        boolean gKeyHintShown = false;
    }
}
