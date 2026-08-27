package com.sanhiruzu.zendiorama.network;

import com.sanhiruzu.zendiorama.platform.DioramaServices;
import java.lang.reflect.Method;

public final class DioramaClientboundPayloadHandler {
    private static final String CLIENT_HANDLER = "com.sanhiruzu.zendiorama.client.DioramaClientPayloadHandler";

    private DioramaClientboundPayloadHandler() {
    }

    public static void handleSkySnapshot(DioramaSkySnapshotPayload payload) {
        invokeClient("handleSkySnapshot", DioramaSkySnapshotPayload.class, payload);
    }

    public static void handleTransition(DioramaTransitionPayload payload) {
        invokeClient("handleTransition", DioramaTransitionPayload.class, payload);
    }

    public static void handleWorldMapSnapshot(WorldMapSnapshotPayload payload) {
        invokeClient("handleWorldMapSnapshot", WorldMapSnapshotPayload.class, payload);
    }

    private static void invokeClient(String methodName, Class<?> payloadClass, Object payload) {
        if (!DioramaServices.platform().isClientEnvironment()) {
            return;
        }

        try {
            Class<?> handler = Class.forName(CLIENT_HANDLER);
            Method method = handler.getMethod(methodName, payloadClass);
            method.invoke(null, payload);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to dispatch client diorama payload", exception);
        }
    }
}
