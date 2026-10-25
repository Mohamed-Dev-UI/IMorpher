# Parameter Effectiveness & API Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every parameter combination in IMorpher that silently does nothing or silently overrides another parameter, and make the API surface honest about what each knob controls.

**Architecture:** The audit (§2, verified against source) found the parameter surface splits into strict planning fields, render/evaluation fields, and a handful of dead or shadowed wires. The plan (1) fixes wires that are accepted but never read (remember keys, dead enum branches, dead debug option), (2) makes pairs of visually-identical enum entries actually differ or become declared aliases, (3) threads the parameters the engine already owns into the few render sites that still ignore them, (4) splits the plan-cache key from the full config so render-only fields stop invalidating plans, and (5) adds a static `MorphValidator.validateConfiguration` check plus a docs sweep so remaining precedence rules are documented, not discovered.

**Tech Stack:** Kotlin, Jetpack Compose (Robolectric unit probes via `RenderProbeHarness`), Gradle (`./gradlew.bat`).

**Spec:** §2 of this document — the audit table. It is the authoritative list of defects this plan implements; file:line anchors were verified against the working tree on 2026-10-25.

## Global Constraints

- Version target: 0.3.0. Public API is deprecated, never removed (`@Deprecated(level = DeprecationLevel.WARNING)`), matching the `customStartOffsets` precedent.
- `MorphConfiguration` remains a data class; two equal configurations must always produce equal plans (planning-key split must preserve this).
- Compose never reads core module `internal` symbols — new cross-module hooks need `public` or new public functions in core.
- Keep `MorphConfiguration` free of callbacks/diagnostics (poisons cache keys) — diagnostics go through `MorphValidator`/warnings, not config.
- Renderer behavior tests use bitmap deltas, mirroring `MorphParameterApplicationTest` (`BitmapAsserts.meanAbsDifference`, thresholds 0.005–0.01).
- Out of scope (already deferred elsewhere): KMP rendering, `ContentScale` crop/fill, `DirectionTraversal` on custom-geometry sites beyond what Task 9 threads (custom contours get the policy; custom *callback geometry* stays free-form).
- All test commands run on Windows: `./gradlew.bat <task>` from the repo root.

## Audit findings (the spec)

Every row was verified in source. "Conflict" = one param silently overrides/erases another; "No-op" = accepted but unread; "Weak" = effective only in a narrow configuration.

| ID | Finding | Type | Evidence |
| --- | --- | --- | --- |
| A1 | `customDrawOrder` + `timing` are consumed by `TimelineEvaluator` but missing from the `remember` key → stale evaluator on recomposition | No-op | `PlanRemember.kt:47-57` |
| A2 | `staggerOrder` honored only when `staggerDelay > 0`; config can never cancel/override timeline stagger cleanly | Conflict | `PlanRemember.kt:53-54` |
| A3 | `drawOrder` drives *nothing* by default: renderer skips it while `keepRenderOrder = true` (default), and stagger ranking uses `staggerOrder`, never `drawOrder` (docs claim otherwise) | No-op/Conflict | `MorphRenderer.kt:291-292`, `TimelineEvaluator.kt:112-114` |
| A4 | `staggerOffsets` doc promises a "squeeze" so the last path starts on-timeline; impl is plain `delay*rank` + flat 0.95 clamp → late paths all start simultaneously when delay is large | Doc/impl mismatch | `DrawOrder.kt:94-110`, `TimelineEvaluator.kt:182` |
| A5 | `timing = BY_PATH_LENGTH` saturates short paths → stagger on short paths cancels (lamb paths of a pen-draw pop in) | Conflict | `TimelineEvaluator.kt:378-394` |
| A6 | `DrawOrderStrategy.BY_NAME` consults `customDrawOrder` (kdoc scopes it to `CUSTOM`) | Conflict | `DrawOrder.kt:25-29` |
| A7 | `DrawOrderStrategy.CUSTOM` + empty/typo'd `customDrawOrder` silently degrades to declaration order | No-op | `DrawOrder.kt:43,63-76` |
| A8 | `BY_POSITION` ≡ `AUTO` ≡ `TOP_LEFT_TO_BOTTOM_RIGHT` (same branch) | Alias | `DrawOrder.kt:31-34` |
| A9 | `StrokeFillStrategy.MORPH` ≡ `CROSSFADE` (folded into one boolean; kdoc promises stroke-shrink behavior) | No-op distinction | `MorphPlanner.kt:618`, `FillPass.kt:144` |
| A10 | `FillMode.LINEAR` ≡ `DIRECTIONAL` (identical hard-wipe clip; kdoc promises a hard-vs-directional distinction) | No-op distinction | `FillPass.kt:345-365`, `ClipPass.kt:185-203` |
| A11 | SWEEP start angle diverges: pair-fill `ClipPass` hardcodes `-90f`, standalone `FillPass` derives per direction | Conflict (site divergence) | `ClipPass.kt:177` vs `FillPass.kt:367-378` |
| A12 | `customDrawAnchors` read only when `PathStart.CUSTOM`; with default draws the map is a silent no-op | No-op combo | `StrokePass.kt:449` |
| A13 | `StartPointStrategy.CUSTOM` still sources *alignment* only from the deprecated `customStartOffsets` — following the deprecation breaks alignment; no modern home exists | Conflict | `MorphConfiguration.kt:39-51`, `MorphPlanner.kt:529-530`, `StrokePass.kt:594-599` |
| A14 | `markerArcFraction` (debug option, surfaced in the showcase) never rendered | No-op | declared `MorphConfiguration.kt:113`; zero renderer reads |
| A15 | `fallback ∈ {CROSSFADE, SCALE_CROSSFADE}` (or AUTO-resolved crossfade) silently forces `missingPath` to FADE | Conflict | `MorphPlanner.kt:85` |
| A16 | Accessibility `CROSSFADE_ONLY` force-rewrites `fallback` silently + double-plans the cache | Conflict | `MorphIcon.kt:52-54/106-108/162-164`, `MorphingIcon.kt:50-54` |
| A17 | `MorphIcon.animation` param shadows `configuration.animation` (both homes for one value; undiscoverable at call sites) | Conflict | `MorphIcon.kt:43/89/97` |
| A18 | `rememberVectorAnimation(animation: VectorAnimationDefinition, ...)` ignores its headline param | No-op | `VectorAnimationEntry.kt:70-75` |
| A19 | `appearanceOnly` built/serialized, never consumed | No-op | `VectorAnimation.kt:78`, `VectorAnimationCodec.kt:47,128` |
| A20 | `MorphPrecompiler.isPrepared` probes only `MorphConfiguration.Default` — warm() + custom config always answers `false` | No-op | `MorphPrecompiler.kt:51-53` |
| A21 | Plan cache keyed on the *full* config; 14 render-only fields fragment a 64-slot LRU and re-plan on main thread | Performance conflict | `MorphPlanner.kt:693-696`, `PlanRemember.kt:89-96` |
| A22 | `directionTraversal` and `timing` ignored at: `missingPath = DRAW` reveals, custom-geometry contours, `FillMode.PATH_TRAVERSAL` fills | No-op at sites | `StrokePass.kt:179-243,513-546`, `FillPass.kt:391-434` |
| A23 | `FillMode.FADE`/`RADIAL` ignore their `direction` silently (nothing calls `axisFor`) | No-op | `FillPass.kt:330-343`, `ClipPass.kt:160-167,184` |
| A24 | `MorphDebugOptions` warnings + plan warnings are invisible without `debug != null` (release users get silent precedence) | Visibility gap | `MorphRenderer.kt:429-448`, `DrawOrder.kt:238-259` |
| A25 | `MorphValidator` audits none of the reveal-parameter conflicts | Missing validation | `MorphValidator.kt:16-40` |
| A26 | `TimingMode.BY_COMMAND` ≡ `UNIFORM` at pair and group clock (only contour remap differs); undocumented | Weak/doc | `TimelineEvaluator.kt:400,415` |
| A27 | Inline-DSL `VectorAnimation` instances restart one-shots every recomposition (identity-keyed effects, class not `@Stable`/data) | Trap | `VectorAnimationEntry.kt:215,218`, `VectorAnimation.kt:74` |
| A28 | `PathMatchingStrategy.BY_NAME` ignores `customMappings` (kdoc + docs claim it uses them) | Conflict | `PathMatcher.kt:53-60` |
| A29 | `DrawDirection.CENTER_OUT`/`OUTSIDE_IN` collapse to top-left on axis sweeps (documented for SWEEP, silent for LINEAR/DIRECTIONAL) | Weak | `DrawOrder.kt:134` |
| A30 | Dead internal API: `pairSpace` identity fn, `ensureBegun()` no-op, unreachable `animation ?: configuration.animation` fallback | No-op | `TimelineEvaluator.kt:511-512`, `PathNodeNormalizer.kt:49-51`, `PlanRemember.kt:46` |

