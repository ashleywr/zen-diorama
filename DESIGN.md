# Diorama Mod — NeoForge 1.21.1 Implementation Spec

**Status:** Approved for end-to-end implementation  
**Last Updated:** 2026-06-15

---

## Vision

A cosy, solo-friendly NeoForge mod that lets players build miniature dioramas — tiny self-contained worlds visible as decorative blocks, which you can physically enter, build inside, and leave to see the exterior update. The magic is the scale shift: knowing the vast space you just built is secretly inside a block on your shelf.

---

## Architecture

### Dimension

- **Single shared dimension** for all dioramas (low overhead, no per-diorama registration)
- **Grid-allocated plots:** 15×15×15 interior volume, one plot per frame, with spacing between plots
- **Scale rule:** one interior block renders as exactly 1/16 of an exterior block; the 15×15 interior leaves a 1/16 exterior margin for the frame border
- **No nesting in v1:** Diorama frames cannot be placed inside a diorama interior.
- **Interior bootstrap:** bedrock support below a grass floor only; no decorative trees or natural terrain generation
- **Plot allocation:** Stored in `SavedData` on server; maps frame block UUID → plot origin
- **Chunk loading:** Chunks load on player entry, unload on exit; processes pause when unoccupied (intentional design)
- **Isolation:** Players should feel like they are small on the table inside their own frame, not in a shared flat world. Other plots must not be visible from inside.

### Frame Block & BlockEntity

- **Diorama Frame:** Placeable block, right-click to enter interior
- **BlockEntity storage:** Holds plot assignment, dirty flag, miniature snapshot (block-state schematic)
- **Snapshot:** Compact block-state array, max 4096 blocks for rendering budget

### Plot Entry Point

- **Exit Portal:** Automatically placed at interior spawn; walking through it returns player to frame's original location
- **Assignments:** One plot per frame block, persistent across restarts

---

## Features (Full Implementation)

### Phase 1: Entry & Exit

**Entry Flow:**
- Right-click frame → shrink animation plays → player teleports to plot interior
- Visual cues: vignette overlay, HUD badge showing "1:16 scale"
- Skybox shows a custom outside-room ambience captured from the frame's placement context

**Exit Flow:**
- Use the edge exit block → reverse shrink animation → return to original frame position
- Frame marked dirty, miniature render scheduled for update

**Implementation:**
- `ITeleporter` for custom teleport with animation hooks
- `BlockEntity` tracks entry/exit state
- Frame position stored for return teleport

### Phase 2: Miniature Render

**Exterior Miniature:**
- Block-state snapshot captured on exit and after debounced edits (stored in BlockEntity NBT)
- Client-side `BlockEntityRenderer` draws schematic scaled-down within frame bounding box
- Debounced updates: any block change sets dirty flag; 5s of inactivity triggers snapshot (collapsing rapid edits into one render)
- Final snapshot forced on exit regardless of timer

**Complexity Budget:**
- Capped at 4096 blocks per miniature; if interior exceeds this, sample at lower density (show approximate representation)
- V1 renderer may store block IDs/default states first; full block-state properties are a follow-up fidelity pass
- Rapid building collapses into one render pass

**Implementation:**
- `BlockEvent.EntityPlaceEvent` to detect block changes and set dirty flag
- Debounce timer (100 ticks = 5s default)
- NBT serialization for snapshot persistence

### Phase 3: Skybox

**Cubemap Capture (client-side, per-pixel raycasting):**
- On entry, `DioramaCubemapCapture` runs synchronously on the game thread while the vignette transition plays
- Casts per-pixel rays from the frame block's position through the live `ClientLevel`; no server roundtrip
- Uses `state.getMapColor()` combined with `BlockColors.getColor()` for biome-tinted block colors (grass, leaves, water get proper tints)
- Emissive blocks get a warm glow boost proportional to their light level
- Rays that reach max distance (32 blocks without hitting anything) use the full sky gradient: biome sky color, day/night cycle, sun glow, sunrise warmth, weather overcast — all computed from live `ClientLevel` state
- Result: a 6-face cubemap where nearby blocks appear as color blobs and open sky shows correct time-of-day gradient
- Static snapshot per session; recaptured on next entry (transition always triggers a fresh capture)

**Processing:**
- `skyboxBlurRadius` passes of 3×3 box blur (default 8) to dissolve geometry into blurred blooms
- The final look: massive soft blobs of color filling the diorama sky — lamps become sun-like glow sources, stone walls become grey ceilings, forest becomes a green canopy overhead

