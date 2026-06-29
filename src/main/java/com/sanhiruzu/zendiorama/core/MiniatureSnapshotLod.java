package com.sanhiruzu.zendiorama.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniatureSnapshotLod {
    private MiniatureSnapshotLod() {
    }

    public static MiniatureSnapshot build(MiniatureSnapshot snapshot, int factor) {
        if (factor <= 1 || snapshot.entries().isEmpty()) {
            return snapshot;
        }

        Map<Long, Column> columns = new HashMap<>();
        int maxX = 0;
        int maxZ = 0;
        for (MiniatureSnapshot.Entry entry : snapshot.entries()) {
            columns.computeIfAbsent(xzKey(entry.x(), entry.z()), ignored -> new Column()).include(entry);
            if (entry.x() > maxX) maxX = entry.x();
            if (entry.z() > maxZ) maxZ = entry.z();
        }

        int width = maxX + 1;
        int depth = maxZ + 1;
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();

        for (int baseX = 0, outX = 0; baseX < width; baseX += factor, outX++) {
            for (int baseZ = 0, outZ = 0; baseZ < depth; baseZ += factor, outZ++) {
                CellAggregate ground = new CellAggregate();
                CellAggregate feature = new CellAggregate();
                int occupiedColumns = 0;
                int featureColumns = 0;

                for (int dx = 0; dx < factor && baseX + dx < width; dx++) {
                    for (int dz = 0; dz < factor && baseZ + dz < depth; dz++) {
                        Column column = columns.get(xzKey(baseX + dx, baseZ + dz));
                        if (column == null) continue;
                        occupiedColumns++;
                        if (column.ground != null) {
                            ground.add(column.ground);
                        }
                        if (column.feature != null) {
                            feature.add(column.feature);
                            featureColumns++;
                        }
                    }
                }

                if (ground.count > 0) {
                    entries.add(ground.finish(outX, outZ));
                }
                if (feature.count > 0 && featureColumns * 2 >= occupiedColumns) {
                    entries.add(feature.finish(outX, outZ));
                }
            }
        }

        return new MiniatureSnapshot(snapshot.sourceBlockCount(), entries);
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static final class Column {
        private MiniatureSnapshot.Entry ground;
        private MiniatureSnapshot.Entry feature;

        private void include(MiniatureSnapshot.Entry entry) {
            if (ground == null || entry.y() < ground.y()) {
                feature = ground;
                ground = entry;
                return;
            }
            if (feature == null || entry.y() > feature.y()) {
                feature = entry;
            }
        }
    }

    private static final class CellAggregate {
        private final Map<Appearance, Integer> appearances = new LinkedHashMap<>();
        private int count;
        private int yTotal;

        private void add(MiniatureSnapshot.Entry entry) {
            count++;
            yTotal += entry.y();
            appearances.merge(new Appearance(entry.blockStateId(), entry.tint()), 1, Integer::sum);
        }

        private MiniatureSnapshot.Entry finish(int x, int z) {
            Appearance appearance = null;
            int bestCount = -1;
            for (Map.Entry<Appearance, Integer> entry : appearances.entrySet()) {
                if (entry.getValue() > bestCount) {
                    appearance = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            int y = Math.round((float) yTotal / count);
            return new MiniatureSnapshot.Entry(x, y, z, appearance.blockStateId, appearance.tint);
        }
    }

    private record Appearance(String blockStateId, int tint) {
    }
}
