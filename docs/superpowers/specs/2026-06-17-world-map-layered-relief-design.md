# World Map — Layered Relief Rendering

**Date:** 2026-06-17
**Status:** Approved design, pending implementation plan
**Component:** `world_map` block render pipeline (sampling → snapshot → baked geometry)

## Problem

The `world_map` block renders a 3D relief of an overworld region. It currently
samples only the single top block of each column (`Heightmap.Types.WORLD_SURFACE`)
and draws a flat-topped colored pillar from the base up to that height.

Two consequences:

1. **Trees pollute the terrain.** A tree's leaf height is sampled as the column
   height, so forests render as lumpy flat-topped green pillars and the
   slope/AO hillshade — built from those same heights — is spiky and noisy.
   The underlying ground cannot roll.
2. **No feature distinction.** Trees, terrain, and (eventually) structures all
   render identically as colored pillars.

Horizontal resolution is *not* the problem: at the default `blocksPerTile = 48`,
each placed map block covers a 48×48 world-block area and renders every one of
those 2304 columns as its own voxel (under the 4096 budget, so nothing is
downsampled). It is already 1 voxel per world block. The lever for "more detail"
is therefore **vertical / feature** detail, not horizontal.

## Goal

Separate **ground relief** from **features** so that:

- terrain undulates cleanly (hillshade computed from true ground height), and
- trees render as distinct toy-diorama shapes (tapered canopy on a trunk stub),

while keeping the look the user has converged on ("toy-like", clean flat
surfaces, biome-tinted, flat water sheets).

Non-goals (this spec): structure highlighting (stretch — see end),
full volumetric multi-layer sampling (YAGNI), horizontal resolution changes.

## Design

### 1. Data model — `MiniatureSnapshot.Entry`

Each column can now emit **up to two entries**: a ground entry and, when
vegetation sits on it, a tree entry.

**No new field.** A voxel's role (ground vs. tree) is *derived from its block
state*, not stored — a tree entry's block state is already leaves/log, a ground
entry's is grass/dirt/stone/etc. The renderer and sampler both decode the state
anyway, so classification is free and the block state is the single source of
truth. `Entry` keeps its current shape `(x, y, z, blockStateId, tint)`.

`y` remains "surface height relative to sea level" for both kinds: a ground
entry's `y` is the ground top; a tree entry's `y` is the canopy top.

A shared classifier lives in `core/` so the sampler and renderer can never drift:

```java
// core/SurfaceClassifier.java (no client-only deps; BlockState is common)
public static boolean isFeature(BlockState state); // leaves OR logs → tree feature
```

### 2. NBT — `MiniatureSnapshotNbt`

**No change.** The existing format (palette + packed position `int[]` + state
`int[]` + optional tint `int[]`) already carries everything; role is recomputed
on read via `SurfaceClassifier`. Pre-existing snapshots load and render exactly
as before until the map is re-configured to pick up the new ground/tree split.

Note: a column may now emit two entries sharing the same `(x, z)` at different
`y`. The position packer already distinguishes them by `y`, so no collision.

### 3. Sampling — `SurfaceSampler`

Per column `(x, z)`:

1. **Find ground.** Start at `WORLD_SURFACE - 1` and scan downward, skipping
   blocks classified as *vegetation* (leaves, logs, plants/saplings, snow layer,
   tall grass/ferns, vines, sugar cane, etc.) until the first real terrain block
   (grass/dirt/stone/sand/gravel/terracotta/etc. — anything not in the skip set).
   That block + height → the **ground** entry, with biome tint as today.
   - Guard the scan with a floor (`level.getMinBuildHeight()`); if nothing solid
     is found, fall back to the original `WORLD_SURFACE` block as ground.
2. **Detect feature.** If one or more skipped blocks (leaves/logs) sat above the
   ground in that column, emit a **tree** entry:
   - `y` = canopy top height (the original `WORLD_SURFACE - 1`),
   - block state = the canopy block (leaves/log), so it classifies as a feature
     via `SurfaceClassifier.isFeature` on read,
   - tint = foliage tint (existing `computeTint` foliage branch).
   - Only leaves/logs trigger a tree entry; loose plants (grass, flowers, snow)
     do **not** — they stay part of the ground color so we don't get a tree
     entry for every grassy block.

