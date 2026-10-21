# DrawConfig Structural Cleanup (C6 Follow-up) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the one-size-fits-all `DrawConfig` (5 fields, attached to DRAW_PROGRESS / FILL_PROGRESS / CLIP_PROGRESS tracks alike) into per-purpose typed reveal configs, so each property carries only the fields its renderer actually consumes — ending the "fill config with never-consumed mode/start/direction" trap (plan C6).

**Architecture:** Introduce `sealed interface VectorAnimation.Reveal{}` with three variants — `DrawConfig` (mode/start/direction, stroke semantics, existing name preserved), new `FillRevealConfig(fillMode, fillDirection)`, new `ClipRevealConfig(direction)` — carried by `Segment.reveal`. `PathFrameValues` swaps its `Array<DrawConfig?>` (PropKey-ordinal indexed) for three typed slots; `TimelineEvaluator` assigns by `track.prop`; the renderer's 15 read sites become typed. The codec keeps the EXISTING wire format byte-for-byte (VERSION stays 1): it dispatches by prop when writing and maps bytes to the typed variant when reading — full old-byte compatibility, no version bump.

**Tech Stack:** Kotlin (Android library modules), JUnit4 + Robolectric pixel probes, existing `RenderProbeHarness`.

**Spec:** `docs/superpowers/plans/2026-10-20-start-direction-coordination.md`, Conflict Inventory row C6 + the follow-up accepted in the final whole-branch review ("C6 structural cleanup deferred", renderer committed `6635f65` baseline). Baseline suite: 338 tests / 0 failures (then 16 commits ahead of origin).

## Current-shape evidence (pin exact consumers)

- `VectorAnimation.kt:138–145` — `DrawConfig(mode, start, direction, fillMode, fillDirection)`; `:187` — `PathFrameValues.drawConfigs: Array<DrawConfig?>(PropKey.COUNT)`; `:221` — array `copyInto` in PathFrameValues copy.
- `TimelineEvaluator.kt:248` — `values.drawConfigs[track.prop.ordinal] = draw`; group reset `:194/:199`.
- `MorphRenderer.kt` consumers (all reads for the typed swap): `689` group merge (`into.drawConfigs[key.ordinal] == null → copy`), `786` customDraw (DRAW), `792` customFill (FILL — fillMode/fillDirection only), `917/918` pair pass, `959/1825/1958` clip `.direction`, `1100/1104/1109` radial/clip block, `1419` strokeDraw (PATH_TRAVERSAL — mode/start/direction of the STROKE config), `1931/1934` static side + `VectorAnimation.drawConfigFor` fallback.
- `VectorAnimationCodec.kt:24` — `VERSION = 1`, `:39` header, `:51–56` per-draw 5-field write, `:107–112` 5-field read (prop-agnostic).
- `VectorAnimationBuilder.kt:445` — constructor in build() (inherited-fill copy); `:550` — `cfg(mode, start, direction, fillMode, fillDirection)`; only the FILL track ever sets fillMode/fillDirection (fill()); clipReveal passes `cfg(FORWARD, START, direction)`.

## Target shape (contract)

1. `sealed interface VectorAnimation.Reveal` — `DrawConfig` (mode/start/direction; fillMode/fillDirection REMOVED), `FillRevealConfig(fillMode: FillMode?, fillDirection: DrawDirection?)`, `ClipRevealConfig(direction: DrawDirection)`; all three `class`es implement `Reveal`.
2. `Segment.reveal: Reveal?` replaces `Segment.draw` (per-prop kind enforced at build time: DRAW→DrawConfig, FILL→FillRevealConfig, CLIP→ClipRevealConfig).
3. `PathFrameValues`: `strokeReveal: DrawConfig?`, `fillReveal: FillRevealConfig?`, `clipReveal: ClipRevealConfig?` — copy semantics and group-merge "only when slot null" preserved.
4. `VectorAnimationBuilder`: per-prop constructors; fill-inheritance (shipped Task 6) reads stroke `DrawConfig.direction` via `as? DrawConfig` and produces `FillRevealConfig(fillDirection = inherited)`.
5. Codec: byte-identical format; encode dispatches by prop (DRAW: mode/start/direction + two false/null nullable-enum writes; FILL: `FORWARD`/`START`/`AUTO` placeholder strings that no consumer reads, then fillMode/fillDirection; CLIP: `FORWARD`/`START` placeholders + direction); decode maps by prop to the typed variant. Old files decode identically to today.
6. `VectorAnimation.drawConfigFor(...)` — **@Deprecated** shim (property=DRAW_PROGRESS → `as? DrawConfig` from matching Reveal; else null), replaced by new typed pair `strokeRevealFor(...): DrawConfig?` and `fillRevealFor(...): FillRevealConfig?` used by the renderer's static-side fallback.
7. Zero behavior change: every existing pixel probe (338-test baseline) passes unchanged; codec round-trips old golden bytes and new typed records; app untouched except nothing.

## Global Constraints

