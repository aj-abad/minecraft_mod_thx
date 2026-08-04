package com.theoxylo.thx.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Attaches looping rotor sound to every nearby helicopter and lets it wind down
 * on its own. Each craft gets two cross-fading layers (slow + fast; see
 * {@link HelicopterSound}) that read the craft's {@code rotorSpeed} each tick, so
 * the pilot's predicted craft and spectator craft all sound right. The layers
 * self-terminate when the craft dies, unloads, changes
 * dimension, or the rotor fully stops -- so this handler only has to (re)start
 * them.
 *
 * Client-only, registered on the FML bus alongside {@link ClientInputHandler}.
 */
public class ClientSoundHandler
{
    /** Governed rotor RPM (0..1) above which the rotor is turning enough to start the loop. */
    static final float START_SPEED = 0.02f;

    /** ...and below which it has wound down: the loop ends and may restart on the next spin-up
     *  (kept under {@link #START_SPEED} for hysteresis, so it doesn't flicker at the boundary). */
    static final float STOP_SPEED = 0.01f;

    /** Helicopters (by entity id) that currently have their sound playing, so we don't double-start. */
    private final Set<Integer> active = new HashSet<Integer>();

    /** Last world seen, to forget everything on a world/dimension change (the sound engine is reset too). */
    private World lastWorld;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        if (world != lastWorld) { active.clear(); lastWorld = world; }
        if (world == null) return;

        SoundHandler sh = mc.getSoundHandler();

        // start the two layers on any helicopter whose rotor has spun up and isn't already sounding
        List entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++)
        {
            Object o = entities.get(i);
            if (!(o instanceof ThxEntityHelicopter)) continue;
            ThxEntityHelicopter heli = (ThxEntityHelicopter) o;
            int id = heli.getEntityId();
            if (active.contains(id)) continue;
            if (heli.isDead || heli.rotorSpeed <= START_SPEED) continue; // wait for the rotor to spin up
            sh.playSound(new HelicopterSound(heli, false)); // slow layer
            sh.playSound(new HelicopterSound(heli, true));  // fast layer
            active.add(id);
        }

        // drop craft whose sound has ended (dead / unloaded / rotor stopped) so a later spin-up restarts it
        Iterator<Integer> it = active.iterator();
        while (it.hasNext())
        {
            Entity e = world.getEntityByID(it.next());
            if (!(e instanceof ThxEntityHelicopter) || e.isDead
                || ((ThxEntityHelicopter) e).rotorSpeed <= STOP_SPEED)
            {
                it.remove();
            }
        }
    }
}
