package com.theoxylo.thx.client.model;

import net.minecraft.client.model.ModelBase;

import org.lwjgl.opengl.GL11;

/**
 * Base for the THX code-built entity models. Ported from the 1.6.1 ThxModel,
 * decoupled from the old file-based ThxConfig so it can be instantiated outside
 * Minecraft (e.g. the standalone model viewer). Geometry/transform logic is
 * unchanged from the original.
 */
public abstract class ThxModel extends ModelBase
{
    static final float RAD_PER_DEG = 0.01745329f;
    static final float PI          = 3.14159265f;

    public boolean visible = true;
    public boolean paused;

    float deltaTime;
    long  prevTime;
    public long entityPrevTime;

    public float rotationYaw;
    public float rotationYawSpeed;
    public float rotationPitch;
    public float rotationPitchSpeed;
    public float rotationRoll;
    public float rotationRollSpeed;

    /** Texture id (e.g. "thx:textures/entity/helicopter.png"); used by the in-game renderer. */
    public String renderTexture;

    int updateCount;

    /** deltaTime-based smoothing of rotation, then applies the model-space GL transform. */
    public void update()
    {
        updateCount++;

        long time = System.nanoTime();
        deltaTime = ((float) (time - prevTime)) / 1000000000f; // ns -> sec
        if (deltaTime > .03f) deltaTime = .03f; // ~30 fps floor when not rendered for a while

        if (paused)
        {
            // hold
        }
        else if (entityPrevTime > prevTime)
        {
            float adjustedDeltaTime = ((float) (time - entityPrevTime)) / 1000000000f;
            rotationYaw   += rotationYawSpeed   * adjustedDeltaTime;
            rotationPitch += rotationPitchSpeed * adjustedDeltaTime;
            rotationRoll  += rotationRollSpeed  * adjustedDeltaTime;
        }
        else
        {
            rotationYaw   += rotationYawSpeed   * deltaTime;
            rotationPitch += rotationPitchSpeed * deltaTime;
            rotationRoll  += rotationRollSpeed  * deltaTime;
        }
        prevTime = time;

        GL11.glRotatef(-90f - rotationYaw, 0.0f, 1.0f, 0.0f);
        GL11.glRotatef(-rotationPitch, 0.0f, 0.0f, 1.0f);
        GL11.glRotatef(-rotationRoll, 1.0f, 0.0f, 0.0f);
        GL11.glScalef(-1f, -1f, 1f); // model space (Y-down) -> world space (Y-up)
    }

    public abstract void render();
}
