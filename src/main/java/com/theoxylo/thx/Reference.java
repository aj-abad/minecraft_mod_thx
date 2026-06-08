package com.theoxylo.thx;

/**
 * Compile-time constants for the mod. Keep the modid lowercase and stable: it
 * is also the asset namespace (assets/thx/...) and the entity/registry prefix.
 */
public final class Reference
{
    public static final String MODID = "thx";
    public static final String NAME = "THX Helicopter Mod";
    public static final String VERSION = "0.26.0";
    public static final String ACCEPTED_MC_VERSIONS = "[1.7.10]";

    // Fully-qualified proxy class names for @SidedProxy.
    public static final String CLIENT_PROXY = "com.theoxylo.thx.proxy.ClientProxy";
    public static final String SERVER_PROXY = "com.theoxylo.thx.proxy.CommonProxy";

    private Reference() {}
}
