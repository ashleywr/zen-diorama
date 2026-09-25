package com.sanhiruzu.zendiorama.block;

import com.mojang.serialization.MapCodec;
import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A subtle in-world stake which is rendered as a glowing point of interest on World Maps. */
public final class SurveyPinBlock extends BaseEntityBlock {
    public static final MapCodec<SurveyPinBlock> CODEC = simpleCodec(SurveyPinBlock::new);
    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 10, 11);

    public SurveyPinBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(COLOR, DyeColor.CYAN));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COLOR);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SurveyPinBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        DyeColor next = DyeColor.byId((state.getValue(COLOR).getId() + 1) % DyeColor.values().length);
        level.setBlock(pos, state.setValue(COLOR, next), Block.UPDATE_CLIENTS);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            WorldMapBlockEntity.refreshSurveyPinsAt(serverLevel, pos);
        }
        player.sendSystemMessage(Component.translatableWithFallback(
                "message.zen_diorama.survey_pin.color",
                "Survey Pin colour: %s",
                Component.translatable("color.minecraft." + next.getName())));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.is(oldState.getBlock())
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            WorldMapBlockEntity.refreshSurveyPinsAt(serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            WorldMapBlockEntity.refreshSurveyPinsAt(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
