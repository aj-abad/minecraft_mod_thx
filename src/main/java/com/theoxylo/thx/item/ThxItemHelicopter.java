package com.theoxylo.thx.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.theoxylo.thx.ModThx;
import com.theoxylo.thx.Reference;
import com.theoxylo.thx.entity.ThxEntityHelicopter;

/**
 * The craftable helicopter item. Ported from the 1.6.1 ThxItemHelicopter:
 *  - no numeric id constructor (1.7 abolished item ids)
 *  - {@code setTextureName} (was {@code func_111206_d}) points at
 *    assets/thx/textures/items/helicopter_icon.png
 *  - the unlocalized name resolves via assets/thx/lang/en_US.lang
 */
public class ThxItemHelicopter extends Item
{
    private static final float RAD_PER_DEG = 0.01745329f;
    private static final float PI = 3.14159265f;

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
        world.playSoundAtEntity(player, "random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));

        // spawn ~3 blocks ahead of where the player is looking
        float yawRad = player.rotationYaw * RAD_PER_DEG;
        float pitchRad = player.rotationPitch * RAD_PER_DEG;
        float cosYaw = MathHelper.cos(-yawRad - PI);
        float sinYaw = MathHelper.sin(-yawRad - PI);
        float horiz  = -MathHelper.cos(-pitchRad);
        double spawnX = player.posX + sinYaw * horiz * 3.0;
        double spawnY = player.posY + 1.0;
        double spawnZ = player.posZ + cosYaw * horiz * 3.0;
        float yaw = (player.rotationYaw - 45f) % 360f;

        if (!world.isRemote)
        {
            ThxEntityHelicopter heli = new ThxEntityHelicopter(world, spawnX, spawnY, spawnZ, yaw);
            world.spawnEntityInWorld(heli);
            ModThx.log.info("spawned helicopter for " + player.getCommandSenderName());
        }

        if (!player.capabilities.isCreativeMode)
        {
            itemstack.stackSize--;
        }
        return itemstack;
    }
}