Precedence conflicts that are *known and deliberate* (already warning via `DirectionMath.resolve`: `START_OVERRIDES_DIRECTION`, `ANCHOR_IGNORED_BY_MODE`, `CENTER_OUT_DIRECTION_ON_STROKE`, `MISSING_CONCRETE_ANCHOR`) are not defects — but they stay release-invisible (A24) and unaudited statically (A25); A24/A25 cover them.

## Review Focus

The five failure modes most likely to bite a user that no task's golden-path test exercises; each is pinned to a task:

1. **Recomposing with only `customDrawOrder` or `timing` changed** must not keep the old stagger clocks — pinned in Task 1 (stale-key probe).
2. **Short paths under stagger + `BY_PATH_LENGTH`** must each draw visibly (≥5% of timeline) instead of popping — pinned in Task 4.
3. **`LINEAR` vs `DIRECTIONAL` and `MORPH` vs `CROSSFADE`** must render different mid-frames — pinned in Tasks 6–7 (bitmap deltas).
4. **A config with `customDrawAnchors` but no `PathStart.CUSTOM` anywhere** must yield a validator warning, not silence — pinned in Task 12.
5. **Plan cache must stay warm** when only render-only fields (`debug`, `colorSpace`, `defaultFillMode`, ...) change — pinned in Task 14 (cache-hit unit test).

---

# Phase 1 — Accepted-but-broken wires (compose)

### Task 1: Evaluator remember key includes `customDrawOrder` and `timing`

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/PlanRemember.kt:47`
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/MorphParameterApplicationTest.kt`

**Interfaces:** Consumes existing `TimelineEvaluator(animation, plan, colorSpace, staggerDelay, staggerOrder, customDrawOrder, timingMode)`. Produces no API change.

- [ ] **Step 1: Write the failing test** — add to `MorphParameterApplicationTest` (fixture style: `harness.stroked(...)` + `reveal(...)` helper used by the existing stagger test `staggerDelaysLaterPathsMidMorph`):

```kotlin
@Test
fun timingAndCustomDrawOrderChangesRebuildTheEvaluator() {
    val from = harness.stroked("a", harness.line())
    val to = harness.stroked("b", harness.line())
    val animation = threeRevealStagger() // three staggered draw reveals, hoisted (reuse the stagger test's timeline)
    val uniform = harness.renderMorph(from, to, 0.8f, configuration = MorphConfiguration(timing = TimingMode.UNIFORM), animation = animation)
    val byCommand = harness.renderMorph(from, to, 0.8f, configuration = MorphConfiguration(timing = TimingMode.BY_COMMAND), animation = animation)
    assertTrue("timing must apply on its own recomposition", difference(uniform, byCommand) > 0.005f)
}
```

Both configs render in separate `ComposeView` compositions (the harness composes fresh each call), so this only fails while the key is stale via the shared `planCache`+`remember` path.

- [ ] **Step 2: Run to verify failure** — `./gradlew.bat :vector-morph-compose:testDebugUnitTest --tests "com.imorpher.vectormorph.compose.MorphParameterApplicationTest"` → FAIL.
- [ ] **Step 3: Implement** — extend the key list at `PlanRemember.kt:47`:

```kotlin
val evaluator = remember(plan, planningAnimation, configuration.colorSpace,
    configuration.staggerDelay, configuration.staggerOrder,
    configuration.customDrawOrder, configuration.timing) { ... }
```

- [ ] **Step 4: Run to verify pass** — same command → PASS.
- [ ] **Step 5: Commit** — `git commit -m "compose: rebuild evaluator when customDrawOrder or timing changes (stale remember key)"`.

### Task 2: Config stagger overrides are checked against the whole stagger pair

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/PlanRemember.kt:53-54`
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/MorphParameterApplicationTest.kt`

**Interfaces:** Consumes `MorphConfiguration.staggerDelay/staggerOrder`, `VectorAnimation.staggerDelay/staggerOrder`. Produces no API change. Semantics: a config "supplies" stagger when `staggerDelay > 0` **or** `staggerOrder != MorphConfiguration.Default.staggerOrder`; supplied values win field-by-field against the timeline's, otherwise the timeline's own stagger stands.

