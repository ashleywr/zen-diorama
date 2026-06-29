package com.sanhiruzu.zendiorama.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class WorldMapGroupRulesTest {
    @Test
    void acceptsAllowedFilledSquare() {
        WorldMapBlockEntity.ConnectedGroup group = new WorldMapBlockEntity.ConnectedGroup(
                Direction.UP, filledBounds(1, 1), 0, 0, 0, 0);

        assertTrue(WorldMapGroupRules.isValid(group));
    }

    @Test
    void rejectsNonSquareRectangle() {
        WorldMapBlockEntity.ConnectedGroup group = new WorldMapBlockEntity.ConnectedGroup(
                Direction.UP, filledBounds(2, 1), 0, 1, 0, 0);

        assertFalse(WorldMapGroupRules.isValid(group));
    }

    @Test
    void rejectsHoleInsideSquareBounds() {
        Map<BlockPos, WorldMapBlockEntity> tiles = filledBounds(2, 2);
        tiles.remove(new BlockPos(1, 0, 1));
        WorldMapBlockEntity.ConnectedGroup group = new WorldMapBlockEntity.ConnectedGroup(
                Direction.UP, tiles, 0, 1, 0, 1);

        assertFalse(WorldMapGroupRules.isValid(group));
    }

    @Test
    void acceptsSquareSizeBelowMaxTileLimit() {
        WorldMapBlockEntity.ConnectedGroup group = new WorldMapBlockEntity.ConnectedGroup(
                Direction.UP, filledBounds(5, 5), 0, 4, 0, 4);

        assertTrue(WorldMapGroupRules.isValid(group));
    }

    private static Map<BlockPos, WorldMapBlockEntity> filledBounds(int width, int height) {
        Map<BlockPos, WorldMapBlockEntity> tiles = new HashMap<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                tiles.put(new BlockPos(x, 0, z), null);
            }
        }
        return tiles;
    }
}
