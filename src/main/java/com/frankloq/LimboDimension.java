package com.frankloq;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

public class LimboDimension {

    public static final RegistryKey<World> LIMBO_KEY = RegistryKey.of(
            RegistryKeys.WORLD,
            Identifier.of(HardcoreWorldReset.MOD_ID, "limbo")
    );

    // Teleports the players to the Limbo dimension
    // Returns true if successful, false if the dimension was not found.
    public static boolean teleportToLimbo(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();

        if (server == null) {
            HardcoreWorldReset.LOGGER.error("Server is null when trying to teleport to Limbo.");
            return false;
        }

        ServerWorld limboWorld = server.getWorld(LIMBO_KEY);

        if (limboWorld == null) {
            HardcoreWorldReset.LOGGER.error(
                    "Limbo world not found in server world list! " +
                            "Make sure the dimension JSON files are in the correct location."
            );

            // Log all registered worlds to help debug
            HardcoreWorldReset.LOGGER.error("Registered worlds:");
            for (RegistryKey<World> key : server.getWorldRegistryKeys()) {
                HardcoreWorldReset.LOGGER.error("  - {}", key.getValue());
            }

            return false;
        }

        // We use now 1.21 native teleportation instead of FabricDimensions
        player.teleportTo(new net.minecraft.world.TeleportTarget(
                limboWorld,
                new net.minecraft.util.math.Vec3d(0, 65, 0),
                net.minecraft.util.math.Vec3d.ZERO,
                0.0f,
                0.0f,
                entity -> {
                    // I kinda liked the sound effect of traveling to the Nether of FabricDimensions so lets add it manually here
                    if (entity instanceof ServerPlayerEntity p) {
                        p.playSoundToPlayer(
                                net.minecraft.sound.SoundEvents.BLOCK_PORTAL_TRAVEL,
                                net.minecraft.sound.SoundCategory.MASTER,
                                0.5f,
                                1.0f
                        );
                    }
                }
        ));

        HardcoreWorldReset.LOGGER.info(
                "Teleported {} to Limbo.",
                player.getName().getString()
        );

        return true;
    }
}