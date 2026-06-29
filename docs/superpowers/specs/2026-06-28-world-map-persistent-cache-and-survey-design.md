# World Map Persistent Cache and Survey Design

**Date:** 2026-06-28
**Status:** Draft design
**Component:** `world_map` persistence, sampling, network sync, and refresh gameplay

## Goal

Make world maps behave like durable, partially explored artifacts instead of volatile render caches.

After a player rejoins or a server restarts, a configured world map should show its last known snapshot immediately. Missing areas should remain visibly incomplete until the player explores, refreshes, or pays for a survey action that loads and samples the missing region.

## Problem

The current `world_map` block stores configuration in block entity NBT, but treats the sampled map contents as runtime cache:

- `WorldMapBlockEntity.snapshot` is in memory only.
- Client VBOs are transient render caches.
- `saveAdditional()` intentionally writes `Dirty=true` and does not persist the snapshot.
- On restart or first chunk watch, the server must resample from currently loaded chunks.
- Low render distance can produce partial snapshots, and repeated dirty refreshes can cause visible blinking/rebuilds.

This makes a large map feel unstable: the player already made the map, but it forgets what it knew.

## Desired Behavior

- World maps persist their sampled contents across server restarts.
- Joining clients receive the cached map immediately when the block comes into view.
- Partial maps are valid: unknown areas can be blank, stone-backed, or otherwise visibly unexplored.
- Maps do not poll forever for chunks outside the active server loading radius.
- Explicit refresh actions resample from currently loaded chunks.
- A paid survey action can deliberately load more chunks and fill missing areas.
- Large maps remain bounded by batching, compression, and visibility-aware network sync.

## Vanilla Map Lessons

Vanilla maps are a useful model:

- Map image data lives in `SavedData`, not in item/block NBT.
- The stored image is compact: fixed-size color indices, not full block state strings.
- The client caches a texture, but the server-side saved data is canonical.
- Maps support partial discovery; unexplored pixels stay blank.
- Updates are incremental and dirty-region based.

World maps need richer data than vanilla maps: height, material, biome tint, terrain/feature distinction, and 3D relief. The architecture should still follow the same principle: durable compact server data plus client-side render cache.

## Data Model

### Persistent map record

Introduce persistent world-map saved data, separate from block entity NBT:

```text
WorldMapSavedData
  mapId -> WorldMapRecord

WorldMapRecord
  version
  configHash
  footprint
  tileLayout
  tiles
  createdGameTime
  updatedGameTime
```

The block entity stores only:

- stable `mapId`
- current config: center, scale, zoom, style, sampler resolution, tile layout
- state flags: valid layout, surveying, last known completion

The saved data stores the sampled snapshot contents.

### Tile records

Store map contents by tile so large groups can stream and invalidate independently:

```text
WorldMapTileRecord
  tileX
  tileZ
  sampledGridSize
  completionMask
  palette
  columns
```

`completionMask` marks which sampled columns are known. Unknown columns are rendered as blank/backing. This avoids conflating "air/water/flat terrain" with "not sampled yet."

### Compact column encoding

Avoid storing `MiniatureSnapshot.Entry` lists with repeated block state strings.

Use a palette:

```text
palette[paletteId] = blockStateId
column:
  x
  z
  groundY
  groundPaletteId
  groundTint
  optional featureY
  optional featurePaletteId
  optional featureTint
```

This matches the current ground + feature sampling model while being much smaller than string-heavy payloads. It also leaves room for future prominent-object or textured-relief data.

## Sampling Semantics

### Normal refresh

Normal refresh samples only chunks that are already loaded and ready:

- If a sampled column's chunk is loaded, update that column.
- If not, leave the previous cached value in place.
- If no previous value exists, leave the column unknown.
- Do not keep the block entity dirty just because unknown columns remain.

This makes partial maps stable and avoids low-render-distance polling loops.

### Explicit refresh

Right-click refresh, compass recenter, zoom changes, and commands resample the affected tile/group from currently loaded chunks. They do not force far chunks to load.

Config changes that change grid layout or scale should create a new cache generation or clear incompatible tile data.

### Paid survey

Right-click a world map with a configured item, initially gold ingot:

- Consume the item only if a survey can start.
- Queue a survey job for the connected map group.
- Load/generate the chunks needed for the map footprint in batches.
- Sample columns as chunks become available.
- Persist tile records as they complete.
- Release any temporary chunk tickets when done or canceled.

The survey is explicit, visible, and bounded. It is the intended way to fill areas beyond normal render distance.

## Survey Cost

V1 can use a simple cost:

- 1 gold ingot per connected map group survey.

If testing shows large maps are too cheap, scale cost by zoom or tile count:

```text
cost = max(1, ceil(tileCount / 8)) gold ingots
```

Do not make normal partial refresh cost anything. The paid action should represent deliberate long-range surveying, not ordinary map use.

