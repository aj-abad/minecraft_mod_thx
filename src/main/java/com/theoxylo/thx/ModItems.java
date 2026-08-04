package com.theoxylo.thx;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraftforge.oredict.RecipeSorter;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.theoxylo.thx.item.ThxItemHelicopter;
import com.theoxylo.thx.recipe.RecipeHelicopterUpgrade;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Item registration + crafting recipes. Replaces the old ModLoader.addName /
 * ModLoader.addRecipe calls. Called from {@link proxy.CommonProxy#preInit}.
 */
public final class ModItems
{
    public static Item helicopter;

    public static void register()
    {
        helicopter = new ThxItemHelicopter();
        GameRegistry.registerItem(helicopter, "helicopter");

        // Base craft (1 helicopter, no cargo bay or ammo rack):
        //   I R I     I = iron block ("blockIron"), R = redstone torch,
        //   F B .     F = furnace,                  B = boat
        //
        // The top row is the rotor: two blades either side of the torch standing in
        // for the mast. Beneath it the engine sits alongside the boat that donates
        // the hull and the seat. Three iron blocks put the craft just under an anvil
        // (27 ingots vs 31), which is where a vehicle this capable belongs.
        GameRegistry.addRecipe(new ShapedOreRecipe(
            ThxItemHelicopter.create(false, false),
            "IRI",
            "FB ",
            'I', "blockIron",
            'R', Blocks.redstone_torch,
            'F', Blocks.furnace,
            'B', Items.boat));

        // Cargo bay and ammo rack are bolted on afterwards with a chest and/or a
        // dispenser. Registered as a custom IRecipe because the installed sections
        // live in stack NBT, which shapeless matching can't see — see the class doc.
        RecipeSorter.register(Reference.MODID + ":helicopterUpgrade", RecipeHelicopterUpgrade.class,
                RecipeSorter.Category.SHAPELESS, "after:minecraft:shapeless");
        GameRegistry.addRecipe(new RecipeHelicopterUpgrade());
    }

    private ModItems() {}
}
