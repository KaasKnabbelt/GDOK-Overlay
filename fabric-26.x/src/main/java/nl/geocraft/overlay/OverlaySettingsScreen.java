package nl.geocraft.overlay;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import nl.geocraft.overlay.render.RenderMode;

public class OverlaySettingsScreen extends Screen {

    private final OverlayConfig config = OverlayConfig.getInstance();
    private final Screen parent;

    public OverlaySettingsScreen() {
        this(null);
    }

    public OverlaySettingsScreen(Screen parent) {
        super(Component.literal("GDOK Overlay"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Title
        StringWidget titleWidget = new StringWidget(Component.literal("GDOK Overlay"), this.font);
        titleWidget.setPosition(centerX - titleWidget.getWidth() / 2, centerY - 100);
        addRenderableWidget(titleWidget);

        // Opacity slider (0–100%)
        addRenderableWidget(new OpacitySlider(
                centerX - 100, centerY - 75, 200, 20,
                config.getOpacityPercent()
        ));

        // Spelerlocatie toggle
        addRenderableWidget(CycleButton.onOffBuilder(config.isShareLocation())
                .create(centerX - 100, centerY - 50, 200, 20,
                        Component.literal("Spelerlocatie"),
                        (btn, value) -> {
                            config.setShareLocation(value);
                            GeoOverlayMod.refreshPlayerTracking();
                        }));

        // Zelfde-niveau toggle: nieuwe overlays krijgen automatisch de gemiddelde Y.
        addRenderableWidget(CycleButton.onOffBuilder(config.isSameLevel())
                .create(centerX - 100, centerY - 25, 200, 20,
                        Component.literal("Zelfde niveau"),
                        (btn, value) -> config.setSameLevel(value)));

        // Weergave: volledige blokken (echte texture) of dun getint tapijt.
        addRenderableWidget(CycleButton.<RenderMode>builder(mode -> Component.literal(renderModeLabel(mode)), config.getRenderMode())
                .withValues(RenderMode.values())
                .create(centerX - 100, centerY, 200, 20,
                        Component.literal("Weergave"),
                        (btn, value) -> config.setRenderMode(value)));

        // Reset-knop: zet alle overlays terug op hun originele Y.
        addRenderableWidget(Button.builder(Component.literal("Reset hoogtes"),
                        button -> resetAllOverlayHeights())
                .bounds(centerX - 100, centerY + 25, 200, 20)
                .build());

        // Keybind hint
        Component hintText = Component.literal("Page Up / Down — Overlay hoogte aanpassen").withColor(0x888888);
        StringWidget hintWidget = new StringWidget(hintText, this.font);
        hintWidget.setPosition(centerX - hintWidget.getWidth() / 2, centerY + 55);
        addRenderableWidget(hintWidget);

        // Done button
        addRenderableWidget(Button.builder(Component.literal("Klaar"), button -> onClose())
                .bounds(centerX - 50, centerY + 75, 100, 20)
                .build());
    }

    static String renderModeLabel(RenderMode mode) {
        return mode == RenderMode.CARPET ? "Dun tapijt" : "Volledige blokken";
    }

    private void resetAllOverlayHeights() {
        java.util.Map<String, Integer> changes = OverlayManager.getInstance().resetAllY();
        BridgeServer bridge = BridgeServer.getInstance();
        if (bridge == null) return;
        for (var entry : changes.entrySet()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "overlay");
            msg.addProperty("action", "updateY");
            msg.addProperty("id", entry.getKey());
            msg.addProperty("y", entry.getValue());
            bridge.broadcastMessage(msg);
        }
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x80000000, 0xA0000000);
    }

    private class OpacitySlider extends AbstractSliderButton {

        public OpacitySlider(int x, int y, int width, int height, int initialPercent) {
            super(x, y, width, height, Component.literal("Opacity: " + initialPercent + "%"), initialPercent / 100.0);
        }

        @Override
        protected void updateMessage() {
            int percent = (int) Math.round(this.value * 100);
            setMessage(Component.literal("Opacity: " + percent + "%"));
        }

        @Override
        protected void applyValue() {
            int percent = (int) Math.round(this.value * 100);
            config.setOpacityPercent(percent);
        }
    }
}
