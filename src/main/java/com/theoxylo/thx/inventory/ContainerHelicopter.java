package com.theoxylo.thx.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;

import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The helicopter loadout menu: a single fuel slot, a 3x3 cargo grid, and a
 * single ammo slot, above the player's inventory.
 *
 * Slot order = index order, which the rest of the game relies on:
 * <pre>
 *   0        fuel   (furnace fuel only)
 *   1..9     cargo  (row-major, anything)
 *   10       ammo   (the launcher: arrows, fire charges, TNT)
 *   11..37   player main inventory
 *   38..46   player hotbar
 * </pre>
 * Shift-click routing: helicopter slots empty into the player inventory; from
 * the player side, furnace fuel tries the fuel slot first and launcher ammo
 * the ammo slot, then everything falls through to the cargo grid.
 *
 * A craft built without the cargo bay or launcher keeps those sections on
 * screen but unfillable: the inventory rejects every insert into them, and
 * their merge ranges drop out of shift-click routing (1.7.10's
 * {@code mergeItemStack} ignores {@code isItemValid}, so the routing has to
 * repeat the gate). Players can therefore see what a chest or dispenser would
 * buy them before they spend one. The slots stay {@link ValidatedSlot}s rather
 * than hard-locked ones on purpose: a section that can't be filled must still
 * be emptiable, so a craft saved before the sections became optional can be
 * unloaded rather than stranding its load behind a slot that refuses to give
 * it back.
 */
public class ContainerHelicopter extends Container
{
    /** Number of helicopter slots (indices 0..HELI_SLOTS-1). */
    private static final int HELI_SLOTS = HelicopterInventory.SIZE; // 11

    /** Container indices of the player inventory block (main + hotbar) that follows the heli slots. */
    private static final int PLAYER_START = HELI_SLOTS;            // 11
    private static final int HOTBAR_START = PLAYER_START + 27;     // 38
    private static final int PLAYER_END = HOTBAR_START + 9;        // 47 (exclusive)

    // furnace-style progress ids for sendProgressBarUpdate (values <= 20000 fit the packet's short)
    private static final int PROG_BURN_REMAINING = 0;
    private static final int PROG_BURN_MAX = 1;

    /** Client-side mirrors of the craft's burn state, fed by {@link #updateProgressBar};
     *  the GUI's flame gauge reads these. */
    public int burnRemainingDisplay;
    public int burnMaxDisplay;

    private int lastBurnRemaining = -1;
    private int lastBurnMax = -1;

    private final ThxEntityHelicopter heli;

    /** Whether this craft was built with the cargo bay / launcher; gates slots and merge ranges. */
    private final boolean hasCargo;
    private final boolean hasAmmo;

