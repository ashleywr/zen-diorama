package com.sanhiruzu.zendiorama.network;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries a world-map surface snapshot from server to client via a custom binary payload,
 * bypassing the 2 MB NbtAccounter limit that applies to block-entity update packets.
 */
public record WorldMapSnapshotPayload(BlockPos pos, MiniatureSnapshot snapshot)
        implements CustomPacketPayload {

    public static final Type<WorldMapSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "world_map_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldMapSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(WorldMapSnapshotPayload::encode, WorldMapSnapshotPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, WorldMapSnapshotPayload p) {
        buf.writeBlockPos(p.pos());
        List<MiniatureSnapshot.Entry> entries = p.snapshot().entries();
        int n = entries.size();
        buf.writeVarInt(p.snapshot().sourceBlockCount());
        buf.writeVarInt(n);

        // Palette-compress block state strings so unique ids are written once.
        Map<String, Integer> paletteIdx = new HashMap<>();
        List<String> palette = new ArrayList<>();
        int[] stateIdx = new int[n];
        boolean anyTint = false;
        for (int i = 0; i < n; i++) {
            MiniatureSnapshot.Entry e = entries.get(i);
            Integer idx = paletteIdx.get(e.blockStateId());
            if (idx == null) {
                idx = palette.size();
                paletteIdx.put(e.blockStateId(), idx);
                palette.add(e.blockStateId());
            }
            stateIdx[i] = idx;
            if (e.tint() != 0) anyTint = true;
        }
        buf.writeVarInt(palette.size());
        for (String s : palette) buf.writeUtf(s);

        // Per-entry: x, y (offset by 512 to avoid negatives), z, palette index.
        for (int i = 0; i < n; i++) {
            MiniatureSnapshot.Entry e = entries.get(i);
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.y() + 512);
            buf.writeVarInt(e.z());
            buf.writeVarInt(stateIdx[i]);
        }
        buf.writeBoolean(anyTint);
        if (anyTint) {
            for (MiniatureSnapshot.Entry e : entries) buf.writeInt(e.tint());
        }
    }

    private static WorldMapSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int source = buf.readVarInt();
        int n = buf.readVarInt();

        int paletteSize = buf.readVarInt();
        String[] palette = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) palette[i] = buf.readUtf();

        int[] xs = new int[n], ys = new int[n], zs = new int[n], si = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = buf.readVarInt();
            ys[i] = buf.readVarInt() - 512;
            zs[i] = buf.readVarInt();
            si[i]  = buf.readVarInt();
        }
        boolean anyTint = buf.readBoolean();
        int[] tints = anyTint ? new int[n] : null;
        if (anyTint) for (int i = 0; i < n; i++) tints[i] = buf.readInt();

        List<MiniatureSnapshot.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = si[i] >= 0 && si[i] < palette.length ? palette[si[i]] : "minecraft:air";
            entries.add(new MiniatureSnapshot.Entry(xs[i], ys[i], zs[i], id,
                    tints != null ? tints[i] : 0));
        }
        return new WorldMapSnapshotPayload(pos, new MiniatureSnapshot(source, entries));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
