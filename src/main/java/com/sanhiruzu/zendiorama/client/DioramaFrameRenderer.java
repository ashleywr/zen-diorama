package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlockEntity;
import com.sanhiruzu.zendiorama.core.MiniatureBlockStateCodec;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

public class DioramaFrameRenderer implements BlockEntityRenderer<DioramaFrameBlockEntity> {
    private static final float MINIATURE_SCALE = 1.0F / 16.0F;
    private static final int PLOT_SIZE = 15;

    public static boolean suppressMiniature = false;

    private final BlockRenderDispatcher blockRenderer;

    public DioramaFrameRenderer(BlockEntityRendererProvider.Context context) {
        this.blockRenderer = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(
            DioramaFrameBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay) {

        if (suppressMiniature) return;

        MiniatureSnapshot snapshot = blockEntity.getSnapshot();
        if (snapshot.entries().isEmpty()) return;

        boolean fullBright = DioramaConfig.MINIATURE_FULL_BRIGHT.get();
        boolean innerShadow = DioramaConfig.MINIATURE_INNER_SHADOW.get();

        // VBO caching bakes lighting into the geometry, so it is only valid when lighting is static
        // (full brightness). Otherwise fall back to per-frame immediate rendering.
        boolean useCache = DioramaConfig.MINIATURE_VBO_CACHE.get() && fullBright;
        if (useCache) {
            int flags = innerShadow ? 1 : 0;
            CachedBlockGeometry cache = blockEntity.renderCache instanceof CachedBlockGeometry c ? c : null;
            if (cache == null || !cache.matches(snapshot, flags)) {
                if (cache != null) cache.close();
                cache = CachedBlockGeometry.bake(snapshot, flags,
                        source -> bakeMiniature(source, snapshot, innerShadow));
                blockEntity.renderCache = cache;
            }
            cache.draw(poseStack.last().pose());
            return;
        }

        // Immediate fallback — release any geometry baked while caching was enabled.
        if (blockEntity.renderCache instanceof CachedBlockGeometry stale) {
            stale.close();
            blockEntity.renderCache = null;
        }

        poseStack.pushPose();
        applyMiniatureTransform(poseStack);
        renderImmediate(snapshot, poseStack, bufferSource, packedLight, fullBright, innerShadow);
        poseStack.popPose();
    }

    private static void applyMiniatureTransform(PoseStack poseStack) {
        // Center in the frame, scale to 1:16, and center the 15×15×15 grid inside the block
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.scale(MINIATURE_SCALE, MINIATURE_SCALE, MINIATURE_SCALE);
        poseStack.translate(-7.5, -7.5, -7.5);
    }

    @SuppressWarnings("deprecation")
    private void bakeMiniature(MultiBufferSource source, MiniatureSnapshot snapshot, boolean innerShadow) {
        PoseStack poseStack = new PoseStack();
        applyMiniatureTransform(poseStack);
        for (MiniatureSnapshot.Entry entry : snapshot.entries()) {
            BlockState state = MiniatureBlockStateCodec.decode(entry.blockStateId());
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;

            // Full brightness is guaranteed here (cache is gated on it), so world light is irrelevant.
            int light = computeLight(entry.x(), entry.y(), entry.z(), 0, true, innerShadow);
            poseStack.pushPose();
            poseStack.translate(entry.x(), entry.y(), entry.z());
            blockRenderer.renderSingleBlock(state, poseStack, source, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }

    @SuppressWarnings("deprecation")
    private void renderImmediate(
            MiniatureSnapshot snapshot,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int worldPackedLight,
            boolean fullBright,
            boolean innerShadow) {
        for (MiniatureSnapshot.Entry entry : snapshot.entries()) {
            BlockState state = MiniatureBlockStateCodec.decode(entry.blockStateId());
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;

            int light = computeLight(entry.x(), entry.y(), entry.z(), worldPackedLight, fullBright, innerShadow);
            poseStack.pushPose();
            poseStack.translate(entry.x(), entry.y(), entry.z());
            blockRenderer.renderSingleBlock(state, poseStack, bufferSource, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }

    private static int computeLight(int x, int y, int z, int worldLight, boolean fullBright, boolean innerShadow) {
        int baseLevel = fullBright ? 15 : Math.max(
                (worldLight & 0xFF) >> 4,
                (worldLight >> 16 & 0xFF) >> 4);

        if (innerShadow) {
            int borderDist = Math.min(
                    Math.min(x, PLOT_SIZE - 1 - x),
                    Math.min(Math.min(y, PLOT_SIZE - 1 - y),
                             Math.min(z, PLOT_SIZE - 1 - z)));
            float factor = borderDist == 0 ? 0.5F : borderDist == 1 ? 0.75F : 1.0F;
            baseLevel = Mth.floor(baseLevel * factor);
        }

        return LightTexture.pack(baseLevel, baseLevel);
    }
}
