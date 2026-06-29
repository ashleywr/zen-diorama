# World Map Prominent Objects Design

## Goal

Make prominent above-ground structures on the world map read like miniature objects instead of terrain bumps, while keeping terrain, water, and clutter readable at the current map scale.

## Problem

The current world map renderer treats nearly everything as terrain relief plus occasional feature columns. That works for hills and forests, but villages, ruins, cliffs with hard faces, and other discrete landmarks collapse into the same visual language as surrounding terrain. The result is a map that reads topographically but does not sell the "miniature diorama" look for major objects.

## Desired Behavior

- Terrain remains a smoothed, locally enhanced relief surface.
- Water stays mostly flat as a reference plane.
- Prominent above-ground structures are rendered as clearer miniature masses.
- Skinny clutter such as posts, poles, and one-column spikes is ignored.
- Detection is generic and should work for modded blocks without hardcoded vanilla block lists.
- Coarser LoDs preserve major landmarks but continue suppressing small noisy details.

## Constraints

- Computation must stay cheap enough for the existing bake-and-cache pipeline.
- Detection should work from sampled column data, not require an expensive raw-block reconstruction pass.
- Classification must rely on generic geometry signals rather than mod ID or hand-maintained block families.
- False positives on ordinary terrain ridges should be minimized.

## Approach

### 1. Build a column-space prominence mask

Work from the already-sampled snapshot grid. For each sampled column:

- Identify its ground height from the terrain entry.
- Measure local prominence against nearby ground neighbors.
- Measure whether the column participates in a locally raised mass rather than a gentle terrain slope.

Columns only qualify as object candidates when they exceed a minimum prominence threshold.

### 2. Group candidate columns into connected regions

Perform a 2D flood-fill over candidate columns. For each region, compute:

- footprint area
- X span
- Z span
- max prominence over local ground
- average prominence

Reject regions that are too small or too thin in either axis. This filters out poles, fences, and other skinny clutter even if they are tall.

### 3. Render accepted regions with object shaping

Accepted regions stay in the same snapshot/geometry pipeline, but their columns use object-oriented shaping rules:

- walls and masses read more upright than terrain
- roof mass is visually flatter and less noisy
- total height contribution is capped so structures remain toy-like rather than spiky

The object pass is still simplified geometry, not a literal reconstruction of every sampled block.

### 4. Keep LoD behavior compatible

The near LoD should retain the most object detail. Coarser LoDs should:

- preserve the existence and footprint of major landmarks
- simplify roof and wall variation
- merge away small regions below the object thresholds

This keeps the far view calm while preserving recognizable settlement structure.

## Architecture

### Core logic

Add a pure helper in `core/` that:

- inspects a sampled `MiniatureSnapshot`
- identifies candidate object columns from local prominence
- groups them into accepted/rejected connected regions
- exposes a cheap mask or lookup keyed by sampled `x,z`

This keeps classification testable without rendering dependencies.

### Client geometry

Extend `WorldMapGeometry` so it can ask whether a sampled column belongs to an accepted object region. If yes, it applies the object shaping rules instead of plain terrain shaping.

The existing `WorldMapLodCache` remains responsible for distance-based geometry selection. Object detection can run against each LoD snapshot independently, or against the snapshot currently being baked for that LoD.

## Error Handling and Edge Cases

- Missing neighbors fall back to the current column height when computing local prominence.
- Sparse sampled data should degrade gracefully to "no detected objects".
- Forest canopies should not become object regions unless they form a broad, prominent mass that also survives the footprint filters.
- Cliffs may be accepted when they read as a prominent raised mass; gentle hills should remain terrain.

## Testing

Add pure unit tests covering:

- a house-like plateau becomes an object region
- a long thin line does not become an object region
- a single tall spike does not become an object region
- a gentle terrain bump does not become an object region
- a broad raised structure remains detectable after LoD reduction

Client verification should remain compile-based in automation, with in-game visual validation required afterward.

## Scope

This design only adds generic prominent-object detection and shaping for the world map renderer. It does not attempt:

- exact building reconstruction
- interior recovery
- hardcoded structure type recognition
- biome- or mod-specific object rules
