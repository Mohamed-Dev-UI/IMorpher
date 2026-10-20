# Start/Direction Parameter Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `start: PathStart` and `direction: DrawDirection` two orthogonal, non-conflicting parameters resolved through one precedence contract, so a user's intent never silently depends on which parameter was set last.

**Architecture:** Move the anchor-direction arbitration out of the four scattered render sites into one pure resolver in `vector-morph-core` (`DirectionMath.resolve`). `PathStart` owns *where the pen begins*; `DrawDirection` owns *how it travels* and only derives the anchor when start is the new `AUTO` sentinel. Fill tracks inherit direction from their stroke track at DSL build time instead of each site baking its own default.

**Tech Stack:** Kotlin, Android library modules (minSdk 23, Java 11), Compose `DrawScope` renderer, JUnit4 + Robolectric, Gradle.

**Spec:** `README.md` §"Drawing and stroke reveal" (lines 173–202) documents today's contract — including its limitation ("the stroke continues in contour traversal order"). The conflict inventory below specifies the target contract.

## Conflict Inventory (what this plan fixes)

| # | Conflict | Evidence |
|---|----------|----------|
| C1 | Both `start` and `direction` pick the anchor; arbitration is implicit. Explicit `start` silently discards `direction`'s anchor; default `start = START` silently delegates to `direction`. | `MorphRenderer.resolveStartFraction` (MorphRenderer.kt:1530–1542) |
| C2 | `direction` promises travel it cannot deliver on open contours — traversal follows contour point order; only `CLOCKWISE`/`COUNTER_CLOCKWISE` reverse, and only closed contours. | README:199–201; `shouldReverseWinding` (MorphRenderer.kt:2023–2027, used at 1145) |
| C2b | `DrawMode.OUTSIDE_IN` ignores `start` entirely (`startFrac` computed but unused, MorphRenderer.kt:1508–1513); `DrawMode.CENTER_OUT`/`RADIAL` use it differently. No warning. | MorphRenderer.kt:1489, 1508–1513 |
| C3 | Same names, two meanings: `CENTER_OUT`/`OUTSIDE_IN` exist in both `DrawMode` and `DrawDirection`; `DrawDirection.CENTER_OUT` silently falls through to the diagonal axis (`DrawOrder.kt:131`) and as SWEEP anchor resolves to the top-left corner (MorphRenderer.kt:1309). | DrawOrderAndDirectionTest:142–147 |
| C4 | `customStartOffsets` does double duty: documented morph-alignment map, also supplies the draw anchor when `start = CUSTOM`, with inconsistent name precedence per site (`[f.name] ?: [t.name]` at 1147 vs `[side.name]` at 854). | MorphConfiguration.kt:31–32; MorphRenderer.kt:847–854, 1147–1154, 1810–1813 |
| C5 | Default drift per site: stroke `AUTO`→TOP_LEFT (1531); per-path `fill()` bakes `TOP_RIGHT_TO_BOTTOM_LEFT` (VectorAnimationBuilder.kt:544, 548–551); clip bakes `TOP_LEFT_TO_BOTTOM_RIGHT` (250, renderer 870). `reveal()` couples stroke+fill (174–181) but per-path `strokeReveal()` + `fill()` do not — directions silently contradict. | VectorAnimationBuilder.kt, MorphConfiguration.kt:35 |
| C6 | `DrawConfig` (mode/start/direction/fillMode/fillDirection) rides scalar tracks by `PropKey` ordinal and is read from three properties; `fill()` stores a full draw config with mode/start/direction that are never consumed by fills. | TimelineEvaluator.kt:248; MorphRenderer.kt:830–846, 1135–1137, 1328–1330 |
| C7 | No conflict diagnostics: `MorphDebugOptions.showStartPoints`/`showDirection` exist but the overlay does not visualize the *resolved* anchor/axis or contradictions. | Enums.kt / MorphDebugOptions |

## Target Contract

