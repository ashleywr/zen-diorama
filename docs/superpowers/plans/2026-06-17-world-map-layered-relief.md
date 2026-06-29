# World Map Layered Relief Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the `world_map` block as layered relief — true ground terrain that rolls cleanly, with trees as distinct toy shapes sitting on top — instead of flat-topped colored pillars.

**Architecture:** A column can emit up to two snapshot entries (a ground entry and a tree entry). A voxel's role (ground vs. tree) is *derived from its block state* via a shared `core/SurfaceClassifier` predicate — nothing new is stored in NBT. The sampler scans down past leaves/logs to find true ground; the renderer builds its hillshade from ground entries only and draws tree entries as a trunk stub + tapered canopy.

**Tech Stack:** NeoForge 21.1.220, Minecraft 1.21.1, Java 21, Gradle (`gradlew.bat`), JUnit 5.

## Global Constraints

- **Mod target:** NeoForge 21.1.220 / Minecraft 1.21.1 / Java 21. Copy exact API names; do not invent.
- **`core/` package:** pure-ish algorithms. May reference common Minecraft classes (`BlockState`, `BlockTags`) — these are already used by `MiniatureBlockStateCodec` and `SurfaceSampler` — but **never** client-only classes (`net.minecraft.client.*`).
- **Testing reality:** `.\gradlew.bat test` is pure JUnit with **no Minecraft runtime**. Code touching `BlockTags`, `ServerLevel`, biomes, or GPU buffers cannot be unit-tested here. For those, the automated gate is: `.\gradlew.bat compileJava` succeeds **and** `.\gradlew.bat test` (existing tests) stays green. Behavior is verified in the final manual task.
- **Commits:** Do **not** add `Co-Authored-By` or any AI attribution to commit messages (user standing rule).
- **No format change:** `MiniatureSnapshot.Entry`, `MiniatureSnapshotNbt`, and `SnapshotSampler` are NOT modified by this plan.

---

### Task 1: Shared ground-vs-feature classifier

**Files:**
- Create: `src/main/java/com/sanhiruzu/zendiorama/core/SurfaceClassifier.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `SurfaceClassifier.isFeature(BlockState state) -> boolean` — true for tree material (leaves or logs), used by both the sampler (Task 2) and the renderer (Task 3).

- [ ] **Step 1: Create the classifier**

Create `src/main/java/com/sanhiruzu/zendiorama/core/SurfaceClassifier.java`:

```java
package com.sanhiruzu.zendiorama.core;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared ground-vs-feature classification for world-map relief rendering.
 *
 * <p>A voxel's role is derived from its block state rather than stored: the sampler
 * (server) and the geometry baker (client) both call {@link #isFeature} so the two
 * can never drift. "Feature" means tree material (leaves/logs) that sits on top of
 * the ground and should render as a tree shape rather than a terrain pillar.
 */
public final class SurfaceClassifier {
    private SurfaceClassifier() {
    }

    /** True when the block is tree material (leaves or logs). */
    public static boolean isFeature(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }
}
```

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`. (No unit test — `BlockTags`/`BlockState` require a Minecraft runtime the test harness does not provide; correctness is exercised in Task 6.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/core/SurfaceClassifier.java
git commit -m "feat: add shared ground-vs-feature classifier for world map"
```

---

### Task 2: Sampler emits ground + tree entries; raise budget

**Files:**
- Modify: `src/main/java/com/sanhiruzu/zendiorama/core/SurfaceSampler.java` (replace the `sample` method body)
- Modify: `src/main/java/com/sanhiruzu/zendiorama/DioramaConfig.java:60-62` (`MAP_MAX_BLOCKS` default)

**Interfaces:**
- Consumes: `SurfaceClassifier.isFeature(BlockState)` (Task 1); existing `computeTint(ServerLevel, BlockPos, BlockState)` and `MiniatureBlockStateCodec.encode(BlockState)`.
- Produces: a `MiniatureSnapshot` whose entries include, per column, one ground entry and (when leaves/logs sat above) one tree entry. Tree entries are identified downstream by `SurfaceClassifier.isFeature` on their block state.

- [ ] **Step 1: Raise the entry budget**

In `DioramaConfig.java`, change the `MAP_MAX_BLOCKS` default from `4096` to `16384`. The line currently reads:

```java
            .defineInRange("mapMaxBlocks", 4096, 64, 262144);
```

Change to:

```java
            .defineInRange("mapMaxBlocks", 16384, 64, 262144);
```

A fully-forested 48×48 tile now emits up to ~2× entries (ground + tree ≈ 4608); the higher budget keeps the stride-downsampler from firing. Worst case ~16384 entries packs to ~200 KB — far under the block-entity packet limit.

- [ ] **Step 2: Replace the sampler's `sample` method**

In `SurfaceSampler.java`, replace the entire existing `sample(...)` method with:

```java
    public static MiniatureSnapshot sample(ServerLevel level, int originX, int originZ, int width, int maxBlocks) {
        List<MiniatureSnapshot.Entry> entries = new ArrayList<>();
        int seaLevel = level.getSeaLevel();
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                int worldX = originX + x;
                int worldZ = originZ + z;
                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
                if (topY < minY) continue;
                cursor.set(worldX, topY, worldZ);
                BlockState topState = level.getBlockState(cursor);
                if (topState.isAir()) continue;

                // Scan down past tree material (leaves/logs) to the ground block, so the
                // ground entry reflects true terrain height rather than a tree's canopy.
                int groundY = topY;
                BlockState groundState = topState;
                while (groundY > minY && SurfaceClassifier.isFeature(groundState)) {
                    groundY--;
                    cursor.setY(groundY);
                    groundState = level.getBlockState(cursor);
                }

                // Ground entry (cursor is at the ground block).
                entries.add(new MiniatureSnapshot.Entry(
                        x,
                        groundY - seaLevel,
                        z,
                        MiniatureBlockStateCodec.encode(groundState),
                        computeTint(level, cursor, groundState)));

                // Tree entry when vegetation sat above the ground in this column.
                if (SurfaceClassifier.isFeature(topState)) {
                    cursor.set(worldX, topY, worldZ);
                    entries.add(new MiniatureSnapshot.Entry(
                            x,
                            topY - seaLevel,
                            z,
                            MiniatureBlockStateCodec.encode(topState),
                            computeTint(level, cursor, topState)));
                }
            }
        }

        return SnapshotSampler.sample(entries, maxBlocks);
    }
