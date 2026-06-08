package com.theoxylo.thx.proxy;

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
    }

    @Override
    public void registerRenderers()
    {
        // TODO Phase 4: RenderingRegistry.registerEntityRenderingHandler(
        //     ThxEntityHelicopter.class, new ThxRender());  (+ rocket, missile)
    }

    @Override
    public void registerKeyBindings()
    {
        // TODO Phase 6: ClientRegistry.registerKeyBinding(...) for flight controls,
        //     plus an InputEvent.KeyInputEvent / client-tick handler.
    }
}
