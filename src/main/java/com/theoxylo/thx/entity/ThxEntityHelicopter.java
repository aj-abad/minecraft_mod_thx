package com.theoxylo.thx.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.theoxylo.thx.util.Vector3;

/**
 * The helicopter vehicle entity, now with the ported flight model.
 *
 * Flight is server-authoritative: the pilot's client sends a control bitmask
 * (see HelicopterInputMessage); the server runs the physics here and the entity
 * tracker syncs position/rotation to all clients. Roll + throttle are synced via
 * dataWatcher (for the model roll and rotor speed). The entity itself holds no
 * client-only references, so the dedicated server is safe.
 *
 * Physics (updateThrust/updateMotion) and the input-to-rotation mapping are
 * ported from the 1.6.1 ThxEntityHelicopter. Deferred for now: view modes,
 * look-pitch, altitude lock, HUD, map, crash damage, and the rocket/missile
 * features (descoped).
 */
public class ThxEntityHelicopter extends Entity
{
    private static final float RAD_PER_DEG = 0.01745329f;
    private static final float PI = 3.14159265f;
    private static final float DT = 0.05f; // fixed per-tick timestep (20 TPS)

    // tuning (from the original)
    private static final float MAX_ACCEL = 0.2000f;
    private static final float GRAVITY = 0.2005f;
    private static final float MAX_VELOCITY = 0.26f;
    private static final float FRICTION = 0.98f;
    private static final float MAX_PITCH = 50.0f;
    private static final float PITCH_SPEED_DEG = 40f;
    private static final float MAX_ROLL = 30.0f;
    private static final float ROLL_SPEED_DEG = 40f;
    public static final float THROTTLE_MIN = -0.06f;
    public static final float THROTTLE_MAX = 0.09f;
    private static final float THROTTLE_INC = 0.004f;

    // pilot control key bits (see HelicopterInputMessage / ClientInputHandler)
    private static final int K_FWD = 1, K_BACK = 2, K_LEFT = 4, K_RIGHT = 8, K_UP = 16, K_DOWN = 32;

    // dataWatcher slots (base Entity uses 0-1)
    private static final int DW_ROLL = 22;
    private static final int DW_THROTTLE = 23;

    /** Pilot seat height relative to the entity origin; lowered so the rotor clears the rider's head. */
    private static final double SEAT_OFFSET_Y = -0.2;

    /** Latest pilot input bitmask, written by the network handler (Netty thread), read on the server tick. */
    public volatile int inputKeys;

    // flight state (read by the renderer where public)
    public float rotationRoll;
    public float throttle;
    public float rotationYawSpeed;
    public float rotationPitchSpeed;
    public float rotationRollSpeed;

    private float yawRad, pitchRad, rollRad;
    private final Vector3 fwd = new Vector3();
    private final Vector3 thrust = new Vector3();
    private final Vector3 velocity = new Vector3();

    public ThxEntityHelicopter(World world)
    {
        super(world);
        setSize(1.8f, 2.0f);
        yOffset = 0.8f;
        preventEntitySpawning = true;
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
        dataWatcher.addObject(DW_ROLL, Integer.valueOf(0));     // roll * 1000
        dataWatcher.addObject(DW_THROTTLE, Integer.valueOf(0)); // throttle * 1000
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate(); // prevPos/rotation bookkeeping, fire/water checks

        if (riddenByEntity != null)
        {
            if (!worldObj.isRemote)
            {
                if (riddenByEntity.isDead)
                {
                    riddenByEntity.mountEntity(null);
                }
                else
                {
                    applyPilotInput();
                    updateRotation();
                    updateVectors();
                    updateMotion();
                    moveEntity(motionX, motionY, motionZ);
                }
            }
            else
            {
                readSyncedState(); // roll + throttle for rendering
            }
        }
        else // vacant: simple gravity fall until it rests on the ground
        {
            if (!worldObj.isRemote)
            {
                motionY -= 0.08;
                if (motionY < -2.0) motionY = -2.0;
                moveEntity(motionX, motionY, motionZ);
                motionX *= 0.5;
                motionZ *= 0.5;
                if (onGround) { motionX = 0.0; motionZ = 0.0; }
                throttle *= 0.6f; // spin the rotor down
            }
            else
            {
                readSyncedState();
            }
        }

        if (!worldObj.isRemote)
        {
            dataWatcher.updateObject(DW_ROLL, Integer.valueOf((int) (rotationRoll * 1000f)));
            dataWatcher.updateObject(DW_THROTTLE, Integer.valueOf((int) (throttle * 1000f)));
        }
    }

    private void readSyncedState()
    {
        rotationRoll = dataWatcher.getWatchableObjectInt(DW_ROLL) / 1000f;
        throttle = dataWatcher.getWatchableObjectInt(DW_THROTTLE) / 1000f;
    }

