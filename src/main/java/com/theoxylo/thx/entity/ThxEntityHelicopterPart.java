package com.theoxylo.thx.entity;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;

/**
 * One solid slab of a parked helicopter, handed to vanilla through
 * {@link ThxEntityHelicopter#getParts()}.
 *
 * These are the ender dragon's trick: entities that live only in their owner's
 * fields, never spawned into the world, never saved and never synced. Vanilla's
 * chunk lookup walks an entity's parts whenever the entity itself is caught by
 * an AABB query, and {@link net.minecraft.world.World#getCollidingBoundingBoxes}
 * then takes each part's {@link #getBoundingBox()} as a wall — so a couple of
 * these give a parked craft a cabin and a tail boom to bump into, instead of one
 * cube swallowing the whole airframe. Both sides build their own copies off the
 * craft's replicated position and heading, so none of it costs a packet.
 *
 * A slab is a craft-local box: a span along the nose axis, a half-width either
 * side of it and a height range, all in blocks from the entity origin. Its world
 * box is the axis-aligned hull of that rectangle at the current heading (see
 * {@link #follow}), which is why the slabs fatten a little on the diagonals.
 *
 * Deliberately inert otherwise: not collidable, since clicks and arrows should
 * keep going to the craft — only it has an entity id the server can resolve —
 * and not pushable, since the craft reads contact off these boxes itself.
 */
public class ThxEntityHelicopterPart extends Entity
{
    private final ThxEntityHelicopter craft;

    /** Craft-local extents in blocks: the nose-axis span this slab covers... */
    private final float fwdMin, fwdMax;
    /** ...how far it reaches to either side of the centreline... */
    private final float halfLat;
    /** ...and its height range about the entity origin. */
    private final float yMin, yMax;

    ThxEntityHelicopterPart(ThxEntityHelicopter craft, float fwdMin, float fwdMax,
                            float halfLat, float yMin, float yMax)
    {
        super(craft.worldObj);
        this.craft = craft;
        this.fwdMin = fwdMin;
        this.fwdMax = fwdMax;
        this.halfLat = halfLat;
        this.yMin = yMin;
        this.yMax = yMax;
        setSize(halfLat * 2f, yMax - yMin);
    }

    /** Re-seat the slab on the craft's current position and heading. */
    void follow(float sinYaw, float cosYaw)
    {
        float mid = (fwdMin + fwdMax) * 0.5f;
        float halfLen = (fwdMax - fwdMin) * 0.5f;
        // the nose axis is (-sin, cos) and the lateral one (-cos, -sin); the box around the
        // rotated rectangle is each half-extent projected onto both world axes
        posX = craft.posX - mid * sinYaw;
        posY = craft.posY;
        posZ = craft.posZ + mid * cosYaw;
        double halfX = halfLen * Math.abs(sinYaw) + halfLat * Math.abs(cosYaw);
        double halfZ = halfLen * Math.abs(cosYaw) + halfLat * Math.abs(sinYaw);
        boundingBox.setBounds(posX - halfX, posY + yMin, posZ - halfZ,
                              posX + halfX, posY + yMax, posZ + halfZ);
    }

    /** The wall other entities collide with — the whole point of these. */
    @Override
    public AxisAlignedBB getBoundingBox() { return boundingBox; }

    /** Clicks, arrows and mob targeting keep going to the craft's own box. */
    @Override
    public boolean canBeCollidedWith() { return false; }

    /** The craft handles shoves itself, so vanilla's would only double up. */
    @Override
    public boolean canBePushed() { return false; }

    @Override
    public boolean isEntityEqual(Entity other) { return this == other || craft == other; }

    @Override
    protected void entityInit() {}

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {}

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {}
}
