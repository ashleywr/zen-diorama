# World Map Diorama — Solid 3D Render (Design)

**Date:** 2026-06-17
**Status:** Approved

## Problem

The world map block is meant to display a 3D terrain miniature ("diorama map") of a region. Two defects make it unusable:

1. **Severe lag.** Both `WorldMapRenderer` and `DioramaFrameRenderer` re-tessellated every snapshot block via `BlockRenderDispatcher.renderSingleBlock` *every frame*. A 256×256 world map is up to 65,536 surface blocks → ~65k model bakes per frame. No exception, pure throughput collapse.
2. **Crash on configure.** The snapshot was serialized as one `CompoundTag` (three named ints + a block-state string) per entry. For ~65k entries this exceeds the 2 MB block-entity update-packet limit → `NbtAccounterException`.

A first attempt at VBO caching also introduced a regression: the cached geometry renders **nothing**.

## Goal

A persistent, performant **solid-block 3D terrain miniature** on the world-map block: real block geometry, full-bright (tabletop-model look), baked once to a GPU vertex buffer and replayed each frame.

Non-goals (explicitly dropped): flat cartography texture, distance LOD / flat→voxel morph, hologram/translucent shader.

## Approach

**Bake once, replay cheaply.** Block models are expensive to tessellate, so the geometry for a snapshot is compiled a single time into one `VertexBuffer` per `RenderType`, keyed by the snapshot instance, and replayed each frame at near-zero cost. This is the standard technique vanilla uses for chunk sections.

### Components

| Component | Responsibility | Status |
|---|---|---|
| `block/MiniatureSnapshotNbt` | Compact NBT (de)serialization: string palette + packed-XYZ `int[]` + palette-index `int[]`. ~10× smaller; keeps a legacy reader for old saves. | Done (crash fix) |
| `client/CachedBlockGeometry` | Bakes emitted block geometry into per-`RenderType` VBOs; `matches(key, flags)` for invalidation; `draw(modelView)` replays them. | Done; **renders nothing — to fix** |
| `WorldMapRenderer` | Bakes on first render / snapshot change, then `cache.draw(...)`. | Done; depends on the fix |
| `DioramaFrameRenderer` | Same cached path (full-bright), immediate-mode fallback when caching disabled. | Done; depends on the fix |
| `block/WorldMapBlockEntity`, `block/DioramaFrameBlockEntity` | Hold a client-only `transient Object renderCache`; close it in `setRemoved()` to avoid GPU leaks. | Done |

### The fix (the only part needing in-game iteration)

The cached VBO draws nothing. Before changing code, gather evidence: add a **one-shot diagnostic log** in `CachedBlockGeometry.bake` recording entries emitted, number of `RenderType` buffers built, and total vertices. One in-game run then tells us which case we're in:

- **Bake problem** (zero buffers / zero vertices): the capturing `MultiBufferSource` / `BufferBuilder` setup is wrong; nothing is being captured.
- **Draw problem** (buffers exist, still invisible): the model-view matrix passed to `drawWithShader`, render-state setup, or shader selection is wrong.

Fix the identified cause, then remove the diagnostic.

## Data flow

1. Server samples the overworld surface (`SurfaceSampler`) → `MiniatureSnapshot` (capped at `MAP_MAX_BLOCKS`).
2. Snapshot persisted/synced via `MiniatureSnapshotNbt` (compact) on the block-entity update packet.
3. Client renderer detects a new snapshot instance, bakes it into `CachedBlockGeometry`, caches it on the block entity.
4. Each frame: `cache.draw(poseStack.last().pose())`.
5. Block removed → `setRemoved()` closes the VBOs.

## Constraints / notes

- Full brightness is baked into the geometry, so the cache is valid regardless of world lighting. The frame renderer only uses the cache when `miniatureVboCache && miniatureFullBright` (lighting is otherwise dynamic).
- `MAP_MAX_BLOCKS` default 65,536 is safe for the compact packet (~0.5 MB). Very high values (approaching the 262,144 max) could approach the 2 MB packet cap again; out of scope to guard now.

## Testing

GPU rendering cannot be unit-tested. Verification is: `gradlew compileJava` for type safety, then in-game (`runClient`) — configure a world map and confirm (a) the 3D terrain renders, (b) correct block colors/positions, (c) no lag, (d) no crash on configure.
