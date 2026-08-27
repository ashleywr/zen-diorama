package com.sanhiruzu.zendiorama.server;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlockEntity;
import com.sanhiruzu.zendiorama.core.PlotOrigin;
import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public final class DioramaPlayerVisibilityHandler {
    private DioramaPlayerVisibilityHandler() {
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (player.level().isClientSide) {
            return;
        }

        boolean inDiorama = player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL);
        if (player.isInvisible() != inDiorama) {
            player.setInvisible(inDiorama);
        }
        if (inDiorama && player.level() instanceof ServerLevel dioramaLevel) {
            syncDioramaTimeToReturnLevel(player, dioramaLevel);
            refreshReturnedFrameSnapshotIfReady(player, dioramaLevel);
        }
    }

    public static void onPlayerDimensionChanged(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        if (from.equals(DioramaDimensions.DIORAMA_LEVEL) && !to.equals(DioramaDimensions.DIORAMA_LEVEL)) {
            releaseForcedChunksIfLastOccupant(player);
        }

        boolean inDiorama = to.equals(DioramaDimensions.DIORAMA_LEVEL);
        if (player.isInvisible() != inDiorama) {
            player.setInvisible(inDiorama);
        }
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            releaseForcedChunksIfLastOccupant(player);
        }
    }

    public static boolean allowSpawn(ServerLevel level, MobSpawnType spawnType) {
        return spawnType != MobSpawnType.NATURAL || !level.dimension().equals(DioramaDimensions.DIORAMA_LEVEL);
    }

    public static boolean allowBlockPlace(ServerPlayer player, BlockPos pos, Block block) {
        if (player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            if (block == ZenDiorama.DIORAMA_FRAME.get() || isOutsideReturnedPlot(player, pos)) {
                return false;
            }
            markReturnedFrameDirty(player);
        }
        return true;
    }

    public static boolean allowBlockBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        if (player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            if (isOutsideReturnedPlot(player, pos)
                    || state.is(ZenDiorama.DIORAMA_EXIT.get())
                    || state.is(ZenDiorama.DIORAMA_CONTROL.get())) {
                return false;
            }
            markReturnedFrameDirty(player);
        }
        return true;
    }

    private static void syncDioramaTimeToReturnLevel(ServerPlayer player, ServerLevel dioramaLevel) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
        if (target == null) {
            return;
        }

        ServerLevel sourceLevel = player.server.getLevel(target.dimension());
        if (sourceLevel != null) {
            dioramaLevel.setDayTime(sourceLevel.getDayTime());
        }
    }

    private static void markReturnedFrameDirty(ServerPlayer player) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
        if (target == null) {
            return;
        }

        ServerLevel sourceLevel = player.server.getLevel(target.dimension());
        if (sourceLevel == null) {
            return;
        }

        BlockEntity blockEntity = sourceLevel.getBlockEntity(target.framePos());
        if (blockEntity instanceof DioramaFrameBlockEntity frame) {
            frame.markInteriorDirty(player.level().getGameTime());
        }
    }

    private static boolean isOutsideReturnedPlot(ServerPlayer player, BlockPos pos) {
        DioramaFrameBlockEntity frame = getReturnedFrame(player);
        if (frame == null || frame.getPlotOrigin() == null) {
            return true;
        }

        PlotOrigin origin = frame.getPlotOrigin();
        int plotSize = DioramaConfig.plotSize();
        int groundY = Math.max(player.level().getMinBuildHeight() + 1, origin.y() + 1);
        return pos.getX() < origin.x()
                || pos.getX() >= origin.x() + plotSize
                || pos.getZ() < origin.z()
                || pos.getZ() >= origin.z() + plotSize
                || pos.getY() < groundY
                || pos.getY() >= groundY + plotSize;
    }

    private static void refreshReturnedFrameSnapshotIfReady(ServerPlayer player, ServerLevel dioramaLevel) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
        if (target == null) {
            return;
        }

        DioramaFrameBlockEntity frame = getReturnedFrame(player);
        if (frame != null && frame.shouldRefreshSnapshot(dioramaLevel.getGameTime())) {
            frame.refreshSnapshotFromInterior(dioramaLevel);
        }
    }

    private static DioramaFrameBlockEntity getReturnedFrame(ServerPlayer player) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
        if (target == null) {
            return null;
        }

        if (target.frameId() != null) {
            DioramaPlotSavedData.PlotRecord record = DioramaPlotSavedData.get((ServerLevel) player.level()).get(target.frameId());
            if (record != null && record.frameDimension() != null && record.framePos() != null) {
                ServerLevel frameLevel = player.server.getLevel(record.frameDimension());
                if (frameLevel != null) {
                    BlockEntity blockEntity = frameLevel.getBlockEntity(record.framePos());
                    if (blockEntity instanceof DioramaFrameBlockEntity frame) {
                        return frame;
                    }
                }
            }
        }

        ServerLevel sourceLevel = player.server.getLevel(target.dimension());
        if (sourceLevel == null) {
            return null;
        }

        BlockEntity blockEntity = sourceLevel.getBlockEntity(target.framePos());
        if (blockEntity instanceof DioramaFrameBlockEntity frame) {
            return frame;
        }
        return null;
    }

    private static void releaseForcedChunksIfLastOccupant(ServerPlayer player) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
        if (target == null || target.frameId() == null || !(player.level() instanceof ServerLevel interiorLevel)) {
            return;
        }

        DioramaFrameBlockEntity frame = getReturnedFrame(player);
        if (frame == null || frame.isAlwaysLoaded() || hasOtherOccupants(interiorLevel, target.frameId(), player.getUUID())) {
            return;
        }

        frame.setPlotChunksForced(interiorLevel, false);
    }

    private static boolean hasOtherOccupants(ServerLevel interiorLevel, UUID frameId, UUID excludingPlayerId) {
        for (ServerPlayer player : interiorLevel.players()) {
            if (player.getUUID().equals(excludingPlayerId)) {
                continue;
            }
            DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
            if (target != null && frameId.equals(target.frameId())) {
                return true;
            }
        }
        return false;
    }
}
