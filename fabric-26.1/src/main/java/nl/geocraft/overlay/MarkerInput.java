package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nl.geocraft.overlay.markers.MarkerManager;
import nl.geocraft.overlay.markers.MarkerPicker;

/**
 * Middle-click on a ghost marker puts that block in your hand (BOMEN.md §9.3), in creative,
 * the same way vanilla pick-block does: the item goes into a hotbar slot through the creative
 * inventory packet and that slot is selected. So the number of different markers does not
 * matter; the player never has to look for the right block.
 *
 * <p>Runs at the start of the client tick, before vanilla handles the pick key. A press that
 * does not hit a ghost (or hits a real block first) is put back, so vanilla pick-block keeps
 * working everywhere else. Mojmap twin of the yarn class in {@code fabric-1.21.11}.</p>
 */
final class MarkerInput {

    private static final int HOTBAR = 9;

    private MarkerInput() {}

    static void onStartTick(Minecraft client) {
        if (client.player == null || client.level == null || client.screen != null) return;
        if (MarkerManager.getInstance().isEmpty()) return;
        KeyMapping pick = client.options.keyPickItem;
        int passOn = 0;
        while (pick.consumeClick()) {
            if (!tryPick(client)) passOn++;
        }
        for (int i = 0; i < passOn; i++) KeyMapping.click(KeyMappingHelper.getBoundKeyOf(pick));
    }

    private static boolean tryPick(Minecraft client) {
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition(1f);
        Vec3 look = player.getViewVector(1f);
        MarkerPicker.Hit hit = MarkerPicker.pick(MarkerManager.getInstance().layers(),
                eye.x, eye.y, eye.z, look.x, look.y, look.z, player.blockInteractionRange());
        if (hit == null) return false;
        HitResult vanilla = client.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceTo(eye) < hit.distance() - 1e-3) {
            return false; // a real block or entity in front of the ghost
        }
        if (!player.isCreative()) {
            player.sendOverlayMessage(Component.literal(Messages.markerPickNeedsCreative()));
            return false;
        }
        Item item = McMarkerProbe.resolve(hit.block()).asItem();
        if (item == Items.AIR || client.gameMode == null) return false;

        Inventory inv = player.getInventory();
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
        player.sendOverlayMessage(Component.literal(Messages.markerPicked(hit.block())));
        return true;
    }
}
