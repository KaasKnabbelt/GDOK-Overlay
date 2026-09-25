package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
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
 * working everywhere else. Yarn twin of the mojmap class in {@code fabric-26.x}.</p>
 */
final class MarkerInput {

    private MarkerInput() {}

    static void onStartTick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.currentScreen != null) return;
        if (MarkerManager.getInstance().isEmpty()) return;
        KeyBinding pick = client.options.pickItemKey;
        int passOn = 0;
        while (pick.wasPressed()) {
            if (!tryPick(client)) passOn++;
        }
        for (int i = 0; i < passOn; i++) KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(pick));
    }

    private static boolean tryPick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getCameraPosVec(1f);
        Vec3d look = player.getRotationVec(1f);
        MarkerPicker.Hit hit = MarkerPicker.pick(MarkerManager.getInstance().layers(),
                eye.x, eye.y, eye.z, look.x, look.y, look.z, player.getBlockInteractionRange());
        if (hit == null) return false;
        HitResult vanilla = client.crosshairTarget;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getPos().distanceTo(eye) < hit.distance() - 1e-3) {
            return false; // a real block or entity in front of the ghost
        }
        if (!player.isCreative()) {
            player.sendMessage(Text.literal(Messages.markerPickNeedsCreative()), true);
            return false;
        }
        Block block = McMarkerProbe.resolve(hit.block());
        Item item = block.asItem();
        if (item == Items.AIR || client.interactionManager == null) return false;

        PlayerInventory inv = player.getInventory();
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
        player.sendMessage(Text.literal(Messages.markerPicked(hit.block())), true);
        return true;
    }
}
