# Design: Production-Ready Docs + Full-Coverage Showcase

- Date: 2026-10-25
- Status: Approved design (pre-plan)
- Scope: `README.md`, `docs/`, `CHANGELOG.md` (docs half); `app` module (showcase half).
- Related plans: 2026-10-20-start-direction-coordination.md, 2026-10-24-limitations-remediation.md (implementation context for 0.2.0 API state this work documents/demonstrates).

## Goal

Ship-ready presentation for IMorpher 0.2.0:

1. **Docs:** README becomes a concise, consumer-facing centerpiece; deep material moves
   into `docs/` guides. Every public API parameter and enum is documented somewhere
   authoritative, with a migration guide for 0.1→0.2.
2. **Showcase:** the demo app exposes **every** library customization — all 24
   `MorphConfiguration` parameters (including the deprecated `customStartOffsets`
   surface only where still honored — excluded by design, see Non-goals), the 8
   `MorphDebugOptions` toggles, composable-level knobs (tint, size, alignment,
   animationSpec, motion preference, `onSettled`), and curated showcases for the
   `MorphingIcon` composable, fill reveals, gradient tracks, diagnostics, precompile/
   cache, the animation codec, and custom geometry.

## User decisions (recorded)

- Showcase layout: **one shared, full-coverage Configuration panel rendered by all
  four playgrounds** (MorphIcon, MorphVector, VectorAnimator, DrawIcon) — coverage can
  never drift between playgrounds.
- Docs layout: **trim README to a consumer centerpiece and move deep material into
  `docs/` guides.**
- Internal `docs/superpowers/plans` notes stay as-is (they are process history, not
  consumer docs).

## Part 1 — Docs

### README.md (target ~250–300 lines)

Consumer-first structure:

1. Badges: Maven Central (exists), CI (`.github/workflows/ci.yml` badge), Apache-2.0
   license, Kotlin 2.2 / Compose (no version-specific badge URLs invented where
   unnecessary; API/min SDK stated as text).
2. One-paragraph pitch (largely kept from current intro).
3. Installation: keep exactly the current catalog/coordinates guidance (it is correct),
   trimmed slightly.
4. Quickstart: `MorphIcon` (state morph with tint), `MorphVector` (manual progress),
   `MorphingIcon` (chain to changing target) — the three composable families — circa
   60 lines total of code.
5. Feature matrix: compact grouped list linking deeper sections/guides.
6. What's new in 0.2.0 (5–8 bullets: `directionTraversal` default `HONOR_DIRECTION`,
   `customDrawAnchors` split, typed reveal records + codec v2, `ViewportNormalization`
   setting, segment budget/simplification tolerance, `MorphingIcon`, DST-free pivot
   semantics) linking `docs/MIGRATION.md`.
7. Configuration overview: table of the 24 parameters with one-line meaning, linking
   `docs/CONFIGURATION.md` for full enum tables and the start/direction precedence
   contract.
8. Debugging & diagnostics, Performance & memory: two-to-five paragraph snapshots
   linking `docs/DIAGNOSTICS.md` and keeping the R8 note visible in README itself
   (consumer-rules.pro ships with the artifact; gradients degrade predictably without).
9. Troubleshooting / FAQ (crossfaded gradients with custom Brush; click handling &
   touch targets; reduced motion semantics; plan cache memory on low-end devices).
10. Showcase app paragraph linking `docs/SHOWCASE-APP.md`.
11. Contributing, testing commands (current correctness kept), versioning policy,
    license. Publishing moved to `docs/PUBLISHING.md` with a one-line pointer.

### docs/ set

| File | Content |
| --- | --- |
| `docs/README.md` | Index linking all guides + internal-plans disclaimer. |
| `docs/CONFIGURATION.md` | Full reference: all 24 `MorphConfiguration` params with types/defaults/semantics; every enum table; `MorphDebugOptions`; the start/direction precedence contract (ported nearly verbatim from current README §"Drawing and stroke reveal"); fill-direction inheritance rules. |
| `docs/TIMELINE-DSL.md` | Cookbook: `VectorAnimation` factory, every vector-level method and `PathAnimationScope` method, `AnimationTarget` types, keyframes, spring easing, stagger, fill/stroke/gradient tracks, fill modes, `VectorAnimationCodec`, `linearGradientSpecAtAngle`. |
| `docs/DIAGNOSTICS.md` | `MorphValidator`, `MorphPlan.report`, debug overlays, `MorphPrecompiler`, `MorphCaches`, `CustomizerFailures`, plan cache trimming. |
| `docs/CUSTOM-GEOMETRY.md` | `CustomVectorAnimation`, `CustomVectorPathOverrides`, draw/fill custom contours, clip reveal & morphing masks, boolean geometry. |
| `docs/MIGRATION.md` | 0.1→0.2: `directionTraversal` default flip + how to restore `LEGACY`; `customStartOffsets` → `customDrawAnchors` (pen anchors) and CUSTOM correspondences (alignment); `PathStart.START` vs `AUTO` semantics change; `start = AUTO + direction = CUSTOM` anchor change (`MISSING_CONCRETE_ANCHOR`); codec reads v1, writes v2. |
| `docs/PUBLISHING.md` | Current "Publishing to Maven Central" section relocated with light edits. |
| `docs/SHOWCASE-APP.md` | Tour of the upgraded app: topics, shared Configuration panel (what each section maps to), diagnostics screens, and "every parameter has a control here" table. |