1. **`start` owns the anchor.** Explicit `start != AUTO` always wins and any anchor claim that `direction` could not honor is recorded as a warning, not silently dropped.
2. **`start = AUTO`** (new sentinel) means "derive from `direction`" — identical visuals to today's default (`AUTO` direction → top-left anchor).
3. **`start = START`** means literal arc position 0 of the normalized contour, regardless of `direction`. (Old default behavior remains reachable via `AUTO`.)
4. **`direction` owns travel.** With `MorphConfiguration.directionTraversal == HONOR_DIRECTION`, a stroke on an open contour traverses the arc toward the requested axis; `LEGACY` (default in this release) keeps today's contour-order behavior. Explicit `REVERSE`/`CENTER_OUT`/`OUTSIDE_IN` modes always win.
5. **Fills inherit direction** from the stroke config of the same target when `fill()` is called without an explicit direction; `MorphConfiguration.defaultFillDirection` remains the last fallback.
6. **`customStartOffsets`** drives morph alignment only; **`customDrawAnchors`** (new) drives `PathStart.CUSTOM` draw anchors, with a documented legacy fallback to `customStartOffsets` for one release.
7. **Warnings are visualized** by the debug overlay (`showDirection`/`showStartPoints`).

## Global Constraints

- Modules: `vector-morph-core`, `vector-morph-compose`, `app` (settings.gradle.kts). Core is an Android library, minSdk 23, `sourceCompatibility = JavaVersion.VERSION_11`.
- `VectorAnimationCodec` encodes enums by name (`VectorAnimationCodec.kt:53`); no schema/version change — new enum constants round-trip by name, `DrawConfig` keeps its 5 fields.
- No new dependency. JUnit4 for core; Robolectric already used by compose/app tests.
- Compatibility: default rendering output must be pixel-identical for existing API usage (Task 1 probe pins this). Behavior changes ship behind `directionTraversal` and the `customDrawAnchors` fallback this release.
- Test commands (Windows): `.\gradlew :vector-morph-core:test`, `.\gradlew :vector-morph-compose:testDebugUnitTest --tests "..."`, `.\gradlew :app:testDebugUnitTest --tests "..."`.
- Commit style: conventional (`feat:`, `test:`, `refactor:`, `docs:`).

## Review Focus

1. **Open contour + explicit `start=START` + explicit direction** (e.g. chevron icons): the pen must anchor at literal arc 0 and direction must not silently re-anchor; warning recorded. Pinned: Task 3 probe `explicitStartWinsOverDirection`.
2. **Closed contours + `CLOCKWISE`/`COUNTER_CLOCKWISE` + winding reversal:** `shouldReverseWinding` behavior must survive the resolver migration and the `directionTraversal` flag unchanged for `LEGACY`. Pinned: Task 4 `clockwiseReversalUnderLegacyPolicy`.
3. **`OUTSIDE_IN`/`CENTER_OUT` modes with a negotiated anchor:** anchor impact must stay exactly as today's (OUTSIDE_IN ignores it; CENTER_OUT expands from it) and now warn. Pinned: Task 3 `outsideInIgnoresStartWithSamePixels`.
4. **`customStartOffsets` legacy consumers:** morph alignment and draw anchoring must not cross-contaminate when only one map is populated. Pinned: Task 5 `legacyMapStillAnchorsDraws` + `alignmentMapDoesNotMovePen`.
5. **Codec-decoded animations with START defaults:** decode→render output must equal encode→render output for a START-based config. Pinned: Task 8 `startAUTOroundTrips`.

---

### Task 1: `PathStart.AUTO` sentinel with interim-compatible behavior

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/Enums.kt:44-59`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/animation/VectorAnimation.kt:138-145`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/animation/VectorAnimationBuilder.kt:507-529`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/MorphRenderer.kt:853,1136,1356,1810` (fallbacks `?: PathStart.START` → `?: PathStart.AUTO`) and `:1530` (interim resolution)
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/model/PathStartAutoTest.kt`
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/DrawDefaultAnchorRegressionTest.kt`

**Interfaces:**
- Consumes: existing `PathStart`, `DrawDirection`, `VectorAnimation.DrawConfig` (unchanged field set).
- Produces: `PathStart.AUTO` constant consumed by Tasks 3–7; `DrawConfig` default becomes `PathStart.AUTO`; DSL default start becomes `PathStart.AUTO` in `drawProgress`/`strokeReveal`.

