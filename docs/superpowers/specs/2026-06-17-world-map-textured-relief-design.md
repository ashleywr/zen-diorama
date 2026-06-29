# World Map — Textured Block Relief

**Date:** 2026-06-17
**Status:** Approved design, pending implementation plan
**Component:** `world_map` block render pipeline (client baking + a new pure relief planner)

## Problem

The `world_map` block renders terrain as flat `POSITION_COLOR` colored cubes
(`WorldMapGeometry`) — solid per-face colors, no textures, no real block shapes.
It looks markedly worse than (a) flying up in the actual world and (b) the mod's
own diorama *frame* miniature, which renders **real textured Minecraft block
models**.

The cause is purely that the two features use different renderers. The frame
miniature (`DioramaFrameRenderer` + `CachedBlockGeometry`) bakes real block
models via `blockRenderer.renderSingleBlock(...)` into per-render-type VBOs. The
world map uses the separate, cruder flat-cube renderer.

The user wants the world map to keep its **walk-around 3D elevation** (not a flat
top-down photo) but **look much better** — real textures are welcome; photoreal
is not required.

## Goal

Render the world map as a **real textured block relief**, reusing the frame
miniature's rendering technology, fed a heightfield. Keep true 3D elevation.

Non-goals: top-down render-to-texture (rejected — user wants 3D, not a photo);
real multi-block tree structures with trunks (leaf bumps only); threaded/async
baking (v1 accepts a reconfigure hitch); dynamic world lighting on the map.

## Design

### Overview

Replace `WorldMapGeometry` with a real-block path that mirrors
`DioramaFrameRenderer`:

1. A **pure planner** (`core/WorldMapRelief`) turns the snapshot into a flat list
   of block **placements** `(x, y, z, blockStateId)` in an integer cubic-block
   grid — ground, skirts, and leaf bumps. No Minecraft-client dependencies; unit
   testable.
2. A **client baker** iterates the placements, applies the map transform, and
   calls `renderSingleBlock` into the existing `CachedBlockGeometry`.
3. `WorldMapRenderer` bakes/draws through `CachedBlockGeometry` (same cached path
   the frame already uses).

The recent ground/tree sampling work is retained — it provides exactly the data
the planner needs (ground heights for skirts, tree entries for bumps).

### 1. Pure relief planner — `core/WorldMapRelief`

```java
public record Placement(int x, int y, int z, String blockStateId) {}

public static List<Placement> plan(
        List<MiniatureSnapshot.Entry> entries,
        int heightFactor,
        Predicate<MiniatureSnapshot.Entry> isTree);
```

`heightFactor` is an integer ≥ 1 (the vertical exaggeration; see §4).
`isTree` is injected so the planner stays free of Minecraft-runtime block
decoding and is unit-testable: the client passes
`e -> SurfaceClassifier.isFeature(MiniatureBlockStateCodec.decode(e.blockStateId()))`,
while unit tests pass a trivial fake (e.g. id contains `"leaves"`).

Algorithm:

- Split entries into **ground** (`!isTree`) and **tree** (`isTree`).
- Compute `minY` = min ground `y`. Define `gridY(entryY) = (entryY - minY) * heightFactor`.
- Build a ground-height map `(x,z) -> entryY`.
- **Ground placement:** for each ground entry, one placement at
  `(x, gridY(entryY), z)` with the ground block state.
- **Skirts:** for each ground column, fill placements of the **same ground
  block** from `gridY(entryY) - 1` down to `gridY(minNeighborY)` (the lowest of
  its 4 cardinal ground neighbors). Columns missing a neighbor (map edge) fill
  down to `0`. This makes slopes/cliffs solid with no see-through gaps.
- **Leaf bumps:** for each tree entry, fill placements of the **tree (leaf)
  block** from `groundGridTop + 1` up to `groundGridTop + treeHeight`, where
  `groundGridTop = gridY(groundY at that x,z)` and
  `treeHeight = clamp(canopyY - groundY, 1, MAX_TREE_BUMP)` — trees are placed at
  their *real* (un-exaggerated) height above the exaggerated ground, so they stay
  proportionate instead of becoming giant leaf towers. `MAX_TREE_BUMP = 6`.

The planner returns every block to draw; the client side does no relief logic.

### 2. Client baker — `client/WorldMapBlockBaker`

Replaces `WorldMapGeometry`. A small class with a static emitter method:

```java
static void emit(MultiBufferSource source, List<Placement> placements,
                 int blocksPerTile);
```

For each placement: push a `PoseStack`, apply the map transform (center in the
block; uniform scale `1f / blocksPerTile` on all axes so blocks stay **cubic**;
recenter the grid), translate to `(x, y, z)`, and
`blockRenderer.renderSingleBlock(decode(blockStateId), poseStack, source,
fullBrightLight, OverlayTexture.NO_OVERLAY)`. Skip `air` and non-`MODEL`
render-shape states (same guard the frame baker uses).

