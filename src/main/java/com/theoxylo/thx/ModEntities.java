package com.theoxylo.thx;

import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.common.registry.EntityRegistry;

/**
 * Entity registration. Replaces the old ModLoader.registerEntityID /
 * addEntityTracker pair. registerModEntity also wires FML's spawn packet, so
 * the client reconstructs the entity via its (World) constructor — no manual
 * getSpawnPacket needed.
 */
public final class ModEntities
{
    private static final int HELICOPTER_ID = 1; // unique within this mod
    private static final int TRACKING_RANGE = 80;
    private static final int UPDATE_FREQUENCY = 3;
    private static final boolean SEND_VELOCITY = true;

    public static void register()
    {
        EntityRegistry.registerModEntity(
            ThxEntityHelicopter.class,
            "helicopter",
            HELICOPTER_ID,
            ModThx.instance,
            TRACKING_RANGE,
            UPDATE_FREQUENCY,
            SEND_VELOCITY);
    }

    private ModEntities() {}
}
