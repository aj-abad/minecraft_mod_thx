package com.theoxylo.thx.network;

import com.theoxylo.thx.Reference;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The mod's network channel. Replaces the old ModLoader Packet250 plumbing with
 * FML's {@link SimpleNetworkWrapper}. Currently carries one message: the pilot's
 * control input (client -> server).
 */
public final class ThxNetwork
{
    public static SimpleNetworkWrapper CHANNEL;

    public static void init()
    {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MODID);
        // discriminator 0; handled on the SERVER (sent from the controlling client)
        CHANNEL.registerMessage(HelicopterInputMessage.Handler.class, HelicopterInputMessage.class, 0, Side.SERVER);
    }

    private ThxNetwork() {}
}
