package com.theoxylo.thx.entity;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.theoxylo.thx.ModItems;
import com.theoxylo.thx.util.Vector3;

/**
 * The helicopter vehicle entity, now with the ported flight model.
 *
 * Flight is server-authoritative: the pilot's client sends a control bitmask
 * (see HelicopterInputMessage); the server runs the physics here and the entity
 * tracker syncs position/rotation to all clients. Roll + rotor power are synced
 * via dataWatcher (for the model roll and rotor speed). The entity itself holds no
 * client-only references, so the dedicated server is safe.
 *
 * Physics is a GTA-style arcade flight model (replacing the 1.6.1 port):
 * pitch/roll keys apply torque integrated with angular momentum, angular drag,
 * and a weak auto-level stabilizer; the rotor spools toward the commanded
 * collective and lifts along the tilted rotor axis; per-axis aerodynamic drag
 * (not a speed cap) sets the terminal speeds; hard impacts wreck the craft.
 * Water: a too-fast splash-down also wrecks it, and submerging past the drown
 * depth kills the engine for good — the hull then floats, bobbing at that depth.
 * Yaw still chases the pilot's look direction. Deferred for now: view modes,
 * look-pitch, altitude lock, HUD, map, and the rocket/missile features
 * (descoped).
 */
public class ThxEntityHelicopter extends Entity
{
    private static final float RAD_PER_DEG = 0.01745329f;
    private static final float DT = 0.05f; // fixed per-tick timestep (20 TPS)

    // --- flight model tuning ---
    // translation: accelerations in blocks/tick^2, velocities in blocks/tick (1 b/t = 20 m/s)
    private static final float GRAVITY = 0.045f;
    private static final float DRAG_FWD = 0.956f;   // velocity kept per tick along the nose -> ~1.05 b/t terminal dive
    private static final float DRAG_LAT = 0.92f;    // sideslip bleeds faster, so banked turns carve
    private static final float DRAG_VERT = 0.964f;  // rotor disc resists vertical flow -> ~0.4 b/t max climb
    private static final float MAX_VELOCITY = 1.5f; // safety net only; the real limits come from drag
    private static final float GROUND_FRICTION = 0.85f;

    // rotor: lift in units of gravity (1.0 hovers when level)
    public static final float POWER_MAX = 1.35f;      // full collective (public: renderer keys the rotor anim off it)
    private static final float POWER_NEUTRAL = 0.95f; // hands-off idle: just under hover, so the craft slowly settles
    private static final float POWER_MIN = 0.60f;     // full down collective: brisk but survivable descent
    private static final float POWER_SPOOL = 0.04f;   // lerp toward target per tick (~1.2 s spool time constant)

    // attitude: deg, deg/s, deg/s^2; keys apply torque, integrated with momentum and drag
    private static final float PITCH_TORQUE = 130f;
    private static final float ROLL_TORQUE = 130f;
    private static final float MAX_PITCH_DOWN = 60f;
    private static final float MAX_PITCH_UP = 45f;
    private static final float MAX_ROLL = 45f;
    private static final float SOFT_LIMIT_ZONE = 0.3f; // input authority fades over the last 30% of travel
    private static final float ANGULAR_DRAG = 0.90f;   // per tick -> max rotation rate ~58 deg/s
    private static final float STAB_GAIN = 1.0f;       // weak auto-level: deg/s^2 of restoring torque per deg off level

    // crash: blocked motion (intended minus actual move) that wrecks the craft
    private static final float CRASH_SPEED = 0.5f;        // piloted: ~10 m/s into terrain
    private static final float CRASH_SPEED_VACANT = 1.2f; // bailed-out freefall: only a long drop wrecks it

    // rotor wash (client-side visual): spray ring kicked up from water below the rotor
    private static final int WASH_RANGE = 8;          // blocks of downwash reach below the craft
    private static final float WASH_MIN_POWER = 0.4f; // rotor power needed to kick up spray

