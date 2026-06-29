package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.block.WorldMapBlock;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class WorldMapRenderer implements BlockEntityRenderer<WorldMapBlockEntity> {
    private static final float MIN_BRIGHTNESS = 0.04f;
    private static final float MAX_BRIGHTNESS = 0.88f;
    private static final long GEOMETRY_BAKE_INTERVAL_NANOS = 20_000_000L;
    private static long lastGeometryBakeNanos;

    public WorldMapRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            WorldMapBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay) {

        if (!blockEntity.isConfigured()) return;
        boolean layoutValid = blockEntity.isLayoutValid();
        boolean waitingForChunks = layoutValid && blockEntity.isWaitingForChunks();
        MiniatureSnapshot snapshot = blockEntity.getSnapshot();
        if (layoutValid && !waitingForChunks && snapshot.entries().isEmpty()) return;

        float heightExaggeration = blockEntity.getEffectiveHeightExaggeration();
        float elevationTint = blockEntity.getEffectiveElevationTint();

        int renderGridSize = blockEntity.getSampledGridSize();
        int expectedGridSize = blockEntity.getExpectedSampledGridSize();

        // neighborMask is computed server-side and synced via NBT — no per-frame level queries.
        int neighborMask = blockEntity.getNeighborMask();

        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        WorldMapGeometry.PresentationLayout presentationLayout = new WorldMapGeometry.PresentationLayout(
                blockEntity.getPresentationTileX(),
                blockEntity.getPresentationTileZ(),
                blockEntity.getPresentationTilesWide(),
                blockEntity.getPresentationTilesTall());

        int cacheFlags = Float.floatToIntBits(heightExaggeration) * 31
                + Float.floatToIntBits(elevationTint) * 17
                + renderGridSize
                + expectedGridSize * 1009
                + neighborMask * 65537
                + presentationLayout.tileX() * 97
                + presentationLayout.tileZ() * 193
                + presentationLayout.tilesWide() * 389
                + presentationLayout.tilesTall() * 769
                + (layoutValid ? 0 : 0x40000000)
                + (waitingForChunks ? 0x20000000 : 0);
        if (waitingForChunks) {
            WorldMapGeometry waiting = blockEntity.renderCache instanceof WorldMapGeometry g ? g : null;
            if (waiting == null || !waiting.matches(blockEntity, cacheFlags)) {
                if (waiting != null) waiting.close();
                waiting = WorldMapGeometry.bakeWaitingLayout(blockEntity, cacheFlags, neighborMask, presentationLayout);
                blockEntity.renderCache = waiting;
            }
            int blockLight = unpackLightLevel(packedLight);
            int skyLight = unpackLightLevel(packedLight >> 16);
            int skyDarken = 0;
            if (level != null) {
                blockLight = level.getBrightness(LightLayer.BLOCK, pos);
                skyLight = level.getBrightness(LightLayer.SKY, pos);
                skyDarken = level.getSkyDarken();
            }
            float brightness = mapBrightnessForLightLevels(blockLight, skyLight, skyDarken) * 0.92f;
            applyFacingTransform(poseStack, level, pos);
            waiting.draw(poseStack.last().pose(), brightness);
            return;
        }
        if (!layoutValid && !blockEntity.isLayoutSevereInvalid()) {
            WorldMapGeometry waiting = blockEntity.renderCache instanceof WorldMapGeometry g ? g : null;
            if (waiting == null || !waiting.matches(blockEntity, cacheFlags)) {
                if (waiting != null) waiting.close();
                waiting = WorldMapGeometry.bakeWaitingLayout(blockEntity, cacheFlags, neighborMask, presentationLayout);
                blockEntity.renderCache = waiting;
            }
            int blockLight = unpackLightLevel(packedLight);
            int skyLight = unpackLightLevel(packedLight >> 16);
            int skyDarken = 0;
            if (level != null) {
                blockLight = level.getBrightness(LightLayer.BLOCK, pos);
                skyLight = level.getBrightness(LightLayer.SKY, pos);
                skyDarken = level.getSkyDarken();
            }
            float brightness = mapBrightnessForLightLevels(blockLight, skyLight, skyDarken) * 0.92f;
            applyFacingTransform(poseStack, level, pos);
            waiting.draw(poseStack.last().pose(), brightness);
            return;
        }
        if (!layoutValid) {
            WorldMapGeometry invalid = blockEntity.renderCache instanceof WorldMapGeometry g ? g : null;
            if (invalid == null || !invalid.matches(blockEntity, cacheFlags)) {
                if (invalid != null) invalid.close();
                invalid = WorldMapGeometry.bakeInvalidLayout(blockEntity, cacheFlags, neighborMask, presentationLayout);
                blockEntity.renderCache = invalid;
            }
            int blockLight = unpackLightLevel(packedLight);
            int skyLight = unpackLightLevel(packedLight >> 16);
            int skyDarken = 0;
            if (level != null) {
                blockLight = level.getBrightness(LightLayer.BLOCK, pos);
                skyLight = level.getBrightness(LightLayer.SKY, pos);
                skyDarken = level.getSkyDarken();
            }
            float brightness = mapBrightnessForLightLevels(blockLight, skyLight, skyDarken) * 0.9f;
            applyFacingTransform(poseStack, level, pos);
            invalid.draw(poseStack.last().pose(), brightness);
            return;
        }
        WorldMapLodCache cache = blockEntity.renderCache instanceof WorldMapLodCache c ? c : null;
        if (cache == null || !cache.matches(snapshot, cacheFlags)) {
            if (cache != null) cache.close();
            cache = new WorldMapLodCache(
                    snapshot,
                    cacheFlags,
                    heightExaggeration,
                    elevationTint,
                    neighborMask,
                    presentationLayout,
                    expectedGridSize);
            blockEntity.renderCache = cache;
        }
        double distanceSq = cameraDistanceSq(pos);
        WorldMapLodCache.LodSelection lodSelection = cache.selectionForDistance(distanceSq);
        WorldMapGeometry geo = readyOrBuildGeometry(cache, lodSelection.baseIndex());
        if (geo == null) return;

        int blockLight = unpackLightLevel(packedLight);
        int skyLight = unpackLightLevel(packedLight >> 16);
        int skyDarken = 0;
        if (level != null) {
            blockLight = level.getBrightness(LightLayer.BLOCK, pos);
            skyLight = level.getBrightness(LightLayer.SKY, pos);
            skyDarken = level.getSkyDarken();
        }
        float brightness = mapBrightnessForLightLevels(blockLight, skyLight, skyDarken);

        // Pulse brightness while the snapshot is being resampled so the player can see
        // the map is still loading. Sine wave over a 2-second cycle, 70%–100% of normal.
        if (blockEntity.isDirty() && !blockEntity.isWaitingForChunks()) {
            float phase = (float)(System.currentTimeMillis() % 2000L) / 2000f;
            brightness *= 0.70f + 0.30f * (float)Math.sin(phase * Math.PI * 2);
        }

        applyFacingTransform(poseStack, level, pos);
        geo.draw(poseStack.last().pose(), brightness);
        if (lodSelection.overlayIndex() >= 0 && lodSelection.overlayAlpha() > 0.0f) {
            WorldMapGeometry overlay = readyOrBuildGeometry(cache, lodSelection.overlayIndex());
            if (overlay != null) {
                overlay.draw(poseStack.last().pose(), brightness, lodSelection.overlayAlpha());
            }
        }
    }

    private static WorldMapGeometry readyOrBuildGeometry(WorldMapLodCache cache, int targetIndex) {
        WorldMapGeometry ready = cache.geometryIfReady(targetIndex);
        if (ready != null) {
            return ready;
        }

        int buildIndex = cache.nextMissingCoarseFirst(targetIndex);
        if (buildIndex >= 0) {
            cache.requestGeometry(buildIndex);
            if (cache.hasPreparedGeometry(buildIndex) && tryClaimGeometryBakeSlot()) {
                WorldMapGeometry uploaded = cache.uploadPreparedIfReady(buildIndex);
                if (uploaded != null) {
                    return uploaded;
                }
            }
        }

        return cache.coarsestReadyGeometry(targetIndex);
    }

    private static boolean tryClaimGeometryBakeSlot() {
        long now = System.nanoTime();
        if (now - lastGeometryBakeNanos < GEOMETRY_BAKE_INTERVAL_NANOS) {
            return false;
        }
        lastGeometryBakeNanos = now;
        return true;
    }

    static float mapBrightnessForLightLevels(int blockLight, int skyLight, int skyDarken) {
        float block = Math.clamp(blockLight / 15.0f, 0.0f, 1.0f);
        float darkenedSky = Math.clamp((skyLight - skyDarken) / 15.0f, 0.0f, 1.0f);
        float skyFade = 1.0f - Math.clamp(skyDarken / 15.0f, 0.0f, 1.0f);
        float ambient = Math.max(block, darkenedSky * skyFade);
        float boostedAmbient = (float) Math.pow(ambient, 1.45d);
        return MIN_BRIGHTNESS + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * boostedAmbient;
    }

    private static int unpackLightLevel(int packedChannel) {
        return (packedChannel & 0xFF) >> 4;
    }

    private static double cameraDistanceSq(BlockPos pos) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = camera.x - (pos.getX() + 0.5);
        double dy = camera.y - (pos.getY() + 0.5);
        double dz = camera.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Applies a PoseStack transform that orients the floor-space geometry (XZ plane, +Y up)
     * onto the correct block face for {@code facing}.
     *
     * <p>Geometry coordinates after transform:
     * <ul>
     *   <li>UP   – no change (floor tile)
     *   <li>SOUTH – surface at Z=1, voxels project –Z toward viewer
     *   <li>NORTH – surface at Z=0, voxels project +Z toward viewer
     *   <li>EAST  – surface at X=1, voxels project –X toward viewer
     *   <li>WEST  – surface at X=0, voxels project +X toward viewer (X↔Y swap)
     * </ul>
     *
     * <p>WorldMapGeometry.draw() calls RenderSystem.disableCull() before drawing, so
     * the WEST reflection (det = –1) renders correctly without winding issues.
     */
    private static void applyFacingTransform(PoseStack poseStack, Level level, BlockPos pos) {
        if (level == null) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(WorldMapBlock.FACING)) return;
        Direction facing = state.getValue(WorldMapBlock.FACING);
        switch (facing) {
            case SOUTH -> {
                poseStack.translate(0, 0, 1);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            }
            case NORTH -> {
                poseStack.translate(0, 1, 0);
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
            }
            case EAST -> {
                poseStack.translate(1, 0, 0);
                poseStack.mulPose(Axis.ZP.rotationDegrees(90));
            }
            case WEST -> {
                // Swap X↔Y: geo (x,y,z) → block (y,x,z).
                // Surface at X=0, voxels project +X. Culling is off so the det=−1 is safe.
                poseStack.last().pose().mul(new Matrix4f(
                        0f, 1f, 0f, 0f,
                        1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 0f, 0f, 1f));
            }
            default -> { /* UP — no transform */ }
        }
    }

}
