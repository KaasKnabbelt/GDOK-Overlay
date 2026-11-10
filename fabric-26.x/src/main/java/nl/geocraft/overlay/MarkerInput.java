package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nl.geocraft.overlay.markers.MarkerLayer;
import nl.geocraft.overlay.markers.MarkerManager;
import nl.geocraft.overlay.markers.MarkerPicker;

/**
 * Middle- and right-click on a ghost marker (BOMEN.md §9.3), in creative.
 *
 * <ul>
 *   <li><b>Middle-click</b> puts the marker block in your hand, the way vanilla pick-block does:
 *       the item goes into a hotbar slot through the creative inventory packet and that slot is
 *       selected.</li>
 *   <li><b>Right-click</b> puts the marker block on its spot, whatever you hold: the same hotbar
 *       route, then an ordinary block placement ({@code useItemOn}) against the top of the ground
 *       block, or against the plant on the spot (vanilla replaces it). To the server that is
 *       pick-block plus placing; no packets of our own. Held down it places one ghost after the
 *       other at vanilla's pace (4 ticks). Only ghosts the scanner found free; the actionbar
 *       names the aimed ghost's block and shows the counter after a placement.</li>
 * </ul>
 *
 * <p>Runs at the start of the client tick, before vanilla handles the keys. A press that does not
 * hit a ghost (or hits a real block or entity first) is put back, so vanilla keeps working
 * everywhere else. Vanilla also repeats a held use key every 4 ticks with whatever is in the hand.
 * On a ghost, and for the rest of a hold that placed ghosts, the use key is therefore "up" for
 * vanilla during the tick and set back at its end ({@link #onEndTick}): otherwise the marker block
 * just placed would get a second one on top, and that one would become a tree too.</p>
 *
 * <p>Mojmap twin of the yarn class in {@code fabric-1.21.11}; keep the modules in step.</p>
 */
final class MarkerInput {

    private static final int HOTBAR = 9;
    /** Vanilla's item-use cooldown: one block per 4 ticks while the key is held. */
    private static final int USE_DELAY = 4;
    /** The aimed ghost's name is shown again after this many ticks (the actionbar fades after 60). */
    private static final int NAME_REFRESH = 40;
    /** After a placement the counter stays this long before a name replaces it. */
    private static final int COUNTER_HOLD = 20;

    private static int cooldown;
    private static boolean useSuppressed;
    /** The use key has been held since one of our placements. */
    private static boolean holdOwned;
    private static MarkerLayer counterLayer;
    private static int counterHold;
    private static MarkerLayer shownLayer;
    private static int shownIndex = -1;
    private static int shownTicks;

    private MarkerInput() {}

    static void onStartTick(Minecraft client) {
        if (cooldown > 0) cooldown--;
        if (counterHold > 0) counterHold--;
        if (client.player == null || client.level == null || client.gui.screen() != null) return;
        if (MarkerManager.getInstance().isEmpty()) return;
        McMarkerProbe.loadDutch();
        handlePick(client);
        // "Rechtsklik plaatst" uit (G-menu, 1.2.1): vanilla zet neer wat je in je hand hebt, en een
        // los blok op de plek telt als geplaatst (MarkerScanner). Middelklik blijft werken.
        if (OverlayConfig.getInstance().isMarkerPlace()) handleUse(client);
        else holdOwned = false;
    }

    /** End of the tick: the use key gets its real state back (see the class comment). */
    static void onEndTick(Minecraft client) {
        if (!useSuppressed) return;
        useSuppressed = false;
        // A screen that opened during the tick released every key; leave it released.
        if (client.gui.screen() == null) client.options.keyUse.setDown(true);
    }

    // -- Middle-click ------------------------------------------------------

    private static void handlePick(Minecraft client) {
        KeyMapping pick = client.options.keyPickItem;
        int passOn = 0;
        while (pick.consumeClick()) {
            if (!tryPick(client)) passOn++;
        }
        for (int i = 0; i < passOn; i++) KeyMapping.click(KeyMappingHelper.getBoundKeyOf(pick));
    }

    private static boolean tryPick(Minecraft client) {
        LocalPlayer player = client.player;
        MarkerPicker.Hit hit = aimedGhost(client, false);
        if (hit == null) return false;
        if (!player.isCreative()) {
            player.sendOverlayMessage(Component.literal(Messages.markerPickNeedsCreative()));
            return false;
        }
        Item item = McMarkerProbe.resolve(hit.block()).asItem();
        if (item == Items.AIR || client.gameMode == null) return false;
        selectInHotbar(client, item);
        player.sendOverlayMessage(Component.literal(Messages.markerPicked(McMarkerProbe.displayName(hit.block()))));
        return true;
    }

    // -- Right-click -------------------------------------------------------