    // water: a hard splash-down crashes (same speed bar as terrain, but measured on entry,
    // since water never blocks movement); deep submersion drowns the engine and the hull floats
    private static final float ENGINE_DROWN_SUB = 0.6f; // submerged hull fraction that kills the engine
    private static final float BUOY_LEVEL = 0.6f;       // floating equilibrium: fraction submerged
    private static final float BUOY_ACCEL = 0.1f;       // restoring accel per unit of depth error
    private static final float WATER_DRAG = 0.8f;       // per-tick velocity retention in water

    // pilot control key bits (see HelicopterInputMessage / ClientInputHandler)
    private static final int K_FWD = 1, K_BACK = 2, K_LEFT = 4, K_RIGHT = 8, K_UP = 16, K_DOWN = 32;

    // dataWatcher slots (base Entity uses 0-1)
    private static final int DW_ROLL = 22;
    private static final int DW_POWER = 23;
    private static final int DW_FLAGS = 24; // bit 0: engineDead

    /** Pilot seat height relative to the entity origin; lowered so the rotor clears the rider's head. */
    private static final double SEAT_OFFSET_Y = -0.2;

    /** Latest pilot input bitmask, written by the network handler (Netty thread), read on the server tick. */
    public volatile int inputKeys;

    /** Set by the client input handler when the LOCAL player is the pilot: predict locally, ignore the tracker. */
    public boolean clientControlled;

    // client interpolation toward the entity-tracker target (non-piloted / spectator view)
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;

    // flight state (read by the renderer where public)
    public float rotationRoll;
    public float prevRotationRoll; // previous-tick roll, for smooth render interpolation
    public float rotorPower;       // current rotor lift in units of gravity; drives the rotor anim
    public float rotationYawSpeed;
    public float rotationPitchSpeed; // deg/s, carries angular momentum between ticks
    public float rotationRollSpeed;  // deg/s, carries angular momentum between ticks

    /** Engine drowned by deep water: permanent; the craft is an unpowered, buoyant hulk. */
    private boolean engineDead;
    private boolean wasTouchingWater; // for detecting the tick the craft first hits water

    private float yawRad, pitchRad, rollRad;
    private final Vector3 up = new Vector3(); // rotor axis in world space
    private final Vector3 thrust = new Vector3();

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
        dataWatcher.addObject(DW_ROLL, Integer.valueOf(0));  // roll * 1000
        dataWatcher.addObject(DW_POWER, Integer.valueOf(0)); // rotorPower * 1000
        dataWatcher.addObject(DW_FLAGS, Byte.valueOf((byte) 0));
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate(); // prevPos/rotation bookkeeping, fire/water checks
        prevRotationRoll = rotationRoll; // captured at tick start, mirroring prevRotationYaw/Pitch

