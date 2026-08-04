package com.theoxylo.thx.client.model;

/**
 * Rotor animation for the helicopter model. Ported from the 1.6.1
 * ThxModelHelicopterBase; the two former ThxConfig lookups are now constants
 * (rotor enabled; max speed = the old default of 70%).
 *
 * Both rotors advance at a rate proportional to {@link #rotorSpeed} -- the
 * governed rotor RPM (0..1) the entity feeds in each frame -- so the tail rotor
 * stays locked to the main rotor and neither slows with collective. The
 * spin-up / coast-down smoothing lives in that governed value, not here.
 */
public class ThxModelHelicopterBase extends ThxModel
{
    public boolean ENABLE_ROTOR = true;

    /** Blade angle, radians; the tail rotor turns the opposite way at the same rate. */
    public float mainRotorAngle = 0f;
    public float tailRotorAngle = 0f;

    /**
     * True when the model advances the blade angle itself, off {@link #rotorSpeed} and wall-clock
     * time. Right for the standalone viewer, which owns its one model; wrong in game, where a
     * single shared model instance renders every helicopter in view and would give them all one
     * angle, advanced at whichever craft happened to render first that frame. The in-game
     * renderer clears this and assigns the angle per entity instead.
     */
    public boolean selfAnimate = true;

    /** Governed rotor RPM, 0..1, set by the renderer each frame from the entity. */
    public float rotorSpeed = 0f;
    // was: 18f * ThxConfig.getIntProperty("rotor_speed_percent") / 100f  (default 70%)
    // in-game the same rate lives on the entity, ticked (see ThxEntityHelicopter.ROTOR_RAD_PER_TICK)
    public static final float MAX_ROTOR_SPEED = 18f * 0.70f;

    public void render()
    {
        update();

        if (!ENABLE_ROTOR)
        {
            // rotor disabled: park the blades as a fixed cross
            mainRotorAngle = 0.7854f;
            tailRotorAngle = 0.7854f;
        }
        else if (selfAnimate && !paused && rotorSpeed > 0f)
        {
            // main and tail rotor advance together, scaled by the same governed RPM
            mainRotorAngle += deltaTime * MAX_ROTOR_SPEED * rotorSpeed;
            tailRotorAngle -= deltaTime * MAX_ROTOR_SPEED * rotorSpeed;
            if (mainRotorAngle > 2 * PI) mainRotorAngle -= 2 * PI;
            if (tailRotorAngle < -2 * PI) tailRotorAngle += 2 * PI;
        }
        // otherwise (angle supplied by the renderer, paused, or rotorSpeed == 0): leave it alone
    }
}
