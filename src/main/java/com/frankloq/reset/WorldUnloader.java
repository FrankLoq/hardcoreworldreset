package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;

import java.util.List;

public class WorldUnloader {

    // The three dimensions we want to reset.
    public static final List<RegistryKey<World>> RESET_DIMENSIONS = List.of(
            World.OVERWORLD,
            World.NETHER,
            World.END
    );

    // Saves all chunks in all three target worlds and
    // forces them to be written to disk.
    // This MUST be called on the main server thread.
    // @param server the running MinecraftServer instance
    public static void saveAndUnloadWorlds(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Beginning world save and unload...");

        for (RegistryKey<World> key : RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);

            if (world == null) {
                HardcoreWorldReset.LOGGER.warn(
                        "World {} was null during unload, skipping.",
                        key.getValue()
                );
                continue;
            }

            HardcoreWorldReset.LOGGER.info(
                    "Saving world: {}",
                    key.getValue()
            );

            try {
                // Save all chunks that are currently loaded.
                // The "true" argument means flush = write everything to disk now.
                // The "false" argument means don't log the save to console
                // (we handle our own logging)
                world.save(null, true, false);

                HardcoreWorldReset.LOGGER.info(
                        "Successfully saved world: {}",
                        key.getValue()
                );

            } catch (Exception e) {
                HardcoreWorldReset.LOGGER.error(
                        "Failed to save world {}: {}",
                        key.getValue(),
                        e.getMessage()
                );
            }
        }

        HardcoreWorldReset.LOGGER.info("All worlds saved.");
    }
}