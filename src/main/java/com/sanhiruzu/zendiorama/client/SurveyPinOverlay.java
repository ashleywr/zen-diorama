package com.sanhiruzu.zendiorama.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sanhiruzu.zendiorama.core.SurveyPinMarker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.List;

/** A full-bright, depth-independent overlay for Survey Pins on a World Map. */
public final class SurveyPinOverlay implements AutoCloseable {
    private static final float BORDER_INSET = 0.052F;
    private static final float OUTER_RADIUS = 0.042F;
    private static final float INNER_RADIUS = 0.017F;

    private final VertexBuffer vbo;
    private final List<SurveyPinMarker> pins;
    private final int centerX;
    private final int centerZ;
    private final int blocksPerTile;
    private final int neighborMask;
    private boolean closed;

    private SurveyPinOverlay(VertexBuffer vbo, List<SurveyPinMarker> pins,
            int centerX, int centerZ, int blocksPerTile, int neighborMask) {
        this.vbo = vbo;
        this.pins = List.copyOf(pins);
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.blocksPerTile = blocksPerTile;
        this.neighborMask = neighborMask;
    }

    public static SurveyPinOverlay bake(List<SurveyPinMarker> pins,
            int centerX, int centerZ, int blocksPerTile, int neighborMask) {
        ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(256, pins.size() * 8 * 16));
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (SurveyPinMarker pin : pins) {
            float x = mapCoordinate(pin.worldX(), centerX, blocksPerTile,
                    (neighborMask & 4) == 0, (neighborMask & 8) == 0);
            float z = mapCoordinate(pin.worldZ(), centerZ, blocksPerTile,
                    (neighborMask & 1) == 0, (neighborMask & 2) == 0);
            addDiamond(builder, x, z, OUTER_RADIUS, 0.124F, pin.color(), 130);
            addDiamond(builder, x, z, INNER_RADIUS, 0.126F, 0xFFFFFF, 255);
        }

        MeshData mesh = builder.build();
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        if (mesh != null) {
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
        }
        bytes.close();
        return new SurveyPinOverlay(vbo, pins, centerX, centerZ, blocksPerTile, neighborMask);
    }

    public boolean matches(List<SurveyPinMarker> pins,
            int centerX, int centerZ, int blocksPerTile, int neighborMask) {
        return !closed
                && this.pins.equals(pins)
                && this.centerX == centerX
                && this.centerZ == centerZ
                && this.blocksPerTile == blocksPerTile
                && this.neighborMask == neighborMask;
    }

    public void draw(Matrix4f poseMatrix, float pulse) {
        if (closed || pins.isEmpty()) return;

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseMatrix);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(pulse, pulse, pulse, 1.0F);
        vbo.bind();
        vbo.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vbo.close();
        }
    }

    private static float mapCoordinate(int worldCoordinate, int mapCenter, int blocksPerTile,
            boolean insetAtMinimum, boolean insetAtMaximum) {
        float minimum = insetAtMinimum ? BORDER_INSET : 0.0F;
        float maximum = insetAtMaximum ? 1.0F - BORDER_INSET : 1.0F;
        float normalized = ((worldCoordinate + 0.5F) - (mapCenter - blocksPerTile / 2.0F)) / blocksPerTile;
        return minimum + Math.clamp(normalized, 0.0F, 1.0F) * (maximum - minimum);
    }

    private static void addDiamond(BufferBuilder builder, float x, float z, float radius,
            float y, int color, int alpha) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        builder.addVertex(x, y, z - radius).setColor(red, green, blue, alpha);
        builder.addVertex(x - radius, y, z).setColor(red, green, blue, alpha);
        builder.addVertex(x, y, z + radius).setColor(red, green, blue, alpha);
        builder.addVertex(x + radius, y, z).setColor(red, green, blue, alpha);
    }
}
