package com.theoxylo.thx.client.render;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.theoxylo.thx.Reference;
import com.theoxylo.thx.client.model.ThxModelHelicopter;
import com.theoxylo.thx.entity.ThxEntityHelicopter;

/**
 * Entity renderer for the helicopter. Ported from the 1.6.1 ThxRender with the
 * deobf method names ({@code func_110775_a} -> getEntityTexture,
 * {@code func_110776_a} -> bindTexture) and without the old sided-helper model
 * plumbing: one shared model instance, like a vanilla entity renderer.
 */
public class ThxRender extends Render
{
    private static final ResourceLocation TEXTURE =
        new ResourceLocation(Reference.MODID, "textures/entity/helicopter.png");

    private final ThxModelHelicopter model = new ThxModelHelicopter();

    public ThxRender()
    {
        // this one model draws every helicopter in view, so it can't accumulate the blade angle
        // itself -- doRender supplies it per entity below
        model.selfAnimate = false;
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTicks)
    {
        ThxEntityHelicopter heli = (ThxEntityHelicopter) entity;

        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);

        bindTexture(TEXTURE);

        // interpolate rotation between ticks so the model is smooth at render FPS
        model.rotationYaw   = lerpAngle(heli.prevRotationYaw, heli.rotationYaw, partialTicks);
        model.rotationPitch = heli.prevRotationPitch + (heli.rotationPitch - heli.prevRotationPitch) * partialTicks;
        // a punch rocks the airframe about its nose axis on top of the flown attitude, which is
        // what roll is here — the shake is render-only, so the collision box never follows it
        model.rotationRoll  = lerpAngle(heli.prevRotationRoll, heli.rotationRoll, partialTicks)
                            + hitShake(heli, partialTicks);

        // the blades turn at the governed rotor RPM (0..1): spooling up at startup, holding while the
        // engine runs (so descending doesn't slow them) and winding down to a stop when it stops. The
        // angle is integrated on the entity, one per craft, so a second helicopter in view can't drag
        // this one's rotor around with it; here it's just interpolated like the airframe attitude.
        model.rotorSpeed     = heli.prevRotorSpeed + (heli.rotorSpeed - heli.prevRotorSpeed) * partialTicks;
        model.mainRotorAngle = lerpRadians(heli.prevRotorAngle, heli.rotorAngle, partialTicks);
        model.tailRotorAngle = -model.mainRotorAngle; // tail rotor turns the other way at the same rate
        model.render();

        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity)
    {
        return TEXTURE;
    }

    /**
     * RenderBoat's punch rock, degrees: a wobble that decays over the ten ticks after a hit,
     * sized by the damage banked so far (so the last blow before break-up rocks hardest) and
     * swinging the opposite way each hit.
     */
    private static float hitShake(ThxEntityHelicopter heli, float partialTicks)
    {
        float ticks = heli.getTimeSinceHit() - partialTicks;
        if (ticks <= 0f) return 0f;
        float damage = heli.getDamageTaken() - partialTicks;
        if (damage < 0f) damage = 0f;
        return MathHelper.sin(ticks) * ticks * damage / 10f * heli.getForwardDirection();
    }

    /** Shortest-path angle interpolation (handles the 180/-180 wrap). */
    private static float lerpAngle(float prev, float now, float t)
    {
        float d = now - prev;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return prev + d * t;
    }

    /** Same, in radians, for the blade angle the entity wraps at 2*pi. A tick of rotor never covers
     *  more than ~0.63 rad, so the shortest path is always the way the blades are actually turning. */
    private static float lerpRadians(float prev, float now, float t)
    {
        final float TWO_PI = 6.28318531f;
        float d = now - prev;
        while (d > TWO_PI / 2f) d -= TWO_PI;
        while (d < -TWO_PI / 2f) d += TWO_PI;
        return prev + d * t;
    }
}
