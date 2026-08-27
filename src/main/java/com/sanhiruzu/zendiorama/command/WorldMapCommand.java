package com.sanhiruzu.zendiorama.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.block.WorldMapBlockEntity;
import com.sanhiruzu.zendiorama.block.WorldMapZoomLevel;
import com.sanhiruzu.zendiorama.block.WorldMapZoomTuning;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.brigadier.CommandDispatcher;

public final class WorldMapCommand {
    private WorldMapCommand() {}

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("zdmap")
                .requires(src -> src.hasPermission(2))
                .executes(WorldMapCommand::info)
                .then(Commands.literal("settings")
                    .then(Commands.argument("voxels", IntegerArgumentType.integer(1))
                        .then(Commands.argument("scale", IntegerArgumentType.integer(1))
                            .executes(ctx -> setValues(ctx,
                                IntegerArgumentType.getInteger(ctx, "voxels"),
                                IntegerArgumentType.getInteger(ctx, "scale"))))))
                .then(Commands.literal("height")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> setHeight(ctx, DoubleArgumentType.getDouble(ctx, "value")))))
                .then(Commands.literal("tint")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> setTint(ctx, DoubleArgumentType.getDouble(ctx, "value")))))
                .then(Commands.literal("voxels")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> setVoxels(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("refresh")
                    .executes(WorldMapCommand::refresh))
                .then(Commands.literal("reset")
                    .executes(WorldMapCommand::reset))
                .then(Commands.literal("info")
                    .executes(WorldMapCommand::info))
        );
    }

    private static int setValues(CommandContext<CommandSourceStack> ctx, int voxels, int scale)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        wme.configure(wme.getMapCenterX(), wme.getMapCenterZ(), scale);
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(),
                t -> t.applyZoom(-1, voxels));  // -1 = custom (not a preset zoom level)
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        int effectiveVoxels = Math.min(voxels, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        Component sampling = samplingSummary(scale, effectiveVoxels);
        ctx.getSource().sendSuccess(() -> tr(
                "command.zen_diorama.map.settings",
                "Settings -> %s  scale=%s blocks/tile  (%s)",
                voxelSummary(voxels),
                scale,
                sampling
        ), false);
        return 1;
    }

    private static WorldMapBlockEntity getTargetedMap(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HitResult hit = player.pick(20.0, 1.0f, false);
        if (hit instanceof BlockHitResult bhr) {
            if (player.level().getBlockEntity(bhr.getBlockPos()) instanceof WorldMapBlockEntity wme) {
                return wme;
            }
        }
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
            tr("command.zen_diorama.map.error.look_at_map", "Look at a World Map block first.")).create();
    }

    private static int setHeight(CommandContext<CommandSourceStack> ctx, double value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        float v = (float) value;
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(),
                t -> t.setHeightExaggeration(v));
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> tr("command.zen_diorama.map.height", "Height -> %s (all connected tiles)", value), false);
        return 1;
    }

    private static int setTint(CommandContext<CommandSourceStack> ctx, double value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        float v = (float) value;
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(),
                t -> t.setElevationTint(v));
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> tr("command.zen_diorama.map.tint", "Tint -> %s (all connected tiles)", value), false);
        return 1;
    }

    private static int setVoxels(CommandContext<CommandSourceStack> ctx, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        DioramaConfig.MAP_RESOLUTION.set(count);
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        int effectiveVoxels = Math.min(count, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        ctx.getSource().sendSuccess(() -> tr(
                "command.zen_diorama.map.voxels",
                "Voxels -> %s (1:1 sampling up to scale %s). Refreshing...",
                voxelSummary(count),
                effectiveVoxels
        ), false);
        return 1;
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> tr("command.zen_diorama.map.refresh", "Queued refresh for all connected map tiles."), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(), t -> {
            t.resetHeightExaggeration();
            t.resetElevationTint();
        });
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> tr(
                "command.zen_diorama.map.reset",
                "Reset to config defaults - height=%s, tint=%s (all connected tiles).",
                DioramaConfig.MAP_HEIGHT_EXAGGERATION.get(),
                DioramaConfig.MAP_ELEVATION_TINT.get()
        ), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        int scale  = wme.getBlocksPerTile();
        int voxels = wme.getSamplerResolution();
        int effectiveVoxels = wme.getEffectiveSamplerResolution();
        int actual = wme.getSampledGridSize();
        Component sampling = samplingSummary(scale, effectiveVoxels);
        WorldMapBlockEntity.ConnectedGroup group = WorldMapBlockEntity.collectConnectedGroup(
                wme.getLevel(), wme.getBlockPos());
        Component zoomLine;
        if (wme.getZoomIndex() >= 0 && wme.getZoomIndex() < WorldMapZoomLevel.LEVELS.size() && group != null) {
            WorldMapZoomLevel zoom = WorldMapZoomLevel.LEVELS.get(wme.getZoomIndex());
            WorldMapZoomTuning.EffectiveZoom tuned = WorldMapZoomTuning.resolve(zoom, group.width(), group.height());
            zoomLine = tr(
                    "command.zen_diorama.map.info.zoom.preset",
                    "[%s/%s]  %s  (%s blocks / %s per tile on %sx%s)",
                    wme.getZoomIndex() + 1,
                    WorldMapZoomLevel.LEVELS.size(),
                    zoom.displayName(),
                    tuned.scale(),
                    voxelSummary(tuned.voxels()),
                    group.width(),
                    group.height()
            );
        } else {
            zoomLine = tr("command.zen_diorama.map.info.zoom.custom", "[custom]  %s blocks / %s", scale, voxelSummary(voxels));
        }
        com.sanhiruzu.zendiorama.block.WorldMapPreset style =
            com.sanhiruzu.zendiorama.block.WorldMapPreset.PRESETS.get(wme.getStyleIndex());
        ctx.getSource().sendSuccess(() -> tr(
                "command.zen_diorama.map.info",
                "World Map @ %s\n  zoom:    %s\n  style:   [%s/%s]  %s  (height %s  tint %s)\n  sampled: %sx%s  (%s)\n  height:  %s\n  tint:    %s",
                wme.getBlockPos().toShortString(),
                zoomLine,
                wme.getStyleIndex() + 1,
                com.sanhiruzu.zendiorama.block.WorldMapPreset.PRESETS.size(),
                style.displayName(),
                style.height(),
                style.tint(),
                actual,
                actual,
                sampling,
                wme.getEffectiveHeightExaggeration(),
                wme.getEffectiveElevationTint()
        ), false);
        return 1;
    }

    private static Component samplingSummary(int scale, int voxels) {
        if (scale <= voxels) {
            return tr("command.zen_diorama.map.sampling.every_block", "every block sampled");
        }
        return tr("command.zen_diorama.map.sampling.per_blocks", "1 voxel per %s blocks", scale / voxels);
    }

    private static Component voxelSummary(int requestedVoxels) {
        int effectiveVoxels = Math.min(requestedVoxels, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        if (effectiveVoxels == requestedVoxels) {
            return tr("command.zen_diorama.map.voxel_summary.exact", "%s voxels", requestedVoxels);
        }
        return tr(
                "command.zen_diorama.map.voxel_summary.capped",
                "%s voxels requested, %s effective",
                requestedVoxels,
                effectiveVoxels
        );
    }
}
