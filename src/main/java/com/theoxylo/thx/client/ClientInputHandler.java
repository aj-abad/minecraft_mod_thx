package com.theoxylo.thx.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;

import com.theoxylo.thx.entity.ThxEntityHelicopter;
import com.theoxylo.thx.network.HelicopterInputMessage;
import com.theoxylo.thx.network.ThxNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Reads the pilot's control keys each client tick and sends the control bitmask
 * to the server (only when it changes). This is the ONLY place with client-only
 * (Minecraft/LWJGL) references; the entity stays server-safe.
 *
 * Pitch (W/S), roll (A/D) and ascend (Jump) read the player's VANILLA movement
 * bindings directly, so flight uses whatever those are bound to and walking
 * still works; only descend is a dedicated {@link ThxKeyBindings} key (default
 * X). Yaw follows where you look. Shift still sneak-dismounts (vanilla).
 */
public class ClientInputHandler
{
    private int lastKeys = -1;
    private ThxEntityHelicopter controlled; // the helicopter we're currently predicting

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        ThxEntityHelicopter heli = (player != null && player.ridingEntity instanceof ThxEntityHelicopter)
            ? (ThxEntityHelicopter) player.ridingEntity : null;

        // released a helicopter we used to fly -> hand it back to tracker interpolation
        if (controlled != null && controlled != heli)
        {
            controlled.clientControlled = false;
            controlled = null;
        }

        if (heli == null)
        {
            lastKeys = -1;
            return;
        }

        int keys = 0;
        if (mc.currentScreen == null) // don't fly while a GUI/chat is open
        {
            // Pitch/roll/ascend reuse the vanilla movement bindings (see ThxKeyBindings
            // for why a separate W/A/S/D/Space binding would break walking); descend is
            // our own non-conflicting key.
            GameSettings gs = mc.gameSettings;
            if (gs.keyBindForward.getIsKeyPressed()) keys |= 1;
            if (gs.keyBindBack.getIsKeyPressed())    keys |= 2;
            if (gs.keyBindLeft.getIsKeyPressed())    keys |= 4;
            if (gs.keyBindRight.getIsKeyPressed())   keys |= 8;
            if (gs.keyBindJump.getIsKeyPressed())    keys |= 16;
            if (ThxKeyBindings.descend.getIsKeyPressed()) keys |= 32;
        }

        // drive the local prediction, and mirror the input to the server
        heli.clientControlled = true;
        heli.inputKeys = keys;
        controlled = heli;

        if (keys != lastKeys)
        {
            ThxNetwork.CHANNEL.sendToServer(new HelicopterInputMessage(heli.getEntityId(), (byte) keys));
            lastKeys = keys;
        }
    }
}
