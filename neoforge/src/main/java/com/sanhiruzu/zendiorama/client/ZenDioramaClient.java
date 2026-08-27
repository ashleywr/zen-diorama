package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.client.DioramaClientPayloadHandler;
import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

@Mod(value = ZenDiorama.MOD_ID, dist = Dist.CLIENT)
public class ZenDioramaClient {
    public ZenDioramaClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerSkyEffects);
        NeoForge.EVENT_BUS.addListener(this::onComputeFov);
        NeoForge.EVENT_BUS.addListener(this::onRenderHud);
        NeoForge.EVENT_BUS.addListener(this::onMouseClick);
        NeoForge.EVENT_BUS.addListener(this::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(this::onInteraction);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onDisconnect);
        NeoForge.EVENT_BUS.addListener(DioramaSoundMuffle::onPlaySound);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onComputeFov);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onRenderHand);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onGuiPre);
        NeoForge.EVENT_BUS.addListener(DioramaOffscreenCubemap::onClientTick);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ZenDiorama.DIORAMA_FRAME_ENTITY.get(), DioramaFrameRenderer::new);
        event.registerBlockEntityRenderer(ZenDiorama.WORLD_MAP_ENTITY.get(), WorldMapRenderer::new);
    }

    private void registerSkyEffects(RegisterDimensionSpecialEffectsEvent event) {
        DioramaSkyEffects.register(event);
    }

    private void onRenderHud(RenderGuiEvent.Post event) {
        DioramaHudOverlay.render(event.getGuiGraphics());
    }

    private void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (DioramaHudOverlay.shouldCancelInput()) {
            event.setCanceled(true);
        }
    }

    private void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (DioramaHudOverlay.shouldCancelInput()) {
            event.setCanceled(true);
        }
    }

    private void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (DioramaHudOverlay.shouldCancelInput()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    private void onClientTick(ClientTickEvent.Pre event) {
        DioramaHudOverlay.suppressMovement();
    }

    private void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        DioramaClientPayloadHandler.clearPendingSnapshots();
    }

    private void onComputeFov(ComputeFovModifierEvent event) {
        ResourceKey<Level> levelDimension = event.getPlayer().level().dimension();
        if (DioramaDimensions.DIORAMA_LEVEL.equals(levelDimension)) {
            event.setNewFovModifier(event.getNewFovModifier() * 0.82F);
        }
    }
}
