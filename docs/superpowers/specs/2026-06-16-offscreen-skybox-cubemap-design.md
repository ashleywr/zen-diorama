# Off-Screen Skybox Cubemap — Design

**Date:** 2026-06-16
**Target:** NeoForge 21.1.220 / Minecraft 1.21.1 / Java 21
**Status:** Approved design, pending spec review

## Problem

The diorama skybox is captured by spinning the player's *real* camera through six
orientations over six consecutive frames and screenshotting the main framebuffer
(`DioramaCubemapCapture`). This works but has three visible problems:

1. **Visible camera pan** — the player sees the camera whip around on entry, which is jarring.
2. **The diorama block appears in the skybox** — captured from the frame's location, the down
   face shows the frame block itself ("the miniature is down there"), breaking immersion.
3. **Duct-tape pipeline** — a 90° FOV override, a center-square crop, a 180° pole-face rotation
   fix, and a cross-face seam-blend pass are all needed to compensate for screenshotting the
   live view at the player's FOV/aspect.

There is also a fixed ~750 ms entry delay (`skyboxCaptureDelayTicks`) so the six live frames can
finish before the server teleports the player.

## Goals

- Eliminate the visible camera pan entirely (capture off-screen; the real view never moves).
- Exclude the diorama block (and miniature) from the capture so edges read as clean world.
- Replace the FOV/crop/rotation/seam hacks with a real 90° projection.
- Make entry near-instant via a capture-complete handshake instead of a fixed delay.
- Add a subtle 1-block edge "lip" inside the plot representing the diorama's physical edge.

## Non-Goals

- **Live / periodic re-capture.** The skybox stays a one-time snapshot taken at entry. Day/night
  and weather are frozen for the visit. (Can be layered on later.)
- Multi-version compatibility. We target 1.21.1 NeoForge only and accept reliance on engine
  internals / reflection where needed.

## Approach (chosen)

Off-screen framebuffer render. On entry, render the surrounding world into a hidden square
render target six times with a controlled camera and a real 90° projection, then upload the six
faces to the existing `DioramaSkyboxTextures`. The player's real view is never touched.

Alternative considered and rejected: hiding the existing live-camera spin behind an opaque fade.
Cheaper and lower-risk, but leaves every other rough edge (block-in-skybox, FOV/crop/rotation
hacks, entry delay) in place. Rejected in favor of the cleaner pipeline.

## Architecture

### Capture mechanism

A render-thread capture pass, triggered when a capture has been requested (flag set by the
transition payload handler) and performed after the player's real frame is drawn
(e.g. `RenderLevelStageEvent.AFTER_LEVEL` or equivalent late render hook):

1. Lazily allocate/resize a dedicated off-screen square `TextureTarget` to
   `skyboxCaptureResolution` (with depth).
2. Save and redirect the engine's render destination to the off-screen target (main-target swap
   via reflection — **the fragile core**), and save the current camera state.
3. Position the engine camera at the **frame block's center**.
4. For each of the six faces (north −Z, south +Z, east +X, west −X, up +Y, down −Y):
   - Set the camera orientation for that face.
   - Build a real 90° **square** projection matrix (no FOV hack, no aspect distortion).
   - Render the level into the off-screen target.
   - Read the face back and (optionally) blur it (`skyboxBlurRadius`), then upload to
     `DioramaSkyboxTextures.upload(face, image)`.
5. Restore the camera and the main render target.
6. Notify the server that capture is complete (see Timing).

Face order and the cube quad mapping in `DioramaSkyboxRenderer` are unchanged. Because the
projection is a true 90°, the up/down faces no longer need the 180° yaw correction and adjacent
faces tile without the seam-blend pass.

### Excluding the diorama block

The capture camera sits at the **frame block's center**. From inside the block, its own model
faces cull (they face outward, away from the camera), so the block does not appear in any face —
including the down face. Additionally a static `suppressMiniature` flag causes
`DioramaFrameRenderer` to skip drawing the miniature during the capture pass.