- [ ] **Step 1: Write the failing core test**

```kotlin
// PathStartAutoTest.kt
@Test fun startAutoIsAGenuineConstantDistinctFromStart() {
    assertTrue(PathStart.entries.contains(PathStart.AUTO))
    assertNotEquals(PathStart.AUTO, PathStart.START)
}
@Test fun drawConfigDefaultsToAuto() {
    assertEquals(PathStart.AUTO, VectorAnimation.DrawConfig().start)
}
```

- [ ] **Step 2: Run — expect compile FAIL** (`Unresolved reference: AUTO`)

`.\gradlew :vector-morph-core:test --tests "com.imorpher.vectormorph.core.model.PathStartAutoTest"`

- [ ] **Step 3: Implement**

- Add `AUTO` to `PathStart` with doc: "Derived from `DrawDirection` (top-left when direction is AUTO). Never a literal anchor itself."
- Change `DrawConfig.start` default to `PathStart.AUTO` (VectorAnimation.kt:140). No other DrawConfig change (codec stays byte-compatible).
- In `VectorAnimationBuilder.drawProgress` (line 513) and `strokeReveal` (line 524): default parameter `start: PathStart = PathStart.AUTO`.
- In `MorphRenderer.resolveStartFraction` (line 1530), make `AUTO` behave identically to today's default by extending the guard so the direction table also runs for AUTO:

```kotlin
val resolvedStart = if (start == PathStart.AUTO || start == PathStart.START) when (direction) {
    // existing table 1531–1541 unchanged
} else start
```

- Replace the four `?: PathStart.START` renderer fallbacks (lines 853, 1136, 1356, 1810) with `?: PathStart.AUTO`.

- [ ] **Step 4: Write the failing regression probe** (parity: new default must render identical to old explicit default)

```kotlin
// DrawDefaultAnchorRegressionTest.kt  (renderMorph helper exists in MorphParameterApplicationTest.kt)
@Test fun autoDefaultMatchesOldExplicitDefault() {
    val old = VectorAnimation { allPaths { drawProgress(start = PathStart.START, direction = DrawDirection.AUTO) } }
    val new = VectorAnimation { allPaths { drawProgress(start = PathStart.AUTO, direction = DrawDirection.AUTO) } }
    listOf(0.25f, 0.5f, 0.75f).forEach { t ->
        assertTrue(renderMorph(from, to, t, animation = old).sameAs(renderMorph(from, to, t, animation = new)))
    }
}
```

- [ ] **Step 5: Run all core + compose tests** — `.\gradlew :vector-morph-core:test :vector-morph-compose:testDebugUnitTest` → PASS

- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: add PathStart.AUTO sentinel with default-parity behavior"`

---

### Task 2: Core resolver `DirectionMath.resolve`

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrder.kt:115-147` (append to `DirectionMath`)
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/reveal/DirectionResolverTest.kt`

**Interfaces:**
- Consumes: `PathStart` (incl. new `AUTO`), `DrawDirection`, `DrawMode` (Enums.kt).
- Produces (used by Tasks 3, 6, 8):

```kotlin
/** Warning categories emitted when start/direction/mode contradict. */
enum class DrawResolutionWarning { START_OVERRIDES_DIRECTION, ANCHOR_IGNORED_BY_MODE, CENTER_OUT_DIRECTION_ON_STROKE, MISSING_CONCRETE_ANCHOR }

/** One resolved anchor decision. `anchor` is never PathStart.AUTO. */
data class ResolvedDraw(
    val anchor: PathStart,
    val warnings: List<DrawResolutionWarning>,
)

fun resolve(start: PathStart, direction: DrawDirection, mode: DrawMode): ResolvedDraw
```

