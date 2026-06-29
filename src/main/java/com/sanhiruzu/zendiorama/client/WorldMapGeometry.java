package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.MiniatureBlockStateCodec;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.sanhiruzu.zendiorama.core.SurfaceClassifier;
import com.sanhiruzu.zendiorama.core.WorldMapProminentObjects;
import com.sanhiruzu.zendiorama.core.WorldMapReliefShaper;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Baked GPU geometry for the world map diorama: one colored voxel per surface block,
 * shaded with vanilla MapColor values so the terrain reads like a 3D Minecraft map.
 *
 * <p>Uses {@link DefaultVertexFormat#POSITION_COLOR} and
 * {@link GameRenderer#getPositionColorShader()} — the same shader the skybox uses —
 * so there are no light-map or chunk-uniform dependencies that would break in a BER context.
 *
 * <p>Geometry is baked once per snapshot change and replayed cheaply each frame.
 */
public final class WorldMapGeometry implements AutoCloseable {
    private static final float BRIGHT = 0.94f;
    private static final float MEDIUM = 0.68f;
    private static final float DARK   = 0.42f;

    /** Width of the raised border rim on each edge (in block-face units 0–1). */
    private static final float BORDER_INSET  = 0.05f;
    /** Small separation between terrain columns and rim walls to avoid coplanar depth fighting. */
    private static final float BORDER_CONTENT_GAP = 0.002f;
    /** Height of the border rim above the block surface. */
    private static final float BORDER_HEIGHT = 0.10f;
    /** Dark wood colour for the border. */
    private static final int BORDER_R = 90, BORDER_G = 58, BORDER_B = 28;
    private static final int INVALID_BASE_R = 34, INVALID_BASE_G = 24, INVALID_BASE_B = 24;
    private static final int INVALID_PANEL_R = 120, INVALID_PANEL_G = 34, INVALID_PANEL_B = 34;
    private static final int INVALID_X_R = 240, INVALID_X_G = 210, INVALID_X_B = 210;
    private static final int WAIT_BASE_R = 42, WAIT_BASE_G = 44, WAIT_BASE_B = 48;
    private static final int WAIT_PANEL_R = 96, WAIT_PANEL_G = 102, WAIT_PANEL_B = 112;
    private static final int WAIT_BAND_R = 156, WAIT_BAND_G = 164, WAIT_BAND_B = 176;

    private static final AtomicBoolean DIAGNOSTIC_LOGGED = new AtomicBoolean(false);

    private final VertexBuffer vbo;
    private final Object key;
    private final int flags;
    private boolean closed;

    public record PresentationLayout(int tileX, int tileZ, int tilesWide, int tilesTall) {
        static final PresentationLayout SINGLE_TILE = new PresentationLayout(0, 0, 1, 1);

        public PresentationLayout {
            if (tilesWide < 1 || tilesTall < 1) {
                throw new IllegalArgumentException("presentation layout dimensions must be positive");
            }
        }

        float sample(float x, float y, float z) {
            float u = Math.clamp((tileX + x) / tilesWide, 0.0f, 1.0f);
            float v = Math.clamp((tileZ + z) / tilesTall, 0.0f, 1.0f);

            float dx = Math.abs(u - 0.5f) * 2.0f;
            float dz = Math.abs(v - 0.5f) * 2.0f;
            float edge = Math.max(dx, dz);
            float vignette = 1.0f - 0.18f * (float)Math.pow(edge, 1.7d);

            float directional = 0.90f + 0.10f * Math.clamp((1.0f - u) * 0.65f + (1.0f - v) * 0.35f, 0.0f, 1.0f);
            float heightLift = 0.97f + 0.05f * Math.clamp(y, 0.0f, 1.0f);
            return Math.clamp(vignette * directional * heightLift, 0.72f, 1.02f);
        }
    }

    private WorldMapGeometry(Object key, int flags, VertexBuffer vbo) {
        this.key = key;
        this.flags = flags;
        this.vbo = vbo;
    }

    /**
     * Bakes a snapshot into a single POSITION_COLOR vertex buffer.
     *
     * @param key             identity-compared invalidation key (the snapshot instance)
     * @param flags           extra invalidation bits (height exaggeration as float bits)
     * @param snapshot        surface block entries
     * @param blocksPerTile   world blocks covered by this tile (controls XZ scale)
     * @param heightExaggeration vertical scale multiplier applied on top of the XZ scale
     */
    public static WorldMapGeometry bake(
            Object key, int flags,
            MiniatureSnapshot snapshot, int blocksPerTile, float heightExaggeration,
            float elevationTint, int neighborMask, PresentationLayout presentationLayout) {
        return prepare(
                key,
                flags,
                snapshot,
                blocksPerTile,
                heightExaggeration,
                elevationTint,
                neighborMask,
                presentationLayout).upload();
    }

    public static PreparedMesh prepare(
            Object key, int flags,
            MiniatureSnapshot snapshot, int blocksPerTile, float heightExaggeration,
            float elevationTint, int neighborMask, PresentationLayout presentationLayout) {

        // 5 faces × 4 vertices × 16 bytes (POSITION_COLOR) per voxel;
        // ByteBufferBuilder grows automatically if this is exceeded.
        int capacity = snapshot.entries().size() * 5 * 4 * 16;
        ByteBufferBuilder bb = new ByteBufferBuilder(Math.max(capacity, 64));
        BufferBuilder builder = new BufferBuilder(bb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Which edges are external (no adjacent world-map block) — determines where borders appear
        // and how much to inset the voxel content so it stays inside the border rim.
        boolean noN = (neighborMask & 1) == 0;
        boolean noS = (neighborMask & 2) == 0;
        boolean noW = (neighborMask & 4) == 0;
        boolean noE = (neighborMask & 8) == 0;
        float xMin = noW ? BORDER_INSET + BORDER_CONTENT_GAP : 0.0f;
        float xMax = noE ? 1.0f - BORDER_INSET - BORDER_CONTENT_GAP : 1.0f;
        float zMin = noN ? BORDER_INSET + BORDER_CONTENT_GAP : 0.0f;
        float zMax = noS ? 1.0f - BORDER_INSET - BORDER_CONTENT_GAP : 1.0f;
        // XZ: content fills the available interior, one scale per axis so the grid fills
        // the non-border area fully — avoids gaps on asymmetric border arrangements.
        float xzScaleX = (xMax - xMin) / blocksPerTile;
        float xzScaleZ = (zMax - zMin) / blocksPerTile;
        // Y and minimum column stub height are keyed to 1/blocksPerTile regardless of border inset
        // so column heights stay consistent between bordered and shared-edge tiles.
        float xzScaleY = 1.0f / blocksPerTile;
        float yScale   = xzScaleY * heightExaggeration;
        float xzCenter = blocksPerTile / 2.0f;
        float xContent = (xMin + xMax) / 2.0f;
        float zContent = (zMin + zMax) / 2.0f;

        // Build a height lookup for slope shading — same technique BlueMap uses in its lowres shader:
        // compare each block's height to its East (+X) and South (+Z) neighbors, then
        // shade = clamp((heightDiff) * 0.06, -0.2, 0.04). Shadows are intentionally stronger than
        // highlights so cliffs and ridges pop clearly.
        // Fixed sea-level baseline: entry.y() = groundY - seaLevel (already absolute).
        // Using a fixed divisor ensures all tiles share the same vertical scale, so
        // adjacent tiles connect without height seams at their edges.
        // 32 blocks above sea level ≈ typical lowland-to-hill variation; heightExaggeration
        // multiplies on top for dramatic effect.
        float yNorm = yScale / 32.0f;

        // Ground-only height lookup: tree entries are excluded so the hillshade reflects
        // true terrain and tree bases can sit on the ground beneath them.
        Map<Long, Integer> groundHeight = new java.util.HashMap<>(snapshot.entries().size() * 2);
        for (MiniatureSnapshot.Entry e : snapshot.entries()) {
            if (!SurfaceClassifier.isFeature(MiniatureBlockStateCodec.decode(e.blockStateId()))) {
                groundHeight.put(xzKey(e.x(), e.z()), e.y());
            }
        }
        Map<Long, Float> shapedGroundHeight = new java.util.HashMap<>(groundHeight.size() * 2);
        for (Map.Entry<Long, Integer> entry : groundHeight.entrySet()) {
            long columnKey = entry.getKey();
            int x = (int)(columnKey >> 32);
            int z = (int)columnKey;
            int center = entry.getValue();
            shapedGroundHeight.put(columnKey, WorldMapReliefShaper.shapeTerrainHeight(
                    center,
                    groundHeight.getOrDefault(xzKey(x, z - 1), center),
                    groundHeight.getOrDefault(xzKey(x - 1, z), center),
                    groundHeight.getOrDefault(xzKey(x + 1, z), center),
                    groundHeight.getOrDefault(xzKey(x, z + 1), center),
                    false));
        }
        WorldMapProminentObjects.Result prominentObjects = WorldMapProminentObjects.detect(snapshot);

        PresentationLayout layout = presentationLayout != null ? presentationLayout : PresentationLayout.SINGLE_TILE;

        for (MiniatureSnapshot.Entry entry : snapshot.entries()) {
            // Skip entries that fall outside the render grid — this happens when displayGridSize
            // is set smaller than the number of sampled columns, showing a zoomed-in subset.
            if (entry.x() >= blocksPerTile || entry.z() >= blocksPerTile) continue;

            BlockState state = MiniatureBlockStateCodec.decode(entry.blockStateId());
            MapColor mapColor = safeMapColor(state);
            if (mapColor == MapColor.NONE) continue;

            // Trees/features render as solid cube columns at canopy height (the readable
            // "toy block" look) rather than tapered shapes — at 1/48-block voxel width a
            // tapered tree reads as a spike and leaves holes around it. The ground/feature
            // split still pays off: the hillshade below is computed from ground heights only.

            // Prefer the captured biome tint (grass/foliage/water) when present; otherwise the
            // block's flat MapColor. The tint already encodes the biome-specific hue, so terrain
            // varies between swamp, savanna, forest, etc. instead of one uniform green.
            int col = entry.tint() != 0 ? entry.tint() : mapColor.col;
            float baseR = (col >> 16) & 0xFF;
            float baseG = (col >> 8)  & 0xFF;
            float baseB =  col        & 0xFF;

            boolean isWater = !state.getFluidState().isEmpty();
            boolean isFeature = SurfaceClassifier.isFeature(state);
            long columnKey = xzKey(entry.x(), entry.z());
            int centerGround = groundHeight.getOrDefault(columnKey, entry.y());
            int objectBase = prominentObjects.baselineAt(entry.x(), entry.z(), centerGround);
            boolean isProminentObject = !isFeature && prominentObjects.isObjectColumn(entry.x(), entry.z());
            float shapedGround = isWater
                    ? entry.y()
                    : shapedGroundHeight.getOrDefault(columnKey, (float) centerGround);
            float shapedHeight = isFeature
                    ? WorldMapReliefShaper.shapeFeatureHeight(shapedGround, entry.y())
                    : isProminentObject
                    ? WorldMapReliefShaper.shapeObjectHeight(objectBase, entry.y())
                    : shapedGround;

            // Ambient occlusion — blocks tucked below taller neighbors (valley floors, tree bases,
            // canyon walls) read darker, so the canopy separates from the ground beneath it.
            int taller = 0;
            if (groundHeight.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x() - 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x(), entry.z() - 1), entry.y()) > entry.y() + 1) taller++;
            float ao = -taller * 0.05f;

            // Topographic elevation tint — lowlands shade deeper/cooler green, highlands
            // lighter/warmer (toward tan), following the relief. Smooth gradient keyed on the
            // block's normalized height, so it reads as a contour map rather than noise.
            // Water keeps its flat sheet color.
            float elevR = 1.0f, elevG = 1.0f, elevB = 1.0f;
            if (!isWater && elevationTint > 0.0f) {
                float t = Math.clamp(entry.y() / 48.0f, 0.0f, 1.0f); // 0 = sea level, 1 = 48+ blocks up
                float mr = 0.80f + (1.12f - 0.80f) * t;
                float mg = 0.90f + (1.10f - 0.90f) * t;
                float mb = 0.85f + (0.80f - 0.85f) * t;
                elevR = 1.0f + (mr - 1.0f) * elevationTint;
                elevG = 1.0f + (mg - 1.0f) * elevationTint;
                elevB = 1.0f + (mb - 1.0f) * elevationTint;
            }

            // Water reads as a clean flat sheet — skip the terrain AO.
            float baseFactor = isWater ? 1.0f : 1.0f + ao;
            int r = Math.clamp((int)(baseR * baseFactor * elevR), 0, 255);
            int g = Math.clamp((int)(baseG * baseFactor * elevG), 0, 255);
            int b = Math.clamp((int)(baseB * baseFactor * elevB), 0, 255);

            // Slope shade — positive = ridge/highlight, negative = valley/shadow
            int heightE = groundHeight.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y());
            int heightS = groundHeight.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y());
            float heightDiff = (entry.y() - heightE) + (entry.y() - heightS);
            float shade = isWater ? 0.0f : Math.clamp(heightDiff * 0.12f, -0.35f, 0.08f);

            // Apply shade to top face only (side faces keep their fixed MEDIUM/DARK brightness)
            int rt = Math.clamp((int)(r + shade * 255), 0, 255);
            int gt = Math.clamp((int)(g + shade * 255), 0, 255);
            int bt = Math.clamp((int)(b + shade * 255), 0, 255);

            // Model-space voxel bounds (0…1 = one Minecraft block).
            // Columns extend from the slab base up to the surface height — the same technique
            // BlueMap/Dynmap use so trees, hills, and terrain read as solid masses rather than
            // floating tiles. Below-sea-level entries (water/ocean) get a minimum stub height.
            float x0 = xContent + (entry.x() - xzCenter) * xzScaleX;
            float x1 = x0 + xzScaleX;
            float z0 = zContent + (entry.z() - xzCenter) * xzScaleZ;
            float z1 = z0 + xzScaleZ;
            float columnBase = 0.064f;
            float columnTop  = shapedHeight * yNorm + columnBase + xzScaleY;
            float y0 = columnBase;
            float y1 = Math.max(columnTop, columnBase + xzScaleY * 0.5f);

            // Top face — slope-shaded
            quad(builder, rt, gt, bt, BRIGHT, layout,
                    x0, y1, z0,
                    x0, y1, z1,
                    x1, y1, z1,
                    x1, y1, z0);

            // South face (+Z)
            quad(builder, r, g, b, MEDIUM, layout,
                    x0, y0, z1,
                    x1, y0, z1,
                    x1, y1, z1,
                    x0, y1, z1);

            // North face (-Z)
            quad(builder, r, g, b, DARK, layout,
                    x1, y0, z0,
                    x0, y0, z0,
                    x0, y1, z0,
                    x1, y1, z0);

            // East face (+X)
            quad(builder, r, g, b, MEDIUM, layout,
                    x1, y0, z1,
                    x1, y0, z0,
                    x1, y1, z0,
                    x1, y1, z1);

            // West face (-X)
            quad(builder, r, g, b, DARK, layout,
                    x0, y0, z0,
                    x0, y0, z1,
                    x0, y1, z1,
                    x0, y1, z0);
        }

        emitBorderRim(builder, layout, noN, noS, noW, noE);

        MeshData mesh = builder.build();

        if (DIAGNOSTIC_LOGGED.compareAndSet(false, true)) {
            ZenDiorama.LOGGER.info(
                    "[zen_diorama] WorldMapGeometry.prepare: entries={}, hasGeometry={}",
                    snapshot.entries().size(), mesh != null);
        }

        return new PreparedMesh(key, flags, bb, mesh);
    }

    public static WorldMapGeometry bakeInvalidLayout(
            Object key, int flags, int neighborMask, PresentationLayout presentationLayout) {
        ByteBufferBuilder bb = new ByteBufferBuilder(4096);
        BufferBuilder builder = new BufferBuilder(bb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        boolean noN = (neighborMask & 1) == 0;
        boolean noS = (neighborMask & 2) == 0;
        boolean noW = (neighborMask & 4) == 0;
        boolean noE = (neighborMask & 8) == 0;
        PresentationLayout layout = presentationLayout != null ? presentationLayout : PresentationLayout.SINGLE_TILE;

        float panelInset = 0.13f;
        float panelY0 = 0.02f;
        float panelY1 = 0.055f;
        float xBarHalf = 0.055f;

        // Dark base panel.
        quad(builder, INVALID_BASE_R, INVALID_BASE_G, INVALID_BASE_B, 1.0f, layout,
                panelInset, panelY0, panelInset,
                panelInset, panelY0, 1.0f - panelInset,
                1.0f - panelInset, panelY0, 1.0f - panelInset,
                1.0f - panelInset, panelY0, panelInset);

        // Raised red warning plate.
        quad(builder, INVALID_PANEL_R, INVALID_PANEL_G, INVALID_PANEL_B, BRIGHT, layout,
                panelInset, panelY1, panelInset,
                panelInset, panelY1, 1.0f - panelInset,
                1.0f - panelInset, panelY1, 1.0f - panelInset,
                1.0f - panelInset, panelY1, panelInset);

        // Diagonal warning X.
        diagonalBar(builder, layout,
                panelInset, panelY1 + 0.002f, panelInset,
                1.0f - panelInset, panelY1 + 0.002f, 1.0f - panelInset,
                xBarHalf,
                INVALID_X_R, INVALID_X_G, INVALID_X_B);
        diagonalBar(builder, layout,
                panelInset, panelY1 + 0.002f, 1.0f - panelInset,
                1.0f - panelInset, panelY1 + 0.002f, panelInset,
                xBarHalf,
                INVALID_X_R, INVALID_X_G, INVALID_X_B);

        // Keep the normal wooden edge rim so it still reads as the same block family.
        emitBorderRim(builder, layout, noN, noS, noW, noE);

        MeshData mesh = builder.build();
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        if (mesh != null) {
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
        }
        bb.close();
        return new WorldMapGeometry(key, flags, vbo);
    }

    public static WorldMapGeometry bakeWaitingLayout(
            Object key, int flags, int neighborMask, PresentationLayout presentationLayout) {
        ByteBufferBuilder bb = new ByteBufferBuilder(4096);
        BufferBuilder builder = new BufferBuilder(bb, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        boolean noN = (neighborMask & 1) == 0;
        boolean noS = (neighborMask & 2) == 0;
        boolean noW = (neighborMask & 4) == 0;
        boolean noE = (neighborMask & 8) == 0;
        PresentationLayout layout = presentationLayout != null ? presentationLayout : PresentationLayout.SINGLE_TILE;

        float panelInset = 0.13f;
        float panelY0 = 0.02f;
        float panelY1 = 0.05f;
        float bandInset = 0.20f;
        float bandWidth = 0.10f;

        quad(builder, WAIT_BASE_R, WAIT_BASE_G, WAIT_BASE_B, 1.0f, layout,
                panelInset, panelY0, panelInset,
                panelInset, panelY0, 1.0f - panelInset,
                1.0f - panelInset, panelY0, 1.0f - panelInset,
                1.0f - panelInset, panelY0, panelInset);

        quad(builder, WAIT_PANEL_R, WAIT_PANEL_G, WAIT_PANEL_B, BRIGHT, layout,
                panelInset, panelY1, panelInset,
                panelInset, panelY1, 1.0f - panelInset,
                1.0f - panelInset, panelY1, 1.0f - panelInset,
                1.0f - panelInset, panelY1, panelInset);

        // Two soft diagonal bands to read as "data unavailable/loading area" without alarm.
        diagonalBar(builder, layout,
                bandInset, panelY1 + 0.002f, 1.0f - bandInset,
                1.0f - bandInset, panelY1 + 0.002f, bandInset,
                bandWidth,
                WAIT_BAND_R, WAIT_BAND_G, WAIT_BAND_B);
        diagonalBar(builder, layout,
                bandInset - 0.08f, panelY1 + 0.001f, 1.0f - bandInset,
                1.0f - bandInset - 0.08f, panelY1 + 0.001f, bandInset,
                bandWidth * 0.55f,
                WAIT_BASE_R, WAIT_BASE_G, WAIT_BASE_B);

        emitBorderRim(builder, layout, noN, noS, noW, noE);

        MeshData mesh = builder.build();
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        if (mesh != null) {
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
        }
        bb.close();
        return new WorldMapGeometry(key, flags, vbo);
    }

    public boolean matches(Object key, int flags) {
        return !closed && this.key == key && this.flags == flags;
    }

    public void draw(Matrix4f poseMatrix, float brightness) {
        draw(poseMatrix, brightness, 1.0f);
    }

    public void draw(Matrix4f poseMatrix, float brightness, float alpha) {
        if (closed) return;

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseMatrix);
        Matrix4f projection = RenderSystem.getProjectionMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        if (alpha < 0.999f) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }

        // The POSITION_COLOR shader multiplies vertex colors by the shader color, so we dim the
        // whole map by the ambient light at its position here — no re-bake needed for time of day.
        RenderSystem.setShaderColor(brightness, brightness, brightness, alpha);
        vbo.bind();
        vbo.drawWithShader(modelView, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        if (alpha < 0.999f) {
            RenderSystem.disableBlend();
        }

        RenderSystem.enableCull();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vbo.close();
        }
    }

    private static MapColor safeMapColor(BlockState state) {
        try {
            MapColor c = state.getMapColor(null, null);
            return c != null ? c : MapColor.COLOR_GRAY;
        } catch (Exception e) {
            return MapColor.COLOR_GRAY;
        }
    }

    private static void quad(BufferBuilder b, int r, int g, int bl, float brightness, PresentationLayout layout,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3) {
        addVertex(b, r, g, bl, brightness, layout, x0, y0, z0);
        addVertex(b, r, g, bl, brightness, layout, x1, y1, z1);
        addVertex(b, r, g, bl, brightness, layout, x2, y2, z2);
        addVertex(b, r, g, bl, brightness, layout, x3, y3, z3);
    }

    private static void diagonalBar(BufferBuilder b, PresentationLayout layout,
            float x0, float y, float z0,
            float x1, float y1, float z1,
            float halfWidth,
            int r, int g, int bl) {
        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float)Math.sqrt(dx * dx + dz * dz);
        if (len <= 0.0001f) return;
        float px = -dz / len * halfWidth;
        float pz = dx / len * halfWidth;
        quad(b, r, g, bl, 1.0f, layout,
                x0 - px, y, z0 - pz,
                x0 + px, y, z0 + pz,
                x1 + px, y1, z1 + pz,
                x1 - px, y1, z1 - pz);
    }

    private static void emitBorderRim(BufferBuilder builder, PresentationLayout layout,
            boolean noN, boolean noS, boolean noW, boolean noE) {
        float BI = BORDER_INSET, BH = BORDER_HEIGHT;
        int R = BORDER_R, G = BORDER_G, B = BORDER_B;
        if (noN) {
            quad(builder, R,G,B, BRIGHT, layout,  0f,BH,0f,  0f,BH,BI,  1f,BH,BI,  1f,BH,0f);
            quad(builder, R,G,B, MEDIUM, layout,  0f,0f,BI,  1f,0f,BI,  1f,BH,BI,  0f,BH,BI);
            quad(builder, R,G,B, DARK, layout,  1f,0f,0f,  0f,0f,0f,  0f,BH,0f,  1f,BH,0f);
        }
        if (noS) {
            quad(builder, R,G,B, BRIGHT, layout,  0f,BH,1f-BI,  0f,BH,1f,  1f,BH,1f,  1f,BH,1f-BI);
            quad(builder, R,G,B, MEDIUM, layout,  1f,0f,1f-BI,  0f,0f,1f-BI,  0f,BH,1f-BI,  1f,BH,1f-BI);
            quad(builder, R,G,B, DARK, layout,  0f,0f,1f,  1f,0f,1f,  1f,BH,1f,  0f,BH,1f);
        }
        if (noW) {
            quad(builder, R,G,B, BRIGHT, layout,  0f,BH,0f,  0f,BH,1f,  BI,BH,1f,  BI,BH,0f);
            quad(builder, R,G,B, MEDIUM, layout,  BI,0f,1f,  BI,0f,0f,  BI,BH,0f,  BI,BH,1f);
            quad(builder, R,G,B, DARK, layout,  0f,0f,1f,  0f,0f,0f,  0f,BH,0f,  0f,BH,1f);
        }
        if (noE) {
            quad(builder, R,G,B, BRIGHT, layout,  1f-BI,BH,0f,  1f-BI,BH,1f,  1f,BH,1f,  1f,BH,0f);
            quad(builder, R,G,B, MEDIUM, layout,  1f-BI,0f,0f,  1f-BI,0f,1f,  1f-BI,BH,1f,  1f-BI,BH,0f);
            quad(builder, R,G,B, DARK, layout,  1f,0f,0f,  1f,0f,1f,  1f,BH,1f,  1f,BH,0f);
        }
    }

    static float presentationLightFactor(PresentationLayout layout, float x, float y, float z) {
        return (layout != null ? layout : PresentationLayout.SINGLE_TILE).sample(x, y, z);
    }

    private static void addVertex(BufferBuilder b, int r, int g, int bl, float brightness,
            PresentationLayout layout, float x, float y, float z) {
        float light = brightness * presentationLightFactor(layout, x, y, z);
        b.addVertex(x, y, z).setColor(scale(r, light), scale(g, light), scale(bl, light), 255);
    }

    private static int scale(int channel, float factor) {
        return Math.clamp((int)(channel * factor), 0, 255);
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static final class PreparedMesh implements AutoCloseable {
        private final Object key;
        private final int flags;
        private final ByteBufferBuilder buffer;
        private MeshData mesh;
        private boolean closed;

        private PreparedMesh(Object key, int flags, ByteBufferBuilder buffer, MeshData mesh) {
            this.key = key;
            this.flags = flags;
            this.buffer = buffer;
            this.mesh = mesh;
        }

        public WorldMapGeometry upload() {
            if (closed) {
                throw new IllegalStateException("prepared mesh is already closed");
            }
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                if (mesh != null) {
                    vbo.bind();
                    vbo.upload(mesh); // upload takes ownership and closes the MeshData
                    mesh = null;
                    VertexBuffer.unbind();
                }
                return new WorldMapGeometry(key, flags, vbo);
            } finally {
                VertexBuffer.unbind();
                close();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (mesh != null) {
                mesh.close();
                mesh = null;
            }
            buffer.close();
        }
    }
}
