package com.theoxylo.thx.proxy;

import com.theoxylo.thx.client.ClientCameraHandler;
import com.theoxylo.thx.client.ClientInputHandler;
import com.theoxylo.thx.client.ThxKeyBindings;
import com.theoxylo.thx.client.render.ThxRender;
import com.theoxylo.thx.entity.ThxEntityHelicopter;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * Client-only proxy. The safe home for rendering, key bindings, and any
 * net.minecraft.client.* / LWJGL references that must not load on a dedicated
 * server.
 */
public class ClientProxy extends CommonProxy
{
    @Override
    public void init(FMLInitializationEvent event)
    {
        super.init(event);
        registerRenderers();
        registerKeyBindings();
        // Flight input: poll the pilot's keys each client tick and send to the server.
        FMLCommonHandler.instance().bus().register(new ClientInputHandler());
        // First-person view banks with the helicopter's roll (Flan's-style).
        FMLCommonHandler.instance().bus().register(new ClientCameraHandler());
    }

    @Override
    public void registerRenderers()
    {
        RenderingRegistry.registerEntityRenderingHandler(ThxEntityHelicopter.class, new ThxRender());
    }

    @Override
    public void registerKeyBindings()
    {
        // Flight controls -> Options/Controls (the client-tick poll lives in ClientInputHandler).
        ThxKeyBindings.register();
    }
}
