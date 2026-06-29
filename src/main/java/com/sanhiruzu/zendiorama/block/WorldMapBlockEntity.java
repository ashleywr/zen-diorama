package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.sanhiruzu.zendiorama.core.SurfaceSampler;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class WorldMapBlockEntity extends BlockEntity {
    public static final int MAX_SAMPLER_RESOLUTION = 512;
    private static final int SNAPSHOT_REFRESH_PHASES = 20;
    private boolean configured;
    private boolean layoutValid = true;
    private boolean layoutSevereInvalid;
    private int anchorBlockX;
    private int anchorBlockY;
    private int anchorBlockZ;
    private int mapCenterX;
    private int mapCenterZ;
    private int blocksPerTile;
    /** NaN means "use config default". */
    private float heightExaggeration = Float.NaN;
    /** NaN means "use config default". */
    private float elevationTint = Float.NaN;
    /** Index into {@link WorldMapZoomLevel#LEVELS}. Default 0 = 32/32. */
    private int zoomIndex = 0;
    /** Index into {@link WorldMapPreset#PRESETS}. Default 1 = Natural. */
    private int styleIndex = 1;
    /** Overrides global MAP_RESOLUTION for this tile's sampler. 0 = use global config. */
    private int samplerResolution = 0;
    private boolean dirty;
    private boolean waitingForChunks;
    private boolean refreshImmediately;
    private long lastDirtyTime;
    /** Which of the 4 cardinal sides have an adjacent world-map block.
     *  Bit 0=N, bit 1=S, bit 2=W, bit 3=E. Computed server-side, synced to client. */
    private int neighborMask = 0;
    private int presentationTileX = 0;
    private int presentationTileZ = 0;
    private int presentationTilesWide = 1;
    private int presentationTilesTall = 1;
    private MiniatureSnapshot snapshot = new MiniatureSnapshot(0, List.of());
    public transient int snapshotVersion;
    /** Client-only baked GPU geometry ({@code CachedBlockGeometry}); typed as Object to avoid loading client classes server-side. */
    public transient Object renderCache;
    private transient MiniatureSnapshot sampledGridSnapshot;
    private transient int sampledGridSize = 1;

    public WorldMapBlockEntity(BlockPos pos, BlockState state) {
        super(ZenDiorama.WORLD_MAP_ENTITY.get(), pos, state);
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, WorldMapBlockEntity self) {
        if (!self.isConfigured()) {
            // Retry neighbor detection every second until configured
            if (level.getGameTime() % 20 == 0) self.detectRegionFromNeighbors();
            return;
        }
        if (!self.dirty) return;
        long debounce = DioramaConfig.SYNC_DEBOUNCE_TICKS.get();
        long gap = level.getGameTime() - self.lastDirtyTime;
        if (gap < debounce) return;
        if (!self.refreshImmediately && !self.isSnapshotRefreshPhase(level.getGameTime())) return;
        if (level.getServer() == null) return;
        ServerLevel overworld = level.getServer().overworld();
        if (overworld == null) return;
        self.refreshSnapshotFromOverworld(overworld);
    }

    public void configure(int centerX, int centerZ, int tileWidth) {
        configure(centerX, centerZ, tileWidth, getStoredAnchorPos());
    }

    public void configure(int centerX, int centerZ, int tileWidth, BlockPos anchorPos) {
        // Skip if nothing changed — prevents infinite propagation loops between neighbors
        if (configured
                && mapCenterX == centerX
                && mapCenterZ == centerZ
                && blocksPerTile == tileWidth
                && anchorBlockX == anchorPos.getX()
                && anchorBlockY == anchorPos.getY()
                && anchorBlockZ == anchorPos.getZ()) return;
        this.mapCenterX = centerX;
        this.mapCenterZ = centerZ;
        this.blocksPerTile = tileWidth;
        setStoredAnchorPos(anchorPos);
        this.configured = true;
        this.dirty = true;
        this.waitingForChunks = false;
        this.refreshImmediately = false;
        this.lastDirtyTime = level != null ? level.getGameTime() : 0;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        propagateToNeighbors();
    }

    // ---------------------------------------------------------------------------
    // Facing-aware plane geometry helpers
    // ---------------------------------------------------------------------------

    /**
     * Describes one of the 4 in-plane neighbor directions for a given block facing.
     * dMapX / dMapZ are the signed multipliers (×blocksPerTile) to apply to mapCenterX/Z
     * when propagating configuration to that neighbor.
     */
    public record PlaneDir(Direction blockDir, int dMapX, int dMapZ) {}
    public record ConnectedGroup(Direction facing, Map<BlockPos, WorldMapBlockEntity> tiles,
                                 int minA, int maxA, int minB, int maxB) {
        public int width() { return maxA - minA + 1; }
        public int height() { return maxB - minB + 1; }
        public int tileCount() { return tiles.size(); }
    }

    /**
     * Returns the 4 in-plane neighbor directions for the given map-surface facing,
     * together with how each step changes the map center coordinates.
     *
     * <p>For a floor (UP), neighbors are the 4 horizontal directions and the map axes
     * match X/Z as normal. For wall facings the plane rotates and the map axes shift
     * accordingly (vertical block adjacency drives the Z or X map axis).
     */
    public static PlaneDir[] planeDirs(Direction facing) {
        return switch (facing) {
            case UP, DOWN -> new PlaneDir[]{
                    new PlaneDir(Direction.EAST,  +1,  0),
                    new PlaneDir(Direction.WEST,  -1,  0),
                    new PlaneDir(Direction.SOUTH,  0, +1),
                    new PlaneDir(Direction.NORTH,  0, -1),
            };
            case SOUTH -> new PlaneDir[]{
                    new PlaneDir(Direction.EAST,  +1,  0),
                    new PlaneDir(Direction.WEST,  -1,  0),
                    new PlaneDir(Direction.UP,     0, +1),
                    new PlaneDir(Direction.DOWN,   0, -1),
            };
            case NORTH -> new PlaneDir[]{
                    new PlaneDir(Direction.EAST,  +1,  0),
                    new PlaneDir(Direction.WEST,  -1,  0),
                    new PlaneDir(Direction.UP,     0, -1),
                    new PlaneDir(Direction.DOWN,   0, +1),
            };
            case EAST, WEST -> new PlaneDir[]{
                    new PlaneDir(Direction.UP,    +1,  0),
                    new PlaneDir(Direction.DOWN,  -1,  0),
                    new PlaneDir(Direction.SOUTH,  0, +1),
                    new PlaneDir(Direction.NORTH,  0, -1),
            };
        };
    }

    /**
     * Returns the 4 in-plane neighbor directions ordered as [geo-N, geo-S, geo-W, geo-E].
     * These correspond to neighborMask bits 0..3 and control which borders the renderer draws.
     */
    static Direction[] geoNeighborDirs(Direction facing) {
        return switch (facing) {
            case UP, DOWN  -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case SOUTH     -> new Direction[]{Direction.DOWN,  Direction.UP,    Direction.WEST, Direction.EAST};
            case NORTH     -> new Direction[]{Direction.UP,    Direction.DOWN,  Direction.WEST, Direction.EAST};
            case EAST, WEST-> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP};
        };
    }

    /**
     * Projects a tile's block position onto the axis that drives mapCenterX for its facing.
     * Two tiles with the same facing whose tileAxisA values differ by 1 have mapCenterX
     * values differing by blocksPerTile.
     */
    public static int tileAxisA(BlockPos pos, Direction facing) {
        return switch (facing) {
            case UP, DOWN, NORTH, SOUTH -> pos.getX();
            case EAST, WEST             -> pos.getY();
        };
    }

    /**
     * Projects a tile's block position onto the axis that drives mapCenterZ for its facing.
     * Higher values correspond to higher mapCenterZ (more southern terrain).
     */
    public static int tileAxisB(BlockPos pos, Direction facing) {
        return switch (facing) {
            case UP, DOWN, EAST, WEST -> pos.getZ();
            case SOUTH                -> pos.getY();
            case NORTH                -> -pos.getY();
        };
    }

    /** Reads this tile's FACING from the block state; defaults to UP if unavailable. */
    private Direction getBlockFacing() {
        if (level == null) return Direction.UP;
        BlockState st = level.getBlockState(worldPosition);
        return st.hasProperty(WorldMapBlock.FACING) ? st.getValue(WorldMapBlock.FACING) : Direction.UP;
    }

    private void propagateToNeighbors() {
        if (level == null || level.isClientSide) return;
        Direction facing = getBlockFacing();
        for (PlaneDir pd : planeDirs(facing)) {
            BlockPos adj = worldPosition.relative(pd.blockDir());
            if (!level.isLoaded(adj)) continue;
            BlockState adjState = level.getBlockState(adj);
            if (!adjState.hasProperty(WorldMapBlock.FACING) || adjState.getValue(WorldMapBlock.FACING) != facing) continue;
            BlockEntity neighbor = level.getBlockEntity(adj);
            // Propagate to BOTH unconfigured and already-configured neighbors so that
            // right-clicking or using a command on any tile in a group recenters them all.
            // configure() has an early-exit guard so this terminates rather than looping.
            if (neighbor instanceof WorldMapBlockEntity wme) {
                wme.configure(
                        mapCenterX + pd.dMapX() * blocksPerTile,
                        mapCenterZ + pd.dMapZ() * blocksPerTile,
                        blocksPerTile,
                        getStoredAnchorPos());
            }
        }
    }

    public void detectRegionFromNeighbors() {
        if (configured || level == null || level.isClientSide) return;
        Direction facing = getBlockFacing();
        for (PlaneDir pd : planeDirs(facing)) {
            BlockPos adj = worldPosition.relative(pd.blockDir());
            if (!level.isLoaded(adj)) continue;
            BlockState adjState = level.getBlockState(adj);
            if (!adjState.hasProperty(WorldMapBlock.FACING) || adjState.getValue(WorldMapBlock.FACING) != facing) continue;
            BlockEntity neighbor = level.getBlockEntity(adj);
            if (neighbor instanceof WorldMapBlockEntity wme && wme.hasStoredConfiguration()) {
                configure(
                        wme.mapCenterX - pd.dMapX() * wme.blocksPerTile,
                        wme.mapCenterZ - pd.dMapZ() * wme.blocksPerTile,
                        wme.blocksPerTile,
                        wme.getStoredAnchorPos());
                return;
            }
        }
    }

    public int getNeighborMask() { return neighborMask; }
    public int getPresentationTileX() { return presentationTileX; }
    public int getPresentationTileZ() { return presentationTileZ; }
    public int getPresentationTilesWide() { return presentationTilesWide; }
    public int getPresentationTilesTall() { return presentationTilesTall; }

    /** Recomputes which of the 4 in-plane sides have an adjacent same-facing world-map block
     *  and syncs to clients if changed.  Mask bits: 0=geoN, 1=geoS, 2=geoW, 3=geoE.
     *
     *  <p>Only queries block states for chunks that are already loaded — querying unloaded chunks
     *  during block-entity initialisation forces synchronous chunk loading which re-enters
     *  setLevel() and causes a StackOverflowError. */
    public void updateNeighborMask() {
        if (level == null) return;
        Direction facing = getBlockFacing();
        Direction[] geoNbrs = geoNeighborDirs(facing);
        net.minecraft.world.level.block.Block wm = ZenDiorama.WORLD_MAP.get();
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            BlockPos adj = worldPosition.relative(geoNbrs[i]);
            if (!level.isLoaded(adj)) continue;
            BlockState adjState = level.getBlockState(adj);
            if (adjState.is(wm)
                    && adjState.hasProperty(WorldMapBlock.FACING)
                    && adjState.getValue(WorldMapBlock.FACING) == facing) {
                mask |= (1 << i);
            }
        }
        if (mask != neighborMask) {
            neighborMask = mask;
            setChanged();
            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        // Do NOT query neighbors here. setLevel fires during chunk loading; calling
        // getBlockEntity(adj) on a position that has no entity yet causes Minecraft to
        // create a new one, which calls setLevel() again → StackOverflowError.
        // Neighbor detection runs in serverTick() (unconfigured tiles) and setPlacedBy()
        // (fresh placement). neighborChanged() handles live updates.
    }

    private void refreshSnapshotFromOverworld(ServerLevel overworld) {
        int w = blocksPerTile;
        int originX = mapCenterX - w / 2;
        int originZ = mapCenterZ - w / 2;

        // Cap at 512: keeps the custom payload under ~4 MB uncompressed (~300 KB compressed),
        // well within Minecraft's ~2 MB wire-frame limit. The old NBT path was limited to 256
        // by the NbtAccounter; custom payloads have no such limit, so 512 is safe.
        // (MiniPos used 10-bit packing so anything ≤ 1023 is fine for coordinates.)
        int res = getEffectiveSamplerResolution();
        SurfaceSampler.SampleResult sample = SurfaceSampler.sampleWithStatus(overworld, originX, originZ, w, res * res * 2);
        MiniatureSnapshot fresh = sample.snapshot();
        if (fresh.entries().isEmpty()) {
            // No loaded columns overlapped this tile. Keep the placeholder, but do not poll
            // forever for chunks that may be outside the server's active loading radius.
            dirty = false;
            waitingForChunks = true;
            refreshImmediately = false;
            lastDirtyTime = level != null ? level.getGameTime() : 0;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            return;
        }
        this.snapshot = fresh;
        this.dirty = false;
        this.waitingForChunks = false;
        this.refreshImmediately = false;
        this.lastDirtyTime = level != null ? level.getGameTime() : 0;
        this.snapshotVersion++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            // Explicitly send BE data with Dirty=false to every player in this level.
            // sendBlockUpdated queues the block for deferred chunk-tracking sends which may
            // be batched with earlier Dirty=true updates; explicit send guarantees the client
            // receives Dirty=false before the snapshot payload that follows it.
            net.minecraft.network.protocol.Packet<?> bePkt = getUpdatePacket();
            WorldMapSnapshotPayload payload = new WorldMapSnapshotPayload(
                    worldPosition.immutable(), this.snapshot);
            ChunkPos chunkPos = new ChunkPos(worldPosition);
            for (ServerPlayer sp : ((ServerLevel) level).getChunkSource().chunkMap.getPlayers(chunkPos, false)) {
                if (bePkt != null) sp.connection.send(bePkt);
                PacketDistributor.sendToPlayer(sp, payload);
            }
        }
    }

    /** Sets the snapshot on the client side after receiving a {@link WorldMapSnapshotPayload}. */
    public void setSnapshot(MiniatureSnapshot snapshot) {
        this.snapshot = snapshot;
        this.dirty = false;
        this.waitingForChunks = false;
        this.refreshImmediately = false;
        this.snapshotVersion++;
        this.sampledGridSnapshot = null;
        if (renderCache instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
        renderCache = null;
    }

    public boolean isConfigured() { return configured && layoutValid; }
    public boolean hasStoredConfiguration() { return configured; }
    public boolean isLayoutValid() { return layoutValid; }
    public boolean isLayoutSevereInvalid() { return layoutSevereInvalid; }
    public boolean isDirty() { return dirty; }
    public boolean isWaitingForChunks() { return waitingForChunks; }
    public BlockPos getStoredAnchorPos() { return new BlockPos(anchorBlockX, anchorBlockY, anchorBlockZ); }
    public int getMapCenterX() { return mapCenterX; }
    public int getMapCenterZ() { return mapCenterZ; }
    public int getBlocksPerTile() { return blocksPerTile > 0 ? blocksPerTile : DioramaConfig.MAP_BLOCKS_PER_TILE.get(); }
    public MiniatureSnapshot getSnapshot() { return snapshot; }

    public int getSampledGridSize() {
        if (sampledGridSnapshot == snapshot) {
            return sampledGridSize;
        }
        int size = 1;
        for (var e : snapshot.entries()) {
            if (e.x() + 1 > size) size = e.x() + 1;
            if (e.z() + 1 > size) size = e.z() + 1;
        }
        sampledGridSnapshot = snapshot;
        sampledGridSize = size;
        return size;
    }

    public int getExpectedSampledGridSize() {
        int width = getBlocksPerTile();
        int res = getEffectiveSamplerResolution();
        return SurfaceSampler.sampledGridWidth(width, res * res * 2);
    }

    public float getEffectiveHeightExaggeration() {
        return Float.isNaN(heightExaggeration) ? DioramaConfig.MAP_HEIGHT_EXAGGERATION.get().floatValue() : heightExaggeration;
    }

    public float getEffectiveElevationTint() {
        return Float.isNaN(elevationTint) ? DioramaConfig.MAP_ELEVATION_TINT.get().floatValue() : elevationTint;
    }

    public boolean hasHeightOverride() { return !Float.isNaN(heightExaggeration); }
    public boolean hasTintOverride() { return !Float.isNaN(elevationTint); }

    public void setHeightExaggeration(float value) {
        this.heightExaggeration = value;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void setElevationTint(float value) {
        this.elevationTint = value;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void resetHeightExaggeration() { setHeightExaggeration(Float.NaN); }
    public void resetElevationTint() { setElevationTint(Float.NaN); }

    public int getZoomIndex()  { return zoomIndex; }
    public int getStyleIndex() { return styleIndex; }
    public int getSamplerResolution() { return samplerResolution > 0 ? samplerResolution : DioramaConfig.MAP_RESOLUTION.get(); }
    public int getEffectiveSamplerResolution() { return Math.min(getSamplerResolution(), MAX_SAMPLER_RESOLUTION); }

    /** Sets the zoom metadata for this tile only (scale is handled separately via configure()). */
    public void applyZoom(int idx, int voxels) {
        this.zoomIndex = idx;
        this.samplerResolution = voxels;
        setChanged();
    }

    /** Sets style (height + tint) for this tile and notifies the client immediately. */
    void applyStyle(int idx, float height, float tint) {
        this.styleIndex = idx;
        this.heightExaggeration = height;
        this.elevationTint = tint;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Force an immediate snapshot re-sample from the overworld, bypassing the debounce. */
    public void forceRefresh() {
        if (level == null || level.isClientSide || level.getServer() == null) return;
        ServerLevel overworld = level.getServer().overworld();
        if (overworld != null) refreshSnapshotFromOverworld(overworld);
    }

    /** Mark this tile dirty and bypass the debounce so it re-samples on the next server tick.
     *  Sets lastDirtyTime to (gameTime - debounce - 1) so the debounce check always passes
     *  immediately, even in a fresh world where gameTime itself is less than the debounce window.
     *  Also notifies clients so the loading animation starts right away. */
    public void markForImmediateRefresh() {
        if (level == null || level.isClientSide) return;
        dirty = true;
        waitingForChunks = false;
        refreshImmediately = true;
        lastDirtyTime = level.getGameTime() - DioramaConfig.SYNC_DEBOUNCE_TICKS.get() - 1;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private boolean isSnapshotRefreshPhase(long gameTime) {
        return Math.floorMod(worldPosition.hashCode(), SNAPSHOT_REFRESH_PHASES)
                == Math.floorMod(gameTime, SNAPSHOT_REFRESH_PHASES);
    }

    /** Returns the facing of the world-map block at {@code pos}, or UP if absent. */
    private static Direction facingAt(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.hasProperty(WorldMapBlock.FACING) ? st.getValue(WorldMapBlock.FACING) : Direction.UP;
    }

    /** BFS flood-fill over all connected same-facing world-map tiles, applying {@code action} to each. */
    public static void applyToConnected(Level level, BlockPos startPos, Consumer<WorldMapBlockEntity> action) {
        ConnectedGroup group = collectConnectedGroup(level, startPos);
        if (group == null) return;
        for (WorldMapBlockEntity tile : group.tiles().values()) {
            action.accept(tile);
        }
    }

    /** BFS flood-fill to find every connected world-map tile and mark each for immediate refresh. */
    public static void forceRefreshConnected(Level level, BlockPos startPos) {
        applyToConnected(level, startPos, WorldMapBlockEntity::markForImmediateRefresh);
    }

    public static @Nullable ConnectedGroup collectConnectedGroup(Level level, BlockPos startPos) {
        if (level == null || level.isClientSide) return null;
        Direction facing = facingAt(level, startPos);
        PlaneDir[] dirs = planeDirs(facing);
        Map<BlockPos, WorldMapBlockEntity> group = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        visited.add(startPos);
        queue.add(startPos);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (level.getBlockEntity(pos) instanceof WorldMapBlockEntity wme) {
                BlockPos immutablePos = pos.immutable();
                group.put(immutablePos, wme);
                int axisA = tileAxisA(immutablePos, facing);
                int axisB = tileAxisB(immutablePos, facing);
                minA = Math.min(minA, axisA);
                maxA = Math.max(maxA, axisA);
                minB = Math.min(minB, axisB);
                maxB = Math.max(maxB, axisB);
            }
            for (PlaneDir pd : dirs) {
                BlockPos next = pos.relative(pd.blockDir());
                if (!visited.contains(next)) {
                    BlockState ns = level.getBlockState(next);
                    if (ns.is(ZenDiorama.WORLD_MAP.get())
                            && ns.hasProperty(WorldMapBlock.FACING)
                            && ns.getValue(WorldMapBlock.FACING) == facing) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        if (group.isEmpty()) return null;
        return new ConnectedGroup(facing, group, minA, maxA, minB, maxB);
    }

    public static void refreshConnectedLayoutState(Level level, BlockPos startPos) {
        ConnectedGroup group = collectConnectedGroup(level, startPos);
        if (group == null) return;
        WorldMapGroupRules.ValidationResult validation = WorldMapGroupRules.validate(group);
        boolean becameValid = validation.valid()
                && group.tiles().values().stream().anyMatch(tile -> !tile.isLayoutValid());
        for (WorldMapBlockEntity tile : group.tiles().values()) {
            tile.setPresentationLayoutFromGroup(group);
            tile.setLayoutState(validation.valid(), "too_many_tiles".equals(validation.reason()));
        }
        if (becameValid) {
            playAssemblyFlourish(level, group);
        }
    }

    private void setPresentationLayoutFromGroup(ConnectedGroup group) {
        int tileX = tileAxisA(worldPosition, group.facing()) - group.minA();
        int tileZ = tileAxisB(worldPosition, group.facing()) - group.minB();
        int tilesWide = group.width();
        int tilesTall = group.height();
        if (presentationTileX == tileX
                && presentationTileZ == tileZ
                && presentationTilesWide == tilesWide
                && presentationTilesTall == tilesTall) {
            return;
        }
        presentationTileX = tileX;
        presentationTileZ = tileZ;
        presentationTilesWide = tilesWide;
        presentationTilesTall = tilesTall;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private static void playAssemblyFlourish(Level level, ConnectedGroup group) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        Vec3 center = Vec3.ZERO;
        int count = 0;
        for (BlockPos pos : group.tiles().keySet()) {
            center = center.add(effectOrigin(pos, group.facing()));
            count++;
        }
        if (count == 0) return;
        center = center.scale(1.0D / count);

        serverLevel.playSound(null, BlockPos.containing(center),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.55F, 1.35F);
        serverLevel.playSound(null, BlockPos.containing(center),
                SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.35F, 1.1F);

        for (BlockPos pos : group.tiles().keySet()) {
            Vec3 origin = effectOrigin(pos, group.facing());
            serverLevel.sendParticles(ParticleTypes.GLOW,
                    origin.x, origin.y, origin.z,
                    3, 0.18D, 0.05D, 0.18D, 0.01D);
            serverLevel.sendParticles(ParticleTypes.WAX_ON,
                    origin.x, origin.y, origin.z,
                    2, 0.16D, 0.04D, 0.16D, 0.0D);
        }
    }

    private static Vec3 effectOrigin(BlockPos pos, Direction facing) {
        Vec3 center = Vec3.atCenterOf(pos);
        return switch (facing) {
            case UP, DOWN -> center.add(0.0D, -0.34D, 0.0D);
            default -> center.add(
                    facing.getStepX() * 0.34D,
                    0.0D,
                    facing.getStepZ() * 0.34D);
        };
    }

    private void setLayoutState(boolean valid, boolean severeInvalid) {
        if (layoutValid == valid && layoutSevereInvalid == severeInvalid) return;
        layoutValid = valid;
        layoutSevereInvalid = severeInvalid;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void setStoredAnchorPos(BlockPos anchorPos) {
        this.anchorBlockX = anchorPos.getX();
        this.anchorBlockY = anchorPos.getY();
        this.anchorBlockZ = anchorPos.getZ();
    }

    /** Sets zoom fields directly without calling configure() or propagateToNeighbors(). */
    private void setZoomDirect(int cx, int cz, int scale, int zoomIdx, int voxels, BlockPos anchorPos, boolean notifyClient) {
        this.mapCenterX = cx;
        this.mapCenterZ = cz;
        this.blocksPerTile = scale;
        this.zoomIndex = zoomIdx;
        this.samplerResolution = voxels;
        setStoredAnchorPos(anchorPos);
        this.configured = true;
        this.dirty = true;
        this.waitingForChunks = false;
        this.refreshImmediately = false;
        this.lastDirtyTime = level != null ? level.getGameTime() : 0;
        setChanged();
        if (notifyClient && level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Recenters the entire connected group so that world position (worldX, worldZ) falls at the
     *  geometric center of the group's tile-grid bounding box.
     *  Preserves the current scale, zoomIndex, and samplerResolution from the group. */
    public static void recenterGroupOnPoint(Level level, BlockPos startPos, int worldX, int worldZ) {
        ConnectedGroup group = collectConnectedGroup(level, startPos);
        if (group == null) return;

        WorldMapBlockEntity ref = group.tiles().values().stream()
                .filter(WorldMapBlockEntity::hasStoredConfiguration).findFirst().orElse(null);
        if (ref == null) return;
        int zoomIdx = ref.zoomIndex;
        int scale = ref.blocksPerTile;
        int voxels = ref.samplerResolution;
        BlockPos anchorPos = resolveAnchorForGroup(group, ref.getStoredAnchorPos(), startPos);
        if (zoomIdx >= 0 && zoomIdx < WorldMapZoomLevel.LEVELS.length) {
            WorldMapZoomTuning.EffectiveZoom tuned = WorldMapZoomTuning.resolve(
                    WorldMapZoomLevel.LEVELS[zoomIdx], group.width(), group.height());
            scale = tuned.scale();
            voxels = tuned.voxels();
        }

        // Geometric center: 0.5 for even-width groups, integer for odd-width
        float ca = (group.maxA() - group.minA()) / 2.0f;
        float cb = (group.maxB() - group.minB()) / 2.0f;

        for (Map.Entry<BlockPos, WorldMapBlockEntity> entry : group.tiles().entrySet()) {
            BlockPos tPos = entry.getKey();
            WorldMapBlockEntity t = entry.getValue();
            int newCX = worldX + Math.round((tileAxisA(tPos, group.facing()) - group.minA() - ca) * scale);
            int newCZ = worldZ + Math.round((tileAxisB(tPos, group.facing()) - group.minB() - cb) * scale);
            t.setZoomDirect(newCX, newCZ, scale, zoomIdx, voxels, anchorPos, true);
        }
    }

    /** Queues a zoom level for every tile in the connected group while preserving the current sampled center. */
    public static void reZoomConnected(Level level, BlockPos startPos, int newZoomIdx) {
        ConnectedGroup group = collectConnectedGroup(level, startPos);
        if (group == null) return;
        WorldMapZoomLevel zoom = WorldMapZoomLevel.LEVELS[newZoomIdx];
        WorldMapZoomTuning.EffectiveZoom tuned = WorldMapZoomTuning.resolve(zoom, group.width(), group.height());

        // Preserve the current sampled world center of the full mosaic. Using block
        // coordinates here collapses zoomed multi-tile maps back around the placed
        // map blocks instead of the terrain they were showing.
        int newScale  = tuned.scale();
        int newVoxels = tuned.voxels();
        WorldMapBlockEntity ref = group.tiles().values().stream()
                .filter(WorldMapBlockEntity::hasStoredConfiguration).findFirst().orElse(null);
        BlockPos anchorTilePos = resolveAnchorForGroup(group, ref != null ? ref.getStoredAnchorPos() : startPos, startPos);
        double centerX = configuredCenterX(group, ref, anchorTilePos);
        double centerZ = configuredCenterZ(group, ref, anchorTilePos);

        // setZoomDirect sets lastDirtyTime = gameTime; offset it back so the remaining wait
        // is zoomDebounceTicks rather than the full syncDebounceTicks.
        long syncDebounce  = DioramaConfig.SYNC_DEBOUNCE_TICKS.get();
        long zoomDebounce  = DioramaConfig.ZOOM_DEBOUNCE_TICKS.get();
        long timeOffset    = Math.max(0, syncDebounce - zoomDebounce);
        for (Map.Entry<BlockPos, WorldMapBlockEntity> entry : group.tiles().entrySet()) {
            BlockPos tPos = entry.getKey();
            WorldMapBlockEntity t = entry.getValue();
            int newCX = centeredTileMapCoordinate(centerX, tileAxisA(tPos, group.facing()), group.minA(), group.maxA(), newScale);
            int newCZ = centeredTileMapCoordinate(centerZ, tileAxisB(tPos, group.facing()), group.minB(), group.maxB(), newScale);
            t.setZoomDirect(newCX, newCZ, newScale, newZoomIdx, newVoxels, anchorTilePos, false);
            t.lastDirtyTime -= timeOffset;
        }
    }

    static int centeredTileMapCoordinate(double worldCenter, int tileAxis, int minAxis, int maxAxis, int scale) {
        double groupCenterAxis = (maxAxis - minAxis) / 2.0D;
        return (int) Math.round(worldCenter + (tileAxis - minAxis - groupCenterAxis) * scale);
    }

    private static double configuredCenterX(ConnectedGroup group, @Nullable WorldMapBlockEntity fallback, BlockPos fallbackPos) {
        int count = 0;
        double sum = 0.0D;
        for (WorldMapBlockEntity tile : group.tiles().values()) {
            if (tile != null && tile.hasStoredConfiguration()) {
                sum += tile.mapCenterX;
                count++;
            }
        }
        if (count > 0) return sum / count;
        return fallback != null && fallback.hasStoredConfiguration() ? fallback.mapCenterX : fallbackPos.getX();
    }

    private static double configuredCenterZ(ConnectedGroup group, @Nullable WorldMapBlockEntity fallback, BlockPos fallbackPos) {
        int count = 0;
        double sum = 0.0D;
        for (WorldMapBlockEntity tile : group.tiles().values()) {
            if (tile != null && tile.hasStoredConfiguration()) {
                sum += tile.mapCenterZ;
                count++;
            }
        }
        if (count > 0) return sum / count;
        return fallback != null && fallback.hasStoredConfiguration() ? fallback.mapCenterZ : fallbackPos.getZ();
    }

    private static BlockPos resolveAnchorForGroup(ConnectedGroup group, BlockPos preferredAnchor, BlockPos fallback) {
        if (group.tiles().containsKey(preferredAnchor)) {
            return preferredAnchor;
        }
        if (group.tiles().containsKey(fallback)) {
            return fallback;
        }
        BlockPos nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BlockPos pos : group.tiles().keySet()) {
            int distance = Math.abs(pos.getX() - preferredAnchor.getX())
                    + Math.abs(pos.getY() - preferredAnchor.getY())
                    + Math.abs(pos.getZ() - preferredAnchor.getZ());
            if (nearest == null || distance < bestDistance) {
                nearest = pos;
                bestDistance = distance;
            }
        }
        return nearest != null ? nearest : fallback;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Configured", configured);
        tag.putBoolean("LayoutValid", layoutValid);
        tag.putBoolean("LayoutSevereInvalid", layoutSevereInvalid);
        tag.putBoolean("WaitingForChunks", waitingForChunks);
        if (configured) {
            tag.putInt("AnchorBlockX", anchorBlockX);
            tag.putInt("AnchorBlockY", anchorBlockY);
            tag.putInt("AnchorBlockZ", anchorBlockZ);
            tag.putInt("MapCenterX", mapCenterX);
            tag.putInt("MapCenterZ", mapCenterZ);
            tag.putInt("BlocksPerTile", blocksPerTile);
        }
        if (!Float.isNaN(heightExaggeration)) tag.putFloat("HeightExaggeration", heightExaggeration);
        if (!Float.isNaN(elevationTint)) tag.putFloat("ElevationTint", elevationTint);
        tag.putInt("ZoomIndex", zoomIndex);
        tag.putInt("StyleIndex", styleIndex);
        if (samplerResolution > 0) tag.putInt("SamplerResolution", samplerResolution);
        // Snapshot is a rendering cache rebuilt from the overworld each session.
        // Storing it in block entity NBT risks exceeding the 2 MB chunk/packet limit for
        // large map resolutions, and MiniPos packing only handles x,z < 1024 anyway.
        // Always mark dirty so serverTick re-samples after world load.
        tag.putBoolean("Dirty", true);
        tag.putInt("NeighborMask", neighborMask);
        tag.putInt("PresentationTileX", presentationTileX);
        tag.putInt("PresentationTileZ", presentationTileZ);
        tag.putInt("PresentationTilesWide", presentationTilesWide);
        tag.putInt("PresentationTilesTall", presentationTilesTall);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        configured = tag.getBoolean("Configured");
        layoutValid = !tag.contains("LayoutValid") || tag.getBoolean("LayoutValid");
        layoutSevereInvalid = tag.getBoolean("LayoutSevereInvalid");
        waitingForChunks = tag.getBoolean("WaitingForChunks");
        if (configured) {
            anchorBlockX = tag.contains("AnchorBlockX") ? tag.getInt("AnchorBlockX") : worldPosition.getX();
            anchorBlockY = tag.contains("AnchorBlockY") ? tag.getInt("AnchorBlockY") : worldPosition.getY();
            anchorBlockZ = tag.contains("AnchorBlockZ") ? tag.getInt("AnchorBlockZ") : worldPosition.getZ();
            mapCenterX = tag.getInt("MapCenterX");
            mapCenterZ = tag.getInt("MapCenterZ");
            blocksPerTile = tag.getInt("BlocksPerTile");
        }
        heightExaggeration = tag.contains("HeightExaggeration") ? tag.getFloat("HeightExaggeration") : Float.NaN;
        elevationTint = tag.contains("ElevationTint") ? tag.getFloat("ElevationTint") : Float.NaN;
        zoomIndex  = tag.contains("ZoomIndex")  ? tag.getInt("ZoomIndex")  : 0;
        styleIndex = tag.contains("StyleIndex") ? tag.getInt("StyleIndex") : 1;
        samplerResolution = tag.contains("SamplerResolution") ? tag.getInt("SamplerResolution") : 0;
        // When loading from disk, saveAdditional always writes Dirty=true so the server re-samples
        // on restart. When receiving a ClientboundBlockEntityDataPacket (NeoForge routes these
        // through loadAdditional via onDataPacket, not handleUpdateTag), the tag comes from
        // getUpdateTag() which writes the real runtime dirty value. Reading from the tag handles
        // both cases correctly without NeoForge's handleUpdateTag bypass breaking the client state.
        dirty = tag.getBoolean("Dirty");
        // Set lastDirtyTime so gap = gameTime - lastDirtyTime always exceeds the debounce on the
        // first serverTick after load, even on a brand-new world where gameTime is still small.
        // level is null at load time so we can't call level.getGameTime() here.
        lastDirtyTime = -(DioramaConfig.SYNC_DEBOUNCE_TICKS.get() + 1);
        neighborMask = tag.getInt("NeighborMask");
        presentationTileX = tag.getInt("PresentationTileX");
        presentationTileZ = tag.getInt("PresentationTileZ");
        presentationTilesWide = Math.max(1, tag.contains("PresentationTilesWide") ? tag.getInt("PresentationTilesWide") : 1);
        presentationTilesTall = Math.max(1, tag.contains("PresentationTilesTall") ? tag.getInt("PresentationTilesTall") : 1);
        // On the client, a WorldMapSnapshotPayload can arrive before the chunk packet delivers the
        // block entity. DioramaClientPayloadHandler caches such early snapshots; claim it now.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MiniatureSnapshot pending = com.sanhiruzu.zendiorama.client.DioramaClientPayloadHandler
                    .takePendingSnapshot(worldPosition);
            if (pending != null) setSnapshot(pending);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        // saveAdditional always writes Dirty=true so world-load triggers a fresh sample.
        // Override with the real runtime value so the client shows the loading pulse only
        // while a re-sample is actually pending, not after the snapshot has arrived.
        tag.putBoolean("Dirty", dirty);
        return tag;
    }

    // Note: NeoForge routes ClientboundBlockEntityDataPacket through onDataPacket →
    // loadWithComponents → loadAdditional, NOT through handleUpdateTag. The dirty flag is
    // therefore read directly in loadAdditional; no override is needed here.

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