Resolution rules (pure, no path measurement): `start == AUTO` → anchor from direction table (AUTO→TOP_LEFT) with no warning; explicit start ≠ AUTO → that anchor, plus `START_OVERRIDES_DIRECTION` when start differs from the direction-table anchor and direction is a spatial value (corner/axis/CW/CCW); `mode == OUTSIDE_IN` → `ANCHOR_IGNORED_BY_MODE`; `direction == CENTER_OUT || OUTSIDE_IN` → `CENTER_OUT_DIRECTION_ON_STROKE` (fills keep them valid; stroke callers warn). `DirectionMath.axisFor`/`anchorFor`/`isDiagonal` stay untouched, including the documented diagonal fall-through (DrawOrderAndDirectionTest pins it).

- [ ] **Step 1: Write the failing table test**

```kotlin
@Test fun autoStartDerivesAnchorFromDirection() {
    for (direction in DrawDirection.entries - DrawDirection.CUSTOM - DrawDirection.AUTO) {
        assertEquals(DirectionMath.anchorPathStartOf(direction), DirectionMath.resolve(PathStart.AUTO, direction, DrawMode.FORWARD).anchor)
    }
    assertEquals(PathStart.TOP_LEFT, DirectionMath.resolve(PathStart.AUTO, DrawDirection.AUTO, DrawMode.FORWARD).anchor)
}
@Test fun explicitStartWinsWithWarning() {
    val r = DirectionMath.resolve(PathStart.TOP, DrawDirection.LEFT_TO_RIGHT, DrawMode.FORWARD)
    assertEquals(PathStart.TOP, r.anchor)
    assertTrue(DrawResolutionWarning.START_OVERRIDES_DIRECTION in r.warnings)
}
@Test fun startNeverRemainsAuto() {
    assertTrue(DrawDirection.entries.none { DirectionMath.resolve(PathStart.START, it, DrawMode.FORWARD).anchor == PathStart.AUTO })
}
@Test fun outsideInWarnsAnchorIgnored() { assertTrue(DrawResolutionWarning.ANCHOR_IGNORED_BY_MODE in DirectionMath.resolve(PathStart.CENTER, DrawDirection.LEFT_TO_RIGHT, DrawMode.OUTSIDE_IN).warnings) }
@Test fun centerOutDirectionWarnsOnStroke() { assertTrue(DrawResolutionWarning.CENTER_OUT_DIRECTION_ON_STROKE in DirectionMath.resolve(PathStart.AUTO, DrawDirection.CENTER_OUT, DrawMode.FORWARD).warnings) }
```

`DirectionMath.anchorPathStartOf(direction: DrawDirection): PathStart` = the mapping currently inlined at MorphRenderer.kt:1531–1541, extracted to core (public; reuse from Task 3).

- [ ] **Step 2: Run — expect FAIL** — `.\gradlew :vector-morph-core:test --tests "com.imorpher.vectormorph.core.reveal.DirectionResolverTest"`

- [ ] **Step 3: Implement `DirectionMath.resolve` + `anchorPathStartOf` in DrawOrder.kt** (extract table from MorphRenderer.kt:1531–1541 verbatim; renderer imports it — no copy)

- [ ] **Step 4: Run — PASS**, then `.\gradlew :vector-morph-core:test` full suite → PASS (DrawOrderAndDirectionTest must stay green)

- [ ] **Step 5: Commit** — `git commit -m "feat: add DirectionMath.resolve with explicit start/direction precedence"`

---

### Task 3: Renderer stroke sites consume the resolver

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/MorphRenderer.kt:1399-1591` (`buildRevealedStrokePath` + `resolveStartFraction`)
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/StrokeResolutionApplicationTest.kt`

**Interfaces:**
- Consumes: `DirectionMath.resolve`, `ResolvedDraw`, `DrawResolutionWarning`, `DirectionMath.anchorPathStartOf` (Task 2); render helper `renderMorph(...)` pattern from `MorphParameterApplicationTest.kt`.
- Produces: `buildRevealedStrokePath` now takes an additional parameter `warnings: (List<DrawResolutionWarning>) -> Unit = {}`

`resolveStartFraction` keeps its `PathMeasure` sampling (nearest-point-to-anchor at 1547–1590) but obtains `anchor` from `DirectionMath.resolve(start, direction, mode)`.anchor instead of the inline 1530–1542 table.

