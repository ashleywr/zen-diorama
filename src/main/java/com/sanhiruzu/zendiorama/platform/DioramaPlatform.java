package com.sanhiruzu.zendiorama.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface DioramaPlatform {
    boolean isClientEnvironment();

    Iterable<ServerPlayer> trackingPlayers(ServerLevel level, BlockPos pos);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    void sendToServer(CustomPacketPayload payload);

    void beginEntryCubemapCapture(double x, double y, double z, Runnable afterCapture);

    boolean isEntryCubemapCaptureActive();
}
