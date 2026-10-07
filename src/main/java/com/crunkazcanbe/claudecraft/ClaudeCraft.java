package com.crunkazcanbe.claudecraft;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * ClaudeCraft — a client-only Forge 1.12.2 mod that opens a localhost TCP socket
 * so Claude (or any local tool) can drive the player precisely: set look angles
 * directly (no cursor-warp fight), hold movement keys, click/place blocks, switch
 * hotbar slots, send chat/commands, and read game state back.
 *
 * No access transformers, so it can't trip the Baritone-style AT crash. The only
 * reflection is ReflectionHelper on GuiScreen (button list, actionPerformed,
 * keyTyped) for driving open menus.
 */
@Mod(modid = ClaudeCraft.MODID,
     name = ClaudeCraft.NAME,
     version = ClaudeCraft.VERSION,
     clientSideOnly = true,
     acceptableRemoteVersions = "*")
public class ClaudeCraft {
    public static final String MODID   = "claudecraft";
    public static final String NAME    = "ClaudeCraft";
    public static final String VERSION = "0.1.0";

    public static Logger LOG;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ChatLog());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(CreativeSweep.INSTANCE);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new CommandServer.PendingWorld());
        CommandServer.start();
    }
}
