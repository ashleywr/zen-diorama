package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.PlotLip;
import com.sanhiruzu.zendiorama.core.PlotOrigin;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.DioramaClientboundPayloadHandler;
import com.sanhiruzu.zendiorama.server.DioramaPendingTeleports;
import com.sanhiruzu.zendiorama.server.DioramaReturnData;
import com.sanhiruzu.zendiorama.server.DioramaPlotSavedData;
import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class DioramaFrameBlock extends BaseEntityBlock {
    private static final int LEGACY_TERRAIN_CLEANUP_RADIUS = 12 * 16;
    private static final int LEGACY_TERRAIN_CLEANUP_HEIGHT = 12;
    private static final net.minecraft.world.level.block.state.BlockState PRIVATE_WALL = Blocks.BARRIER.defaultBlockState();

    public static final MapCodec<DioramaFrameBlock> CODEC = simpleCodec(DioramaFrameBlock::new);

    public DioramaFrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            if (!player.isShiftKeyDown()) {
                DioramaClientboundPayloadHandler.handleTransition(new DioramaTransitionPayload(true, null));
            }
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DioramaFrameBlockEntity frame && player instanceof ServerPlayer serverPlayer) {
            frame.ensureAssigned(pos);
            if (player.isShiftKeyDown()) {
                pickupFrame(level, pos, serverPlayer, frame);
                return InteractionResult.CONSUME;
            }
            teleportIntoDiorama(serverPlayer, frame, hit);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DioramaFrameBlockEntity frame && player instanceof ServerPlayer serverPlayer) {
            frame.ensureAssigned(pos);
            pickupFrame(level, pos, serverPlayer, frame);
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    private static void pickupFrame(Level level, BlockPos pos, ServerPlayer player, DioramaFrameBlockEntity frame) {
        if (level instanceof ServerLevel serverLevel && frame.getFrameId() != null && isOccupied(serverLevel, frame.getFrameId())) {
            player.displayClientMessage(Component.translatable("message.zen_diorama.frame.occupied"), true);
            return;
        }
        ItemStack stack = ZenDiorama.DIORAMA_FRAME_ITEM.get().getDefaultInstance();
        CompoundTag portableData = frame.savePortableReference();
        BlockItem.setBlockEntityData(stack, ZenDiorama.DIORAMA_FRAME_ENTITY.get(), portableData);
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel interiorLevel = serverLevel.getServer().getLevel(DioramaDimensions.DIORAMA_LEVEL);
            if (interiorLevel != null && frame.isAlwaysLoaded()) {
                frame.setAlwaysLoaded(false);
                frame.updateForcedChunks(interiorLevel);
                frame.setAlwaysLoaded(true);
            }
            frame.clearPersistentPosition(serverLevel);
        }

        level.removeBlock(pos, false);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void teleportIntoDiorama(ServerPlayer player, DioramaFrameBlockEntity frame, BlockHitResult hit) {
        ServerLevel target = player.server.getLevel(DioramaDimensions.DIORAMA_LEVEL);
        if (!(player.level() instanceof ServerLevel source)) {
            return;
        }
        if (target == null || frame.getPlotOrigin() == null) {
            return;
        }

        sendSkyboxSnapshot(player, source, frame);
        DioramaReturnData.store(player, source, frame.getFrameId(), frame.getBlockPos());
        target.setDayTime(source.getDayTime());
        ensurePlotBoundary(target, frame);
        ensurePlotInterior(target, frame);
        frame.setPlotChunksForced(target, true);

        BlockPos spawnPos = findEntrySpawn(target, frame, hit);
        double x = spawnPos.getX() + 0.5D;
        double y = spawnPos.getY();
        double z = spawnPos.getZ() + 0.5D;
        source.playSound(null, frame.getBlockPos(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 0.7F, 1.35F);
        // framePos carried so client can perform cubemap capture from the correct location
        PacketDistributor.sendToPlayer(player, new DioramaTransitionPayload(true, frame.getBlockPos()));
        // Delay teleport so the client can complete all 6 GPU cubemap frames before being moved to the diorama dimension
        DioramaPendingTeleports.enqueue(player, target, x, y, z, player.getYRot(), player.getXRot(),
                DioramaConfig.SKYBOX_CAPTURE_DELAY_TICKS.get(),
                () -> target.playSound(null, spawnPos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.65F, 0.6F));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof DioramaFrameBlockEntity frame) {
            frame.registerPersistentPosition(serverLevel);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof DioramaFrameBlockEntity frame) {
            destroyLinkedInterior(serverLevel, frame);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    private static void destroyLinkedInterior(ServerLevel frameLevel, DioramaFrameBlockEntity frame) {
        ServerLevel interior = frameLevel.getServer().getLevel(DioramaDimensions.DIORAMA_LEVEL);
        if (interior == null || frame.getPlotOrigin() == null) {
            return;
        }

        frame.setPlotChunksForced(interior, false);
        int plotSize = DioramaConfig.plotSize();
        PlotOrigin origin = frame.getPlotOrigin();
        int minX = origin.x() - 3;
        int maxX = origin.x() + plotSize + 2;
        int minZ = origin.z() - 3;
        int maxZ = origin.z() + plotSize + 2;
        int minY = origin.y();
        int maxY = origin.y() + plotSize + 2;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos clearPos = new BlockPos(x, y, z);
                    if (!interior.getBlockState(clearPos).isAir()) {
                        interior.setBlock(clearPos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        if (frame.getFrameId() != null) {
            DioramaPlotSavedData.get(frameLevel).remove(frame.getFrameId());
        }
    }

    private static boolean isOccupied(ServerLevel level, java.util.UUID frameId) {
        ServerLevel interior = level.getServer().getLevel(DioramaDimensions.DIORAMA_LEVEL);
        if (interior == null) {
            return false;
        }
        for (ServerPlayer player : interior.players()) {
            DioramaReturnData.ReturnTarget target = DioramaReturnData.get(player);
            if (target != null && frameId.equals(target.frameId())) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos findEntrySpawn(ServerLevel level, DioramaFrameBlockEntity frame, BlockHitResult hit) {
        PlotOrigin plotOrigin = frame.getPlotOrigin();
        int plotSize = DioramaConfig.plotSize();
        int groundY = Math.max(level.getMinBuildHeight() + 1, plotOrigin.y() + 1);
        BlockPos preferred = clickedInteriorPos(plotOrigin, groundY, plotSize, hit);
        if (isSafeEntrySpawn(level, preferred)) {
            return preferred;
        }

        for (int radius = 1; radius < plotSize; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    BlockPos candidate = preferred.offset(dx, 0, dz);
                    if (isInsideInterior(plotOrigin, groundY, plotSize, candidate) && isSafeEntrySpawn(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return new BlockPos(plotOrigin.x() + plotSize / 2, groundY + 1, plotOrigin.z() + plotSize / 2);
    }

    private static BlockPos clickedInteriorPos(PlotOrigin plotOrigin, int groundY, int plotSize, BlockHitResult hit) {
        BlockPos framePos = hit.getBlockPos();
        Vec3 localHit = hit.getLocation().subtract(framePos.getX(), framePos.getY(), framePos.getZ());
        int interiorX = clamp((int) Math.floor(localHit.x() * plotSize), 0, plotSize - 1);
        int interiorZ = clamp((int) Math.floor(localHit.z() * plotSize), 0, plotSize - 1);
        return new BlockPos(plotOrigin.x() + interiorX, groundY + 1, plotOrigin.z() + interiorZ);
    }

    private static boolean isSafeEntrySpawn(ServerLevel level, BlockPos feetPos) {
        return level.getBlockState(feetPos.below()).isFaceSturdy(level, feetPos.below(), Direction.UP)
                && level.getBlockState(feetPos).isAir()
                && level.getBlockState(feetPos.above()).isAir();
    }

    private static boolean isInsideInterior(PlotOrigin plotOrigin, int groundY, int plotSize, BlockPos pos) {
        return pos.getX() >= plotOrigin.x()
                && pos.getX() < plotOrigin.x() + plotSize
                && pos.getZ() >= plotOrigin.z()
                && pos.getZ() < plotOrigin.z() + plotSize
                && pos.getY() >= groundY + 1
                && pos.getY() < groundY + plotSize;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void sendSkyboxSnapshot(ServerPlayer player, ServerLevel source, DioramaFrameBlockEntity frame) {
        BlockPos sourcePos = frame.getBlockPos();
        // Atmospheric metadata only — the actual cubemap is captured client-side by DioramaOffscreenCubemap
        int sampleDistance = 12;
        PacketDistributor.sendToPlayer(player, new DioramaSkySnapshotPayload(
                source.dimension().location(),
                sourcePos,
                source.getBiome(sourcePos).value().getSkyColor(),
                captureSkyColorSimple(source, sourcePos, 0, 0, -sampleDistance),
                captureSkyColorSimple(source, sourcePos, 0, 0, sampleDistance),
                captureSkyColorSimple(source, sourcePos, sampleDistance, 0, 0),
                captureSkyColorSimple(source, sourcePos, -sampleDistance, 0, 0),
                captureSkyColorSimple(source, sourcePos, 0, sampleDistance, 0),
                captureSkyColorSimple(source, sourcePos, 0, -sampleDistance, 0),
                source.getDayTime(),
                source.getRainLevel(1.0F),
                source.getThunderLevel(1.0F)
        ));
    }

    // Single directional sky color — kept for the 6-directional fog tint in DioramaSkySnapshotState
    private static int captureSkyColorSimple(ServerLevel source, BlockPos sourcePos, int dx, int dy, int dz) {
        int skyColor = source.getBiome(sourcePos).value().getSkyColor();
        Vec3 direction = new Vec3(dx, dy, dz).normalize();
        if (direction.lengthSqr() < 1.0e-6) return skyColor;
        Vec3 sky = colorToVec(skyColor);
        Vec3 accumulated = Vec3.ZERO;
        double weight = 0.0D;
        int steps = Math.max(4, Math.min(24, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)))));
        for (int i = 1; i <= steps; i++) {
            BlockPos pos = BlockPos.containing(
                    sourcePos.getX() + 0.5D + direction.x * i,
                    sourcePos.getY() + 0.5D + direction.y * i,
                    sourcePos.getZ() + 0.5D + direction.z * i);
            BlockState state = source.getBlockState(pos);
            if (state.isAir()) continue;
            int mapColor = state.getMapColor(source, pos).col;
            int emission = state.getLightEmission(source, pos);
            double blockWeight = (emission > 0 ? 2.5D + emission / 4.0D : 1.5D) / Math.sqrt(i);
            Vec3 blockColor = colorToVec(mapColor);
            if (emission > 0) blockColor = blockColor.lerp(new Vec3(1.0D, 0.86D, 0.55D), 0.45D);
            accumulated = accumulated.add(blockColor.scale(blockWeight));
            weight += blockWeight;
        }
        if (weight <= 0.0D) return vecToColor(sky);
        Vec3 roomTint = accumulated.scale(1.0D / weight);
        double blend = Math.min(0.72D, weight / 5.0D);
        return vecToColor(sky.lerp(roomTint, blend));
    }

    private static Vec3 colorToVec(int color) {
        return new Vec3(
                (double) ((color >> 16) & 0xFF) / 255.0D,
                (double) ((color >> 8) & 0xFF) / 255.0D,
                (double) (color & 0xFF) / 255.0D);
    }

    private static int vecToColor(Vec3 color) {
        int red = clamp((int) Math.round(color.x * 255.0D), 0, 255);
        int green = clamp((int) Math.round(color.y * 255.0D), 0, 255);
        int blue = clamp((int) Math.round(color.z * 255.0D), 0, 255);
        return red << 16 | green << 8 | blue;
    }

    private static void ensurePlotBoundary(ServerLevel level, DioramaFrameBlockEntity frame) {
        int plotSize = DioramaConfig.plotSize();
        int minX = frame.getPlotOrigin().x() - 1;
        int maxX = frame.getPlotOrigin().x() + plotSize;
        int minZ = frame.getPlotOrigin().z() - 1;
        int maxZ = frame.getPlotOrigin().z() + plotSize;
        int minY = frame.getPlotOrigin().y();
        int maxY = frame.getPlotOrigin().y() + plotSize + 1;

        for (int x = minX; x <= maxX; x++) {
            fillBoundaryColumn(level, PRIVATE_WALL, x, minZ, minY, maxY);
            fillBoundaryColumn(level, PRIVATE_WALL, x, maxZ, minY, maxY);
        }
        for (int z = minZ; z <= maxZ; z++) {
            fillBoundaryColumn(level, PRIVATE_WALL, minX, z, minY, maxY);
            fillBoundaryColumn(level, PRIVATE_WALL, maxX, z, minY, maxY);
        }
        fillBoundaryPlane(level, minX, maxX, minZ, maxZ, minY);
        fillBoundaryPlane(level, minX, maxX, minZ, maxZ, maxY);
    }

    private static void ensurePlotInterior(ServerLevel level, DioramaFrameBlockEntity frame) {
        PlotOrigin plotOrigin = frame.getPlotOrigin();
        int plotSize = DioramaConfig.plotSize();
        int groundY = Math.max(level.getMinBuildHeight() + 1, plotOrigin.y() + 1);

        ensureExitBlock(level, plotOrigin, groundY, plotSize);
        ensureControlBlock(level, plotOrigin, groundY, plotSize);

        if (frame.isPlotInitialized()) {
            return;
        }

        clearLegacyGeneratedTerrain(level, plotOrigin, groundY, plotSize);
        ensurePlotFloor(level, plotOrigin, groundY, plotSize);
        ensureEdgeLip(level, plotOrigin, groundY, plotSize);
        frame.markPlotInitialized();
    }

    private static void clearLegacyGeneratedTerrain(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        int protectedMinX = plotOrigin.x() - 1;
        int protectedMaxX = plotOrigin.x() + plotSize;
        int protectedMinZ = plotOrigin.z() - 1;
        int protectedMaxZ = plotOrigin.z() + plotSize;
        int minX = plotOrigin.x() - LEGACY_TERRAIN_CLEANUP_RADIUS;
        int maxX = plotOrigin.x() + plotSize + LEGACY_TERRAIN_CLEANUP_RADIUS;
        int minZ = plotOrigin.z() - LEGACY_TERRAIN_CLEANUP_RADIUS;
        int maxZ = plotOrigin.z() + plotSize + LEGACY_TERRAIN_CLEANUP_RADIUS;
        int minY = level.getMinBuildHeight();
        int maxY = Math.min(level.getMaxBuildHeight() - 1, groundY + LEGACY_TERRAIN_CLEANUP_HEIGHT);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x >= protectedMinX && x <= protectedMaxX && z >= protectedMinZ && z <= protectedMaxZ) {
                    continue;
                }

                for (int y = minY; y <= maxY; y++) {
                    BlockPos clearPos = new BlockPos(x, y, z);
                    if (!level.getBlockState(clearPos).isAir()) {
                        level.setBlock(clearPos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void ensurePlotFloor(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        for (int x = plotOrigin.x(); x < plotOrigin.x() + plotSize; x++) {
            for (int z = plotOrigin.z(); z < plotOrigin.z() + plotSize; z++) {
                BlockPos floorPos = new BlockPos(x, groundY, z);
                BlockPos supportPos = floorPos.below();

                if (level.getBlockState(supportPos).isAir()) {
                    level.setBlockAndUpdate(supportPos, Blocks.BEDROCK.defaultBlockState());
                }
                if (!level.getBlockState(floorPos).is(Blocks.GRASS_BLOCK)) {
                    level.setBlockAndUpdate(floorPos, Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private static void ensureEdgeLip(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        BlockState edge = ZenDiorama.DIORAMA_EDGE.get().defaultBlockState();
        for (int[] cell : PlotLip.perimeter(
                plotOrigin.x(), groundY + 1, plotOrigin.z(), plotSize)) {
            BlockPos lipPos = new BlockPos(cell[0], cell[1], cell[2]);
            if (level.getBlockState(lipPos).isAir()) {
                level.setBlockAndUpdate(lipPos, edge);
            }
        }
    }

    private static void ensureExitBlock(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        int exitX = plotOrigin.x() + plotSize / 2;
        int exitY = groundY + 1;
        int exitZ = plotOrigin.z() + 1;
        BlockPos exitPos = new BlockPos(exitX, exitY, exitZ);

        if (level.getBlockState(exitPos).isAir()) {
            level.setBlockAndUpdate(exitPos, ZenDiorama.DIORAMA_EXIT.get().defaultBlockState());
        }
    }

    private static void ensureControlBlock(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        int controlX = plotOrigin.x() + plotSize - 2;
        int controlY = groundY + 1;
        int controlZ = plotOrigin.z() + plotSize - 2;
        BlockPos controlPos = new BlockPos(controlX, controlY, controlZ);

        if (level.getBlockState(controlPos).isAir()) {
            level.setBlockAndUpdate(controlPos, ZenDiorama.DIORAMA_CONTROL.get().defaultBlockState());
        }
    }

    private static void fillBoundaryColumn(ServerLevel level, net.minecraft.world.level.block.state.BlockState boundary, int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            BlockPos wallPos = new BlockPos(x, y, z);
            if (level.getBlockState(wallPos).isAir() || !level.getBlockState(wallPos).is(Blocks.BARRIER)) {
                level.setBlockAndUpdate(wallPos, boundary);
            }
        }
    }

    private static void fillBoundaryPlane(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos wallPos = new BlockPos(x, y, z);
                if (level.getBlockState(wallPos).isAir() || !level.getBlockState(wallPos).is(Blocks.BARRIER)) {
                    level.setBlockAndUpdate(wallPos, PRIVATE_WALL);
                }
            }
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DioramaFrameBlockEntity(pos, state);
    }
}