```

(The `computeTint` helper below this method is unchanged. `BlockPos` is already imported.)

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run existing tests (regression)**

Run: `.\gradlew.bat test`
Expected: `BUILD SUCCESSFUL` — `SnapshotSamplerTest`, `PlotAllocatorTest`, `PlotLipTest` all pass (no format change, so they are unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/core/SurfaceSampler.java src/main/java/com/sanhiruzu/zendiorama/DioramaConfig.java
git commit -m "feat: sample ground and tree entries separately for world map relief"
```

---

### Task 3: Renderer — ground-only hillshade + import

**Files:**
- Modify: `src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java`

**Interfaces:**
- Consumes: `SurfaceClassifier.isFeature(BlockState)` (Task 1); existing `MiniatureBlockStateCodec.decode(String)`.
- Produces: a `groundHeight` map (built from non-feature entries) that Task 4's tree branch and the existing ground shading both read. This task only changes the height lookup + capacity + import; tree *rendering* is added in Task 4.

- [ ] **Step 1: Add the classifier import**

In `WorldMapGeometry.java`, directly under the existing line:

```java
import com.sanhiruzu.zendiorama.core.MiniatureSnapshot;
```

add:

```java
import com.sanhiruzu.zendiorama.core.SurfaceClassifier;
```

- [ ] **Step 2: Enlarge the buffer capacity estimate**

Replace:

```java
        // 5 faces × 4 vertices × 16 bytes (POSITION_COLOR) per voxel
        int capacity = snapshot.entries().size() * 5 * 4 * 16;
```

with:

```java
        // Up to 10 faces per entry (trees add a trunk box + canopy frustum);
        // ByteBufferBuilder grows automatically if this is exceeded.
        int capacity = snapshot.entries().size() * 10 * 4 * 16;
```

- [ ] **Step 3: Build the height lookup from ground entries only**

Replace:

```java
        Map<Long, Integer> heightMap = new java.util.HashMap<>(snapshot.entries().size() * 2);
        for (MiniatureSnapshot.Entry e : snapshot.entries()) {
            heightMap.put(xzKey(e.x(), e.z()), e.y());
        }
```

with:

