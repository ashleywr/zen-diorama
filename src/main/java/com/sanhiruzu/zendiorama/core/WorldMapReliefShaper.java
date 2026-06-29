package com.sanhiruzu.zendiorama.core;

public final class WorldMapReliefShaper {
    private static final float MAX_SLOPE_SAMPLES = 8.0f;
    private static final float SLOPE_BOOST = 0.75f;
    private static final float FEATURE_HEIGHT_CAP = 6.0f;
    private static final float OBJECT_BASE_LIFT = 2.0f;
    private static final float OBJECT_HEIGHT_SCALE = 1.0f;
    private static final float OBJECT_HEIGHT_CAP = 10.0f;

    private WorldMapReliefShaper() {
    }

    public static float shapeTerrainHeight(
            int centerHeight,
            int northHeight,
            int westHeight,
            int eastHeight,
            int southHeight,
            boolean isWater) {
        if (isWater) {
            return centerHeight;
        }

        float localSlope = (
                Math.abs(centerHeight - northHeight)
                + Math.abs(centerHeight - westHeight)
                + Math.abs(centerHeight - eastHeight)
                + Math.abs(centerHeight - southHeight)) / 4.0f;
        float boost = Math.min(localSlope, MAX_SLOPE_SAMPLES) * SLOPE_BOOST;
        return centerHeight + boost;
    }

    public static float shapeFeatureHeight(float groundHeight, float featureHeight) {
        return Math.min(featureHeight, groundHeight + FEATURE_HEIGHT_CAP);
    }

    public static float shapeObjectHeight(float groundHeight, float objectHeight) {
        float objectDelta = Math.max(0.0f, objectHeight - groundHeight);
        float upliftedDelta = Math.min(OBJECT_HEIGHT_CAP, OBJECT_BASE_LIFT + objectDelta * OBJECT_HEIGHT_SCALE);
        return groundHeight + upliftedDelta;
    }
}
