package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldMapReliefShaperTest {
    @Test
    void boostsSteepLocalTerrainMoreThanGentleTerrainAtSameElevation() {
        float gentle = WorldMapReliefShaper.shapeTerrainHeight(20, 19, 20, 21, 20, false);
        float steep = WorldMapReliefShaper.shapeTerrainHeight(20, 8, 20, 32, 20, false);

        assertTrue(steep > gentle + 2.0f);
    }

    @Test
    void keepsWaterAtBaseHeightWithoutExtraRelief() {
        float water = WorldMapReliefShaper.shapeTerrainHeight(0, -4, 0, 6, 0, true);

        assertEquals(0.0f, water);
    }

    @Test
    void capsFeatureHeightAboveGround() {
        float capped = WorldMapReliefShaper.shapeFeatureHeight(12, 28);

        assertEquals(18.0f, capped);
    }

    @Test
    void shapesProminentObjectsMoreUprightThanTerrain() {
        float terrain = WorldMapReliefShaper.shapeTerrainHeight(14, 10, 10, 10, 10, false);
        float object = WorldMapReliefShaper.shapeObjectHeight(10.0f, 16.0f);

        assertTrue(object > terrain);
    }

    @Test
    void capsObjectHeightToToyMass() {
        float capped = WorldMapReliefShaper.shapeObjectHeight(10.0f, 30.0f);

        assertEquals(20.0f, capped);
    }
}
