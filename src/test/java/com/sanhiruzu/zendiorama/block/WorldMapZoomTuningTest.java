package com.sanhiruzu.zendiorama.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldMapZoomTuningTest {
    @Test
    void singleTileKeepsPresetValues() {
        WorldMapZoomTuning.EffectiveZoom zoom = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[WorldMapZoomLevel.LEVELS.length - 1], 1, 1);

        assertEquals(1536, zoom.scale());
        assertEquals(256, zoom.voxels());
    }

    @Test
    void provincePresetMatchesGoodSingleTileAnchor() {
        WorldMapZoomTuning.EffectiveZoom zoom = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[4], 1, 1);

        assertEquals(1024, zoom.scale());
        assertEquals(128, zoom.voxels());
    }

    @Test
    void eightByEightMapDividesPresetScaleAcrossTiles() {
        WorldMapZoomTuning.EffectiveZoom zoom = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[0], 8, 8);
        WorldMapZoomTuning.EffectiveZoom zoom2 = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[1], 8, 8);
        WorldMapZoomTuning.EffectiveZoom zoom3 = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[2], 8, 8);

        assertEquals(16, zoom.scale());
        assertEquals(32, zoom2.scale());
        assertEquals(64, zoom3.scale());
        assertEquals(128, zoom.voxels());
        assertEquals(128, zoom2.voxels());
        assertEquals(128, zoom3.voxels());
    }

    @Test
    void tenByTenMapDividesStreetPresetAcrossTiles() {
        WorldMapZoomTuning.EffectiveZoom zoom = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[0], 10, 10);

        assertEquals(13, zoom.scale());
    }

    @Test
    void largerPresetStillResolvesLargerOnSameFootprint() {
        WorldMapZoomTuning.EffectiveZoom small = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[0], 8, 8);
        WorldMapZoomTuning.EffectiveZoom large = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[4], 8, 8);

        assertTrue(large.scale() > small.scale());
    }

    @Test
    void eightByEightTopEndDividesPresetScaleAcrossTiles() {
        WorldMapZoomTuning.EffectiveZoom province = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[4], 8, 8);
        WorldMapZoomTuning.EffectiveZoom region = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[5], 8, 8);
        WorldMapZoomTuning.EffectiveZoom kingdom = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[6], 8, 8);
        WorldMapZoomTuning.EffectiveZoom continent = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[7], 8, 8);

        assertEquals(128, province.scale());
        assertEquals(144, region.scale());
        assertEquals(160, kingdom.scale());
        assertEquals(192, continent.scale());
        assertTrue(region.scale() > province.scale());
        assertTrue(kingdom.scale() > region.scale());
        assertTrue(continent.scale() > kingdom.scale());
    }

    @Test
    void threeByThreeTownPresetSpansTheWholeMosaic() {
        WorldMapZoomTuning.EffectiveZoom town = WorldMapZoomTuning.resolve(
                WorldMapZoomLevel.LEVELS[3], 3, 3);

        assertEquals(256, town.scale());
        assertEquals(128, town.voxels());
    }

    @Test
    void threeByThreeZoomCoordinatesStaySpreadAroundMiddleTile() {
        int left = WorldMapBlockEntity.centeredTileMapCoordinate(10_000, 0, 0, 2, 1024);
        int middle = WorldMapBlockEntity.centeredTileMapCoordinate(10_000, 1, 0, 2, 1024);
        int right = WorldMapBlockEntity.centeredTileMapCoordinate(10_000, 2, 0, 2, 1024);

        assertEquals(8_976, left);
        assertEquals(10_000, middle);
        assertEquals(11_024, right);
    }

    @Test
    void evenSizedZoomCoordinatesUseGeometricCenterBetweenTiles() {
        int left = WorldMapBlockEntity.centeredTileMapCoordinate(10_000, 0, 0, 1, 256);
        int right = WorldMapBlockEntity.centeredTileMapCoordinate(10_000, 1, 0, 1, 256);

        assertEquals(9_872, left);
        assertEquals(10_128, right);
    }
}
