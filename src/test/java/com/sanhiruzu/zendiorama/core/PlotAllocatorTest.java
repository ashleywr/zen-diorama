package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlotAllocatorTest {
    @Test
    void allocatesFirstPlotAtOrigin() {
        Map<UUID, PlotOrigin> assignments = new LinkedHashMap<>();
        PlotOrigin origin = PlotAllocator.allocate(UUID.randomUUID(), assignments, 15, 16);

        assertEquals(new PlotOrigin(0, 0, 0), origin);
    }

    @Test
    void spacesPlotsByConfiguredStride() {
        Map<UUID, PlotOrigin> assignments = new LinkedHashMap<>();
        PlotAllocator.allocate(UUID.randomUUID(), assignments, 15, 16);
        PlotOrigin second = PlotAllocator.allocate(UUID.randomUUID(), assignments, 15, 16);

        assertEquals(new PlotOrigin(31, 0, 0), second);
    }

    @Test
    void returnsExistingAssignmentForSameFrame() {
        Map<UUID, PlotOrigin> assignments = new LinkedHashMap<>();
        UUID frameId = UUID.randomUUID();

        PlotOrigin first = PlotAllocator.allocate(frameId, assignments, 15, 16);
        PlotOrigin second = PlotAllocator.allocate(frameId, assignments, 15, 16);

        assertEquals(first, second);
        assertEquals(1, assignments.size());
    }
}
