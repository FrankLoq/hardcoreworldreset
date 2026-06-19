package com.frankloq.reset;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;

public class WorldSpawnLocator {

    public static BlockPos determineWorldSpawn(ServerWorld world) {
        BlockPos bestPos = null;

        for (int r = 0; r <= 384; r += 16) {

            // Calculate how many points we need to check to form a perfect circle at this radius
            int points = (r == 0) ? 1 : (int) ((2 * Math.PI * r) / 16);

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
            if (y <= world.getBottomY()) {
                y = 63;
            }

            BlockPos fallbackPos = new BlockPos(0, Math.max(y, 63), 0);

        return fallbackPos;
    }

    return bestPos;
}

    public static BlockPos checkAndGetSafePos(ServerWorld world, int x, int z) {
        // We load the chunk at the target X, Z to scan it safely
        world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);

        // Calculate the starting coordinates of the chunk
        int chunkStartX = (x >> 4) << 4;
        int chunkStartZ = (z >> 4) << 4;

        // Scan multiple blocks inside the loaded chunk
        // We check a grid of points within the 16x16 chunk. If any of them are safe, we use it
        for (int dx = 2; dx < 16; dx += 4) {
            for (int dz = 2; dz < 16; dz += 4) {
                int checkX = chunkStartX + dx;
                int checkZ = chunkStartZ + dz;

        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Anti-void protection
                if (y <= world.getBottomY()) {
                    continue;
                }

                BlockPos fuzzyPos = new BlockPos(checkX, y, checkZ);

                if (isValidSpawnBlock(world, fuzzyPos)) {
                    return fuzzyPos; // Found a safe solid block!
                }
            }
        }

        return null; // The entire chunk grid was unsafe
    }

    // Definitive fix for the player spawning crawling or in dangerous blocks
    private static boolean isValidSpawnBlock(ServerWorld world, BlockPos pos) {
        BlockState under = world.getBlockState(pos.down());
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());

        // The floor must be a solid block and not water or lava
        if (!under.isSolidBlock(world, pos.down()) || !world.getFluidState(pos.down()).isEmpty()) {
            return false;
        }

        // Paranoid check to ensure the block the player is standing in is definitely not a fluid
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }

        // No dangerous blocks allowed. Reject powder snow, magma and cactus
        if (under.isOf(Blocks.POWDER_SNOW) || feet.isOf(Blocks.POWDER_SNOW) || head.isOf(Blocks.POWDER_SNOW)) return false;
        if (under.isOf(Blocks.MAGMA_BLOCK) || under.isOf(Blocks.CACTUS) || under.isOf(Blocks.CAMPFIRE)) return false;

        // The crawling prevention, the collision shape for the feet and head must be completely empty
        if (!feet.getCollisionShape(world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) return false;

        return true;
    }
}