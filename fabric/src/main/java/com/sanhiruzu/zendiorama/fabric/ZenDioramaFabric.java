package com.sanhiruzu.zendiorama.fabric;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.command.WorldMapCommand;
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotRequestPayload;
import com.sanhiruzu.zendiorama.server.DioramaPendingTeleports;
import com.sanhiruzu.zendiorama.server.DioramaPlayerVisibilityHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.state.BlockState;

public final class ZenDioramaFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ZenDiorama.init(new FabricDioramaPlatform());

        // Fabric initialises mods while every registry is still open, so all three
        // groups register here in one pass. registerAll() enforces the required
        // order: blocks, then items, then block entities.
        ZenDiorama.registerAll();

        registerPayloads();
        registerServerEvents();
    }

    private void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(DioramaSkySnapshotPayload.TYPE, DioramaSkySnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DioramaTransitionPayload.TYPE, DioramaTransitionPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(WorldMapSnapshotPayload.TYPE, WorldMapSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DioramaCaptureReadyPayload.TYPE, DioramaCaptureReadyPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(WorldMapSnapshotRequestPayload.TYPE, WorldMapSnapshotRequestPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(DioramaCaptureReadyPayload.TYPE,
                (payload, context) -> context.server().execute(() ->
                        DioramaPendingTeleports.completeCapture(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(WorldMapSnapshotRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().level().getBlockEntity(payload.pos()) instanceof WorldMapBlockEntity worldMap) {
                        ZenDiorama.sendWorldMapSnapshot(context.player(), worldMap);
                    }
                }));
    }

    private void registerServerEvents() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WorldMapCommand.register(dispatcher));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            entries.accept(ZenDiorama.DIORAMA_FRAME_ITEM.get());
            entries.accept(ZenDiorama.WORLD_MAP_ITEM.get());
            entries.accept(ZenDiorama.SURVEY_PIN_ITEM.get());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DioramaPendingTeleports.onServerTick();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                DioramaPlayerVisibilityHandler.onPlayerTick(player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                DioramaPlayerVisibilityHandler.onPlayerLoggedOut(handler.player));

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer serverPlayer)
                        || DioramaPlayerVisibilityHandler.allowBlockBreak(serverPlayer, pos, state));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem blockItem)) {
                return InteractionResult.PASS;
            }

            BlockPlaceContext context = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hitResult);
            BlockPos placePos = context.canPlace() ? context.getClickedPos() : hitResult.getBlockPos();
            BlockState clickedState = world.getBlockState(hitResult.getBlockPos());
            if (!clickedState.canBeReplaced(context)) {
                placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
            }

            return DioramaPlayerVisibilityHandler.allowBlockPlace(serverPlayer, placePos, blockItem.getBlock())
                    ? InteractionResult.PASS
                    : InteractionResult.FAIL;
        });
    }
}
