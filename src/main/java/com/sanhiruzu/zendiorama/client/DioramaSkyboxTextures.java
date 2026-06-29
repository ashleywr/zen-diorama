package com.sanhiruzu.zendiorama.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public final class DioramaSkyboxTextures {
    // 0=north, 1=south, 2=east, 3=west, 4=up, 5=down
    private static final ResourceLocation[] LOCATIONS = {
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_north"),
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_south"),
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_east"),
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_west"),
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_up"),
        ResourceLocation.fromNamespaceAndPath("zen_diorama", "dynamic/skybox_down"),
    };

    private static final DynamicTexture[] TEXTURES = new DynamicTexture[6];
    private static boolean ready = false;

    private DioramaSkyboxTextures() {
    }

    public static void upload(int face, NativeImage image) {
        if (TEXTURES[face] != null) {
            TEXTURES[face].setPixels(image);
            TEXTURES[face].upload();
        } else {
            TEXTURES[face] = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(LOCATIONS[face], TEXTURES[face]);
        }

        ready = true;
        for (DynamicTexture t : TEXTURES) {
            if (t == null) {
                ready = false;
                break;
            }
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static ResourceLocation location(int face) {
        return LOCATIONS[face];
    }

    public static void clear() {
        ready = false;
        for (int i = 0; i < 6; i++) {
            if (TEXTURES[i] != null) {
                TEXTURES[i].close();
                TEXTURES[i] = null;
            }
        }
    }
}
