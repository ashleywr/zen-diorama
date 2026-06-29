package com.sanhiruzu.zendiorama.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class DioramaSkyboxRenderer {
    private static final float SIZE = 96.0F;

    private DioramaSkyboxRenderer() {
    }

    public static void renderSky(Matrix4f modelViewMatrix) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(modelViewMatrix);

        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        Matrix4f matrix = poseStack.last().pose();

        if (DioramaSkyboxTextures.isReady()) {
            renderTextured(matrix);
        } else {
            renderFallback(matrix);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    private static void renderTextured(Matrix4f matrix) {
        float s = SIZE;
        // 0=north(-Z), 1=south(+Z), 2=east(+X), 3=west(-X), 4=up(+Y), 5=down(-Y)
        renderFace(matrix, DioramaSkyboxTextures.location(0),
                -s, -s, -s,   s, -s, -s,   s,  s, -s,  -s,  s, -s);
        renderFace(matrix, DioramaSkyboxTextures.location(1),
                 s, -s,  s,  -s, -s,  s,  -s,  s,  s,   s,  s,  s);
        renderFace(matrix, DioramaSkyboxTextures.location(2),
                 s, -s, -s,   s, -s,  s,   s,  s,  s,   s,  s, -s);
        renderFace(matrix, DioramaSkyboxTextures.location(3),
                -s, -s,  s,  -s, -s, -s,  -s,  s, -s,  -s,  s,  s);
        renderFace(matrix, DioramaSkyboxTextures.location(4),
                -s,  s, -s,   s,  s, -s,   s,  s,  s,  -s,  s,  s);
        renderFace(matrix, DioramaSkyboxTextures.location(5),
                -s, -s,  s,   s, -s,  s,   s, -s, -s,  -s, -s, -s);
    }

    private static void renderFace(Matrix4f matrix, ResourceLocation texture,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x1, y1, z1).setUv(0.0F, 1.0F);
        buffer.addVertex(matrix, x2, y2, z2).setUv(1.0F, 1.0F);
        buffer.addVertex(matrix, x3, y3, z3).setUv(1.0F, 0.0F);
        buffer.addVertex(matrix, x4, y4, z4).setUv(0.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void renderFallback(Matrix4f matrix) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        float s = SIZE;
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Simple sky-blue fallback quad for each face
        int r = 143, g = 177, b = 237;
        addQuad(buffer, matrix, -s, -s, -s,  s, -s, -s,  s,  s, -s, -s,  s, -s, r, g, b);
        addQuad(buffer, matrix,  s, -s,  s, -s, -s,  s, -s,  s,  s,  s,  s,  s, r, g, b);
        addQuad(buffer, matrix,  s, -s, -s,  s, -s,  s,  s,  s,  s,  s,  s, -s, r, g, b);
        addQuad(buffer, matrix, -s, -s,  s, -s, -s, -s, -s,  s, -s, -s,  s,  s, r, g, b);
        addQuad(buffer, matrix, -s,  s, -s,  s,  s, -s,  s,  s,  s, -s,  s,  s, r, g, b);
        addQuad(buffer, matrix, -s, -s,  s,  s, -s,  s,  s, -s, -s, -s, -s, -s, r, g, b);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4,
            int r, int g, int b) {
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, 255);
        buffer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, 255);
    }
}
