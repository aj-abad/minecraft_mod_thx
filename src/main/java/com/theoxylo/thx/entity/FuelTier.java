package com.theoxylo.thx.entity;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;

/**
 * Fuel quality tiers, classified by furnace burn time: wood-grade scraps, coal,
 * and block-grade fuel (coal blocks, lava buckets). Better fuel doesn't just
 * burn longer — the engine runs hotter, lifting the whole flight envelope
 * (pitch/roll authority, yaw rate, climb power, top speed) ~20-25% per tier.
 *
 * WOOD is the baseline: its values are exactly the original flight model.
 *
 * Top speed comes from drag, not a cap (terminal speed = thrust / (1 - drag)),
 * so it could be scaled from either side — but the two feel nothing alike.
 * Drag also governs how fast the craft sheds momentum, so buying speed by
 * thinning the drag makes better fuel coast further and wallow: faster and
 * mushier at once. The knob here is therefore a multiplier on the rotor's
 * horizontal drive, with drag held at the wood-tier value for every tier: the
 * deceleration curve stays identical all the way up, and better fuel simply
 * accelerates harder and tops out higher while still stopping crisply.
 *
 * The drive multipliers sit a little under the headline +22%/+50% because
 * powerMax adds horizontal drive of its own whenever the collective is up.
 *
 * Grade also sets how long the rotor takes to wind up from a standstill: a
 * hotter engine drags the disc up to governed RPM faster, so the scrap-fuel
 * craft that flies slower also takes noticeably longer to get going. Lift is
 * capped by rotor RPM, so this time is also the wait before the craft can break
 * ground on a cold start.
 *
 * Also sets the crash yield: the explosion when a piloted craft goes in hard
 * (creeper = 3, TNT = 4; an empty tank falls back to a small non-terrain pop),
 * and how hard the airframe can be struck before that happens — a grade that
 * cruises 40% faster would otherwise wreck itself on impacts it flies into as a
 * matter of course. The impact scaling deliberately trails the drive scaling a
 * little: better fuel is far more forgiving at any given speed, but not quite as
 * forgiving at its own top speed, so the fast craft is still the riskier one.
 */
public enum FuelTier
{
    //     torque yaw    powerMax drive  maxVel crash impact spool
    WOOD ( 1.00f, 1.00f, 1.35f,   1.00f, 1.5f,  2.0f, 1.00f, 3.5f),
    COAL ( 1.22f, 1.22f, 1.43f,   1.18f, 1.7f,  3.0f, 1.15f, 2.7f),
    BLOCK( 1.50f, 1.50f, 1.53f,   1.42f, 2.0f,  4.0f, 1.35f, 2.0f);

    /** Coal-equivalent burn ticks; also the WOOD/COAL threshold. */
    public static final int COAL_BURN_TICKS = 1600;
    /** Coal-block burn ticks; the COAL/BLOCK threshold. */
    public static final int BLOCK_BURN_TICKS = 16000;

    /** Multiplier on pitch/roll control torque. */
    public final float torqueMult;
    /** Multiplier on the yaw chase gain and its rate cap. */
    public final float yawMult;
    /** Full-collective rotor lift in units of gravity (WOOD = the old POWER_MAX). */
    public final float powerMax;
    /** Multiplier on the rotor's horizontal thrust; sets terminal cruise/dive speed. */
    public final float driveMult;
    /** Safety-net speed clamp (blocks/tick). */
    public final float maxVelocity;
    /** Explosion strength for a piloted crash on this tier. */
    public final float crashStrength;
    /** Multiplier on the impact speed the airframe survives (terrain and water alike). */
    public final float impactMult;
    /** Seconds for the rotor to wind up from a standstill to governed RPM. */
    public final float spoolSeconds;

    private FuelTier(float torqueMult, float yawMult, float powerMax,
                     float driveMult, float maxVelocity, float crashStrength,
                     float impactMult, float spoolSeconds)
    {
        this.torqueMult = torqueMult;
        this.yawMult = yawMult;
        this.powerMax = powerMax;
        this.driveMult = driveMult;
        this.maxVelocity = maxVelocity;
        this.crashStrength = crashStrength;
        this.impactMult = impactMult;
        this.spoolSeconds = spoolSeconds;
    }

    public static FuelTier classify(int burnTicks)
    {
        if (burnTicks >= BLOCK_BURN_TICKS) return BLOCK;
        if (burnTicks >= COAL_BURN_TICKS) return COAL;
        return WOOD;
    }

    /** Tier of a fuel item, or null if the stack isn't furnace fuel. */
    public static FuelTier ofItem(ItemStack stack)
    {
        int burn = stack == null ? 0 : TileEntityFurnace.getItemBurnTime(stack);
        return burn > 0 ? classify(burn) : null;
    }
}
