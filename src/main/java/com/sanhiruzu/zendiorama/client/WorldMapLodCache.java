package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshotLod;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldMapLodCache implements AutoCloseable {
    private static final int[] LOD_FACTORS = {1, 2, 4};
    private static final double MEDIUM_LOD_DISTANCE_SQ = 12.0 * 12.0;
    private static final double MEDIUM_LOD_BLEND_END_SQ = 16.0 * 16.0;
    private static final double FAR_LOD_DISTANCE_SQ = 24.0 * 24.0;
    private static final double FAR_LOD_BLEND_END_SQ = 32.0 * 32.0;
    private static final AtomicInteger WORKER_ID = new AtomicInteger();
    private static final ExecutorService MESH_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2)),
            task -> {
                Thread thread = new Thread(task, "ZenDiorama WorldMap Mesh " + WORKER_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private final MiniatureSnapshot snapshot;
    private final int flags;
    private final float heightExaggeration;
    private final float elevationTint;
    private final int neighborMask;
    private final WorldMapGeometry.PresentationLayout presentationLayout;
    private final int sampledGridSize;
    private final WorldMapGeometry[] geometries = new WorldMapGeometry[LOD_FACTORS.length];
    private final MiniatureSnapshot[] lodSnapshots = new MiniatureSnapshot[LOD_FACTORS.length];
    @SuppressWarnings("unchecked")
    private final CompletableFuture<WorldMapGeometry.PreparedMesh>[] pendingMeshes =
            new CompletableFuture[LOD_FACTORS.length];
    private boolean closed;

    public WorldMapLodCache(
            MiniatureSnapshot snapshot,
            int flags,
            float heightExaggeration,
            float elevationTint,
            int neighborMask,
            WorldMapGeometry.PresentationLayout presentationLayout,
            int sampledGridSize) {
        this.snapshot = snapshot;
        this.flags = flags;
        this.heightExaggeration = heightExaggeration;
        this.elevationTint = elevationTint;
        this.neighborMask = neighborMask;
        this.presentationLayout = presentationLayout;
        this.sampledGridSize = Math.max(1, sampledGridSize);
    }

    public boolean matches(MiniatureSnapshot snapshot, int flags) {
        return this.snapshot == snapshot && this.flags == flags;
    }

    public WorldMapGeometry geometryForDistance(double distanceSq) {
        return geometryForIndex(selectLodIndex(distanceSq));
    }

    public LodSelection selectionForDistance(double distanceSq) {
        if (distanceSq >= FAR_LOD_BLEND_END_SQ) {
            return new LodSelection(2, -1, 0.0f);
        }
        if (distanceSq >= FAR_LOD_DISTANCE_SQ) {
            return new LodSelection(1, 2, blend(distanceSq, FAR_LOD_DISTANCE_SQ, FAR_LOD_BLEND_END_SQ));
        }
        if (distanceSq >= MEDIUM_LOD_BLEND_END_SQ) {
            return new LodSelection(1, -1, 0.0f);
        }
        if (distanceSq >= MEDIUM_LOD_DISTANCE_SQ) {
            return new LodSelection(0, 1, blend(distanceSq, MEDIUM_LOD_DISTANCE_SQ, MEDIUM_LOD_BLEND_END_SQ));
        }
        return new LodSelection(0, -1, 0.0f);
    }

    public WorldMapGeometry geometryForIndex(int index) {
        WorldMapGeometry geometry = geometries[index];
        if (geometry != null) {
            return geometry;
        }

        MiniatureSnapshot lodSnapshot = lodSnapshots[index];
        if (lodSnapshot == null) {
            lodSnapshot = MiniatureSnapshotLod.build(snapshot, LOD_FACTORS[index]);
            lodSnapshots[index] = lodSnapshot;
        }

        geometry = WorldMapGeometry.bake(
                lodSnapshot,
                flags + index * 131071,
                lodSnapshot,
                lodSampledGridSize(index),
                heightExaggeration,
                elevationTint,
                neighborMask,
                presentationLayout);
        geometries[index] = geometry;
        return geometry;
    }

    public void requestGeometry(int index) {
        if (closed || geometries[index] != null || pendingMeshes[index] != null) {
            return;
        }
        pendingMeshes[index] = CompletableFuture
                .supplyAsync(() -> prepareGeometry(index), MESH_EXECUTOR)
                .whenComplete((prepared, error) -> {
                    if (error != null && !(error instanceof CancellationException)) {
                        ZenDiorama.LOGGER.warn("[zen_diorama] Failed to prepare world map LOD geometry", error);
                    }
                    if (closed && prepared != null) {
                        prepared.close();
                    }
                });
    }

    public WorldMapGeometry uploadPreparedIfReady(int index) {
        CompletableFuture<WorldMapGeometry.PreparedMesh> pending = pendingMeshes[index];
        if (pending == null || !pending.isDone()) {
            return null;
        }
        pendingMeshes[index] = null;
        WorldMapGeometry.PreparedMesh prepared;
        try {
            prepared = pending.getNow(null);
        } catch (CancellationException | CompletionException e) {
            return null;
        }
        if (prepared == null || closed) {
            if (prepared != null) {
                prepared.close();
            }
            return null;
        }
        try {
            WorldMapGeometry geometry = prepared.upload();
            geometries[index] = geometry;
            return geometry;
        } catch (RuntimeException e) {
            ZenDiorama.LOGGER.warn("[zen_diorama] Failed to upload world map LOD geometry", e);
            return null;
        }
    }

    public boolean hasPreparedGeometry(int index) {
        CompletableFuture<WorldMapGeometry.PreparedMesh> pending = pendingMeshes[index];
        return pending != null && pending.isDone();
    }

    public WorldMapGeometry geometryIfReady(int index) {
        return geometries[index];
    }

    public WorldMapGeometry coarsestReadyGeometry(int targetIndex) {
        for (int index = LOD_FACTORS.length - 1; index >= targetIndex; index--) {
            WorldMapGeometry geometry = geometries[index];
            if (geometry != null) {
                return geometry;
            }
        }
        return null;
    }

    public int nextMissingCoarseFirst(int targetIndex) {
        for (int index = LOD_FACTORS.length - 1; index >= targetIndex; index--) {
            if (geometries[index] == null) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        closed = true;
        for (CompletableFuture<WorldMapGeometry.PreparedMesh> pending : pendingMeshes) {
            if (pending != null) {
                try {
                    WorldMapGeometry.PreparedMesh prepared = pending.getNow(null);
                    if (prepared != null) {
                        prepared.close();
                    }
                } catch (CancellationException | CompletionException ignored) {
                    // Nothing to clean up: failed tasks did not produce a mesh.
                }
            }
        }
        for (WorldMapGeometry geometry : geometries) {
            if (geometry != null) {
                geometry.close();
            }
        }
    }

    private static int selectLodIndex(double distanceSq) {
        if (distanceSq >= FAR_LOD_BLEND_END_SQ) {
            return 2;
        }
        if (distanceSq >= MEDIUM_LOD_BLEND_END_SQ) {
            return 1;
        }
        return 0;
    }

    private static float blend(double value, double start, double end) {
        if (end <= start) {
            return 1.0f;
        }
        return (float)Math.clamp((value - start) / (end - start), 0.0d, 1.0d);
    }

    public record LodSelection(int baseIndex, int overlayIndex, float overlayAlpha) {
    }

    private int lodSampledGridSize(int index) {
        int factor = LOD_FACTORS[index];
        return (sampledGridSize + factor - 1) / factor;
    }

    private WorldMapGeometry.PreparedMesh prepareGeometry(int index) {
        MiniatureSnapshot lodSnapshot = MiniatureSnapshotLod.build(snapshot, LOD_FACTORS[index]);

        return WorldMapGeometry.prepare(
                lodSnapshot,
                flags + index * 131071,
                lodSnapshot,
                lodSampledGridSize(index),
                heightExaggeration,
                elevationTint,
                neighborMask,
                presentationLayout);
    }
}
