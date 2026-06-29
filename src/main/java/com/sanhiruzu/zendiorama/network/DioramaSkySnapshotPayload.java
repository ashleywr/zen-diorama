package com.sanhiruzu.zendiorama.network;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// Atmospheric metadata sent from server on diorama entry. Face int arrays removed in v2;
// the actual cubemap is now captured client-side by DioramaOffscreenCubemap.
public record DioramaSkySnapshotPayload(
        ResourceLocation sourceDimension,
        BlockPos sourcePos,
        int skyColor,
        int skyColorNorth,
        int skyColorSouth,
        int skyColorEast,
        int skyColorWest,
        int skyColorUp,
        int skyColorDown,
        long dayTime,
        float rainLevel,
        float thunderLevel
) implements CustomPacketPayload {
    public static final Type<DioramaSkySnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "sky_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DioramaSkySnapshotPayload> STREAM_CODEC =
            StreamCodec.of(DioramaSkySnapshotPayload::encode, DioramaSkySnapshotPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, DioramaSkySnapshotPayload payload) {
        buffer.writeResourceLocation(payload.sourceDimension());
        buffer.writeBlockPos(payload.sourcePos());
        buffer.writeInt(payload.skyColor());
        buffer.writeInt(payload.skyColorNorth());
        buffer.writeInt(payload.skyColorSouth());
        buffer.writeInt(payload.skyColorEast());
        buffer.writeInt(payload.skyColorWest());
        buffer.writeInt(payload.skyColorUp());
        buffer.writeInt(payload.skyColorDown());
        buffer.writeVarLong(payload.dayTime());
        buffer.writeFloat(payload.rainLevel());
        buffer.writeFloat(payload.thunderLevel());
    }

    private static DioramaSkySnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
        return new DioramaSkySnapshotPayload(
                buffer.readResourceLocation(),
                buffer.readBlockPos(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarLong(),
                buffer.readFloat(),
                buffer.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
