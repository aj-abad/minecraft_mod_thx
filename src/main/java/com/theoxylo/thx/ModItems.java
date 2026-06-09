package com.theoxylo.thx;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.theoxylo.thx.item.ThxItemHelicopter;

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

        // Recipe (1 helicopter):
        //   I R I     I = iron block, R = redstone, G = glass block,
        //   W L G     L = leather,    W = any wood plank (OreDictionary "plankWood")
        //   W W .
        GameRegistry.addRecipe(new ShapedOreRecipe(
            new ItemStack(helicopter),
            "IRI",
            "WLG",
            "WW ",
            'I', Blocks.iron_block,
            'R', Items.redstone,
            'G', Blocks.glass,
            'L', Items.leather,
            'W', "plankWood"));
    }

    private ModItems() {}
}
