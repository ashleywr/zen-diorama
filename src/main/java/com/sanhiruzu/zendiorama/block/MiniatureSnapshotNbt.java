package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact NBT (de)serialization for a {@link MiniatureSnapshot}.
 *
 * <p>A world map snapshot can hold tens of thousands of entries. Writing each entry as a named-int/string
 * {@code CompoundTag} blows past the 2 MB block-entity update-packet limit. Instead we store:
 * <ul>
 *   <li>a string palette of unique block-state ids,</li>
 *   <li>one {@code int[]} of packed XYZ positions, and</li>
 *   <li>one {@code int[]} of palette indices.</li>
 * </ul>
 * This is roughly an order of magnitude smaller than the per-entry form.
 *
 * <p>Position packing assumes {@code x,z ∈ [0,1023]} (covers the max 512-block tile width) and
 * {@code y ∈ [-512,511]} (covers surface-relative heights across the full world height).
 */
public final class MiniatureSnapshotNbt {
    private static final String SOURCE_KEY = "SnapshotSourceBlocks";
    private static final String PALETTE_KEY = "MiniPalette";
    private static final String POS_KEY = "MiniPos";
    private static final String STATE_KEY = "MiniState";
    private static final String TINT_KEY = "MiniTint";
    private static final String LEGACY_KEY = "Snapshot";

    private MiniatureSnapshotNbt() {
    }

    public static void write(CompoundTag tag, MiniatureSnapshot snapshot) {
        tag.putInt(SOURCE_KEY, snapshot.sourceBlockCount());

        List<MiniatureSnapshot.Entry> entries = snapshot.entries();
        int n = entries.size();
        int[] positions = new int[n];
        int[] states = new int[n];
        int[] tints = new int[n];
        boolean anyTint = false;
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<String> palette = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            MiniatureSnapshot.Entry entry = entries.get(i);
            Integer idx = paletteIndex.get(entry.blockStateId());
            if (idx == null) {
                idx = palette.size();
                paletteIndex.put(entry.blockStateId(), idx);
                palette.add(entry.blockStateId());
            }
            states[i] = idx;
            positions[i] = pack(entry.x(), entry.y(), entry.z());
            tints[i] = entry.tint();
            if (entry.tint() != 0) anyTint = true;
        }

        ListTag paletteTag = new ListTag();
        for (String id : palette) {
            paletteTag.add(StringTag.valueOf(id));
        }
        tag.put(PALETTE_KEY, paletteTag);
        tag.putIntArray(POS_KEY, positions);
        tag.putIntArray(STATE_KEY, states);
        // Only written when at least one entry is biome-tinted (the miniature path never tints),
        // so frame snapshots stay as small as before.
        if (anyTint) {
            tag.putIntArray(TINT_KEY, tints);
        }
    }

    public static MiniatureSnapshot read(CompoundTag tag) {
        int source = tag.getInt(SOURCE_KEY);

        if (tag.contains(POS_KEY, Tag.TAG_INT_ARRAY)) {
            ListTag paletteTag = tag.getList(PALETTE_KEY, Tag.TAG_STRING);
            String[] palette = new String[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = paletteTag.getString(i);
            }

            int[] positions = tag.getIntArray(POS_KEY);
            int[] states = tag.getIntArray(STATE_KEY);
            int[] tints = tag.contains(TINT_KEY, Tag.TAG_INT_ARRAY) ? tag.getIntArray(TINT_KEY) : null;
            List<MiniatureSnapshot.Entry> entries = new ArrayList<>(positions.length);
            for (int i = 0; i < positions.length; i++) {
                int p = positions[i];
                int stateIdx = i < states.length ? states[i] : 0;
                String id = stateIdx >= 0 && stateIdx < palette.length ? palette[stateIdx] : "minecraft:air";
                int tint = tints != null && i < tints.length ? tints[i] : 0;
                entries.add(new MiniatureSnapshot.Entry(unpackX(p), unpackY(p), unpackZ(p), id, tint));
            }
            return new MiniatureSnapshot(source, entries);
        }

        // Legacy per-entry form (pre-compaction saves).
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();
        ListTag entryTags = tag.getList(LEGACY_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < entryTags.size(); i++) {
            CompoundTag entryTag = entryTags.getCompound(i);
            entries.add(new MiniatureSnapshot.Entry(
                    entryTag.getInt("X"),
                    entryTag.getInt("Y"),
                    entryTag.getInt("Z"),
                    entryTag.getString("BlockState")));
        }
        return new MiniatureSnapshot(source, entries);
    }

    private static int pack(int x, int y, int z) {
        return (x & 0x3FF) | ((z & 0x3FF) << 10) | ((y & 0x3FF) << 20);
    }

    private static int unpackX(int packed) {
        return packed & 0x3FF;
    }

    private static int unpackZ(int packed) {
        return (packed >> 10) & 0x3FF;
    }

    private static int unpackY(int packed) {
        int y = (packed >> 20) & 0x3FF;
        // Sign-extend the 10-bit signed value.
        if ((y & 0x200) != 0) {
            y |= ~0x3FF;
        }
        return y;
    }
}
