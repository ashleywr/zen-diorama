package com.sanhiruzu.zendiorama.core;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared ground-vs-feature classification for world-map relief rendering.
 *
 * <p>A voxel's role is derived from its block state rather than stored: the sampler
 * (server) and the geometry baker (client) both call {@link #isFeature} so the two
 * can never drift. "Feature" means tree material (leaves/logs) that sits on top of
 * the ground and should render as a tree shape rather than a terrain pillar.
 */
public final class SurfaceClassifier {
    private SurfaceClassifier() {
    }

    /** True when the block is tree material (leaves or logs). */
    public static boolean isFeature(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }
}
