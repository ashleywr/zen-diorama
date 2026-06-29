package com.sanhiruzu.zendiorama.core;

import java.util.HashMap;
import java.util.Map;

public final class WorldMapProminentObjects {
    private static final int BASELINE_RADIUS = 2;
    private static final int MIN_PROMINENCE = 4;
    private static final int MIN_REGION_AREA = 4;
    private static final int MIN_REGION_SPAN = 2;

    private WorldMapProminentObjects() {
    }

    public static Result detect(MiniatureSnapshot snapshot) {
        Map<Long, Integer> heights = new HashMap<>();
        int width = 1;
        int depth = 1;
        for (MiniatureSnapshot.Entry entry : snapshot.entries()) {
            if (entry.x() + 1 > width) width = entry.x() + 1;
            if (entry.z() + 1 > depth) depth = entry.z() + 1;
            heights.merge(xzKey(entry.x(), entry.z()), entry.y(), Math::min);
        }

        int[] baseline = new int[width * depth];
        boolean[] candidate = new boolean[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int index = index(x, z, width);
                int center = heights.getOrDefault(xzKey(x, z), 0);
                int localBase = center;
                for (int dz = -BASELINE_RADIUS; dz <= BASELINE_RADIUS; dz++) {
                    for (int dx = -BASELINE_RADIUS; dx <= BASELINE_RADIUS; dx++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
                        localBase = Math.min(localBase, heights.getOrDefault(xzKey(nx, nz), center));
                    }
                }
                baseline[index] = localBase;
                candidate[index] = center - localBase >= MIN_PROMINENCE;
            }
        }

        boolean[] accepted = new boolean[width * depth];
        boolean[] visited = new boolean[width * depth];
        int[] queue = new int[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int start = index(x, z, width);
                if (!candidate[start] || visited[start]) continue;

                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                visited[start] = true;
                int area = 0;
                int minX = x, maxX = x, minZ = z, maxZ = z;
                int[] region = new int[width * depth];
                int regionCount = 0;

                while (head < tail) {
                    int current = queue[head++];
                    region[regionCount++] = current;
                    area++;
                    int cx = current % width;
                    int cz = current / width;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cz < minZ) minZ = cz;
                    if (cz > maxZ) maxZ = cz;

                    if (shouldVisit(cx - 1, cz, width, depth, candidate, visited)) {
                        queue[tail++] = index(cx - 1, cz, width);
                        visited[index(cx - 1, cz, width)] = true;
                    }
                    if (shouldVisit(cx + 1, cz, width, depth, candidate, visited)) {
                        queue[tail++] = index(cx + 1, cz, width);
                        visited[index(cx + 1, cz, width)] = true;
                    }
                    if (shouldVisit(cx, cz - 1, width, depth, candidate, visited)) {
                        queue[tail++] = index(cx, cz - 1, width);
                        visited[index(cx, cz - 1, width)] = true;
                    }
                    if (shouldVisit(cx, cz + 1, width, depth, candidate, visited)) {
                        queue[tail++] = index(cx, cz + 1, width);
                        visited[index(cx, cz + 1, width)] = true;
                    }
                }

                if (area >= MIN_REGION_AREA && maxX - minX + 1 >= MIN_REGION_SPAN && maxZ - minZ + 1 >= MIN_REGION_SPAN) {
                    for (int i = 0; i < regionCount; i++) {
                        accepted[region[i]] = true;
                    }
                }
            }
        }

        return new Result(width, accepted, baseline);
    }

    private static boolean shouldVisit(
            int x, int z, int width, int depth,
            boolean[] candidate, boolean[] visited) {
        if (x < 0 || z < 0 || x >= width || z >= depth) return false;
        int index = index(x, z, width);
        return candidate[index] && !visited[index];
    }

    private static int index(int x, int z, int width) {
        return z * width + x;
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public record Result(int width, boolean[] accepted, int[] baseline) {
        public boolean isObjectColumn(int x, int z) {
            return x >= 0 && z >= 0 && index(x, z, width) < accepted.length && accepted[index(x, z, width)];
        }

        public int baselineAt(int x, int z, int fallback) {
            int index = index(x, z, width);
            if (index < 0 || index >= baseline.length) return fallback;
            return baseline[index];
        }
    }
}