Lighting: baked at full brightness (`LightTexture.pack(15, 15)`), consistent with
the frame's cached path; this keeps the VBO cache valid (light is baked into
vertices).

### 3. Renderer wiring — `WorldMapRenderer`

Mirror `DioramaFrameRenderer`'s cached path:

- Cache key = snapshot instance; flags = `heightFactor` (folded in, since it is
  baked into geometry).
- If `blockEntity.renderCache` is a `CachedBlockGeometry` matching key+flags,
  `draw` it. Otherwise close the stale cache, `CachedBlockGeometry.bake(snapshot,
  flags, src -> WorldMapBlockBaker.emit(src, WorldMapRelief.plan(...), blocksPerTile))`,
  store, draw.
- `WorldMapBlockEntity.renderCache` already typed as `Object`; no change needed
  beyond it now holding a `CachedBlockGeometry` instead of a `WorldMapGeometry`.

### 4. Height exaggeration as cubic stacking — `DioramaConfig`

Stretching Y would smear block textures. Instead, exaggeration is an **integer
vertical multiplier in cubic-block units**: `gridY = (entryY - minY) * heightFactor`,
with skirts filling the taller columns. Blocks stay cubic; elevation still pops.

`MAP_HEIGHT_EXAGGERATION` (currently a double 0.1–16.0) is reinterpreted as this
factor via `Math.max(1, Math.round(value))`. Default value changed to `2.0`.
(`MAP_MAX_BLOCKS` is unchanged; it still bounds sampled entries, not placements.)

### 5. Removal

Delete `client/WorldMapGeometry.java` — fully replaced.

## Performance

Real block models are ~10–50× the vertices of flat cubes. Consequences and the
v1 stance:

- **Bake cost:** a reconfigure re-bakes a tile's placements on the render thread
  (as the frame already does). A single 48-tile is fine; a large multi-tile map
  may produce a **visible hitch on (re)configure**. Per-frame draw stays cheap
  (VBO replay). v1 accepts this; baking is one-shot per snapshot change.
- **Geometry volume:** skirts and exaggeration multiply block counts, heaviest at
  map edges (skirt to base) and high `heightFactor`. Bounded by terrain range ×
  factor. v1 keeps the existing per-tile sample budget; no async baking, no
  level-of-detail (explicit YAGNI — revisit only if large maps hitch badly).
- **VRAM:** one set of per-render-type VBOs per tile; scales with map size.

If big wall-sized maps become a problem, the follow-up lever is a config toggle
between this detailed renderer and a simple one, or threaded baking — out of
scope here.

## Affected files

| File | Change |
|---|---|
| `core/WorldMapRelief.java` | **new** — pure planner: snapshot → `List<Placement>` (ground + skirts + leaf bumps) |
| `client/WorldMapBlockBaker.java` | **new** — emits placements via `renderSingleBlock` into a `MultiBufferSource` |
| `client/WorldMapRenderer.java` | bake/draw via `CachedBlockGeometry` + the planner/baker (mirrors frame renderer) |
| `client/WorldMapGeometry.java` | **deleted** — replaced |
| `DioramaConfig.java` | `MAP_HEIGHT_EXAGGERATION` reinterpreted as integer factor; default 2.0 |

`CachedBlockGeometry`, `SurfaceSampler`, `SurfaceClassifier`, `MiniatureSnapshot`,
and `MiniatureSnapshotNbt` are unchanged.

## Testing

- **Unit (pure JUnit, no Minecraft runtime) — `WorldMapRelief.plan`:**
  - Flat 2×2 ground (equal heights) → one placement per column, no skirts.
  - A step (one column higher than neighbors) → taller column gets skirt
    placements filling down to the lower neighbor height.
  - Map-edge column → skirt fills down to `0`.
  - `heightFactor = 2` → ground grid heights doubled; skirt counts scale.
  - A tree entry → leaf-block placements from ground+1 up to `clamp(canopy-ground,
    1, 6)`, positioned on top of the (exaggerated) ground, not exaggerated
    themselves.
  - Placements reference the correct block state ids (ground id for ground/skirt,
    leaf id for bumps).
- **In-game (manual):** configure a forested, hilly region with water; verify real
  grass/dirt/leaf textures, solid cliff sides (no see-through), forests as
  textured leafy bumps, water as its block, elevation reads clearly, and a
  multi-tile map bakes without unacceptable hitching.

## Notes

- Skirt fill uses the surface block for simplicity (e.g. grass-block sides read as
  dirt-with-fringe). "Dirt under grass" for cleaner cliff faces is a possible
  later refinement, deliberately omitted from v1 to keep the planner pure and
  simple.
- Tree bumps are leaf-only (no trunks), per the chosen direction.
