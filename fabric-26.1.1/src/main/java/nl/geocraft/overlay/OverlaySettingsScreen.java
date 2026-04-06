package nl.geocraft.overlay;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

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
        titleWidget.setPosition(centerX - titleWidget.getWidth() / 2, centerY - 40);
        addRenderableWidget(titleWidget);

        // Opacity slider (0–100%)
        addRenderableWidget(new OpacitySlider(
                centerX - 100, centerY - 10, 200, 20,
                config.getOpacityPercent()
        ));

        // Keybind hint
        Component hintText = Component.literal("Page Up / Down — Overlay hoogte aanpassen").withColor(0x888888);
        StringWidget hintWidget = new StringWidget(hintText, this.font);
        hintWidget.setPosition(centerX - hintWidget.getWidth() / 2, centerY + 28);
        addRenderableWidget(hintWidget);

        // Done button
        addRenderableWidget(Button.builder(Component.literal("Klaar"), button -> onClose())
                .bounds(centerX - 50, centerY + 46, 100, 20)
                .build());
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
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
