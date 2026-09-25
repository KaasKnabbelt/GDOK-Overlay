package nl.geocraft.overlay;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nl.geocraft.overlay.markers.PlantFlow;
import org.jspecify.annotations.Nullable;

/**
 * "Bomen planten" confirmation (BOMEN.md §9.5): runs the check, shows what it found and only
 * then starts the commands. A dumb layout over {@link PlantFlow}; opened from the G menu or by
 * the viewer ({@code markers/plant}). Mojmap twin of the yarn screen in {@code fabric-1.21.11}.
 */
public class PlantScreen extends Screen {

    private static final int LINE_HEIGHT = 12;

    private final PlantFlow flow;
    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    private Button plantButton;

    public PlantScreen(String layerId, @Nullable Screen parent) {
        super(Component.literal("Bomen planten"));
        this.flow = new PlantFlow(layerId, GeoOverlayMod.getRenderer().markerProbe());
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addToHeader(new StringWidget(Component.literal(flow.title()), font));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        plantButton = footer.addChild(Button.builder(Component.literal(flow.plantLabel()), b -> {
                    if (flow.confirm()) onClose();
                    else refresh();
                })
                .width(170)
                .build());
        footer.addChild(Button.builder(Component.literal("Opnieuw controleren"), b -> flow.restart())
                .width(130)
                .tooltip(Tooltip.create(Component.literal("Na het weghalen van losse markerblokken, of als je over een niet-geladen deel gevlogen bent.")))
                .build());
        footer.addChild(Button.builder(Component.literal("Annuleren"), b -> onClose()).width(90).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        refresh();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void tick() {
        super.tick();
        flow.tick();
        refresh();
    }

    private void refresh() {
        if (plantButton == null) return;
        plantButton.setMessage(Component.literal(flow.plantLabel()));
        plantButton.active = flow.canPlant();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int y = layout.getHeaderHeight() + 12;
        for (PlantFlow.Line line : flow.lines()) {
            graphics.centeredText(font, line.text(), width / 2, y, line.color());
            y += LINE_HEIGHT;
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
