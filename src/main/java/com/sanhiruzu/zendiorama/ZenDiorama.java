package com.sanhiruzu.zendiorama;

import com.mojang.logging.LogUtils;
import com.sanhiruzu.zendiorama.block.DioramaControlBlock;
import com.sanhiruzu.zendiorama.block.DioramaEdgeBlock;
import com.sanhiruzu.zendiorama.block.DioramaExitBlock;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlock;
import com.sanhiruzu.zendiorama.block.DioramaFrameBlockEntity;
import com.sanhiruzu.zendiorama.block.DioramaFrameItem;
import com.sanhiruzu.zendiorama.block.WorldMapBlock;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.network.WorldMapSnapshotPayload;
import com.sanhiruzu.zendiorama.platform.DioramaPlatform;
import com.sanhiruzu.zendiorama.platform.DioramaServices;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.Supplier;
import java.util.List;

public final class ZenDiorama {
    public static final String MOD_ID = "zen_diorama";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Registration is DEFERRED, not performed in this class initializer.
    //
    // Constructing a Block calls MappedRegistry.createIntrusiveHolder, which throws
    // "Registry is already frozen" if it runs after registries close. Fabric happens
    // to initialise mods while registries are open, so eager construction worked
    // there - but NeoForge constructs mods after the freeze, so a dedicated server
    // died with ExceptionInInitializerError before this was made lazy.
    //
    // Each loader entrypoint drives registration at the point its registries are
    // open: Fabric calls registerAll() during onInitialize; NeoForge calls the
    // per-registry methods from RegisterEvent. Order matters - items reference
    // blocks, and block entities reference blocks - and both loaders honour it.
    private static final List<Runnable> BLOCK_REGISTRATIONS = new ArrayList<>();
    private static final List<Runnable> ITEM_REGISTRATIONS = new ArrayList<>();
    private static final List<Runnable> BLOCK_ENTITY_REGISTRATIONS = new ArrayList<>();

    public static final RegistryHandle<Block> DIORAMA_FRAME = registerBlock(
            "diorama_frame",
            () -> new DioramaFrameBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .noOcclusion()));

    public static final RegistryHandle<Block> DIORAMA_EXIT = registerBlock(
            "diorama_exit",
            () -> new DioramaExitBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 10)
                    .noCollission()
                    .noOcclusion()));

    public static final RegistryHandle<Block> DIORAMA_CONTROL = registerBlock(
            "diorama_control",
            () -> new DioramaControlBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 6)
                    .noOcclusion()));

    public static final RegistryHandle<Block> DIORAMA_EDGE = registerBlock(
            "diorama_edge",
            () -> new DioramaEdgeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(-1.0F, 3600000.0F)
                    .noOcclusion()));

    public static final RegistryHandle<Block> WORLD_MAP = registerBlock(
            "world_map",
            () -> new WorldMapBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(1.5F)
                    .noCollission()));

    public static final RegistryHandle<BlockItem> DIORAMA_FRAME_ITEM = registerItem(
            "diorama_frame",
            () -> new DioramaFrameItem(DIORAMA_FRAME.get(), new Item.Properties()));

    public static final RegistryHandle<BlockItem> WORLD_MAP_ITEM = registerItem(
            "world_map",
            () -> new BlockItem(WORLD_MAP.get(), new Item.Properties()));

    public static final RegistryHandle<BlockEntityType<DioramaFrameBlockEntity>> DIORAMA_FRAME_ENTITY =
            registerBlockEntity(
                    "diorama_frame",
                    () -> new BlockEntityType<>(DioramaFrameBlockEntity::new, Set.of(DIORAMA_FRAME.get()), null));

    public static final RegistryHandle<BlockEntityType<WorldMapBlockEntity>> WORLD_MAP_ENTITY =
            registerBlockEntity(
                    "world_map",
                    () -> new BlockEntityType<>(WorldMapBlockEntity::new, Set.of(WORLD_MAP.get()), null));

    private static boolean initialized;

    private ZenDiorama() {
    }

    public static void init(DioramaPlatform platform) {
        if (initialized) {
            return;
        }

        DioramaServices.initialize(platform);
        initialized = true;
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void sendWorldMapSnapshot(ServerPlayer player, WorldMapBlockEntity worldMap) {
        if (worldMap.isDirty() || worldMap.getSnapshot().entries().isEmpty()) {
            return;
        }

        Packet<?> packet = worldMap.getUpdatePacket();
        if (packet != null) {
            player.connection.send(packet);
        }
        DioramaServices.platform().sendToPlayer(
                player,
                new WorldMapSnapshotPayload(worldMap.getBlockPos(), worldMap.getSnapshot()));
    }

    public static void sendWorldMapSnapshots(LevelChunk chunk, ServerPlayer player) {
        chunk.getBlockEntities().values().forEach(blockEntity -> {
            if (blockEntity instanceof WorldMapBlockEntity worldMap) {
                sendWorldMapSnapshot(player, worldMap);
            }
        });
    }

    /**
     * Registers every block. Must run while the BLOCK registry is open.
     */
    public static void registerBlocks() {
        BLOCK_REGISTRATIONS.forEach(Runnable::run);
    }

    /**
     * Registers every item. Must run after {@link #registerBlocks()}, because
     * BlockItems dereference their block.
     */
    public static void registerItems() {
        ITEM_REGISTRATIONS.forEach(Runnable::run);
    }

    /**
     * Registers every block entity type. Must run after {@link #registerBlocks()},
     * because each type declares its valid blocks.
     */
    public static void registerBlockEntities() {
        BLOCK_ENTITY_REGISTRATIONS.forEach(Runnable::run);
    }

    /**
     * Convenience for loaders whose registries are all open at once (Fabric).
     */
    public static void registerAll() {
        registerBlocks();
        registerItems();
        registerBlockEntities();
    }

    private static RegistryHandle<Block> registerBlock(String name, Supplier<Block> factory) {
        RegistryHandle<Block> handle = new RegistryHandle<>(name);
        BLOCK_REGISTRATIONS.add(() ->
                handle.set(Registry.register(BuiltInRegistries.BLOCK, id(name), factory.get())));
        return handle;
    }

    private static <T extends Item> RegistryHandle<T> registerItem(String name, Supplier<T> factory) {
        RegistryHandle<T> handle = new RegistryHandle<>(name);
        ITEM_REGISTRATIONS.add(() ->
                handle.set(Registry.register(BuiltInRegistries.ITEM, id(name), factory.get())));
        return handle;
    }

    private static <T extends BlockEntityType<?>> RegistryHandle<T> registerBlockEntity(
            String name, Supplier<T> factory) {
        RegistryHandle<T> handle = new RegistryHandle<>(name);
        BLOCK_ENTITY_REGISTRATIONS.add(() ->
                handle.set(Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(name), factory.get())));
        return handle;
    }

    /**
     * A late-bound handle to a registered object. Populated when the owning
     * registry opens; {@link #get()} before that is a programming error and fails
     * loudly rather than returning null into worldgen or block logic.
     */
    public static final class RegistryHandle<T> {
        private final String name;
        private T value;

        private RegistryHandle(String name) {
            this.name = name;
        }

        public T get() {
            if (value == null) {
                throw new IllegalStateException(
                        "zen_diorama:" + name + " accessed before it was registered. "
                                + "Registration must run from the loader entrypoint "
                                + "while the registry is open.");
            }
            return value;
        }

        private void set(T value) {
            this.value = value;
        }
    }
}
