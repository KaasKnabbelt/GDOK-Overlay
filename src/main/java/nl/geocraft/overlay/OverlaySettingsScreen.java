package nl.geocraft.overlay;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

/**
 * In-game settings screen with an opacity slider.
 * Opened via keybind (default: G).
 */
public class OverlaySettingsScreen extends Screen {

    private final OverlayConfig config = OverlayConfig.getInstance();
    private final Screen parent;

    public OverlaySettingsScreen() {
        this(null);
    }

    public OverlaySettingsScreen(Screen parent) {
        super(Text.literal("GDOK Overlay"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Title
        TextWidget titleWidget = new TextWidget(Text.literal("GDOK Overlay"), this.textRenderer);
        titleWidget.setPosition(centerX - titleWidget.getWidth() / 2, centerY - 40);
        addDrawableChild(titleWidget);

        // Opacity slider (0–100%)
        addDrawableChild(new OpacitySlider(
                centerX - 100, centerY - 10, 200, 20,
                config.getOpacityPercent()
        ));

        // Keybind hint
        Text hintText = Text.literal("Page Up / Down — Overlay hoogte aanpassen").styled(s -> s.withColor(0x888888));
        TextWidget hintWidget = new TextWidget(hintText, this.textRenderer);
        hintWidget.setPosition(centerX - hintWidget.getWidth() / 2, centerY + 28);
        addDrawableChild(hintWidget);

        // Done button
        addDrawableChild(ButtonWidget.builder(Text.literal("Klaar"), button -> close())
                .dimensions(centerX - 50, centerY + 46, 100, 20)
                .build());
    }

    @Override
    public void close() {
        config.save();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, 0x80000000, 0xA0000000);
    }

    /**
     * Custom slider widget for opacity percentage.
     */
    private class OpacitySlider extends SliderWidget {

        public OpacitySlider(int x, int y, int width, int height, int initialPercent) {
            super(x, y, width, height, Text.literal("Opacity: " + initialPercent + "%"), initialPercent / 100.0);
        }

        @Override
        protected void updateMessage() {
            int percent = (int) Math.round(this.value * 100);
            setMessage(Text.literal("Opacity: " + percent + "%"));
        }

        @Override
        protected void applyValue() {
            int percent = (int) Math.round(this.value * 100);
            config.setOpacityPercent(percent);
        }
    }
}
