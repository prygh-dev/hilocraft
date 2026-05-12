package com.example.examplemod;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent; // Added for testing
import org.apache.logging.log4j.Logger;

// Import the Cubic Chunks API
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.world.World;

@Mod(modid = ExampleMod.MODID, name = ExampleMod.NAME, version = ExampleMod.VERSION)
public class ExampleMod
{
    public static final String MODID = "examplemod";
    public static final String NAME = "Example Mod";
    public static final String VERSION = "1.0";

    private static Logger logger;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
    }

    // This is the best place to test, as we need a World object to check
    @EventHandler
    public void serverStarting(FMLServerStartingEvent event)
    {
        World world = event.getServer().getEntityWorld();

        // The core test: Check if the world is an instance of ICubicWorld
        if (world instanceof ICubicWorld) {
            ICubicWorld cubicWorld = (ICubicWorld) world;

            if (cubicWorld.isCubicWorld()) {
                logger.info("CUBIC CHUNKS DETECTED! Max Height: {}", cubicWorld.getMaxHeight());
            } else {
                logger.info("Cubic Chunks API present, but this world is NOT a Cubic World.");
            }
        } else {
            logger.error("CRITICAL: Cubic Chunks API is NOT working. Is the CoreMod loaded?");
        }

        event.registerServerCommand(new CommandWriteCCblockdata()); //not needed?

        event.registerServerCommand(new CommandLoadBinary());
        event.registerServerCommand(new CommandBanyan());
        event.registerServerCommand(new CommandPathPaint());
        event.registerServerCommand(new CommandTree());

    }
}