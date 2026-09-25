package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import com.sanhiruzu.zendiorama.platform.DioramaServices;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DioramaClientPayloadHandler {
    private DioramaClientPayloadHandler() {
    }

    // Snapshots that arrived before the block entity existed on the client (chunk packet race).
    // Cleared when the level changes (handleLevelChange) to avoid stale entries across sessions.
    private static final Map<BlockPos, WorldMapSnapshotPayload> PENDING_SNAPSHOTS = new ConcurrentHashMap<>();

    /** Called by WorldMapBlockEntity.loadAdditional on the client to pick up any snapshot that
     *  arrived before the block entity was ready. */
    public static WorldMapSnapshotPayload takePendingSnapshot(BlockPos pos) {
        return PENDING_SNAPSHOTS.remove(pos);
    }

    /** Drop all pending snapshots when the client disconnects or changes worlds. */
    public static void clearPendingSnapshots() {
        PENDING_SNAPSHOTS.clear();
    }

    public static void handleWorldMapSnapshot(WorldMapSnapshotPayload payload) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(payload.pos()) instanceof WorldMapBlockEntity wme) {
            wme.setSnapshot(payload.snapshot(), payload.surveyPins());
        } else {
            // Block entity not loaded yet — cache until loadAdditional fires on the client.
            PENDING_SNAPSHOTS.put(payload.pos(), payload);
        }
    }

    public static void handleSkySnapshot(DioramaSkySnapshotPayload payload) {
        DioramaSkySnapshotState.update(payload);
    }

    public static void handleTransition(DioramaTransitionPayload payload) {
        BlockPos p = payload.framePos();
        if (payload.entering() && p != null) {
            // Capture the 6 faces over the next frames via the normal pipeline (camera at block center),
            // then ack the server so it teleports us in. The capture frames are hidden by the overlay.
            DioramaSkyboxRenderer.resetDiagnostic();
            DioramaServices.platform().beginEntryCubemapCapture(
                    p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D,
                    () -> DioramaServices.platform().sendToServer(new DioramaCaptureReadyPayload()));
            DioramaHudOverlay.beginTransition(true);
        } else {
            DioramaHudOverlay.beginTransition(payload.entering());
        }
    }
}
