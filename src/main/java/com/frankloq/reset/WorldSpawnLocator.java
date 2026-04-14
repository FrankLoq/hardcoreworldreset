package com.frankloq.reset;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Random;

public class WorldSpawnLocator {

    public static BlockPos determineWorldSpawn(ServerWorld world) {
        int[] radii = {0, 512, 1024, 1536, 2048};
        BlockPos bestPos = null;

        for (int r : radii) {
            int points = (r == 0) ? 1 : (r / 64);
            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI / points) * i;
                int x = (int) (Math.cos(angle) * r);
                int z = (int) (Math.sin(angle) * r);

                BlockPos candidate = checkAndGetSafePos(world, x, z);
                if (candidate != null) {
                    bestPos = candidate;
                    break;
                }
            }
            if (bestPos != null) break;
        }

        // Fallback if the entire 2048 radius is somehow ocean
        if (bestPos == null) {
            world.getChunkManager().getChunk(0, 0, ChunkStatus.FULL, true);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0);

            // Never let the player spawn below sea level
            bestPos = new BlockPos(0, Math.max(y, 63), 0);
        }

        return bestPos;
    }

    private static BlockPos checkAndGetSafePos(ServerWorld world, int x, int z) {
        // Generate just enough of the chunk to read the biome (extremely fast)
        world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.BIOMES, true);

        BlockPos checkPos = new BlockPos(x, 64, z);
        var biomeEntry = world.getBiome(checkPos);

        if (biomeEntry.getKey().isPresent()) {
            String biomeId = biomeEntry.getKey().get().getValue().getPath();
            if (biomeId.contains("ocean") || biomeId.contains("river") || biomeId.contains("swamp")) {
                return null;
            }
        }

        // It's safe, now generate the full chunk so we can get the real blocks
        world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);

        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);

        BlockState stateUnder = world.getBlockState(pos.down());
        if (stateUnder.isSolidBlock(world, pos.down()) && world.getFluidState(pos.down()).isEmpty()) {
            return pos;
        }

        return null;
    }

    public static BlockPos getPlayerFuzzySpawn(ServerWorld world, BlockPos worldSpawn) {
        Random rand = new Random();
        for (int i = 0; i < 40; i++) {
            int offsetX = rand.nextInt(21) - 10;
            int offsetZ = rand.nextInt(21) - 10;
            int x = worldSpawn.getX() + offsetX;
            int z = worldSpawn.getZ() + offsetZ;

            // Force the specific random chunk to generate before asking for the height
            world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

            // Catch void heights from straggling chunk updates
            if (y < world.getBottomY() + 10) {
                y = 63;
            }

            BlockPos fuzzyPos = new BlockPos(x, y, z);

            BlockState stateUnder = world.getBlockState(fuzzyPos.down());
            if (stateUnder.isSolidBlock(world, fuzzyPos.down()) && world.getFluidState(fuzzyPos.down()).isEmpty()) {
                return fuzzyPos;
            }
        }

        // Exact center, clamped at sea level so they don't fall.
        return new BlockPos(worldSpawn.getX(), Math.max(worldSpawn.getY(), 63), worldSpawn.getZ());
    }
}