- No wire-format change: `VectorAnimationCodec.VERSION` stays `1`; old encoded bytes must decode with identical results (golden-byte test pins this).
- No visual change: all pre-existing pixel probes pass untouched; the review bar is "suite totals ≥ baseline with 0 failures".
- Public API additions: `FillRevealConfig`, `ClipRevealConfig`, `Reveal`, `Segment.reveal`, `strokeRevealFor/fillRevealFor`; removals: `DrawConfig.fillMode/fillDirection`, `PathFrameValues.drawConfigs`, `Segment.draw`; deprecation: `drawConfigFor`. Breaking internal-field rename acceptable (pre-1.0).
- Test commands as established: `.\gradlew :vector-morph-core:test`, `.\gradlew :vector-morph-compose:testDebugUnitTest --rerun`, `.\gradlew :app:testDebugUnitTest` (900000 ms; `;` not `&&`). Commit ONLY touched files; no push; no docs beyond the files each task names.

## Review Focus

1. **Fill inheritance regression** — the shipped Task 6 gate reads DRAW directions; with `as? DrawConfig` the `AUTO` exclusion and per-target map must behave identically. Pin: existing `FillDirectionInheritanceTest` (updated to typed reads) all green. Owning task: Task 1.
2. **Codec byte-stability** — FILL/CLIP records keep the 5-field layout with placeholders; a golden-byte test decodes pre-cleanup bytes into typed reveals. Owning task: Task 1.
3. **Evaluator/group-merge parity** — per-frame slot fills and the renderer's `689` merge must preserve "first-writer-wins" per prop exactly. Pin: `TimelineEvaluatorParamsTest` + a new slot-merge unit test. Owning task: Task 1.
4. **PATH_TRAVERSAL read path** — it reads the STROKE config (`strokeDraw`) even when rendering a fill; the typed split must keep this read from the stroke slot, not the fill slot. Pin: existing probes in `MorphParameterApplicationTest`/`StrokeResolutionApplicationTest` green unchanged. Owning task: Task 1.
5. **Deprecated-shim determinism** — `drawConfigFor` deprecation shim must return identical values to today's for its single consumer pattern. Pin: a small unit test on the shim. Owning task: Task 2.

---

### Task 1: Typed reveal configs end-to-end

**Files:**
- Modify: `vector-morph-core/.../animation/VectorAnimation.kt` (Reveal types, Segment field, PathFrameValues slots, drawConfigFor deprecation + typed new methods)
- Modify: `vector-morph-core/.../animation/VectorAnimationBuilder.kt` (per-prop constructors, inheritance scan via `as? DrawConfig`)
- Modify: `vector-morph-core/.../animation/TimelineEvaluator.kt:248` (+ `:194/:199` resets)
- Modify: `vector-morph-core/.../animation/VectorAnimationCodec.kt` (typed encode/decode — same bytes)
- Modify: `vector-morph-compose/.../renderer/MorphRenderer.kt` (all 15 consumer sites listed above)
- Modify: core tests that touch the field (`PathStartAutoTest`, `FillDirectionInheritanceTest`, `VectorAnimationCodecTest`, plus whatever `TimelineEvaluatorParamsTest`/others reference)
- Create: `vector-morph-core/.../animation/RevealTypeShapeTest.kt`, `vector-morph-core/.../animation/FrameSlotMergeTest.kt`, golden-bytes test in `VectorAnimationCodecTest.kt`

**Interfaces:**
- Produces (for Task 2): the typed API surface `Reveal`/`DrawConfig`/`FillRevealConfig`/`ClipRevealConfig`, `strokeRevealFor`/`fillRevealFor`, deprecated `drawConfigFor` shim.

- [ ] **Step 1: Write the failing shape tests** (core)

```kotlin
// RevealTypeShapeTest.kt
@Test fun fillCompilesAFillReveal() {
    val anim = VectorAnimation { allPaths { fill(mode = FillMode.DIRECTIONAL, direction = DrawDirection.BOTTOM_TO_TOP) } }
    val reveal = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal
    assertTrue(reveal is FillRevealConfig)
    assertEquals(FillMode.DIRECTIONAL, (reveal as FillRevealConfig).fillMode)
}
@Test fun strokeRevealCompilesAStrokeConfig() {
    val anim = VectorAnimation { path("p") { strokeReveal(start = PathStart.TOP, direction = DrawDirection.BOTTOM_TO_TOP) } }
    val reveal = anim.tracks.first { it.prop == PropKey.DRAW_PROGRESS }.segments.single().reveal
    assertTrue(reveal is DrawConfig && (reveal as DrawConfig).start == PathStart.TOP)
}
@Test fun clipRevealCompilesAClipConfig() {
    val anim = VectorAnimation { allPaths { clipReveal(direction = DrawDirection.LEFT_TO_RIGHT) } }
    val reveal = anim.tracks.first { it.prop == PropKey.CLIP_PROGRESS }.segments.single().reveal
    assertTrue(reveal is ClipRevealConfig && (reveal as ClipRevealConfig).direction == DrawDirection.LEFT_TO_RIGHT)
}
@Test fun fillTrackCarriesNoStrokeFields() {
    // structural assertion: FillRevealConfig has no mode/start/direction properties accessible — encoded as absence of DrawConfig on FILL
    val anim = VectorAnimation { allPaths { fill(mode = FillMode.DIRECTIONAL) } }
    val reveal = anim.tracks.first { it.prop == PropKey.FILL_PROGRESS }.segments.single().reveal
    assertFalse(reveal is DrawConfig)
}
```

