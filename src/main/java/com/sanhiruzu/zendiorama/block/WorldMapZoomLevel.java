package com.sanhiruzu.zendiorama.block;

/** Paired scale + voxels for one right-click zoom step. Larger views intentionally keep a denser miniature look instead of scaling 1:1 forever. */
public record WorldMapZoomLevel(String name, int scale, int voxels) {

    public static final WorldMapZoomLevel[] LEVELS = {
        new WorldMapZoomLevel("Street",    128, 128),
        new WorldMapZoomLevel("Block",     256, 128),
        new WorldMapZoomLevel("District",  512, 128),
        new WorldMapZoomLevel("Town",      768, 128),
        new WorldMapZoomLevel("Province",  1024, 128),
        new WorldMapZoomLevel("Region",    1152, 160),
        new WorldMapZoomLevel("Kingdom",   1280, 192),
        new WorldMapZoomLevel("Continent", 1536, 256),
    };
}
