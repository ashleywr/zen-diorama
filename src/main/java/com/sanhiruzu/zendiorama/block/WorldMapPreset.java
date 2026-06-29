package com.sanhiruzu.zendiorama.block;

/** Visual style preset: height exaggeration + elevation tint. */
public record WorldMapPreset(String name, float height, float tint) {

    public static final WorldMapPreset[] PRESETS = {
        new WorldMapPreset("Flat",     1.0f, 0.00f),
        new WorldMapPreset("Natural",  6.0f, 0.40f),
        new WorldMapPreset("Topo",     8.0f, 0.65f),
        new WorldMapPreset("Dramatic", 12.0f, 0.60f),
    };
}
