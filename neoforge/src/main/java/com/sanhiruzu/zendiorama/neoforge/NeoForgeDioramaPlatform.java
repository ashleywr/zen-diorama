package com.sanhiruzu.zendiorama.neoforge;

import com.sanhiruzu.zendiorama.client.DioramaOffscreenCubemap;
import com.sanhiruzu.zendiorama.platform.DioramaPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

public final class NeoForgeDioramaPlatform implements DioramaPlatform {
    @Override
    public boolean isClientEnvironment() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public Iterable<ServerPlayer> trackingPlayers(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(pos), false);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void beginEntryCubemapCapture(double x, double y, double z, Runnable afterCapture) {
        DioramaOffscreenCubemap.beginCapture(x, y, z, afterCapture);
    }

    @Override
    public boolean isEntryCubemapCaptureActive() {
        return DioramaOffscreenCubemap.isActive();
    }
}
