package nl.geocraft.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import nl.geocraft.overlay.render.RenderMode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game overlay manager (G, and ModMenu). A dumb widget layout over
 * {@link OverlayMenuState}: one scrollable list with a row per overlay (icon, name, block
 * count, Y-stepper, show/hide, pin, remove) followed by the global settings rows, a status
 * header (block counter + viewer connection) and a footer with "Alles wissen (behalve
 * vastgezet)" and "Klaar". Rows rebuild whenever the overlay store's revision changes, so
 * painting on the site while the menu is open shows up live.
 */
public class OverlayManagerScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int GREY = 0xFFA0A0A0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int GAP = 4;

    private final OverlayMenuState state = new OverlayMenuState();
    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 44, 33);

    private OverlayList list;
    private StringWidget statusWidget;
    private long shownRevision = -1;
    private boolean shownSameLevel;

    public OverlayManagerScreen() {
        this(null);
    }

    public OverlayManagerScreen(@Nullable Screen parent) {
        super(Component.literal("GDOK Overlay"));
        this.parent = parent;
    }

    // -- Screen ---------------------------------------------------------

    @Override
    protected void init() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.addChild(new StringWidget(getTitle(), font), LayoutSettings::alignHorizontallyCenter);
        statusWidget = header.addChild(new StringWidget(statusText(), font), LayoutSettings::alignHorizontallyCenter);

        list = layout.addToContents(new OverlayList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));
        rebuildEntries();

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.literal("Alles wissen (behalve vastgezet)"), b -> {
                    state.clearUnpinned();
                    rebuildEntries();
                })
                .width(190)
                .tooltip(Tooltip.create(Component.literal("Wist alle lagen, ook in de GDOK viewer; alleen vastgezette lagen blijven staan.")))
                .build());
        footer.addChild(Button.builder(Component.literal("Klaar"), b -> onClose()).width(100).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        list.updateSize(width, layout);
    }

    @Override
    public void tick() {
        super.tick();
        statusWidget.setMessage(statusText());
        if (state.revision() != shownRevision || state.sameLevel() != shownSameLevel) {
            rebuildEntries();
        }
    }

    @Override
    public void onClose() {
        state.save();
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // -- Building -------------------------------------------------------

    private Component statusText() {
        String blocks = String.format("%,d / %,d blokken", state.totalBlocks(), state.maxBlocks()).replace(',', '.');
        Component left = Component.literal(blocks).withColor(state.overBlockLimit() ? RED : GREY);
        Component sep = Component.literal("  ·  GDOK viewer: ").withColor(GREY);
        Component right = state.viewerConnected()
                ? Component.literal("verbonden").withColor(GREEN)
                : Component.literal("niet verbonden").withColor(RED);
        return Component.empty().append(left).append(sep).append(right);
    }

    private void rebuildEntries() {
        shownRevision = state.revision();
        shownSameLevel = state.sameLevel();
        double scroll = list.scrollAmount();
        list.rebuild();
        list.setScrollAmount(scroll);
    }

    // -- List -----------------------------------------------------------

    private class OverlayList extends ContainerObjectSelectionList<Entry> {

        OverlayList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
        }

        void rebuild() {
            clearEntries();
            List<OverlayMenuState.Row> rows = state.rows();
            if (rows.isEmpty()) {
                addEntry(new TextEntry(Component.literal("Geen overlays. Teken iets in de GDOK viewer.").withColor(GREY)));
            } else {
                for (OverlayMenuState.Row row : rows) addEntry(new RowEntry(row));
            }
            addEntry(new TextEntry(Component.literal("Instellingen").withColor(WHITE)));
            addEntry(new LevelEntry());
            addEntry(new LookEntry());
            addEntry(new MiscEntry());
            addEntry(new TextEntry(Component.literal("Page Up / Page Down past de hoogte ook buiten dit menu aan.").withColor(GREY)));
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 24, 420);
        }
    }

    private abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        @Override
        public List<? extends NarratableEntry> narratables() {
            return children().stream().filter(c -> c instanceof NarratableEntry).map(c -> (NarratableEntry) c).toList();
        }
    }

    /** A centred line of text (section heading, hint, empty state). */
    private class TextEntry extends Entry {
        private final Component text;

        TextEntry(Component text) {
            this.text = text;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            graphics.centeredText(font, text, getContentXMiddle(), getContentYMiddle() - 4, WHITE);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }
    }

    /** Reusable "- value +" control; the value is drawn by the owning entry. */
    private final class Stepper {
        final Button minus;
        final Button plus;
        static final int BUTTON_W = 16;
        static final int VALUE_W = 30;
        static final int WIDTH = BUTTON_W * 2 + VALUE_W;

        Stepper(Runnable down, Runnable up, String tooltip) {
            minus = Button.builder(Component.literal("-"), b -> down.run()).size(BUTTON_W, 20).build();
            plus = Button.builder(Component.literal("+"), b -> up.run()).size(BUTTON_W, 20).build();
            if (tooltip != null) {
                minus.setTooltip(Tooltip.create(Component.literal(tooltip)));
                plus.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
        }

        void place(int x, int y) {
            minus.setPosition(x, y);
            plus.setPosition(x + BUTTON_W + VALUE_W, y);
        }

        void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, String value) {
            minus.extractRenderState(graphics, mouseX, mouseY, a);
            plus.extractRenderState(graphics, mouseX, mouseY, a);
            graphics.centeredText(font, value, minus.getX() + BUTTON_W + VALUE_W / 2, minus.getY() + 6, WHITE);
        }

        List<AbstractWidget> widgets() {
            return List.of(minus, plus);
        }
    }

    /** One overlay. */
    private class RowEntry extends Entry {
        private final OverlayMenuState.Row row;
        private final ItemStack icon;
        private final Stepper stepper;
        private final Button visibility;
        private final Button pin;
        private final Button remove;
        private final List<AbstractWidget> children = new ArrayList<>();

        RowEntry(OverlayMenuState.Row row) {
            this.row = row;
            this.icon = row.isMarker() ? ItemStack.EMPTY : new ItemStack(resolveBlock(row.tag()).asItem());
            this.stepper = new Stepper(() -> state.shiftRow(row.id(), -1), () -> state.shiftRow(row.id(), 1),
                    "Hoogte van alleen deze laag. Volgt de laag nog het gedeelde niveau, dan wordt hij hiermee vastgezet.");
            this.visibility = Button.builder(Component.literal(row.hidden() ? "Toon" : "Verberg"),
                            b -> state.setHidden(row.id(), !row.hidden()))
                    .size(46, 20)
                    .tooltip(Tooltip.create(Component.literal(row.hidden()
                            ? "Deze laag is verborgen in Minecraft. Klik om hem weer te tonen."
                            : "Verberg deze laag in Minecraft (de site houdt hem gewoon).")))
                    .build();
            this.pin = Button.builder(Component.literal(row.pinned() ? "Vast" : "Pin"),
                            b -> state.setPinned(row.id(), !row.pinned()))
                    .size(34, 20)
                    .tooltip(Tooltip.create(Component.literal(row.pinned()
                            ? "Vastgezet: houdt zijn eigen hoogte (Page Up/Down en het gedeelde niveau laten hem staan) "
                              + "en blijft bij \"Alles wissen\". Niet bij opnieuw joinen. Klik om los te maken."
                            : "Zet vast: de laag houdt zijn eigen hoogte, beweegt niet mee met Page Up/Down of het gedeelde "
                              + "niveau en blijft staan bij \"Alles wissen\" (tot je de server verlaat).")))
                    .build();
            this.remove = Button.builder(Component.literal("X"), b -> state.remove(row.id()))
                    .size(20, 20)
                    .tooltip(Tooltip.create(Component.literal("Verwijder deze laag, ook uit de GDOK viewer (de blokken gaan daar van de kaart).")))
                    .build();
            children.addAll(stepper.widgets());
            children.add(visibility);
            children.add(pin);
            children.add(remove);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            int x = getContentRight();

            x -= remove.getWidth();
            remove.setPosition(x, y);
            x -= GAP + pin.getWidth();
            pin.setPosition(x, y);
            x -= GAP + visibility.getWidth();
            visibility.setPosition(x, y);
            x -= GAP + Stepper.WIDTH;
            int stepperX = x;

            // Icon: the block's item, or a colour swatch for the (tag-less) marker.
            int iconX = getContentX();
            int iconY = getContentYMiddle() - 8;
            if (row.isMarker()) {
                int argb = 0xFF000000 | (row.red() << 16) | (row.green() << 8) | row.blue();
                graphics.fill(iconX + 2, iconY + 2, iconX + 14, iconY + 14, argb);
            } else {
                graphics.item(icon, iconX, iconY);
            }

            // Name (truncated to the room left of the controls) and block count underneath in grey.
            int textX = iconX + 20;
            int textRoom = stepperX - GAP - textX;
            int textColor = row.hidden() ? GREY : WHITE;
            String name = font.plainSubstrByWidth(row.name(), textRoom);
            if (!name.equals(row.name()) && name.length() > 1) name = name.substring(0, name.length() - 1) + "…";
            graphics.text(font, name, textX, getContentYMiddle() - 9, textColor);
            String count = String.format("%,d", row.blocks()).replace(',', '.') + (row.blocks() == 1 ? " blok" : " blokken")
                    + (row.hidden() ? " · verborgen" : "") + (row.pinned() ? " · vast" : "");
            graphics.text(font, font.plainSubstrByWidth(count, textRoom), textX, getContentYMiddle() + 1, GREY);

            for (AbstractWidget w : List.of(visibility, pin, remove)) w.extractRenderState(graphics, mouseX, mouseY, a);
            // De stepper toont de getekende hoogte; stappen op een laag die nog het gedeelde
            // niveau volgt zet hem vast (zie OverlayManager.adjustOverlayY).
            stepper.place(stepperX, y);
            stepper.extract(graphics, mouseX, mouseY, a, Integer.toString(row.renderY()));
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return children;
        }
    }

    /** Settings row: same-level toggle + the shared level stepper (only while on). */
    private class LevelEntry extends Entry {
        private final CycleButton<Boolean> toggle;
        private final Stepper stepper;
        private final Button reset;

        LevelEntry() {
            toggle = CycleButton.onOffBuilder(state.sameLevel())
                    .withTooltip(v -> Tooltip.create(Component.literal(
                            "Aan: alle losse lagen op één hoogte, samen te verschuiven. Uit: elke laag houdt haar eigen hoogte. Vastgezette lagen doen nooit mee.")))
                    .create(0, 0, 150, 20, Component.literal("Zelfde niveau"), (b, v) -> state.setSameLevel(v));
            stepper = new Stepper(() -> state.shiftLevel(-1), () -> state.shiftLevel(1), "Niveau van alle lagen (als Page Up / Down)");
            reset = Button.builder(Component.literal("Reset hoogtes"), b -> state.resetHeights())
                    .size(150 - Stepper.WIDTH - GAP, 20)
                    .tooltip(Tooltip.create(Component.literal("Terug naar de starthoogte uit het AHN zoals de site die stuurde.")))
                    .build();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            int left = getContentXMiddle() - 155;
            toggle.setPosition(left, y);
            toggle.extractRenderState(graphics, mouseX, mouseY, a);
            int right = getContentXMiddle() + 5;
            reset.setPosition(right, y);
            reset.extractRenderState(graphics, mouseX, mouseY, a);
            int stepperX = right + reset.getWidth() + GAP;
            Integer level = state.levelY();
            stepper.minus.active = state.sameLevel() && level != null;
            stepper.plus.active = stepper.minus.active;
            stepper.place(stepperX, y);
            stepper.extract(graphics, mouseX, mouseY, a, state.sameLevel() && level != null ? level.toString() : "-");
        }

        @Override
        public List<? extends GuiEventListener> children() {
            List<GuiEventListener> c = new ArrayList<>();
            c.add(toggle);
            c.add(reset);
            c.addAll(stepper.widgets());
            return c;
        }
    }

    /** Settings row: opacity slider + render mode. */
    private class LookEntry extends Entry {
        private final OpacitySlider slider = new OpacitySlider(state.opacityPercent());
        private final CycleButton<RenderMode> mode = CycleButton.<RenderMode>builder(
                        m -> Component.literal(OverlayMenuState.renderModeLabel(m)), state.renderMode())
                .withValues(RenderMode.values())
                .create(0, 0, 150, 20, Component.literal("Weergave"), (b, v) -> state.setRenderMode(v));

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            slider.setPosition(getContentXMiddle() - 155, y);
            slider.extractRenderState(graphics, mouseX, mouseY, a);
            mode.setPosition(getContentXMiddle() + 5, y);
            mode.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(slider, mode);
        }
    }

    /** Settings row: player location sharing. */
    private class MiscEntry extends Entry {
        private final CycleButton<Boolean> share = CycleButton.onOffBuilder(state.shareLocation())
                .withTooltip(v -> Tooltip.create(Component.literal(
                        "Deel je positie met de GDOK viewer, die toont dan je spelerkop op de kaart (alleen op GeoCraft).")))
                .create(0, 0, 150, 20, Component.literal("Spelerlocatie"), (b, v) -> {
                    state.setShareLocation(v);
                    GeoOverlayMod.refreshPlayerTracking();
                });

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            share.setPosition(getContentXMiddle() - 155, y);
            share.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(share);
        }
    }

    private class OpacitySlider extends AbstractSliderButton {
        OpacitySlider(int initialPercent) {
            super(0, 0, 150, 20, Component.literal("Doorzichtigheid: " + initialPercent + "%"), initialPercent / 100.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Doorzichtigheid: " + (int) Math.round(value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            state.setOpacityPercent((int) Math.round(value * 100));
        }
    }

    // -- Helpers --------------------------------------------------------

    private static Block resolveBlock(String tag) {
        Block block = null;
        if (tag != null) {
            Identifier id = Identifier.tryParse(tag.contains(":") ? tag : "minecraft:" + tag);
            if (id != null) block = BuiltInRegistries.BLOCK.getValue(id);
        }
        if (block == null || block == Blocks.AIR) block = Blocks.WOOL.white();
        return block;
    }
}
