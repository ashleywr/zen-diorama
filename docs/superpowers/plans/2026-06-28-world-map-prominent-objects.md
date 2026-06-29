# World Map Prominent Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make major above-ground structures on the world map render as readable miniature objects instead of terrain bumps.

**Architecture:** Add a pure snapshot-space detector that marks prominent connected object regions using local prominence and footprint thresholds, then have the client geometry baker apply separate shaping to those regions. The detector works on sampled columns, so it stays generic for modded blocks and cheap enough for the existing cached bake pipeline.

**Tech Stack:** Java 21, NeoForge/Minecraft client BER rendering, JUnit 5, Gradle

---

### Task 1: Add Pure Prominent-Object Detection

**Files:**
- Create: `src/main/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjects.java`
- Create: `src/test/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjectsTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void detectsCompactRaisedPlateauAsObjectRegion() { /* 3x3 raised region => object */ }

@Test
void rejectsLongThinRaisedLine() { /* 1x4 line => not object */ }

@Test
void rejectsSingleTallSpike() { /* 1 cell spike => not object */ }

@Test
void rejectsGentleTerrainBump() { /* low prominence hump => not object */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapProminentObjectsTest"`
Expected: FAIL because `WorldMapProminentObjects` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
public final class WorldMapProminentObjects {
    public static Result detect(MiniatureSnapshot snapshot) {
        // Build ground-height grid from snapshot
        // Mark candidate columns by local prominence threshold
        // Flood-fill 4-neighbor candidate regions
        // Keep regions only if area and x/z spans meet thresholds
        // Return boolean mask keyed by sampled x,z
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapProminentObjectsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjects.java src/test/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjectsTest.java
git commit -m "feat: detect prominent world map objects"
```

### Task 2: Apply Object Shaping In Geometry Bake

**Files:**
- Modify: `src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java`
- Modify: `src/main/java/com/sanhiruzu/zendiorama/core/WorldMapReliefShaper.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void shapesProminentObjectsMoreUprightThanTerrain() { /* object height > terrain height for same base */ }

@Test
void capsObjectHeightToToyMass() { /* very tall object remains capped */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapReliefShaperTest"`
Expected: FAIL because object shaping helpers do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
public static float shapeObjectHeight(float groundHeight, float objectHeight) {
    // Raise object masses more directly than terrain
    // Flatten/cap roofs so they read as toy structures
}
```

Then in `WorldMapGeometry`, detect accepted object columns from `WorldMapProminentObjects.detect(snapshot)` and route those columns through object shaping instead of plain terrain shaping.

- [ ] **Step 4: Run tests and compile to verify**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapReliefShaperTest"`
Expected: PASS

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sanhiruzu/zendiorama/client/WorldMapGeometry.java src/main/java/com/sanhiruzu/zendiorama/core/WorldMapReliefShaper.java
git commit -m "feat: render prominent world map objects as miniatures"
```

### Task 3: Verify LoD Compatibility

**Files:**
- Modify: `src/test/java/com/sanhiruzu/zendiorama/core/MiniatureSnapshotLodTest.java`
- Modify: `src/test/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjectsTest.java`
- Review: `src/main/java/com/sanhiruzu/zendiorama/client/WorldMapLodCache.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void keepsBroadProminentObjectDetectableAfterLodReduction() { /* house plateau survives factor 2 or 4 */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapProminentObjectsTest" --tests "com.sanhiruzu.zendiorama.core.MiniatureSnapshotLodTest"`
Expected: FAIL until thresholds work across reduced snapshots.

- [ ] **Step 3: Write minimal implementation**

```java
// Tune detection thresholds or LoD interpretation so broad objects remain detected
// while thin clutter still drops out.
```

- [ ] **Step 4: Run tests and compile to verify**

Run: `.\gradlew.bat test --tests "com.sanhiruzu.zendiorama.core.WorldMapProminentObjectsTest" --tests "com.sanhiruzu.zendiorama.core.MiniatureSnapshotLodTest" --tests "com.sanhiruzu.zendiorama.core.WorldMapReliefShaperTest"`
Expected: PASS

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/sanhiruzu/zendiorama/core/MiniatureSnapshotLodTest.java src/test/java/com/sanhiruzu/zendiorama/core/WorldMapProminentObjectsTest.java
git commit -m "test: cover world map prominent object lod behavior"
```
