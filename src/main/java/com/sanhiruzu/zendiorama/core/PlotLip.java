package com.sanhiruzu.zendiorama.core;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for the diorama interior edge lip. No Minecraft dependencies. */
public final class PlotLip {

    private PlotLip() {
    }

    /**
     * The 1-block-high ring of cells at {@code lipY} covering the outer edge of the
     * {@code plotSize × plotSize} floor footprint anchored at ({@code originX}, {@code originZ}).
     *
     * @return list of {x, y, z} world coordinate triples.
     */
    public static List<int[]> perimeter(int originX, int lipY, int originZ, int plotSize) {
        List<int[]> cells = new ArrayList<>(Math.max(0, 4 * plotSize - 4));
        for (int lx = 0; lx < plotSize; lx++) {
            for (int lz = 0; lz < plotSize; lz++) {
                boolean onEdge = lx == 0 || lx == plotSize - 1 || lz == 0 || lz == plotSize - 1;
                if (onEdge) {
                    cells.add(new int[] {originX + lx, lipY, originZ + lz});
                }
            }
        }
        return cells;
    }
}