```java
        // Ground-only height lookup: tree entries are excluded so the hillshade reflects
        // true terrain and tree bases can sit on the ground beneath them.
        Map<Long, Integer> groundHeight = new java.util.HashMap<>(snapshot.entries().size() * 2);
        for (MiniatureSnapshot.Entry e : snapshot.entries()) {
            if (!SurfaceClassifier.isFeature(MiniatureBlockStateCodec.decode(e.blockStateId()))) {
                groundHeight.put(xzKey(e.x(), e.z()), e.y());
            }
        }
```

- [ ] **Step 4: Point the ground shading at `groundHeight`**

In the AO block, replace the four lines:

```java
            if (heightMap.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (heightMap.getOrDefault(xzKey(entry.x() - 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (heightMap.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y()) > entry.y() + 1) taller++;
            if (heightMap.getOrDefault(xzKey(entry.x(), entry.z() - 1), entry.y()) > entry.y() + 1) taller++;
```

with:

```java
            if (groundHeight.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x() - 1, entry.z()), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y()) > entry.y() + 1) taller++;
            if (groundHeight.getOrDefault(xzKey(entry.x(), entry.z() - 1), entry.y()) > entry.y() + 1) taller++;
```

And in the slope block, replace:

```java
            int heightE = heightMap.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y());
            int heightS = heightMap.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y());
```

with:

```java
            int heightE = groundHeight.getOrDefault(xzKey(entry.x() + 1, entry.z()), entry.y());
            int heightS = groundHeight.getOrDefault(xzKey(entry.x(), entry.z() + 1), entry.y());
```

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`. (At this point trees still render as ground pillars — they are added next task. Hillshade is already ground-only, which on its own removes the tree-spiked terrain noise.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java
git commit -m "feat: build world map hillshade from ground entries only"
```

---

### Task 4: Renderer — draw tree entries as trunk + tapered canopy

**Files:**
- Modify: `src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java`

