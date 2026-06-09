package com.theoxylo.thx.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.theoxylo.thx.ModThx;
import com.theoxylo.thx.Reference;

/**
 * The craftable helicopter item. Ported from the 1.6.1 ThxItemHelicopter:
 *  - no numeric id constructor (1.7 abolished item ids)
 *  - {@code setTextureName} (was {@code func_111206_d}) points at
 *    assets/thx/textures/items/helicopter_icon.png
 *  - the unlocalized name resolves via assets/thx/lang/en_US.lang
 */
public class ThxItemHelicopter extends Item
{
    public ThxItemHelicopter()
    {
        setUnlocalizedName("helicopter");                       // -> item.helicopter.name
        setTextureName(Reference.MODID + ":helicopter_icon");   // -> thx:textures/items/helicopter_icon.png
        setMaxStackSize(16);
        setMaxDamage(0);
        setCreativeTab(CreativeTabs.tabTransport);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack itemstack, World world, EntityPlayer player)
    {
        // TODO Phase 3: spawn a ThxEntityHelicopter here. The 1.6.1 version played
        // "random.bow", computed a spawn position 3 blocks ahead of the player from
        // yaw/pitch, set owner = player, and called world.spawnEntityInWorld(...).
        // Left disabled (and the stack intentionally not decremented) until the
        // entity is ported, so the item doesn't consume itself with no effect.
        if (ModThx.log != null)
        {
            ModThx.log.info("helicopter item used by " + player.getCommandSenderName()
                + " (spawn pending Phase 3)");
        }
        return itemstack;
    }
}
