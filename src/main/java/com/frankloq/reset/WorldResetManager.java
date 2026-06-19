package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Unit;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

public class WorldResetManager {

    private static final int PHASE_DELAY_TICKS = 40;

    private static ResetPhase currentPhase = ResetPhase.IDLE;
    private static int phaseTimer = 0;
    private static long newSeed = 0;
    private static boolean countdownLocked = false;

    private static int currentTry = 1;
    private static boolean hasLoadedTryCount = false;

    public static boolean isResetting() {
        return currentPhase != ResetPhase.IDLE;
    }

    public static boolean tryLockCountdown() {
        if (countdownLocked || currentPhase != ResetPhase.IDLE) return false;
        countdownLocked = true;
        return true;
    }

    public static void unlockCountdown() {
        countdownLocked = false;
    }

    // Indestructible try counter system
    public static int getCurrentTry(MinecraftServer server) {
        if (!hasLoadedTryCount) loadTryCount(server);
        return currentTry;
    }

    private static void loadTryCount(MinecraftServer server) {
        try {
            Path stateFile = getWorldFolder(server).resolve("hardcore_state.txt");
            if (Files.exists(stateFile)) {
                currentTry = Integer.parseInt(Files.readString(stateFile).trim());
            } else {
                currentTry = 1;
            }
        } catch (Exception e) {
            currentTry = 1;
        }
        hasLoadedTryCount = true;
    }

    public static void incrementAndSaveTryCount(MinecraftServer server) {
        if (!hasLoadedTryCount) loadTryCount(server);
        currentTry++;
        try {
            Path stateFile = getWorldFolder(server).resolve("hardcore_state.txt");
            Files.writeString(stateFile, String.valueOf(currentTry));
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to save Try Counter!", e);
        }
    }

    public static void tick(MinecraftServer server) {
        if (currentPhase == ResetPhase.IDLE) return;
        if (phaseTimer > 0) {
            phaseTimer--;
            return;
        }

        switch (currentPhase) {
            case UNLOADING -> executeUnloadPhase(server);
            case DELETING -> executeDeletionPhase(server);
            case REGENERATING -> executeRegenerationPhase(server);
            case DONE -> executeDonePhase(server);
            default -> {
            }
        }
    }

    public static void beginReset(MinecraftServer server) {
        if (currentPhase != ResetPhase.IDLE) return;

        Long fixedSeed = getFixedSeed(server);
        if (fixedSeed != null) {
            newSeed = fixedSeed;
            HardcoreWorldReset.LOGGER.info("Using seed from server.properties: {}", newSeed);
        } else {
            newSeed = new Random().nextLong();
        }

        // Ensure we load the try count into memory before the reset starts
        if (!hasLoadedTryCount) loadTryCount(server);

        HardcoreWorldReset.LOGGER.info("Starting true world reset. New seed: {}", newSeed);
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Beginning world erasure..."), false);

        // Teleport everyone to Limbo before the unloading phase starts
        for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            com.frankloq.LimboDimension.teleportToLimbo(player);
        }

