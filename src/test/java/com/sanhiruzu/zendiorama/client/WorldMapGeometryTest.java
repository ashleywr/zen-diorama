package com.sanhiruzu.zendiorama.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldMapGeometryTest {
    @Test
    void presentationLightIsContinuousAcrossTileBoundaries() {
        float leftEdge = WorldMapGeometry.presentationLightFactor(
                new WorldMapGeometry.PresentationLayout(0, 0, 2, 1),
                1.0f, 0.3f, 0.5f);
        float rightEdge = WorldMapGeometry.presentationLightFactor(
                new WorldMapGeometry.PresentationLayout(1, 0, 2, 1),
                0.0f, 0.3f, 0.5f);

        assertEquals(leftEdge, rightEdge, 0.0001f);
    }

    @Test
    void presentationLightKeepsMapCenterBrighterThanOuterCorner() {
        float center = WorldMapGeometry.presentationLightFactor(
                new WorldMapGeometry.PresentationLayout(1, 1, 3, 3),
                0.5f, 0.4f, 0.5f);
        float corner = WorldMapGeometry.presentationLightFactor(
                new WorldMapGeometry.PresentationLayout(0, 0, 3, 3),
                0.0f, 0.1f, 0.0f);

        assertTrue(center > corner);
    }
}
