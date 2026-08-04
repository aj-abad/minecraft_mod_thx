package com.theoxylo.thx.inventory;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * A slot that defers placement validity to the backing inventory's
 * {@code isItemValidForSlot} (1.7.10's base {@link Slot#isItemValid} accepts
 * everything and never consults the inventory). Used for every helicopter slot:
 * fuel (furnace fuel only), cargo (anything, when fitted), and the launcher's
 * ammo slot (arrows / fire charges / TNT, when fitted).
 *
 * Blocks normal-click placement, drag-place, and hotkey swaps; shift-click is
 * routed separately in {@link ContainerHelicopter#transferStackInSlot}, whose
 * merge ranges must stay consistent with these rules because
 * {@code Container.mergeItemStack} ignores {@code isItemValid}.
 */
public class ValidatedSlot extends Slot
{
    private final int index;

    public ValidatedSlot(IInventory inventory, int index, int x, int y)
    {
        super(inventory, index, x, y);
        this.index = index;
    }

    @Override
    public boolean isItemValid(ItemStack stack)
    {
        return stack != null && inventory.isItemValidForSlot(index, stack);
    }
}
