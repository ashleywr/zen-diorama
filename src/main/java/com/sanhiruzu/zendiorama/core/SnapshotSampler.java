package com.sanhiruzu.zendiorama.core;

import java.util.ArrayList;
import java.util.List;

public final class SnapshotSampler {
    private SnapshotSampler() {
    }

    public static MiniatureSnapshot sample(List<MiniatureSnapshot.Entry> source, int maxBlocks) {
        if (maxBlocks < 1) {
            throw new IllegalArgumentException("maxBlocks must be positive");
        }
        if (source.size() <= maxBlocks) {
            return new MiniatureSnapshot(source.size(), source);
        }

        int stride = (int) Math.ceil(source.size() / (double) maxBlocks);
        List<MiniatureSnapshot.Entry> sampled = new ArrayList<>(maxBlocks);
        for (int i = 0; i < source.size() && sampled.size() < maxBlocks; i += stride) {
            sampled.add(source.get(i));
        }
        return new MiniatureSnapshot(source.size(), sampled);
    }
}
