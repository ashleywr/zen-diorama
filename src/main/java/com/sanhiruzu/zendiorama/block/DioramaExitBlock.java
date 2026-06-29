package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.server.DioramaReturnData;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.server.DioramaPlotSavedData;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.UUID;

public class DioramaExitBlock extends Block {
    public static final MapCodec<DioramaExitBlock> CODEC = simpleCodec(DioramaExitBlock::new);

    public DioramaExitBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        exitDiorama(level, pos, serverPlayer);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!level.isClientSide() && entity instanceof ServerPlayer serverPlayer) {
            exitDiorama(level, pos, serverPlayer);
        }
    }

    private static void exitDiorama(Level level, BlockPos pos, ServerPlayer serverPlayer) {
        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(serverPlayer);
        if (target == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.zen_diorama.exit.no_return"), true);
            return;
        }

        ServerLevel returnLevel = serverPlayer.server.getLevel(target.dimension());
        if (returnLevel == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.zen_diorama.exit.missing_dimension"), true);
            return;
        }

        BlockPos framePos = resolveFramePos(returnLevel, target);
        DioramaFrameBlockEntity frame = refreshFrameSnapshot((ServerLevel) level, returnLevel, framePos);
        DioramaReturnData.clear(serverPlayer);
        if (frame != null && !frame.isAlwaysLoaded() && !hasOtherOccupants((ServerLevel) level, target.frameId())) {
            frame.setPlotChunksForced((ServerLevel) level, false);
        }
        level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 0.7F, 0.75F);
        PacketDistributor.sendToPlayer(serverPlayer, new DioramaTransitionPayload(false, null));
        BlockPos safeReturnPos = findSafeReturnPos(returnLevel, target, framePos);
        serverPlayer.teleportTo(returnLevel, safeReturnPos.getX() + 0.5D, safeReturnPos.getY(), safeReturnPos.getZ() + 0.5D, target.yaw(), target.pitch());
        returnLevel.playSound(null, framePos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.65F, 1.4F);
    }

    private static BlockPos findSafeReturnPos(ServerLevel returnLevel, DioramaReturnData.ReturnTarget target, BlockPos framePos) {
        BlockPos preferred = BlockPos.containing(target.returnX(), target.returnY(), target.returnZ());
        if (isSafeReturnPos(returnLevel, preferred)) {
            return preferred;
        }

        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = preferred.offset(dx, 0, dz);
                    if (isSafeReturnPos(returnLevel, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return framePos.relative(net.minecraft.core.Direction.NORTH);
    }

    private static boolean isSafeReturnPos(ServerLevel level, BlockPos feetPos) {
        return level.getBlockState(feetPos).isAir()
                && level.getBlockState(feetPos.above()).isAir()
                && !level.getBlockState(feetPos.below()).isAir();
    }

    private static BlockPos resolveFramePos(ServerLevel returnLevel, DioramaReturnData.ReturnTarget target) {
        UUID frameId = target.frameId();
        if (frameId == null) {
            return target.framePos();
        }
        DioramaPlotSavedData.PlotRecord record = DioramaPlotSavedData.get(returnLevel).get(frameId);
        if (record != null && record.frameDimension() != null && record.frameDimension().equals(returnLevel.dimension()) && record.framePos() != null) {
            return record.framePos();
        }
        return target.framePos();
    }

    private static boolean hasOtherOccupants(ServerLevel interiorLevel, UUID frameId) {
        if (frameId == null) {
            return false;
        }
        for (ServerPlayer player : interiorLevel.players()) {
            DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
            if (target != null && frameId.equals(target.frameId())) {
                return true;
            }
        }
        return false;
    }

    private static DioramaFrameBlockEntity refreshFrameSnapshot(ServerLevel interiorLevel, ServerLevel frameLevel, BlockPos framePos) {
        // The overworld frame chunk usually unloads while the player is inside the diorama,
        // so force it loaded before reading the block entity — otherwise the snapshot never refreshes.
        frameLevel.getChunkAt(framePos);
        BlockEntity blockEntity = frameLevel.getBlockEntity(framePos);
        if (blockEntity instanceof DioramaFrameBlockEntity frame) {
            frame.refreshSnapshotFromInterior(interiorLevel);
            return frame;
        }
        return null;
    }
}