- [ ] **Step 1: Write the failing probe**

```kotlin
// StrokeResolutionApplicationTest.kt  — open contour (chevron-like biped), FORWARD draw
@Test fun explicitStartWinsOverContradictoryDirection() {
    // start=TOP_RIGHT must anchor top-right whether direction "agrees" or not
    val agree = renderMorphBundle(start = PathStart.TOP_RIGHT, direction = DrawDirection.TOP_TO_BOTTOM, t = 0.15f)
    val contradict = renderMorphBundle(start = PathStart.TOP_RIGHT, direction = DrawDirection.BOTTOM_TO_TOP, t = 0.15f)
    assertTrue(agree.litCentroidX > 0.65f)   // first lit pixels hug the right side
    assertEquals(agree.litPixelsKey, contradict.litPixelsKey, 0.01f) // anchor identical → pixels identical
}
@Test fun autoStillAnchorsTopLeftForDefaultDirection() {
    val r = renderMorphBundle(start = PathStart.AUTO, direction = DrawDirection.AUTO, t = 0.15f)
    assertTrue(r.litCentroidX < 0.5f && r.litCentroidY < 0.5f)
}
@Test fun outsideInIgnoresStartWithSamePixels() {
    val a = renderMorphBundle(start = PathStart.CENTER, direction = DrawDirection.AUTO, t = 0.3f, mode = DrawMode.OUTSIDE_IN)
    val b = renderMorphBundle(start = PathStart.BOTTOM_LEFT, direction = DrawDirection.AUTO, t = 0.3f, mode = DrawMode.OUTSIDE_IN)
    assertTrue(a.sameAs(b)) // C2b pinned: anchor has no effect; both emit ANCHOR_IGNORED_BY_MODE via the warnings callback
}
```

(Implementation detail for the test author: probing `buildRevealedStrokePath` on a single contour Path directly is NOT sufficient — use the same Robolectric pixel-probe harness as `MorphParameterApplicationTest`; helper `litPixelsKey` = normalized lit-pixel centroid + count.)

- [ ] **Step 2: Run — expect FAIL** — `.\gradlew :vector-morph-compose:testDebugUnitTest --tests "com.imorpher.vectormorph.compose.StrokeResolutionApplicationTest"`

- [ ] **Step 3: Implement** — swap the inline table for `DirectionMath.resolve` (signature per Task 2); pass a warnings collector through the three stroke call sites (pair pass 1150–1154, static side 852–856, traversal path 1353–1361) so Warning rows surface in Task 7. No behavioral changes other than warnings.

- [ ] **Step 4: Run** — Task 3 tests + Task 1 regression probe → PASS

- [ ] **Step 5: Commit** — `git commit -m "refactor: stroke reveal resolves anchors through DirectionMath.resolve"`

---

