package com.sanhiruzu.zendiorama.server;

import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlockEntity;
import com.sanhiruzu.zendiorama.core.PlotOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

public final class DioramaPlayerVisibilityHandler {
    private DioramaPlayerVisibilityHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide) {
            boolean inDiorama = player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL);
            if (player.isInvisible() != inDiorama) {
                player.setInvisible(inDiorama);
            }
            if (inDiorama && player.level() instanceof ServerLevel dioramaLevel) {
                syncDioramaTimeToReturnLevel(player, dioramaLevel);
                refreshReturnedFrameSnapshotIfReady(player, dioramaLevel);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (event.getFrom().equals(DioramaDimensions.DIORAMA_LEVEL) && !event.getTo().equals(DioramaDimensions.DIORAMA_LEVEL)) {
                releaseForcedChunksIfLastOccupant(player);
            }
            boolean inDiorama = event.getTo().equals(DioramaDimensions.DIORAMA_LEVEL);
            if (player.isInvisible() != inDiorama) {
                player.setInvisible(inDiorama);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            releaseForcedChunksIfLastOccupant(player);
        }
    }

    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getSpawnType() == MobSpawnType.NATURAL
                && event.getLevel().getLevel().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            if (event.getPlacedBlock().is(ZenDiorama.DIORAMA_FRAME.get())
                    || isOutsideReturnedPlot(player, event.getPos())) {
                event.setCanceled(true);
                return;
            }
            markReturnedFrameDirty(player);
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && player.level().dimension().equals(DioramaDimensions.DIORAMA_LEVEL)) {
            if (isOutsideReturnedPlot(player, event.getPos())
                    || event.getState().is(ZenDiorama.DIORAMA_EXIT.get())
                    || event.getState().is(ZenDiorama.DIORAMA_CONTROL.get())) {
                event.setCanceled(true);
                return;
            }
            markReturnedFrameDirty(player);
        }
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
        if (frame != null
                && frame.shouldRefreshSnapshot(dioramaLevel.getGameTime())) {
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
