package com.sanhiruzu.zendiorama.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Defers diorama entry teleports until the client acks cubemap capture (or a timeout fires). */
public final class DioramaPendingTeleports {
    private static final class PendingTeleport {
        final ServerPlayer player;
        final ServerLevel target;
        final double x, y, z;
        final float yRot, xRot;
        final Runnable afterTeleport;
        int ticksUntilTimeout;

        PendingTeleport(ServerPlayer player, ServerLevel target,
                        double x, double y, double z,
                        float yRot, float xRot,
                        int timeoutTicks, Runnable afterTeleport) {
            this.player = player;
            this.target = target;
            this.x = x; this.y = y; this.z = z;
            this.yRot = yRot; this.xRot = xRot;
            this.ticksUntilTimeout = timeoutTicks;
            this.afterTeleport = afterTeleport;
        }
    }

    private static final List<PendingTeleport> QUEUE = new ArrayList<>();

    private DioramaPendingTeleports() {}

    public static void enqueue(ServerPlayer player, ServerLevel target,
                                double x, double y, double z,
                                float yRot, float xRot,
                                int timeoutTicks, Runnable afterTeleport) {
        // Replace any existing pending entry for this player.
        QUEUE.removeIf(p -> p.player == player);
        QUEUE.add(new PendingTeleport(player, target, x, y, z, yRot, xRot, timeoutTicks, afterTeleport));
    }

    /** Client acked capture for this player: teleport now. */
    public static void completeCapture(ServerPlayer player) {
        Iterator<PendingTeleport> it = QUEUE.iterator();
        while (it.hasNext()) {
            PendingTeleport p = it.next();
            if (p.player == player) {
                it.remove();
                fire(p);
                return;
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) return;
        QUEUE.removeIf(p -> {
            if (--p.ticksUntilTimeout <= 0) {
                fire(p);
                return true;
            }
            return false;
        });
    }

    private static void fire(PendingTeleport p) {
        if (p.player.isRemoved()) return;
        p.player.teleportTo(p.target, p.x, p.y, p.z, p.yRot, p.xRot);
        if (p.afterTeleport != null) p.afterTeleport.run();
    }
}
