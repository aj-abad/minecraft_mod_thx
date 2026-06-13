package com.theoxylo.thx.client.render;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
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
        model.rotationRoll  = lerpAngle(heli.prevRotationRoll, heli.rotationRoll, partialTicks);

        // rotor visual speed is proportional to rotor power (synced for spectators), so a
        // drowned or abandoned craft's rotor visibly winds down to a stop
        model.rotorSpeed = 1.05f * heli.rotorPower / ThxEntityHelicopter.POWER_MAX;
        model.render();

        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity)
    {
        return TEXTURE;
    }

    /** Shortest-path angle interpolation (handles the 180/-180 wrap). */
    private static float lerpAngle(float prev, float now, float t)
    {
        float d = now - prev;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return prev + d * t;
    }
}
