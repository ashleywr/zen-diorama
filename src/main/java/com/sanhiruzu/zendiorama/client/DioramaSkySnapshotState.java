package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import net.minecraft.world.phys.Vec3;

public final class DioramaSkySnapshotState {
    private static DioramaSkySnapshotPayload CURRENT;

    private DioramaSkySnapshotState() {
    }

    public static void update(DioramaSkySnapshotPayload payload) {
        CURRENT = payload;
    }

    public static boolean hasSnapshot() {
        return CURRENT != null;
    }

    public static Vec3 sourceSkyColor() {
        if (CURRENT == null) {
            return new Vec3(0.58D, 0.72D, 1.00D);
        }

        return averageSkyColor();
    }

    public static Vec3 sourceSkyColor(float x, float y, float z) {
        if (CURRENT == null) {
            return sourceSkyColor();
        }

        double absX = Math.abs(x);
        double absY = Math.abs(y);
        double absZ = Math.abs(z);
        double total = absX + absY + absZ;
        if (total < 1.0E-4) {
            return sourceSkyColor();
        }

        Vec3 south = faceToVec(CURRENT.skyColorSouth());
        Vec3 north = faceToVec(CURRENT.skyColorNorth());
        Vec3 east = faceToVec(CURRENT.skyColorEast());
        Vec3 west = faceToVec(CURRENT.skyColorWest());
        Vec3 up = faceToVec(CURRENT.skyColorUp());
        Vec3 down = faceToVec(CURRENT.skyColorDown());

        Vec3 result = Vec3.ZERO;
        if (x >= 0.0F) {
            result = result.add(east.scale(absX));
        } else {
            result = result.add(west.scale(absX));
        }
        if (y >= 0.0F) {
            result = result.add(up.scale(absY));
        } else {
            result = result.add(down.scale(absY));
        }
        if (z >= 0.0F) {
            result = result.add(south.scale(absZ));
        } else {
            result = result.add(north.scale(absZ));
        }

        return result.scale(1.0D / total);
    }

    private static Vec3 averageSkyColor() {
        int color = CURRENT.skyColor();
        double red = (double) ((color >> 16) & 0xFF) / 255.0D;
        double green = (double) ((color >> 8) & 0xFF) / 255.0D;
        double blue = (double) (color & 0xFF) / 255.0D;
        return new Vec3(red, green, blue);
    }

    public static float sourceDayOfTime() {
        if (CURRENT == null) {
            return 0.5F;
        }

        return (float) (CURRENT.dayTime() % 24000L) / 24000.0F;
    }

    public static float rainLevel() {
        return CURRENT == null ? 0.0F : CURRENT.rainLevel();
    }

    public static float thunderLevel() {
        return CURRENT == null ? 0.0F : CURRENT.thunderLevel();
    }

    private static Vec3 faceToVec(int color) {
        double red = (double) ((color >> 16) & 0xFF) / 255.0D;
        double green = (double) ((color >> 8) & 0xFF) / 255.0D;
        double blue = (double) (color & 0xFF) / 255.0D;
        return new Vec3(red, green, blue);
    }

    public static void clear() {
        CURRENT = null;
    }
}
