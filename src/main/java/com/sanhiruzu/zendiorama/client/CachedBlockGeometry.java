package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Compiled, GPU-resident geometry for a static block snapshot.
 *
 * <p>Block models are expensive to tessellate, so a {@link net.minecraft.client.renderer.blockentity.BlockEntityRenderer}
 * that draws thousands of blocks must not re-emit them every frame. This helper bakes the geometry once into a
 * {@link VertexBuffer} per {@link RenderType} (keyed by the snapshot it was built from) and replays those buffers
 * each frame for a near-zero per-frame cost.
 *
 * <p>Light is baked into the vertices, so a cached snapshot must only be used where lighting is static (e.g. full
 * brightness). Callers re-bake by comparing {@link #matches(Object, int)} against the current snapshot reference.
 */
public final class CachedBlockGeometry implements AutoCloseable {
    private static final AtomicBoolean DIAGNOSTIC_LOGGED = new AtomicBoolean(false);

    private final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
    private final Object key;
    private final int flags;
    private boolean closed;

    private CachedBlockGeometry(Object key, int flags) {
        this.key = key;
        this.flags = flags;
    }

    /**
     * Bake every block emitted by {@code emitter} into per-RenderType vertex buffers.
     * Must be called on the render thread.
     *
     * @param key   identity-compared cache key (typically the snapshot instance)
     * @param flags extra invalidation bits folded into the key (e.g. lighting options)
     */
    public static CachedBlockGeometry bake(Object key, int flags, Consumer<MultiBufferSource> emitter) {
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> scratch = new LinkedHashMap<>();
        MultiBufferSource source = renderType -> builders.computeIfAbsent(renderType, rt -> {
            ByteBufferBuilder bb = new ByteBufferBuilder(rt.bufferSize());
            scratch.put(rt, bb);
            return new BufferBuilder(bb, rt.mode(), rt.format());
        });

        emitter.accept(source);

        CachedBlockGeometry cache = new CachedBlockGeometry(key, flags);
        int totalVertices = 0;
        for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
            MeshData mesh = entry.getValue().build();
            if (mesh == null) {
                continue;
            }
            totalVertices += mesh.drawState().vertexCount();
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh); // takes ownership and closes the MeshData
            VertexBuffer.unbind();
            cache.buffers.put(entry.getKey(), vbo);
        }
        scratch.values().forEach(ByteBufferBuilder::close);

        if (DIAGNOSTIC_LOGGED.compareAndSet(false, true)) {
            // One-shot: tells us whether an invisible map is a bake problem (zero geometry)
            // or a draw problem (geometry exists but does not appear).
            ZenDiorama.LOGGER.info(
                    "[zen_diorama] CachedBlockGeometry.bake: renderTypes={} (built buffers={}), totalVertices={}",
                    builders.size(), cache.buffers.size(), totalVertices);
        }
        return cache;
    }

    /** True if this cache is still valid for the given key/flags. */
    public boolean matches(Object key, int flags) {
        return !closed && this.key == key && this.flags == flags;
    }

    /**
     * Replay all cached buffers.
     *
     * @param poseMatrix the block entity's pose matrix ({@code poseStack.last().pose()}), which on a
     *                   {@link net.minecraft.client.renderer.blockentity.BlockEntityRenderer} contains only the
     *                   per-block translation. The camera rotation lives in {@link RenderSystem#getModelViewMatrix()}
     *                   and is composed in here so {@link VertexBuffer#drawWithShader} gets a complete model-view.
     */
    public void draw(Matrix4f poseMatrix) {
        if (closed) {
            return;
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseMatrix);
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        for (Map.Entry<RenderType, VertexBuffer> entry : buffers.entrySet()) {
            RenderType rt = entry.getKey();
            ShaderInstance shader = shaderFor(rt);
            if (shader == null) {
                continue;
            }
            rt.setupRenderState();
            VertexBuffer vbo = entry.getValue();
            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            rt.clearRenderState();
        }
    }

    private static ShaderInstance shaderFor(RenderType rt) {
        if (rt == RenderType.solid()) {
            return GameRenderer.getRendertypeSolidShader();
        }
        if (rt == RenderType.cutoutMipped()) {
            return GameRenderer.getRendertypeCutoutMippedShader();
        }
        if (rt == RenderType.cutout()) {
            return GameRenderer.getRendertypeCutoutShader();
        }
        if (rt == RenderType.translucent()) {
            return GameRenderer.getRendertypeTranslucentShader();
        }
        if (rt == RenderType.tripwire()) {
            return GameRenderer.getRendertypeTripwireShader();
        }
        // Block models only use the chunk render layers above; fall back to solid for anything unexpected.
        return GameRenderer.getRendertypeSolidShader();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        buffers.values().forEach(VertexBuffer::close);
        buffers.clear();
    }
}
