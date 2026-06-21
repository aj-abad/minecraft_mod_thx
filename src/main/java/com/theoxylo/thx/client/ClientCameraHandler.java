package com.theoxylo.thx.client;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.player.EntityPlayer;

import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Glues the pilot's first-person view to the helicopter's attitude -- banking
 * with roll and tilting with pitch -- the way Flan's Mod sticks the camera to a
 * rolling/pitching plane.
 *
 * ROLL uses Minecraft's dormant view-roll: {@code EntityRenderer.camRoll} is
 * applied as a Z-axis (screen-plane) rotation at the start of {@code orientCamera}
 * and interpolated by {@code prevCamRoll}; vanilla 1.7.10 never drives it. We set
 * it each client tick.
 *
 * PITCH has no camera field -- the view pitch IS the player's look pitch
 * ({@code rotationPitch}). The craft's yaw already follows the pilot's look, so we
 * keep that freedom for pitch too: instead of locking the view to the nose we ADD
 * the nose pitch as a bias on top of the player's own look (free look still works).
 * Each tick we nudge {@code rotationPitch} by only the CHANGE in that bias (tracked
 * in {@link #appliedPitch}) and leave {@code prevRotationPitch} alone. That leans on
 * two vanilla facts: the per-tick entity snapshot ({@code prevRotationPitch =
 * rotationPitch}) absorbs the bias each tick, and {@code setAngles} shifts prev/cur
 * together for mouse look -- so the injected {@code cur - prev} delta eases in over
 * exactly one tick (in lockstep with the model's own pitch easing in ThxRender) and
 * rides on top of vanilla's per-frame mouse look without fighting it.
 *
 * Both run at {@code ClientTickEvent} END. {@code camRoll} is private (reflection:
 * SRG name in production, deobf in dev); if that fails the roll quietly disables and
 * the model still banks. Client-only, like {@link ClientInputHandler}.
 */
public class ClientCameraHandler
{
    /**
     * Camera roll as a multiple of the craft's roll angle. {@code -1} = full 1:1
     * bank (Flan's-style); scale toward 0 to soften, flip the sign to reverse.
     */
    private static final float BANK_FACTOR = -1.0f;

    /**
     * View-pitch bias as a multiple of the craft's nose pitch. {@code 1} = full
     * (the view tilts the whole way down/up with the nose); scale toward 0 to
     * soften, {@code 0} disables pitch coupling, flip the sign to reverse.
     */
    private static final float PITCH_FACTOR = 1.0f;

    private Field camRollField;
    private boolean resolved;

    /** Are we currently coupling the view (so we know to undo it exactly once on release)? */
    private boolean active;
    /** Nose-pitch bias currently baked into the player's view pitch, so we can adjust/remove it. */
    private float appliedPitch;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        // END phase: after EntityRenderer.updateRenderer's prevCamRoll = camRoll capture, so
        // camRoll interpolates smoothly; the pitch bias is independent of that timing.
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        boolean piloting = player != null
            && player.ridingEntity instanceof ThxEntityHelicopter
            && mc.gameSettings.thirdPersonView == 0; // only the first-person view; third stays free

        if (piloting)
        {
            ThxEntityHelicopter heli = (ThxEntityHelicopter) player.ridingEntity;

            // ROLL -> dormant screen-plane camera roll.
            setCamRoll(mc, heli.rotationRoll * BANK_FACTOR);

            // PITCH -> bias the player's look by the nose pitch. Nudge by the delta only and
            // leave prevRotationPitch to the entity snapshot, so the change eases like the model.
            float target = heli.rotationPitch * PITCH_FACTOR;
            player.rotationPitch += target - appliedPitch;
            appliedPitch = target;

            active = true;
        }
        else if (active)
        {
            // Released, or switched to third person: undo our coupling once.
            setCamRoll(mc, 0f);
            if (player != null) player.rotationPitch -= appliedPitch;
            appliedPitch = 0f;
            active = false;
        }
    }

    private void setCamRoll(Minecraft mc, float value)
    {
        Field f = camRollField();
        if (f == null) return;
        try
        {
            f.setFloat(mc.entityRenderer, value);
        }
        catch (Exception e)
        {
            FMLLog.warning("[THX] camera bank disabled: couldn't set EntityRenderer.camRoll (%s)", e);
            camRollField = null; // stop retrying every tick
        }
    }

    /** Resolve EntityRenderer.camRoll once: SRG name (obfuscated runtime) then the deobf name (dev). */
    private Field camRollField()
    {
        if (!resolved)
        {
            resolved = true;
            try
            {
                camRollField = ReflectionHelper.findField(EntityRenderer.class, "field_78495_O", "camRoll");
            }
            catch (Throwable t)
            {
                FMLLog.warning("[THX] camera bank disabled: couldn't find EntityRenderer.camRoll (%s)", t);
                camRollField = null;
            }
        }
        return camRollField;
    }
}
