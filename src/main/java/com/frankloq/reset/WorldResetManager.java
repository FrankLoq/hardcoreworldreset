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

    public static ResetPhase getCurrentPhase() { return currentPhase; }
    public static boolean isResetting() { return currentPhase != ResetPhase.IDLE; }

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
            case UNLOADING    -> executeUnloadPhase(server);
            case DELETING     -> executeDeletionPhase(server);
            case REGENERATING -> executeRegenerationPhase(server);
            case DONE         -> executeDonePhase(server);
            default           -> {}
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

    // Had to all of this as well for 1.21 compatibility
    private static void executeUnloadPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: UNLOADING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Unloading memory cache..."), false);

        // Get rid of entities
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) WorldInjectionUtils.clearAllEntities(world);
        }

        // Force save to pack the chunks securely to disk
        server.saveAll(true, true, true);

        // Advance to the deleting phase after 40 ticks
        // This wait perfectly ensures no dropped items are ticking when we wipe the ram.
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
                else dimPath = worldFolder.resolve("dimensions").resolve(key.getValue().getNamespace()).resolve(key.getValue().getPath());

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
                HardcoreWorldReset.LOGGER.error("Failed to recreate player folders!", e);
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
                } catch (Exception e) {}
            }

            WorldInjectionUtils.injectSeedIntoMemory(world, newSeed);

            if (key.equals(World.OVERWORLD)) {
                world.setTimeOfDay(0L);
                // net.minecraft.util.math.BlockPos newSpawn = WorldSpawnLocator.determineWorldSpawn(world);
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

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            int spawnRadius = server.getGameRules().getInt(net.minecraft.world.GameRules.SPAWN_CHUNK_RADIUS);
            
            // 1. Add ticket to load chunks around 0,0 safely
            net.minecraft.util.math.ChunkPos spawnChunk = new net.minecraft.util.math.ChunkPos(new net.minecraft.util.math.BlockPos(0, 0, 0));
            overworld.getChunkManager().addTicket(
                    net.minecraft.server.world.ChunkTicketType.START,
                    spawnChunk,
                    spawnRadius,
                    net.minecraft.util.Unit.INSTANCE
            );

            // 2. Use native Minecraft code to find the highest solid block at 0,0
            net.minecraft.util.math.BlockPos safeSpawnPos = overworld.getTopPosition(
                    net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 
                    new net.minecraft.util.math.BlockPos(0, 0, 0)
            );
            overworld.setSpawnPos(safeSpawnPos, 0.0f);

            for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {

                    com.frankloq.reset.WorldInjectionUtils.wipePlayerState(player);

                    // 3. Teleport the player safely on top of the block
                    player.teleport(
                            overworld,
                            safeSpawnPos.getX() + 0.5,
                            safeSpawnPos.getY() + 1.0, 
                            safeSpawnPos.getZ() + 0.5,
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
        advanceTo(ResetPhase.IDLE);
    }

    private static <T> void injectByType(Object target, Class<T> fieldType, T value) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    try { field.setAccessible(true); field.set(target, value); } catch (Exception ignored) {}
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
        try { return server.getSavePath(WorldSavePath.ROOT).normalize(); } catch (Exception e) { return null; }
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
        // --- CONFIG CHECK ---
        // If the config file is set to false, return null to trigger a random seed!
        if (!com.frankloq.HardcoreWorldReset.reuseSeed) {
            return null; 
        }
        // --------------------

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