    /** Map the pilot's input + look direction onto yaw/pitch/roll/throttle (server side). */
    private void applyPilotInput()
    {
        final int k = inputKeys;
        final boolean fwdK = (k & K_FWD) != 0, backK = (k & K_BACK) != 0;
        final boolean leftK = (k & K_LEFT) != 0, rightK = (k & K_RIGHT) != 0;
        final boolean upK = (k & K_UP) != 0, downK = (k & K_DOWN) != 0;

        if (onGround) // sluggish on the ground
        {
            if (Math.abs(rotationPitch) > 0.1f) rotationPitch *= 0.70f;
            if (Math.abs(rotationRoll) > 0.1f) rotationRoll *= 0.70f;
            motionX *= FRICTION;
            motionY = 0.0;
            motionZ *= FRICTION;
        }

        // YAW: follow the pilot's look direction (their rotation is already synced to the server)
        float deltaYawDeg = riddenByEntity.rotationYaw - rotationYaw;
        while (deltaYawDeg > 180f) deltaYawDeg -= 360f;
        while (deltaYawDeg < -180f) deltaYawDeg += 360f;
        rotationYawSpeed = deltaYawDeg * 3f;
        if (rotationYawSpeed > 90f) rotationYawSpeed = 90f;
        if (rotationYawSpeed < -90f) rotationYawSpeed = -90f;
        rotationYaw += rotationYawSpeed * DT;
        rotationYaw %= 360f;

        // PITCH: forward = nose down, back = nose up, else auto-level
        if (fwdK)
        {
            rotationPitchSpeed = PITCH_SPEED_DEG;
            rotationPitch += rotationPitchSpeed * DT;
            if (rotationPitch > MAX_PITCH) { rotationPitch = MAX_PITCH; rotationPitchSpeed = 0f; }
        }
        else if (backK)
        {
            rotationPitchSpeed = -PITCH_SPEED_DEG;
            rotationPitch += rotationPitchSpeed * DT;
            if (rotationPitch < -MAX_PITCH / 1.5f) { rotationPitch = -MAX_PITCH / 1.5f; rotationPitchSpeed = 0f; }
        }
        else
        {
            rotationPitchSpeed = -rotationPitch * 0.5f;
            rotationPitch += rotationPitchSpeed * DT;
        }

        // ROLL: left/right bank, else auto-level
        if (leftK)
        {
            rotationRollSpeed = ROLL_SPEED_DEG;
            rotationRoll += rotationRollSpeed * DT;
            if (rotationRoll > MAX_ROLL) { rotationRoll = MAX_ROLL; rotationRollSpeed = 0f; }
        }
        else if (rightK)
        {
            rotationRollSpeed = -ROLL_SPEED_DEG;
            rotationRoll += rotationRollSpeed * DT;
            if (rotationRoll < -MAX_ROLL) { rotationRoll = -MAX_ROLL; rotationRollSpeed = 0f; }
        }
        else
        {
            rotationRollSpeed = -rotationRoll * 0.6f;
            rotationRoll += rotationRollSpeed * DT;
        }

        // THROTTLE (collective): ascend/descend, else decay toward zero
        if (upK)
        {
            throttle += THROTTLE_INC;
            if (throttle > THROTTLE_MAX) throttle = THROTTLE_MAX;
        }
        else if (downK)
        {
            throttle -= THROTTLE_INC;
            if (throttle < THROTTLE_MIN) throttle = THROTTLE_MIN;
        }
        else
        {
            throttle *= 0.6f;
        }
    }

    private void updateRotation()
    {
        rotationYaw %= 360f;
        if (rotationYaw > 180f) rotationYaw -= 360f; else if (rotationYaw < -180f) rotationYaw += 360f;
        yawRad = rotationYaw * RAD_PER_DEG;

        rotationPitch %= 360f;
        if (rotationPitch > 180f) rotationPitch -= 360f; else if (rotationPitch < -180f) rotationPitch += 360f;
        pitchRad = rotationPitch * RAD_PER_DEG;

        rotationRoll %= 360f;
        if (rotationRoll > 180f) rotationRoll -= 360f; else if (rotationRoll < -180f) rotationRoll += 360f;
        rollRad = rotationRoll * RAD_PER_DEG;
    }

    private void updateVectors()
    {
        float cosYaw = MathHelper.cos(-yawRad - PI);
        float sinYaw = MathHelper.sin(-yawRad - PI);
        float cosPitch = MathHelper.cos(-pitchRad);
        float sinPitch = MathHelper.sin(-pitchRad);
        fwd.x = -sinYaw * cosPitch;
        fwd.y = sinPitch;
        fwd.z = -cosYaw * cosPitch;
    }

    private void updateThrust()
    {
        // lift falls off as the craft pitches/rolls away from level
        thrust.y = MathHelper.cos(pitchRad) * MathHelper.cos(rollRad) * MathHelper.cos(rollRad);

        // forward/back from pitch
        float accel = 1f - MathHelper.cos(pitchRad);
        if (pitchRad > 0f) accel *= -1f;
        thrust.x = -fwd.x * accel;
        thrust.z = -fwd.z * accel;
        thrust.y += -fwd.y * accel;

        // strafe from roll
        float strafe = 1f - MathHelper.cos(rollRad);
        if (rollRad > 0f) strafe *= -1f;
        thrust.x -= fwd.z * strafe;
        thrust.z += fwd.x * strafe;

        // scale by throttle, then subtract gravity
        thrust.normalize().scale(MAX_ACCEL * (1f + throttle));
        thrust.y -= GRAVITY;
    }

    private void updateMotion()
    {
        updateThrust();

        velocity.set((float) motionX, (float) motionY, (float) motionZ);
        velocity.scale(FRICTION);
        Vector3.add(velocity, thrust, velocity);

        if (velocity.lengthSquared() > MAX_VELOCITY * MAX_VELOCITY)
        {
            velocity.scale(MAX_VELOCITY / velocity.length());
        }

        motionX = velocity.x;
        motionY = velocity.y;
        motionZ = velocity.z;
    }

    /** Right-click: board if empty, dismount if you're the pilot; blocked if someone else is aboard. */
    @Override
    public boolean interactFirst(EntityPlayer player)
    {
        if (riddenByEntity != null && riddenByEntity != player)
        {
            return true;
        }
        if (!worldObj.isRemote)
        {
            player.mountEntity(player.ridingEntity == this ? null : this);
        }
        return true;
    }

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
    public boolean canBeCollidedWith() { return !isDead; }

    @Override
    public boolean canBePushed() { return false; }

    @Override
    protected boolean canTriggerWalking() { return false; }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {}

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {}
}