- [ ] **Step 1: Write the failing test** — timeline with `stagger(delay, BOTTOM_RIGHT_TO_TOP_LEFT)`; render two configs: `MorphConfiguration(staggerOrder = BY_NAME)` (delay stays 0 override) and default. Assert the two frames differ mid-morph beyond noise:

```kotlin
@Test
fun configStaggerOrderOverridesTimelineOrderWithoutConfigDelay() {
    // timeline stagger(delay = 0.2f, order = BOTTOM_RIGHT_TO_TOP_LEFT)
    val withConfigOrder = harness.renderMorph(from, to, 0.5f,
        configuration = MorphConfiguration(staggerOrder = DrawOrderStrategy.BY_NAME), animation = threeRevealStagger(order = BOTTOM_RIGHT_TO_TOP_LEFT))
    val without = harness.renderMorph(from, to, 0.5f, configuration = MorphConfiguration.Default, animation = threeRevealStagger(order = BOTTOM_RIGHT_TO_TOP_LEFT))
    assertTrue(difference(withConfigOrder, without) > 0.005f)
}
```

- [ ] **Step 2: Run** → FAIL (config order ignored while `staggerDelay == 0`).
- [ ] **Step 3: Implement** at `PlanRemember.kt:53-54`:

```kotlin
val configSuppliesStagger = configuration.staggerDelay > 0f ||
    configuration.staggerOrder != MorphConfiguration.Default.staggerOrder
// then, inside the evaluator constructor:
staggerDelay = if (configuration.staggerDelay > 0f) configuration.staggerDelay else it.staggerDelay,
staggerOrder = if (configSuppliesStagger) configuration.staggerOrder else it.staggerOrder,
```

- [ ] **Step 4: Run** → PASS (plus all of `MorphParameterApplicationTest`, `TimelineEvaluatorParamsTest`).
- [ ] **Step 5: Commit** — `git commit -m "compose: honor config staggerOrder (and delay/channel overrides) independent of staggerDelay===0"`.

### Task 3: Stagger offsets actually squeeze

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrder.kt:94-111`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/animation/TimelineEvaluator.kt:182` (drop the flat clamp; keep a defensive `coerceAtMost(1f)`)
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrderAndDirectionTest.kt`

**Interfaces:** `DrawOrder.staggerOffsets(plan, order, staggerDelay): FloatArray` signature unchanged; new guarantee: offsets are `min(delay*rank, squeeze)` with `squeeze` applied proportionally so the last offset ≤ 0.95 and relative rank spacing is preserved whenever possible.

- [ ] **Step 1: Write the failing test**:

```kotlin
@Test
fun staggerOffsetsSqueezeToKeepTheLastPathOnTimeline() {
    val offsets = DrawOrder.staggerOffsets(planWith6Pairs, order0to5, staggerDelay = 0.4f)
    assertTrue("last offset must fit the timeline", offsets.max() <= 0.95f)
    // proportional squeeze keeps ranking distinct instead of clamping collinear
    assertTrue("late paths keep distinct starts", offsets[4] > offsets[3])
}
```

(`planWith6Pairs` — reuse the smallest existing six-pair plan fixture in this test file's helpers; if none exists, build via `MorphPlanner.plan` on two 6-filled-path vectors compiled with `VectorCompiler`.)

- [ ] **Step 2: Run** → FAIL (old code: offsets 3..5 all clamp to 0.95).
- [ ] **Step 3: Implement** — in `staggerOffsets`, after computing `offsets[i] = delay * rankOf[i]`: if `offsets.max() > 0.95f` scale all values by `0.95f / max`. Update the kdoc to say "squeezed proportionally so the last path still starts at ≤ 0.95" (matches the existing doc's promise).
- [ ] **Step 4: Run** → PASS, plus `TimelineEvaluatorParamsTest` (its stagger expectations must survive).
- [ ] **Step 5: Commit** — `git commit -m "core: squeeze stagger offsets so large delays keep every path visible"`.

### Task 4: Short paths stay visible under stagger + `BY_PATH_LENGTH`

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/animation/TimelineEvaluator.kt:378-402`
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/animation/TimelineEvaluatorParamsTest.kt`

**Interfaces:** `progressForPair` private; behavior contract: when stagger is active (`maxStaggerOffset > 0`), every path's mapped progress window is at least `MIN_STAGGER_WINDOW = 0.05` of the timeline. No public API change.

- [ ] **Step 1: Write the failing test** (mirror the fixture style of `uniformTimingIgnoresPathLengths`, `TimelineEvaluatorParamsTest.kt:232` — one long stroked path + one near-zero-length path, timeline with `stagger(delay = 0.2f, …)`, timing default `BY_PATH_LENGTH`):

```kotlin
@Test
fun staggerKeepsShortPathsVisibleUnderPathLengthTiming() {
    // evaluate a frame at p = shortOffset + 0.03  (inside the old saturation dead zone)
    val frame = evaluateAt(0.5f) // long path visibly underway
    val shortPair = frame.pathProps[shortPairIndex]
    assertTrue("short path must still be mid-draw, not finished",
        shortPair.strokeRevealProgress < 1f)
}
```

(Read the drawn state the same way `uniformTimingIgnoresPathLengths` asserts clocks.)

- [ ] **Step 2: Run** → FAIL (short path already saturated to 1).
- [ ] **Step 3: Implement** — in the `BY_PATH_LENGTH` branch, clamp the speed-up factor by the path's remaining window:

```kotlin
TimingMode.BY_PATH_LENGTH -> {
    val length = pathLengths[pairIndex]
    if (length <= 1e-5f || maxPathLength <= 1e-5f) return staggered
    var speed = maxPathLength / length
    if (maxStaggerOffset > 0f) {
        val window = (1f - staggerOffsets[pairIndex]).coerceAtLeast(0.05f)
        speed = speed.coerceAtMost(window / MIN_STAGGER_WINDOW)
    }
    (staggered * speed).coerceIn(0f, 1f)
}
```

(`companion object { private const val MIN_STAGGER_WINDOW = 0.05f }`; extend the inline comment at lines 380-383 to document the guarantee.)
- [ ] **Step 4: Run** → PASS plus `MorphPlanningTest:205` (`pathLengthTimingLetsShortPathsFinishBeforeLongPaths` must stay green — that test runs without stagger).
- [ ] **Step 5: Commit** — `git commit -m "core: guarantee a visible draw window for short paths when stagger + BY_PATH_LENGTH combine"`.

---

# Phase 2 — Make the "identical" enum pairs distinct

### Task 5: `BY_NAME` stops consulting `customDrawOrder`

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrder.kt:25-29`
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrderAndDirectionTest.kt`

**Interfaces:** No signature change. After this task `customDrawOrder` affects only `DrawOrderStrategy.CUSTOM` (and z-order for `CUSTOM` when `keepRenderOrder = false`).

- [ ] **Step 1: Write the failing test**:

```kotlin
@Test
fun byNameIgnoresCustomOrderList() {
    val withList = DrawOrder.orderIndices(plan, DrawOrderStrategy.BY_NAME, listOf("c", "b", "a"))
    val plain = DrawOrder.orderIndices(plan, DrawOrderStrategy.BY_NAME, emptyList())
    assertTrue(withList.contentEquals(plain))
}
```

- [ ] **Step 2: Run** → FAIL (today `withList` reorders).
- [ ] **Step 3: Implement** — replace `val order = customOrder.ifEmpty { ...sorted names... }` with the always-derived `val order = pairs.mapNotNull { pathOf(it)?.name }.sorted()`; pass that to `sortByNames`. Remove `customOrder` from any BY_NAME path (the `when` branch takes no params).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "core: BY_NAME draw order derives name rank itself; customDrawOrder is CUSTOM-only"`.

