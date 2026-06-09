package com.theoxylo.thx.client.model;

import net.minecraft.util.MathHelper;

/**
 * Rotor animation for the helicopter model. Ported from the 1.6.1
 * ThxModelHelicopterBase; the two former ThxConfig lookups are now constants
 * (rotor enabled; max speed = the old default of 70%).
 */
public class ThxModelHelicopterBase extends ThxModel
{
    public boolean ENABLE_ROTOR = true;

    float mainRotorAngle = 0f;
    float mainRotorAnglePrev = 0f;
    float tailRotorAngle = 0f;
    float tailRotorAnglePrev = 0f;

    public float rotorSpeed = 0f;
    // was: 18f * ThxConfig.getIntProperty("rotor_speed_percent") / 100f  (default 70%)
    float MAX_ROTOR_SPEED = 18f * 0.70f;

    float timeSpun = 0f;
    float SPIN_UP_TIME = 10f;

    public void render()
    {
        update();

        if (ENABLE_ROTOR && !paused)
        {
            if (rotorSpeed > 0f)
            {
                if (timeSpun < SPIN_UP_TIME)
                {
                    timeSpun += deltaTime * 3f; // spin up faster than spin down

                    mainRotorAngle += deltaTime * MAX_ROTOR_SPEED * rotorSpeed * timeSpun / SPIN_UP_TIME;
                    tailRotorAngle -= deltaTime * MAX_ROTOR_SPEED * timeSpun / SPIN_UP_TIME; // not linked to throttle
                }
                else
                {
                    mainRotorAngle += deltaTime * MAX_ROTOR_SPEED * rotorSpeed;
                    tailRotorAngle -= deltaTime * MAX_ROTOR_SPEED;
                }
                if (mainRotorAngle > 2 * PI) mainRotorAngle -= 2 * PI;
                if (tailRotorAngle < 2 * PI) tailRotorAngle += 2 * PI;
            }
            else
            {
                rotorSpeed = 0f;

                if (timeSpun > 0f)
                {
                    timeSpun -= deltaTime;

                    mainRotorAngle += deltaTime * MAX_ROTOR_SPEED * (1 - MathHelper.cos(timeSpun / SPIN_UP_TIME));
                    tailRotorAngle += deltaTime * MAX_ROTOR_SPEED * (1 - MathHelper.cos(timeSpun / SPIN_UP_TIME));

                    mainRotorAnglePrev = mainRotorAngle;
                    tailRotorAnglePrev = tailRotorAngle;
                }
                else
                {
                    mainRotorAngle = mainRotorAnglePrev;
                    tailRotorAngle = tailRotorAnglePrev;
                }
            }
        }
        else
        {
            // show fixed rotor by rendering twice
            mainRotorAngle = 0.7854f;
            tailRotorAngle = 0.7854f;
        }
    }
}
