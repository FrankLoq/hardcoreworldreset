package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

public class PlayerRespawner {

    public static void respawnExemptPlayer(ServerPlayerEntity player, MinecraftServer server) {
        BlockPos personalSpawn = player.getSpawnPointPosition();
        if (personalSpawn != null) {
            ServerWorld personalSpawnWorld = server.getWorld(player.getSpawnPointDimension());
            if (personalSpawnWorld == null) {
                HardcoreWorldReset.LOGGER.warn(
                        "Exempt player {} has a saved spawn at {} in an unavailable dimension. Using world spawn.",
                        player.getName().getString(), personalSpawn, player.getSpawnPointDimension().getValue()
                );
            } else {
                BlockState spawnBlock = personalSpawnWorld.getBlockState(personalSpawn);
                boolean isBed = spawnBlock.getBlock() instanceof net.minecraft.block.BedBlock;

                if (isBed) {
                    HardcoreWorldReset.LOGGER.info(
                            "Trying saved bed spawn for exempt player {} at {} in {}.",
                            player.getName().getString(),
                            personalSpawn,
                            personalSpawnWorld.getRegistryKey().getValue()
                    );

                    BlockPos safePersonalSpawn = WorldSpawnLocator.findSafeSpawnNear(personalSpawnWorld, personalSpawn);
                    if (safePersonalSpawn != null) {
                        HardcoreWorldReset.LOGGER.info(
                                "Respawning exempt player {} near saved spawn at {} in {}.",
                                player.getName().getString(),
                                safePersonalSpawn,
                                personalSpawnWorld.getRegistryKey().getValue()
                        );
                        restoreAndTeleport(player, personalSpawnWorld, safePersonalSpawn);
                        return;
                    }

                    HardcoreWorldReset.LOGGER.warn(
                            "No safe position exists near saved spawn {} for exempt player {}. Using world spawn.",
                            personalSpawn, player.getName().getString()
                    );
                } else {
                    HardcoreWorldReset.LOGGER.warn(
                            "Saved spawn {} for exempt player {} is no longer a bed. Using world spawn.",
                            personalSpawn, player.getName().getString()
                    );
                }
            }
        } else {
            HardcoreWorldReset.LOGGER.info("Exempt player {} has no saved spawn. Using world spawn.", player.getName().getString());
        }

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            HardcoreWorldReset.LOGGER.error("Cannot respawn exempt player {} because the overworld is unavailable.", player.getName().getString());
            return;
        }

        BlockPos worldSpawn = overworld.getSpawnPos();
        BlockPos safeSpawn = WorldSpawnLocator.checkAndGetSafePos(overworld, worldSpawn.getX(), worldSpawn.getZ());
        if (safeSpawn == null) {
            int y = overworld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldSpawn.getX(), worldSpawn.getZ());
            safeSpawn = new BlockPos(worldSpawn.getX(), Math.max(y, 63), worldSpawn.getZ());
            HardcoreWorldReset.LOGGER.warn("No safe position was found near world spawn. Using {} for exempt player {}.", safeSpawn, player.getName().getString());
        } else {
            HardcoreWorldReset.LOGGER.info("Respawning exempt player {} at world spawn fallback {}.", player.getName().getString(), safeSpawn);
        }

        restoreAndTeleport(player, overworld, safeSpawn);
    }

    private static void restoreAndTeleport(ServerPlayerEntity player, ServerWorld world, BlockPos spawn) {
        world.getChunkManager().getChunk(spawn.getX() >> 4, spawn.getZ() >> 4, ChunkStatus.FULL, true);
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        player.networkHandler.sendPacket(new HealthUpdateS2CPacket(
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel()
        ));
        player.setFireTicks(0);
        player.fallDistance = 0.0f;
        player.changeGameMode(GameMode.SURVIVAL);
        player.teleport(world, spawn.getX() + 0.5, spawn.getY() + 0.1, spawn.getZ() + 0.5, 0.0f, 0.0f);
    }

    public static void respawnAllPlayers(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) return;

        // Fetch the calculated natural world spawn point
        BlockPos worldSpawn = overworld.getSpawnPos();

        net.minecraft.server.command.ServerCommandSource silentSource = server.getCommandSource().withSilent();

        server.getCommandManager().executeWithPrefix(silentSource, "clear @a");

        server.getCommandManager().executeWithPrefix(silentSource, "weather clear");

        server.getCommandManager().executeWithPrefix(silentSource, "recipe take @a *");

        server.getCommandManager().executeWithPrefix(silentSource, "recipe give @a minecraft:crafting_table");

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {

            // Use AdvancementEntry instead of Advancement for it to work in 1.20.2
            for (net.minecraft.advancement.AdvancementEntry advancementEntry : server.getAdvancementLoader().getAdvancements()) {
                net.minecraft.advancement.AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancementEntry);
                if (progress.isAnyObtained()) {
                    java.util.List<String> obtainedCriteria = new java.util.ArrayList<>();
                    progress.getObtainedCriteria().forEach(obtainedCriteria::add);
                    for (String criterion : obtainedCriteria) {
                        player.getAdvancementTracker().revokeCriterion(advancementEntry, criterion);
                    }
                }
            }

            player.setExperienceLevel(0);
            player.setExperiencePoints(0);
            player.experienceProgress = 0.0f;
            player.totalExperience = 0;

            player.clearStatusEffects();

            player.setHealth(20.0f);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
            player.getHungerManager().setExhaustion(0.0f);
            player.setFireTicks(0);
            player.setFrozenTicks(0);
            player.setAir(player.getMaxAir());
            player.fallDistance = 0.0f;

            // Delete their old spawnpoint
            player.setSpawnPoint(null, null, 0.0f, false, false);

            // Use the chunk scanner to find a perfectly safe block near the spawn
            net.minecraft.util.math.BlockPos fuzzySpawn = com.frankloq.reset.WorldSpawnLocator.checkAndGetSafePos(overworld, worldSpawn.getX(), worldSpawn.getZ());

            // Fallback If the scanner somehow returns null (bc someone flooded the spawn with lava idk)
            if (fuzzySpawn == null) {
                int fallbackY = overworld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldSpawn.getX(), worldSpawn.getZ());
                if (fallbackY <= overworld.getBottomY()) fallbackY = 64; // Anti-void protection
                fuzzySpawn = new net.minecraft.util.math.BlockPos(worldSpawn.getX(), fallbackY, worldSpawn.getZ());
            }

            // Force load the specific chunk to prevent suffocation
            overworld.getChunkManager().getChunk(fuzzySpawn.getX() >> 4, fuzzySpawn.getZ() >> 4, ChunkStatus.FULL, true);

            // Drop them safely onto the surface
            player.teleport(
                    overworld,
                    fuzzySpawn.getX() + 0.5,
                    fuzzySpawn.getY() + 0.1,
                    fuzzySpawn.getZ() + 0.5,
                    0.0f, 0.0f
            );

            player.changeGameMode(GameMode.SURVIVAL);
        }
    }
}