        advanceTo(ResetPhase.UNLOADING);
    }

    // Had to do all of this as well for 1.21 compatibility
    private static void executeUnloadPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: UNLOADING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Unloading memory cache..."), false);

        // Get rid of entities
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                WorldInjectionUtils.clearAllEntities(world);

                // Wipes blockEntityTickers and pendingBlockEntityTickers
                try {
                    for (java.lang.reflect.Field field : net.minecraft.world.World.class.getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            java.util.List<?> list = (java.util.List<?>) field.get(world);
                            if (list != null) {
                                list.clear();
                            }
                        }
                    }
                } catch (Exception e) {
                    HardcoreWorldReset.LOGGER.error("Failed to vaporize ghost block entities!", e);
                }
            }
        }

        // Revoke the permanent spawn chunks memory lock
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            overworld.getChunkManager().removeTicket(
                    ChunkTicketType.START, new ChunkPos(overworld.getSpawnPos()), 11, Unit.INSTANCE
            );
        }

        // Force save to pack the chunks securely to disk
        server.saveAll(true, true, true);

        // Advance to the deleting phase after 40 ticks
        // This wait perfectly ensures no dropped items are ticking when we wipe the ram
        advanceTo(ResetPhase.DELETING);
    }

    private static void executeDeletionPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DELETING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Erasing old world files..."), false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                // Now we clear the main RAM and it doesn't crash because entities are dead
                WorldInjectionUtils.lobotomizeChunkManager(world);

                // Clear the io buffer too
                WorldInjectionUtils.lobotomizeStorageIO(world);

                // Rip the file handles away from OS
                WorldInjectionUtils.forceCloseRegionFiles(world);
            }
        }

        Path worldFolder = getWorldFolder(server);
        if (worldFolder != null) {
            for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                Path dimPath;
                if (key == World.OVERWORLD) dimPath = worldFolder;
                else if (key == World.NETHER) dimPath = worldFolder.resolve("DIM-1");
                else if (key == World.END) dimPath = worldFolder.resolve("DIM1");
                else
                    dimPath = worldFolder.resolve("dimensions").resolve(key.getValue().getNamespace()).resolve(key.getValue().getPath());

                deleteFolder(dimPath.resolve("region"));
                deleteFolder(dimPath.resolve("poi"));
                deleteFolder(dimPath.resolve("entities"));
            }

            HardcoreWorldReset.LOGGER.info("Wiping player data, stats, and advancements...");
            deleteFolder(worldFolder.resolve("advancements"));
            deleteFolder(worldFolder.resolve("stats"));
            deleteFolder(worldFolder.resolve("playerdata"));

            try {
                java.nio.file.Files.createDirectories(worldFolder.resolve("advancements"));
                java.nio.file.Files.createDirectories(worldFolder.resolve("stats"));
                java.nio.file.Files.createDirectories(worldFolder.resolve("playerdata"));
            } catch (java.io.IOException e) {
                HardcoreWorldReset.LOGGER.error("Failed to recreate player folders.", e);
            }
        }

        advanceTo(ResetPhase.REGENERATING);
    }

    private static void executeRegenerationPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: REGENERATING");

        // Physically save the new try count to the text file
        incrementAndSaveTryCount(server);

        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Applying new seed: §e" + newSeed), false);
        writeNewSeedToLevelDat(server, newSeed, false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;

            ServerChunkManager manager = world.getChunkManager();
            net.minecraft.world.gen.chunk.ChunkGenerator chunkGen = manager.getChunkGenerator();

            if (chunkGen instanceof NoiseChunkGenerator noiseGen) {
                NoiseConfig newConfig = NoiseConfig.create(
                        noiseGen.getSettings().value(),
                        server.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),
                        newSeed
                );

                injectByType(manager, NoiseConfig.class, newConfig);
                injectByType(manager.chunkLoadingManager, NoiseConfig.class, newConfig);

                try {
                    net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator newCalculator =
                            chunkGen.createStructurePlacementCalculator(
                                    server.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),
                                    newConfig, newSeed
                            );
                    injectByType(manager.chunkLoadingManager, net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator.class, newCalculator);
                } catch (Exception e) {
                }
            }

            WorldInjectionUtils.injectSeedIntoMemory(world, newSeed);

            if (key.equals(World.OVERWORLD)) {
                world.setTimeOfDay(0L);
                // Temporary dummy spawn to prevent crashes
                world.setSpawnPos(new net.minecraft.util.math.BlockPos(0, 200, 0), 0.0f);
            }

            if (key.equals(World.END)) {
                WorldInjectionUtils.resetEnderDragonFight(world);
            }
        }

        advanceTo(ResetPhase.DONE);
    }

    private static void executeDonePhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DONE");

        // We advance to IDLE immediately outside the execute block so the tick loop doesn't fire this multiple times
        advanceTo(ResetPhase.IDLE);

        server.execute(() -> {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld != null) {
                // Calculate the new spawn
                net.minecraft.util.math.BlockPos newSpawn;

                // Protective try-catch block for rapid restarts
                try {
                    // We pump the chunk task queue first to clear out ghost tickets from the previous deletion
                    for (int i = 0; i < 5; i++) {
                        overworld.getChunkManager().tick(() -> true, true);
                    }

                    // Attempt the natural spawn
                    newSpawn = com.frankloq.reset.WorldSpawnLocator.determineWorldSpawn(overworld);

                    overworld.getChunk(newSpawn.getX() >> 4, newSpawn.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);

                } catch (IllegalStateException e) {
                    HardcoreWorldReset.LOGGER.warn("Chunk engine overloaded by rapid restarts. Catching crash and falling back to 0,0.");

                    // We safely fallback to 0, 0 because the REGENERATING phase already added a ticket here,
                    // meaning it is guaranteed to be fully loaded and won't crash
                    int fallbackY = overworld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                    if (fallbackY <= overworld.getBottomY()) fallbackY = 64; // Anti-void protection

                    newSpawn = new net.minecraft.util.math.BlockPos(0, fallbackY, 0);
                }

                // Set the actual world spawn to the spawn we just calculated
                overworld.setSpawnPos(newSpawn, 0.0f);

                // Add ticket to load chunks around 0,0 safely
                int spawnRadius = server.getGameRules().getInt(net.minecraft.world.GameRules.SPAWN_CHUNK_RADIUS);
                overworld.getChunkManager().addTicket(
                        net.minecraft.server.world.ChunkTicketType.START,
                        new net.minecraft.util.math.ChunkPos(newSpawn),
                        spawnRadius,
                        net.minecraft.util.Unit.INSTANCE
                );

                // Safely teleport players directly to the natural spawn
                for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {

                        // Force wipe their RAM cache
                        com.frankloq.reset.WorldInjectionUtils.wipePlayerState(player);

                        // Teleport the player
                        player.teleport(
                                overworld,
                                newSpawn.getX() + 0.5,
                                newSpawn.getY() + 1.0,
                                newSpawn.getZ() + 0.5,
                                0.0f,
                                0.0f
                        );
                    }
                }
            }

            // I'm trying to optimize ram usage but idk if it's gonna do something
            for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                if (key.equals(World.OVERWORLD)) continue;

                ServerWorld world = server.getWorld(key);
                if (world != null) {
                    ServerChunkManager manager = world.getChunkManager();
                    for (int i = 0; i < 50; i++) {
                        manager.tick(() -> false, true);
                    }
                }
            }

            System.gc();

            server.getPlayerManager().broadcast(Text.literal("§a[Reset] §7World reset complete! Respawning players..."), false);
            HardcoreWorldReset.onResetComplete(server);

            countdownLocked = false;
        });
    }

    private static <T> void injectByType(Object target, Class<T> fieldType, T value) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        field.set(target, value);
                    } catch (Exception ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static void guaranteeLevelDatOnShutdown(MinecraftServer server) {
        if (newSeed != 0) {
            writeNewSeedToLevelDat(server, newSeed, true);
        }
    }

    private static boolean writeNewSeedToLevelDat(MinecraftServer server, long seed, boolean isShutdown) {
        try {
            Path rootPath = server.getSavePath(WorldSavePath.ROOT).normalize();
            Path levelDatPath = rootPath.resolve("level.dat");

            if (!Files.exists(levelDatPath)) return false;

            NbtCompound root = NbtIo.readCompressed(levelDatPath, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.getCompound("Data");

            if (data.contains("RandomSeed")) data.putLong("RandomSeed", seed);
            if (data.contains("WorldGenSettings")) {
                NbtCompound wgs = data.getCompound("WorldGenSettings");
                wgs.putLong("seed", seed);
                data.put("WorldGenSettings", wgs);
            }

            data.putLong("Time", 0L);
            data.putLong("DayTime", 0L);
            if (data.contains("DragonFight")) data.remove("DragonFight");

            root.put("Data", data);
            Files.copy(levelDatPath, rootPath.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
            NbtIo.writeCompressed(root, levelDatPath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path getWorldFolder(MinecraftServer server) {
        try {
            return server.getSavePath(WorldSavePath.ROOT).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteFolder(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    HardcoreWorldReset.LOGGER.error("windows won't fucking allow this file to removed bc is a lil child: " + p.getFileName(), e);
                }
            });
        } catch (IOException e) {
            HardcoreWorldReset.LOGGER.error("Failed to read folder: " + path, e);
        }
    }

    private static void advanceTo(ResetPhase phase) {
        currentPhase = phase;
        phaseTimer = PHASE_DELAY_TICKS;
    }

    private static Long getFixedSeed(MinecraftServer server) {
        if (!com.frankloq.HardcoreWorldReset.reuseSeed) {
            return null;
        }

        try {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld != null) {
                long currentSeed = overworld.getSeed();
                return currentSeed;
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to fetch current world seed", e);
        }

        return null;
    }
}