        if (!worldObj.isRemote)
        {
            // SERVER: authoritative simulation
            if (riddenByEntity != null && riddenByEntity.isDead) riddenByEntity.mountEntity(null);
            if (riddenByEntity != null) riddenByEntity.fallDistance = 0f; // the craft eats the impacts, not the pilot
            if (riddenByEntity != null && !engineDead) flightStep();
            else gravityFall(); // vacant, or a drowned-engine hulk (possibly still ridden)
            dataWatcher.updateObject(DW_ROLL, Integer.valueOf((int) (rotationRoll * 1000f)));
            dataWatcher.updateObject(DW_POWER, Integer.valueOf((int) (rotorPower * 1000f)));
            dataWatcher.updateObject(DW_FLAGS, Byte.valueOf((byte) (engineDead ? 1 : 0)));
        }
        else
        {
            // engine death is latched from the server flag (the predicting pilot may also have
            // detected it locally a moment earlier; it never un-dies)
            if ((dataWatcher.getWatchableObjectByte(DW_FLAGS) & 1) != 0) engineDead = true;

            if (clientControlled && riddenByEntity != null)
            {
                // CLIENT PREDICTION (local pilot): run identical physics locally for a smooth,
                // zero-lag view. The tracker's quantized updates are ignored (see
                // setPositionAndRotation2) so they don't fight the prediction.
                if (engineDead) gravityFall();
                else flightStep();
            }
            else
            {
                // CLIENT spectator (vacant or someone else's): smoothly follow the tracker, read synced extras.
                clientLerp();
                readSyncedState();
            }

            spawnRotorWash();
        }
    }

    /** EntityBoat-style smoothing of the quantized tracker updates, so non-piloted helicopters
     *  (e.g. one in freefall) don't jitter. */
    private void clientLerp()
    {
        if (lerpSteps <= 0) return;
        double nx = posX + (lerpX - posX) / lerpSteps;
        double ny = posY + (lerpY - posY) / lerpSteps;
        double nz = posZ + (lerpZ - posZ) / lerpSteps;
        float dyaw = lerpYaw - rotationYaw;
        while (dyaw > 180f) dyaw -= 360f;
        while (dyaw < -180f) dyaw += 360f;
        rotationYaw += dyaw / lerpSteps;
        rotationPitch += (lerpPitch - rotationPitch) / lerpSteps;
        lerpSteps--;
        setPosition(nx, ny, nz);
        setRotation(rotationYaw, rotationPitch);
    }

    /** One step of piloted flight: input -> attitude/rotor -> thrust -> motion -> move + crash check. */
    private void flightStep()
    {
        applyPilotInput();
        updateRotation();
        updateVectors();
        updateMotion();

        double ix = motionX, iy = motionY, iz = motionZ; // intended motion, for the impact check
        moveEntity(motionX, motionY, motionZ);
        if (!worldObj.isRemote) checkCrash(ix, iy, iz, CRASH_SPEED); // prediction never wrecks locally
        if (isDead) return;
        updateWaterState(ix * ix + iy * iy + iz * iz);
    }

    /** Unpowered physics: freefall with retained momentum (so a craft bailed at speed arcs
     *  instead of dropping straight down), or a buoyant float once in water. Covers vacant
     *  craft and drowned-engine hulks (ridden or not); also run by the pilot's client as
     *  prediction, so crash side effects stay server-only. */
    private void gravityFall()
    {
        float sub = submergedFraction();
        if (sub > 0.01f)
        {
            // in water: restoring accel toward the equilibrium depth + heavy damping = settle
            // and bob at BUOY_LEVEL; no crash check, since water never blocks movement
            motionY += BUOY_ACCEL * (sub - BUOY_LEVEL);
            motionX *= WATER_DRAG;
            motionY *= WATER_DRAG;
            motionZ *= WATER_DRAG;
            moveEntity(motionX, motionY, motionZ);
        }
        else
        {
            motionY -= 0.08;
            if (motionY < -2.0) motionY = -2.0;
            double ix = motionX, iy = motionY, iz = motionZ;
            moveEntity(motionX, motionY, motionZ);
            if (!worldObj.isRemote) checkCrash(ix, iy, iz, CRASH_SPEED_VACANT);
            if (isDead) return;
            // light air drag keeps horizontal momentum (a freshly spawned craft has none, so it just drops)
            motionX *= 0.98;
            motionZ *= 0.98;
            if (onGround) { motionX *= 0.5; motionZ *= 0.5; } // settle once it lands
        }
        rotorPower *= sub > 0.01f ? 0.92f : 0.98f; // rotor winds down; water kills it fast
        if (rotorPower < 0.01f) rotorPower = 0f;   // snap to a full stop so the rotor anim can finish
        updateWaterState(0.0); // entry speed 0: an unpowered splash-down never explodes, just floats
    }

    /** Fraction of the hull (bounding box) below the water line, EntityBoat-style: the box is
     *  sampled as five horizontal slices. */
    private float submergedFraction()
    {
        final int slices = 5;
        float sub = 0f;
        double sliceH = (boundingBox.maxY - boundingBox.minY) / slices;
        for (int i = 0; i < slices; i++)
        {
            AxisAlignedBB slice = AxisAlignedBB.getBoundingBox(
                boundingBox.minX, boundingBox.minY + sliceH * i, boundingBox.minZ,
                boundingBox.maxX, boundingBox.minY + sliceH * (i + 1), boundingBox.maxZ);
            if (worldObj.isAABBInMaterial(slice, Material.water)) sub += 1f / slices;
        }
        return sub;
    }

    /** Post-move water checks. Splashing down harder than the terrain crash bar wrecks the
     *  craft (server side, powered flight only — entrySpeedSq is 0 for unpowered falls);
     *  submerging past the drown depth kills the engine for good, after which
     *  {@link #gravityFall()} floats the hull at that depth. */
    private void updateWaterState(double entrySpeedSq)
    {
        float sub = submergedFraction();
        boolean touching = sub > 0.05f;
        if (touching && !wasTouchingWater && !worldObj.isRemote
            && entrySpeedSq > CRASH_SPEED * CRASH_SPEED)
        {
            crash();
            return;
        }
        if (!engineDead && sub >= ENGINE_DROWN_SUB) engineDead = true; // water in the engine
        wasTouchingWater = touching;
    }

    /** Wreck the craft if moveEntity blocked more motion than the threshold (a hard strike). */
    private void checkCrash(double intendedX, double intendedY, double intendedZ, float threshold)
    {
        double bx = intendedX - (posX - prevPosX);
        double by = intendedY - (posY - prevPosY);
        double bz = intendedZ - (posZ - prevPosZ);
        double blockedSq = bx * bx + by * by + bz * bz;
        if (blockedSq > threshold * threshold) crash();
    }

    private void crash()
    {
        if (riddenByEntity != null) riddenByEntity.mountEntity(null);
        worldObj.createExplosion(this, posX, posY, posZ, 1.5f, false); // hurts entities, spares terrain
        setDead();
    }

    /** Tracker update on the client. While the local pilot is predicting, ignore it entirely;
     *  otherwise store it as the interpolation target (smoothed in {@link #clientLerp()}). */
    @Override
    public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int incrementCount)
    {
        if (clientControlled) return;
        lerpX = x; lerpY = y; lerpZ = z;
        lerpYaw = yaw; lerpPitch = pitch;
        lerpSteps = incrementCount;
    }

    private void readSyncedState()
    {
        rotationRoll = dataWatcher.getWatchableObjectInt(DW_ROLL) / 1000f;
        rotorPower = dataWatcher.getWatchableObjectInt(DW_POWER) / 1000f;
    }

    /** Client-side rotor downwash: a VC-style ring of spray + drifting mist on water below.
     *  Driven entirely by replicated state (position + rotorPower), so the predicting pilot and
     *  spectators see the same thing; spawnParticle is a no-op on the dedicated server. */
    private void spawnRotorWash()
    {
        if (rotorPower < WASH_MIN_POWER) return;

        // walk down from the skids looking for a water surface within downwash range
        int bx = MathHelper.floor_double(posX);
        int topY = MathHelper.floor_double(posY);
        int bz = MathHelper.floor_double(posZ);
        double surfaceY = -1.0;
        for (int dy = 0; dy <= WASH_RANGE; dy++)
        {
            Material m = worldObj.getBlock(bx, topY - dy, bz).getMaterial();
            if (m.isLiquid())
            {
                if (m != Material.water) return; // lava: no spray
                surfaceY = topY - dy + 1.0;
                break;
            }
            if (m.isSolid()) return; // ground intercepts the downwash
        }
        double height = posY - surfaceY;
        if (surfaceY < 0.0 || height < 0.0 || height > WASH_RANGE) return; // no water, or submerged

        // closer + more rotor = denser, wider, faster spray
        float intensity = (float) (1.0 - height / WASH_RANGE) * (rotorPower / POWER_MAX);
        if (intensity < 0.05f) return;

        double ringRadius = 1.5 + 2.5 * intensity;
        int sprayCount = (int) (14f * intensity);
        for (int i = 0; i < sprayCount; i++)
        {
            double angle = rand.nextDouble() * Math.PI * 2.0;
            double r = ringRadius * (0.8 + 0.4 * rand.nextDouble());
            double outward = 0.15 + 0.3 * intensity; // deflected outward along the surface
            worldObj.spawnParticle("splash",
                posX + Math.cos(angle) * r, surfaceY + 0.1, posZ + Math.sin(angle) * r,
                Math.cos(angle) * outward, 0.1 + 0.2 * intensity, Math.sin(angle) * outward);
        }

        int mistCount = 1 + (int) (3f * intensity);
        for (int i = 0; i < mistCount; i++)
        {
            double angle = rand.nextDouble() * Math.PI * 2.0;
            double r = ringRadius * rand.nextDouble();
            worldObj.spawnParticle("cloud",
                posX + Math.cos(angle) * r, surfaceY + 0.2, posZ + Math.sin(angle) * r,
                Math.cos(angle) * 0.08, 0.02, Math.sin(angle) * 0.08);
        }
    }

    /** Map the pilot's input + look direction onto attitude torques and rotor power (server side,
     *  re-run on the pilot's client for prediction). */
    private void applyPilotInput()
    {
        final int k = inputKeys;
        final boolean fwdK = (k & K_FWD) != 0, backK = (k & K_BACK) != 0;
        final boolean leftK = (k & K_LEFT) != 0, rightK = (k & K_RIGHT) != 0;
        final boolean upK = (k & K_UP) != 0, downK = (k & K_DOWN) != 0;

        if (onGround) // skids: strong friction, attitude settles, angular momentum dies
        {
            if (Math.abs(rotationPitch) > 0.1f) rotationPitch *= 0.70f;
            if (Math.abs(rotationRoll) > 0.1f) rotationRoll *= 0.70f;
            rotationPitchSpeed *= 0.5f;
            rotationRollSpeed *= 0.5f;
            motionX *= GROUND_FRICTION;
            motionY = 0.0;
            motionZ *= GROUND_FRICTION;
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

        // PITCH: W/S apply torque (authority fading near the limit), the stabilizer pulls weakly
        // toward level, and the rate carries momentum -> the nose dips, wallows, and recovers
        float pitchTorque = -rotationPitch * STAB_GAIN;
        if (fwdK) pitchTorque += PITCH_TORQUE * softLimit(rotationPitch, MAX_PITCH_DOWN);
        else if (backK) pitchTorque -= PITCH_TORQUE * softLimit(-rotationPitch, MAX_PITCH_UP);
        rotationPitchSpeed = (rotationPitchSpeed + pitchTorque * DT) * ANGULAR_DRAG;
        rotationPitch += rotationPitchSpeed * DT;
        if (rotationPitch > MAX_PITCH_DOWN) { rotationPitch = MAX_PITCH_DOWN; if (rotationPitchSpeed > 0f) rotationPitchSpeed = 0f; }
        else if (rotationPitch < -MAX_PITCH_UP) { rotationPitch = -MAX_PITCH_UP; if (rotationPitchSpeed < 0f) rotationPitchSpeed = 0f; }

        // ROLL: same dynamics as pitch
        float rollTorque = -rotationRoll * STAB_GAIN;
        if (leftK) rollTorque += ROLL_TORQUE * softLimit(rotationRoll, MAX_ROLL);
        else if (rightK) rollTorque -= ROLL_TORQUE * softLimit(-rotationRoll, MAX_ROLL);
        rotationRollSpeed = (rotationRollSpeed + rollTorque * DT) * ANGULAR_DRAG;
        rotationRoll += rotationRollSpeed * DT;
        if (rotationRoll > MAX_ROLL) { rotationRoll = MAX_ROLL; if (rotationRollSpeed > 0f) rotationRollSpeed = 0f; }
        else if (rotationRoll < -MAX_ROLL) { rotationRoll = -MAX_ROLL; if (rotationRollSpeed < 0f) rotationRollSpeed = 0f; }

        // ROTOR (collective): spool toward the commanded power; neutral idles just under hover,
        // so a hands-off craft slowly settles instead of auto-hovering
        float powerTarget = upK ? POWER_MAX : downK ? POWER_MIN : POWER_NEUTRAL;
        rotorPower += (powerTarget - rotorPower) * POWER_SPOOL;
    }

    /** Input authority fades to zero over the last {@link #SOFT_LIMIT_ZONE} of travel toward the
     *  limit, so attitude approaches its maximum asymptotically instead of slamming a clamp. */
    private static float softLimit(float angleTowardLimit, float maxAngle)
    {
        float s = (maxAngle - angleTowardLimit) / (maxAngle * SOFT_LIMIT_ZONE);
        return s < 0f ? 0f : s > 1f ? 1f : s;
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

    /** Rotor axis (craft up) in world space from yaw/pitch/roll. */
    private void updateVectors()
    {
        float cosYaw = MathHelper.cos(yawRad), sinYaw = MathHelper.sin(yawRad);
        float cosPitch = MathHelper.cos(pitchRad), sinPitch = MathHelper.sin(pitchRad);
        float cosRoll = MathHelper.cos(rollRad), sinRoll = MathHelper.sin(rollRad);
        // world up, pitched about the right wing (nose-down tilts it toward the nose),
        // then rolled about the nose (positive roll tilts it to port)
        up.x = -sinYaw * sinPitch * cosRoll + cosYaw * sinRoll;
        up.y = cosPitch * cosRoll;
        up.z = cosYaw * sinPitch * cosRoll + sinYaw * sinRoll;
    }

    private void updateThrust()
    {
        // lift along the tilted rotor axis (power 1.0 exactly counters gravity when level), minus
        // world gravity: nosing down trades lift for forward drive and starts a sink
        float lift = GRAVITY * rotorPower;
        thrust.x = up.x * lift;
        thrust.y = up.y * lift - GRAVITY;
        thrust.z = up.z * lift;
    }

    private void updateMotion()
    {
        updateThrust();

        // per-axis drag in the heading frame: slippery along the nose, draggier sideways (sideslip
        // bleeds off, so banked turns carve instead of drifting), draggiest through the rotor disc;
        // terminal speeds fall out of drag vs thrust: ~1.05 b/t dive, ~0.85 level cruise, ~0.4 climb
        float fwdX = -MathHelper.sin(yawRad), fwdZ = MathHelper.cos(yawRad);
        double vFwd = (motionX * fwdX + motionZ * fwdZ) * DRAG_FWD;
        double vLat = (motionX * fwdZ - motionZ * fwdX) * DRAG_LAT;
        if (vFwd < 0.0) vFwd *= 0.97; // tail-first is draggier: backward flight tops out lower
        motionX = vFwd * fwdX + vLat * fwdZ + thrust.x;
        motionZ = vFwd * fwdZ - vLat * fwdX + thrust.z;
        motionY = motionY * DRAG_VERT + thrust.y;

        double speedSq = motionX * motionX + motionY * motionY + motionZ * motionZ;
        if (speedSq > MAX_VELOCITY * MAX_VELOCITY)
        {
            double scale = MAX_VELOCITY / Math.sqrt(speedSq);
            motionX *= scale;
            motionY *= scale;
            motionZ *= scale;
        }
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

    /** Let a player punch a helicopter to remove it; outside creative mode it drops back to an item. */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (worldObj.isRemote || isDead) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof EntityPlayer && attacker != riddenByEntity)
        {
            if (!((EntityPlayer) attacker).capabilities.isCreativeMode)
            {
                entityDropItem(new ItemStack(ModItems.helicopter), 0.5f);
            }
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
    protected void readEntityFromNBT(NBTTagCompound tag)
    {
        engineDead = tag.getBoolean("engineDead");
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag)
    {
        tag.setBoolean("engineDead", engineDead);
    }
}
