package com.sanhiruzu.zendiorama.server;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.PlotOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class DioramaPlotSavedData extends SavedData {
    private static final String DATA_NAME = ZenDiorama.MOD_ID + "_plots";
    private static final Factory<DioramaPlotSavedData> FACTORY = new Factory<>(
            DioramaPlotSavedData::new,
            DioramaPlotSavedData::load,
            DataFixTypes.LEVEL);

    private final Map<UUID, PlotRecord> records = new LinkedHashMap<>();
    private int nextIndex;

    public static DioramaPlotSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public PlotRecord ensure(UUID frameId, int plotSize, int spacing) {
        PlotRecord existing = records.get(frameId);
        if (existing != null) {
            return existing;
        }

        int stride = Math.addExact(plotSize, spacing);
        PlotRecord record = new PlotRecord(new PlotOrigin(nextIndex * stride, 0, 0), null, null);
        records.put(frameId, record);
        nextIndex++;
        setDirty();
        return record;
    }

    public PlotRecord get(UUID frameId) {
        return records.get(frameId);
    }

    public void updateFramePosition(UUID frameId, ResourceKey<Level> dimension, BlockPos pos) {
        PlotRecord existing = records.get(frameId);
        if (existing == null) {
            return;
        }
        records.put(frameId, new PlotRecord(existing.origin(), dimension, pos));
        setDirty();
    }

    public void clearFramePosition(UUID frameId, BlockPos expectedPos) {
        PlotRecord existing = records.get(frameId);
        if (existing == null || existing.framePos() == null || !existing.framePos().equals(expectedPos)) {
            return;
        }
        records.put(frameId, new PlotRecord(existing.origin(), null, null));
        setDirty();
    }

    public void remove(UUID frameId) {
        if (records.remove(frameId) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextIndex", nextIndex);
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, PlotRecord> entry : records.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("FrameId", entry.getKey());
            PlotRecord record = entry.getValue();
            entryTag.putInt("PlotX", record.origin().x());
            entryTag.putInt("PlotY", record.origin().y());
            entryTag.putInt("PlotZ", record.origin().z());
            if (record.frameDimension() != null && record.framePos() != null) {
                entryTag.putString("FrameDimension", record.frameDimension().location().toString());
                entryTag.putInt("FrameX", record.framePos().getX());
                entryTag.putInt("FrameY", record.framePos().getY());
                entryTag.putInt("FrameZ", record.framePos().getZ());
            }
            entries.add(entryTag);
        }
        tag.put("Plots", entries);
        return tag;
    }

    private static DioramaPlotSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        DioramaPlotSavedData data = new DioramaPlotSavedData();
        data.nextIndex = tag.getInt("NextIndex");
        ListTag entries = tag.getList("Plots", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entryTag = entries.getCompound(i);
            UUID frameId = entryTag.getUUID("FrameId");
            PlotOrigin origin = new PlotOrigin(entryTag.getInt("PlotX"), entryTag.getInt("PlotY"), entryTag.getInt("PlotZ"));
            ResourceKey<Level> dimension = null;
            BlockPos framePos = null;
            if (entryTag.contains("FrameDimension")) {
                dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entryTag.getString("FrameDimension")));
                framePos = new BlockPos(entryTag.getInt("FrameX"), entryTag.getInt("FrameY"), entryTag.getInt("FrameZ"));
            }
            data.records.put(frameId, new PlotRecord(origin, dimension, framePos));
        }
        data.nextIndex = Math.max(data.nextIndex, data.records.size());
        return data;
    }

    public record PlotRecord(PlotOrigin origin, ResourceKey<Level> frameDimension, BlockPos framePos) {
    }
}