### Task 4: Direction-driven traversal (`MorphConfiguration.directionTraversal`)

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/Enums.kt` (add `DirectionTraversal`)
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/MorphConfiguration.kt:12-45`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/MorphRenderer.kt:1144-1159` (stroke pass)
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/model/DirectionTraversalConfigTest.kt`
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/TraversalPolicyApplicationTest.kt`

**Interfaces:**
- Consumes: `DrawMode`, `DirectionMath.axisFor` (existing), `buildRevealedStrokePath` (Task 3 signatures).
- Produces: `enum class DirectionTraversal { LEGACY, HONOR_DIRECTION }`; `MorphConfiguration.directionTraversal` consumed by renderer call sites in both stroke passes.

Rule: when `HONOR_DIRECTION` and `mode == FORWARD` and drawProgress is mid-reveal, pick the traversal sign whose arc-chord direction (sample anchor → anchor + len/8 via existing `PathMeasure`) has a positive dot product with `DirectionMath.axisFor(direction)`'s travel vector `(axis[2]-axis[0], axis[3]-axis[1])`. `LEGACY` (default) and all non-FORWARD modes: unchanged. Closed contours: `shouldReverseWinding` runs first (unchanged precedence).

- [ ] **Step 1: Write the failing config test**

```kotlin
@Test fun defaultIsLegacy() = assertEquals(DirectionTraversal.LEGACY, MorphConfiguration().directionTraversal)
@Test fun honorFlagSurvivesEquality() = assertEquals(MorphConfiguration(), MorphConfiguration())
```

- [ ] **Step 2: Run — FAIL (Unresolved reference)** — `.\gradlew :vector-morph-core:test --tests "com.imorpher.vectormorph.core.model.DirectionTraversalConfigTest"`

- [ ] **Step 3: Implement enum + field** (`val directionTraversal: DirectionTraversal = DirectionTraversal.LEGACY`)

- [ ] **Step 4: Write the failing pixel probe**

```kotlin
// TraversalPolicyApplicationTest.kt — open contour authored right-to-left ("sandbox-bird" style path)
@Test fun honorDirectionTraversesLeftToRight() {
    val legacyMid = renderMorph(from, to, t = 0.3f, animation = strokeRevealAnim(LEFT_TO_RIGHT), config = default)   // pen near the authored (left) end
    val honoredMid = renderMorph(from, to, t = 0.3f, animation = strokeRevealAnim(LEFT_TO_RIGHT), config = honorFlag)
    assertTrue(honoredMid.litCentroidX > legacyMid.litCentroidX)             // mid-reveal pixels sit further right under HONOR
    assertTrue(legacyMid.litCentroidX < 0.5f)                                // LEGACY keeps the authored-side behavior
    // closed-contour CW behavior identical across policies:
    assertTrue(renderMorph(circle, circleAlt, 0.3f, cwAnim, default).sameAs(renderMorph(circle, circleAlt, 0.3f, cwAnim, honorFlag)))
}
```

- [ ] **Step 5: Run — FAIL**, **Step 6: Implement** the chord-dot traversal sign in the non-RADIAL stroke branch (MorphRenderer.kt:1144–1159), feeding the existing REVERSE machinery (`-sign` selects mirrored segments); **Step 7: Run** both files → PASS; **Step 8: Commit** — `git commit -m "feat: add MorphConfiguration.directionTraversal to honor draw directions"`

---

### Task 5: Split draw anchors out of `customStartOffsets`

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/MorphConfiguration.kt:31-32`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/MorphRenderer.kt:847-854, 1147-1154, 1806-1813`
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/model/CustomDrawAnchorsTest.kt`
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/DrawAnchorMapApplicationTest.kt`

**Interfaces:**
- Produces: `val customDrawAnchors: Map<String, Float> = emptyMap()` — normalized arc fraction per path for `PathStart.CUSTOM` **draw** anchoring. `customStartOffsets` reverts to morph-alignment-only semantics.

Precedence (renderer-side, one private helper `drawAnchorFor(config, fromName, toName): Float = customDrawAnchors[fromName] ?: customDrawAnchors[toName] ?: customStartOffsets[fromName] ?: customStartOffsets[toName] ?: 0f`, single definition used by all three call sites — fixes C4's inconsistent precedence).

- [ ] **Step 1: Write the failing tests**

```kotlin
// CustomDrawAnchorsTest.kt (core)
@Test fun defaultIsEmptyLegacyMapsUnchanged() {
    val config = MorphConfiguration()
    assertTrue(config.customDrawAnchors.isEmpty())
    assertEquals(emptyMap<String, Float>(), config.customStartOffsets)
}