**Interfaces:**
- Consumes: `groundHeight` map, `xzCenter`, `xzScale`, `yNorm`, `minY` (all in scope inside `bake`'s loop from Task 3); `SurfaceClassifier.isFeature`; existing `quad(...)` helper.
- Produces: `renderTree(...)` and `box(...)` private helpers; tree entries no longer fall through to the ground pillar path.

- [ ] **Step 1: Branch tree entries before the ground path**

In the per-entry loop, immediately after the existing line:

```java
            if (mapColor == MapColor.NONE) continue;
```

insert:

```java
            // Trees/features render as a toy tree (trunk stub + tapered canopy) sitting on
            // the ground beneath them, rather than as a flat terrain pillar.
            if (SurfaceClassifier.isFeature(state)) {
                int fcol = entry.tint() != 0 ? entry.tint() : mapColor.col;
                int fr = (fcol >> 16) & 0xFF;
                int fg = (fcol >> 8) & 0xFF;
                int fb = fcol & 0xFF;
                float fx0 = (entry.x() - xzCenter) * xzScale + 0.5f;
                float fx1 = fx0 + xzScale;
                float fz0 = (entry.z() - xzCenter) * xzScale + 0.5f;
                float fz1 = fz0 + xzScale;
                int gY = groundHeight.getOrDefault(xzKey(entry.x(), entry.z()), entry.y());
                float gTop = (gY - minY) * yNorm + 0.064f + xzScale;
                float cTop = (entry.y() - minY) * yNorm + 0.064f + xzScale;
                renderTree(builder, fx0, fx1, fz0, fz1, gTop, cTop, fr, fg, fb);
                continue;
            }
```

- [ ] **Step 2: Add the `renderTree` and `box` helpers**

In `WorldMapGeometry.java`, add these two methods directly above the existing `private static long xzKey(int x, int z)` method:

```java
    /**
     * Draws a toy tree: a short brown trunk stub from the ground up, topped by a
     * tapered canopy (wide base, inset top) so clustered trees read as bumpy
     * varied-green forest rather than flat cubes. {@code cr,cg,cb} is the canopy
     * (foliage) color; the trunk uses a fixed brown.
     */
    private static void renderTree(BufferBuilder builder,
            float x0, float x1, float z0, float z1,
            float groundTop, float canopyTop,
            int cr, int cg, int cb) {
        float cx = (x0 + x1) * 0.5f;
        float cz = (z0 + z1) * 0.5f;
        float cell = x1 - x0;
        if (canopyTop <= groundTop) canopyTop = groundTop + cell;
        float trunkTop = groundTop + (canopyTop - groundTop) * 0.35f;

        // Trunk — a small brown box from the ground to the canopy underside.
        float tw = cell * 0.18f;
        box(builder, cx - tw, cx + tw, groundTop, trunkTop, cz - tw, cz + tw, 0x6E, 0x4A, 0x2B);

        // Canopy — a frustum: full-cell base tapering to an inset top quad.
        float bw = cell * 0.5f;
        float topHalf = cell * 0.14f;
        float bx0 = cx - bw, bx1 = cx + bw, bz0 = cz - bw, bz1 = cz + bw;
        float tx0 = cx - topHalf, tx1 = cx + topHalf, tz0 = cz - topHalf, tz1 = cz + topHalf;
        float y0 = trunkTop, y1 = canopyTop;

        quad(builder, cr, cg, cb, BRIGHT, tx0, y1, tz0, tx0, y1, tz1, tx1, y1, tz1, tx1, y1, tz0); // top
        quad(builder, cr, cg, cb, MEDIUM, bx0, y0, bz1, bx1, y0, bz1, tx1, y1, tz1, tx0, y1, tz1); // +Z
        quad(builder, cr, cg, cb, DARK,   bx1, y0, bz0, bx0, y0, bz0, tx0, y1, tz0, tx1, y1, tz0); // -Z
        quad(builder, cr, cg, cb, MEDIUM, bx1, y0, bz1, bx1, y0, bz0, tx1, y1, tz0, tx1, y1, tz1); // +X
        quad(builder, cr, cg, cb, DARK,   bx0, y0, bz0, bx0, y0, bz1, tx0, y1, tz1, tx0, y1, tz0); // -X
    }

    /** Draws an axis-aligned box (top + 4 sides; bottom omitted as it is never seen). */
    private static void box(BufferBuilder b,
            float x0, float x1, float y0, float y1, float z0, float z1,
            int r, int g, int bl) {
        quad(b, r, g, bl, BRIGHT, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // top
        quad(b, r, g, bl, MEDIUM, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // +Z
        quad(b, r, g, bl, DARK,   x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0); // -Z
        quad(b, r, g, bl, MEDIUM, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1); // +X
        quad(b, r, g, bl, DARK,   x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // -X
    }
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run existing tests (regression)**

Run: `.\gradlew.bat test`
Expected: `BUILD SUCCESSFUL` (unchanged — no test touches client geometry).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java
git commit -m "feat: render world map trees as trunk and tapered canopy"
```

---

### Task 5: Manual in-game verification

**Files:** none (verification only).

This is the behavioral gate that the unit harness cannot cover.

- [ ] **Step 1: Launch the client**

Run: `.\gradlew.bat runClient`
Expected: client launches into the dev world.

- [ ] **Step 2: Configure a forested map**

Place a `world_map` block (or use an existing one) over a forested, hilly region with at least one lake. **Shift-right-click to (re)configure** so it re-samples with the new ground/tree split (old snapshots predate it).

- [ ] **Step 3: Verify the relief**

Confirm:
- Terrain **rolls** — hills and valleys read as smooth ground, not spiked by tree heights.
- **Forests** read as clusters of small trees (green canopy with brown trunk hints) standing above the ground, not as flat green pillars.
- **Water** still renders as a clean flat sheet.
- A **large multi-block map** still bakes and renders without obvious lag or missing tiles.

- [ ] **Step 4: Tuning pass (only if needed)**

If trees look too thin/fat or too tall/short, adjust the constants in `renderTree` (`tw` trunk width, `bw`/`topHalf` canopy taper, the `0.35f` trunk fraction) and re-run. Commit any tuning:

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java
git commit -m "tune: world map tree proportions"
```

---

## Notes for the implementer

- **Why no unit tests for the new code:** `SurfaceClassifier` uses `BlockTags`, the sampler uses `ServerLevel`/biomes, and the renderer uses GPU buffers — none can be constructed without a running Minecraft, and this repo's `test` task deliberately has no Minecraft runtime. The compile + existing-test-regression + manual-play loop is the established verification path here.
- **Backward compatibility:** old snapshots have only single top-surface entries with no ground entry beneath a tree. They still render sensibly — a leaf-topped entry classifies as a feature and draws a tree from the slab base (its ground lookup misses, falling back to its own height). Re-configuring re-samples for the real split. No migration code is required.
- **Out of scope (future spec):** structure/built-block highlighting, which would justify a stored `byte[]` role marker since "is this a build" is not inferable from a single block state.
```
