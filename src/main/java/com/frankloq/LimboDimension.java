package com.frankloq;

import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
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
            new Identifier(HardcoreWorldReset.MOD_ID, "limbo")
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

        // Use FabricDimensions for safe cross-dimension teleportation
        FabricDimensions.teleport(
                player,
                limboWorld,
                new TeleportTarget(
                        new Vec3d(0.5, 64.0, 0.5),  // position
                        Vec3d.ZERO,                   // velocity (none)
                        0f,                           // yaw
                        0f                            // pitch
                )
        );

        HardcoreWorldReset.LOGGER.info(
                "Teleported {} to Limbo.",
                player.getName().getString()
        );

        return true;
    }
}