### Task 6: `strokeFill = MORPH` gets its documented stroke-shrink behavior

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt:409-419` (pair construction) and `decideStrokeFill` (:611-626)
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlan.kt` (new field `strokeFillMorph: Boolean` on `PathPairPlan`, default `false`, included in `StructuralKey` if pairs hold one)
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/StrokePass.kt` (width scale) and `MorphRenderer.kt` (pass scale), `FillPass.kt:137-147` (fill window for MORPH)
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/plan/MorphPlannerFallbackTest.kt`, `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/MorphParameterApplicationTest.kt`

**Interfaces (new):**
- Planner: `decideStrokeFill(f, t, config): StrokeFillDecision` where `data class StrokeFillDecision(val draw: Boolean, val morph: Boolean)` — `MORPH -> (draw=false, morph=true)`, `CROSSFADE -> (false,false)`, `DRAW →` unchanged `(true,false)`, AUTO resolves into one of the three.
- Renderer: `StrokePass` gains `strokeWidthScale: Float? = null` on its paint entry (MorphRenderer computes `if (pair.strokeFillMorph) 1f - morphT else null`); scale multiplies the interpolated stroke width (after `STROKE_WIDTH` track override), floor 0.
- `FillPass:139-147`'s `effectiveFill` gains the branch `pair.strokeFillMorph -> morphT` (fill grows with the morph while the stroke shrinks).

- [ ] **Step 1: Write failing tests**:
  Core — `morphStrokeFillPlansAStrokeShrinkPair`: config `MorphConfiguration(strokeFill = StrokeFillStrategy.MORPH)` over a filled+stroked pair asserts `pairs.single().strokeFillMorph == true` (and `CROSSFADE` maps to `false`).
  Compose — bitmap probe: same pair rendered at `0.5` under `MORPH` vs `CROSSFADE` differ > 0.01 mid-frame (MORPH shows a shrunk, partially-orange fill; CROSSFADE shows the plain crossfade):
  
```kotlin
@Test
fun strokeFillMorphShrinksTheStrokeMidMorph() { /* renderMorph at 0.5 with config strokeFill=MORPH vs CROSSFADE; assertTrue(difference > 0.01f) */ }
```

- [ ] **Step 2: Run** → both FAIL.
- [ ] **Step 3: Implement** planner decision object + pair field + renderer wiring as in Interfaces. `MorphRenderer.render` already has `pair` and `morphT` at the pair loop; thread the scale into the stroke paint call only when the pair carries both fill and stroke (otherwise `null`).
- [ ] **Step 4: Run** module suites → PASS.
- [ ] **Step 5: Commit** — `git commit -m "core+compose: implement StrokeFillStrategy.MORPH (stroke shrinks while fill grows)"`.

### Task 7: `FillMode.LINEAR` becomes the hard wipe, `DIRECTIONAL` grows the feathered sweep

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/renderer/FillPass.kt:345-365`
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/MorphParameterApplicationTest.kt`

**Decision (target semantics):** the existing shared hard-edged clip stays as **LINEAR** ("hard-edged linear sweep", kdoc as-written). **DIRECTIONAL** becomes the same positioned sweep but painted through a linear-gradient alpha ramp around the moving edge — feather band width `0.15 * axisLength`, ramp fully open behind the edge, fully closed ahead. The clip-mask route in `ClipPass.withFillRevealClip` (used only when a stroke reveal owns the path) keeps the hard edge for every mode — document that in its kdoc ("behind-the-pen sweeps are hard-edged by design; feathering applies to standalone fills").

- [ ] **Step 1: Write the failing test** (both directions must now differ):

```kotlin
@Test
fun linearAndDirectionalFillModesRenderDifferentMidFrames() {
    val linear = harness.renderMorph(from, to, 0.5f, animation = fillReveal(mode = FillMode.LINEAR, direction = DrawDirection.LEFT_TO_RIGHT))
    val directional = harness.renderMorph(from, to, 0.5f, animation = fillReveal(mode = FillMode.DIRECTIONAL, direction = DrawDirection.LEFT_TO_RIGHT))
    assertTrue("LINEAR must be a hard wipe vs DIRECTIONAL's feather", difference(linear, directional) > 0.005f)
}
```

- [ ] **Step 2: Run** → FAIL (identical today).
- [ ] **Step 3: Implement** — split the shared branch. In the DIRECTIONAL arm, replace `clipPath` with a saveLayer painted through `Brush.linearGradient(colors, start = edge-behind, end = edge-ahead, tileMode = TileMode.Clamp)` applied as a mask: simplest faithful implementation keeps the clip but adds an alpha ramp by drawing the fill twice — actually implement via shader mask: `drawScope.clipPath(hardPath) { drawScope.drawIntoCanvas { ... } }` is overkill; preferred concrete approach: keep `clipPath` for the region *behind* the ramp start, and in the ramp band draw the fill with `paintFillDirect(path, result, alpha * bandFraction)` clipped to the band tile — the implementer may use any mask construction as long as `paintFillDirect` still receives the original brush and the edge pixel test pins a partial-alpha band. Extend `GradientInterpolator` reuse: no changes.
- [ ] **Step 4: Run** → PASS; also `fillModesRenderDifferentMidFrames` (RADIAL/…, unchanged) stays green.
- [ ] **Step 5: Commit** — `git commit -m "compose: distinguish FillMode.LINEAR (hard wipe) from DIRECTIONAL (feathered sweep)"`.

