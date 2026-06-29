package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.DioramaConfig;

import java.util.List;

/** Server-side validation for connected world-map group shapes and size limits. */
public final class WorldMapGroupRules {
    private WorldMapGroupRules() {
    }

    public static boolean isValid(WorldMapBlockEntity.ConnectedGroup group) {
        return validate(group).valid();
    }

    public static ValidationResult validate(WorldMapBlockEntity.ConnectedGroup group) {
        int width = group.width();
        int height = group.height();
        int tileCount = group.tileCount();
        if (tileCount > DioramaConfig.mapMaxGroupTiles()) {
            return new ValidationResult(false, "too_many_tiles");
        }
        if (width != height) {
            return new ValidationResult(false, "not_square");
        }
        if (tileCount != width * height) {
            return new ValidationResult(false, "not_full_square");
        }
        List<Integer> allowedSizes = DioramaConfig.allowedMapGroupSizes();
        if (!allowedSizes.contains(width)) {
            return new ValidationResult(false, "size_not_allowed");
        }
        return new ValidationResult(true, "ok");
    }

    public record ValidationResult(boolean valid, String reason) {
    }
}
