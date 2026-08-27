package com.sanhiruzu.zendiorama.server;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DioramaReturnData {
    private static final Map<UUID, ReturnTarget> RETURN_TARGETS = new ConcurrentHashMap<>();

    private DioramaReturnData() {
    }

    public static void store(ServerPlayer player, ServerLevel sourceLevel, UUID frameId, BlockPos framePos) {
        RETURN_TARGETS.put(player.getUUID(), new ReturnTarget(
                frameId,
                sourceLevel.dimension(),
                framePos.immutable(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()));
    }

    public static ReturnTarget get(ServerPlayer player) {
        return RETURN_TARGETS.get(player.getUUID());
    }

    public static void clear(ServerPlayer player) {
        RETURN_TARGETS.remove(player.getUUID());
    }

    public record ReturnTarget(UUID frameId, ResourceKey<Level> dimension, BlockPos framePos, double returnX, double returnY, double returnZ, float yaw, float pitch) {
    }
}
