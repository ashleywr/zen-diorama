package com.sanhiruzu.zendiorama.server;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import java.util.UUID;

public final class DioramaReturnData {
    private static final String RETURN_TAG = ZenDiorama.MOD_ID + ".Return";

    private DioramaReturnData() {
    }

    public static void store(ServerPlayer player, ServerLevel sourceLevel, UUID frameId, BlockPos framePos) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("FrameId", frameId);
        tag.putString("Dimension", sourceLevel.dimension().location().toString());
        tag.putInt("FrameX", framePos.getX());
        tag.putInt("FrameY", framePos.getY());
        tag.putInt("FrameZ", framePos.getZ());
        tag.putDouble("ReturnX", player.getX());
        tag.putDouble("ReturnY", player.getY());
        tag.putDouble("ReturnZ", player.getZ());
        tag.putFloat("Yaw", player.getYRot());
        tag.putFloat("Pitch", player.getXRot());
        player.getPersistentData().put(RETURN_TAG, tag);
    }

    public static ReturnTarget get(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(RETURN_TAG)) {
            return null;
        }

        CompoundTag tag = data.getCompound(RETURN_TAG);
        UUID frameId = tag.hasUUID("FrameId") ? tag.getUUID("FrameId") : null;
        ResourceLocation dimensionLocation = ResourceLocation.parse(tag.getString("Dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
        BlockPos framePos = new BlockPos(tag.getInt("FrameX"), tag.getInt("FrameY"), tag.getInt("FrameZ"));
        double returnX = tag.contains("ReturnX") ? tag.getDouble("ReturnX") : framePos.getX() + 0.5D;
        double returnY = tag.contains("ReturnY") ? tag.getDouble("ReturnY") : framePos.getY() + 1.1D;
        double returnZ = tag.contains("ReturnZ") ? tag.getDouble("ReturnZ") : framePos.getZ() + 0.5D;
        return new ReturnTarget(frameId, dimension, framePos, returnX, returnY, returnZ, tag.getFloat("Yaw"), tag.getFloat("Pitch"));
    }

    public static void clear(ServerPlayer player) {
        player.getPersistentData().remove(RETURN_TAG);
    }

    public record ReturnTarget(UUID frameId, ResourceKey<Level> dimension, BlockPos framePos, double returnX, double returnY, double returnZ, float yaw, float pitch) {
    }
}
