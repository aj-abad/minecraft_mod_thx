package com.theoxylo.thx.proxy;

import net.minecraft.world.World;

import com.theoxylo.thx.ModEntities;
import com.theoxylo.thx.ModItems;
import com.theoxylo.thx.ModThx;
import com.theoxylo.thx.ThxGuiHandler;
import com.theoxylo.thx.network.ThxNetwork;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;

/**
 * Server-safe proxy (also the dedicated-server proxy).
 *
 * IMPORTANT: nothing in this class — or anything it transitively loads — may
 * reference client-only types (net.minecraft.client.*, Minecraft, Render*,
 * org.lwjgl.input.Keyboard, etc.). Those belong in {@link ClientProxy}.
 */
public class CommonProxy
{
    public void preInit(FMLPreInitializationEvent event)
    {
        // Phase 2: helicopter item + crafting recipe (common to both sides).
        ModItems.register();
        // Phase 3: helicopter entity (class + tracker; common to both sides).
        ModEntities.register();
        // A parked helicopter's bounding box spans its whole airframe, reaching further than
        // vanilla assumes any entity can (see ThxEntityHelicopter#setPosition). Without this,
        // an AABB query run from a neighbouring chunk would skip the craft, and with it the
        // colliders that stop players walking through the tail.
        World.MAX_ENTITY_RADIUS = Math.max(World.MAX_ENTITY_RADIUS, 4.0);
        // Flight: network channel for pilot input (client -> server).
        ThxNetwork.init();
        // Loadout menu (fuel/cargo/ammo): GUI handler, common to both sides.
        NetworkRegistry.INSTANCE.registerGuiHandler(ModThx.instance, new ThxGuiHandler());
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    /** No-op on the server; overridden on the client (Phase 4). */
    public void registerRenderers() {}

    /** No-op on the server; overridden on the client (Phase 6). */
    public void registerKeyBindings() {}
}