### Task 8: One SWEEP start angle for both fill sites

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrder.kt` (new `DirectionMath.sweepStartAngle(direction): Float`)
- Modify: `vector-morph-compose/.../renderer/ClipPass.kt:168-183` and `FillPass.kt:367-389`
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/MorphParameterApplicationTest.kt`

**Interfaces (new):** `DirectionMath.sweepStartAngle(direction: DrawDirection): Float` — degrees for the arc that starts "where the direction's anchor is": for `CLOCKWISE`/`COUNTER_CLOCKWISE` (and any direction whose anchor is the top: `TOP_TO_BOTTOM`, `TOP_LEFT…`) it is `-90f` (unchanged historical default for those), for `LEFT_TO_RIGHT` `180f`, `RIGHT_TO_LEFT` `0f`, `TOP_TO_BOTTOM`, `BOTTOM_TO_TOP`, corners per `anchorFor`'s atan2 mapping — exactly the formula currently in `FillPass.kt:372-377`.

- [ ] **Step 1: Write the failing test** — pair fill (square→square, fill on both sides so ClipPass runs) with `reveal(fill(mode = SWEEP, direction = DrawDirection.LEFT_TO_RIGHT))`: mid-frame must differ from the same fill with `direction = DrawDirection.TOP_TO_BOTTOM`:

```kotlin
@Test
fun sweepOnPairFillsHonorsDirectionStartAngle() {
    val left = harness.renderMorph(from, to, 0.5f, animation = pairFillSweep(DrawDirection.LEFT_TO_RIGHT))
    val top = harness.renderMorph(from, to, 0.5f, animation = pairFillSweep(DrawDirection.TOP_TO_BOTTOM))
    assertTrue(difference(left, top) > 0.01f)
}
```

(pairFillSweep uses a vector-level `fill(mode = SWEEP, direction = …)` track so the pair fill goes through `ClipPass`.)

