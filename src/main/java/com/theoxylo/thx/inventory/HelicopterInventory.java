package com.theoxylo.thx.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityFurnace;

/**
 * The helicopter's onboard storage: 1 fuel + a 3x3 cargo grid + 1 ammo = 11
 * slots. Owned by the helicopter entity, which persists it in its entity NBT
 * (so contents survive world reload), burns from the fuel slot in flight, and
 * fires from the ammo slot (see the entity's updateLauncherServer).
 *
 * Slot rules: the fuel slot takes anything a furnace burns, cargo takes
 * anything, and the ammo slot takes the launcher's three munitions — arrows,
 * fire charges, and TNT ({@link #isLauncherAmmo}).
 *
 * The cargo and launcher sections are optional hardware: a craft only has them
 * if it was built with a chest and/or a dispenser (see RecipeHelicopterUpgrade).
 * A section the craft doesn't have refuses every insert here, which is what
 * stops hoppers and other automation from loading a bay that isn't fitted; the
 * container drops the same slots from shift-click routing.
 */
public class HelicopterInventory implements IInventory
{
    public static final int SIZE = 11;
    public static final int SLOT_FUEL = 0;
    public static final int SLOT_CARGO_START = 1; // 1..9 (3x3), row-major
    public static final int SLOT_CARGO_COUNT = 9;
    public static final int SLOT_AMMO = 10;

    private final ItemStack[] stacks = new ItemStack[SIZE];

    // installed sections, set from the item the craft was spawned from and persisted with it
    private boolean hasCargo;
    private boolean hasAmmo;

    public boolean hasCargo() { return hasCargo; }
    public boolean hasAmmo() { return hasAmmo; }

    public void setSections(boolean cargo, boolean ammo)
    {
        hasCargo = cargo;
        hasAmmo = ammo;
    }

    @Override
    public int getSizeInventory() { return SIZE; }

    @Override
    public ItemStack getStackInSlot(int slot)
    {
        return (slot >= 0 && slot < SIZE) ? stacks[slot] : null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount)
    {
        if (slot < 0 || slot >= SIZE || stacks[slot] == null) return null;
        ItemStack stack = stacks[slot];
        if (stack.stackSize <= amount)
        {
            stacks[slot] = null;
            markDirty();
            return stack;
        }
        ItemStack split = stack.splitStack(amount);
        if (stack.stackSize == 0) stacks[slot] = null;
        markDirty();
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot)
    {
        if (slot < 0 || slot >= SIZE || stacks[slot] == null) return null;
        ItemStack stack = stacks[slot];
        stacks[slot] = null;
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack)
    {
        if (slot < 0 || slot >= SIZE) return;
        stacks[slot] = stack;
        if (stack != null && stack.stackSize > getInventoryStackLimit())
        {
            stack.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    @Override
    public String getInventoryName() { return "container.thx.helicopter"; }

    @Override
    public boolean hasCustomInventoryName() { return false; }

    @Override
    public int getInventoryStackLimit() { return 64; }

    @Override
    public void markDirty() {}

    /** The container's canInteractWith does the real range/alive check. */
    @Override
    public boolean isUseableByPlayer(EntityPlayer player) { return true; }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack)
    {
        if (stack == null) return false;
        if (slot == SLOT_FUEL) return TileEntityFurnace.isItemFuel(stack);
        if (slot == SLOT_AMMO) return hasAmmo && isLauncherAmmo(stack);
        return hasCargo && slot >= SLOT_CARGO_START && slot < SLOT_CARGO_START + SLOT_CARGO_COUNT;
    }

    /** The three munitions the launcher fires: arrows, fire charges, and TNT. */
    public static boolean isLauncherAmmo(ItemStack stack)
    {
        if (stack == null) return false;
        Item item = stack.getItem();
        return item == Items.arrow || item == Items.fire_charge
            || item == Item.getItemFromBlock(Blocks.tnt);
    }

    /** Furnace-style persistence: a list of non-empty slots, each tagged with its index. */
    public void writeToNBT(NBTTagCompound tag)
    {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < SIZE; i++)
        {
            if (stacks[i] == null) continue;
            NBTTagCompound slotTag = new NBTTagCompound();
            slotTag.setByte("Slot", (byte) i);
            stacks[i].writeToNBT(slotTag);
            list.appendTag(slotTag);
        }
        tag.setTag("Items", list);
        tag.setBoolean("hasCargo", hasCargo);
        tag.setBoolean("hasAmmo", hasAmmo);
    }

    public void readFromNBT(NBTTagCompound tag)
    {
        hasCargo = tag.getBoolean("hasCargo");
        hasAmmo = tag.getBoolean("hasAmmo");
        for (int i = 0; i < SIZE; i++) stacks[i] = null;
        NBTTagList list = tag.getTagList("Items", 10); // 10 = TAG_Compound
        for (int i = 0; i < list.tagCount(); i++)
        {
            NBTTagCompound slotTag = list.getCompoundTagAt(i);
            int slot = slotTag.getByte("Slot") & 255;
            if (slot < SIZE) stacks[slot] = ItemStack.loadItemStackFromNBT(slotTag);
        }
    }
}
