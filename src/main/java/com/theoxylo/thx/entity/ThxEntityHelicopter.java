package com.theoxylo.thx.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/**
 * The helicopter vehicle entity. Phase 3 scope: spawns, falls to the ground,
 * renders, and is rideable/unrideable. NO flight controls and NO custom
 * networking yet — it just drops under gravity and rests where it lands; the
 * vanilla/FML entity tracker syncs position + riding state.
 *
 * Deliberately a single class (the old ThxEntity base existed to share code
 * with the now-descoped rocket/missile entities). Re-introduce a base later if
 * needed.
 */
public class ThxEntityHelicopter extends Entity
{
    /** Read by the renderer; the model supports roll, but with no physics it stays 0. */
    public float rotationRoll;

    /** Pilot seat height relative to the entity origin; lowered so the rotor clears the rider's head. */
    private static final double SEAT_OFFSET_Y = -0.2;

    public ThxEntityHelicopter(World world)
    {
        super(world);
        setSize(1.8f, 2.0f);
        yOffset = 0.8f;                 // sit above ground on spawn
        preventEntitySpawning = true;   // don't let mobs spawn on top of us
    }

    public ThxEntityHelicopter(World world, double x, double y, double z, float yaw)
    {
        this(world);
        setPositionAndRotation(x, y + yOffset, z, yaw, 0f);
        motionX = motionY = motionZ = 0.0;
    }

    @Override
    protected void entityInit()
    {
        // No synced flight state yet (no dataWatcher entries needed for Phase 3).
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate(); // prevPos/rotation bookkeeping, fire/water checks, etc.

        // Simple gravity so it drops and rests on the ground (NOT flight).
        motionY -= 0.08;
        if (motionY < -2.0) motionY = -2.0; // terminal velocity, avoid tunneling on long drops

        moveEntity(motionX, motionY, motionZ); // collision-aware; zeroes motionY + sets onGround on landing

        // Damp any horizontal drift; settle in place once landed.
        motionX *= 0.5;
        motionZ *= 0.5;
        if (onGround)
        {
            motionX = 0.0;
            motionZ = 0.0;
        }
    }

    /** Right-click: board if empty, dismount if you're the pilot; blocked if someone else is aboard. */
    @Override
    public boolean interactFirst(EntityPlayer player)
    {
        if (riddenByEntity != null && riddenByEntity != player)
        {
            return true; // occupied by another player
        }
        if (!worldObj.isRemote)
        {
            // mountEntity(this) boards; mountEntity(null) dismounts.
            player.mountEntity(player.ridingEntity == this ? null : this);
        }
        return true;
    }

    /** Seat the pilot in the cockpit. */
    @Override
    public void updateRiderPosition()
    {
        if (riddenByEntity != null)
        {
            riddenByEntity.setPosition(posX, posY + SEAT_OFFSET_Y + riddenByEntity.getYOffset(), posZ);
        }
    }

    /** Let a player punch a parked helicopter to remove it (debug convenience). */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (worldObj.isRemote || isDead) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof EntityPlayer && attacker != riddenByEntity)
        {
            setDead();
            return true;
        }
        return false;
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return !isDead; // so it can be right-clicked / hit
    }

    @Override
    public boolean canBePushed()
    {
        return false; // stationary platform until flight physics arrive
    }

    @Override
    protected boolean canTriggerWalking()
    {
        return false;
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag)
    {
        // position/rotation handled by Entity base; nothing custom yet
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag)
    {
        // nothing custom yet
    }
}
