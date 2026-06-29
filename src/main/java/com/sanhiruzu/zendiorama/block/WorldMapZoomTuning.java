package com.sanhiruzu.zendiorama.block;

/** Resolves a zoom preset against the connected map footprint. */
public final class WorldMapZoomTuning {
    private WorldMapZoomTuning() {
    }

    public record EffectiveZoom(int scale, int voxels) {}

    public static EffectiveZoom resolve(WorldMapZoomLevel preset, int tilesWide, int tilesTall) {
        int longSide = Math.max(1, Math.max(tilesWide, tilesTall));
        int scale = Math.max(1, (int) Math.round((double) preset.scale() / longSide));
        return new EffectiveZoom(scale, preset.voxels());
    }
}
