package com.theoxylo.thx.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.input.Keyboard;

import com.theoxylo.thx.entity.ThxEntityHelicopter;
import com.theoxylo.thx.network.HelicopterInputMessage;
import com.theoxylo.thx.network.ThxNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Reads the pilot's raw keys each client tick and sends the control bitmask to
 * the server (only when it changes). This is the ONLY place with client-only
 * (Minecraft/LWJGL) references; the entity stays server-safe.
 *
 * Controls (defaults; configurable later): W/S pitch, A/D roll, Space ascend,
 * X descend. Yaw follows where you look. Shift still sneak-dismounts (vanilla).
 */
public class ClientInputHandler
{
    private static final int KEY_FORWARD = Keyboard.KEY_W;
    private static final int KEY_BACK = Keyboard.KEY_S;
    private static final int KEY_LEFT = Keyboard.KEY_A;
    private static final int KEY_RIGHT = Keyboard.KEY_D;
    private static final int KEY_ASCEND = Keyboard.KEY_SPACE;
    private static final int KEY_DESCEND = Keyboard.KEY_X;

    private int lastKeys = -1;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || !(player.ridingEntity instanceof ThxEntityHelicopter))
        {
            lastKeys = -1;
            return;
        }

        int keys = 0;
        if (mc.currentScreen == null) // don't fly while a GUI/chat is open
        {
            if (Keyboard.isKeyDown(KEY_FORWARD)) keys |= 1;
            if (Keyboard.isKeyDown(KEY_BACK))    keys |= 2;
            if (Keyboard.isKeyDown(KEY_LEFT))    keys |= 4;
            if (Keyboard.isKeyDown(KEY_RIGHT))   keys |= 8;
            if (Keyboard.isKeyDown(KEY_ASCEND))  keys |= 16;
            if (Keyboard.isKeyDown(KEY_DESCEND)) keys |= 32;
        }

        if (keys != lastKeys)
        {
            ThxNetwork.CHANNEL.sendToServer(new HelicopterInputMessage(player.ridingEntity.getEntityId(), (byte) keys));
            lastKeys = keys;
        }
    }
}
