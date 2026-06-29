package com.sanhiruzu.zendiorama.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MiniatureBlockStateCodec {
    private static final ConcurrentMap<BlockState, String> ENCODE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, BlockState> DECODE_CACHE = new ConcurrentHashMap<>();

    private MiniatureBlockStateCodec() {
    }

    public static String encode(BlockState state) {
        return ENCODE_CACHE.computeIfAbsent(state, MiniatureBlockStateCodec::encodeUncached);
    }

    private static String encodeUncached(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        if (properties.isEmpty()) {
            return blockId.toString();
        }

        properties.sort(Comparator.comparing(Property::getName));
        StringBuilder encoded = new StringBuilder(blockId.toString()).append('[');
        for (int i = 0; i < properties.size(); i++) {
            Property<?> property = properties.get(i);
            if (i > 0) {
                encoded.append(',');
            }
            encoded.append(property.getName())
                    .append('=')
                    .append(valueName(state, property));
        }
        return encoded.append(']').toString();
    }

    public static BlockState decode(String encoded) {
        return DECODE_CACHE.computeIfAbsent(encoded, MiniatureBlockStateCodec::decodeUncached);
    }

    private static BlockState decodeUncached(String encoded) {
        int propertiesStart = encoded.indexOf('[');
        String blockId = propertiesStart >= 0 ? encoded.substring(0, propertiesStart) : encoded;
        ResourceLocation location = ResourceLocation.tryParse(blockId);
        if (location == null) {
            return Blocks.AIR.defaultBlockState();
        }

        Block block = BuiltInRegistries.BLOCK.get(location);
        BlockState state = block.defaultBlockState();
        if (propertiesStart < 0 || !encoded.endsWith("]")) {
            return state;
        }

        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        String properties = encoded.substring(propertiesStart + 1, encoded.length() - 1);
        if (properties.isBlank()) {
            return state;
        }

        for (String assignment : properties.split(",")) {
            int equalsIndex = assignment.indexOf('=');
            if (equalsIndex <= 0 || equalsIndex == assignment.length() - 1) {
                continue;
            }

            Property<?> property = definition.getProperty(assignment.substring(0, equalsIndex));
            if (property == null) {
                continue;
            }
            state = setValue(state, property, assignment.substring(equalsIndex + 1));
        }
        return state;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<T> property, String valueName) {
        Optional<T> value = property.getValue(valueName);
        return value.map(comparable -> state.setValue(property, comparable)).orElse(state);
    }
}