Fallback (only if the frame's model renders visibly from the inside): a one-frame client-side
swap of the frame block to air during capture. Expected to be unnecessary.

### Timing / handshake

Replaces the fixed teleport delay:

1. Server `DioramaFrameBlock.teleportIntoDiorama` sets up the plot, sends
   `DioramaSkySnapshotPayload` (atmospheric metadata, unchanged) and
   `DioramaTransitionPayload(true, framePos)`, then registers the player as *awaiting capture*
   in `DioramaPendingTeleports` with a short **fallback timeout** (instead of a fixed delay).
2. Client performs the one-frame off-screen capture, then sends a new serverbound
   `DioramaCaptureReadyPayload`.
3. Server teleports the player immediately on receipt of the ack, or on the fallback timeout if
   the ack never arrives (packet loss / capture failure).

`skyboxCaptureDelayTicks` is repurposed as the fallback timeout (kept in config).

### Subtle edge lip

In `DioramaFrameBlock.ensurePlotInterior`, add a 1-block-high border around the floor perimeter
made of a new **dedicated unbreakable edge block** (`DioramaEdgeBlock`, following the
exit/control unbreakable strength pattern, `strength(-1.0F, 3600000.0F)`), with a frame-like
appearance. The lip hints at the diorama's physical edge without walling the interior in. The
edge block is excluded from the miniature snapshot scan (like barrier/exit/control).

## Components

| Component | Change |
|---|---|
| `client/DioramaOffscreenCubemap` (new; replaces `DioramaCubemapCapture` internals) | Owns off-screen `TextureTarget`; performs 6-face render + readback + blur + upload; main-target/camera swap; render-thread capture hook. |
| `client/DioramaFrameRenderer` | Honor static `suppressMiniature` flag during capture. |
| `network/DioramaCaptureReadyPayload` (new, serverbound) | Client→server ack that capture finished. |
| `network/DioramaTransitionPayload` | Unchanged (still carries `framePos` for entry). |
| `network/DioramaSkySnapshotPayload` | Unchanged (atmospheric metadata only). |
| `block/DioramaFrameBlock` | Ack-driven teleport instead of fixed delay; place edge lip in `ensurePlotInterior`. |
| `server/DioramaPendingTeleports` | Ack-driven completion with fallback timeout; expose `completeCapture(player)`. |
| `block/DioramaEdgeBlock` (new) | Unbreakable decorative edge block for the lip. |
| `block/DioramaFrameBlockEntity` | Exclude edge block from snapshot scan. |
| `ZenDiorama` / `ZenDioramaClient` | Register new block + payload; register capture render hook; remove obsolete live-capture event listeners. |
| `DioramaConfig` | `skyboxCaptureDelayTicks` repurposed as fallback timeout; resolution/blur kept. |

### Removals

- `DioramaCubemapCapture` live-capture handlers: `onCameraAngles`, `onComputeFov`, `onRenderHand`,
  `onGuiPre`, and their registrations in `ZenDioramaClient`.
- The 90° FOV override, screenshot center-crop, 180° pole-face yaw correction, and the
  `blendTopSeam` pass (all obsolete under a real projection).

## Data Flow (entry)

```
Server: ensure plot + lip → send SkySnapshot + Transition(true, framePos) → await ack (queue, timeout)
Client: receive Transition → request capture → (next render, after real frame)
        bind off-screen target → camera @ frame center, suppress miniature
        → render 6 faces @ 90° → readback/blur/upload → restore → send CaptureReady ack
        → start entry vignette
Server: receive CaptureReady (or timeout) → player.teleportTo(diorama)
Client (in diorama): DioramaSkyboxRenderer draws the 6 cube faces
```

## Risks

- **Main-target swap / direct level-render call (fragile core).** Uses engine internals/reflection;
  the most likely thing to break on a version bump. Accepted given 1.21.1-only scope. Mitigation:
  isolate all internal access in `DioramaOffscreenCubemap` behind a small, clearly documented surface.
- **Camera-in-block culling not hiding the frame model.** Mitigated by `suppressMiniature` and the
  air-swap fallback.
- **Six level renders in one frame** cause a one-time hitch on entry. Acceptable (one-time, on a
  transition that already fades). Resolution is configurable to tune cost.
- **Ack packet loss.** Mitigated by the fallback timeout.

## Testing

- Pure-logic unit tests where applicable (plot/lip placement math) under `src/test` (JUnit 5).
- Manual in-client verification: no visible pan on entry; clean faces with no diorama block;
  seams aligned without the blend hack; edge lip present and unbreakable; near-instant entry;
  teleport still fires if the client never acks (simulate by skipping the ack).