### CHANGELOG.md

Root level, Keep-a-Changelog format: `0.2.0` (features/fixed entries reconstructed
from the two plan documents and current README) and `0.1.0` (initial public release).

## Part 2 — Showcase app (`app` module)

### Shared Configuration panel

New file `showcase/ConfigurationPanel.kt` consuming `ShowcaseState` + callbacks
(readystate mutators), rendered by all four playground screens. Collapsible sections
(default expanded = Matching + Start & direction; everything else collapsed to keep
first paint reasonable in a scrollable playground column):

1. **Matching** — `pathMatching` dropdown (all entries incl. CUSTOM); CUSTOM
   mappings editor: rows of `(source name, target name)` pickers enumerating names
   from the currently compiled pair (`path[1]`, `path[2]`… — compiler-disambiguated).
2. **Start & direction** — `startPoint` dropdown (all 5); `pathDirection` dropdown
   (all 4); `directionTraversal` dropdown (2); draw anchors editor: per-path rows with
   name + fraction slider (0–1) building `customDrawAnchors`.
3. **Draw order & stagger** — `drawOrder` dropdown (11 entries); `customDrawOrder`
   editor (ordered chips with remove/add from the pair's path names); `keepRenderOrder`
   switch; `staggerDelay` slider; `staggerOrder` dropdown (CUSTOM filtered, as today).
4. **Paint & color** — `colorSpace` dropdown (4); `strokeFill` dropdown (4).
5. **Fill reveal** — `defaultFillMode` dropdown (6); `defaultFillDirection` dropdown;
   (replaces the dead, persisted `fillMode` field).
6. **Missing & fallback** — existing `missingPath` + `fallback` dropdowns move here
   from per-playground duplication into the shared panel.
7. **Timing** — `timing` dropdown; duration slider; animationSpec picker
   (tween(duration) / spring(MediumBouncy) / spring(NoBouncy) / LinearEasing variants).
8. **Motion & size** — motion preference dropdown; width/height sliders (off = default
   intrinsic); `alignment` dropdown (subset: Center, CenterStart, CenterEnd, TopCenter,
   BottomCenter).
9. **Geometry budget** — `segmentBudget` (off + 4–48 slider) and
   `simplificationTolerance` slider (0.1–1.0).
10. **Viewport** — `viewportNormalization` (STRETCH/FIT).
11. **Debug overlays** — master switch; then 8 `MorphDebugOptions` toggles +
    `markerArcFraction` slider; maps to `configuration.debug = MorphDebugOptions(...)`
    (non-null only when master enabled).
12. Tint selector stays (existing), now living outside the panel as a sibling section
    (it is composable-level, not configuration-level), together with the icon pickers.

### ShowcaseState

- New fields: `startPoint`, `pathDirection`, `directionTraversal`, `drawOrder`,
  `strokeFill`, `defaultFillDirection`, `viewportNormalization`, `alignment`,
  `debugEnabled` + 8 debug toggles + `markerArcFraction`, `customAnchors`
  (Map<String,Float> edit list), `customMappings` (List<PathMapping> edit list),
  `customDrawOrder` (List<String> edit list), `segmentBudget: Int?`,
  `simplificationTolerance`, `iconWidthDp: Float?`, `iconHeightDp: Float?`,
  and an `AnimationSpecKind` enum for the spec picker
  (tween/Linear, tween/FastOutSlowIn, Spring–MediumBouncy, Spring–NoBouncy —
  tween variants carry the shared duration slider). Fields replaced/renamed:
  `fillMode` → wired as `defaultFillMode`; `keepRenderOrder` gets a control (was dead);
  `staggerOrder`/`staggerDelay`/`timing`/`colorSpace` unchanged semantically.
- `toConfiguration()` maps every field (full-coverage mapping, unit-tested).
- Persistence: extend the existing JSON blob + per-key validation fallbacks; malformed
  values fall back to defaults exactly as today.
- Per-playground deviations remain composable-level only: `DrawIcon` keeps its stroke
  width control and no duration slider (documented no-op), `VectorAnimator` keeps its
  DSL toggles; the Configuration panel itself is identical in all four.

### New curated showcases

Registry additions (id, title, description, api string):

| Topic | New showcase |
| --- | --- |
| MorphIcon | `morph-icon-fill` Outline↔Filled with live `strokeFill` dropdown (MORPH/CROSSFADE/DRAW/AUTO); `morph-icon-chain` **MorphingIcon** cycling icons ("Last settled" contract badge); `onSettled` event log appended to StateMorph showcase. |
| MorphVector | `morph-vector-viewport` STRETCH vs FIT using a new wide-viewport demo vector. |
| VectorAnimator | `vector-animator-fill` fill reveal (LINEAR/RADIAL/SWEEP/PATH_TRAVERSAL + direction); `vector-animator-gradient` `gradient()`/`strokeGradient()` endpoints. |
| DrawIcon | `draw-icon-follow-fill` stroke-reveal-then-fill (`followWithFill`); `draw-icon-clip` clipReveal over a new clip-carrying demo icon. |
| New topic **Diagnostics** | `diagnostics-plan-report` (compatibility score/issues/warnings/fallback via `planMorph` off-thread, reuse of PairSuggestions engine); `diagnostics-caches` (Warm button + measured planning ms + `MorphCaches.size/sizeBytes` + Trim Softly button); `diagnostics-codec` (encode→decode→play result round-trip); `diagnostics-custom` (curated shrinking-pen `CustomVectorAnimation` demo from README). |

PairSuggestions card additionally surfaces `plan.report.fallbackUsed` + warning count.

### New demo vectors (`app/src/main/java/…/icons/` into IconCatalog)

1. `BannerWide` (48×16 viewport) — target-viewport `ViewportNormalization` demo.
2. `SealOutline`/`SealSolid` — multi-path, **named paths** (`ring`, `cross`, `dot`);
   one side carries an inherited `clipPathData` — feeds clipReveal, customDrawOrder,
   per-path anchor/mapping editors; added to catalog + pair suggestions matrix so
   exhaustive catalog tests cover them.

### Supporting updates

- `SnippetGenerator`: emits every non-default `MorphConfiguration`/composable param
  (keeps snippets clean by omitting defaults).
- `TopicRegistry`: 4 → 5 topics; item counts and api strings updated.

## Testing

- `ShowcaseStateTest`: round-trip persistence for all new fields; validation fallback
  for malformed/partial blobs; `toConfiguration()` full-mapping assertions (every one
  of the 24 params + debug block mapped correctly).
- New `ConfigurationPanelCoverageTest`: for each section/param assertion that the
  mapped value reaches `MorphConfiguration` / `MorphDebugOptions` and that every enum
  value is offered by its control list.
- Diagnostics showcase tests: plan-report card renders counts; codec round-trip plays a
  restored animation without throwing (Robolectric).
- Catalog/matrix tests: new demo icons included automatically via catalog scan; assert
  `BannerWide` paints within its viewport under FIT vs STRETCH differently (letterbox
  vs stretch).
- Existing suites (`AllCombinationsScanTest`, `DrawParamCoherenceTest`,
  `StrokeRevealRegressionTest`, `ShowcaseNavigationTest`) extended for new ids and
  kept green.

## Non-goals

- No library behavior changes; no new library params; no `customStartOffsets` UI
  (deprecated, replaced by anchors editor; the legacy fallback stays library-internal).
- No theme/navigation rewrite; no benchmark UI; no screenshots/screenshots pipeline.
- Multiplatform, crop/fill ContentScale: unchanged library boundaries.

## Acceptance criteria

1. In-app: every parameter listed in the Design §Part 2 Configuration panel is
   reachable from each of the four playgrounds, and copying a snippet reproduces the
   visible configuration.
2. Reading `docs/CONFIGURATION.md` alone is sufficient to know every parameter,
   enum entry, and precedence rule of 0.2.0; README links resolve.
3. `MorphingIcon`, fill reveals, gradient tracks, diagnostics, precompile/cache, and
   codec each have an in-app demo.
4. `./gradlew :app:testDebugUnitTest :vector-morph-core:testDebugUnitTest
   :vector-morph-compose:testDebugUnitTest` passes.
