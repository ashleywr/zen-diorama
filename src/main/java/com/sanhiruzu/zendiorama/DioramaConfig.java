package com.sanhiruzu.zendiorama;

import java.util.ArrayList;
import java.util.List;

public final class DioramaConfig {
    private static final int DEFAULT_MAP_MAX_GROUP_TILES = 64;

    public static final IntValue PLOT_SIZE = new IntValue(15);
    public static final IntValue PLOT_SPACING = new IntValue(16);
    public static final BooleanValue ALWAYS_LOADED_DEFAULT = new BooleanValue(false);
    public static final IntValue MINIATURE_MAX_BLOCKS = new IntValue(4096);
    public static final IntValue SYNC_DEBOUNCE_TICKS = new IntValue(100);
    public static final IntValue ZOOM_DEBOUNCE_TICKS = new IntValue(40);
    public static final IntValue SKYBOX_CAPTURE_RESOLUTION = new IntValue(64);
    public static final IntValue SKYBOX_BLUR_RADIUS = new IntValue(8);
    public static final IntValue SKYBOX_CAPTURE_DELAY_TICKS = new IntValue(15);
    public static final IntValue MINIATURE_DOWN_TILT = new IntValue(20);
    public static final BooleanValue MINIATURE_FULL_BRIGHT = new BooleanValue(false);
    public static final BooleanValue MINIATURE_INNER_SHADOW = new BooleanValue(true);
    public static final BooleanValue MINIATURE_VBO_CACHE = new BooleanValue(true);
    public static final IntValue MAP_BLOCKS_PER_TILE = new IntValue(48);
    public static final IntValue MAP_RESOLUTION = new IntValue(64);
    public static final IntValue MAP_MAX_GROUP_TILES = new IntValue(DEFAULT_MAP_MAX_GROUP_TILES);
    public static final DoubleValue MAP_HEIGHT_EXAGGERATION = new DoubleValue(8.0D);
    public static final DoubleValue MAP_ELEVATION_TINT = new DoubleValue(0.35D);

    private DioramaConfig() {
    }

    public static int plotSize() {
        return Math.min(PLOT_SIZE.get(), 15);
    }

    public static List<Integer> allowedMapGroupSizes() {
        int maxTiles = mapMaxGroupTiles();
        int maxSide = Math.max(1, (int) Math.floor(Math.sqrt(maxTiles)));
        List<Integer> sizes = new ArrayList<>(maxSide);
        for (int side = 1; side <= maxSide; side++) {
            if (side * side <= maxTiles) {
                sizes.add(side);
            }
        }
        return sizes;
    }

    public static int mapMaxGroupTiles() {
        return MAP_MAX_GROUP_TILES.get();
    }

    public static final class IntValue {
        private final int defaultValue;
        private int value;

        private IntValue(int defaultValue) {
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public int get() {
            return value;
        }

        public void set(int value) {
            this.value = value;
        }

        public void reset() {
            this.value = defaultValue;
        }
    }

    public static final class BooleanValue {
        private final boolean defaultValue;
        private boolean value;

        private BooleanValue(boolean defaultValue) {
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean value) {
            this.value = value;
        }

        public void reset() {
            this.value = defaultValue;
        }
    }

    public static final class DoubleValue {
        private final double defaultValue;
        private double value;

        private DoubleValue(double defaultValue) {
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public Double get() {
            return value;
        }

        public void set(double value) {
            this.value = value;
        }

        public void reset() {
            this.value = defaultValue;
        }
    }
}