// DrawAnchorMapApplicationTest.kt (compose)
@Test fun newMapAnchorsPen() { /* PathStart.CUSTOM + customDrawAnchors["p"]=0.75 → lit pixels centered near 75% arc; t=0.25 shows the arc *after* 75% */ }
@Test fun legacyMapStillAnchorsDrawsWhenNewMapBlank() { /* same probe with only customStartOffsets — compat fallback */ }
@Test fun alignmentMapDoesNotMovePenWhenNewMapPresent() { /* customStartOffsets populated for morph alignment + customDrawAnchors["p"]=0.0 → pen starts at arc 0 (not the alignment fraction) */ }
```

- [ ] **Step 2: Run — FAIL** — `.\gradlew :vector-morph-core:test` + `:vector-morph-compose:testDebugUnitTest`

- [ ] **Step 3: Implement** field + precedence helper + call-site replacement (854, 1147–1149, 1810–1813).

- [ ] **Step 4: Run** both suites → PASS, **Step 5: Commit** — `git commit -m "feat: split customDrawAnchors from customStartOffsets with legacy fallback"`

---

### Task 6: Build-time fill-direction inheritance

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/animation/VectorAnimationBuilder.kt:194-202, 542-552, 314-401` (build loop)
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/animation/FillDirectionInheritanceTest.kt`

**Interfaces:**
- Consumes: `PropKey.DRAW_PROGRESS`/`FILL_PROGRESS` segments (VectorAnimation.kt Track/Segment/DrawConfig).
- Produces: `PathAnimationScope.fill(direction: DrawDirection? = null, ...)` — `null` means inherit; vector-level `fill(...)` keeps `TOP_RIGHT_TO_BOTTOM_LEFT` as its *resolved* default via the same mechanism; `MorphConfiguration.defaultFillDirection` stays the renderer fallback for orphan fills (unchanged MorphRenderer.kt:846).

Rule (implemented in `build()` step 2): for every compiled `PropKey.FILL_PROGRESS` segment whose `draw != null && draw.fillDirection == null`, set `fillDirection` to the `direction` of the **first** (declaration order) `PropKey.DRAW_PROGRESS` segment on the same `AnimationTarget`, else leave null.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun perPathFillInheritsStrokeDirection() {
    val anim = VectorAnimation {
        path("outline") { strokeReveal(direction = DrawDirection.BOTTOM_TO_TOP, interval = 0f..0.5f) }
        path("outline") { fill(mode = FillMode.DIRECTIONAL, interval = 0.4f..1f) }   // direction omitted
    }
    val fillTrack = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }
    assertEquals(DrawDirection.BOTTOM_TO_TOP, fillTrack.segments.first().draw!!.fillDirection)
}
@Test fun orphanFillStaysNullForRendererFallback() {
    val anim = VectorAnimation { allPaths { fill(mode = FillMode.DIRECTIONAL) } }
    assertNull(anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.first().draw!!.fillDirection)
}
```

- [ ] **Step 2: Run — FAIL** — `.\gradlew :vector-morph-core:test --tests "com.imorpher.vectormorph.core.animation.FillDirectionInheritanceTest"`

- [ ] **Step 3: Implement** — maker `fill()`/`PathAnimationScope.fill` parameter `direction: DrawDirection?` (callers passing nothing compile unchanged because default changes from a constant to null); apply the inheritance rule in `build()` before segment compilation; `reveal()` (builder line 179) already couples explicitly and is untouched.

- [ ] **Step 4: Run** — full core suite (codec round-trips unaffected: fillDirection was already nullable) → PASS, **Step 5: Commit** — `git commit -m "feat: per-path fill tracks inherit stroke draw direction at build time"`

---

### Task 7: Debug overlay visualizes resolution + warnings

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/MorphRenderer.kt` (debug overlay section — locate via `showStartPoints`/`showDirection` usages)
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/DrawDebugOverlayTest.kt`

**Interfaces:**
- Consumes: `ResolvedDraw.warnings` collected in Task 3, `MorphDebugOptions.showDirection/showStartPoints` (MorphConfiguration.kt:59–60), `DirectionMath.axisFor`.
- Produces: overlay draws (a) the resolved anchor marker at the active contour anchor, (b) a short arrow along `axisFor(direction)`, (c) a warning indicator whenever warnings are non-empty.

- [ ] **Step 1: Write the failing probe** — same rendered scene with `debug = MorphDebugOptions(showStartPoints = true, showDirection = true)` produces extra lit pixels vs `debug = null`, and the delta appears at the resolved anchor location; pixels identical when debug is null.

- [ ] **Step 2: Run — FAIL**, **Step 3: Implement** overlay drawing (reuse the existing debug paint path for bounding boxes), **Step 4: Run** → PASS, **Step 5: Commit** — `git commit -m "feat: debug overlay shows resolved draw anchors, axes, and conflicts"`