**Emergent Behavior:**
- Dioramas placed near lamps: the lamp becomes a massive blurred light source overhead (sun-like)
- Outdoor dioramas: show the actual biome sky color and sun position at capture time
- Indoor dioramas: show the room's block palette as diffuse overhead color fields

**Decision rationale:**
- Server-side block-color sampling (previous approach) only captured 16×16 map-color samples per face and could not access client-side biome tints; most rays hit air outdoors giving an almost featureless sky
- Client-side raycasting gives per-pixel fidelity, correct biome colors, and runs at the right moment (while client is still in the overworld, during the entry animation)

**Implementation:**
- `DioramaCubemapCapture` — new client-only class; triggered from `DioramaClientPayloadHandler` when entry transition payload arrives
- `DioramaTransitionPayload` carries `BlockPos framePos` so capture knows the frame location
- `DioramaSkySnapshotPayload` now carries only atmospheric metadata (sky color, day time, rain/thunder) — face int arrays removed
- Textures stored in `DioramaSkyboxTextures` (dynamic GPU textures); `DioramaSkyboxRenderer` unchanged
- Capture resolution: `skyboxCaptureResolution` config (default 64 = ≈30ms capture time; 256 = ≈500ms)

### Phase 4: Polish

**Animation & Audio:**
- Entry/exit shrink animation (screen scale effect + sound)
- Ambient sounds inside diorama slightly muffled (glass-through effect); v1 applies conservative volume/pitch shaping to newly played world sounds.

**HUD & Control:**
- Scale badge showing "1:16 scale" inside diorama
- Always-loaded control block inside the diorama toggles chunk forcing for that frame's plot

**Configuration:**
- `plot_size` — interior footprint (default/clamped to 15×15 for exact 1:16 miniature rendering with a border)
- `always_loaded` — per-diorama toggle (default: false)
- `miniature_max_blocks` — max blocks in exterior render (default: 4096)
- `sync_debounce_ticks` — inactivity ticks before miniature updates (default: 100)
- `skybox_capture_resolution` — cubemap face resolution (default: 256)
- `skybox_blur_radius` — blur passes on capture (default: 8)

---

## Data Flow

1. Player places Diorama Frame block → BlockEntity created, plot allocated via SavedData
2. Player right-clicks frame → entry animation → ITeleporter teleports to plot interior
3. Player builds inside → block placement events set dirty flag
4. Debounce timer expires (or player exits) → snapshot taken, stored in BlockEntity NBT
5. Client reads snapshot → BlockEntityRenderer draws miniature
6. Player exits → exit animation → return to original position
7. Next entry → cubemap captured from frame position → processed (blur, tint) → custom SkyRenderer displays as skybox

---

## Key APIs Used

| API | Purpose |
|---|---|
| `DimensionType` | Register shared diorama dimension |
| `ServerLevel.setChunkForced()` | Optional always-loaded mode |
| `ITeleporter` | Custom entry/exit teleport with animation |
| `BlockEntity` + NBT | Store plot assignment, dirty flag, schematic |
| `SavedData` | Server-side UUID → plot origin mapping |
| `BlockEntityRenderer` | Client miniature rendering |
| `SkyRenderer` (client) | Custom skybox for diorama dimension |
| `BlockEvent.EntityPlaceEvent` | Hook block changes to set dirty flag |
| `LevelEvent.Load / Unload` | Manage chunk loading on player entry/exit |

---

## Success Criteria

- ✓ Player can enter/exit diorama via frame block
- ✓ Interior snapshot persists across sessions
- ✓ Miniature render updates when exiting and live after debounced block edits
- ✓ Skybox shows outside room as blurred macro view, eventually using a true cubemap
- ✓ All configuration options exposed and functional
- ✓ No janky teleports, smooth animations
- ✓ Performance holds at 60 FPS with multiple dioramas

---

## Known Open Questions

- Nesting: blocked in v1 to avoid recursive rendering, teleport, and ownership complexity.
- Multi-player: Multiple players entering same diorama simultaneously (design: allow, keep simple, no locking)
- Animated blocks: Miniature render shows water/fire? (v1: static snapshot, skip animated blocks)
- Full isolation: Shared dimension remains, but each occupied plot must hide all other plots from the player's interior experience.