- [ ] **Step 2: Run — expect compile FAIL** — `.\gradlew :vector-morph-core:testDebugUnitTest --tests "...RevealTypeShapeTest"`

- [ ] **Step 3: Implement the core split** — Reveal types; `Segment.reveal`; builder per-prop constructors (`cfg` split: stroke sites → `DrawConfig(mode, start, direction)`, `fill()` → `FillRevealConfig(fillMode = mode, fillDirection = direction)`, `clipReveal` → `ClipRevealConfig(direction)`; keep the CUSTOM-direction `require`); fill-inheritance in build() via `request.reveal as? DrawConfig` (gate unchanged: `prop == DRAW_PROGRESS && reveal != null && direction != AUTO` → first per target) and produce `FillRevealConfig(fillMode = (existing as? FillRevealConfig)?.fillMode, fillDirection = inherited)`; `PathFrameValues` three slots + copy; evaluator assignment by prop (exhaustive when).

- [ ] **Step 4: Update the codec to typed encode/decode with IDENTICAL bytes** — encode: per-prop dispatch as in Target shape 5; decode: read the 5-field record then map by prop (`DRAW_PROGRESS → as? payload → DrawConfig(mode, start, direction)` ignoring fill pair; `FILL → FillRevealConfig(fillMode, fillDirection)` ignoring the three placeholder strings; `CLIP → ClipRevealConfig(direction)`). No VERSION change. Also write the golden-bytes test: hand-build a V1 byte blob for one DRAW + one FILL + one CLIP segment (literal bytes, comments mapping fields), decode → typed assertions.

- [ ] **Step 5: Write the failing slot-merge test + update existing core tests** — `FrameSlotMergeTest`: evaluate a frame where an `allPaths` group and a path track both set strokes → per-frame slots resolve first-writer-wins exactly as the old array did (mirror the group-merge rule from renderer:689 semantics — read renderer lines and mirror in evaluator-level test); update `FillDirectionInheritanceTest` reads (`segments.single().reveal as? FillRevealConfig !!`-style), `PathStartAutoTest` (unchanged semantics — compiles), any `TimelineEvaluator*`/`VectorAnimation*` test touching `.draw`.

- [ ] **Step 6: Migrate the renderer's 15 consumer sites** — mechanical: DRAW reads take the stroke slot (same type), FILL reads take `fillReveal` (fillMode/fillDirection), CLIP reads take `clipReveal.direction`; static-side fallback uses the new `strokeRevealFor/fillRevealFor` (deprecated `drawConfigFor` shim still compiles); group merge at `689` becomes three slot merges.

- [ ] **Step 7: Run all suites + probes** — `.\gradlew :vector-morph-core:test`, `.\gradlew :vector-morph-compose:testDebugUnitTest --rerun`, `.\gradlew :app:testDebugUnitTest` — expectation: identical totals to baseline (338) OR ≥ with new tests added, 0 failures; pixel probes pin parity.

- [ ] **Step 8: Commit** — `feat: typed reveal configs per progress property (DrawConfig/FillRevealConfig/ClipRevealConfig)`

### Task 2: API-surface sweep + docs + deprecation pin

**Files:**
- Modify: `vector-morph-core/.../animation/VectorAnimation.kt` (finalize @Deprecated KDoc on `drawConfigFor`; doc `Segment.reveal` + Reveal types)
- Modify: `vector-morph-core/.../animation/VectorAnimationCodec.kt` (KDoc: byte-stability note re placeholders)
- Create: `vector-morph-core/.../animation/DeprecatedDrawConfigForTest.kt` (shim returns the stroke segment for DRAW_PROGRESS and null otherwise, matching today's behavior on the shipped consumer pattern)
- Modify: `README.md` (one sentence in the Drawing/fill sections: fill tracks carry fill-mode/direction only; stroke draws carry mode/start/direction; codec format unchanged)
- Modify: `docs/superpowers/plans/2026-10-20-start-direction-coordination.md` — NO; instead append a resolution note to THIS plan's file? No — keep plan docs immutable; the README + KDoc note suffices.

- [ ] **Step 1: Write the failing shim test** — construct animation with a DRAW stroke on path; assert `drawConfigFor(name, occurrence...)` returns the same DrawConfig and `strokeRevealFor` equals it; FILL property → null from shim but a FillRevealConfig from `fillRevealFor`.
- [ ] **Step 2: README + KDoc sentences** — per file list.
- [ ] **Step 3: Run all suites** — green; **Step 4: Commit** — `docs: typed reveal API surface notes and deprecated drawConfigFor pin`

## Rollback & verification

- Task 1 is one migration commit (compile-coupled by design); Task 2 is additive docs/tests.
- Full-suite bar: all modules green at ≥ baseline; pixel probes unchanged = parity proof.
