package nl.geocraft.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for the overlay renderer.
 * Stores opacity as a percentage (0–100) and saves to config/geocraft-overlay.json.
 */
public class OverlayConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("geocraft-overlay");
    private static final OverlayConfig INSTANCE = new OverlayConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int opacityPercent = 50;

    public static OverlayConfig getInstance() {
        return INSTANCE;
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

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("geocraft-overlay.json");
    }

    public void load() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            if (data != null) {
                opacityPercent = Math.max(0, Math.min(100, data.opacityPercent));
            }
        } catch (IOException e) {
            LOGGER.warn("[GeoCraft Overlay] Kon config niet laden: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            ConfigData data = new ConfigData();
            data.opacityPercent = opacityPercent;
            Files.writeString(configPath(), GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.warn("[GeoCraft Overlay] Kon config niet opslaan: {}", e.getMessage());
        }
    }

    private static class ConfigData {
        int opacityPercent = 50;
    }
}