    private static void handleUse(Minecraft client) {
        LocalPlayer player = client.player;
        KeyMapping use = client.options.keyUse;
        if (counterLayer != null) {
            MarkerLayer.Counts c = counterLayer.counts();
            player.sendOverlayMessage(Component.literal(Messages.markerCounter(c.placed(), c.total())));
            counterLayer = null;
            counterHold = COUNTER_HOLD;
            shownLayer = null;
        }
        boolean able = player.isCreative() && !player.isUsingItem() && !player.isHandsBusy()
                && client.gameMode != null && !client.gameMode.isDestroying();
        MarkerPicker.Hit ghost = able ? aimedGhost(client, true) : null;
        showName(player, ghost);

        int presses = 0;
        while (use.consumeClick()) presses++;
        boolean held = use.isDown();
        if (!held) holdOwned = false;
        if (ghost != null) {
            // A click places at once (as in vanilla); held, every USE_DELAY ticks.
            if ((presses > 0 || (held && cooldown == 0)) && place(client, ghost)) {
                cooldown = USE_DELAY;
                holdOwned = held;
            }
        } else {
            for (int i = 0; i < presses; i++) KeyMapping.click(KeyMappingHelper.getBoundKeyOf(use));
        }
        if (held && able && (ghost != null || holdOwned)) {
            use.setDown(false);
            useSuppressed = true;
        }
    }

    private static boolean place(Minecraft client, MarkerPicker.Hit hit) {
        LocalPlayer player = client.player;
        Item item = McMarkerProbe.resolve(hit.block()).asItem();
        if (item == Items.AIR) return false;
        Vec3 eye = player.getEyePosition(1f);
        Vec3 look = player.getViewVector(1f);
        BlockPos spot = new BlockPos(hit.x(), hit.y(), hit.z());
        BlockState there = client.level.getBlockState(spot);
        BlockHitResult target;
        if (there.isAir()) {
            double[] p = MarkerPicker.groundHitPoint(hit, eye.x, eye.y, eye.z, look.x, look.y, look.z);
            target = new BlockHitResult(new Vec3(p[0], p[1], p[2]), Direction.UP, spot.below(), false);
        } else if (there.canBeReplaced() && there.getFluidState().isEmpty()) {
            double[] p = MarkerPicker.entryPoint(hit, eye.x, eye.y, eye.z, look.x, look.y, look.z);
            target = new BlockHitResult(new Vec3(p[0], p[1], p[2]), Direction.UP, spot, false);
        } else {
            return false; // something else stands there now; the scanner will notice
        }
        selectInHotbar(client, item);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, target);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        counterLayer = hit.layer();
        return true;
    }

    /** The aimed ghost's block name in the actionbar; repeated while you keep looking at it. */
    private static void showName(LocalPlayer player, MarkerPicker.Hit ghost) {
        if (ghost == null) {
            shownLayer = null;
            return;
        }
        if (counterHold > 0) return;
        boolean same = ghost.layer() == shownLayer && ghost.index() == shownIndex;
        if (same && --shownTicks > 0) return;
        shownLayer = ghost.layer();
        shownIndex = ghost.index();
        shownTicks = NAME_REFRESH;
        player.sendOverlayMessage(Component.literal(Messages.markerAim(McMarkerProbe.displayName(ghost.block()))));
    }

    // -- Shared ------------------------------------------------------------

    /** The ghost under the crosshair within reach, unless a real block or entity is in front of it. */
    private static MarkerPicker.Hit aimedGhost(Minecraft client, boolean emptyOnly) {
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition(1f);
        Vec3 look = player.getViewVector(1f);
        MarkerPicker.Hit hit = MarkerPicker.pick(MarkerManager.getInstance().layers(),
                eye.x, eye.y, eye.z, look.x, look.y, look.z, player.blockInteractionRange(), emptyOnly);
        if (hit == null) return null;
        HitResult vanilla = client.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceTo(eye) < hit.distance() - 1e-3) {
            return null;
        }
        return hit;
    }

    /**
     * The item into the hotbar and selected, as creative pick-block does: an existing slot with
     * it, else the selected slot when empty, else the first empty one, else the selected one.
     */
    private static void selectInHotbar(Minecraft client, Item item) {
        Inventory inv = client.player.getInventory();
        int slot = -1;
        for (int i = 0; i < HOTBAR; i++) {
            if (inv.getItem(i).getItem() == item) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            int selected = inv.getSelectedSlot();
            if (inv.getItem(selected).isEmpty()) {
                slot = selected;
            } else {
                for (int i = 0; i < HOTBAR && slot < 0; i++) {
                    if (inv.getItem(i).isEmpty()) slot = i;
                }
                if (slot < 0) slot = selected; // creative: blocks are free, overwrite the selected one
            }
            ItemStack stack = new ItemStack(item);
            inv.setItem(slot, stack);
            client.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
        }
        inv.setSelectedSlot(slot);
    }
}
