package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SnapshotSamplerTest {
    @Test
    void keepsSmallSnapshotsIntact() {
        List<MiniatureSnapshot.Entry> source = entries(3);

        MiniatureSnapshot snapshot = SnapshotSampler.sample(source, 4096);

        assertEquals(source, snapshot.entries());
        assertEquals(3, snapshot.sourceBlockCount());
        assertFalse(snapshot.wasDownsampled());
    }

    @Test
    void capsLargeSnapshotsAtMaximumEntryCount() {
        MiniatureSnapshot snapshot = SnapshotSampler.sample(entries(10), 4);

        assertEquals(10, snapshot.sourceBlockCount());
        assertEquals(4, snapshot.entries().size());
        assertTrue(snapshot.wasDownsampled());
    }

    @Test
    void rejectsInvalidMaximum() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotSampler.sample(entries(1), 0));
    }

    private static List<MiniatureSnapshot.Entry> entries(int count) {
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new MiniatureSnapshot.Entry(i, 0, 0, "minecraft:stone"));
        }
        return entries;
    }
}
