package com.sanhiruzu.zendiorama.core;

import java.util.List;

public record MiniatureSnapshot(int sourceBlockCount, List<Entry> entries) {
    public MiniatureSnapshot {
        entries = List.copyOf(entries);
    }

    public boolean wasDownsampled() {
        return entries.size() < sourceBlockCount;
    }

    /**
     * @param tint packed RGB biome tint (grass/foliage/water), or 0 for "untinted — use the
     *             block's MapColor as-is". Only consumed by the world-map renderer; the
     *             miniature renderer draws real block textures and ignores it.
     */
    public record Entry(int x, int y, int z, String blockStateId, int tint) {
        public Entry(int x, int y, int z, String blockStateId) {
            this(x, y, z, blockStateId, 0);
        }
    }
}
