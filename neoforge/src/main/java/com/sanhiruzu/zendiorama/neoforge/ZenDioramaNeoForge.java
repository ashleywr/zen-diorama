package com.sanhiruzu.zendiorama.neoforge;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.command.WorldMapCommand;
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
import com.sanhiruzu.zendiorama.network.DioramaClientboundPayloadHandler;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotRequestPayload;
import com.sanhiruzu.zendiorama.server.DioramaPendingTeleports;
import com.sanhiruzu.zendiorama.server.DioramaPlayerVisibilityHandler;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(ZenDiorama.MOD_ID)
public final class ZenDioramaNeoForge {
    public ZenDioramaNeoForge(IEventBus modEventBus) {
        ZenDiorama.init(new NeoForgeDioramaPlatform());

        // NeoForge constructs mods AFTER the vanilla registries freeze, so content
        // must NOT be registered here - doing so throws "Registry is already frozen"
        // from Block.<init> and kills a dedicated server during mod loading.
        // Each registry is reopened for its own RegisterEvent phase, and those phases
        // fire in registry order (blocks, then items, then block entities), which
        // satisfies the ordering items and block entities depend on.
        modEventBus.addListener(this::onRegisterContent);

        modEventBus.addListener(this::registerNetworkPayloads);

        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerDimensionChanged);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onSpawnPlacementCheck);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(this::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // BuildCreativeModeTabContentsEvent is an IModBusEvent - it must go on the
        // mod bus, not the common NeoForge (game) bus, which throws
        // "IModBusEvent events are not allowed on the common NeoForge bus".
        modEventBus.addListener(this::onBuildCreativeTabContents);
    }

    private void onRegisterContent(RegisterEvent event) {
        ResourceKey<? extends Registry<?>> key = event.getRegistryKey();
        if (key.equals(Registries.BLOCK)) {
            ZenDiorama.registerBlocks();
        } else if (key.equals(Registries.ITEM)) {
            ZenDiorama.registerItems();
        } else if (key.equals(Registries.BLOCK_ENTITY_TYPE)) {
            ZenDiorama.registerBlockEntities();
        }
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DioramaPlayerVisibilityHandler.onPlayerTick(player);
        }
    }

    private void onPlayerDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DioramaPlayerVisibilityHandler.onPlayerDimensionChanged(player, event.getFrom(), event.getTo());
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DioramaPlayerVisibilityHandler.onPlayerLoggedOut(player);
        }
    }

    private void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!DioramaPlayerVisibilityHandler.allowSpawn((ServerLevel) event.getLevel().getLevel(), event.getSpawnType())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    private void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && !DioramaPlayerVisibilityHandler.allowBlockPlace(player, event.getPos(), event.getPlacedBlock().getBlock())) {
            event.setCanceled(true);
        }
    }

    private void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && !DioramaPlayerVisibilityHandler.allowBlockBreak(player, event.getPos(), event.getState())) {
            event.setCanceled(true);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        DioramaPendingTeleports.onServerTick();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        WorldMapCommand.register(event.getDispatcher());
    }

    private void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ZenDiorama.DIORAMA_FRAME_ITEM.get());
            event.accept(ZenDiorama.WORLD_MAP_ITEM.get());
            event.accept(ZenDiorama.SURVEY_PIN_ITEM.get());
        }
    }

    private void registerNetworkPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("4").playToClient(
                DioramaSkySnapshotPayload.TYPE,
                DioramaSkySnapshotPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleSkySnapshot(payload));
        event.registrar("4").playToClient(
                DioramaTransitionPayload.TYPE,
                DioramaTransitionPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleTransition(payload));
        event.registrar("4").playToClient(
                WorldMapSnapshotPayload.TYPE,
                WorldMapSnapshotPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleWorldMapSnapshot(payload));
        event.registrar("4").playToServer(
                DioramaCaptureReadyPayload.TYPE,
                DioramaCaptureReadyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> DioramaPendingTeleports.completeCapture((ServerPlayer) context.player())));
        event.registrar("4").playToServer(
                WorldMapSnapshotRequestPayload.TYPE,
                WorldMapSnapshotRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getBlockEntity(payload.pos()) instanceof WorldMapBlockEntity worldMap) {
                        ZenDiorama.sendWorldMapSnapshot(player, worldMap);
                    }
                }));
    }
}
