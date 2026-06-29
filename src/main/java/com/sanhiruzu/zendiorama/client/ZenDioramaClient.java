package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

@Mod(value = ZenDiorama.MOD_ID, dist = Dist.CLIENT)
public class ZenDioramaClient {
    public ZenDioramaClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerSkyEffects);
        NeoForge.EVENT_BUS.addListener(this::onComputeFov);
        NeoForge.EVENT_BUS.addListener(DioramaHudOverlay::render);
        NeoForge.EVENT_BUS.addListener(DioramaHudOverlay::suppressMouse);
        NeoForge.EVENT_BUS.addListener(DioramaHudOverlay::suppressScroll);
        NeoForge.EVENT_BUS.addListener(DioramaHudOverlay::suppressInteraction);
        NeoForge.EVENT_BUS.addListener(DioramaHudOverlay::suppressMovement);
        NeoForge.EVENT_BUS.addListener(DioramaSoundMuffle::onPlaySound);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onComputeFov);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onRenderHand);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onGuiPre);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ZenDiorama.DIORAMA_FRAME_ENTITY.get(), DioramaFrameRenderer::new);
        event.registerBlockEntityRenderer(ZenDiorama.WORLD_MAP_ENTITY.get(), WorldMapRenderer::new);
    }

    private void registerSkyEffects(RegisterDimensionSpecialEffectsEvent event) {
        DioramaSkyEffects.register(event);
    }

    private void onComputeFov(ComputeFovModifierEvent event) {
        ResourceKey<Level> levelDimension = event.getPlayer().level().dimension();
        if (DioramaDimensions.DIORAMA_LEVEL.equals(levelDimension)) {
            event.setNewFovModifier(event.getNewFovModifier() * 0.82F);
        }
    }
}
