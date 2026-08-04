package com.theoxylo.thx.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.theoxylo.thx.ModItems;
import com.theoxylo.thx.ModThx;
import com.theoxylo.thx.Reference;
import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The craftable helicopter item. Ported from the 1.6.1 ThxItemHelicopter:
 *  - no numeric id constructor (1.7 abolished item ids)
 *  - {@code setTextureName} (was {@code func_111206_d}) points at
 *    assets/thx/textures/items/helicopter_icon.png
 *  - the unlocalized name resolves via assets/thx/lang/en_US.lang
 *
 * A helicopter is an object, not a commodity: it does not stack (matching every
 * vanilla vehicle), and it carries the loadout sections it was built with in its
 * stack NBT. The base craft has neither section; a chest and/or a dispenser
 * added on the crafting grid installs them (see RecipeHelicopterUpgrade). The
 * flags ride the item into the world on spawn and back out again whenever the
 * craft drops, so an outfitted helicopter stays outfitted.
 *
 * Only outfitted stacks carry a tag at all, so a base helicopter stays NBT-free.
 */
public class ThxItemHelicopter extends Item
{
    /** How far the placement trace reaches, ItemBoat's figure. */
    private static final double REACH = 5.0;

    /** Stack-NBT keys for the installed sections; prefixed so nothing else on the stack collides. */
    private static final String TAG_CARGO = "thxCargo";
    private static final String TAG_AMMO = "thxAmmo";

    public ThxItemHelicopter()
    {
        setUnlocalizedName("helicopter");                       // -> item.helicopter.name
        setTextureName(Reference.MODID + ":helicopter_icon");   // -> thx:textures/items/helicopter_icon.png
        setMaxStackSize(1);
        setMaxDamage(0);
        setCreativeTab(CreativeTabs.tabTransport);
    }

    /** Whether this stack was built with the 3x3 cargo bay. */
    public static boolean hasCargo(ItemStack stack)
    {
        return stack != null && stack.hasTagCompound() && stack.getTagCompound().getBoolean(TAG_CARGO);
    }

    /** Whether this stack was built with the ammo rack. */
    public static boolean hasAmmo(ItemStack stack)
    {
        return stack != null && stack.hasTagCompound() && stack.getTagCompound().getBoolean(TAG_AMMO);
    }

    /** A helicopter stack with the given sections installed; tagless when it has neither. */
    public static ItemStack create(boolean cargo, boolean ammo)
    {
        ItemStack stack = new ItemStack(ModItems.helicopter);
        if (cargo || ammo)
        {
            NBTTagCompound tag = new NBTTagCompound();
            if (cargo) tag.setBoolean(TAG_CARGO, true);
            if (ammo) tag.setBoolean(TAG_AMMO, true);
            stack.setTagCompound(tag);
        }
        return stack;
    }

    /** Creative offers the fully outfitted craft only: the leaner builds are a survival
     *  progression, and nobody reaches for a stripped airframe when the full one is free. */
    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("unchecked")
    public void getSubItems(Item item, CreativeTabs tab, @SuppressWarnings("rawtypes") List list)
    {
        list.add(create(true, true));
    }

    /** Missing hardware, listed the way vanilla flags a drawback:
     *  {@link EnumChatFormatting#RED}, the colour {@code ItemStack.getTooltip} gives a
     *  negative attribute modifier. A fully-outfitted craft lists nothing, so the tooltip
     *  calls out only what a build lacks. */
    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("unchecked")
    public void addInformation(ItemStack stack, EntityPlayer player,
                               @SuppressWarnings("rawtypes") List lines, boolean advanced)
    {
        if (!hasCargo(stack)) lines.add(EnumChatFormatting.RED + StatCollector.translateToLocal("item.helicopter.no_cargo"));
        if (!hasAmmo(stack)) lines.add(EnumChatFormatting.RED + StatCollector.translateToLocal("item.helicopter.no_ammo"));
    }

    /**
     * Right-click: set a helicopter down on the block the player is looking at, EntityBoat-style
     * — no sound, and it stands on the ground rather than being tossed out ahead of the player to
     * drop the last of the way. The craft has to fit where it lands, so a spot with terrain in the
     * way (or nothing in reach to stand it on) simply doesn't place.
     */
    @Override
    public ItemStack onItemRightClick(ItemStack itemstack, World world, EntityPlayer player)
    {
        MovingObjectPosition hit = rayTraceReach(world, player);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return itemstack;

        int bx = hit.blockX, by = hit.blockY, bz = hit.blockZ;
        if (world.getBlock(bx, by, bz) == Blocks.snow_layer) by--; // rest on the ground under it, not on it

        // centred on the block, skids exactly on its top face
        double spawnX = bx + 0.5, spawnY = by + 1.0, spawnZ = bz + 0.5;
        ThxEntityHelicopter heli = new ThxEntityHelicopter(world, spawnX, spawnY, spawnZ,
                player.rotationYaw, hasCargo(itemstack), hasAmmo(itemstack));

        // clearance on the square hull box: parked, the craft's own box spans the whole airframe
        // out to the tail, which would refuse to place anywhere near a wall
        double half = heli.width / 2.0;
        AxisAlignedBB clearance = AxisAlignedBB.getBoundingBox(
                spawnX - half, spawnY, spawnZ - half,
                spawnX + half, spawnY + heli.height, spawnZ + half).expand(-0.1, -0.1, -0.1);
        if (!world.getCollidingBoundingBoxes(heli, clearance).isEmpty()) return itemstack;

        if (!world.isRemote)
        {
            world.spawnEntityInWorld(heli);
            ModThx.log.info("spawned helicopter for " + player.getCommandSenderName());
        }

        if (!player.capabilities.isCreativeMode)
        {
            itemstack.stackSize--;
        }
        return itemstack;
    }

    /** The block the player is looking at within placement reach, liquids included so a craft
     *  can be set down on water (it floats). ItemBoat's trace, verbatim in effect. */
    private static MovingObjectPosition rayTraceReach(World world, EntityPlayer player)
    {
        double eyeY = player.posY + 1.62 - player.yOffset;
        Vec3 eye = Vec3.createVectorHelper(player.posX, eyeY, player.posZ);
        Vec3 look = player.getLook(1.0f);
        Vec3 end = eye.addVector(look.xCoord * REACH, look.yCoord * REACH, look.zCoord * REACH);
        return world.rayTraceBlocks(eye, end, true);
    }
}