    public ContainerHelicopter(InventoryPlayer playerInv, IInventory inv, ThxEntityHelicopter heli)
    {
        this.heli = heli;
        this.hasCargo = heli.inventory.hasCargo();
        this.hasAmmo = heli.inventory.hasAmmo();

        // Fuel (0), top-aligned with the ammo slot and the cargo grid's first row
        addSlotToContainer(new ValidatedSlot(inv, HelicopterInventory.SLOT_FUEL, 26, 36));

        // Cargo 3x3 (1..9), row-major; unfillable on a craft built without the bay,
        // since the inventory refuses the inserts these slots defer to
        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 3; col++)
            {
                addSlotToContainer(new ValidatedSlot(inv,
                        HelicopterInventory.SLOT_CARGO_START + row * 3 + col,
                        62 + col * 18, 36 + row * 18));
            }
        }

        // Ammo (10): the launcher's magazine; unfillable on a craft built without one
        addSlotToContainer(new ValidatedSlot(inv, HelicopterInventory.SLOT_AMMO, 134, 36));

        // Player main inventory (11..37)
        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 9; col++)
            {
                addSlotToContainer(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 94 + row * 18));
            }
        }

        // Player hotbar (38..46)
        for (int col = 0; col < 9; col++)
        {
            addSlotToContainer(new Slot(playerInv, col, 8 + col * 18, 152));
        }
    }

    /** Closes the menu if the craft is destroyed or the player moves out of reach. */
    @Override
    public boolean canInteractWith(EntityPlayer player)
    {
        return !heli.isDead && heli.getDistanceToEntity(player) <= 8.0F;
    }

    /** The window-property packet carries shorts; clamp so oversized modded fuel
     *  values (> 32767 burn ticks) degrade to a full-looking flame, not garbage. */
    private static int toShortRange(int value)
    {
        return value > Short.MAX_VALUE ? Short.MAX_VALUE : value;
    }

    @Override
    public void addCraftingToCrafters(ICrafting crafter)
    {
        super.addCraftingToCrafters(crafter);
        crafter.sendProgressBarUpdate(this, PROG_BURN_REMAINING, toShortRange(heli.getBurnRemaining()));
        crafter.sendProgressBarUpdate(this, PROG_BURN_MAX, toShortRange(heli.getBurnMax()));
    }

    @Override
    public void detectAndSendChanges()
    {
        super.detectAndSendChanges();
        int burnRemaining = toShortRange(heli.getBurnRemaining());
        int burnMax = toShortRange(heli.getBurnMax());
        if (burnRemaining == lastBurnRemaining && burnMax == lastBurnMax) return;
        for (int i = 0; i < crafters.size(); i++)
        {
            ICrafting crafter = (ICrafting) crafters.get(i);
            if (burnRemaining != lastBurnRemaining) crafter.sendProgressBarUpdate(this, PROG_BURN_REMAINING, burnRemaining);
            if (burnMax != lastBurnMax) crafter.sendProgressBarUpdate(this, PROG_BURN_MAX, burnMax);
        }
        lastBurnRemaining = burnRemaining;
        lastBurnMax = burnMax;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value)
    {
        if (id == PROG_BURN_REMAINING) burnRemainingDisplay = value;
        else if (id == PROG_BURN_MAX) burnMaxDisplay = value;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index)
    {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) return null;
        ItemStack stack = slot.getStack();
        ItemStack before = stack.copy();

        if (index < HELI_SLOTS)
        {
            // helicopter -> player, hotbar first (chest-style)
            if (!mergeItemStack(stack, PLAYER_START, PLAYER_END, true)) return null;
        }
        else
        {
            // player -> helicopter: fuel slot for anything that burns, launcher ammo to the
            // ammo slot, remainder to cargo; if the craft is full, fall back to vanilla
            // main <-> hotbar shuffling
            boolean movedAny = false;
            if (TileEntityFurnace.isItemFuel(stack))
            {
                movedAny |= mergeItemStack(stack, HelicopterInventory.SLOT_FUEL,
                        HelicopterInventory.SLOT_FUEL + 1, false);
            }
            if (hasAmmo && stack.stackSize > 0 && HelicopterInventory.isLauncherAmmo(stack))
            {
                movedAny |= mergeItemStack(stack, HelicopterInventory.SLOT_AMMO,
                        HelicopterInventory.SLOT_AMMO + 1, false);
            }
            if (hasCargo && stack.stackSize > 0)
            {
                movedAny |= mergeItemStack(stack, HelicopterInventory.SLOT_CARGO_START,
                        HelicopterInventory.SLOT_CARGO_START + HelicopterInventory.SLOT_CARGO_COUNT, false);
            }
            if (!movedAny)
            {
                if (index < HOTBAR_START)
                {
                    if (!mergeItemStack(stack, HOTBAR_START, PLAYER_END, false)) return null;
                }
                else if (!mergeItemStack(stack, PLAYER_START, HOTBAR_START, false)) return null;
            }
        }

        if (stack.stackSize == 0) slot.putStack(null);
        else slot.onSlotChanged();
        if (stack.stackSize == before.stackSize) return null;
        slot.onPickupFromSlot(player, stack);
        return before;
    }
}
