# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```powershell
# Run all unit tests (no Minecraft runtime needed)
.\gradlew.bat test

# Compile Java without launching Minecraft
.\gradlew.bat compileJava

# Launch Minecraft client in dev environment
.\gradlew.bat runClient

# Launch Minecraft server in dev environment
.\gradlew.bat runServer

# Run a single test class
.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.PlotAllocatorTest"
```

Unit tests in `src/test/` are pure JUnit 5 (no Minecraft runtime). All server/client code needs a real game environment — test only pure logic there.

## Code Intelligence

Prefer LSP over Grep/Glob/Read for code navigation:
- `goToDefinition` / `goToImplementation` to jump to source
- `findReferences` to see all usages across the codebase
- `workspaceSymbol` to find where something is defined
- `documentSymbol` to list all symbols in a file
- `hover` for type info without reading the file
- `incomingCalls` / `outgoingCalls` for call hierarchy

Before renaming or changing a function signature, use `findReferences` to find all call sites first.

Use Grep/Glob only for text/pattern searches (comments, strings, config values) where LSP doesn't help.

After writing or editing code, check LSP diagnostics before moving on. Fix any type errors or missing imports immediately.

## Architecture Overview

**Mod ID:** `zen_diorama` | **Target:** NeoForge 21.1.220 / Minecraft 1.21.1 / Java 21

### Core Concept

Players place a `diorama_frame` block in the overworld. Right-clicking it teleports them into a private 15×15×15 plot inside a single shared `zen_diorama:diorama` dimension. The interior appears vast; the exterior frame shows a miniature render of what's inside.

### Package Structure

| Package | Responsibility |
|---|---|
| `ZenDiorama` | Root `@Mod` class — all registry `DeferredRegister` declarations live here (`BLOCKS`, `ITEMS`, `BLOCK_ENTITY_TYPES`, `CREATIVE_MODE_TABS`), plus network payload registration |
| `ZenDioramaClient` | Client-only `@Mod` entry point (`dist = CLIENT`) — registers renderers, sky effects, HUD overlay, sound muffle, FOV modifier |
| `block/` | `DioramaFrameBlock` (entry/exit logic, plot setup, skybox sampling), `DioramaFrameBlockEntity` (NBT persistence: frame UUID, plot origin, dirty flag, snapshot), `DioramaExitBlock`, `DioramaControlBlock`, `DioramaFrameItem` |
| `core/` | Pure algorithms with no Minecraft dependencies — `PlotAllocator` (UUID→plot origin mapping), `SnapshotSampler` (downsampling to 4096-block budget), `MiniatureSnapshot`/`MiniatureBlockStateCodec`, `PlotOrigin` record |
| `server/` | `DioramaPlotSavedData` (world-persistent `SavedData`: UUID→`PlotRecord` with origin + frame world position), `DioramaReturnData` (player persistent data for exit teleport target), `DioramaPlayerVisibilityHandler` |
| `client/` | `DioramaFrameRenderer` (BlockEntityRenderer for miniature), `DioramaSkyboxRenderer` (6-face colored-quad skybox for diorama dimension), `DioramaSkyboxState`/`DioramaSkySnapshotState` (client-side snapshot data), `DioramaHudOverlay` (scale badge + input suppression during transition), `DioramaSoundMuffle`, `DioramaClientPayloadHandler` |
| `network/` | `DioramaTransitionPayload` (server→client: entering/exiting animation trigger), `DioramaSkySnapshotPayload` (server→client: 6-face block color arrays for skybox), `DioramaClientboundPayloadHandler` |
| `world/` | `DioramaDimensions` — `ResourceKey<Level>` constant for `zen_diorama:diorama` |
| `DioramaConfig` | NeoForge `ModConfigSpec` — `plotSize` (locked to 15), `plotSpacing`, `alwaysLoadedDefault`, `miniatureMaxBlocks`, `syncDebounceTicks`, `skyboxCaptureResolution`, `skyboxBlurRadius` |

### Key Data Flows

**Entry (right-click frame):**
1. Server: `DioramaFrameBlock.useWithoutItem` → `frame.ensureAssigned()` → `DioramaPlotSavedData.ensure()` allocates/retrieves plot origin
2. Server samples 6-face skybox colors from overworld blocks → sends `DioramaSkySnapshotPayload` to client
3. Server sends `DioramaTransitionPayload(true)` → client shows entry vignette
4. Server: `DioramaReturnData.store()` writes return position to player persistent NBT, then `player.teleportTo(dioramaLevel, ...)`
5. Server sets up barrier walls (`ensurePlotBoundary`), grass floor + exit/control blocks on first visit (`ensurePlotInterior`)

**Skybox (client-side):**
- `DioramaSkyboxState` holds 6×`Vec3[]` grids (8×8 per face) derived from the server snapshot
- `DioramaSkyboxRenderer` renders 6 quads-of-quads after sky stage in the diorama dimension only
- `DioramaSkyEffects` registers custom `DimensionSpecialEffects` to suppress vanilla sky/fog

**Miniature render:**
- `DioramaFrameBlockEntity` stores a `MiniatureSnapshot` (list of `Entry(x,y,z,blockStateId)`) in NBT
- `DioramaFrameBlockEntity.refreshSnapshotFromInterior()` walks the plot volume, excludes barrier/exit/control blocks, calls `SnapshotSampler.sample()` if over budget
- `DioramaFrameRenderer` (client `BlockEntityRenderer`) draws the snapshot scaled inside the frame bounding box

**Exit (step through `diorama_exit`):**
- `DioramaExitBlock` reads `DioramaReturnData` from player, teleports back to original dimension/position, forces a snapshot refresh on the frame entity

### Plot Allocation

Plots are laid out in a 1D strip along the X axis. Each plot gets origin `(index * stride, 0, 0)` where `stride = plotSize + spacing`. `DioramaPlotSavedData` (stored in overworld `DataStorage`) is the single source of truth; `PlotAllocator` in `core/` is the pure-logic equivalent used in unit tests.

### NeoForge Patterns Used

- `DeferredRegister` for blocks/items/block entity types/creative tabs — all registered in `ZenDiorama` constructor via `register(modEventBus)`
- `SavedData` subclass for server persistence (`DioramaPlotSavedData.get(level)` always fetches from overworld storage regardless of current level)
- `player.getPersistentData()` for per-player transient state (`DioramaReturnData`)
- Network payloads implement `CustomPacketPayload` with hand-written `StreamCodec`; registered via `RegisterPayloadHandlersEvent` with version `"1"`
- Client effects registered on `NeoForge.EVENT_BUS` in `ZenDioramaClient`; mod bus events (renderers, sky effects) use `modEventBus.addListener`
