package com.sanhiruzu.zendiorama;

import com.sanhiruzu.zendiorama.block.DioramaFrameBlock;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlockEntity;
import com.sanhiruzu.zendiorama.block.DioramaFrameItem;
import com.sanhiruzu.zendiorama.block.DioramaExitBlock;
import com.sanhiruzu.zendiorama.block.DioramaControlBlock;
import com.sanhiruzu.zendiorama.block.DioramaEdgeBlock;
import com.sanhiruzu.zendiorama.block.WorldMapBlock;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
import com.sanhiruzu.zendiorama.network.DioramaClientboundPayloadHandler;
import com.sanhiruzu.zendiorama.network.DioramaSkySnapshotPayload;
import com.sanhiruzu.zendiorama.network.DioramaTransitionPayload;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import com.sanhiruzu.zendiorama.command.WorldMapCommand;
import com.sanhiruzu.zendiorama.server.DioramaPendingTeleports;
import com.sanhiruzu.zendiorama.server.DioramaPlayerVisibilityHandler;

@Mod(ZenDiorama.MOD_ID)
public class ZenDiorama {
    public static final String MOD_ID = "zen_diorama";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredBlock<Block> DIORAMA_FRAME = BLOCKS.register(
            "diorama_frame",
            () -> new DioramaFrameBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .noOcclusion()));

    public static final DeferredBlock<Block> DIORAMA_EXIT = BLOCKS.register(
            "diorama_exit",
            () -> new DioramaExitBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 10)
                    .noCollission()
                    .noOcclusion()));

    public static final DeferredBlock<Block> DIORAMA_CONTROL = BLOCKS.register(
            "diorama_control",
            () -> new DioramaControlBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 6)
                    .noOcclusion()));

    public static final DeferredBlock<Block> DIORAMA_EDGE = BLOCKS.register(
            "diorama_edge",
            () -> new DioramaEdgeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(-1.0F, 3600000.0F)
                    .noOcclusion()));

    public static final DeferredBlock<Block> WORLD_MAP = BLOCKS.register(
            "world_map",
            () -> new WorldMapBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(1.5F)
                    .noCollission()));

    public static final DeferredItem<BlockItem> DIORAMA_FRAME_ITEM =
            ITEMS.register("diorama_frame", () -> new DioramaFrameItem(DIORAMA_FRAME.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> WORLD_MAP_ITEM =
            ITEMS.register("world_map", () -> new BlockItem(WORLD_MAP.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DioramaFrameBlockEntity>> DIORAMA_FRAME_ENTITY =
            BLOCK_ENTITY_TYPES.register("diorama_frame", () -> BlockEntityType.Builder
                    .of(DioramaFrameBlockEntity::new, DIORAMA_FRAME.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WorldMapBlockEntity>> WORLD_MAP_ENTITY =
            BLOCK_ENTITY_TYPES.register("world_map", () -> BlockEntityType.Builder
                    .of(WorldMapBlockEntity::new, WORLD_MAP.get())
                    .build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.zen_diorama"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> DIORAMA_FRAME_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(DIORAMA_FRAME_ITEM.get());
                        output.accept(WORLD_MAP_ITEM.get());
                    })
                    .build());

    public ZenDiorama(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, DioramaConfig.SPEC);
        modEventBus.addListener(this::registerNetworkPayloads);

        NeoForge.EVENT_BUS.register(DioramaPlayerVisibilityHandler.class);
        NeoForge.EVENT_BUS.addListener(DioramaPendingTeleports::onServerTick);
        NeoForge.EVENT_BUS.addListener(WorldMapCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ZenDiorama::onChunkWatch);
    }

    private static void onChunkWatch(net.neoforged.neoforge.event.level.ChunkWatchEvent.Watch event) {
        net.minecraft.server.level.ServerLevel level = event.getLevel();
        net.minecraft.world.level.chunk.LevelChunk chunk =
                level.getChunk(event.getPos().x, event.getPos().z);
        chunk.getBlockEntities().values().forEach(be -> {
            if (!(be instanceof WorldMapBlockEntity wme)) return;
            if (wme.isDirty() || wme.getSnapshot().entries().isEmpty()) return;
            // Chunk NBT (saveAdditional) always stores Dirty=true so the server re-samples on
            // restart. Override it for joining players who arrive after sampling has finished.
            net.minecraft.network.protocol.Packet<?> pkt = wme.getUpdatePacket();
            if (pkt != null) event.getPlayer().connection.send(pkt);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    event.getPlayer(),
                    new WorldMapSnapshotPayload(wme.getBlockPos(), wme.getSnapshot()));
        });
    }

    private void registerNetworkPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("3").playToClient(
                DioramaSkySnapshotPayload.TYPE,
                DioramaSkySnapshotPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleSkySnapshot(payload));
        event.registrar("3").playToClient(
                DioramaTransitionPayload.TYPE,
                DioramaTransitionPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleTransition(payload));
        event.registrar("3").playToClient(
                WorldMapSnapshotPayload.TYPE,
                WorldMapSnapshotPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleWorldMapSnapshot(payload));
        event.registrar("3").playToServer(
                DioramaCaptureReadyPayload.TYPE,
                DioramaCaptureReadyPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        context.enqueueWork(() ->
                                com.sanhiruzu.zendiorama.server.DioramaPendingTeleports.completeCapture(serverPlayer));
                    }
                });
    }
}
