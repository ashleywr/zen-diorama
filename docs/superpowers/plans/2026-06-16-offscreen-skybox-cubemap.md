# Off-Screen Skybox Cubemap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the live-camera skybox capture with a hidden off-screen 6-face cubemap render that never moves the player's view, excludes the diorama block, removes the FOV/crop/rotation/seam hacks, teleports via a capture-complete handshake, and adds a subtle unbreakable edge lip inside the plot.

**Architecture:** Client renders the surrounding world into a dedicated off-screen `TextureTarget` six times (camera at the frame block's center, real 90° square projection), reads each face back via `Screenshot.takeScreenshot(target)`, blurs, and uploads to the existing `DioramaSkyboxTextures`. The server defers the teleport until the client acks capture (with a fallback timeout). A new unbreakable `DioramaEdgeBlock` forms a 1-block lip around the floor perimeter.

**Tech Stack:** NeoForge 21.1.220, Minecraft 1.21.1, Java 21, JUnit 5 (pure-logic tests only), Gradle.

---

## Testing Reality (read first)

This codebase can only unit-test pure logic in `core/` (no Minecraft runtime in tests — see `CLAUDE.md`). Therefore:

- **Pure logic** (the edge-lip perimeter math) gets a real JUnit 5 TDD cycle.
- **Everything else** (rendering, networking, blocks) is verified by `.\gradlew.bat compileJava` after each change, then a **manual in-client checklist** in the final task.
- The off-screen render (Task 6) is the **fragile core**: it uses engine internals/reflection whose exact behavior must be confirmed in a running client. The plan gives a concrete scaffold; expect to iterate on the flagged lines at runtime.

Build commands:
- Compile: `.\gradlew.bat compileJava`
- Unit tests: `.\gradlew.bat test`
- Single test class: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.PlotLipTest"`
- Client (manual): `.\gradlew.bat runClient`

Commit style: this repo's history uses no AI co-author trailer — do not add one.

---

## File Structure

**Create:**
- `src/main/java/com/sanhiruzu/zendiorama/core/PlotLip.java` — pure perimeter math (testable)
- `src/test/java/com/sanhiruzu/zendiorama/core/PlotLipTest.java` — unit test
- `src/main/java/com/sanhiruzu/zendiorama/block/DioramaEdgeBlock.java` — unbreakable edge block
- `src/main/resources/assets/zen_diorama/blockstates/diorama_edge.json`
- `src/main/resources/assets/zen_diorama/models/block/diorama_edge.json`
- `src/main/java/com/sanhiruzu/zendiorama/network/DioramaCaptureReadyPayload.java` — serverbound ack
- `src/main/java/com/sanhiruzu/zendiorama/client/DioramaOffscreenCubemap.java` — off-screen capture engine

**Modify:**
- `ZenDiorama.java` — register edge block + ack payload
- `DioramaConfig.java` — repurpose `skyboxCaptureDelayTicks` comment as fallback timeout
- `block/DioramaFrameBlock.java` — place lip; ack-driven teleport
- `block/DioramaFrameBlockEntity.java` — exclude edge block from snapshot scan
- `server/DioramaPendingTeleports.java` — ack-driven completion + timeout
- `client/DioramaClientPayloadHandler.java` — trigger off-screen capture, send ack
- `client/DioramaFrameRenderer.java` — `suppressMiniature` flag
- `client/ZenDioramaClient.java` — register capture render hook; remove old listeners
- `src/main/resources/assets/zen_diorama/lang/en_us.json` — edge block name

**Delete (Task 7):**
- `client/DioramaCubemapCapture.java` — replaced (also removes FOV/crop/rotation/seam hacks)

---

## Task 1: Pure edge-lip perimeter math (TDD)

**Files:**
- Create: `src/main/java/com/sanhiruzu/zendiorama/core/PlotLip.java`
- Test: `src/test/java/com/sanhiruzu/zendiorama/core/PlotLipTest.java`

The lip is a 1-block-high ring at `lipY` covering the outer cells of the `plotSize × plotSize` floor footprint (a cell is on the ring if its local x or z is `0` or `plotSize-1`). Returns world `{x,y,z}` int triples. Kept Minecraft-free so it is unit-testable.

- [ ] **Step 1: Write the failing test**

```java
package com.sanhiruzu.zendiorama.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotLipTest {

    @Test
    void perimeterCountMatchesRingFormula() {
        // For an N×N footprint the ring has 4*N - 4 cells.
        List<int[]> ring = PlotLip.perimeter(0, 5, 0, 15);
        assertEquals(4 * 15 - 4, ring.size());
    }

    @Test
    void allCellsAreOnTheEdgeAtLipY() {
        int originX = 100, originZ = -40, lipY = 7, size = 15;
        List<int[]> ring = PlotLip.perimeter(originX, lipY, originZ, size);
        for (int[] cell : ring) {
            int lx = cell[0] - originX;
            int lz = cell[2] - originZ;
            assertEquals(lipY, cell[1]);
            boolean onEdge = lx == 0 || lx == size - 1 || lz == 0 || lz == size - 1;
            assertTrue(onEdge, "cell not on edge: lx=" + lx + " lz=" + lz);
        }
    }

    @Test
    void includesAllFourCorners() {
        int size = 15;
        List<int[]> ring = PlotLip.perimeter(0, 0, 0, size);
        assertTrue(contains(ring, 0, 0, 0));
        assertTrue(contains(ring, size - 1, 0, 0));
        assertTrue(contains(ring, 0, 0, size - 1));
        assertTrue(contains(ring, size - 1, 0, size - 1));
    }

    private static boolean contains(List<int[]> ring, int x, int y, int z) {
        return ring.stream().anyMatch(c -> c[0] == x && c[1] == y && c[2] == z);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.PlotLipTest"`
Expected: FAIL — `PlotLip` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.sanhiruzu.zendiorama.core;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for the diorama interior edge lip. No Minecraft dependencies. */
public final class PlotLip {

    private PlotLip() {
    }

    /**
     * The 1-block-high ring of cells at {@code lipY} covering the outer edge of the
     * {@code plotSize × plotSize} floor footprint anchored at ({@code originX}, {@code originZ}).
     *
     * @return list of {x, y, z} world coordinate triples.
     */
    public static List<int[]> perimeter(int originX, int lipY, int originZ, int plotSize) {
        List<int[]> cells = new ArrayList<>(Math.max(0, 4 * plotSize - 4));
        for (int lx = 0; lx < plotSize; lx++) {
            for (int lz = 0; lz < plotSize; lz++) {
                boolean onEdge = lx == 0 || lx == plotSize - 1 || lz == 0 || lz == plotSize - 1;
                if (onEdge) {
                    cells.add(new int[] {originX + lx, lipY, originZ + lz});
                }
            }
        }
        return cells;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.PlotLipTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/core/PlotLip.java src/test/java/com/sanhiruzu/zendiorama/core/PlotLipTest.java
git commit -m "feat: add pure edge-lip perimeter geometry with tests"
```

---

## Task 2: Edge block + assets + registration

**Files:**
- Create: `src/main/java/com/sanhiruzu/zendiorama/block/DioramaEdgeBlock.java`
- Create: `src/main/resources/assets/zen_diorama/blockstates/diorama_edge.json`
- Create: `src/main/resources/assets/zen_diorama/models/block/diorama_edge.json`
- Modify: `ZenDiorama.java`
- Modify: `src/main/resources/assets/zen_diorama/lang/en_us.json`

`DioramaEdgeBlock` mirrors the unbreakable strength pattern used by the exit/control blocks (`strength(-1.0F, 3600000.0F)`).

- [ ] **Step 1: Create the block class**

```java
package com.sanhiruzu.zendiorama.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Unbreakable decorative lip marking the diorama interior edge. */
public class DioramaEdgeBlock extends Block {
    public static final MapCodec<DioramaEdgeBlock> CODEC = simpleCodec(DioramaEdgeBlock::new);

    public DioramaEdgeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
```

- [ ] **Step 2: Register the block in `ZenDiorama.java`**

Add after the `DIORAMA_CONTROL` registration (around `ZenDiorama.java:71`):

```java
    public static final DeferredBlock<Block> DIORAMA_EDGE = BLOCKS.register(
            "diorama_edge",
            () -> new DioramaEdgeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(-1.0F, 3600000.0F)
                    .noOcclusion()));
```

Add the import near the other block imports (top of `ZenDiorama.java`):

```java
import com.sanhiruzu.zendiorama.block.DioramaEdgeBlock;
```

- [ ] **Step 3: Create the blockstate JSON**

`src/main/resources/assets/zen_diorama/blockstates/diorama_edge.json`:

```json
{
  "variants": {
    "": { "model": "zen_diorama:block/diorama_edge" }
  }
}
```

- [ ] **Step 4: Create the block model JSON**

`src/main/resources/assets/zen_diorama/models/block/diorama_edge.json` (reuse an existing diorama frame texture so no new art is required; adjust the texture path if the frame uses a different one):

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "minecraft:block/stripped_oak_log"
  }
}
```

- [ ] **Step 5: Add the lang entry**

In `src/main/resources/assets/zen_diorama/lang/en_us.json`, add (keep valid JSON — add a comma to the previous line):

```json
  "block.zen_diorama.diorama_edge": "Diorama Edge"
```

- [ ] **Step 6: Compile**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/block/DioramaEdgeBlock.java src/main/resources/assets/zen_diorama/blockstates/diorama_edge.json src/main/resources/assets/zen_diorama/models/block/diorama_edge.json src/main/java/com/sanhiruzu/zendiorama/ZenDiorama.java src/main/resources/assets/zen_diorama/lang/en_us.json
git commit -m "feat: add unbreakable diorama edge block"
```

---

## Task 3: Place the lip + exclude it from the snapshot

**Files:**
- Modify: `block/DioramaFrameBlock.java` (in `ensurePlotInterior`)
- Modify: `block/DioramaFrameBlockEntity.java` (in `refreshSnapshotFromInterior`)

The lip sits one block above the floor (`groundY + 1`) on the perimeter. Place it on first init alongside the floor.

- [ ] **Step 1: Add lip placement in `ensurePlotInterior`**

In `block/DioramaFrameBlock.java`, locate `ensurePlotInterior`. After `ensurePlotFloor(level, plotOrigin, groundY, plotSize);` (inside the `if (!frame.isPlotInitialized())` block, before `frame.markPlotInitialized();`), add:

```java
        ensureEdgeLip(level, plotOrigin, groundY, plotSize);
```

Then add this helper method to the class (near `ensurePlotFloor`):

```java
    private static void ensureEdgeLip(ServerLevel level, PlotOrigin plotOrigin, int groundY, int plotSize) {
        BlockState edge = ZenDiorama.DIORAMA_EDGE.get().defaultBlockState();
        for (int[] cell : com.sanhiruzu.zendiorama.core.PlotLip.perimeter(
                plotOrigin.x(), groundY + 1, plotOrigin.z(), plotSize)) {
            BlockPos lipPos = new BlockPos(cell[0], cell[1], cell[2]);
            if (level.getBlockState(lipPos).isAir()) {
                level.setBlockAndUpdate(lipPos, edge);
            }
        }
    }
```

- [ ] **Step 2: Exclude edge block from the miniature snapshot**

In `block/DioramaFrameBlockEntity.java`, in `refreshSnapshotFromInterior`, extend the skip condition. Change:

```java
                    if (state.isAir()
                            || state.is(Blocks.BARRIER)
                            || state.is(ZenDiorama.DIORAMA_EXIT.get())
                            || state.is(ZenDiorama.DIORAMA_CONTROL.get())) {
                        continue;
                    }
```

to:

```java
                    if (state.isAir()
                            || state.is(Blocks.BARRIER)
                            || state.is(ZenDiorama.DIORAMA_EXIT.get())
                            || state.is(ZenDiorama.DIORAMA_CONTROL.get())
                            || state.is(ZenDiorama.DIORAMA_EDGE.get())) {
                        continue;
                    }
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/block/DioramaFrameBlock.java src/main/java/com/sanhiruzu/zendiorama/block/DioramaFrameBlockEntity.java
git commit -m "feat: place unbreakable edge lip in diorama interior"
```

---

## Task 4: Serverbound capture-ready ack payload

**Files:**
- Create: `network/DioramaCaptureReadyPayload.java`
- Modify: `ZenDiorama.java` (register payload, `playToServer`)

Empty payload — its arrival is the signal. Registered on the same `"2"` registrar; bump to `"3"` since the protocol changes.

- [ ] **Step 1: Create the payload**

```java
package com.sanhiruzu.zendiorama.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: the entry cubemap capture has finished; safe to teleport. */
public record DioramaCaptureReadyPayload() implements CustomPacketPayload {
    public static final Type<DioramaCaptureReadyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("zen_diorama", "capture_ready"));

    public static final StreamCodec<ByteBuf, DioramaCaptureReadyPayload> STREAM_CODEC =
            StreamCodec.unit(new DioramaCaptureReadyPayload());

    @Override
    public Type<DioramaCaptureReadyPayload> type() {
        return TYPE;
    }
}
```

- [ ] **Step 2: Register it (server-bound) in `ZenDiorama.java`**

In `registerNetworkPayloads`, bump both existing `registrar("2")` calls to `registrar("3")`, and add the serverbound handler. The full method becomes:

```java
    private void registerNetworkPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("3").playToClient(
                DioramaSkySnapshotPayload.TYPE,
                DioramaSkySnapshotPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleSkySnapshot(payload));
        event.registrar("3").playToClient(
                DioramaTransitionPayload.TYPE,
                DioramaTransitionPayload.STREAM_CODEC,
                (payload, context) -> DioramaClientboundPayloadHandler.handleTransition(payload));
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
```

Add the import near the other network imports:

```java
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
```

- [ ] **Step 3: Compile** (will fail until Task 5 adds `completeCapture` — that is expected; do Task 5 before committing).

Run: `.\gradlew.bat compileJava`
Expected: FAIL — `completeCapture` not found yet.

- [ ] **Step 4: Proceed to Task 5, then commit together.**

---

## Task 5: Ack-driven teleport (replace fixed delay)

**Files:**
- Modify: `server/DioramaPendingTeleports.java`
- Modify: `block/DioramaFrameBlock.java` (`teleportIntoDiorama`)
- Modify: `DioramaConfig.java` (comment only)

Replace the fixed countdown with an ack-or-timeout model: the teleport fires when `completeCapture` is called for the player, or when the fallback timeout (still `skyboxCaptureDelayTicks`, now an upper bound) elapses.

- [ ] **Step 1: Rewrite `DioramaPendingTeleports`**

Replace the whole file with:

```java
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
```

- [ ] **Step 2: Point `DioramaFrameBlock` at the timeout semantics**

In `block/DioramaFrameBlock.java`, the existing `teleportIntoDiorama` already calls `DioramaPendingTeleports.enqueue(...)` with `DioramaConfig.SKYBOX_CAPTURE_DELAY_TICKS.get()`. No signature change is needed — the same argument is now the fallback timeout. Confirm the call still reads:

```java
        DioramaPendingTeleports.enqueue(player, target, x, y, z, player.getYRot(), player.getXRot(),
                DioramaConfig.SKYBOX_CAPTURE_DELAY_TICKS.get(),
                () -> target.playSound(null, spawnPos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.65F, 0.6F));
```

- [ ] **Step 3: Update the config comment in `DioramaConfig.java`**

Change the `SKYBOX_CAPTURE_DELAY_TICKS` comment to describe the new meaning:

```java
    public static final ModConfigSpec.IntValue SKYBOX_CAPTURE_DELAY_TICKS = BUILDER
            .comment("Fallback timeout (server ticks) before teleporting into the diorama if the client never acks cubemap capture. Normally the ack arrives in ~1 frame; this only fires on packet loss or capture failure. Default 15.")
            .defineInRange("skyboxCaptureDelayTicks", 15, 1, 100);
```

- [ ] **Step 4: Compile** (now Task 4 + 5 resolve together)

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Tasks 4 + 5 together**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/network/DioramaCaptureReadyPayload.java src/main/java/com/sanhiruzu/zendiorama/ZenDiorama.java src/main/java/com/sanhiruzu/zendiorama/server/DioramaPendingTeleports.java src/main/java/com/sanhiruzu/zendiorama/DioramaConfig.java src/main/java/com/sanhiruzu/zendiorama/block/DioramaFrameBlock.java
git commit -m "feat: ack-driven diorama entry teleport with timeout fallback"
```

---

## Task 6: Off-screen cubemap engine (FRAGILE CORE)

**Files:**
- Create: `client/DioramaOffscreenCubemap.java`

This task is the one that must be confirmed in a running client. The scaffold below uses: a `TextureTarget`, a main-render-target swap via reflection, camera position/rotation via reflection, a direct `LevelRenderer.renderLevel(...)` call with a 90° square projection, and `Screenshot.takeScreenshot(target)` for readback. **Verify each flagged line against the actual engine in `runClient` and adjust as needed** — exact internal signatures are the expected source of iteration here.

Capture is requested by setting a flag (Task 7 wires the trigger and the render hook). The blur helpers are carried over from the old `DioramaCubemapCapture`.

- [ ] **Step 1: Create the engine**

```java
package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Field;

/**
 * Off-screen 6-face cubemap capture. Renders the surrounding world into a hidden square target
 * from the frame block's center, with a real 90 degree projection — the player's view never moves.
 *
 * FRAGILE: relies on engine internals (main-target swap, camera reflection, direct level render).
 * Targets 1.21.1 NeoForge only.
 */
public final class DioramaOffscreenCubemap {
    // Face order matches DioramaSkyboxRenderer: 0=north(-Z),1=south(+Z),2=east(+X),3=west(-X),4=up(+Y),5=down(-Y)
    // Minecraft yaw: 0=south, 90=west, 180=north, 270=east. Pitch: -90=up, +90=down.
    private static final float[] FACE_YAWS   = { 180f, 0f, 270f, 90f, 0f, 0f };
    private static final float[] FACE_PITCHES = {   0f, 0f,   0f,  0f, -90f, 90f };

    private static boolean captureRequested = false;
    private static double camX, camY, camZ;

    private static TextureTarget target;

    private DioramaOffscreenCubemap() {
    }

    /** Request a capture centered on the given frame-block world position (block center). */
    public static void request(double frameCenterX, double frameCenterY, double frameCenterZ) {
        camX = frameCenterX;
        camY = frameCenterY;
        camZ = frameCenterZ;
        captureRequested = true;
    }

    public static boolean isCaptureRequested() {
        return captureRequested;
    }

    /**
     * Performs the capture if requested. Must be called on the render thread at a point where the
     * GL context is ready and we are NOT inside the normal level render (Task 7 chooses the hook).
     * Returns true if a capture ran (caller should then send the ack).
     */
    public static boolean runIfRequested() {
        if (!captureRequested) return false;
        captureRequested = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        int size = Mth.clamp(DioramaConfig.SKYBOX_CAPTURE_RESOLUTION.get(), 16, 1024);
        ensureTarget(size);

        RenderTarget mainTarget = mc.getMainRenderTarget();
        Camera camera = mc.gameRenderer.getMainCamera();

        // Suppress the miniature BER during capture so it never lands in a face.
        DioramaFrameRenderer.suppressMiniature = true;
        try {
            swapMainTarget(mc, target);                 // FRAGILE: reflection on Minecraft.mainRenderTarget
            for (int face = 0; face < 6; face++) {
                renderFace(mc, camera, face, size);
            }
        } catch (Exception e) {
            com.sanhiruzu.zendiorama.ZenDiorama.LOGGER.error("[zen_diorama] off-screen capture failed", e);
        } finally {
            swapMainTarget(mc, mainTarget);             // restore
            DioramaFrameRenderer.suppressMiniature = false;
            mainTarget.bindWrite(true);
        }
        return true;
    }

    private static void renderFace(Minecraft mc, Camera camera, int face, int size) {
        // FRAGILE: position + orient the engine camera for this face.
        setCamera(camera, mc, camX, camY, camZ, FACE_YAWS[face], FACE_PITCHES[face]);

        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(90.0), 1.0f, 0.05f, mc.gameRenderer.getDepthFar());
        Matrix4f frustum = new Matrix4f().rotationXYZ(
                (float) Math.toRadians(FACE_PITCHES[face]), (float) Math.toRadians(-FACE_YAWS[face]), 0f);

        target.bindWrite(true);
        target.clear(false);

        // FRAGILE: direct level render into the bound (swapped) target. Confirm signature in 1.21.1.
        mc.levelRenderer.renderLevel(
                mc.getTimer(), false, camera, mc.gameRenderer, mc.gameRenderer.lightTexture(),
                frustum, projection);

        NativeImage image = Screenshot.takeScreenshot(target);
        int blurPasses = DioramaConfig.SKYBOX_BLUR_RADIUS.get();
        for (int i = 0; i < blurPasses; i++) {
            image = boxBlur(image);
        }
        DioramaSkyboxTextures.upload(face, image);
    }

    private static void ensureTarget(int size) {
        if (target == null) {
            target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
        } else if (target.width != size || target.height != size) {
            target.resize(size, size, Minecraft.ON_OSX);
        }
    }

    // --- FRAGILE reflection helpers (verify field/method names in 1.21.1) ---

    private static Field mainTargetField;

    private static void swapMainTarget(Minecraft mc, RenderTarget newTarget) throws Exception {
        if (mainTargetField == null) {
            mainTargetField = findField(Minecraft.class, RenderTarget.class);
            mainTargetField.setAccessible(true);
        }
        mainTargetField.set(mc, newTarget);
    }

    private static void setCamera(Camera camera, Minecraft mc, double x, double y, double z,
                                  float yaw, float pitch) {
        try {
            // Camera.setPosition(Vec3) and Camera.setRotation(float yaw, float pitch) are protected.
            var setPos = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
            setPos.setAccessible(true);
            setPos.invoke(camera, new Vec3(x, y, z));
            var setRot = Camera.class.getDeclaredMethod("setRotation", float.class, float.class);
            setRot.setAccessible(true);
            setRot.invoke(camera, yaw, pitch);
        } catch (Exception e) {
            throw new RuntimeException("camera reflection failed", e);
        }
    }

    private static Field findField(Class<?> owner, Class<?> type) {
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() == type) return f;
        }
        throw new IllegalStateException("no field of type " + type + " in " + owner);
    }

    // --- blur (carried over from the previous capture implementation) ---

    private static NativeImage boxBlur(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long ra = 0, ga = 0, ba = 0;
                int count = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int sx = Mth.clamp(x + ox, 0, w - 1);
                        int sy = Mth.clamp(y + oy, 0, h - 1);
                        int abgr = src.getPixelRGBA(sx, sy);
                        ra += FastColor.ABGR32.red(abgr);
                        ga += FastColor.ABGR32.green(abgr);
                        ba += FastColor.ABGR32.blue(abgr);
                        count++;
                    }
                }
                dst.setPixelRGBA(x, y, FastColor.ABGR32.color(255,
                        (int) (ba / count), (int) (ga / count), (int) (ra / count)));
            }
        }
        src.close();
        return dst;
    }
}
```

- [ ] **Step 2: Add the `suppressMiniature` flag to `DioramaFrameRenderer`**

In `client/DioramaFrameRenderer.java`, add a public static flag and honor it at the top of `render`:

```java
    public static boolean suppressMiniature = false;
```

At the very top of `render(...)`, before reading the snapshot:

```java
        if (suppressMiniature) return;
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL. If a flagged engine signature does not resolve (`renderLevel`, `getTimer`, `getDepthFar`, `TextureTarget`, `resize`), fix it against the decompiled sources before continuing — these are the anticipated iteration points.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/DioramaOffscreenCubemap.java src/main/java/com/sanhiruzu/zendiorama/client/DioramaFrameRenderer.java
git commit -m "feat: off-screen cubemap capture engine + miniature suppression flag"
```

---

## Task 7: Wire the trigger, the render hook, and remove the old capture

**Files:**
- Modify: `client/DioramaClientPayloadHandler.java`
- Modify: `client/ZenDioramaClient.java`
- Delete: `client/DioramaCubemapCapture.java`

The transition payload now requests an off-screen capture; a render hook performs it and sends the ack; the old live-capture file and its listeners are removed.

- [ ] **Step 1: Update the transition handler**

Replace the body of `client/DioramaClientPayloadHandler.java` `handleTransition` with:

```java
    public static void handleTransition(DioramaTransitionPayload payload) {
        if (payload.entering() && payload.framePos() != null) {
            net.minecraft.core.BlockPos p = payload.framePos();
            // Request a capture centered on the frame block; the render hook runs it next frame.
            DioramaOffscreenCubemap.request(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
            DioramaHudOverlay.beginTransition(true);
        } else {
            DioramaHudOverlay.beginTransition(payload.entering());
        }
    }
```

- [ ] **Step 2: Add the render hook + ack in `ZenDioramaClient.java`**

Add a listener that, after the level renders, runs the capture if requested and sends the ack. Use `RenderLevelStageEvent` at the `AFTER_LEVEL` stage; guard against reentrancy by running only when requested.

Add imports:

```java
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.sanhiruzu.zendiorama.network.DioramaCaptureReadyPayload;
```

In the constructor, register:

```java
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL
                    && DioramaOffscreenCubemap.isCaptureRequested()) {
                if (DioramaOffscreenCubemap.runIfRequested()) {
                    PacketDistributor.sendToServer(new DioramaCaptureReadyPayload());
                }
            }
        });
```

> Reentrancy note: `AFTER_LEVEL` fires from inside the engine's level render. If the nested `renderLevel` call corrupts state in-client, switch the hook to run the capture at the **start of the next frame** instead (e.g. defer one frame: on the first `AFTER_LEVEL` where requested, set a "ready next frame" flag; perform the capture on the following frame's earliest client render event). Decide this during the in-client verification step.

Then **remove** the four old listener registrations:

```java
        NeoForge.EVENT_BUS.addListener(DioramaCubemapCapture::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(DioramaCubemapCapture::onComputeFov);
        NeoForge.EVENT_BUS.addListener(DioramaCubemapCapture::onRenderHand);
        NeoForge.EVENT_BUS.addListener(DioramaCubemapCapture::onGuiPre);
```

- [ ] **Step 3: Delete the old capture file**

```bash
git rm src/main/java/com/sanhiruzu/zendiorama/client/DioramaCubemapCapture.java
```

- [ ] **Step 4: Compile**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL. If anything still references `DioramaCubemapCapture`, remove those references.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/DioramaClientPayloadHandler.java src/main/java/com/sanhiruzu/zendiorama/client/ZenDioramaClient.java
git commit -m "feat: trigger off-screen capture via render hook; remove live-camera capture"
```

---

## Task 8: Full build + manual in-client verification

**Files:** none (verification only)

- [ ] **Step 1: Full compile + unit tests**

Run: `.\gradlew.bat compileJava` then `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL; `PlotLipTest` passes.

- [ ] **Step 2: Launch the client**

Run: `.\gradlew.bat runClient`

- [ ] **Step 3: Verification checklist** (place a frame in an open area, enter, look around, exit)

- [ ] No visible camera pan/spin on entry (the player's view never whips around).
- [ ] Entry is near-instant (no ~750 ms freeze before teleport).
- [ ] Inside the diorama: all 6 skybox faces show the real surrounding world.
- [ ] Looking off the edge / down does NOT show the diorama frame block in the skybox.
- [ ] Cube faces line up without obvious seams (no FOV/crop/rotation artifacts).
- [ ] A 1-block unbreakable edge lip rings the interior floor and cannot be broken.
- [ ] Exit, then view the frame from outside: the flat miniature still renders correctly.
- [ ] Timeout fallback: confirm the teleport still happens even if the ack is dropped (e.g. temporarily comment out `PacketDistributor.sendToServer(...)` in `ZenDioramaClient` and verify entry still completes after the timeout, then restore it).

- [ ] **Step 4: Place a frame in dense terrain (under trees, beside a wall)** and confirm the up/down faces pick up nearby geometry (sanity that all faces capture real world).

- [ ] **Step 5: Final commit (if any fixes were made during verification)**

```bash
git add -A
git commit -m "fix: off-screen cubemap in-client verification adjustments"
```

---

## Self-Review Notes

- **Spec coverage:** off-screen render (T6), block exclusion via camera-in-block + `suppressMiniature` (T6), real 90° projection / removal of FOV-crop-rotation-seam hacks (T6/T7 deletion), ack handshake + timeout (T4/T5), subtle edge lip with dedicated unbreakable block (T1/T2/T3), one-time snapshot (no re-capture task — matches non-goal). All covered.
- **Fragile core isolation:** all engine-internal access lives in `DioramaOffscreenCubemap` (spec risk-mitigation requirement met).
- **Type consistency:** `request(...)` / `isCaptureRequested()` / `runIfRequested()` / `suppressMiniature` / `completeCapture(ServerPlayer)` / `enqueue(...)` signatures are used identically across tasks.
- **Known iteration points (flagged, not placeholders):** exact 1.21.1 signatures for `levelRenderer.renderLevel`, `mc.getTimer`, `getDepthFar`, `TextureTarget`/`resize`, the `Camera` reflected methods, and the `RenderLevelStageEvent` reentrancy decision — all to be confirmed in `runClient` per Task 6/Task 8.
