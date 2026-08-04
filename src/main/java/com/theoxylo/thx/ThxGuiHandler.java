package com.theoxylo.thx;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.theoxylo.thx.client.gui.GuiHelicopter;
import com.theoxylo.thx.entity.ThxEntityHelicopter;
import com.theoxylo.thx.inventory.ContainerHelicopter;

import cpw.mods.fml.common.network.IGuiHandler;

/**
 * Bridges the server-side {@link ContainerHelicopter} and the client-side
 * {@link GuiHelicopter} for the helicopter loadout menu.
 *
 * Opened via {@code player.openGui(ModThx.instance, GUI_HELICOPTER, world,
 * entityId, 0, 0)}: {@code openGui}'s three int args are free-form, so we pass
 * the helicopter's entity id in {@code x} and recover it here with
 * {@code getEntityByID} (the same idiom as HelicopterInputMessage's handler).
 *
 * Dedicated-server safe: the client type {@link GuiHelicopter} is referenced
 * only inside {@link #getClientGuiElement}, which the server never calls, so the
 * JVM never classloads it there.
 */
public class ThxGuiHandler implements IGuiHandler
{
    public static final int GUI_HELICOPTER = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z)
    {
        if (id != GUI_HELICOPTER) return null;
        Entity entity = world.getEntityByID(x);
        if (!(entity instanceof ThxEntityHelicopter)) return null;
        ThxEntityHelicopter heli = (ThxEntityHelicopter) entity;
        return new ContainerHelicopter(player.inventory, heli.inventory, heli);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z)
    {
        if (id != GUI_HELICOPTER) return null;
        Entity entity = world.getEntityByID(x);
        if (!(entity instanceof ThxEntityHelicopter)) return null;
        ThxEntityHelicopter heli = (ThxEntityHelicopter) entity;
        return new GuiHelicopter(player.inventory, heli.inventory, heli);
    }
}
