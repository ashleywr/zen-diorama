package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.sanhiruzu.zendiorama.core.MiniatureBlockStateCodec;
import com.sanhiruzu.zendiorama.core.PlotOrigin;
import com.sanhiruzu.zendiorama.core.SnapshotSampler;
import com.sanhiruzu.zendiorama.server.DioramaPlotSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DioramaFrameBlockEntity extends BlockEntity {
    private UUID frameId;
    private PlotOrigin plotOrigin;
    private boolean dirty;
    private boolean plotInitialized;
    private long lastDirtyGameTime;
    private boolean alwaysLoaded;
    private MiniatureSnapshot snapshot = new MiniatureSnapshot(0, List.of());
    public transient int snapshotVersion;
    /** Client-only baked GPU geometry ({@code CachedBlockGeometry}); typed as Object to avoid loading client classes server-side. */
    public transient Object renderCache;

    public DioramaFrameBlockEntity(BlockPos pos, BlockState blockState) {
        super(ZenDiorama.DIORAMA_FRAME_ENTITY.get(), pos, blockState);
        this.alwaysLoaded = DioramaConfig.ALWAYS_LOADED_DEFAULT.get();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (renderCache instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // GPU buffer cleanup is best-effort
            }
            renderCache = null;
        }
    }

    public void ensureAssigned(BlockPos pos) {
        if (frameId == null) {
            frameId = UUID.randomUUID();
        }
        if (level instanceof ServerLevel serverLevel) {
            DioramaPlotSavedData.PlotRecord record = DioramaPlotSavedData.get(serverLevel)
                    .ensure(frameId, DioramaConfig.plotSize(), DioramaConfig.PLOT_SPACING.get());
            plotOrigin = record.origin();
            DioramaPlotSavedData.get(serverLevel).updateFramePosition(frameId, serverLevel.dimension(), pos);
        }
        setChanged();
    }

    public void registerPersistentPosition(ServerLevel level) {
        ensureAssigned(worldPosition);
        DioramaPlotSavedData.get(level).updateFramePosition(frameId, level.dimension(), worldPosition);
    }

    public void clearPersistentPosition(ServerLevel level) {
        if (frameId != null) {
            DioramaPlotSavedData.get(level).clearFramePosition(frameId, worldPosition);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel && frameId != null) {
            DioramaPlotSavedData.PlotRecord record = DioramaPlotSavedData.get(serverLevel)
                    .ensure(frameId, DioramaConfig.plotSize(), DioramaConfig.PLOT_SPACING.get());
            if (plotOrigin == null) {
                plotOrigin = record.origin();
            }
            DioramaPlotSavedData.get(serverLevel).updateFramePosition(frameId, serverLevel.dimension(), worldPosition);
        }
    }

    public void markInteriorDirty(long gameTime) {
        dirty = true;
        lastDirtyGameTime = gameTime;
        setChanged();
    }

    public boolean shouldRefreshSnapshot(long gameTime) {
        return dirty && gameTime - lastDirtyGameTime >= DioramaConfig.SYNC_DEBOUNCE_TICKS.get();
    }

    public void setSnapshot(MiniatureSnapshot snapshot) {
        this.snapshot = snapshot;
        this.dirty = false;
        this.snapshotVersion++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void refreshSnapshotFromInterior(ServerLevel interiorLevel) {
        if (plotOrigin == null) {
            return;
        }

        int plotSize = DioramaConfig.plotSize();
        int groundY = Math.max(interiorLevel.getMinBuildHeight() + 1, plotOrigin.y() + 1);
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();

        for (int y = groundY; y < groundY + plotSize; y++) {
            for (int x = plotOrigin.x(); x < plotOrigin.x() + plotSize; x++) {
                for (int z = plotOrigin.z(); z < plotOrigin.z() + plotSize; z++) {
                    BlockPos samplePos = new BlockPos(x, y, z);
                    BlockState state = interiorLevel.getBlockState(samplePos);
                    if (state.isAir()
                            || state.is(Blocks.BARRIER)
                            || state.is(ZenDiorama.DIORAMA_EXIT.get())
                            || state.is(ZenDiorama.DIORAMA_CONTROL.get())
                            || state.is(ZenDiorama.DIORAMA_EDGE.get())) {
                        continue;
                    }
                    entries.add(new MiniatureSnapshot.Entry(
                            x - plotOrigin.x(),
                            y - groundY,
                            z - plotOrigin.z(),
                            MiniatureBlockStateCodec.encode(state)));
                }
            }
        }

        setSnapshot(SnapshotSampler.sample(entries, DioramaConfig.MINIATURE_MAX_BLOCKS.get()));
    }

    public UUID getFrameId() {
        return frameId;
    }

    public PlotOrigin getPlotOrigin() {
        return plotOrigin;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isAlwaysLoaded() {
        return alwaysLoaded;
    }

    public void setAlwaysLoaded(boolean alwaysLoaded) {
        this.alwaysLoaded = alwaysLoaded;
        setChanged();
    }

    public void updateForcedChunks(ServerLevel interiorLevel) {
        setPlotChunksForced(interiorLevel, alwaysLoaded);
    }

    public void setPlotChunksForced(ServerLevel interiorLevel, boolean forced) {
        if (plotOrigin == null) {
            return;
        }

        int plotSize = DioramaConfig.plotSize();
        BlockPos min = new BlockPos(plotOrigin.x() - 1, plotOrigin.y(), plotOrigin.z() - 1);
        BlockPos max = new BlockPos(plotOrigin.x() + plotSize, plotOrigin.y(), plotOrigin.z() + plotSize);
        ChunkPos minChunk = new ChunkPos(min);
        ChunkPos maxChunk = new ChunkPos(max);
        for (int chunkX = minChunk.x; chunkX <= maxChunk.x; chunkX++) {
            for (int chunkZ = minChunk.z; chunkZ <= maxChunk.z; chunkZ++) {
                interiorLevel.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }

    public boolean isPlotInitialized() {
        return plotInitialized;
    }

    public void markPlotInitialized() {
        plotInitialized = true;
        setChanged();
    }

    public MiniatureSnapshot getSnapshot() {
        return snapshot;
    }

    public CompoundTag savePortableReference() {
        CompoundTag tag = new CompoundTag();
        if (frameId != null) {
            tag.putUUID("FrameId", frameId);
        }
        if (plotOrigin != null) {
            tag.putInt("PlotX", plotOrigin.x());
            tag.putInt("PlotY", plotOrigin.y());
            tag.putInt("PlotZ", plotOrigin.z());
        }
        tag.putBoolean("PlotInitialized", plotInitialized);
        tag.putBoolean("Dirty", dirty);
        tag.putLong("LastDirtyGameTime", lastDirtyGameTime);
        tag.putBoolean("AlwaysLoaded", alwaysLoaded);
        saveSnapshot(tag);
        return tag;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (frameId != null) {
            tag.putUUID("FrameId", frameId);
        }
        if (plotOrigin != null) {
            tag.putInt("PlotX", plotOrigin.x());
            tag.putInt("PlotY", plotOrigin.y());
            tag.putInt("PlotZ", plotOrigin.z());
        }
        tag.putBoolean("PlotInitialized", plotInitialized);
        tag.putBoolean("Dirty", dirty);
        tag.putLong("LastDirtyGameTime", lastDirtyGameTime);
        tag.putBoolean("AlwaysLoaded", alwaysLoaded);
        saveSnapshot(tag);
    }

    private void saveSnapshot(CompoundTag tag) {
        MiniatureSnapshotNbt.write(tag, snapshot);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        frameId = tag.hasUUID("FrameId") ? tag.getUUID("FrameId") : null;
        if (tag.contains("PlotX")) {
            plotOrigin = new PlotOrigin(tag.getInt("PlotX"), tag.getInt("PlotY"), tag.getInt("PlotZ"));
        }
        plotInitialized = tag.getBoolean("PlotInitialized");
        dirty = tag.getBoolean("Dirty");
        lastDirtyGameTime = tag.getLong("LastDirtyGameTime");
        alwaysLoaded = tag.getBoolean("AlwaysLoaded");

        snapshot = MiniatureSnapshotNbt.read(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
