package nl.geocraft.overlay;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.Positioner;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import nl.geocraft.overlay.render.RenderMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game overlay manager (G, and ModMenu). A dumb widget layout over
 * {@link OverlayMenuState}: one scrollable list with a row per overlay followed by the
 * global settings rows, a status header (block counter + viewer connection) and a footer
 * with "Alles wissen (behalve vastgezet)" and "Klaar". Rows rebuild whenever the overlay
 * store's revision changes, so painting on the site while the menu is open shows up live.
 *
 * <p>The menu has two modes (config {@code advancedMenu}, toggled by the "Menu" row).
 * <b>Eenvoudig</b> (default): per row icon, name, count, show/hide and remove; settings are
 * one shared height stepper, opacity and render mode. <b>Uitgebreid</b> adds the per-row
 * Y-stepper and pin, the same-level row, location sharing and the keybinds shortcut.</p>
 *
 * <p>Yarn twin of the mojmap screen in {@code fabric-26.x}; keep the two in step.</p>
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
    private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this, 44, 33);

    private OverlayList list;
    private TextWidget statusWidget;
    private long shownRevision = -1;
    private boolean shownSameLevel;
    private boolean shownAdvanced;

    public OverlayManagerScreen() {
        this(null);
    }

    public OverlayManagerScreen(@Nullable Screen parent) {
        super(Text.literal("GDOK Overlay"));
        this.parent = parent;
    }

    // -- Screen ---------------------------------------------------------

    @Override
    protected void init() {
        DirectionalLayoutWidget header = layout.addHeader(DirectionalLayoutWidget.vertical().spacing(4));
        header.add(new TextWidget(getTitle(), textRenderer), Positioner::alignHorizontalCenter);
        statusWidget = header.add(new TextWidget(statusText(), textRenderer), Positioner::alignHorizontalCenter);

        list = layout.addBody(new OverlayList(client, width, layout.getContentHeight(), layout.getHeaderHeight()));
        rebuildEntries();

        DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(8));
        footer.add(ButtonWidget.builder(Text.literal("Alles wissen (behalve vastgezet)"), b -> {
                    state.clearUnpinned();
                    rebuildEntries();
                })
                .width(190)
                .tooltip(Tooltip.of(Text.literal("Wist alle lagen, ook in de GDOK viewer; alleen vastgezette lagen blijven staan.")))
                .build());
        footer.add(ButtonWidget.builder(Text.literal("Klaar"), b -> close()).width(100).build());

        layout.forEachChild(this::addDrawableChild);
        refreshWidgetPositions();
    }

    @Override
    protected void refreshWidgetPositions() {
        layout.refreshPositions();
        list.position(width, layout);
    }

    @Override
    public void tick() {
        super.tick();
        statusWidget.setMessage(statusText());
        if (state.revision() != shownRevision
                || state.sameLevel() != shownSameLevel
                || state.advancedMenu() != shownAdvanced) {
            rebuildEntries();
        }
    }

    @Override
    public void close() {
        state.save();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // -- Building -------------------------------------------------------

    private Text statusText() {
        String blocks = String.format("%,d / %,d blokken", state.totalBlocks(), state.maxBlocks()).replace(',', '.');
        Text left = Text.literal(blocks).withColor(state.overBlockLimit() ? RED : GREY);
        Text sep = Text.literal("  ·  GDOK viewer: ").withColor(GREY);
        Text right = state.viewerConnected()
                ? Text.literal("verbonden").withColor(GREEN)
                : Text.literal("niet verbonden").withColor(RED);
        return Text.empty().append(left).append(sep).append(right);
    }

    private void rebuildEntries() {
        shownRevision = state.revision();
        shownSameLevel = state.sameLevel();
        shownAdvanced = state.advancedMenu();
        double scroll = list.getScrollY();
        list.rebuild();
        list.setScrollY(scroll);
    }

    // -- List -----------------------------------------------------------

    private class OverlayList extends ElementListWidget<Entry> {

        OverlayList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        void rebuild() {
            boolean advanced = state.advancedMenu();
            clearEntries();
            List<OverlayMenuState.Row> rows = state.rows();
            if (rows.isEmpty()) {
                addEntry(new TextEntry(Text.literal("Geen overlays. Teken iets in de GDOK viewer.").withColor(GREY)));
            } else {
                for (OverlayMenuState.Row row : rows) addEntry(new RowEntry(row, advanced));
            }
            addEntry(new TextEntry(Text.literal("Instellingen").withColor(WHITE)));
            if (advanced) {
                addEntry(new LevelEntry());
            } else {
                addEntry(new SimpleLevelEntry());
            }
            addEntry(new LookEntry());
            if (advanced) {
                addEntry(new MiscEntry());
            }
            addEntry(new MenuModeEntry());
            addEntry(new TextEntry(Text.literal("Page Up / Page Down past de hoogte ook buiten dit menu aan.").withColor(GREY)));
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 24, 420);
        }
    }

    private abstract static class Entry extends ElementListWidget.Entry<Entry> {
        @Override
        public List<? extends Selectable> selectableChildren() {
            return children().stream().filter(c -> c instanceof Selectable).map(c -> (Selectable) c).toList();
        }
    }

    /** A centred line of text (section heading, hint, empty state). */
    private class TextEntry extends Entry {
        private final Text text;

        TextEntry(Text text) {
            this.text = text;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            context.drawCenteredTextWithShadow(textRenderer, text, getContentMiddleX(), getContentMiddleY() - 4, WHITE);
        }

        @Override
        public List<? extends Element> children() {
            return List.of();
        }
    }

    /** Reusable "- value +" control; the value is drawn by the owning entry. */
    private final class Stepper {
        final ButtonWidget minus;
        final ButtonWidget plus;
        static final int BUTTON_W = 16;
        static final int VALUE_W = 30;
        static final int WIDTH = BUTTON_W * 2 + VALUE_W;

        Stepper(Runnable down, Runnable up, String tooltip) {
            minus = ButtonWidget.builder(Text.literal("-"), b -> down.run()).size(BUTTON_W, 20).build();
            plus = ButtonWidget.builder(Text.literal("+"), b -> up.run()).size(BUTTON_W, 20).build();
            if (tooltip != null) {
                minus.setTooltip(Tooltip.of(Text.literal(tooltip)));
                plus.setTooltip(Tooltip.of(Text.literal(tooltip)));
            }
        }

        void place(int x, int y) {
            minus.setPosition(x, y);
            plus.setPosition(x + BUTTON_W + VALUE_W, y);
        }

        void render(DrawContext context, int mouseX, int mouseY, float delta, String value) {
            minus.render(context, mouseX, mouseY, delta);
            plus.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, value, minus.getX() + BUTTON_W + VALUE_W / 2, minus.getY() + 6, WHITE);
        }

        List<ClickableWidget> widgets() {
            return List.of(minus, plus);
        }
    }

    /** One overlay. In the simple menu the per-row stepper and pin are left out. */
    private class RowEntry extends Entry {
        private final OverlayMenuState.Row row;
        private final ItemStack icon;
        private final @Nullable Stepper stepper;
        private final ButtonWidget visibility;
        private final @Nullable ButtonWidget pin;
        private final ButtonWidget remove;
        private final List<ClickableWidget> children = new ArrayList<>();

        RowEntry(OverlayMenuState.Row row, boolean advanced) {
            this.row = row;
            this.icon = row.isMarker() ? ItemStack.EMPTY : new ItemStack(resolveBlock(row.tag()).asItem());
            this.stepper = !advanced ? null
                    : new Stepper(() -> state.shiftRow(row.id(), -1), () -> state.shiftRow(row.id(), 1),
                    "Hoogte van alleen deze laag. Volgt de laag nog het gedeelde niveau, dan wordt hij hiermee vastgezet.");
            this.visibility = ButtonWidget.builder(Text.literal(row.hidden() ? "Toon" : "Verberg"),
                            b -> state.setHidden(row.id(), !row.hidden()))
                    .size(46, 20)
                    .tooltip(Tooltip.of(Text.literal(row.hidden()
                            ? "Deze laag is verborgen in Minecraft. Klik om hem weer te tonen."
                            : "Verberg deze laag in Minecraft (de site houdt hem gewoon).")))
                    .build();
            this.pin = !advanced ? null
                    : ButtonWidget.builder(Text.literal(row.pinned() ? "Vast" : "Pin"),
                            b -> state.setPinned(row.id(), !row.pinned()))
                    .size(34, 20)
                    .tooltip(Tooltip.of(Text.literal(row.pinned()
                            ? "Vastgezet: houdt zijn eigen hoogte (Page Up/Down en het gedeelde niveau laten hem staan) "
                              + "en blijft bij \"Alles wissen\". Niet bij opnieuw joinen. Klik om los te maken."
                            : "Zet vast: de laag houdt zijn eigen hoogte, beweegt niet mee met Page Up/Down of het gedeelde "
                              + "niveau en blijft staan bij \"Alles wissen\" (tot je de server verlaat).")))
                    .build();
            this.remove = ButtonWidget.builder(Text.literal("X"), b -> state.remove(row.id()))
                    .size(20, 20)
                    .tooltip(Tooltip.of(Text.literal("Verwijder deze laag, ook uit de GDOK viewer (de blokken gaan daar van de kaart).")))
                    .build();
            if (stepper != null) children.addAll(stepper.widgets());
            children.add(visibility);
            if (pin != null) children.add(pin);
            children.add(remove);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            int x = getContentRightEnd();

            x -= remove.getWidth();
            remove.setPosition(x, y);
            if (pin != null) {
                x -= GAP + pin.getWidth();
                pin.setPosition(x, y);
            }
            x -= GAP + visibility.getWidth();
            visibility.setPosition(x, y);
            int stepperX = x;
            if (stepper != null) {
                x -= GAP + Stepper.WIDTH;
                stepperX = x;
            }

            // Icon: the block's item, or a colour swatch for the (tag-less) marker.
            int iconX = getContentX();
            int iconY = getContentMiddleY() - 8;
            if (row.isMarker()) {
                int argb = 0xFF000000 | (row.red() << 16) | (row.green() << 8) | row.blue();
                context.fill(iconX + 2, iconY + 2, iconX + 14, iconY + 14, argb);
            } else {
                context.drawItem(icon, iconX, iconY);
            }

            // Name (truncated to the room left of the controls) and block count underneath in grey.
            int textX = iconX + 20;
            int textRoom = stepperX - GAP - textX;
            int textColor = row.hidden() ? GREY : WHITE;
            String name = textRenderer.trimToWidth(row.name(), textRoom);
            if (!name.equals(row.name()) && name.length() > 1) name = name.substring(0, name.length() - 1) + "…";
            context.drawTextWithShadow(textRenderer, name, textX, getContentMiddleY() - 9, textColor);
            String count = String.format("%,d", row.blocks()).replace(',', '.') + (row.blocks() == 1 ? " blok" : " blokken")
                    + (row.hidden() ? " · verborgen" : "") + (row.pinned() ? " · vast" : "");
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(count, textRoom), textX, getContentMiddleY() + 1, GREY);

            visibility.render(context, mouseX, mouseY, delta);
            if (pin != null) pin.render(context, mouseX, mouseY, delta);
            remove.render(context, mouseX, mouseY, delta);
            // De stepper toont de getekende hoogte; stappen op een laag die nog het gedeelde
            // niveau volgt zet hem vast (zie OverlayManager.adjustOverlayY).
            if (stepper != null) {
                stepper.place(stepperX, y);
                stepper.render(context, mouseX, mouseY, delta, Integer.toString(row.renderY()));
            }
        }

        @Override
        public List<? extends Element> children() {
            return children;
        }
    }

    /** Settings row: same-level toggle + the shared level stepper (only while on). */
    private class LevelEntry extends Entry {
        private final CyclingButtonWidget<Boolean> toggle;
        private final Stepper stepper;
        private final ButtonWidget reset;

        LevelEntry() {
            toggle = CyclingButtonWidget.onOffBuilder(state.sameLevel())
                    .tooltip(v -> Tooltip.of(Text.literal(
                            "Aan: alle losse lagen op één hoogte, samen te verschuiven. Uit: elke laag houdt haar eigen hoogte. Vastgezette lagen doen nooit mee.")))
                    .build(0, 0, 150, 20, Text.literal("Zelfde niveau"), (b, v) -> state.setSameLevel(v));
            stepper = new Stepper(() -> state.shiftLevel(-1), () -> state.shiftLevel(1), "Niveau van alle lagen (als Page Up / Down)");
            reset = ButtonWidget.builder(Text.literal("Reset hoogtes"), b -> state.resetHeights())
                    .size(150 - Stepper.WIDTH - GAP, 20)
                    .tooltip(Tooltip.of(Text.literal("Terug naar de starthoogte uit het AHN zoals de site die stuurde.")))
                    .build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            int left = getContentMiddleX() - 155;
            toggle.setPosition(left, y);
            toggle.render(context, mouseX, mouseY, delta);
            int right = getContentMiddleX() + 5;
            reset.setPosition(right, y);
            reset.render(context, mouseX, mouseY, delta);
            int stepperX = right + reset.getWidth() + GAP;
            Integer level = state.levelY();
            stepper.minus.active = state.sameLevel() && level != null;
            stepper.plus.active = stepper.minus.active;
            stepper.place(stepperX, y);
            stepper.render(context, mouseX, mouseY, delta, state.sameLevel() && level != null ? level.toString() : "-");
        }

        @Override
        public List<? extends Element> children() {
            List<Element> c = new ArrayList<>();
            c.add(toggle);
            c.add(reset);
            c.addAll(stepper.widgets());
            return c;
        }
    }

    /** Simple-mode settings row: one height stepper that moves every layer (as Page Up/Down). */
    private class SimpleLevelEntry extends Entry {
        private final Stepper stepper = new Stepper(() -> state.shiftLevel(-1), () -> state.shiftLevel(1),
                "Hoogte van alle lagen (als Page Up / Page Down)");

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            context.drawTextWithShadow(textRenderer, "Hoogte", getContentMiddleX() - 155, getContentMiddleY() - 4, WHITE);
            Integer level = state.levelY();
            stepper.place(getContentMiddleX() + 5, y);
            stepper.render(context, mouseX, mouseY, delta, level != null ? level.toString() : "-");
        }

        @Override
        public List<? extends Element> children() {
            return stepper.widgets();
        }
    }

    /** Settings row: opacity slider + render mode. */
    private class LookEntry extends Entry {
        private final OpacitySlider slider = new OpacitySlider(state.opacityPercent());
        private final CyclingButtonWidget<RenderMode> mode = CyclingButtonWidget.<RenderMode>builder(
                        m -> Text.literal(OverlayMenuState.renderModeLabel(m)), state.renderMode())
                .values(RenderMode.values())
                .build(0, 0, 150, 20, Text.literal("Weergave"), (b, v) -> state.setRenderMode(v));

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            slider.setPosition(getContentMiddleX() - 155, y);
            slider.render(context, mouseX, mouseY, delta);
            mode.setPosition(getContentMiddleX() + 5, y);
            mode.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(slider, mode);
        }
    }

    /** Advanced settings row: player location sharing + the keybinds shortcut. */
    private class MiscEntry extends Entry {
        private final CyclingButtonWidget<Boolean> share = CyclingButtonWidget.onOffBuilder(state.shareLocation())
                .tooltip(v -> Tooltip.of(Text.literal(
                        "Deel je positie met de GDOK viewer, die toont dan je spelerkop op de kaart (alleen op GeoCraft).")))
                .build(0, 0, 150, 20, Text.literal("Spelerlocatie"), (b, v) -> {
                    state.setShareLocation(v);
                    GeoOverlayMod.refreshPlayerTracking();
                });
        private final ButtonWidget keybinds = ButtonWidget.builder(Text.literal("Toetsen aanpassen..."),
                        b -> {
                            if (client != null) {
                                client.setScreen(new net.minecraft.client.gui.screen.option.KeybindsScreen(
                                        OverlayManagerScreen.this, client.options));
                            }
                        })
                .size(150, 20)
                .tooltip(Tooltip.of(Text.literal("Opent het toetsenscherm; de sneltoetsen van de mod staan onder \"GDOK Overlay\".")))
                .build();

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            share.setPosition(getContentMiddleX() - 155, y);
            share.render(context, mouseX, mouseY, delta);
            keybinds.setPosition(getContentMiddleX() + 5, y);
            keybinds.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(share, keybinds);
        }
    }

    /** Settings row: simple/advanced menu toggle (config, persisted on close). */
    private class MenuModeEntry extends Entry {
        private final CyclingButtonWidget<Boolean> mode = CyclingButtonWidget.<Boolean>builder(
                        v -> Text.literal(v ? "Uitgebreid" : "Eenvoudig"), state.advancedMenu())
                .values(Boolean.FALSE, Boolean.TRUE)
                .tooltip(v -> Tooltip.of(Text.literal(v
                        ? "Alle knoppen: hoogte en vastzetten per laag, gedeeld niveau, spelerlocatie en sneltoetsen."
                        : "Alleen de basis: lagen tonen, verbergen en verwijderen, hoogte, doorzichtigheid en weergave.")))
                .build(0, 0, 150, 20, Text.literal("Menu"), (b, v) -> state.setAdvancedMenu(v));

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + (getContentHeight() - 20) / 2;
            mode.setPosition(getContentMiddleX() - 155, y);
            mode.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(mode);
        }
    }

    private class OpacitySlider extends SliderWidget {
        OpacitySlider(int initialPercent) {
            super(0, 0, 150, 20, Text.literal("Doorzichtigheid: " + initialPercent + "%"), initialPercent / 100.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Doorzichtigheid: " + (int) Math.round(value * 100) + "%"));
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
            if (id != null) block = Registries.BLOCK.get(id);
        }
        if (block == null || block == Blocks.AIR) block = Blocks.WHITE_WOOL;
        return block;
    }
}
