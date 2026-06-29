package com.sanhiruzu.zendiorama.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: the entry cubemap capture has finished; safe to teleport. */
public record DioramaCaptureReadyPayload() implements CustomPacketPayload {
    public static final Type<DioramaCaptureReadyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("zen_diorama", "capture_ready"));

    public static final StreamCodec<ByteBuf, DioramaCaptureReadyPayload> STREAM_CODEC =
            StreamCodec.unit(new DioramaCaptureReadyPayload());

    @Override
    public Type<DioramaCaptureReadyPayload> type() {
        return TYPE;
    }
}
