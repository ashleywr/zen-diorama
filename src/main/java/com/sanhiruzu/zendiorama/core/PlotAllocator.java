package com.sanhiruzu.zendiorama.core;

import java.util.Map;
import java.util.UUID;

public final class PlotAllocator {
    private PlotAllocator() {
    }

    public static PlotOrigin allocate(UUID frameId, Map<UUID, PlotOrigin> assignments, int plotSize, int spacing) {
        PlotOrigin existing = assignments.get(frameId);
        if (existing != null) {
            return existing;
        }

        int stride = Math.addExact(plotSize, spacing);
        PlotOrigin origin = new PlotOrigin(assignments.size() * stride, 0, 0);
        assignments.put(frameId, origin);
        return origin;
    }
}