Water handling is unchanged: a water-topped column produces a single ground-kind
entry with the water tint (renderer still treats water as a flat sheet).

`computeTint` is reused unchanged for the color of each emitted entry.

### 4. Budget — `DioramaConfig`

Raise `MAP_MAX_BLOCKS` from `4096` to `16384`. A fully-forested 48×48 tile emits
up to ~2× entries (ground + tree ≈ 4608); the higher budget ensures the
stride-downsampler effectively never fires. Worst case ~16384 entries × ~12 bytes
≈ 200 KB packed — well under the block-entity update-packet limit.

The existing `SnapshotSampler` stride decimator is a poor 2D downsampler; this
spec sidesteps it by raising the budget rather than reworking it. Proper 2D
decimation is noted as a future cleanup, out of scope here.

### 5. Rendering — `WorldMapGeometry`

- **Hillshade from ground only.** Build the slope/AO height lookup from
  non-feature (ground) entries exclusively — i.e. skip entries where
  `SurfaceClassifier.isFeature(state)`. Tree heights no longer spike the terrain,
  so the relief reads as clean rolling ground.
- **Ground entries** (`!isFeature`) render as today's pillars (base → ground
  height) with the cleaner hillshade, biome tint, AO, slope shading; water stays
  a flat sheet.
- **Tree entries** (`isFeature`) render as a toy tree:
  - base = ground height at that `(x, z)` (looked up from the ground height map;
    fall back to the tree entry's own base if absent),
  - a short **trunk stub** in a trunk-brown color from the ground up a small
    fraction of the canopy,
  - a **tapered canopy** from the trunk top to `y`, with the **top face inset**
    (smaller than the base) so the silhouette reads rounded rather than as a flat
    cube, in the foliage tint.
  - At 1/48-block voxel width an individual tree is tiny; clustered forests are
    expected to read as bumpy, varied-green canopy with brown hints — the
    intended toy-forest look.

### 6. Backward compatibility

The cost of deriving role from block state (vs. a stored flag) is that **old
snapshots can't be interpreted perfectly.** A pre-existing forest column is a
single leaf-topped entry with *no separate ground entry beneath it*. On read it
now classifies as a feature, so it renders as a tree — but with no ground entry
at that `(x, z)`, the tree base falls back to the entry's own height, so it grows
from the slab base as before. Net effect: old maps still render sensibly (forests
get the tapered-canopy look instead of flat pillars), just not byte-identical to
the previous build.

Users re-configure (shift-right-click) an existing map to re-sample with true
ground/tree separation. This matches the existing requirement to re-configure for
biome tints. Re-configuring is the supported path; old-data rendering only needs
to look reasonable, not pixel-match.

## Affected files

| File | Change |
|---|---|
| `core/SurfaceClassifier.java` | **new** — shared `isFeature(BlockState)` predicate (leaves/logs) |
| `core/SurfaceSampler.java` | ground scan + feature detection; emit 1–2 entries/column |
| `DioramaConfig.java` | `MAP_MAX_BLOCKS` 4096 → 16384 |
| `client/WorldMapGeometry.java` | ground-only hillshade (skip features); tree shape rendering |

`MiniatureSnapshot.java` and `MiniatureSnapshotNbt.java` are unchanged.

## Testing

- **Unit (pure JUnit, no Minecraft runtime):**
  - `SurfaceClassifier.isFeature` returns true for leaves/logs, false for
    grass/dirt/stone/water. (Uses real `Blocks` constants — these are available
    without a full Minecraft runtime; if any test needs bootstrapping, keep the
    classifier logic table-driven so it can be unit-tested in isolation.)
  - `MiniatureSnapshotNbt` round-trip is unchanged — verify the existing tests
    still pass (no format change).
  - `SnapshotSampler` budget behavior unchanged at the new limit.
- **In-game (manual):** configure a forested region; verify terrain rolls,
  forests read as tree clusters with brown trunk hints, water stays flat, and a
  large multi-block map performs acceptably.

## Stretch (future spec)

Structure/built-block classification so villages and player builds stand out
(distinct palette + slight height pop). Unlike ground/tree, "is this a build"
isn't reliably inferable from a single block state (a dirt house is still dirt),
so *this* is the point at which storing an explicit role marker becomes
worthwhile — a right-sized `byte[]` array in the NBT (written only when present),
not bit-packing.
