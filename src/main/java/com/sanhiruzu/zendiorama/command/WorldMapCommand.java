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
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class WorldMapCommand {
    private WorldMapCommand() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
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
        String sampling = scale <= effectiveVoxels
                ? "every block sampled"
                : "1 voxel per " + (scale / effectiveVoxels) + " blocks";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Settings → " + voxelSummary(voxels) + "  scale=" + scale + " blocks/tile  (" + sampling + ")"), false);
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
            Component.literal("Look at a World Map block first.")).create();
    }

    private static int setHeight(CommandContext<CommandSourceStack> ctx, double value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        float v = (float) value;
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(),
                t -> t.setHeightExaggeration(v));
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> Component.literal("Height → " + value + " (all connected tiles)"), false);
        return 1;
    }

    private static int setTint(CommandContext<CommandSourceStack> ctx, double value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        float v = (float) value;
        WorldMapBlockEntity.applyToConnected(wme.getLevel(), wme.getBlockPos(),
                t -> t.setElevationTint(v));
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> Component.literal("Tint → " + value + " (all connected tiles)"), false);
        return 1;
    }

    private static int setVoxels(CommandContext<CommandSourceStack> ctx, int count)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        DioramaConfig.MAP_RESOLUTION.set(count);
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        int effectiveVoxels = Math.min(count, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Voxels → " + voxelSummary(count) + " (1:1 sampling up to scale " + effectiveVoxels + "). Refreshing…"), false);
        return 1;
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        WorldMapBlockEntity.forceRefreshConnected(wme.getLevel(), wme.getBlockPos());
        ctx.getSource().sendSuccess(() -> Component.literal("Queued refresh for all connected map tiles."), false);
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
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Reset to config defaults — height=" + DioramaConfig.MAP_HEIGHT_EXAGGERATION.get()
            + ", tint=" + DioramaConfig.MAP_ELEVATION_TINT.get() + " (all connected tiles)."), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        WorldMapBlockEntity wme = getTargetedMap(ctx);
        int scale  = wme.getBlocksPerTile();
        int voxels = wme.getSamplerResolution();
        int effectiveVoxels = wme.getEffectiveSamplerResolution();
        int actual = wme.getSampledGridSize();
        String sampling = scale <= effectiveVoxels
            ? "every block sampled"
            : "1 voxel per " + (scale / effectiveVoxels) + " blocks";
        WorldMapBlockEntity.ConnectedGroup group = WorldMapBlockEntity.collectConnectedGroup(
                wme.getLevel(), wme.getBlockPos());
        String zoomLine;
        if (wme.getZoomIndex() >= 0 && wme.getZoomIndex() < WorldMapZoomLevel.LEVELS.length && group != null) {
            WorldMapZoomLevel zoom = WorldMapZoomLevel.LEVELS[wme.getZoomIndex()];
            WorldMapZoomTuning.EffectiveZoom tuned = WorldMapZoomTuning.resolve(zoom, group.width(), group.height());
            zoomLine = "[" + (wme.getZoomIndex() + 1) + "/" + WorldMapZoomLevel.LEVELS.length + "]  "
                    + zoom.name() + "  (" + tuned.scale() + " blocks / " + voxelSummary(tuned.voxels())
                    + " per tile on " + group.width() + "x" + group.height() + ")";
        } else {
            zoomLine = "[custom]  " + scale + " blocks / " + voxelSummary(voxels);
        }
        com.sanhiruzu.zendiorama.block.WorldMapPreset style =
            com.sanhiruzu.zendiorama.block.WorldMapPreset.PRESETS[wme.getStyleIndex()];
        ctx.getSource().sendSuccess(() -> Component.literal(
            "World Map @ " + wme.getBlockPos().toShortString()
            + "\n  zoom:    " + zoomLine
            + "\n  style:   [" + (wme.getStyleIndex() + 1) + "/" + com.sanhiruzu.zendiorama.block.WorldMapPreset.PRESETS.length + "]  " + style.name() + "  (height " + style.height() + "  tint " + style.tint() + ")"
            + "\n  sampled: " + actual + "×" + actual + "  (" + sampling + ")"
            + "\n  height:  " + wme.getEffectiveHeightExaggeration()
            + "\n  tint:    " + wme.getEffectiveElevationTint()), false);
        return 1;
    }

    private static String voxelSummary(int requestedVoxels) {
        int effectiveVoxels = Math.min(requestedVoxels, WorldMapBlockEntity.MAX_SAMPLER_RESOLUTION);
        if (effectiveVoxels == requestedVoxels) {
            return requestedVoxels + " voxels";
        }
        return requestedVoxels + " voxels requested, " + effectiveVoxels + " effective";
    }
}
