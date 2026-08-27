package com.sanhiruzu.zendiorama.network;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WorldMapSnapshotRequestPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<WorldMapSnapshotRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "world_map_snapshot_request"));

    public static final StreamCodec<FriendlyByteBuf, WorldMapSnapshotRequestPayload> STREAM_CODEC =
            StreamCodec.of(WorldMapSnapshotRequestPayload::encode, WorldMapSnapshotRequestPayload::decode);

    private static void encode(FriendlyByteBuf buffer, WorldMapSnapshotRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
    }

    private static WorldMapSnapshotRequestPayload decode(FriendlyByteBuf buffer) {
        return new WorldMapSnapshotRequestPayload(buffer.readBlockPos());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
