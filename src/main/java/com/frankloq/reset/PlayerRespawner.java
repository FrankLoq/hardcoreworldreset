package com.frankloq.reset;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

public class PlayerRespawner {

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

            // Fetch a random block inside the 21x21 scatter radius
            BlockPos fuzzySpawn = WorldSpawnLocator.getPlayerFuzzySpawn(overworld, worldSpawn);

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