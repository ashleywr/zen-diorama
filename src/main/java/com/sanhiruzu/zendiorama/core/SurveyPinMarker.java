package com.sanhiruzu.zendiorama.core;

/**
 * A map-visible point of interest. Pins travel beside terrain snapshots rather than being encoded
 * as terrain voxels, so they remain visible at every map zoom level.
 */
public record SurveyPinMarker(int worldX, int worldZ, int color) {
    public SurveyPinMarker {
        color &= 0xFFFFFF;
    }
}
