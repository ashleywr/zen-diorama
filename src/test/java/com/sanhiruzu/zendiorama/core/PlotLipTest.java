package com.sanhiruzu.zendiorama.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotLipTest {

    @Test
    void perimeterCountMatchesRingFormula() {
        // For an N×N footprint the ring has 4*N - 4 cells.
        List<int[]> ring = PlotLip.perimeter(0, 5, 0, 15);
        assertEquals(4 * 15 - 4, ring.size());
    }

    @Test
    void allCellsAreOnTheEdgeAtLipY() {
        int originX = 100, originZ = -40, lipY = 7, size = 15;
        List<int[]> ring = PlotLip.perimeter(originX, lipY, originZ, size);
        for (int[] cell : ring) {
            int lx = cell[0] - originX;
            int lz = cell[2] - originZ;
            assertEquals(lipY, cell[1]);
            boolean onEdge = lx == 0 || lx == size - 1 || lz == 0 || lz == size - 1;
            assertTrue(onEdge, "cell not on edge: lx=" + lx + " lz=" + lz);
        }
    }

    @Test
    void includesAllFourCorners() {
        int size = 15;
        List<int[]> ring = PlotLip.perimeter(0, 0, 0, size);
        assertTrue(contains(ring, 0, 0, 0));
        assertTrue(contains(ring, size - 1, 0, 0));
        assertTrue(contains(ring, 0, 0, size - 1));
        assertTrue(contains(ring, size - 1, 0, size - 1));
    }

    private static boolean contains(List<int[]> ring, int x, int y, int z) {
        return ring.stream().anyMatch(c -> c[0] == x && c[1] == y && c[2] == z);
    }
}
