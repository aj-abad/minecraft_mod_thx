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

        model.rotationYaw   = heli.rotationYaw;
        model.rotationPitch = heli.rotationPitch;
        model.rotationRoll  = heli.rotationRoll;

        // rotor speed tracks throttle while occupied; still when parked/empty
        if (heli.riddenByEntity != null)
        {
            float power = (heli.throttle - ThxEntityHelicopter.THROTTLE_MIN)
                / (ThxEntityHelicopter.THROTTLE_MAX - ThxEntityHelicopter.THROTTLE_MIN);
            model.rotorSpeed = power / 2f + 0.75f;
        }
        else
        {
            model.rotorSpeed = 0f;
        }
        model.render();

        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity)
    {
        return TEXTURE;
    }
}
