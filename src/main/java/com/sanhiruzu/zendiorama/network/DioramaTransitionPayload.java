package com.sanhiruzu.zendiorama.network;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public record DioramaTransitionPayload(boolean entering, @Nullable BlockPos framePos) implements CustomPacketPayload {
    public static final Type<DioramaTransitionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "transition"));

    public static final StreamCodec<FriendlyByteBuf, DioramaTransitionPayload> STREAM_CODEC =
            StreamCodec.of(DioramaTransitionPayload::encode, DioramaTransitionPayload::decode);

    private static void encode(FriendlyByteBuf buffer, DioramaTransitionPayload payload) {
        buffer.writeBoolean(payload.entering());
        boolean hasPos = payload.framePos() != null;
        buffer.writeBoolean(hasPos);
        if (hasPos) {
            buffer.writeBlockPos(payload.framePos());
        }
    }

    private static DioramaTransitionPayload decode(FriendlyByteBuf buffer) {
        boolean entering = buffer.readBoolean();
        boolean hasPos = buffer.readBoolean();
        BlockPos framePos = hasPos ? buffer.readBlockPos() : null;
        return new DioramaTransitionPayload(entering, framePos);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
