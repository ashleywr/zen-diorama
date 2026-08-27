package com.sanhiruzu.zendiorama.fabric;

import com.sanhiruzu.zendiorama.platform.DioramaPlatform;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class FabricDioramaPlatform implements DioramaPlatform {
    @Override
    public boolean isClientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public Iterable<ServerPlayer> trackingPlayers(ServerLevel level, BlockPos pos) {
        return PlayerLookup.tracking(level, pos);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void beginEntryCubemapCapture(double x, double y, double z, Runnable afterCapture) {
        afterCapture.run();
    }

    @Override
    public boolean isEntryCubemapCaptureActive() {
        return false;
    }
}
