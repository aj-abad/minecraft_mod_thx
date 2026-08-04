package com.theoxylo.thx.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import com.theoxylo.thx.Reference;
import com.theoxylo.thx.entity.ThxEntityHelicopter;

/**
 * One looping rotor-sound layer bound to a helicopter, its volume and pitch
 * driven each tick by the craft's {@code rotorSpeed} (governed rotor RPM, 0..1,
 * decoupled from collective so descending doesn't slow it). Two layers per helicopter
 * cross-fade: the "slow" layer fades in from a standstill and pitches up as the
 * rotor spools, then fades out as the "fast" layer fades in near full speed (see
 * the envelope in {@link #update()}).
 *
 * The two layer volumes sum to ~{@link #PEAK_VOLUME} across the hand-off, so the loudness stays
 * steady through the cross-fade -- no dip. Every layer is kept strictly above
 * zero for its whole life: 1.7.10's SoundManager skips a zero-volume sound at
 * play time and can cull one to zero when a category slider moves, either of
 * which would silence the layer for good.
 *
 * Client-only (net.minecraft.client.audio); started/stopped by
 * {@link ClientSoundHandler}.
 */
class HelicopterSound extends MovingSound
{
    /**
     * Per-layer peak volume; the two layers sum to ~this at the cross-fade, so keep headroom under 1.0.
     * Held ~6 dB under a full-scale 0.9 so the rotors sit under the rest of the mix rather than on top of it.
     */
    private static final float PEAK_VOLUME = 0.45f;

    /** Volume floor kept on every layer for its whole life (see the class note on zero-volume culling). */
    private static final float VOLUME_FLOOR = 0.02f;

    private final ThxEntityHelicopter heli;
    private final boolean fast;

    HelicopterSound(ThxEntityHelicopter heli, boolean fast)
    {
        super(new ResourceLocation(Reference.MODID, fast ? "heli_fast" : "heli_slow"));
        this.heli = heli;
        this.fast = fast;
        this.repeat = true;          // loop for as long as the craft runs
        this.field_147665_h = 0;     // repeat delay: no gap between loops
        this.volume = VOLUME_FLOOR;  // > 0 so playSound doesn't skip us on the first frame
        // field_147666_i stays LINEAR: attenuate with distance like a normal world sound
        updatePosition();
    }

    @Override
    public void update()
    {
        Minecraft mc = Minecraft.getMinecraft();
        // end the loop when the craft is gone (dead, unloaded, or a dimension away) or fully wound down
        if (mc.theWorld == null || heli.isDead || heli.worldObj != mc.theWorld
            || heli.rotorSpeed <= ClientSoundHandler.STOP_SPEED)
        {
            donePlaying = true;
            return;
        }

        float s = heli.rotorSpeed; // governed rotor RPM, already 0..1
        if (s < 0f) s = 0f; else if (s > 1f) s = 1f;

        // spool: the slow layer swells from silence as the rotor first turns.
        // blend: the hand-off from slow to fast as the rotor nears full speed.
        float spool = smoothstep(0.02f, 0.20f, s);
        float blend = smoothstep(0.45f, 0.85f, s);

        float vol;
        if (fast)
        {
            vol = blend * PEAK_VOLUME;
            field_147663_c = 0.90f + 0.22f * s; // steady loop; only a slight rise as the rotor spools to full
        }
        else
        {
            vol = spool * (1f - blend) * PEAK_VOLUME; // fade in from a stop, fade out into the fast layer
            field_147663_c = 0.70f + 0.85f * s;       // pitch up as the rotor speeds up
        }

        // dry-tank sputter: the engine note goes ragged — the pitch sags and the volume
        // stutters (two incommensurate sines, so the misfires don't sound metronomic)
        if (heli.isEngineSputtering())
        {
            float wob = MathHelper.sin(heli.ticksExisted * 0.9f) * 0.5f
                      + MathHelper.sin(heli.ticksExisted * 2.3f) * 0.5f;
            field_147663_c *= 0.90f + 0.08f * wob;
            vol *= 0.75f + 0.25f * wob;
        }

        this.volume = vol < VOLUME_FLOOR ? VOLUME_FLOOR : vol;

        updatePosition();
    }

    private void updatePosition()
    {
        this.xPosF = (float) heli.posX;
        this.yPosF = (float) heli.posY;
        this.zPosF = (float) heli.posZ;
    }

    /** Hermite ramp: 0 at edge0, 1 at edge1, flat outside -- a soft, click-free fade. */
    private static float smoothstep(float edge0, float edge1, float x)
    {
        float t = (x - edge0) / (edge1 - edge0);
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }
}
