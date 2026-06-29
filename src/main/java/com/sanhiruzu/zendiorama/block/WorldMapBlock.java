package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class WorldMapBlock extends BaseEntityBlock {
    public static final MapCodec<WorldMapBlock> CODEC = simpleCodec(WorldMapBlock::new);
    /** Direction the map surface faces (its outward normal). UP = floor, NSEW = wall. */
    public static final DirectionProperty FACING = DirectionProperty.create("facing",
            d -> d != Direction.DOWN);

    public WorldMapBlock(BlockBehaviour.Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction clicked = ctx.getClickedFace();
        // Only allow UP and horizontal wall faces (no ceiling)
        if (clicked == Direction.DOWN) clicked = Direction.UP;
        return defaultBlockState().setValue(FACING, clicked);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorldMapBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        if (!(level.getBlockEntity(pos) instanceof WorldMapBlockEntity wme)) return;
        // Try to inherit scale/zoom from an adjacent configured tile so the new tile matches.
        wme.detectRegionFromNeighbors();
        if (!wme.isConfigured()) {
            // Standalone first tile — center on the placer's feet with default zoom and sample now.
            WorldMapZoomLevel zoom = WorldMapZoomLevel.LEVELS[wme.getZoomIndex()];
            WorldMapZoomTuning.EffectiveZoom tuned = WorldMapZoomTuning.resolve(zoom, 1, 1);
            wme.configure((int) placer.getX(), (int) placer.getZ(), tuned.scale(), pos);
            wme.applyZoom(wme.getZoomIndex(), tuned.voxels());
            wme.markForImmediateRefresh();
        } else {
            // Joined an existing group — recenter the whole group around the geometric center
            // of the connected block positions. Using block positions (not mapCenterX/Z) means
            // the anchor is always the physical midpoint of the tile grid, regardless of any
            // prior zoom or compass recentering.
            // No immediate refresh: let the debounce accumulate rapid placements so adding
            // several tiles in a row only triggers one re-sample when changes settle.
            // Use the placer's world X/Z as the map center; recenterGroupOnPoint will
            // distribute the surrounding tiles relative to this anchor.
            WorldMapBlockEntity.recenterGroupOnPoint(level, pos, (int) placer.getX(), (int) placer.getZ());
        }
        WorldMapBlockEntity.refreshConnectedLayoutState(level, pos);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ZenDiorama.WORLD_MAP_ENTITY.get(), WorldMapBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WorldMapBlockEntity mapBe)) return InteractionResult.PASS;
        if (!mapBe.isLayoutValid()) {
            player.sendSystemMessage(Component.literal(
                    "Reconnect this map into a full square group: " + allowedGroupSummary() + "."));
            return InteractionResult.SUCCESS;
        }

        // Compass in hand → reconfigure map center to player position.
        if (player.getMainHandItem().is(net.minecraft.world.item.Items.COMPASS)) {
            mapBe.configure((int) player.getX(), (int) player.getZ(), mapBe.getBlocksPerTile());
            WorldMapBlockEntity.forceRefreshConnected(level, pos);
            player.sendSystemMessage(Component.translatable(
                    "message.zen_diorama.map.configured", (int) player.getX(), (int) player.getZ()));
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            // Shift+right-click → cycle zoom level. Normal right-click is reserved for refresh
            // so accidental clicks do not queue expensive map resampling at a different scale.
            int newZoom = (mapBe.getZoomIndex() + 1) % WorldMapZoomLevel.LEVELS.length;
            WorldMapZoomLevel zoom = WorldMapZoomLevel.LEVELS[newZoom];
            WorldMapBlockEntity.ConnectedGroup group = WorldMapBlockEntity.collectConnectedGroup(level, pos);
            WorldMapZoomTuning.EffectiveZoom tuned = group == null
                    ? WorldMapZoomTuning.resolve(zoom, 1, 1)
                    : WorldMapZoomTuning.resolve(zoom, group.width(), group.height());
            WorldMapBlockEntity.reZoomConnected(level, pos, newZoom);
            int effectiveVoxels = Math.min(tuned.voxels(), WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
            player.sendSystemMessage(Component.literal(
                    "Zoom [" + (newZoom + 1) + "/" + WorldMapZoomLevel.LEVELS.length + "]  "
                    + zoom.name() + "  — " + tuned.scale() + " blocks / " + voxelSummary(tuned.voxels())
                    + " per tile on " + groupLabel(group)
                    + "  (" + samplingSummary(tuned.scale(), effectiveVoxels) + ")"));
            return InteractionResult.SUCCESS;
        }

        // Right-click → refresh current map settings without changing zoom.
        WorldMapBlockEntity.forceRefreshConnected(level, pos);
        player.sendSystemMessage(Component.literal("Queued refresh for connected world map tiles."));
        return InteractionResult.SUCCESS;
    }

    private static String samplingSummary(int scale, int voxels) {
        if (scale <= voxels) return "every block sampled";
        return "1 voxel per " + (scale / voxels) + " blocks";
    }

    private static String voxelSummary(int requestedVoxels) {
        int effectiveVoxels = Math.min(requestedVoxels, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        if (effectiveVoxels == requestedVoxels) {
            return requestedVoxels + " voxels";
        }
        return requestedVoxels + " voxels requested, " + effectiveVoxels + " effective";
    }

    private static String groupLabel(@Nullable WorldMapBlockEntity.ConnectedGroup group) {
        if (group == null) return "1x1";
        return group.width() + "x" + group.height();
    }

    private static String allowedGroupSummary() {
        return DioramaConfig.allowedMapGroupSizes().stream()
                .map(size -> size + "x" + size)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void refreshLayoutsAround(Level level, BlockPos pos) {
        refreshLayoutAt(level, pos);
        for (Direction dir : Direction.values()) {
            refreshLayoutAt(level, pos.relative(dir));
        }
    }

    private static void refreshLayoutAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof WorldMapBlockEntity)) return;
        WorldMapBlockEntity.refreshConnectedLayoutState(level, pos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // Update the newly placed tile's own mask. neighborChanged fires on adjacent tiles
        // when this block is placed, but NOT on this block itself — so without this call
        // the new tile would always start with mask=0 (all 4 borders drawn).
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WorldMapBlockEntity wme) {
            wme.updateNeighborMask();
        }
        if (!level.isClientSide) {
            refreshLayoutsAround(level, pos);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WorldMapBlockEntity wme) {
            wme.updateNeighborMask();
        }
        if (!level.isClientSide) {
            refreshLayoutsAround(level, pos);
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    private static final VoxelShape SHAPE_FLOOR  = Block.box( 0,  0,  0, 16,  1, 16);
    private static final VoxelShape SHAPE_NORTH  = Block.box( 0,  0,  0, 16, 16,  1);
    private static final VoxelShape SHAPE_SOUTH  = Block.box( 0,  0, 15, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST   = Block.box(15,  0,  0, 16, 16, 16);
    private static final VoxelShape SHAPE_WEST   = Block.box( 0,  0,  0,  1, 16, 16);

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
            default    -> SHAPE_FLOOR;
        };
    }
}
