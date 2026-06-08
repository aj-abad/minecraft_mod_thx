package com.theoxylo.thx;

import org.apache.logging.log4j.Logger;

import com.theoxylo.thx.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Main mod entry point. Replaces the old {@code mod_thx extends BaseMod} /
 * ModLoader bootstrap. Registration is split across the FML lifecycle and the
 * {@link CommonProxy}/ClientProxy pair so the dedicated server never touches
 * client-only classes.
 */
@Mod(
    modid = Reference.MODID,
    name = Reference.NAME,
    version = Reference.VERSION,
    acceptedMinecraftVersions = Reference.ACCEPTED_MC_VERSIONS
)
public class ModThx
{
    @Instance(Reference.MODID)
    public static ModThx instance;

    @SidedProxy(clientSide = Reference.CLIENT_PROXY, serverSide = Reference.SERVER_PROXY)
    public static CommonProxy proxy;

    public static Logger log;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        log = event.getModLog();
        log.info("preInit " + Reference.NAME + " v" + Reference.VERSION);

        // TODO Phase 2: register the helicopter item + crafting recipe (GameRegistry).
        // TODO Phase 3: register entities (EntityRegistry.registerModEntity).
        // TODO Phase 5: create the SimpleNetworkWrapper channel + message handlers.

        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        log.info("init");

        // TODO Phase 4: entity renderers (registered in ClientProxy).
        // TODO Phase 6: key bindings + client tick handler (registered in ClientProxy).

        proxy.init(event);
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event)
    {
        log.info("postInit");
        proxy.postInit(event);
    }
}
