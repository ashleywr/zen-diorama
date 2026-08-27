package com.sanhiruzu.zendiorama.fabric;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.client.DioramaClientPayloadHandler;
import com.sanhiruzu.zendiorama.client.DioramaFrameRenderer;
import com.sanhiruzu.zendiorama.client.DioramaHudOverlay;
import com.sanhiruzu.zendiorama.client.WorldMapRenderer;
import com.sanhiruzu.zendiorama.network.DioramaClientboundPayloadHandler;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;

public final class ZenDioramaFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(ZenDiorama.DIORAMA_FRAME_ENTITY.get(), DioramaFrameRenderer::new);
        BlockEntityRenderers.register(ZenDiorama.WORLD_MAP_ENTITY.get(), WorldMapRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(DioramaSkySnapshotPayload.TYPE,
                (payload, context) -> DioramaClientboundPayloadHandler.handleSkySnapshot(payload));
        ClientPlayNetworking.registerGlobalReceiver(DioramaTransitionPayload.TYPE,
                (payload, context) -> DioramaClientboundPayloadHandler.handleTransition(payload));
        ClientPlayNetworking.registerGlobalReceiver(WorldMapSnapshotPayload.TYPE,
                (payload, context) -> DioramaClientboundPayloadHandler.handleWorldMapSnapshot(payload));

        ClientTickEvents.START_CLIENT_TICK.register(client -> DioramaHudOverlay.suppressMovement());
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> DioramaHudOverlay.render(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> DioramaClientPayloadHandler.clearPendingSnapshots());

        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> DioramaHudOverlay.shouldCancelInput());
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                DioramaHudOverlay.shouldCancelInput() ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                DioramaHudOverlay.shouldCancelInput() ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                DioramaHudOverlay.shouldCancelInput()
                        ? net.minecraft.world.InteractionResultHolder.fail(player.getItemInHand(hand))
                        : net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand)));
    }
}