## Survey Job Design

Add a server-side job manager:

```text
WorldMapSurveyManager
  activeJobsByMapId
  queueSurvey(group, player, payment)
  tick(server)
```

Each job contains:

- map id and config generation
- target tile positions
- required chunk positions
- pending chunk queue
- loaded chunk tickets
- per-tick budget
- timeout/cancel state

### Batching

Survey must not synchronously load a huge rectangle in one tick.

Suggested defaults:

- request/load at most 4-8 chunks per tick
- sample at most 1-2 map tiles per tick once chunks are ready
- cap total active survey jobs per server
- cap total temporary chunk tickets per job

The exact values should be config-backed after profiling.

### Cancellation

Cancel a survey if:

- the map block/group is removed or becomes invalid
- the config generation changes
- the server stops
- the job exceeds a timeout

Always release temporary chunk tickets.

## Network Sync

### Initial watch

When a player starts watching the chunk containing a world-map block:

1. Send block entity config/update packet.
2. Send cached tile snapshot payloads for visible tiles.
3. Client builds geometry from cached data.

This replaces "resample before the player sees anything" with "send what the map already knows."

### Payload format

Replace or extend `WorldMapSnapshotPayload` with a palette-encoded tile payload:

```text
WorldMapTileSnapshotPayload
  blockPos
  mapId
  configHash
  tileX
  tileZ
  sampledGridSize
  completionMask
  palette
  encodedColumns
```

For large maps, split payloads by tile. Avoid one huge group payload.

### Deltas

V1 can send whole updated tiles when they change. Later, add dirty-rectangle or dirty-column deltas like vanilla maps.

## Client Rendering

Client rendering remains cache-based:

- Decode tile payload into a client `MiniatureSnapshot`-like structure.
- Bake VBO geometry as today.
- Cache VBOs per tile/config/LOD.
- Render unknown columns as absent terrain, showing the map backing.

Client-side caches are disposable. The durable source of truth is the server saved data.

## Block Entity Responsibilities

`WorldMapBlockEntity` should shrink back toward configuration and visibility:

- owns map id and presentation layout
- validates connected group layout
- handles player interactions
- asks saved data for current tile records
- sends cached data on watch/update
- queues refresh/survey jobs

It should not own the durable snapshot contents.

## Persistence Strategy

Use `SavedData` for the first implementation. If records become too large, move payload blobs to region-like cache files with `SavedData` holding only an index.

Do not store large map snapshots in block entity NBT. Chunk NBT and block-entity update packets are the wrong place for multi-tile map image data.

## Staleness and Invalidations

V1 invalidation is explicit:

- player refresh
- compass recenter
- zoom/style/config change
- paid survey

Do not attempt automatic block-change invalidation over huge map footprints in v1. Tracking every affected block/chunk would be complex and could become more expensive than the map itself.

Later, optional chunk-level staleness can be added:

- record source chunk timestamps or sampled chunk versions
- mark impacted map columns stale when a watched chunk changes
- update only stale columns during refresh

## UX States

World map tiles can be in these states:

- `empty`: configured but no cached known columns
- `partial`: some known columns, some unknown
- `complete`: all sampled columns known for current config
- `surveying`: paid survey is actively filling data
- `stale`: cached data exists but config/source marker suggests it may be out of date

Rendering should make `partial` and `surveying` readable without blinking:

- known columns render normally
- unknown columns show backing
- surveying may use subtle particles/sound or a small status message
- avoid per-frame brightness pulsing for stable partial maps

## Performance Expectations

Caching helps most with:

- instant join/restart display
- avoiding repeated resampling
- avoiding low-render-distance polling
- reducing server CPU during idle

Caching does not eliminate:

- first-time survey cost
- network cost to send large cached maps
- client GPU cost when many large maps are visible
- memory cost of server/client caches

An 8x8 group at 128 voxels per tile is roughly one million sampled columns before feature entries. This is practical only with palette compression, per-tile streaming, and no automatic constant refresh.

## Migration

Existing maps have configuration but no persistent cache. On first load after this feature:

- assign a map id if missing
- create empty saved-data records
- do not auto-survey
- allow normal loaded-chunk refresh to populate nearby areas
- allow gold survey to fill the full footprint

## Non-Goals

- Perfect automatic live updating for every block change in the sampled world.
- Free forced loading of huge map footprints on login.
- Storing large snapshots in block entity NBT.
- Client-only durable cache as the canonical map source.
- A full replacement of the current relief renderer.

## Open Questions

- Should the paid survey cost scale by group size, zoom tier, or both?
- Should normal right-click refresh overwrite known columns with current loaded data, or only fill unknown columns?
- What is the best unknown-column visual: bare backing, dim grid cells, or a special "unexplored" material?
- Should survey jobs load generated chunks only, or generate missing chunks too?
- What per-tick chunk and tile budgets feel smooth on low-end servers?