---

### Task 8: Codec round-trip + final deprecation docs

**Files:**
- Modify: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/VectorAnimationCodecTest.kt`
- Modify: `README.md:173-202`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/Enums.kt:25-41` (KDoc only)

**Interfaces:** Consumes codec (`VectorAnimationCodec.encode/decode`), Task 1–6 API. Produces: documentation of the precedence contract.

- [ ] **Step 1: Write the failing test** — round-trip an animation containing `PathStart.AUTO`, `DrawDirection.BOTTOM_TO_TOP`, and a FILL_PROGRESS segment with inherited direction; assert `decoded` renders the same mid-frame as the original (pixel probe via Robolectric as in existing codec render tests, or compare compiled track fields).

- [ ] **Step 2: Run — FAIL**, **Step 3: Run existing codec suite** (must stay green: enum-name encoding already handles new constants), **Step 4: Update KDoc + README** — rewrite the paragraph at README:199–202 to the Target Contract (1–7 above), document `directionTraversal`, `customDrawAnchors`, `PathStart.AUTO`, and mark `DrawDirection.CENTER_OUT/OUTSIDE_IN` as fill-sweep-only (stroke use emits `CENTER_OUT_DIRECTION_ON_STROKE`). **Step 5: Commit** — `git commit -m "docs: document start/direction precedence contract"`.

---

### Task 9: Showcase app picks AUTO and guards conflicting combos

**Files:**
- Modify: `app/src/main/java/com/mohamed/dev/ui/imorpher/showcase/ShowcaseState.kt:52-53, 135-136`
- Modify: `app/src/main/java/com/mohamed/dev/ui/imorpher/showcase/SnippetGenerator.kt:80-81, 108-109` (only if a migration comment is needed — names print automatically)
- Create: `app/src/test/java/com/mohamed/dev/ui/imorpher/showcase/DrawParamCoherenceTest.kt`

**Interfaces:**
- Consumes: `DirectionMath.resolve` (Task 2) to classify the currently selected combo in the showcase filter/preview layer.
- Produces: showcase default `pathStart = PathStart.AUTO`; a computed `state.startDirectionCoherence` list of `DrawResolutionWarning` for the current selection, surfaced where the pickers already render (UI wiring follows the existing state observation pattern; no new UI framework).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun defaultPathStartIsAuto() = assertEquals(PathStart.AUTO, ShowcaseState().pathStart)
@Test fun conflictingComboReportsWarnings() {
    val state = ShowcaseState().apply { pathStart = PathStart.TOP; drawDirection = DrawDirection.LEFT_TO_RIGHT }
    assertTrue(DrawResolutionWarning.START_OVERRIDES_DIRECTION in state.startDirectionWarnings())
}
```

- [ ] **Step 2: Run — FAIL** — `.\gradlew :app:testDebugUnitTest --tests "com.mohamed.dev.ui.imorpher.showcase.DrawParamCoherenceTest"`

- [ ] **Step 3: Implement** — default + `startDirectionWarnings(): List<DrawResolutionWarning>` calling `DirectionMath.resolve(state.pathStart, state.drawDirection, drawMode)`; render the warnings where the picker already shows labels (existing `drawDirections`/`pathStarts` lists need no change; `ShowcaseStatePersistence` parses `PathStart.entries` so the new constant persists correctly).

- [ ] **Step 4: Run** — app suite incl. `ShowcaseNavigationTest`, `IconPairMorphMatrixTest` → PASS, **Step 5: Commit** — `git commit -m "feat: showcase defaults to PathStart.AUTO and surfaces start/direction conflicts"`

---

## Rollback & verification

- Every task is independently committable; Tasks 3–7 keep pixel outputs identical by default (probes pin that), so bisecting a visual regression is safe.
- Full-suite command after each task: `.\gradlew :vector-morph-core:test :vector-morph-compose:testDebugUnitTest :app:testDebugUnitTest`.
- The behavior flips (`AUTO` default, `customDrawAnchors` precedence, fill inheritance) are all new-code paths in this release; legacy compatibility is pinned by the Task 1/3/5/8 probes.
