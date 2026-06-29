package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.server.DioramaReturnData;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class DioramaControlBlock extends Block {
    public static final MapCodec<DioramaControlBlock> CODEC = simpleCodec(DioramaControlBlock::new);

    public DioramaControlBlock(BlockBehaviour.Properties properties) {
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
        if (!(level instanceof ServerLevel interiorLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        DioramaReturnData.ReturnTarget target = DioramaReturnData.get(serverPlayer);
        if (target == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.zen_diorama.control.no_frame"), true);
            return InteractionResult.CONSUME;
        }

        ServerLevel frameLevel = serverPlayer.server.getLevel(target.dimension());
        if (frameLevel == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.zen_diorama.exit.missing_dimension"), true);
            return InteractionResult.CONSUME;
        }

        BlockEntity blockEntity = frameLevel.getBlockEntity(target.framePos());
        if (!(blockEntity instanceof DioramaFrameBlockEntity frame)) {
            serverPlayer.displayClientMessage(Component.translatable("message.zen_diorama.control.no_frame"), true);
            return InteractionResult.CONSUME;
        }

        frame.setAlwaysLoaded(!frame.isAlwaysLoaded());
        frame.updateForcedChunks(interiorLevel);
        interiorLevel.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.8F, frame.isAlwaysLoaded() ? 1.25F : 0.75F);
        serverPlayer.displayClientMessage(Component.translatable(
                frame.isAlwaysLoaded()
                        ? "message.zen_diorama.control.enabled"
                        : "message.zen_diorama.control.disabled"), true);
        return InteractionResult.CONSUME;
    }
}
