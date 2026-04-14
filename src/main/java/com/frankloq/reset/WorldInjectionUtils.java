package com.frankloq.reset;

import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.entity.boss.dragon.EnderDragonFight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WorldInjectionUtils {

    public static void injectSeedIntoMemory(ServerWorld world, long newSeed) {
        try {
            net.minecraft.world.WorldProperties properties = world.getLevelProperties();
            if (properties instanceof LevelProperties levelProps) {
                for (Field field : LevelProperties.class.getDeclaredFields()) {
                    if (field.getType() == GeneratorOptions.class) {
                        field.setAccessible(true);
                        Object generatorOptions = field.get(levelProps);
                        for (Field genField : generatorOptions.getClass().getDeclaredFields()) {
                            if (genField.getType() == long.class) {
                                genField.setAccessible(true);
                                genField.setLong(generatorOptions, newSeed);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {}
    }

    public static void forceCloseRegionFiles(ServerWorld world) {
        try {
            ServerChunkManager chunkManager = world.getChunkManager();

            // Block and POI chunks
            deepClose(chunkManager.threadedAnvilChunkStorage, 0, new HashSet<>());
            deepClose(chunkManager.getPointOfInterestStorage(), 0, new HashSet<>());

            // Entity chunks
            Object entityManager = null;
            for (Field f : ServerWorld.class.getDeclaredFields()) {
                if (f.getType() == ServerEntityManager.class) {
                    f.setAccessible(true);
                    entityManager = f.get(world);
                    break;
                }
            }
            if (entityManager != null) deepClose(entityManager, 0, new HashSet<>());

        } catch (Exception e) {}
    }

    private static void deepClose(Object target, int depth, Set<Object> visited) {
        if (target == null || depth > 4 || !visited.add(target)) return;

        if (target instanceof RegionBasedStorage storage) {
            closeRegionBasedStorage(storage);
            return;
        }

        if (target instanceof net.minecraft.server.world.ServerWorld ||
                target instanceof net.minecraft.server.MinecraftServer ||
                target instanceof net.minecraft.server.PlayerManager ||
                target instanceof net.minecraft.server.world.ServerChunkManager) {
            return;
        }

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;

                String typeName = field.getType().getName();
                if (!typeName.startsWith("net.minecraft") && !typeName.startsWith("com.mojang")) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) {
                        deepClose(value, depth + 1, visited);
                    }
                } catch (Exception ignored) {}
            }
            current = current.getSuperclass();
        }
    }

    private static void closeRegionBasedStorage(RegionBasedStorage storage) {
        try {
            for (Field f : RegionBasedStorage.class.getDeclaredFields()) {
                if (it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap.class.isAssignableFrom(f.getType()) ||
                        Map.class.isAssignableFrom(f.getType())) {

                    f.setAccessible(true);
                    Object cache = f.get(storage);
                    if (cache == null) continue;

                    Method valuesMethod = cache.getClass().getMethod("values");
                    Collection<?> values = (Collection<?>) valuesMethod.invoke(cache);
                    for (Object regionFile : values) {
                        if (regionFile instanceof AutoCloseable closeable) closeable.close();
                    }

                    Method clearMethod = cache.getClass().getMethod("clear");
                    clearMethod.invoke(cache);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    public static void clearAllEntities(ServerWorld world) {
        java.util.List<net.minecraft.entity.Entity> entitiesToRemove = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
            if (entity != null && !(entity instanceof net.minecraft.entity.player.PlayerEntity)) {
                entitiesToRemove.add(entity);
            }
        }
        for (net.minecraft.entity.Entity entity : entitiesToRemove) {
            try { entity.discard(); } catch (Exception ignored) {}
        }
    }

    public static void resetEnderDragonFight(ServerWorld endWorld) {
        try {
            for (Field field : ServerWorld.class.getDeclaredFields()) {
                if (field.getType() == EnderDragonFight.class) {
                    field.setAccessible(true);
                    for (java.lang.reflect.Constructor<?> c : field.getType().getDeclaredConstructors()) {
                        if (c.getParameterCount() == 3 && c.getParameterTypes()[0] == ServerWorld.class && c.getParameterTypes()[1] == long.class) {
                            Class<?> dataClass = c.getParameterTypes()[2];
                            Object defaultData = null;
                            for (Field df : dataClass.getDeclaredFields()) {
                                if (java.lang.reflect.Modifier.isStatic(df.getModifiers()) && df.getType() == dataClass) {
                                    df.setAccessible(true);
                                    defaultData = df.get(null);
                                    break;
                                }
                            }
                            Object newFight = c.newInstance(endWorld, endWorld.getSeed(), defaultData);
                            field.set(endWorld, newFight);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {}
    }
}