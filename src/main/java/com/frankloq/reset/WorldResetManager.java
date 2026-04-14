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

        Long fixedSeed = getFixedSeed();
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

        advanceTo(ResetPhase.UNLOADING);
    }

    private static void executeUnloadPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: UNLOADING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Unloading memory cache..."), false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) WorldInjectionUtils.clearAllEntities(world);
        }

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            overworld.getChunkManager().removeTicket(
                    ChunkTicketType.START, new ChunkPos(overworld.getSpawnPos()), 11, Unit.INSTANCE
            );
        }

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;

            ServerChunkManager manager = world.getChunkManager();
            try { world.save(null, true, false); } catch (Exception e) {}

            for (int i = 0; i < 50; i++) manager.tick(() -> false, true);
        }

        advanceTo(ResetPhase.DELETING);
    }

    private static void executeDeletionPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DELETING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Erasing old world files..."), false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                try { world.getChunkManager().save(true); } catch (Exception ignored) {}
                WorldInjectionUtils.forceCloseRegionFiles(world);
            }
        }

        Path worldFolder = getWorldFolder(server);
        if (worldFolder != null) {
            deleteFolder(worldFolder.resolve("region"));
            deleteFolder(worldFolder.resolve("entities"));
            deleteFolder(worldFolder.resolve("poi"));
            deleteFolder(worldFolder.resolve("advancements"));
            deleteFolder(worldFolder.resolve("stats"));
            deleteFolder(worldFolder.resolve("playerdata"));

            try {
                Files.createDirectories(worldFolder.resolve("playerdata"));
                Files.createDirectories(worldFolder.resolve("advancements"));
                Files.createDirectories(worldFolder.resolve("stats"));
            } catch (Exception ignored) {}

            deleteFolder(worldFolder.resolve("DIM-1").resolve("region"));
            deleteFolder(worldFolder.resolve("DIM-1").resolve("entities"));
            deleteFolder(worldFolder.resolve("DIM-1").resolve("poi"));

            deleteFolder(worldFolder.resolve("DIM1").resolve("region"));
            deleteFolder(worldFolder.resolve("DIM1").resolve("entities"));
            deleteFolder(worldFolder.resolve("DIM1").resolve("poi"));
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
                injectByType(manager.threadedAnvilChunkStorage, NoiseConfig.class, newConfig);

                try {
                    net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator newCalculator =
                            chunkGen.createStructurePlacementCalculator(
                                    server.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),
                                    newConfig, newSeed
                            );
                    injectByType(manager.threadedAnvilChunkStorage, net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator.class, newCalculator);
                } catch (Exception e) {}
            }

            WorldInjectionUtils.injectSeedIntoMemory(world, newSeed);

            if (key.equals(World.OVERWORLD)) {
                world.setTimeOfDay(0L);
                net.minecraft.util.math.BlockPos newSpawn = WorldSpawnLocator.determineWorldSpawn(world);
                world.setSpawnPos(newSpawn, 0.0f);
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
            overworld.getChunkManager().addTicket(
                    ChunkTicketType.START, new ChunkPos(overworld.getSpawnPos()), 11, Unit.INSTANCE
            );
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

            NbtCompound root = NbtIo.readCompressed(levelDatPath.toFile());
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
            NbtIo.writeCompressed(root, levelDatPath.toFile());
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
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static void advanceTo(ResetPhase phase) {
        currentPhase = phase;
        phaseTimer = PHASE_DELAY_TICKS;
    }

    private static Long getFixedSeed() {
        try {
            Path propsPath = java.nio.file.Paths.get("server.properties");
            if (Files.exists(propsPath)) {
                java.util.Properties props = new java.util.Properties();
                try (java.io.InputStream in = Files.newInputStream(propsPath)) {
                    props.load(in);
                    String seedStr = props.getProperty("level-seed", "").trim();

                    if (!seedStr.isEmpty()) {
                        try {
                            return Long.parseLong(seedStr); // If it's a pure number
                        } catch (NumberFormatException e) {
                            return (long) seedStr.hashCode(); // If it's a word, hash it like vanilla does
                        }
                    }
                }
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to read server.properties", e);
        }
        return null; // Return null if file is missing or seed is blank
    }
}