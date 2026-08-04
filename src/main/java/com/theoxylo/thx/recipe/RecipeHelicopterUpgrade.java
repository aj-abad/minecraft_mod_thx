package com.theoxylo.thx.recipe;

import net.minecraft.init.Blocks;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

import com.theoxylo.thx.ModItems;
import com.theoxylo.thx.item.ThxItemHelicopter;

/**
 * Bolts optional hardware onto a helicopter: a chest installs the 3x3 cargo
 * bay, a dispenser installs the ammo rack, and either may be added on its own,
 * both at once, or one after the other on a craft that already has the other.
 *
 * This is a hand-written {@link IRecipe} rather than a set of shapeless recipes
 * because 1.7.10's {@code ShapelessRecipes.matches} compares item and damage
 * only — it cannot see stack NBT, which is exactly where the installed sections
 * live. A shapeless "helicopter + chest" would therefore happily consume an
 * already-outfitted craft and hand back one with its ammo rack erased. Matching
 * here instead lets the flags be read, OR-ed into the result, and no-ops
 * refused, so a chest is never silently eaten by a craft that already has a bay.
 *
 * Shapeless by nature: the parts may sit anywhere on the grid, and anything
 * else present at all disqualifies the recipe.
 */
public class RecipeHelicopterUpgrade implements IRecipe
{
    private static final Item CHEST = Item.getItemFromBlock(Blocks.chest);
    private static final Item DISPENSER = Item.getItemFromBlock(Blocks.dispenser);

    private static final int NO_MATCH = -1;
    private static final int FLAG_CARGO = 1;
    private static final int FLAG_AMMO = 2;

    @Override
    public boolean matches(InventoryCrafting grid, World world)
    {
        return resolve(grid) != NO_MATCH;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting grid)
    {
        int flags = resolve(grid);
        if (flags == NO_MATCH) return null;
        return ThxItemHelicopter.create((flags & FLAG_CARGO) != 0, (flags & FLAG_AMMO) != 0);
    }

    /** The most this ever consumes: one craft plus both parts. */
    @Override
    public int getRecipeSize() { return 3; }

    /** Recipe-listing mods show the fully outfitted craft as the representative output. */
    @Override
    public ItemStack getRecipeOutput() { return ThxItemHelicopter.create(true, true); }

    /**
     * The sections the grid's contents would produce, or {@link #NO_MATCH} if this
     * isn't an upgrade at all. Returns flags rather than a stack so the hot path
     * ({@code matches}, run over every recipe on every grid change) allocates nothing.
     */
    private static int resolve(InventoryCrafting grid)
    {
        ItemStack heli = null;
        boolean chest = false;
        boolean dispenser = false;

        for (int i = 0; i < grid.getSizeInventory(); i++)
        {
            ItemStack stack = grid.getStackInSlot(i);
            if (stack == null) continue;
            Item item = stack.getItem();

            if (item == ModItems.helicopter)
            {
                if (heli != null) return NO_MATCH; // one craft at a time
                heli = stack;
            }
            else if (item == CHEST)
            {
                if (chest) return NO_MATCH; // a second bay has nowhere to go
                chest = true;
            }
            else if (item == DISPENSER)
            {
                if (dispenser) return NO_MATCH;
                dispenser = true;
            }
            else
            {
                return NO_MATCH; // stray ingredient: not this recipe
            }
        }

        if (heli == null || (!chest && !dispenser)) return NO_MATCH;

        boolean cargo = ThxItemHelicopter.hasCargo(heli);
        boolean ammo = ThxItemHelicopter.hasAmmo(heli);

        // every part offered has to install something, or the craft would eat it for nothing
        if (chest && cargo) return NO_MATCH;
        if (dispenser && ammo) return NO_MATCH;

        return ((cargo || chest) ? FLAG_CARGO : 0) | ((ammo || dispenser) ? FLAG_AMMO : 0);
    }
}
