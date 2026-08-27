package com.sanhiruzu.zendiorama.platform;

public final class DioramaServices {
    private static DioramaPlatform platform;

    private DioramaServices() {
    }

    public static void initialize(DioramaPlatform platform) {
        DioramaServices.platform = platform;
    }

    public static DioramaPlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("Diorama platform services have not been initialized");
        }
        return platform;
    }
}
