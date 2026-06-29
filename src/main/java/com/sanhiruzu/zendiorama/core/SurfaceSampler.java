package com.sanhiruzu.zendiorama.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

public final class SurfaceSampler {
    private SurfaceSampler() {}

    public static MiniatureSnapshot sample(ServerLevel level, int originX, int originZ, int width, int maxBlocks) {
        return sampleWithStatus(level, originX, originZ, width, maxBlocks).snapshot();
    }

    public static SampleResult sampleWithStatus(ServerLevel level, int originX, int originZ, int width, int maxBlocks) {
        // Compute a spatial XZ stride so the sampled entry count stays within budget.
        // Using a list-order stride (SnapshotSampler) on data sorted by z-inner-loop would
        // drop every other z-row (stride=2), producing "every other line missing". Spatial
        // stride reduces resolution uniformly in both axes instead.
        //
        // Worst case: 2 entries per sampled column (ground + one feature). We choose the
        // smallest stride s where ceil(width/s)^2 * 2 <= maxBlocks.
        int xzStride = samplingStride(width, maxBlocks);
        int sampledWidth = (width + xzStride - 1) / xzStride;

        List<MiniatureSnapshot.Entry> entries = new ArrayList<>(sampledWidth * sampledWidth * 2);
        int seaLevel = level.getSeaLevel();
        int minY = level.getMinBuildHeight();
        int missingColumns = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // normX/normZ are contiguous 0-based indices into the sampled grid so the
        // geometry baker can tile voxels across the full block face without gaps.
        int normX = 0;
        for (int x = 0; x < width; x += xzStride, normX++) {
            int worldX = originX + x;
            int normZ = 0;
            for (int z = 0; z < width; z += xzStride, normZ++) {
                int worldZ = originZ + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(worldX >> 4, worldZ >> 4);
                if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) {
                    missingColumns++;
                    continue;
                }
                int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX & 15, worldZ & 15);
                if (topY < minY) continue;
                cursor.set(worldX, topY, worldZ);
                BlockState topState = chunk.getBlockState(cursor);
                if (topState.isAir()) continue;

                // Scan down past tree material (leaves/logs) to the ground block, so the
                // ground entry reflects true terrain height rather than a tree's canopy.
                int groundY = topY;
                BlockState groundState = topState;
                boolean groundIsFeature = SurfaceClassifier.isFeature(groundState);
                while (groundY > minY && groundIsFeature) {
                    groundY--;
                    cursor.setY(groundY);
                    groundState = chunk.getBlockState(cursor);
                    groundIsFeature = SurfaceClassifier.isFeature(groundState);
                }

                // Ground entry (cursor is at the ground block).
                entries.add(new MiniatureSnapshot.Entry(
                        normX,
                        groundY - seaLevel,
                        normZ,
                        MiniatureBlockStateCodec.encode(groundState),
                        computeTint(level, cursor, groundState)));

                // Tree entry when vegetation sat above the ground in this column.
                if (groundY != topY) {
                    cursor.set(worldX, topY, worldZ);
                    entries.add(new MiniatureSnapshot.Entry(
                            normX,
                            topY - seaLevel,
                            normZ,
                            MiniatureBlockStateCodec.encode(topState),
                            computeTint(level, cursor, topState)));
                }
            }
        }

        return new SampleResult(new MiniatureSnapshot(width * width, entries), missingColumns == 0, missingColumns);
    }

    public record SampleResult(MiniatureSnapshot snapshot, boolean complete, int missingColumns) {
    }

    public static int sampledGridWidth(int width, int maxBlocks) {
        int stride = samplingStride(width, maxBlocks);
        return (width + stride - 1) / stride;
    }

    private static int samplingStride(int width, int maxBlocks) {
        int xzStride = 1;
        int sampledWidth = width;
        while ((long) sampledWidth * sampledWidth * 2 > maxBlocks) {
            xzStride++;
            sampledWidth = (width + xzStride - 1) / xzStride;
        }
        return xzStride;
    }

    /**
     * Returns the packed RGB biome tint a block would render with (grass/foliage/water colormaps),
     * or 0 when the block isn't biome-tinted and should keep its flat MapColor.
     */
    private static int computeTint(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (state.is(BlockTags.LEAVES) || block == Blocks.VINE) {
            Biome biome = level.getBiome(pos).value();
            return biome.getFoliageColor() & 0xFFFFFF;
        }
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS
                || block == Blocks.FERN
                || block == Blocks.LARGE_FERN
                || block == Blocks.SUGAR_CANE) {
            Biome biome = level.getBiome(pos).value();
            return biome.getGrassColor(pos.getX(), pos.getZ()) & 0xFFFFFF;
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            Biome biome = level.getBiome(pos).value();
            return biome.getWaterColor() & 0xFFFFFF;
        }
        return 0;
    }
}
