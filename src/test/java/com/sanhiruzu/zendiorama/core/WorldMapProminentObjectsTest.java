package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class WorldMapProminentObjectsTest {
    @Test
    void detectsCompactRaisedPlateauAsObjectRegion() {
        MiniatureSnapshot snapshot = snapshot(new int[][] {
                {10, 10, 10, 10, 10},
                {10, 16, 16, 16, 10},
                {10, 16, 16, 16, 10},
                {10, 16, 16, 16, 10},
                {10, 10, 10, 10, 10}
        });

        WorldMapProminentObjects.Result result = WorldMapProminentObjects.detect(snapshot);

        assertTrue(result.isObjectColumn(2, 2));
        assertTrue(result.isObjectColumn(1, 1));
    }

    @Test
    void rejectsLongThinRaisedLine() {
        MiniatureSnapshot snapshot = snapshot(new int[][] {
                {10, 10, 10, 10, 10},
                {10, 18, 10, 10, 10},
                {10, 18, 10, 10, 10},
                {10, 18, 10, 10, 10},
                {10, 18, 10, 10, 10}
        });

        WorldMapProminentObjects.Result result = WorldMapProminentObjects.detect(snapshot);

        assertFalse(result.isObjectColumn(1, 1));
        assertFalse(result.isObjectColumn(1, 4));
    }

    @Test
    void rejectsSingleTallSpike() {
        MiniatureSnapshot snapshot = snapshot(new int[][] {
                {10, 10, 10},
                {10, 24, 10},
                {10, 10, 10}
        });

        WorldMapProminentObjects.Result result = WorldMapProminentObjects.detect(snapshot);

        assertFalse(result.isObjectColumn(1, 1));
    }

    @Test
    void rejectsGentleTerrainBump() {
        MiniatureSnapshot snapshot = snapshot(new int[][] {
                {10, 10, 10, 10, 10},
                {10, 11, 11, 11, 10},
                {10, 11, 12, 11, 10},
                {10, 11, 11, 11, 10},
                {10, 10, 10, 10, 10}
        });

        WorldMapProminentObjects.Result result = WorldMapProminentObjects.detect(snapshot);

        assertFalse(result.isObjectColumn(2, 2));
    }

    @Test
    void keepsBroadProminentObjectDetectableAfterLodReduction() {
        MiniatureSnapshot snapshot = snapshot(new int[][] {
                {10, 10, 10, 10, 10, 10, 10, 10},
                {10, 10, 10, 10, 10, 10, 10, 10},
                {10, 10, 16, 16, 16, 16, 10, 10},
                {10, 10, 16, 16, 16, 16, 10, 10},
                {10, 10, 16, 16, 16, 16, 10, 10},
                {10, 10, 16, 16, 16, 16, 10, 10},
                {10, 10, 10, 10, 10, 10, 10, 10},
                {10, 10, 10, 10, 10, 10, 10, 10}
        });

        MiniatureSnapshot lod = MiniatureSnapshotLod.build(snapshot, 2);
        WorldMapProminentObjects.Result result = WorldMapProminentObjects.detect(lod);

        assertTrue(result.isObjectColumn(1, 1));
    }

    private static MiniatureSnapshot snapshot(int[][] heights) {
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();
        for (int z = 0; z < heights.length; z++) {
            for (int x = 0; x < heights[z].length; x++) {
                entries.add(new MiniatureSnapshot.Entry(x, heights[z][x], z, "minecraft:stone"));
            }
        }
        return new MiniatureSnapshot(heights.length * heights[0].length, entries);
    }
}
