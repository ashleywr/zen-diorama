package com.sanhiruzu.zendiorama.world;

import com.sanhiruzu.zendiorama.ZenDiorama;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class DioramaDimensions {
    public static final ResourceKey<Level> DIORAMA_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "diorama"));

    private DioramaDimensions() {
    }
}
