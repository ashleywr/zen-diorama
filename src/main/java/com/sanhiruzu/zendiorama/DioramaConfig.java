package com.sanhiruzu.zendiorama;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

public final class DioramaConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final int DEFAULT_MAP_MAX_GROUP_TILES = 64;

    public static final ModConfigSpec.IntValue PLOT_SIZE = BUILDER
            .comment("Interior plot edge length in blocks. V1 uses 15 so each inside block renders at exact 1:16 scale with a frame border.")
            .defineInRange("plotSize", 15, 15, 15);

    public static final ModConfigSpec.IntValue PLOT_SPACING = BUILDER
            .comment("Empty spacing between allocated diorama plots.")
            .defineInRange("plotSpacing", 16, 0, 256);

    public static final ModConfigSpec.BooleanValue ALWAYS_LOADED_DEFAULT = BUILDER
            .comment("Whether new dioramas should keep their chunks forced when empty.")
            .define("alwaysLoadedDefault", false);

    public static final ModConfigSpec.IntValue MINIATURE_MAX_BLOCKS = BUILDER
            .comment("Maximum block entries retained in a miniature snapshot.")
            .defineInRange("miniatureMaxBlocks", 4096, 1, 65536);

    public static final ModConfigSpec.IntValue SYNC_DEBOUNCE_TICKS = BUILDER
            .comment("Ticks of inactivity before a dirty miniature snapshot is refreshed.")
            .defineInRange("syncDebounceTicks", 100, 1, 20 * 60);

    public static final ModConfigSpec.IntValue ZOOM_DEBOUNCE_TICKS = BUILDER
            .comment("Ticks of inactivity after a zoom change before the world map resamples. Lets you cycle quickly to the zoom you want without resampling each intermediate level. Default 40 (~2 s).")
            .defineInRange("zoomDebounceTicks", 40, 1, 20 * 60);

    public static final ModConfigSpec.IntValue SKYBOX_CAPTURE_RESOLUTION = BUILDER
            .comment("Per-face resolution of the client-side rendered cubemap skybox. Higher = sharper environment detail before blur. Default 64 ≈ 30ms; 256 ≈ 500ms capture time.")
            .defineInRange("skyboxCaptureResolution", 64, 16, 256);

    public static final ModConfigSpec.IntValue SKYBOX_BLUR_RADIUS = BUILDER
            .comment("Number of 3x3 box-blur passes applied to each captured skybox face. 0 = no blur. Default 8.")
            .defineInRange("skyboxBlurRadius", 8, 0, 32);

    public static final ModConfigSpec.IntValue SKYBOX_CAPTURE_DELAY_TICKS = BUILDER
            .comment("Fallback timeout (server ticks) before teleporting into the diorama if the client never acks cubemap capture. Normally the ack arrives in ~1 frame; this only fires on packet loss or capture failure. Default 15.")
            .defineInRange("skyboxCaptureDelayTicks", 15, 1, 100);

    public static final ModConfigSpec.IntValue MINIATURE_DOWN_TILT = BUILDER
            .comment("Degrees the miniature tilts downward for a display-case look. 0 = flat front, 20 = slight overhead angle.")
            .defineInRange("miniatureDownTilt", 20, 0, 40);

    public static final ModConfigSpec.BooleanValue MINIATURE_FULL_BRIGHT = BUILDER
            .comment("Render the miniature at full brightness, ignoring world lighting.")
            .define("miniatureFullBright", false);

    public static final ModConfigSpec.BooleanValue MINIATURE_INNER_SHADOW = BUILDER
            .comment("Darken blocks near the edges of the miniature to simulate a lit display-case vignette.")
            .define("miniatureInnerShadow", true);

    public static final ModConfigSpec.BooleanValue MINIATURE_VBO_CACHE = BUILDER
            .comment("Cache compiled miniature geometry on the GPU, rebuilding only when the snapshot changes. Requires miniatureFullBright. Improves performance when many frames are visible.")
            .define("miniatureVboCache", true);

    public static final ModConfigSpec.IntValue MAP_BLOCKS_PER_TILE = BUILDER
            .comment("Real-world blocks covered by each world map tile block. 48 = a 48×48 block region per tile (larger voxels, more readable).")
            .defineInRange("mapBlocksPerTile", 48, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAP_RESOLUTION = BUILDER
            .comment("Voxels across each world map tile face. Higher = more detail but slower sampling. " +
                     "Blocks are sampled 1:1 when blocksPerTile ≤ this value; beyond that, sampling strides so the " +
                     "output is always this many voxels across. Default 64.")
            .defineInRange("mapResolution", 64, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAP_MAX_GROUP_TILES = BUILDER
            .comment("Hard server-side cap for connected world-map tiles. Any full square whose tile count is at or below this limit is allowed. Default 64 = up to 8x8.")
            .defineInRange("mapMaxGroupTiles", DEFAULT_MAP_MAX_GROUP_TILES, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue MAP_HEIGHT_EXAGGERATION = BUILDER
            .comment("Vertical relief of the 3D map diorama. Rendered height = worldHeight / blocksPerTile * this. "
                    + "0.0 = perfectly flat; higher values exaggerate hills and valleys. Default 8.0.")
            .defineInRange("mapHeightExaggeration", 8.0, 0.0, Double.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue MAP_ELEVATION_TINT = BUILDER
            .comment("Topographic color variation on the 3D map. 0.0 = off, 1.0 = natural, higher values oversaturate. Default 0.35.")
            .defineInRange("mapElevationTint", 0.35, 0.0, Double.MAX_VALUE);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static int plotSize() {
        return Math.min(PLOT_SIZE.get(), 15);
    }

    public static List<Integer> allowedMapGroupSizes() {
        int maxTiles = mapMaxGroupTiles();
        int maxSide = Math.max(1, (int)Math.floor(Math.sqrt(maxTiles)));
        List<Integer> sizes = new ArrayList<>(maxSide);
        for (int side = 1; side <= maxSide; side++) {
            if (side * side <= maxTiles) {
                sizes.add(side);
            }
        }
        return sizes;
    }

    public static int mapMaxGroupTiles() {
        try {
            return MAP_MAX_GROUP_TILES.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_MAP_MAX_GROUP_TILES;
        }
    }

    private DioramaConfig() {
    }
}
