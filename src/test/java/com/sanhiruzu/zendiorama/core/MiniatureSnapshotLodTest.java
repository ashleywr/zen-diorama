package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MiniatureSnapshotLodTest {
    @Test
    void mergesTwoByTwoGroundColumnsIntoOneAveragedCell() {
        MiniatureSnapshot snapshot = new MiniatureSnapshot(16, List.of(
                new MiniatureSnapshot.Entry(0, 10, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(1, 14, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(0, 12, 1, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(1, 16, 1, "minecraft:stone", 0)));

        MiniatureSnapshot lod = MiniatureSnapshotLod.build(snapshot, 2);

        assertEquals(1, lod.entries().size());
        MiniatureSnapshot.Entry ground = lod.entries().getFirst();
        assertEquals(0, ground.x());
        assertEquals(0, ground.z());
        assertEquals(13, ground.y());
        assertEquals("minecraft:grass_block", ground.blockStateId());
        assertEquals(0x55AA33, ground.tint());
        assertTrue(lod.wasDownsampled());
    }

    @Test
    void dropsSparseFeatureNoiseWhenAggregating() {
        MiniatureSnapshot snapshot = new MiniatureSnapshot(16, List.of(
                new MiniatureSnapshot.Entry(0, 10, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(0, 18, 0, "minecraft:oak_leaves", 0x337733),
                new MiniatureSnapshot.Entry(1, 11, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(0, 12, 1, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(1, 13, 1, "minecraft:grass_block", 0x55AA33)));

        MiniatureSnapshot lod = MiniatureSnapshotLod.build(snapshot, 2);

        assertEquals(1, lod.entries().size());
        assertEquals("minecraft:grass_block", lod.entries().getFirst().blockStateId());
    }

    @Test
    void keepsDenseFeatureCoverageAtCoarseLevels() {
        MiniatureSnapshot snapshot = new MiniatureSnapshot(16, List.of(
                new MiniatureSnapshot.Entry(0, 10, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(0, 18, 0, "minecraft:oak_leaves", 0x337733),
                new MiniatureSnapshot.Entry(1, 11, 0, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(1, 19, 0, "minecraft:oak_leaves", 0x337733),
                new MiniatureSnapshot.Entry(0, 12, 1, "minecraft:grass_block", 0x55AA33),
                new MiniatureSnapshot.Entry(0, 20, 1, "minecraft:oak_leaves", 0x337733),
                new MiniatureSnapshot.Entry(1, 13, 1, "minecraft:grass_block", 0x55AA33)));

        MiniatureSnapshot lod = MiniatureSnapshotLod.build(snapshot, 2);

        assertEquals(2, lod.entries().size());
        assertEquals("minecraft:oak_leaves", lod.entries().getLast().blockStateId());
        assertEquals(19, lod.entries().getLast().y());
    }
}