- [ ] **Step 2: Run** → FAIL (ClipPass always starts at -90, so both directions match).
- [ ] **Step 3: Implement** — move `FillPass`'s start-angle `when` into `DirectionMath.sweepStartAngle`; call it from `ClipPass` and `FillPass` (`startAngle = DirectionMath.sweepStartAngle(direction)`). `clockwise` flag logic in both sites stays as-is.
- [ ] **Step 4: Run** → PASS; confirm `DrawParamCoherenceTest` showcase expectations unaffected (they don't cover pair-fills with axis directions).
- [ ] **Step 5: Commit** — `git commit -m "compose: SWEEP reveal start angle derives direction consistently at both fill sites"`.

### Task 9: Thread `timing`, `honorDirection`, and custom anchors into the ignored render sites

**Files:**
- Modify: `vector-morph-compose/.../renderer/StrokePass.kt:179-243` (`drawMissingSide` gains `honorDirection`, `timingMode`, `customStartFraction` params) and `:513-546` (`drawCustomContours` gains `timingMode`, `customStartFraction`)
- Modify: `vector-morph-compose/.../renderer/MorphRenderer.kt` (call sites pass `config.directionTraversal == HONOR_DIRECTION`, `config.timing`, and the pair's `drawAnchorFor` value)
- Modify: `vector-morph-compose/.../renderer/FillPass.kt:421-434` (PATH_TRAVERSAL branch passes `timingMode = config-timing`, `customStart = anchor`, `honorDirection`)
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/TraversalPolicyApplicationTest.kt`, `MorphParameterApplicationTest.kt`

**Interfaces:** no new public API; parameters mirror those `drawMorph` already threads (`StrokePass.kt:60-83`). Defaults preserve today's behavior (`honorDirection = false`, `timingMode = mapper-default`) so custom-geometry callers compile unchanged.

- [ ] **Step 1: Write failing tests** (three probes, name style of `TraversalPolicyApplicationTest`):

```kotlin
@Test // a) missingPath = DRAW honors directionTraversal
fun drawMissingPathHonorsTraversalPolicy()
@Test // b) PATH_TRAVERSAL fill honors config timing
fun pathTraversalFillHonorsTimingMode()   // BY_PATH vs UNIFORM differ mid-frame
@Test // c) PATH_TRAVERSAL fill honors custom anchors
fun pathTraversalFillHonorsCustomDrawAnchors() // anchor 0.5 vs 0.0 differ; buildRevealedStrokePath start = PathStart.CUSTOM
```

For (c): pass a timeline `draw(start = PathStart.CUSTOM)` + `fill(mode = PATH_TRAVERSAL)` and configuration `customDrawAnchors = mapOf("stroke" to 0.5f)`; render with/without the anchor entry → frames differ.
For (a): `missingPath = DRAW`, open contour, direction `LEFT_TO_RIGHT`, `HONOR_DIRECTION` vs `LEGACY` render mid-frames different (today identical).
For (b): fill `PATH_TRAVERSAL` with `configuration.timing = BY_PATH` vs `UNIFORM`; short-contour pairs must differ mid-frame (stale default was UNIFORM-only path).

- [ ] **Step 2: Run** → all three FAIL.
- [ ] **Step 3: Implement** — add the parameters and thread them from `MorphRenderer` exactly as `drawMorph` does (`MorphRenderer.kt:979-1188`); in FillPass resolve `customStartFraction` via the anchors chain (`StrokePass.drawAnchorFor`, used by `MorphRenderer.kt:846,977,1183`).
- [ ] **Step 4: Run** both module suites → PASS; `RetargetProbeTest`/goldens unaffected (defaults preserve old frames for configs that don't use these knobs... except (a)/(b)/(c) tests themselves are behavior changes — expected, they are the fixes).
- [ ] **Step 5: Commit** — `git commit -m "compose: directionTraversal/timing/custom anchors apply at missing-side, custom-geometry, and PATH_TRAVERSAL fill sites"`.

### Task 10: Render the debug `markerArcFraction` markers

**Files:**
- Modify: `vector-morph-compose/.../renderer/MorphRenderer.kt` (`drawDebugOverlay`, ~:335-432)
- Modify: `vector-morph-core/.../model/MorphConfiguration.kt:112-113` (kdoc: remove "reserved" wording in docs; the field kdoc stays truthful)
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/DrawDebugOverlayTest.kt`

**Interfaces:** none new. Semantics: when `debug != null` and `debug.markerArcFraction in 0.001..0.999`, draw a small magenta ring at the arc-fraction position of every morph pair's target contour (reuse the same PathMeasure used by `showStartPoints`, offset unless it collides with the start-point dot).

- [ ] **Step 1: Write the failing test** (style of `DrawDebugOverlayTest`):

```kotlin
@Test
fun markerArcFractionDrawsMarkersThatMove() {
    val at25 = harness.renderMorph(from, to, 0.5f, configuration = debugOption(markerArcFraction = 0.25f))
    val at75 = harness.renderMorph(from, to, 0.5f, configuration = debugOption(markerArcFraction = 0.75f))
    assertTrue(difference(at25, at75) > 0.005f)   // marker renders and follows the fraction
    val none = harness.renderMorph(from, to, 0.5f, configuration = debugOption(markerArcFraction = 0f))
    assertTrue(difference(at25, none) > 0.005f)   // 0 keeps drawing nothing extra
}
```

- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** in `drawDebugOverlay`: for each pair with a revealed target contour, measure the interpolated target path (same measurement the start-point overlay uses), `getSegment(0f, fraction * len, markerPath, true)`, draw the marker at `markerPath.getLastPt()`.
- [ ] **Step 4: Run** → PASS; `DrawDebugOverlayTest` existing cases stay green.
- [ ] **Step 5: Commit** — `git commit -m "compose: render debug markerArcFraction as a moving contour marker"`.

---

# Phase 3 — Static validation & honest planning warnings

### Task 11: Planner records when fallback discards `missingPath`

**Files:**
- Modify: `vector-morph-core/.../plan/MorphPlanner.kt:85-111` (crossfade-all branch)
- Test: `vector-morph-core/.../plan/MorphPlannerFallbackTest.kt`

**Interfaces:** no API change; the appearing/disappearing pairs produced while `fallbackDecision.crossfadeAll` carry a warning string on the pair (`pair.warnings` extended upstream at pair construction, same channel `validatePlan` already escalates): `"FallbackStrategy ${decision.strategy} crossfades every pair; missingPath ${config.missingPath} is ignored"`.

- [ ] **Step 1: Write the failing test** — `fallback = CROSSFADE` + `missingPath = DRAW`, a plan with one appearing path: assert the plan's report/issues (or pair warnings) include the conflict string.

```kotlin
@Test
fun crossfadeFallbackRecordsIgnoredMissingPath() {
    val plan = planWith(fallback = CROSSFADE, missingPath = MissingPathBehavior.DRAW, oneAppearingPath)
    assertTrue(plan.report.issues.any { it.message.contains("missingPath") })
}
```

- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — thread the string into `planAppearingPair`/`planDisappearingPair` when `fallbackDecision.crossfadeAll && config.missingPath != MissingPathBehavior.FADE` (append to the pair's warnings list the same way existing fallback warnings flow into `MorphReport`).
- [ ] **Step 4: Run** `MorphPlannerFallbackTest` → PASS (existing `:360-367` pins keep passing).
- [ ] **Step 5: Commit** — `git commit -m "core: plan diagnosis records missingPath discarded by crossfade fallbacks"`.

### Task 12: `MorphValidator.validateConfiguration` — static audit of parameter conflicts

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/validation/MorphValidator.kt` (new public fun)
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/validation/MorphValidatorTest.kt`

**Interfaces (new public API):**

```kotlin
/** Static audit of configuration/timeline parameter conflicts; never throws. */
fun MorphValidator.validateConfiguration(
    config: MorphConfiguration,
    animation: VectorAnimation? = null,
): List<MorphIssue>
```

Checks emitted (category strings chosen to match the existing lowercase style):
| Condition | Severity/category |
| --- | --- |
| `customDrawAnchors` non-empty and animation has no segment whose reveal start == `PathStart.CUSTOM` (walk `animation.tracks` segments for the reveal payload carrying `start`; permits legacy `customStartOffsets` fallback count) | WARNING `custom-anchors-unused` |
| `customDrawOrder` non-empty and `drawOrder != CUSTOM` | WARNING `custom-order-unused` |
| `missingPath != FADE` and `fallback ∈ {CROSSFADE, SCALE_CROSSFADE}` | WARNING `missing-path-under-crossfade` |
| `fallback == THROW` (kdoc: throws only on structural emptiness — remind) | INFO `fallback-throw-scope` |
| `drawOrder != BY_INDEX` while `keepRenderOrder == true` | INFO `draw-order-not-rendered` (z-order note) |
| `staggerOrder` set while `staggerDelay == 0` | INFO `stagger-order-without-delay` |
| A fill reveal track declares `mode ∈ {FADE, RADIAL}` with a non-AUTO direction | WARNING `fill-direction-ignored` |
| `direction == CENTER_OUT`/`OUTSIDE_IN` on a stroke-reveal segment | INFO `sweep-direction-on-stroke` (mirrors the runtime warning) |
| `customMappings` non-empty while `pathMatching ∉ {AUTO, CUSTOM}` | INFO `custom-mappings-unused` (BY_NAME/GEOMETRY/AREA/CENTROID/INDEX/ID ignore them — A28) |

- [ ] **Step 1: Write failing tests** — one per row: build `MorphConfiguration(...)` (+ minimal DSL animation via `VectorAnimationBuilder` where needed) and assert issue category presence/absence; etiquettes: `coherentDefaultConfigIsSilent` mirrors runtime behavior.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** the checks; walk `animation.tracks` (internal to core — validator is in core) for `Segment.reveal` payloads; keep the function pure and allocation-light.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "core: MorphValidator.validateConfiguration audits conflicting parameter combos"`.

---

# Phase 4 — Cache & API surface

### Task 13: `MorphPrecompiler.isPrepared` accepts a configuration

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/MorphPrecompiler.kt:51-54`
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/api/ComposeApiSurfaceTest.kt`

**Interfaces (new overload):** `fun isPrepared(from: ImageVector, to: ImageVector, configuration: MorphConfiguration = MorphConfiguration.Default): Boolean` — old 2-arg call keeps source/binary compatibility via the default value.

- [ ] **Step 1: Write failing test** — `warm(from, to, warmConfig)` then `isPrepared(from, to, warmConfig)` must be `true` (today `false` because it probes Default).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — add the parameter, probe with `configuration.copy(animation = null)`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "compose: isPrepared probes the caller's configuration"`.

### Task 14: Plan cache keys on a planning-only key

**Files:**
- Create: `vector-morph-core/.../model/PlanningKey.kt`
- Modify: `vector-morph-core/.../plan/MorphPlanner.kt:693-710` (`Key` uses `PlanningKey`; keep `MorphPlanCache.getOrPut(from, to, config)` overload delegating)
- Modify: `vector-morph-compose/.../PlanRemember.kt:89-96`, `MorphPrecompiler.kt:30-37,62` (call the key directly)
- Test: `vector-morph-core/.../plan/MorphPlanCacheByteBudgetTest.kt` (new case), `MorphPlanningTest.kt`

**Interfaces (new public API, core):**

```kotlin
/** Fields that influence planning. Render-only fields deliberately excluded. */
data class PlanningKey(
    val pathMatching: PathMatchingStrategy, val startPoint: StartPointStrategy,
    val pathDirection: PathDirectionStrategy, val customMappings: List<PathMapping>,
    val missingPath: MissingPathBehavior, val fallback: FallbackStrategy,
    val strokeFill: StrokeFillStrategy, val segmentBudget: Int?,
    val simplificationTolerance: Float, val viewportNormalization: ViewportNormalization,
)
/** on MorphConfiguration: */ fun MorphConfiguration.planningKey(): PlanningKey
/** on MorphPlanCache: */ fun getOrPut(preparedFrom, preparedTo, key: PlanningKey, producer: () -> MorphPlan): MorphPlan
```

Fields enumerated = exactly the config reads in the planner today (`MorphPlanner.kt:58-63, 313-337, 616, 552-570`); if Task 6 changes the consumed set (it adds no planning field — MORPH resolution is config-driven already), keep the list in sync.

- [ ] **Step 1: Write failing test** — cache test: `getOrPut(from,to,config(a=...))` plausibly fills with `debug = MorphDebugOptions(...)` toggled to a different value (via two full configs) must REUSE the plan instance (reference equality):

```kotlin
@Test
fun planCacheIgnoresRenderOnlyFields() {
    val planA = cache.getOrPut(from, to, config.copy(debug = null)) { plan(...) }
    val planB = cache.getOrPut(from, to, config.copy(debug = MorphDebugOptions(showCentroids = true))) { plan(...) }
    assertSame(planA, planB)
}
```

- [ ] **Step 2: Run** → FAIL (distinct keys, second miss).
- [ ] **Step 3: Implement** — key splitting; the caching callers switch to the PlanningKey overload (`PlanRemember.kt` also swaps its `remember` key from the full `planningConfiguration` to `from`+`to`+`planningConfiguration.planningKey()` so Compose recomposition doesn't re-plan on debug toggles either). Note the deliberate consequence: `CROSSFADE_ONLY` forcing still re-keys (fallback is planning-relevant) — documented at `MorphIcon.kt` kdoc in Task 15 docs.
- [ ] **Step 4: Run** `MorphPlanCacheByteBudgetTest`, `MorphPlanningTest`, `StructuralKeyTest`, compose unit suites → PASS.
- [ ] **Step 5: Commit** — `git commit -m "core+compose: plan cache keys a dedicated planning key; render-only config fields stop invalidating plans"`.

### Task 15: Deprecate the misleading no-op overloads and double homes

**Files:**
- Modify: `vector-morph-compose/.../VectorAnimationEntry.kt:68-75` (`rememberVectorAnimation(definition,)` → `@Deprecated`)
- Modify: `vector-morph-compose/.../MorphIcon.kt:89,97,154` (`animation: VectorAnimationSpec?` parameter → kdoc + deprecation note pointing at `configuration.animation`; keep behavior)
- Test: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/api/ComposeApiSurfaceTest.kt`

**Interfaces:** deprecations only, no behavior change:
- `@Deprecated("Parameter is ignored by this overload; use the three-argument form and hand the definition to VectorAnimator", level = DeprecationLevel.WARNING) fun rememberVectorAnimation(animation: VectorAnimationDefinition, ...)`.
- `MorphIcon`'s `animation` parameter: add `@DeprecationLevel.WARNING` deprecation with message "Two homes: set configuration.animation instead; the parameter only shadows it and doubles the override inside the overload". (Binary compatibility kept — deprecating a parameter is annotation-only.)

- [ ] **Step 1: Write the api-surface test** (compile-level assertions used by `ComposeApiSurfaceTest`): reflection-check the annotation exists on the overload and on the parameter.
- [ ] **Step 2: Run** → FAIL (annotations missing).
- [ ] **Step 3: Implement** exactly the two annotations + kdoc paragraphs (also document the `CROSSFADE_ONLY` fallback override in the same kdoc: "Accessibility CROSSFADE_ONLY resolves the effective fallback to CROSSFADE and plans a second cache entry").
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "compose: deprecate the ignored rememberVectorAnimation(definition) overload and MorphIcon.animation shadowing"`.

### Task 16: Kill the dead wires

**Files:**
- Modify: `vector-morph-core/.../animation/VectorAnimation.kt` (remove `appearanceOnly` constructor property), `VectorAnimationBuilder.kt` (delete the flag maintenance), `VectorAnimationCodec.kt:47,128` (keep the stream slot: `out.writeBoolean(false)` / `input.readBoolean()` discarded, comment "slot reserved, was appearanceOnly ≤ 0.2"),
- Modify: `vector-morph-core/.../animation/TimelineEvaluator.kt:511-512` (delete `pairSpace`), `vector-morph-core/.../geometry/PathNodeNormalizer.kt:49-51` (delete `ensureBegun`)
- Modify: `vector-morph-compose/.../PlanRemember.kt:46` (delete unreachable `?: configuration.animation`)
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/VectorAnimationCodecTest.kt`, `VectorAnimationBuilderTest.kt:226-244`, `SpringAndCodecTest.kt:135`, `CodecV2Test.kt:68`, `DeprecatedDrawConfigForTest.kt`

- [ ] **Step 1: Update tests first** — delete the `appearanceOnly` assertions (`VectorAnimationBuilderTest.kt:226-244`, `CodecV2Test.kt:68`, `SpringAndCodecTest.kt:135` line); keep a codec round-trip test asserting an old stream (boolean written) decodes fine.
- [ ] **Step 2: Run** → the simplified suite is the new baseline (plus remaining failures drive implementation).
- [ ] **Step 3: Implement** — remove the field/var/update logic; codec keeps writing/reading the reserved slot; delete `pairSpace`, `ensureBegun`, dead branch.
- [ ] **Step 4: Run** both module suites → PASS.
- [ ] **Step 5: Commit** — `git commit -m "core: remove dead appearanceOnly flag, pairSpace, ensureBegun; reserve codec slot"`.

### Task 17: Inline-DSL one-shots stop restarting (stable key)

**Files:**
- Create: `vector-morph-core/.../animation/AnimationStableKey.kt` — `public fun VectorAnimation.stableKey(): Int` (murmur3-style fold over track/segment scalar fields: target names, prop ordinals, from/to/interval floats, easing ordinal, reveal payload fields, brush specs' structural hash — mirrors the hash used by `model/StructuralKey.kt` if shared, else a self-contained fold). Document: recomposing compositions should key `remember`/`LaunchedEffect` on it.
- Modify: `vector-morph-compose/.../VectorAnimationEntry.kt:215,218` (key `remember`/`LaunchedEffect` on `animation.stableKey()`; `vector` stays identity-keyed — ImageVector identity is stable)
- Test: `vector-morph-core/.../animation/AnimationStableKeyTest.kt` + one compose probe

**Interfaces (new public API, core):** `fun VectorAnimation.stableKey(): Int`. Contract: two builds from identical DSL calls return the same key; any track/segment/payload difference changes it. Customizer callbacks and brush *identity* are folded as `system.identityHashCode` (documented: unequal across processes by design).

- [ ] **Step 1: Write failing tests**:

```kotlin
@Test fun identicalDslBuildsShareAStableKey() {
    fun build() = vectorAnimation(vector) { path("stroke") { drawProgress(0f..1f) } }
    assertEquals(build().stableKey(), build().stableKey())
}
@Test fun changedIntervalChangesTheKey() {
    assertTrue(build().stableKey() != build().copy-ish(intervalAChanged)...)
}
```

Plus a compose probe: compose `VectorAnimation(vector, inlineDslBuild())` twice in Robolectric — after pumping, the bitmap at rest equals the settled state (progress reached 1; identity-keyed version restarts forever). Compose probe asserts settled state via `RenderProbeHarness` by pumping the looper far enough.

- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — the fold in `AnimationStableKey.kt` (no public mutable state, safe on any thread) + swap effect keys in `VectorAnimationEntry.kt`.
- [ ] **Step 4: Run** both suites + `RetargetProbeTest` androidTest skipped locally (needs device; note in PR description) → PASS for unit suites.
- [ ] **Step 5: Commit** — `git commit -m "core+compose: VectorAnimation.stableKey; inline-DSL one-shots survive recomposition"`.

---

# Phase 5 — Docs & API-hygiene sweep

### Task 18: Documentation + remaining aliases/finalization

**Files:**
- Modify: `docs/CONFIGURATION.md`, `docs/DIAGNOSTICS.md`, `docs/MIGRATION.md`, `CHANGELOG.md`, `vector-morph-core/.../model/Enums.kt` (kdoc), `MorphConfiguration.kt` (kdoc for the rewritten rules), `README.md` (parameter quick-links)
- Modify: `vector-morph-core/.../model/Enums.kt` (`@Deprecated` on `DrawOrderStrategy.BY_POSITION` with message "Alias of TOP_LEFT_TO_BOTTOM_RIGHT; use AUTO/TOP_LEFT_TO_BOTTOM_RIGHT. Removal in 0.4" — deprecate only after Task 5's semantics land, limited to enum entry annotation, source-warning level)
- Test: none new (docs); run full suites as regression gate.

Content requirements (each is a promise the code now keeps after Tasks 1–17):

- `drawOrder` row: "Controls **render z-order only when `keepRenderOrder = false`**; stagger timing is always assigned from `staggerOrder`." — replaces the wrong "drives stagger timing" sentence.
- `staggerOrder` row: documents `PlanRemember`'s override rule (config supplies stagger when delay > 0 **or** order ≠ default; otherwise the timeline's own stagger stands; a config cannot cancel a timeline's stagger).
- `timing BY_COMMAND` row: "At the vector/group clock this behaves like `UNIFORM`; only per-contour stroke tracing applies command-count remapping."
- `missingPath` row: gains "Ignored (with a plan warning) whenever a crossfade fallback crossfades every pair."
- `customStartOffsets` row: explicit "alignment today still reads only this map under `StartPointStrategy.CUSTOM`; `customDrawAnchors` never moves geometry" + migration checklist note (interim state until the 0.3 alignment home lands).
- `customDrawAnchors` row: "Requires `PathStart.CUSTOM` (per draw track); otherwise silently unused — `MorphValidator.validateConfiguration` warns."
- `debug.markerArcFraction` row: rendered behavior (magenta ring at fraction).
- New sections: "Fill-mode rendering map" table (FADE ignores direction; RADIAL ignores direction; LINEAR hard wipe; DIRECTIONAL feathered; SWEEP per-direction start angle at both sites; PATH_TRAVERSAL follows stroke choreography and inherits its mode/start/direction) + "Sites that honor `directionTraversal`" list (morph-pair stroke, missing-path DRAW reveal, PATH_TRAVERSAL fill, custom contours — all after Task 9) with the still-excluded closed-contour/winding rule.
- `DIAGNOSTICS.md`: add the `validateConfiguration` issue categories + plan warning from Task 11.
- `MIGRATION.md`: deprecations (Task 15), `BY_POSITION`, notes for `isPrepared(config)` and `stableKey()`.
- `CHANGELOG.md`: 0.3.0-Unreleased section, one bullet per behavior change from Tasks 3, 4, 6, 7, 8, 9, 10, 14.

- [ ] **Step 1: Write/update all docs** per the list above (no test steps; keep the enum deprecation annotation in the same commit).
- [ ] **Step 2: Run the full unit gate** `./gradlew.bat :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest :app:testDebugUnitTest :benchmark:test` → PASS (app/benchmark compile unchanged).
- [ ] **Step 3: Commit** — `git commit -m "docs: parameter reference reflects resolved precedence, distinct fill modes, and 0.3 deprecations"`.

---

## Deferred (explicitly out of this plan)

- A modern home for `StartPointStrategy.CUSTOM` alignment (A13 full deprecation path) — needs a data-model decision (`PathMapping(source, target, startOffset?)` vs a new map); landed as the `customStartOffsets` deprecation-completion story of the 0.3 API cleanup. Interim state stays documented (Task 18).
- Nullable stagger/draw-order override model (`staggerDelay: Float?`, `staggerOrder: DrawOrderStrategy?`) — the 0.3+ primary-constructor redesign; Task 2 removes the worst sharp edge first.
- `MotionPreferenceResolver` runtime reactivity to system animation-scale changes ("Remove animations" toggled while composed) — platform limitation, documented only.
- `ContentScale` crop/fill, KMP rendering — untouched per strategy docs.

## Task dependency notes

Tasks 1–4 are independent of each other and of everything downstream. Task 5 must precede Task 18's `BY_POSITION` note. Tasks 6–8 are independent of each other but all touch the renderer passes (sequence 6 → 7 → 8 to minimize merges). Task 9 depends on nothing but Touches the same files as 6–8 — run 9 last in Phase 2. Task 14's field list depends on Task 6's planner change (no new planning fields, but review the consumed-set list). Task 18 is last.
