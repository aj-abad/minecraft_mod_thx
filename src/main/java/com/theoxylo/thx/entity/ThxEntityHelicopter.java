package com.theoxylo.thx.entity;

import java.util.List;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntitySmallFireball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.theoxylo.thx.ModThx;
import com.theoxylo.thx.ThxGuiHandler;
import com.theoxylo.thx.inventory.HelicopterInventory;
import com.theoxylo.thx.item.ThxItemHelicopter;
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
 * collective — capped by the rotor RPM it has wound up to, so a cold start
 * waits out the grade's spool time — and lifts along the tilted rotor axis;
 * per-axis aerodynamic drag
 * (not a speed cap) sets the terminal speeds; impacts short of wrecking scrub
 * speed off the craft in proportion to the strike, and past that bar it is
 * wrecked (see {@link #handleImpact}) — a piloted impact is a fuel-scaled
 * fireball, a vacant one a harmless break-up into drops (see {@link #crash()}).
 * That bar widens with the fuel grade, so a craft that cruises half again as
 * fast is not wrecked by the speeds it flies at.
 * Water: the hull is buoyant, so the surface is a landing site — set down on a
 * lake, sit there under the rotor, lift off again (see {@link #floatOnWater}).
 * It stays dangerous at the extremes: arriving faster than a ditching wrecks the
 * craft, and a hull left buried floods its engine for good, leaving a hulk that
 * floats but never flies again.
 * Yaw still chases the pilot's look direction.
 *
 * The collision box tracks the model rather than boxing it loosely: its floor
 * follows the airframe's lowest point as the craft tilts, so a nose-up flare
 * swings the tail boom down without sinking it through the ground.
 *
 * Parked: with nobody aboard the craft turns solid along its own outline — a
 * cabin and a tail boom ({@link ThxEntityHelicopterPart}) rather than one cube —
 * and bleeds its attitude back to level. Players walk it around by leaning on
 * it: a shove on the cabin slides it, a shove on the boom swings the nose
 * (see {@link #parkedStep()}).
 *
 * Fuel: the engine burns furnace fuel from the loadout inventory's fuel slot
 * (see {@link #updateEngineServer}); the fuel's grade scales the whole flight
 * envelope ({@link FuelTier}), and a tank that runs dry in flight gives a short
 * sputtering grace before the engine cuts out — recoverable by landing and
 * refueling, unlike drowning.
 *
 * Launcher: a craft built with one (the dispenser upgrade) fires its ammo slot
 * on the pilot's right-click — arrows full-auto, fire charges at half rate,
 * TNT as gravity bombs (see {@link #updateLauncherServer}). Deferred for now:
 * view modes, look-pitch, altitude lock, HUD, and map.
 */
public class ThxEntityHelicopter extends Entity
{
    private static final float RAD_PER_DEG = 0.01745329f;
    private static final float DT = 0.05f; // fixed per-tick timestep (20 TPS)

    // --- flight model tuning ---
    // translation: accelerations in blocks/tick^2, velocities in blocks/tick (1 b/t = 20 m/s)
    private static final float GRAVITY = 0.045f;
    // drag is shared by every fuel grade on purpose: it sets how fast the craft sheds momentum,
    // and better fuel should stop as crisply as wood, not coast further (see FuelTier). Grades
    // scale horizontal drive, climb power and the velocity safety net instead
    private static final float DRAG_FWD = 0.956f;   // velocity kept per tick along the nose -> ~1.05 b/t terminal dive at wood tier
    private static final float DRAG_LAT = 0.92f;    // sideslip bleeds faster, so banked turns carve
    private static final float DRAG_VERT = 0.964f;  // rotor disc resists vertical flow -> ~0.4 b/t max climb
    private static final float GROUND_FRICTION = 0.85f;

    // rotor: lift in units of gravity (1.0 hovers when level)
    public static final float POWER_MAX = FuelTier.WOOD.powerMax; // wood-tier full collective, kept as the rotor-wash intensity reference
    private static final float POWER_NEUTRAL = 0.95f; // hands-off idle: just under hover, so the craft slowly settles
    private static final float POWER_MIN = 0.60f;     // full down collective: brisk but survivable descent
    private static final float POWER_SPOOL = 0.04f;   // lerp toward target per tick (~1.2 s spool time constant)
    private static final float ROTOR_COAST_MULT = 1.6f; // coasting down takes this much longer than the grade's wind-up
    private static final float TWO_PI = 6.28318531f;
    // blade advance per tick at full RPM, radians; mirrors ThxModelHelicopterBase.MAX_ROTOR_SPEED
    // (rad/s), which can't be referenced from here -- it's a client-only class
    private static final float ROTOR_RAD_PER_TICK = 18f * 0.70f * DT;

    // attitude: deg, deg/s, deg/s^2; keys apply torque, integrated with momentum and drag
    private static final float PITCH_TORQUE = 130f;
    private static final float ROLL_TORQUE = 130f;
    private static final float MAX_PITCH_DOWN = 60f;
    private static final float MAX_PITCH_UP = 45f;
    private static final float MAX_ROLL = 45f;
    private static final float SOFT_LIMIT_ZONE = 0.3f; // input authority fades over the last 30% of travel
    private static final float ANGULAR_DRAG = 0.90f;   // per tick -> max rotation rate ~58 deg/s
    private static final float STAB_GAIN = 1.0f;       // weak auto-level: deg/s^2 of restoring torque per deg off level

    // crash: blocked motion (intended minus actual move) that wrecks the craft. The piloted bar is
    // the wood-grade one; better fuel widens it by its impactMult, since a grade that cruises half
    // again as fast would otherwise wreck itself on the ordinary knocks of flying at its own speed
    private static final float CRASH_SPEED = 0.5f;        // piloted, wood grade: ~10 m/s into terrain
    private static final float CRASH_SPEED_VACANT = 1.2f; // bailed-out freefall: only a long drop wrecks it;
                                                          // unscaled, since an engine-out drop falls at gravity
                                                          // whatever is in the tank

    // survivable impacts: anything that struck hard enough to be felt but not hard enough to wreck
    // the craft scrubs speed off it. Vanilla moveEntity already zeroes the blocked axis, so this is
    // about what is left — the along-the-wall speed of a scrape, the forward run of a hard set-down
    private static final float IMPACT_DEADBAND = 0.25f; // fraction of the crash bar below which contact is free
    private static final float IMPACT_BLEED = 0.7f;     // velocity scrubbed by a strike just short of wrecking

    // bailing out: a pilot who steps out in mid-air leaves along the craft's flight path rather
    // than dropping straight down out of a moving cabin (see releaseRider). The cap keeps a
    // terminal-velocity dive from turning the pilot into a projectile
    private static final double BAILOUT_MOMENTUM = 0.6;  // fraction of the craft's velocity handed over
    private static final double BAILOUT_MAX_SPEED = 0.8; // ceiling on the departing speed, blocks/tick
    private static final double BAILOUT_MIN_SPEED = 0.1; // under this it's a step off, not a leap

    // crash pyrotechnics (piloted impacts only; a vacant craft breaks up into drops instead)
    private static final float CRASH_STRENGTH_DRY = 1.5f; // empty tank: a small entity-only pop
    private static final int CRASH_FIRES_MAX = 24;        // cap on extra crash-site fires

    // rotor wash (client-side visual): spray ring kicked up from water below the rotor
    private static final int WASH_RANGE = 8;          // blocks of downwash reach below the craft
    private static final float WASH_MIN_POWER = 0.4f; // rotor power needed to kick up spray

    // water: the hull floats on it — there is a boat in the recipe — riding high enough to keep
    // the engine dry, so the craft sets down on the surface, sits there and lifts off again.
    // Water still bites at the extremes: a hard splash-down wrecks it (measured on entry, since
    // water never blocks movement — but water cushions, so the bar is well above the terrain
    // one), and a craft that stays buried drowns its engine for good.
    //
    // The buoyancy is stiff on purpose. A hull that merely drifted to its float level would let
    // an ordinary approach — a descent of the craft's own climb rate — plunge the airframe under
    // on the way to settling; this arrests a landing inside the top half of the hull, which is
    // also what stops the engine flooding on touchdown
    private static final float ENGINE_DROWN_SUB = 0.8f;  // submerged hull fraction that floods the engine
    private static final int DROWN_TICKS = 20;           // held that deep for a second before it does: a
                                                         // splash that bobs back up is a landing, not a sinking
    private static final float BUOY_LEVEL = 0.25f;       // floating equilibrium: fraction submerged
    private static final float BUOY_ACCEL = 0.4f;        // restoring accel per unit of depth error
    private static final float WATER_DRAG = 0.3f;        // per-tick velocity retention, hull fully submerged:
                                                         // water eats an arrival rather than storing it, or a
                                                         // craft with the rotor carrying most of its weight
                                                         // would be sprung back out on every touchdown
    private static final float WATER_CRASH_SPEED = 0.9f; // wood grade, ~18 m/s: a ditching is survivable, a
                                                         // dive is not; widened per grade like the terrain bar

    // fuel: the engine burns the fuel slot furnace-style — 1 burn-tick per game tick at idle,
    // hotter while maneuvering. Running dry in flight gives a sputtering grace (lift capped just
    // below hover: no climbing, a slow sink) before the engine cuts out — recoverable, unlike
    // drowning: land, refuel, fly on. Creative pilots burn nothing and fly BLOCK-grade unless
    // fuel in the tank says otherwise.
    private static final float BURN_ACTIVE_MULT = 1.5f; // burn rate with any control key held
    private static final int SPUTTER_TICKS = 200;       // ~10 s dry-tank grace period
    private static final float SPUTTER_POWER = 0.92f;   // collective cap while sputtering: just under hover

    // engine state machine (synced via DW_FLAGS so prediction, rotor and sound track it)
    public static final int ENGINE_OFF = 0;     // parked, dry past the grace period, or drowned
    public static final int ENGINE_RUNNING = 1;
    public static final int ENGINE_SPUTTER = 2; // tank just ran dry in flight

    // box floor: the model rotates inside an axis-aligned box, so a fixed floor lets its corners
    // swing out through the bottom at attitude — most visibly the tail boom, whose underside swings
    // to ~0.97 below the origin in a full nose-up flare, sinking the boom into the ground on the
    // very manoeuvre you land with. The floor tracks the model instead: BASE_DEPTH is the level
    // resting depth, deepened only as attitude demands.
    //
    // That resting depth is the airframe's own lowest point — the underside of the cabin floor
    // plate, which is the deepest KEEL corner below. The 1.6.1 port carried 0.8 here, four tenths
    // of a block below anything drawn, so a craft resting squarely on its box hovered visibly above
    // the ground; matching the model is also what puts the resting origin on the entity tracker's
    // 1/32 grid, which is what {@link #unburyFloor} exists to clean up after
    private static final float BASE_DEPTH = 0.375f; // floor below the origin, level: the cabin floor plate
    private static final float BOX_TOP = 1.2f;      // ceiling above the origin, held fixed

    /** Slack above the box floor within which a block top counts as ground the craft is resting on
     *  rather than terrain it has fallen into — see {@link #unburyFloor}. Comfortably covers the
     *  entity tracker's quantization (1/32 block of position, ~1.4 degrees of attitude) and stays
     *  far under the tail boom's own ground clearance. */
    private static final double FLOOR_SNAP = 0.0625;

    /**
     * Lower silhouette of the airframe in blocks from the origin — {nose-axis, half-width, up} per
     * corner, each standing for a mirrored pair. Minimising the rotated height over just these
     * reproduces the whole airframe's lowest point exactly: height is linear in position, so its
     * minimum over the hull is always at one of its vertices. The rotor discs are left out on
     * purpose — a 3.75-block disc would drive the depth on every bank and float the craft.
     */
    private static final float[][] KEEL = {
        {  0.75f,  0.625f,  -0.1875f }, // front wall, bottom edge
        {  0.625f, 0.5f,    -0.375f  }, // cabin floor, forward corners
        { -0.625f, 0.5f,    -0.375f  }, // cabin floor, aft corners
        { -0.75f,  0.625f,  -0.1875f }, // back wall, bottom edge
        { -0.875f, 0.0625f,  0.75f   }, // tail boom underside, at the cabin
        { -2.125f, 0.0625f,  0.75f   }, // tail boom underside, at the tip
    };

    // parked collision: the airframe in blocks from the entity origin, along the nose axis
    // (positive toward the nose), out to either flank, and up. Read off the model, whose units
    // are an eighth of a block: the cabin runs from the windshield at model x -6 back past the
    // foot of the rotor mast at +7, ten units across, and the boom carries on to the tail
    // rotor's disc at +18
    private static final float HULL_FWD_MIN = -0.875f;  // back of the cabin, at the mast
    private static final float HULL_FWD_MAX = 0.75f;    // nose
    private static final float HULL_HALF_LAT = 0.625f;  // cabin flanks
    private static final float HULL_TOP = 1.1f;         // over the canopy; the mast and rotor above stay clear
    private static final float TAIL_FWD_MIN = -2.25f;   // the boom's tip, under the tail rotor
    private static final float TAIL_HALF_LAT = 0.25f;   // the boom is a rail; padded to something shoulderable
    private static final float TAIL_BOTTOM = 0.35f;     // the boom rides high — clear underneath, chest-high to a player
    private static final float TAIL_TOP = 1.15f;
    private static final float TAIL_MID = (TAIL_FWD_MIN + HULL_FWD_MIN) * 0.5f; // where a tail shove bears

    // shoving a parked craft: contact is read off those colliders, and only lasts while a
    // player keeps pressing — the craft slides out of reach within a fraction of a block, so
    // leaning on one nudges it rather than sending it away
    private static final double PUSH_REACH = 0.2;       // contact slack around a collider, EntityBoat's figure
    private static final double BODY_PUSH = 0.04;       // accel per tick off the cabin -> ~1.5 m/s of slide
    private static final double TAIL_SHOVE = 0.03;      // the same along the boom, where there is less to lean on
    private static final float TAIL_YAW_PUSH = 2.0f;    // deg/tick of swing from a square shove on the tail
    private static final float PARK_LEVEL_RATE = 0.04f;       // attitude bled off per tick in the air: a craft bailed
                                                              // at altitude still rights itself gradually over its fall
    private static final float PARK_LEVEL_RATE_GROUND = 0.6f; // on the ground, level almost at once — a nose-up flare
                                                              // rests the craft high on its tail boom, so a slow bleed
                                                              // would swing the nose down ~0.9 blocks in plain sight
                                                              // once you climb out (settles within ~0.3 s)

    // launcher (optional hardware, the dispenser build): right-click gunnery from the pilot's
    // seat, fed from the loadout's ammo slot — see updateLauncherServer
    private static final int FIRE_COOLDOWN_ARROW = 4;  // ticks: the vanilla held-right-click rate, full auto
    private static final int FIRE_COOLDOWN_CHARGE = 8; // half the arrow rate
    private static final int FIRE_COOLDOWN_TNT = 60;   // 3 s a bomb: TNT griefs, so each drop is deliberate
    private static final float ARROW_SPEED = 3.0f;     // a full-charge bow's muzzle speed
    private static final float ARROW_SPREAD = 2.0f;    // slight machine-gun spray (a bow is 1)
    private static final float SHOT_MOMENTUM = 0.5f;   // fraction of craft velocity carried onto shots
    private static final double MUZZLE_OFFSET = 2.2;   // spawn clear of the hull box's diagonal
    private static final int BOARD_FIRE_GRACE_TICKS = 10; // ~0.5s: covers the mount round-trip before the
                                                            // new pilot's real key state has reached us

    // pilot control key bits (see HelicopterInputMessage / ClientInputHandler)
    private static final int K_FWD = 1, K_BACK = 2, K_LEFT = 4, K_RIGHT = 8, K_UP = 16, K_DOWN = 32;
    private static final int K_FIRE = 64; // use-item key: the launcher trigger, not a flight control
    private static final int K_MOVE_MASK = K_FWD | K_BACK | K_LEFT | K_RIGHT | K_UP | K_DOWN;

    // taking punches, EntityBoat's scheme: a hit banks damage that bleeds off again at 1/tick, so
    // breaking a craft down takes a few blows in quick succession rather than one. The banked
    // damage also sizes the rock the renderer puts on the airframe (see DW_HIT_* below)
    private static final float HIT_DAMAGE_MULT = 10f; // banked per point of damage
    private static final float HIT_DAMAGE_MAX = 40f;  // banked damage that breaks the craft up
    private static final int HIT_SHAKE_TICKS = 10;    // how long one hit's rock runs

    // dataWatcher slots (base Entity uses 0-1)
    private static final int DW_ROLL = 22;
    private static final int DW_POWER = 23;
    private static final int DW_FLAGS = 24; // bit 0: engineDead, bits 1-2: engineState, bits 3-4: fuelTier ordinal, bit 5: cargo bay, bit 6: launcher
    private static final int DW_HIT_TIME = 25;   // ticks left on the punch rock
    private static final int DW_HIT_DIR = 26;    // which way it rocks; flipped by every hit
    private static final int DW_HIT_DAMAGE = 27; // banked punch damage, and the rock's amplitude

    /** Pilot seat height relative to the entity origin; lowered so the rotor clears the rider's head. */
    private static final double SEAT_OFFSET_Y = -0.2;

    /** Latest pilot input bitmask, written by the network handler (Netty thread), read on the server tick. */
    public volatile int inputKeys;

    /** Set by the client input handler when the LOCAL player is the pilot: predict locally, ignore the tracker. */
    public boolean clientControlled;

    /** Onboard storage: fuel + 3x3 cargo + ammo. Shown by the loadout menu (sneak + right-click). */
    public final HelicopterInventory inventory = new HelicopterInventory();

    // client interpolation toward the entity-tracker target (non-piloted / spectator view)
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;

    // flight state (read by the renderer where public)
    public float rotationRoll;
    public float prevRotationRoll; // previous-tick roll, for smooth render interpolation
    public float rotorPower;       // current rotor lift in units of gravity; drives thrust
    public float rotorSpeed;       // governed rotor RPM (0..1): drives the sound + rotor anims, decoupled from collective
    public float prevRotorSpeed;   // previous-tick rotor RPM, for smooth render interpolation
    public float rotorAngle;       // blade angle, radians, wrapped to [0, 2pi): per-craft, since one model renders them all
    public float prevRotorAngle;   // previous-tick blade angle, for smooth render interpolation
    public float rotationYawSpeed;
    public float rotationPitchSpeed; // deg/s, carries angular momentum between ticks
    public float rotationRollSpeed;  // deg/s, carries angular momentum between ticks

    /** Engine drowned by deep water: permanent; the craft is an unpowered, buoyant hulk. */
    private boolean engineDead;
    private boolean wasTouchingWater; // for detecting the tick the craft first hits water
    private int drownTicks;           // consecutive ticks buried past ENGINE_DROWN_SUB

    // engine + fuel state (server-authoritative; state and tier mirrored to clients via DW_FLAGS)
    private int engineState = ENGINE_OFF;
    private FuelTier fuelTier = FuelTier.WOOD; // grade the engine runs on, latched at ignition; sets flight params
    private float burnRemaining; // burn-ticks left on the current item (float: maneuvering burns 1.5/tick)
    private int burnMax;         // burn value of the current item, for the GUI flame gauge
    private int sputterTicks;    // grace countdown once the tank runs dry mid-flight

    // launcher state (server-only: firing is authoritative, clients just see the spawns)
    private int fireCooldown;
    private boolean fireWasHeld;   // for the once-per-press dry click
    private int boardGraceTicks;   // set on boarding; see BOARD_FIRE_GRACE_TICKS
    private boolean fireSafed;     // set on boarding; cleared once the boarding click is actually released

    /** Server: who was aboard at the end of last tick, for spotting a dismount (see releaseRider). */
    private Entity lastRider;

    private float yawRad, pitchRad, rollRad;
    private final Vector3 up = new Vector3(); // rotor axis in world space
    private final Vector3 thrust = new Vector3();

    // model-shaped collision, live only while parked (see getParts)
    private final ThxEntityHelicopterPart hullPart;
    private final ThxEntityHelicopterPart tailPart;
    private final Entity[] parts;

    public ThxEntityHelicopter(World world)
    {
        super(world);
        setSize(1.8f, BOX_TOP + BASE_DEPTH);
        yOffset = BASE_DEPTH; // level; setPosition deepens it as the attitude demands
        preventEntitySpawning = true;

        // the cabin sits flush on the box the craft rests on, so nothing catches in the gap
        // under the fuselage; the boom keeps the model's own clearance
        hullPart = new ThxEntityHelicopterPart(this, HULL_FWD_MIN, HULL_FWD_MAX, HULL_HALF_LAT, -yOffset, HULL_TOP);
        tailPart = new ThxEntityHelicopterPart(this, TAIL_FWD_MIN, HULL_FWD_MIN, TAIL_HALF_LAT, TAIL_BOTTOM, TAIL_TOP);
        parts = new Entity[] { hullPart, tailPart };
        setPosition(posX, posY, posZ); // seat them (and the parked lookup box) on the spawn spot
    }

    public ThxEntityHelicopter(World world, double x, double y, double z, float yaw,
                               boolean hasCargo, boolean hasAmmo)
    {
        this(world);
        setPositionAndRotation(x, y + yOffset, z, yaw, 0f);
        motionX = motionY = motionZ = 0.0;
        inventory.setSections(hasCargo, hasAmmo);
    }

    @Override
    protected void entityInit()
    {
        dataWatcher.addObject(DW_ROLL, Integer.valueOf(0));  // roll * 1000
        dataWatcher.addObject(DW_POWER, Integer.valueOf(0)); // rotorPower * 1000
        dataWatcher.addObject(DW_FLAGS, Byte.valueOf((byte) 0));
        dataWatcher.addObject(DW_HIT_TIME, Integer.valueOf(0));
        dataWatcher.addObject(DW_HIT_DIR, Integer.valueOf(1));
        dataWatcher.addObject(DW_HIT_DAMAGE, Float.valueOf(0f));
    }

    /** Ticks left on the punch rock; read by the renderer. */
    public int getTimeSinceHit() { return dataWatcher.getWatchableObjectInt(DW_HIT_TIME); }
    private void setTimeSinceHit(int ticks) { dataWatcher.updateObject(DW_HIT_TIME, Integer.valueOf(ticks)); }

    /** Which way the punch rock swings (+1/-1); read by the renderer. */
    public int getForwardDirection() { return dataWatcher.getWatchableObjectInt(DW_HIT_DIR); }
    private void setForwardDirection(int dir) { dataWatcher.updateObject(DW_HIT_DIR, Integer.valueOf(dir)); }

    /** Punch damage banked so far; read by the renderer as the rock's amplitude. */
    public float getDamageTaken() { return dataWatcher.getWatchableObjectFloat(DW_HIT_DAMAGE); }
    private void setDamageTaken(float damage) { dataWatcher.updateObject(DW_HIT_DAMAGE, Float.valueOf(damage)); }

    /** Nobody aboard: the craft is parked, so it turns solid along its outline, settles to
     *  level, and can be shoved around by hand. */
    private boolean isParked() { return riddenByEntity == null && !isDead; }

    /**
     * The parked craft's solid pieces. Vanilla's chunk lookup walks these whenever an AABB
     * query catches the craft itself, and {@link World#getCollidingBoundingBoxes} then treats
     * each one's box as a wall — so a parked helicopter blocks movement along its own shape.
     * A flown one has none: the flight model keeps its square box and the sky to itself.
     */
    @Override
    public Entity[] getParts() { return isParked() ? parts : null; }

    /**
     * Bounds and colliders in one place, since everything that moves the craft ends up here.
     *
     * The floor follows the model down as the craft tilts (see {@link #hullDepth()}), with the
     * ceiling held at {@link #BOX_TOP}, so the airframe never draws outside the box it collides
     * on. {@code yOffset} is the depth itself, which keeps {@code moveEntity}'s
     * {@code posY = minY + yOffset} round-trip exact: the origin — and with it the model and the
     * pilot's seat — holds still while the floor moves under it. Flying that is what you want; on
     * the ground it would bury the floor in the block the craft is standing on, so
     * {@link #refloorForAttitude()} rides the craft up instead.
     *
     * Horizontally, flying, this is vanilla's square box: exactly what the flight model and its
     * crash checks were tuned against. Parked, it becomes the airframe's full footprint — not
     * because that shape is solid (it isn't; {@link #getParts()} carries the solid pieces) but
     * because vanilla only reaches an entity's parts through the entity's own box, so it has to
     * span far enough out for a player standing at the tail to find them. Nothing collides
     * against that wide box: {@link #moveHull} narrows back to the square one first.
     */
    @Override
    public void setPosition(double x, double y, double z)
    {
        posX = x;
        posY = y;
        posZ = z;
        if (parts == null) // still inside Entity's constructor: nothing to size against yet
        {
            double half = width / 2.0;
            double y0 = y - yOffset + ySize;
            boundingBox.setBounds(x - half, y0, z - half, x + half, y0 + height, z + half);
            return;
        }

        yOffset = hullDepth();
        height = BOX_TOP + yOffset;
        double minY = y - yOffset + ySize;
        if (!isParked())
        {
            double half = width / 2.0;
            boundingBox.setBounds(x - half, minY, z - half, x + half, minY + height, z + half);
            return;
        }

        float rad = rotationYaw * RAD_PER_DEG;
        float sin = MathHelper.sin(rad), cos = MathHelper.cos(rad);
        double mid = (TAIL_FWD_MIN + HULL_FWD_MAX) * 0.5;
        double halfLen = (HULL_FWD_MAX - TAIL_FWD_MIN) * 0.5;
        double cx = x - mid * sin, cz = z + mid * cos;
        double halfX = halfLen * Math.abs(sin) + HULL_HALF_LAT * Math.abs(cos);
        double halfZ = halfLen * Math.abs(cos) + HULL_HALF_LAT * Math.abs(sin);
        boundingBox.setBounds(cx - halfX, minY, cz - halfZ, cx + halfX, minY + height, cz + halfZ);
        hullPart.follow(sin, cos);
        tailPart.follow(sin, cos);
    }

    /**
     * How far below the origin the box floor has to sit for the model to stay inside it at the
     * current attitude: the airframe's own lowest point, never shallower than {@link #BASE_DEPTH}.
     *
     * Mirrors the renderer's transform — pitch about the right wing, then roll about the nose —
     * under which a craft-local point sits at world height
     * {@code -fwd*sinPitch + cosPitch*(up*cosRoll + lat*sinRoll)}. Each {@link #KEEL} corner
     * stands for a mirrored pair, so the lateral term takes whichever sign comes out lower.
     */
    private float hullDepth()
    {
        float sinP = MathHelper.sin(rotationPitch * RAD_PER_DEG);
        float cosP = MathHelper.cos(rotationPitch * RAD_PER_DEG);
        float sinR = MathHelper.sin(rotationRoll * RAD_PER_DEG);
        float cosR = MathHelper.cos(rotationRoll * RAD_PER_DEG);
        float depth = BASE_DEPTH;
        for (int i = 0; i < KEEL.length; i++)
        {
            float[] corner = KEEL[i];
            float below = corner[0] * sinP - cosP * corner[2] * cosR
                    + Math.abs(cosP * corner[1] * sinR);
            if (below > depth) depth = below;
        }
        return depth;
    }

    /** Move on the square hull box, then put the parked lookup box back. Letting the craft's
     *  own collision run against that wide box would leave it perched on anything within a
     *  tail-length of the cabin. */
    private void moveHull(double dx, double dy, double dz)
    {
        double half = width / 2.0;
        boundingBox.setBounds(posX - half, boundingBox.minY, posZ - half,
                              posX + half, boundingBox.minY + height, posZ + half);
        moveEntity(dx, dy, dz);
        setPosition(posX, posY, posZ);
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate(); // prevPos/rotation bookkeeping, fire/water checks
        prevRotationRoll = rotationRoll; // captured at tick start, mirroring prevRotationYaw/Pitch
        prevRotorSpeed = rotorSpeed;     // same, for the rotor RPM the renderer interpolates
        prevRotorAngle = rotorAngle;     // and for the blade angle, which the renderer interpolates too

        // bounds first, before anything moves: boarding or bailing out swaps the craft between
        // its square flight box and its parked footprint + colliders (see setPosition)
        setPosition(posX, posY, posZ);

        // and get the floor out of the ground before any of it runs, on whichever side is about to
        // simulate — a floor a hair inside a block falls straight through it (see unburyFloor)
        if (!worldObj.isRemote || (clientControlled && riddenByEntity != null)) unburyFloor();

        if (!worldObj.isRemote)
        {
            // SERVER: authoritative simulation
            if (getTimeSinceHit() > 0) setTimeSinceHit(getTimeSinceHit() - 1);   // the punch rock settles
            if (getDamageTaken() > 0f) setDamageTaken(getDamageTaken() - 1f);    // and the banked damage bleeds off
            // a pilot leaving mid-air takes the craft's momentum with them; vanilla unmounts them
            // behind our back (sneak), so the handover is spotted by the seat emptying between
            // ticks rather than hooked
            if (riddenByEntity != lastRider)
            {
                if (riddenByEntity == null) releaseRider(lastRider);
                lastRider = riddenByEntity;
            }
            if (riddenByEntity != null && riddenByEntity.isDead) riddenByEntity.mountEntity(null);
            if (riddenByEntity != null) riddenByEntity.fallDistance = 0f; // the craft eats the impacts, not the pilot
            updateEngineServer();
            updateLauncherServer();
            if (isParked()) parkedStep(); // hands-on shoves + settle to level, before gravity
            if (riddenByEntity != null && engineState != ENGINE_OFF) flightStep();
            else gravityFall(); // vacant, out of fuel, or a drowned-engine hulk (possibly still ridden)
            dataWatcher.updateObject(DW_ROLL, Integer.valueOf((int) (rotationRoll * 1000f)));
            dataWatcher.updateObject(DW_POWER, Integer.valueOf((int) (rotorPower * 1000f)));
            dataWatcher.updateObject(DW_FLAGS, Byte.valueOf((byte) ((engineDead ? 1 : 0)
                    | (engineState << 1) | (fuelTier.ordinal() << 3)
                    | (inventory.hasCargo() ? 32 : 0) | (inventory.hasAmmo() ? 64 : 0))));
        }
        else
        {
            // engine state + fuel grade mirror the server flags; engine death is latched (the
            // predicting pilot may also have detected it locally a moment earlier; it never
            // un-dies). Prediction picks up a state/tier flip a couple of ticks after the
            // server — a brief, tiny divergence, same as the input latency we already accept.
            byte flags = dataWatcher.getWatchableObjectByte(DW_FLAGS);
            if ((flags & 1) != 0) engineDead = true;
            engineState = (flags >> 1) & 3;
            fuelTier = FuelTier.values()[(flags >> 3) & 3];
            // the installed sections ride along too: the client-side inventory starts blank
            // (entity NBT never reaches clients), yet the GUI greys captions off it and — far
            // worse — the client PREDICTS container clicks through isItemValidForSlot. Without
            // this the prediction disagrees with the server on every cargo/ammo insert, and the
            // resync snaps items between slots
            inventory.setSections((flags & 32) != 0, (flags & 64) != 0);

            if (clientControlled && riddenByEntity != null)
            {
                // CLIENT PREDICTION (local pilot): run identical physics locally for a smooth,
                // zero-lag view. The tracker's quantized updates are ignored (see
                // setPositionAndRotation2) so they don't fight the prediction.
                if (engineDead || engineState == ENGINE_OFF) gravityFall();
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

        // governed rotor RPM for the sound + rotor anims (computed on both sides; only read on the client)
        updateRotorSpeed();
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

    /**
     * Server-side engine + fuel bookkeeping, run before the physics branch picks
     * powered or unpowered flight. Furnace-style burn: one item ignites at a
     * time (latching the craft's {@link FuelTier}), ticks down while flying —
     * idle at furnace rate, {@link #BURN_ACTIVE_MULT} with any control key held
     * — and the partial burn survives a dismount (paused, not wasted) but is
     * lost if the craft is destroyed, exactly like a furnace.
     *
     * Creative pilots exempt the whole system: nothing burns and the engine
     * runs BLOCK-grade, unless fuel sits in the tank — then that fuel's grade
     * applies (still unconsumed), so lower tiers stay testable in creative.
     */
    private void updateEngineServer()
    {
        EntityPlayer pilot = riddenByEntity instanceof EntityPlayer ? (EntityPlayer) riddenByEntity : null;
        if (pilot == null || engineDead)
        {
            engineState = ENGINE_OFF; // parked or a hulk; any sputter grace is forfeit
            sputterTicks = 0;
            return;
        }

        if (pilot.capabilities.isCreativeMode)
        {
            engineState = ENGINE_RUNNING;
            sputterTicks = 0;
            FuelTier loaded = FuelTier.ofItem(inventory.getStackInSlot(HelicopterInventory.SLOT_FUEL));
            fuelTier = loaded != null ? loaded : FuelTier.BLOCK;
            return;
        }

        if (burnRemaining <= 0f && !igniteNextItem())
        {
            // dry tank: a running engine gets the sputtering grace before cutting out; a cold
            // one (mounting with nothing aboard) simply never starts
            if (engineState == ENGINE_RUNNING)
            {
                engineState = ENGINE_SPUTTER;
                sputterTicks = SPUTTER_TICKS;
            }
            if (engineState == ENGINE_SPUTTER && --sputterTicks <= 0) engineState = ENGINE_OFF;
            return;
        }

        engineState = ENGINE_RUNNING;
        sputterTicks = 0;
        burnRemaining -= ((inputKeys & K_MOVE_MASK) != 0) ? BURN_ACTIVE_MULT : 1f; // firing isn't maneuvering
    }

    /** Consume one item from the fuel slot furnace-style; latches the performance tier. */
    private boolean igniteNextItem()
    {
        ItemStack stack = inventory.getStackInSlot(HelicopterInventory.SLOT_FUEL);
        int burn = stack == null ? 0 : TileEntityFurnace.getItemBurnTime(stack);
        if (burn <= 0) return false;

        burnRemaining = burnMax = burn;
        fuelTier = FuelTier.classify(burn);

        stack.stackSize--;
        if (stack.stackSize <= 0)
        {
            // furnace parity: a consumed lava bucket leaves the empty bucket behind
            inventory.setInventorySlotContents(HelicopterInventory.SLOT_FUEL,
                    stack.getItem().getContainerItem(stack));
        }
        else
        {
            inventory.markDirty();
        }
        return true;
    }

    /** Burn-ticks left on the item in the firebox, for the GUI flame gauge (server value). */
    public int getBurnRemaining() { return (int) burnRemaining; }

    /** Burn value of the item in the firebox, for the GUI flame gauge (server value). */
    public int getBurnMax() { return burnMax; }

    /** True while the dry-tank grace period runs; drives the ragged engine-sound flutter. */
    public boolean isEngineSputtering() { return engineState == ENGINE_SPUTTER; }

    /**
     * Server-side launcher, run every tick (so the cooldown drains even between shots on the
     * ground). Right-click gunnery from the pilot's seat, fed from the loadout's ammo slot —
     * only on a craft built with the launcher, and independent of the engine: a parked,
     * dry-tank craft is still a turret.
     *
     * Arrows fire full-auto at the vanilla held-right-click rate, fire charges at half of it,
     * and TNT drops as a primed bomb under the hull on a long cooldown — it griefs, so each
     * one is deliberate. Shots aim along the pilot's look unless it points above the horizon
     * (the rotor is up there), in which case they follow the craft's nose; shots carry a
     * fraction of the craft's velocity and a bomb all of it. An empty launcher answers the
     * trigger with the dispenser's dry click, once per press.
     */
    private void updateLauncherServer()
    {
        if (fireCooldown > 0) fireCooldown--;

        if (boardGraceTicks > 0)
        {
            boardGraceTicks--;
            return; // the attach packet may not have reached the new pilot's client yet, so
                    // inputKeys may still be stale (or simply absent) — leave the trigger alone
        }
        boolean held = (inputKeys & K_FIRE) != 0;
        if (fireSafed)
        {
            if (held) return; // still the click that boarded them (or a hold carried past the grace window)
            fireSafed = false;
        }

        boolean pressed = held && !fireWasHeld;
        fireWasHeld = held;
        if (!held || !inventory.hasAmmo() || !(riddenByEntity instanceof EntityPlayer)) return;
        EntityPlayer pilot = (EntityPlayer) riddenByEntity;

        ItemStack ammo = inventory.getStackInSlot(HelicopterInventory.SLOT_AMMO);
        if (ammo == null)
        {
            if (pressed) worldObj.playSoundAtEntity(this, "random.click", 1.0f, 1.2f); // dry trigger
            return;
        }
        if (fireCooldown > 0) return;

        // aim down the pilot's look — unless it points above the horizon, then down the
        // craft's nose instead. Both pitch conventions are +down, so one formula serves
        boolean lookingUp = pilot.rotationPitch < 0f;
        float aimYaw = (lookingUp ? rotationYaw : pilot.rotationYaw) * RAD_PER_DEG;
        float aimPitch = (lookingUp ? rotationPitch : pilot.rotationPitch) * RAD_PER_DEG;
        double dx = -MathHelper.sin(aimYaw) * MathHelper.cos(aimPitch);
        double dy = -MathHelper.sin(aimPitch);
        double dz = MathHelper.cos(aimYaw) * MathHelper.cos(aimPitch);

        Item item = ammo.getItem();
        if (item == Items.arrow) fireArrow(pilot, dx, dy, dz);
        else if (item == Items.fire_charge) fireCharge(pilot, dx, dy, dz);
        else if (item == Item.getItemFromBlock(Blocks.tnt)) dropBomb(pilot);
        else return; // the slot only admits the three; stay safe against a stale save

        if (--ammo.stackSize <= 0) inventory.setInventorySlotContents(HelicopterInventory.SLOT_AMMO, null);
        else inventory.markDirty();
    }

    /** Full-auto chin gun: a bow-speed arrow with a touch of spray, credited to the pilot. */
    private void fireArrow(EntityPlayer pilot, double dx, double dy, double dz)
    {
        EntityArrow arrow = new EntityArrow(worldObj,
                posX + dx * MUZZLE_OFFSET, posY + dy * MUZZLE_OFFSET, posZ + dz * MUZZLE_OFFSET);
        arrow.shootingEntity = pilot; // kill credit; also lets the craft shrug off its own fire
        arrow.canBePickedUp = 1;      // spent arrows are scavengeable, like a dispenser's
        arrow.setThrowableHeading(dx, dy, dz, ARROW_SPEED, ARROW_SPREAD);
        arrow.motionX += motionX * SHOT_MOMENTUM;
        arrow.motionY += motionY * SHOT_MOMENTUM;
        arrow.motionZ += motionZ * SHOT_MOMENTUM;
        worldObj.spawnEntityInWorld(arrow);
        worldObj.playSoundAtEntity(this, "random.bow", 1.0f, 1.0f / (rand.nextFloat() * 0.4f + 0.8f));
        fireCooldown = FIRE_COOLDOWN_ARROW;
    }

    /** A blaze-style small fireball; its constructor turns the direction into acceleration. */
    private void fireCharge(EntityPlayer pilot, double dx, double dy, double dz)
    {
        EntitySmallFireball fireball = new EntitySmallFireball(worldObj,
                posX + dx * MUZZLE_OFFSET, posY + dy * MUZZLE_OFFSET, posZ + dz * MUZZLE_OFFSET,
                dx, dy, dz);
        fireball.shootingEntity = pilot;
        fireball.motionX = motionX * SHOT_MOMENTUM;
        fireball.motionY = motionY * SHOT_MOMENTUM;
        fireball.motionZ = motionZ * SHOT_MOMENTUM;
        worldObj.spawnEntityInWorld(fireball);
        worldObj.playSoundAtEntity(this, "mob.ghast.fireball", 1.0f, rand.nextFloat() * 0.2f + 0.9f);
        fireCooldown = FIRE_COOLDOWN_CHARGE;
    }

    /** A primed TNT bomb dropped under the hull, riding the craft's full momentum. */
    private void dropBomb(EntityPlayer pilot)
    {
        EntityTNTPrimed tnt = new EntityTNTPrimed(worldObj, posX, boundingBox.minY - 0.5, posZ, pilot);
        tnt.motionX = motionX;
        tnt.motionY = motionY;
        tnt.motionZ = motionZ;
        worldObj.spawnEntityInWorld(tnt);
        worldObj.playSoundAtEntity(this, "game.tnt.primed", 1.0f, 1.0f);
        fireCooldown = FIRE_COOLDOWN_TNT;
    }

    /** One step of piloted flight: input -> attitude/rotor -> thrust -> motion -> move + crash check. */
    private void flightStep()
    {
        applyPilotInput();
        updateRotation();
        double lift = refloorForAttitude(); // re-floor the box on the attitude we are about to move at
        updateVectors();
        updateMotion();

        double ix = motionX, iy = motionY, iz = motionZ; // intended motion, for the impact check
        moveEntity(motionX, motionY, motionZ);
        // the re-floor lift displaced the origin without being motion, so count it as intended too:
        // left out, a hard flare would read as most of a crash's worth of blocked travel
        handleImpact(ix, iy + lift, iz, CRASH_SPEED * fuelTier.impactMult);
        if (isDead) return;
        updateWaterState(ix * ix + iy * iy + iz * iz);
    }

    /**
     * Lift the box floor back out of a block it is only just inside of, before anything moves.
     *
     * Vanilla's downward clamp ({@link AxisAlignedBB#calculateYOffset}) only catches a box whose
     * floor is at or above the block top: a floor a hundredth of a block inside the ground is
     * standing on nothing, and the next downward tick falls clean through to the next block top
     * below — a whole block, in one go.
     *
     * A craft reaches that state without ever having moved there. The entity tracker quantizes
     * position to 1/32 of a block, rounded down, and attitude to a byte — and attitude is depth
     * here ({@link #hullDepth}), so a client's copy of a craft resting squarely on the ground sits
     * a fraction low, on a fraction of the server's pitch, with its floor just inside the block
     * underneath. Nothing comes of that while the tracker is driving it. The moment the local pilot
     * mounts, the client switches to predicting the physics itself ({@link #clientControlled}) and
     * the first tick of it drops the craft a block into the ground — the server meanwhile sitting
     * exactly where it was, which is why it looks intermittent and why it comes right on takeoff.
     *
     * Measured on the square hull footprint, the one the craft actually collides on (see
     * {@link #moveHull}), and a no-op whenever the craft is airborne or truly resting.
     */
    private void unburyFloor()
    {
        double floor = boundingBox.minY;
        double half = width / 2.0;
        AxisAlignedBB slab = AxisAlignedBB.getBoundingBox(
                posX - half, floor, posZ - half,
                posX + half, floor + FLOOR_SNAP, posZ + half);
        double top = floor;
        for (Object o : worldObj.getCollidingBoundingBoxes(this, slab))
        {
            double blockTop = ((AxisAlignedBB) o).maxY;
            if (blockTop > top && blockTop <= floor + FLOOR_SNAP) top = blockTop;
        }
        if (top > floor) setPosition(posX, posY + (top - floor), posZ);
    }

    /**
     * Re-floor the box on the attitude the craft is about to move at, riding up where the
     * deepening floor would otherwise sweep into terrain.
     *
     * {@link #setPosition} holds the origin still and moves the floor, which is right in the air
     * but ruinous on the ground: resting, the floor sits exactly flush with the block top —
     * {@code calculateYOffset} put it there — so a manoeuvre that deepens the box by any amount at
     * all buries the floor inside that block. Vanilla's downward clamp only fires while the floor
     * is at or above the block top, so once it is inside nothing catches the craft until the next
     * block boundary, a whole block down: a hard flare sank the helicopter into the ground it was
     * standing on.
     *
     * Conserving the floor instead of the origin lifts the craft as its keel swings down, which is
     * what a flare on the skids does anyway. Only the slab the floor just swept through is tested,
     * so an attitude change with nothing under it still holds the origin still.
     *
     * @return how far the craft was lifted, which the caller owes {@link #handleImpact} — the origin
     *         moved without the craft travelling, and blocked travel is what wrecks it.
     */
    private double refloorForAttitude()
    {
        double floor = boundingBox.minY;
        setPosition(posX, posY, posZ);

        double swept = floor - boundingBox.minY;
        if (swept <= 0.0) return 0.0; // levelling out: the floor rises, and rising never buries it

        AxisAlignedBB slab = AxisAlignedBB.getBoundingBox(
                boundingBox.minX, boundingBox.minY, boundingBox.minZ,
                boundingBox.maxX, floor, boundingBox.maxZ);
        if (worldObj.getCollidingBoundingBoxes(this, slab).isEmpty()) return 0.0;

        // put the floor back where it stood, a position the craft already held legally
        setPosition(posX, posY + swept, posZ);
        return swept;
    }

    /**
     * Ground handling for a craft nobody is flying, run before gravity each tick.
     *
     * Attitude bleeds back to level: there is no rotor left holding the nose anywhere, so a
     * craft set down on a slope — or dropped in on its side — rights itself over a few seconds.
     *
     * Players walk it around by leaning on it, and where they lean decides what happens: a
     * shove on the cabin slides the whole craft, while a shove on the boom is a shove on a
     * lever — its sideways part swings the nose about the cabin, its lengthwise part just
     * pushes. Contact is read off the model colliders (the same boxes that stop players
     * walking through), and the craft slides out of contact within a fraction of a block, so
     * standing against a parked helicopter nudges it rather than sending it away.
     */
    private void parkedStep()
    {
        // on the ground, level fast so the settle reads as a quick set-down rather than a slow,
        // visible sink; in the air keep the gentle rate for the graceful righting of a craft bailed
        // at altitude (see the two rate constants)
        float levelRate = onGround ? PARK_LEVEL_RATE_GROUND : PARK_LEVEL_RATE;
        rotationPitch *= 1f - levelRate;
        rotationRoll *= 1f - levelRate;
        if (Math.abs(rotationPitch) < 0.05f) rotationPitch = 0f; // settle all the way, not asymptotically
        if (Math.abs(rotationRoll) < 0.05f) rotationRoll = 0f;
        rotationPitchSpeed = rotationRollSpeed = 0f;

        List<?> nearby = worldObj.getEntitiesWithinAABBExcludingEntity(this,
                boundingBox.expand(PUSH_REACH, 0.0, PUSH_REACH));
        if (nearby.isEmpty()) return;

        float rad = rotationYaw * RAD_PER_DEG;
        float sin = MathHelper.sin(rad), cos = MathHelper.cos(rad);
        double fwdX = -sin, fwdZ = cos;      // nose
        double rightX = -cos, rightZ = -sin; // starboard
        float swing = 0f;

        for (Object o : nearby)
        {
            if (!(o instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) o;
            AxisAlignedBB reach = player.boundingBox.expand(PUSH_REACH, 0.0, PUSH_REACH);
            if (!reach.intersectsWith(hullPart.boundingBox) && !reach.intersectsWith(tailPart.boundingBox)) continue;

            // which end they are on, measured along the nose axis rather than against the
            // colliders: those are axis-aligned, so they overlap a little on the diagonals
            boolean onTail = (player.posX - posX) * fwdX + (player.posZ - posZ) * fwdZ < HULL_FWD_MIN;

            // shove away from the player, EntityBoat-style: the direction from them to the
            // piece they are leaning on, taken at the tail's mid-boom for a tail shove
            double px = posX + (onTail ? fwdX * TAIL_MID : 0.0) - player.posX;
            double pz = posZ + (onTail ? fwdZ * TAIL_MID : 0.0) - player.posZ;
            double len = Math.sqrt(px * px + pz * pz);
            if (len < 0.01) continue;
            px /= len;
            pz /= len;

            if (onTail)
            {
                // sideways at the boom yaws the craft; pushing the tail to starboard walks
                // the nose to port, so the swing runs against the lateral component
                swing -= (float) (px * rightX + pz * rightZ);
                double along = px * fwdX + pz * fwdZ;
                motionX += along * fwdX * TAIL_SHOVE;
                motionZ += along * fwdZ * TAIL_SHOVE;
            }
            else
            {
                motionX += px * BODY_PUSH;
                motionZ += pz * BODY_PUSH;
            }
        }

        if (swing != 0f)
        {
            rotationYaw = (rotationYaw + swing * TAIL_YAW_PUSH) % 360f;
            if (rotationYaw > 180f) rotationYaw -= 360f;
            else if (rotationYaw < -180f) rotationYaw += 360f;
            setPosition(posX, posY, posZ); // colliders and lookup box follow the new heading
        }
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
            moveHull(motionX, motionY, motionZ);
        }
        else
        {
            motionY -= 0.08;
            if (motionY < -2.0) motionY = -2.0;
            double ix = motionX, iy = motionY, iz = motionZ;
            moveHull(motionX, motionY, motionZ);
            handleImpact(ix, iy, iz, CRASH_SPEED_VACANT);
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

    /** Fraction of the hull below the water line, EntityBoat-style: horizontal slices of the
     *  square hull box. Measured on that box and not on the parked lookup one, which spans the
     *  whole airframe — a slice of it counts as wet if any of it is, so a craft standing safely
     *  on a beach would find the sea a couple of blocks off and drown its own engine.
     *
     *  Twice the boat's slice count, because a floating helicopter is something you land on and
     *  stand still on: the restoring force steps with the slices, and at the boat's resolution a
     *  craft at rest rides those steps up and down through half a block. */
    private float submergedFraction()
    {
        final int slices = 10;
        float sub = 0f;
        double half = width / 2.0;
        double minY = boundingBox.minY;
        double sliceH = height / (double) slices;
        for (int i = 0; i < slices; i++)
        {
            AxisAlignedBB slice = AxisAlignedBB.getBoundingBox(
                posX - half, minY + sliceH * i, posZ - half,
                posX + half, minY + sliceH * (i + 1), posZ + half);
            if (worldObj.isAABBInMaterial(slice, Material.water)) sub += 1f / slices;
        }
        return sub;
    }

    /** Post-move water checks. Setting down on the surface is routine (see {@link #floatOnWater});
     *  only arriving faster than a ditching wrecks the craft (server side, powered flight only —
     *  entrySpeedSq is 0 for unpowered falls), and only burying it past the drown depth kills the
     *  engine for good, after which it floats on as an unpowered hulk. */
    private void updateWaterState(double entrySpeedSq)
    {
        float sub = submergedFraction();
        boolean touching = sub > 0.05f;
        float waterBar = WATER_CRASH_SPEED * fuelTier.impactMult; // a tougher grade ditches harder too
        if (touching && !wasTouchingWater && !worldObj.isRemote
            && entrySpeedSq > waterBar * waterBar)
        {
            crash();
            return;
        }
        // water in the engine, but only once the hull has stayed buried: a hard arrival dunks the
        // craft for a few ticks before the buoyancy throws it back up, and that is a landing
        if (sub >= ENGINE_DROWN_SUB)
        {
            if (++drownTicks >= DROWN_TICKS) engineDead = true;
        }
        else
        {
            drownTicks = 0;
        }
        wasTouchingWater = touching;
    }

    /**
     * Settle up with whatever the craft just hit: how much motion {@code moveEntity} refused to
     * carry out (intended minus actual) is the strike.
     *
     * Past the threshold the craft is wrecked. Short of it the impact still costs speed. Vanilla
     * zeroes the blocked axis and no more, which leaves a craft that clips a cliff on the way past
     * carrying its full along-the-wall speed as if nothing happened — so the rest of the velocity
     * is scrubbed too, in proportion to how hard the strike was: a graze barely registers, a strike
     * just short of wrecking takes most of the speed with it. That also gives the pilot the one
     * thing a bare axis-zero never did — a reason to feel the difference between missing the trees
     * and going through them.
     *
     * The deadband is what keeps resting contact free: a craft sitting on the ground has its weight
     * blocked every tick, and charging it for that would glue it to the spot.
     *
     * Runs on both sides — the pilot's client predicts this same step, and a bleed it did not know
     * about would show up as the craft snapping back on the next tracker update. Only wrecking is
     * server-side.
     */
    private void handleImpact(double intendedX, double intendedY, double intendedZ, float threshold)
    {
        double bx = intendedX - (posX - prevPosX);
        double by = intendedY - (posY - prevPosY);
        double bz = intendedZ - (posZ - prevPosZ);
        double blocked = Math.sqrt(bx * bx + by * by + bz * bz);

        if (blocked > threshold)
        {
            if (!worldObj.isRemote) crash(); // prediction never wrecks locally
            return;
        }

        float deadband = threshold * IMPACT_DEADBAND;
        if (blocked <= deadband) return;

        // severity ramps from the deadband to the crash bar, so there is no step at either end
        double severity = (blocked - deadband) / (threshold - deadband);
        double keep = 1.0 - IMPACT_BLEED * severity;
        motionX *= keep;
        motionY *= keep;
        motionZ *= keep;
    }

    /**
     * Wreck the craft (server side). A piloted impact — someone aboard when it hit — is a
     * fireball: the fuel grade aboard sets the blast (an empty tank is just a small pop), the
     * fuel itself is destroyed, the cargo is flung across the site, and, mobGriefing
     * permitting, the terrain breaks and fires spread with the amount of fuel that went up.
     * Cargo scatters AFTER the blast so it isn't vaporized by it — but whatever lands in the
     * flames burns like any dropped item.
     *
     * A vacant craft never explodes: it breaks up where it falls, spilling the helicopter
     * item and the whole loadout. (Bailing out early enough that the craft impacts on its own
     * demotes the crash to a break-up; riding it in does not.)
     */
    private void crash()
    {
        boolean piloted = riddenByEntity != null;
        if (piloted) riddenByEntity.mountEntity(null);

        if (!piloted)
        {
            entityDropItem(createItem(), 0.5f);
            dropInventoryContents();
            setDead();
            return;
        }

        // yield: the best grade aboard (loaded or mid-burn) sets the blast; the total
        // burn-ticks aboard set how much fire follows
        ItemStack fuel = inventory.getStackInSlot(HelicopterInventory.SLOT_FUEL);
        FuelTier loadedTier = FuelTier.ofItem(fuel);
        FuelTier boomTier = burnRemaining > 0f ? fuelTier : null;
        if (loadedTier != null && (boomTier == null || loadedTier.ordinal() > boomTier.ordinal()))
        {
            boomTier = loadedTier;
        }
        int burnTicksAboard = (int) burnRemaining
                + (loadedTier != null ? fuel.stackSize * TileEntityFurnace.getItemBurnTime(fuel) : 0);
        float strength = boomTier != null ? boomTier.crashStrength : CRASH_STRENGTH_DRY;
        boolean fueled = boomTier != null;
        boolean griefing = worldObj.getGameRules().getGameRuleBooleanValue("mobGriefing");

        // the fuel goes up with the craft
        inventory.setInventorySlotContents(HelicopterInventory.SLOT_FUEL, null);
        burnRemaining = 0f;

        worldObj.newExplosion(this, posX, posY, posZ, strength, fueled && griefing, griefing);
        scatterCargo();
        if (fueled && griefing) igniteCrashSite(strength, burnTicksAboard);
        setDead();
    }

    /** Flings every cargo stack across the crash site with a bit of loft. */
    private void scatterCargo()
    {
        for (int i = 0; i < HelicopterInventory.SLOT_CARGO_COUNT; i++)
        {
            int slot = HelicopterInventory.SLOT_CARGO_START + i;
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null) continue;
            inventory.setInventorySlotContents(slot, null);
            EntityItem item = new EntityItem(worldObj,
                    posX + (rand.nextDouble() - 0.5), posY + 0.5, posZ + (rand.nextDouble() - 0.5), stack);
            item.motionX = rand.nextGaussian() * 0.15;
            item.motionY = 0.2 + rand.nextDouble() * 0.3;
            item.motionZ = rand.nextGaussian() * 0.15;
            item.delayBeforeCanPickup = 20;
            worldObj.spawnEntityInWorld(item);
        }
    }

    /** Scatters fires around the wreck — roughly one per coal-equivalent of fuel that went up —
     *  on whatever surfaces are left near the crater. */
    private void igniteCrashSite(float strength, int burnTicksAboard)
    {
        int fires = 2 + burnTicksAboard / FuelTier.COAL_BURN_TICKS;
        if (fires > CRASH_FIRES_MAX) fires = CRASH_FIRES_MAX;
        int radius = (int) (strength * 1.5f) + 2;
        int cx = MathHelper.floor_double(posX);
        int cy = MathHelper.floor_double(posY);
        int cz = MathHelper.floor_double(posZ);
        for (int i = 0; i < fires; i++)
        {
            int x = cx + rand.nextInt(radius * 2 + 1) - radius;
            int z = cz + rand.nextInt(radius * 2 + 1) - radius;
            for (int y = cy + 3; y > cy - 4; y--) // walk down to the local surface
            {
                if (!worldObj.isAirBlock(x, y, z)) continue;
                if (World.doesBlockHaveSolidTopSurface(worldObj, x, y - 1, z))
                {
                    worldObj.setBlock(x, y, z, Blocks.fire);
                    break;
                }
            }
        }
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

    /** Governed rotor RPM (0..1) for the sound, rotor animation and the lift ceiling: winds up to
     *  full while the engine runs and coasts down to a stop when it doesn't (including a dry tank
     *  past the grace — and it never winds up at all with nothing in the firebox). Decoupled from
     *  collective on purpose, so descending (reduced collective) never slows the rotor.
     *
     *  The ramp is linear rather than an asymptotic lerp, so the wind-up takes exactly the fuel
     *  grade's {@link FuelTier#spoolSeconds} from a standstill instead of merely approaching full
     *  forever; an unpowered disc coasts back down on its own inertia, slower than it wound up.
     *  Derived from replicated state on each side rather than syncing another field. */
    private void updateRotorSpeed()
    {
        boolean running = !engineDead && riddenByEntity != null && engineState != ENGINE_OFF;
        float step = DT / fuelTier.spoolSeconds;
        if (running) rotorSpeed = rotorSpeed + step > 1f ? 1f : rotorSpeed + step;
        else rotorSpeed = rotorSpeed - step / ROTOR_COAST_MULT < 0f ? 0f : rotorSpeed - step / ROTOR_COAST_MULT;

        // and carry the blade angle with it. This lives on the entity rather than in the model
        // because the renderer keeps one shared model instance for every helicopter in view: an
        // angle accumulated there would be common to all of them, and would advance at whichever
        // craft the frame happened to draw first. Ticked, so it's per-craft and framerate-independent.
        rotorAngle += rotorSpeed * ROTOR_RAD_PER_TICK;
        if (rotorAngle >= TWO_PI) rotorAngle -= TWO_PI;
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

        // YAW: follow the pilot's look direction (their rotation is already synced to the server);
        // better fuel turns the nose faster
        float deltaYawDeg = riddenByEntity.rotationYaw - rotationYaw;
        while (deltaYawDeg > 180f) deltaYawDeg -= 360f;
        while (deltaYawDeg < -180f) deltaYawDeg += 360f;
        float yawCap = 90f * fuelTier.yawMult;
        rotationYawSpeed = deltaYawDeg * 3f * fuelTier.yawMult;
        if (rotationYawSpeed > yawCap) rotationYawSpeed = yawCap;
        if (rotationYawSpeed < -yawCap) rotationYawSpeed = -yawCap;
        rotationYaw += rotationYawSpeed * DT;
        rotationYaw %= 360f;

        // PITCH: W/S apply torque (authority fading near the limit, scaled by fuel grade), the
        // stabilizer pulls weakly toward level, and the rate carries momentum -> the nose dips,
        // wallows, and recovers
        float pitchTorque = -rotationPitch * STAB_GAIN;
        if (fwdK) pitchTorque += PITCH_TORQUE * fuelTier.torqueMult * softLimit(rotationPitch, MAX_PITCH_DOWN);
        else if (backK) pitchTorque -= PITCH_TORQUE * fuelTier.torqueMult * softLimit(-rotationPitch, MAX_PITCH_UP);
        rotationPitchSpeed = (rotationPitchSpeed + pitchTorque * DT) * ANGULAR_DRAG;
        rotationPitch += rotationPitchSpeed * DT;
        if (rotationPitch > MAX_PITCH_DOWN) { rotationPitch = MAX_PITCH_DOWN; if (rotationPitchSpeed > 0f) rotationPitchSpeed = 0f; }
        else if (rotationPitch < -MAX_PITCH_UP) { rotationPitch = -MAX_PITCH_UP; if (rotationPitchSpeed < 0f) rotationPitchSpeed = 0f; }

        // ROLL: same dynamics as pitch
        float rollTorque = -rotationRoll * STAB_GAIN;
        if (leftK) rollTorque += ROLL_TORQUE * fuelTier.torqueMult * softLimit(rotationRoll, MAX_ROLL);
        else if (rightK) rollTorque -= ROLL_TORQUE * fuelTier.torqueMult * softLimit(-rotationRoll, MAX_ROLL);
        rotationRollSpeed = (rotationRollSpeed + rollTorque * DT) * ANGULAR_DRAG;
        rotationRoll += rotationRollSpeed * DT;
        if (rotationRoll > MAX_ROLL) { rotationRoll = MAX_ROLL; if (rotationRollSpeed > 0f) rotationRollSpeed = 0f; }
        else if (rotationRoll < -MAX_ROLL) { rotationRoll = -MAX_ROLL; if (rotationRollSpeed < 0f) rotationRollSpeed = 0f; }

        // ROTOR (collective): spool toward the commanded power; neutral idles just under hover,
        // so a hands-off craft slowly settles instead of auto-hovering. Full collective is the
        // fuel grade's ceiling; a sputtering engine caps out just under hover — no climbing on
        // an empty tank, only stretching the glide
        float powerTarget = upK ? fuelTier.powerMax : downK ? POWER_MIN : POWER_NEUTRAL;
        if (engineState == ENGINE_SPUTTER && powerTarget > SPUTTER_POWER) powerTarget = SPUTTER_POWER;
        rotorPower += (powerTarget - rotorPower) * POWER_SPOOL;

        // a disc can only lift what it is turning for: rotor RPM is a hard ceiling on collective,
        // so the wind-up is felt and not just heard. On a cold start this pins power to the ramp
        // all the way up, and the craft breaks ground exactly as the rotor reaches governed RPM.
        // The ceiling is that fraction OF the grade's full collective, not a bare comparison:
        // rotorSpeed is normalised RPM (0..1) while rotorPower is lift in units of gravity, so
        // clamping straight to it would pin lift at 1.0 — exactly hover — and no grade could climb
        float rpmCeiling = rotorSpeed * fuelTier.powerMax;
        if (rotorPower > rpmCeiling) rotorPower = rpmCeiling;
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
        // world gravity: nosing down trades lift for forward drive and starts a sink. Better fuel
        // drives harder in the tilt direction; since terminal speed is thrust / (1 - drag), that
        // buys top speed without the mushy coasting a lighter drag would bring. The vertical
        // component stays untouched — hover still costs exactly power 1.0 on every grade, and
        // climb rate scales through powerMax instead
        float lift = GRAVITY * rotorPower;
        float drive = lift * fuelTier.driveMult;
        thrust.x = up.x * drive;
        thrust.y = up.y * lift - GRAVITY;
        thrust.z = up.z * drive;
    }

    private void updateMotion()
    {
        updateThrust();

        // per-axis drag in the heading frame: slippery along the nose, draggier sideways (sideslip
        // bleeds off, so banked turns carve instead of drifting), draggiest through the rotor disc;
        // terminal speeds fall out of drag vs thrust: wood-tier ~1.05 b/t dive, ~0.85 level cruise,
        // ~0.4 climb, with better grades scaling the thrust side of that ratio
        float fwdX = -MathHelper.sin(yawRad), fwdZ = MathHelper.cos(yawRad);
        double vFwd = (motionX * fwdX + motionZ * fwdZ) * DRAG_FWD;
        double vLat = (motionX * fwdZ - motionZ * fwdX) * DRAG_LAT;
        if (vFwd < 0.0) vFwd *= 0.97; // tail-first is draggier: backward flight tops out lower
        motionX = vFwd * fwdX + vLat * fwdZ + thrust.x;
        motionZ = vFwd * fwdZ - vLat * fwdX + thrust.z;
        motionY = motionY * DRAG_VERT + thrust.y;

        floatOnWater(); // the surface is a runway, not a hole in the world

        double speedSq = motionX * motionX + motionY * motionY + motionZ * motionZ;
        float maxVel = fuelTier.maxVelocity;
        if (speedSq > maxVel * maxVel)
        {
            double scale = maxVel / Math.sqrt(speedSq);
            motionX *= scale;
            motionY *= scale;
            motionZ *= scale;
        }
    }

    /**
     * Buoyancy on a craft under power, the last step of {@link #updateMotion()}: displacement
     * holds the hull on the surface, so water is somewhere to land rather than somewhere to fall
     * through. Gravity is already carried in the thrust, so this is the displacement force alone
     * — which leaves a craft at hands-off collective riding about a third submerged, its cabin
     * floor at the waterline, and throws a dunked one straight back up. Nothing the pilot can do
     * with collective holds it under. Taking off is then just collective: the water's hold fades
     * as the hull comes out of it, and a wood-grade craft is clear of the surface in half a second.
     *
     * The water's hold scales with how much hull is actually wet, so it fades as the craft rises
     * and bites hardest on a craft that has buried itself. Afloat it is still firm enough that
     * taxiing is a crawl (~1.5 m/s): the way across a lake is to take off, and skimming the
     * surface on the way past costs real speed.
     */
    private void floatOnWater()
    {
        float sub = submergedFraction();
        if (sub < 0.01f) return;
        // one-sided: displacement pushes a hull up, never down. The unpowered branch in
        // gravityFall carries no gravity of its own and needs both halves of the spring, but here
        // weight is already in the thrust, and a spring that pulled back down above the float
        // level would out-pull the rotor and pin the craft to the water
        float lift = BUOY_ACCEL * (sub - BUOY_LEVEL);
        if (lift > 0f) motionY += lift;
        double drag = 1.0 - (1.0 - WATER_DRAG) * sub;
        motionX *= drag;
        motionY *= drag;
        motionZ *= drag;
    }

    /**
     * Right-click: board if empty; while someone is aboard every click is consumed —
     * including the pilot's own, which is the launcher trigger now (see
     * {@link #updateLauncherServer}), so it must never dismount. Dismounting is vanilla
     * sneak. Sneak + right-click on a parked craft opens the loadout menu
     * (fuel/cargo/launcher) instead of boarding.
     *
     * Boarding is itself a right-click, and firing has no press-edge check of its own — it
     * fires on {@code held} alone (see {@link #updateLauncherServer}) — so the very click that
     * seats the pilot would double as a trigger pull if it's still down when their input starts
     * arriving. {@link #boardGraceTicks} bridges the mount round-trip and {@link #fireSafed}
     * then withholds the trigger until that click is actually released, however slow.
     */
    @Override
    public boolean interactFirst(EntityPlayer player)
    {
        if (riddenByEntity != null)
        {
            return true; // occupied: outsiders can't board, and the pilot's click is the trigger
        }
        if (player.isSneaking())
        {
            if (!worldObj.isRemote)
            {
                player.openGui(ModThx.instance, ThxGuiHandler.GUI_HELICOPTER, worldObj, getEntityId(), 0, 0);
            }
            return true;
        }
        if (!worldObj.isRemote)
        {
            player.mountEntity(this);
            boardGraceTicks = BOARD_FIRE_GRACE_TICKS;
            fireSafed = true; // require a real release before the trigger can arm
        }
        return true;
    }

    /**
     * Bailing out (server side): a pilot who leaves the seat in mid-air keeps a share of the
     * craft's velocity, so stepping out of a cruising helicopter throws you clear along its
     * flight path — and a hop out of a hover is still just a hop. The craft keeps its own
     * momentum: a pilot's weight is already in the flight model, not carried by it.
     *
     * The fall starts where they let go, so a bail-out at altitude is still a long way down
     * (the craft absorbs impacts only while you're aboard — see {@link #onUpdate}).
     *
     * A player's own client is the authority on where it is, and the entity tracker's velocity
     * updates go to everyone watching them EXCEPT themselves; the pilot has to be told about
     * their new motion directly, the way vanilla knockback does it.
     */
    private void releaseRider(Entity rider)
    {
        if (rider == null || rider.isDead || onGround) return;

        double speed = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        if (speed * BAILOUT_MOMENTUM < BAILOUT_MIN_SPEED) return;

        double share = Math.min(BAILOUT_MOMENTUM, BAILOUT_MAX_SPEED / speed);
        rider.motionX = motionX * share;
        rider.motionY = motionY * share;
        rider.motionZ = motionZ * share;
        rider.velocityChanged = true; // for everyone else watching them
        if (rider instanceof EntityPlayerMP)
        {
            ((EntityPlayerMP) rider).playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(rider));
        }
    }

    @Override
    public void updateRiderPosition()
    {
        if (riddenByEntity != null)
        {
            riddenByEntity.setPosition(posX, posY + SEAT_OFFSET_Y + riddenByEntity.getYOffset(), posZ);
        }
    }

    /**
     * Let a player punch a helicopter apart; outside creative mode it drops back to an item. The
     * loadout contents (remaining fuel + cargo) drop in every mode, chest-style — but not the
     * in-progress burn, like a furnace.
     *
     * EntityBoat's pacing: a hull doesn't come apart on one blow. Each hit banks damage that
     * decays again at 1/tick (see {@link #onUpdate}), so it takes a few hits in quick succession —
     * and each one rocks the airframe, harder the closer it is to breaking up (the rock is drawn
     * from {@link #getTimeSinceHit()}/{@link #getDamageTaken()}, replicated for every viewer). A
     * creative punch still breaks it up at once, and drops no item.
     */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (worldObj.isRemote || isDead) return false;
        Entity attacker = source.getEntity();
        if (!(attacker instanceof EntityPlayer) || attacker == riddenByEntity) return false;

        setForwardDirection(-getForwardDirection());
        setTimeSinceHit(HIT_SHAKE_TICKS);
        setDamageTaken(getDamageTaken() + amount * HIT_DAMAGE_MULT);
        setBeenAttacked();

        boolean creative = ((EntityPlayer) attacker).capabilities.isCreativeMode;
        if (!creative && getDamageTaken() <= HIT_DAMAGE_MAX) return true; // rocked, but still in one piece

        if (riddenByEntity != null) riddenByEntity.mountEntity(null);
        if (!creative) entityDropItem(createItem(), 0.5f);
        dropInventoryContents();
        setDead();
        return true;
    }

    /** The item this craft came from: a helicopter carrying the sections it was built with,
     *  so an outfitted craft drops an outfitted item rather than reverting to the base build. */
    private ItemStack createItem()
    {
        return ThxItemHelicopter.create(inventory.hasCargo(), inventory.hasAmmo());
    }

    /** Spills every loadout slot (fuel + cargo) into the world and empties the inventory. */
    private void dropInventoryContents()
    {
        for (int i = 0; i < inventory.getSizeInventory(); i++)
        {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null)
            {
                inventory.setInventorySlotContents(i, null);
                entityDropItem(stack, 0.5f);
            }
        }
    }

    @Override
    public boolean canBeCollidedWith() { return !isDead; }

    /** Vanilla's shove works off this entity's own box, which parked spans the whole airframe.
     *  {@link #parkedStep()} reads contact off the model colliders instead. */
    @Override
    public boolean canBePushed() { return false; }

    @Override
    protected boolean canTriggerWalking() { return false; }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag)
    {
        engineDead = tag.getBoolean("engineDead");
        inventory.readFromNBT(tag);
        burnRemaining = tag.getFloat("burnRemaining");
        burnMax = tag.getInteger("burnMax");
        int tier = tag.getByte("fuelTier");
        fuelTier = (tier >= 0 && tier < FuelTier.values().length) ? FuelTier.values()[tier] : FuelTier.WOOD;
        // engineState/sputterTicks are transient: a reloaded craft restarts from OFF next tick
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag)
    {
        tag.setBoolean("engineDead", engineDead);
        inventory.writeToNBT(tag);
        tag.setFloat("burnRemaining", burnRemaining);
        tag.setInteger("burnMax", burnMax);
        tag.setByte("fuelTier", (byte) fuelTier.ordinal());
    }
}
