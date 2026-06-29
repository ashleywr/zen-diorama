package com.sanhiruzu.zendiorama.client;

public final class DioramaSkyboxState {
    private DioramaSkyboxState() {
    }

    public static void clear() {
        DioramaSkyboxTextures.clear();
    }

    public static boolean hasSnapshot() {
        return DioramaSkyboxTextures.isReady();
    }
}
