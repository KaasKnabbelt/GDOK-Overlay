package nl.geocraft.overlay;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.text.Text;
import nl.geocraft.overlay.markers.PlantFlow;
import org.jetbrains.annotations.Nullable;

/**
 * "Bomen planten" confirmation (BOMEN.md §9.5): runs the check, shows what it found and only
 * then starts the commands. A dumb layout over {@link PlantFlow}; opened from the G menu or by
 * the viewer ({@code markers/plant}). Yarn twin of the mojmap screen in {@code fabric-26.x}.
 */
public class PlantScreen extends Screen {

    private static final int LINE_HEIGHT = 12;

    private final PlantFlow flow;
    private final @Nullable Screen parent;
    private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this, 33, 33);
    private ButtonWidget plantButton;

    public PlantScreen(String layerId, @Nullable Screen parent) {
        super(Text.literal("Bomen planten"));
        this.flow = new PlantFlow(layerId, GeoOverlayMod.getRenderer().markerProbe());
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addHeader(new TextWidget(Text.literal(flow.title()), textRenderer));
        DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(8));
        plantButton = footer.add(ButtonWidget.builder(Text.literal(flow.plantLabel()), b -> {
                    if (flow.confirm()) close();
                    else refresh();
                })
                .width(170)
                .build());
        footer.add(ButtonWidget.builder(Text.literal("Opnieuw controleren"), b -> flow.restart())
                .width(130)
                .tooltip(Tooltip.of(Text.literal("Na het weghalen van losse markerblokken, of als je over een niet-geladen deel gevlogen bent.")))
                .build());
        footer.add(ButtonWidget.builder(Text.literal("Annuleren"), b -> close()).width(90).build());
        layout.forEachChild(this::addDrawableChild);
        refreshWidgetPositions();
        refresh();
    }

    @Override
    protected void refreshWidgetPositions() {
        layout.refreshPositions();
    }

    @Override
    public void tick() {
        super.tick();
        flow.tick();
        refresh();
    }

    private void refresh() {
        if (plantButton == null) return;
        plantButton.setMessage(Text.literal(flow.plantLabel()));
        plantButton.active = flow.canPlant();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int y = layout.getHeaderHeight() + 12;
        for (PlantFlow.Line line : flow.lines()) {
            context.drawCenteredTextWithShadow(textRenderer, line.text(), width / 2, y, line.color());
            y += LINE_HEIGHT;
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
