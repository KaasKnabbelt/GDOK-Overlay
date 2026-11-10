package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
 *       route, then an ordinary block placement ({@code interactBlock}) against the top of the
 *       ground block, or against the plant on the spot (vanilla replaces it). To the server that
 *       is pick-block plus placing; no packets of our own. Held down it places one ghost after
 *       the other at vanilla's pace (4 ticks). Only ghosts the scanner found free; the actionbar
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
 * <p>Yarn twin of the mojmap class in {@code fabric-26.x}; keep the modules in step.</p>
 */
final class MarkerInput {

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

    static void onStartTick(MinecraftClient client) {
        if (cooldown > 0) cooldown--;
        if (counterHold > 0) counterHold--;
        if (client.player == null || client.world == null || client.currentScreen != null) return;
        if (MarkerManager.getInstance().isEmpty()) return;
        McMarkerProbe.loadDutch();
        handlePick(client);
        // "Rechtsklik plaatst" uit (G-menu, 1.2.1): vanilla zet neer wat je in je hand hebt, en een
        // los blok op de plek telt als geplaatst (MarkerScanner). Middelklik blijft werken.
        if (OverlayConfig.getInstance().isMarkerPlace()) handleUse(client);
        else holdOwned = false;
    }

    /** End of the tick: the use key gets its real state back (see the class comment). */
    static void onEndTick(MinecraftClient client) {
        if (!useSuppressed) return;
        useSuppressed = false;
        // A screen that opened during the tick released every key; leave it released.
        if (client.currentScreen == null) client.options.useKey.setPressed(true);
    }

    // -- Middle-click ------------------------------------------------------

    private static void handlePick(MinecraftClient client) {
        KeyBinding pick = client.options.pickItemKey;
        int passOn = 0;
        while (pick.wasPressed()) {
            if (!tryPick(client)) passOn++;
        }
        for (int i = 0; i < passOn; i++) KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(pick));
    }

    private static boolean tryPick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        MarkerPicker.Hit hit = aimedGhost(client, false);
        if (hit == null) return false;
        if (!player.isCreative()) {
            player.sendMessage(Text.literal(Messages.markerPickNeedsCreative()), true);
            return false;
        }
        Item item = McMarkerProbe.resolve(hit.block()).asItem();
        if (item == Items.AIR || client.interactionManager == null) return false;
        selectInHotbar(client, item);
        player.sendMessage(Text.literal(Messages.markerPicked(McMarkerProbe.displayName(hit.block()))), true);
        return true;
    }

    // -- Right-click -------------------------------------------------------

    private static void handleUse(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        KeyBinding use = client.options.useKey;
        if (counterLayer != null) {
            MarkerLayer.Counts c = counterLayer.counts();
            player.sendMessage(Text.literal(Messages.markerCounter(c.placed(), c.total())), true);
            counterLayer = null;
            counterHold = COUNTER_HOLD;
            shownLayer = null;
        }
        boolean able = player.isCreative() && !player.isUsingItem() && !player.isRiding()
                && client.interactionManager != null && !client.interactionManager.isBreakingBlock();
        MarkerPicker.Hit ghost = able ? aimedGhost(client, true) : null;
        showName(player, ghost);

        int presses = 0;
        while (use.wasPressed()) presses++;
        boolean held = use.isPressed();
        if (!held) holdOwned = false;
        if (ghost != null) {
            // A click places at once (as in vanilla); held, every USE_DELAY ticks.
            if ((presses > 0 || (held && cooldown == 0)) && place(client, ghost)) {
                cooldown = USE_DELAY;
                holdOwned = held;
            }
        } else {
            for (int i = 0; i < presses; i++) KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(use));
        }
        if (held && able && (ghost != null || holdOwned)) {
            use.setPressed(false);
            useSuppressed = true;
        }
    }

    private static boolean place(MinecraftClient client, MarkerPicker.Hit hit) {
        ClientPlayerEntity player = client.player;
        Item item = McMarkerProbe.resolve(hit.block()).asItem();
        if (item == Items.AIR) return false;
        Vec3d eye = player.getCameraPosVec(1f);
        Vec3d look = player.getRotationVec(1f);
        BlockPos spot = new BlockPos(hit.x(), hit.y(), hit.z());
        BlockState there = client.world.getBlockState(spot);
        BlockHitResult target;
        if (there.isAir()) {
            double[] p = MarkerPicker.groundHitPoint(hit, eye.x, eye.y, eye.z, look.x, look.y, look.z);
            target = new BlockHitResult(new Vec3d(p[0], p[1], p[2]), Direction.UP, spot.down(), false);
        } else if (there.isReplaceable() && there.getFluidState().isEmpty()) {
            double[] p = MarkerPicker.entryPoint(hit, eye.x, eye.y, eye.z, look.x, look.y, look.z);
            target = new BlockHitResult(new Vec3d(p[0], p[1], p[2]), Direction.UP, spot, false);
        } else {
            return false; // something else stands there now; the scanner will notice
        }
        selectInHotbar(client, item);
        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, target);
        if (result instanceof ActionResult.Success success && success.swingSource() == ActionResult.SwingSource.CLIENT) {
            player.swingHand(Hand.MAIN_HAND);
        }
        counterLayer = hit.layer();
        return true;
    }

    /** The aimed ghost's block name in the actionbar; repeated while you keep looking at it. */
    private static void showName(ClientPlayerEntity player, MarkerPicker.Hit ghost) {
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
        player.sendMessage(Text.literal(Messages.markerAim(McMarkerProbe.displayName(ghost.block()))), true);
    }

    // -- Shared ------------------------------------------------------------

    /** The ghost under the crosshair within reach, unless a real block or entity is in front of it. */
    private static MarkerPicker.Hit aimedGhost(MinecraftClient client, boolean emptyOnly) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getCameraPosVec(1f);
        Vec3d look = player.getRotationVec(1f);
        MarkerPicker.Hit hit = MarkerPicker.pick(MarkerManager.getInstance().layers(),
                eye.x, eye.y, eye.z, look.x, look.y, look.z, player.getBlockInteractionRange(), emptyOnly);
        if (hit == null) return null;
        HitResult vanilla = client.crosshairTarget;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getPos().distanceTo(eye) < hit.distance() - 1e-3) {
            return null;
        }
        return hit;
    }

    /**
     * The item into the hotbar and selected, as creative pick-block does: an existing slot with
     * it, else the selected slot when empty, else the first empty one, else the selected one.
     */
    private static void selectInHotbar(MinecraftClient client, Item item) {
        PlayerInventory inv = client.player.getInventory();
        int slot = -1;
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (inv.getStack(i).isOf(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            int selected = inv.getSelectedSlot();
            if (inv.getStack(selected).isEmpty()) {
                slot = selected;
            } else {
                for (int i = 0; i < PlayerInventory.getHotbarSize() && slot < 0; i++) {
                    if (inv.getStack(i).isEmpty()) slot = i;
                }
                if (slot < 0) slot = selected; // creative: blocks are free, overwrite the selected one
            }
            ItemStack stack = new ItemStack(item);
            inv.setStack(slot, stack);
            client.interactionManager.clickCreativeStack(stack, 36 + slot);
        }
        inv.setSelectedSlot(slot);
    }
}
