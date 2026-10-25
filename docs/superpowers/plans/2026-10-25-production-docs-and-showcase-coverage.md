# Production Docs + Full-Coverage Showcase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship-ready 0.2.0: consumer docs (`README.md` + `docs/` guides, no maintainer
setup guides), a showcase app that exposes every library parameter, and a tagged
release (`v0.2.0`) that publishes via the existing GitHub workflow.

**Architecture:** Docs-first (no code coupling), then the app: one shared
`ConfigurationPanel` renders the full 24-param surface in all four playgrounds off an
extended `ShowcaseState`; new curated showcases cover the APIs no playground pixels
today (`MorphingIcon`, fill reveals, gradient tracks, diagnostics, precompile/cache,
codec, custom geometry); release chores land last so CI publishes the final tree.

**Tech Stack:** Kotlin 2.2.10, AGP 9.4.1, Compose BOM 2026.02.01, Material3, Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-25-showcase-coverage-and-docs-design.md`
(executors read both; this plan inherits its decisions verbatim).

## Global Constraints

- `VERSION_NAME=0.2.0` stays; release tag is `v0.2.0` (v0.1.0 exists; 0.2.0 unpublished).
- No behavior/API changes in `:vector-morph-core` or `:vector-morph-compose`.
- No personal/one-time setup guides anywhere in docs (README publishing section is
  deleted; no `docs/PUBLISHING.md` is created; a 3-line Releases note is enough).
- All docs claims must match the real 0.2.0 API from the spec's exploration
  (no lighting/stickers/fonts language; TintPass adds one layer per frame, etc.).
- App module stays `com.mohamed.dev.ui.imorpher`; Material3; current backstack
  navigation is kept (no Navigation Compose rewrite).
- Every task ends with the module compiling; final task runs all unit suites.
- Manual verification note: README/CHANGELOG contain no placeholder text.

## Review Focus

1. **Malformed/partial persisted state** — a hand-edited or old SharedPreferences
   blob must not crash on load or produce out-of-range values; loaders fall back to
   validated defaults (current convention). Test: `ShowcaseStateTest` load/validate
   cases for every new field (Task A1).
2. **`toConfiguration()` coverage drift** — a new state field forgotten in the map
   silently becomes invisible configuration. Test: full-mapping assertions over all
   24 params + debug block + exact enum round-trip (Task A1).
3. **Cut-off geometry budget crash** — small `segmentBudget` (min offered = 4) on the
   most complex catalog pair must plan and render without throwing. Test:
   `AllCombinationsScanTest`-style Robolectric scan at budget 4 (Task A4).
4. **R8 missing keep rules for new icons** — hand-authored `ImageVector` fields of the
   new demo icons must survive release minification per the existing
   `keepRules/rules.keep` pattern; verified by file check + naming it in the same
   `IMorpherIcons` object pattern (Task A3).
5. **Codec round-trip determinism** — encode→decode of the app-built timeline must
   replay identically without throwing (customizer/unknown-brush rejection aside).
   Test: diagnostics codec showcase Robolectric test (Task A4).

---

### Task D1: README rewrite + CHANGELOG

**Files:**
- Modify: `README.md` (full rewrite, ~250–300 lines)
- Create: `CHANGELOG.md`
- Reference: LICENSE exists at root; keep link.

**Interfaces:** Produces the section anchors `#configuration-overview`,
`#performance`, `#tickets` not needed; docs links: `docs/README.md`,
`docs/CONFIGURATION.md`, `docs/TIMELINE-DSL.md`, `docs/DIAGNOSTICS.md`,
`docs/CUSTOM-GEOMETRY.md`, `docs/MIGRATION.md`, `docs/SHOWCASE-APP.md` (created in
D2–D5; links present from day one).

- [ ] **Step 1:** Write the README per spec §Part 1 (badges: Maven Central, CI
      `actions/workflows/ci.yml/badge.svg`, License Apache-2.0; quickstart with
      `MorphIcon`/`MorphVector`/`MorphingIcon` code; feature matrix; 0.2.0 highlights
      list; 24-param overview table; debugging/performance snapshots; troubleshooting
      FAQ with the R8 note; showcase paragraph; Contributing/Versioning/License;
      delete the entire "Publishing to Maven Central" section and any setup steps)
      and `CHANGELOG.md` (Keep-a-Changelog; entries for `0.2.0` and `0.1.0`
      reconstructed from the two plan docs + README history).
- [ ] **Step 2:** Verify: `grep -c "Publishing to Maven Central\|generate a user token\|GPG\|signing"` in README/CHANGELOG = 0; all `docs/...md` hrefs exist on disk.
- [ ] **Step 3:** Commit `docs: consumer-facing README, changelog for 0.2.0`.

### Task D2: `docs/CONFIGURATION.md` + `docs/MIGRATION.md`

**Files:** Create `docs/CONFIGURATION.md`, `docs/MIGRATION.md`.

- [ ] **Step 1:** CONFIGURATION.md: param-by-param reference of all 24 fields +
      `MorphDebugOptions` (type/default/semantics from the exploration), every enum
      table, precedence contract ported from current README "start and direction"
      bullets, fill-direction inheritance, viewport rules.
- [ ] **Step 2:** MIGRATION.md: `HONOR_DIRECTION` default flip + `LEGACY` recipe;
      `customStartOffsets` → `customDrawAnchors`/CUSTOM correspondences; `START` vs
      `AUTO` anchor change; `AUTO`+`CUSTOM` `MISSING_CONCRETE_ANCHOR` note; codec v1
      read / v2 write.
- [ ] **Step 3:** Verify links from README resolve; commit `docs: configuration and migration guides`.

### Task D3: `docs/TIMELINE-DSL.md` + `docs/DIAGNOSTICS.md` + `docs/CUSTOM-GEOMETRY.md` + `docs/README.md` index

**Files:** Create the four files (index includes the disclaimer that
`docs/superpowers/` holds internal working docs).

- [ ] **Step 1:** Write all four; every documented symbol must exist in source
      (verify by grep before commit).
- [ ] **Step 2:** Commit `docs: DSL cookbook, diagnostics, custom geometry, docs index`.

### Task D4: `docs/SHOWCASE-APP.md`

**Files:** Create `docs/SHOWCASE-APP.md`.

- [ ] **Step 1:** Write the app tour AFTER Task A4 lands (section: what each topic /
      panel section demonstrates + full param table → control map). Draft only the
      final file content in this task, but execute after A4; keep task order D-first
      only for README-adjacent files.
- [ ] **Step 2:** Commit `docs: showcase app tour`.

### Task A1: `ShowcaseState` full-coverage expansion

**Files:**
- Modify: `app/src/main/java/com/mohamed/dev/ui/imorpher/showcase/ShowcaseState.kt`
- Modify: `app/src/main/java/com/mohamed/dev/ui/imorpher/showcase/ShowcaseStatePersistence.kt`
- Test: `app/src/test/java/com/mohamed/dev/ui/imorpher/ShowcaseStateTest.kt`

**Interfaces (exact, all later tasks consume):**

New state fields (types/defaults):
```kotlin
startPoint: StartPointStrategy = StartPointStrategy.AUTO
pathDirection: PathDirectionStrategy = PathDirectionStrategy.AUTO
directionTraversal: DirectionTraversal = DirectionTraversal.HONOR_DIRECTION
drawOrder: DrawOrderStrategy = DrawOrderStrategy.AUTO
strokeFill: StrokeFillStrategy = StrokeFillStrategy.AUTO
defaultFillDirection: DrawDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT
viewportNormalization: ViewportNormalization = ViewportNormalization.STRETCH
debugEnabled: Boolean = false
showPathPoints/showPathNumbers/showBoundingBoxes/showCentroids/showDirection/showStartPoints/showPathMatching: Boolean = false
markerArcFraction: Float = 0f
segmentBudget: Int? = null                                   // null = off; UI range 4..48
simplificationTolerance: Float = 0.25f   // range 0.1..1.0
iconWidthDp: Float? = null       // null = intrinsic
iconHeightDp: Float? = null
specKind: AnimationSpecKind = AnimationSpecKind.TWEEN            // enum TWEEN, SPRING_MEDIUM_BOUNCY, SPRING_NO_BOUNCY, LINEAR
alignment: AlignmentKind = AlignmentKind.CENTER                  // app enum → Compose Alignment map
customAnchors: List<AnchorEntry> = emptyList()   // AnchorEntry(pathName: String, fraction: Float)
customMappings: List<PathMappingEdit> = emptyList() // PathMappingEdit(source: String, target: String)
customDrawOrderNames: List<String> = emptyList()
```
Replaced: state's dead `fillMode` field becomes the wired `defaultFillMode:
FillMode = FillMode.DIRECTIONAL`. `keepRenderOrder` stays (now with a control).
Removed-but-compatible: `motionHumanPreference` naming unchanged.

New helpers (public, for tests/panel):
```kotlin
fun ShowcaseState.toDebugOptions(): MorphDebugOptions?   // null when !debugEnabled
fun ShowcaseState.toAnimationSpec(): AnimationSpec<Float> // per AnimationSpecKind + durationMs
fun ShowcaseState.toAlignment(): Alignment                // per TextAlignmentKind
```
`toConfiguration()` extended to map all of the above verbatim (full mapping; the
deprecated `customStartOffsets` stays unmapped).

- [ ] **Step 1 (failing tests):** extend `ShowcaseStateTest`: (a) every new field
      round-trips through save/load JSON; (b) malformed blob per new field falls back
      to the validated default without throwing; (c) `toConfiguration()` full-mapping
      assertion — set every field to a non-default sentinel, assert the produced
      `MorphConfiguration` equals an expected literal; (d) `toDebugOptions()` null vs
      populated mapping; (e) `toAnimationSpec()`/`toAlignment()` kind mapping.
- [ ] **Step 2:** run `./gradlew :app:testDebugUnitTest --tests "*ShowcaseState*"`
      → FAIL (fields missing).
- [ ] **Step 3:** implement fields, persistence keys + validation fallbacks, helpers,
      `toConfiguration()` mapping.
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5:** commit `feat(app): full-coverage showcase state with persistence`.

### Task A2: Shared `ConfigurationPanel` + playground rewiring

**Files:**
- Create: `app/src/main/java/com/mohamed/dev/ui/imorpher/showcase/ConfigurationPanel.kt`
- Modify: `ShowcaseScaffold.kt` (only what sections reuse), `ShowcaseControls.kt`
  (add `ShowcaseSwitchRow`, `NullableIntSlider`, `NullableDpSlider` helpers),
  `topics/MorphIconTopic.kt`, `topics/MorphVectorTopic.kt`,
  `topics/VectorAnimatorTopic.kt`, `topics/DrawIconTopic.kt`
- Test: existing UI/state tests; new `ConfigurationPanelCoverageTest.kt`

**Interfaces:**
- Consumes: Task A1 fields/helpers.
- Produces:
```kotlin
@Composable
fun ConfigurationPanel(
    state: ShowcaseState,
    onState: (ShowcaseState) -> Unit,   // single memoized updater (copy semantics), debounced persistence stays in ShowcaseScreen
    pathNames: List<String>,            // compiled-pair names via planMorph on Dispatchers.Default (cached per pair), fallback ["path[1]", "path[2]", ...]
)
```
Sections in spec order (12 collapsible groups; Matching + Start&Direction expanded
default; each collapses to a `TextButton` header with `AnimatedVisibility`). Where a
dropdown previously existed twice (Missing/Fallback/Timing/Colors), panel wins and
the duplication is deleted. New control builders: `StrategyDropdown(label, values)`,
`SwitchRow`, `AnchorEditor(pathNames, entries)`, `MappingEditor(pathNames, entries)`,
`OrderEditor(pathNames, names)`. `CopySnippetButton` output extended by Task A5 —
keep it compiling here.

`pathNames` resolution lives in `PairSuggestions.kt` as
`suspend fun pathNamesFor(from: ImageVector, to: ImageVector): List<String>` (cache,
fallback names when planning fails).

- [ ] **Step 1 (failing test):** `ConfigurationPanelCoverageTest` (Robolectric):
      build the state with sentinels (A1 style), assert `toConfiguration()` inherits
      everything and list contents of every enum const shuffled vs the dropdown list
      constants — pin each of the control lists to `EnumClass.entries`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement panel + rewiring + helper widgets; delete duplicated
      playground dropdowns; wire `onSettled` event log into the MorphIcon playground
      (`lastSettleEvents: List<String>` local, not persisted).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS (existing nav/render tests too).
- [ ] **Step 5:** commit `feat(app): shared full-coverage ConfigurationPanel`.

### Task A3: Demo vectors + catalog

**Files:**
- Create: `app/src/main/java/com/mohamed/dev/ui/imorpher/icons/BannerWide.kt`,
  `SealOutline.kt`, `SealSolid.kt` (each built inside `IMorpherIcons` object
  pattern; Seal* paths named `ring`, `cross`, `dot` via `addPath(..., name = ...)` /
  `pathData` with explicit names; SealOutline carries `clipPathData`)
- Modify: `IconCatalog.kt` (3 entries + kinds: BannerWide=PLAIN, Seal pair
  OUTLINE/SOLID; + 3 curated pairs incl. `SealOutline→SealSolid`).
- Modify: `app/keepRules/rules.keep` per existing pattern (extends `IMorpherIcons**`).
- Test: nothing dedicated (catalog matrix + endpoint scans pick these up
  automatically); add degradation assertion `BannerWidePaintsTest.kt`: FIT letterboxes
  (paint bounds narrower than layout) while STRETCH fills the layout width.

- [ ] **Step 1:** implement icons + catalog + keep rules.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` (adds new icons to all scans)
      → PASS.
- [ ] **Step 3:** commit `feat(app): named/clip/wide demo vectors for new showcases`.

### Task A4: New curated showcases + Diagnostics topic

**Files:**
- Modify: `TopicRegistry.kt` (5 topics; ids/titles/api strings per spec table)
- Modify: `HomeScreen.kt` (five home cards + Diagnostics shows first)
- Create: `showcase/topics/DiagnosticsTopic.kt`
- Modify: `showcase/topics/*.kt` (new showcase screens per spec table; playgrounds
  untouched except panel from A2)
- Modify: `ShowcaseState.kt` (only ids-driven rendering hooks if needed)

**Interfaces (showcase screens visible in A4's own task only):** each screen is
`@Composable fun <Name>Showcase(onBack: () -> Unit)` following existing per-topic
patterns; Diagnostics screens:
```kotlin
suspend fun planReportFor(from, to, config): PlanReportUi  // compat score, issue list, warnings, fallbackUsed (off-thread)
suspend fun warmAndMeasure(from, to, config): WarmResultUi // planning ms + cache size/bytes via MorphCaches
fun codecRoundTrip(animation: VectorAnimation): DecodeStatus // encode+bytes→decode→size
```
in `PairSuggestions.kt`-style engine file `DiagnosticsEngine.kt`.

- [ ] **Step 1 (failing tests):** `AllCombinationsScanTest` extension: every catalog
      pair plans+renders with `segmentBudget = 4` config and the debug overlay
      enabled (all flags on) without throwing; `ShowcaseNavigationTest`: new topic +
      11 item ids navigate; `DiagnosticsScreenTest` (Robolectric): report card text
      contains counts for a known pair and codec round-trip plays a restored
      animation.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement registry entries, screens (vote for named paths on
      Seal icons: anchors/order/mappings editors show real names), viewport/fill/
      gradient/clip/follow-fill/state-fill/chain showcases, Diagnostics engine +
      screens.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5:** commit `feat(app): new showcases and Diagnostics topic`.

### Task A5: Snippet + suggestions parity

**Files:**
- Modify: `SnippetGenerator.kt` (emit every non-default config param + debug +
      size/alignment + spec; keep target-specific sections per playground kind)
- Modify: `PairSuggestions.kt` (surface fallbackUsed + warning count in UI)
- Test: extend existing state tests with snippet assertions (a sentinel state
      produces text containing each non-default param).

- [ ] **Step 1 (failing test):** sentinel-state snippet text contains
      `pathMatching = PathMatchingStrategy.BY_NAME`, `debug = MorphDebugOptions(...)`,
      `segmentBudget = 12`, `viewportNormalization = ...`, etc., and omits defaults.
- [ ] **Step 2:** run → FAIL; **Step 3:** implement; **Step 4:** run → PASS;
- [ ] **Step 5:** commit `feat(app): snippets and pair cards reflect full configuration`.

### Task R1: Release

**Files:** none new; uses accumulated tree.

- [ ] **Step 1:** `./gradlew :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest :app:testDebugUnitTest` → PASS.
- [ ] **Step 2:** `git add -A && git commit` (`release: 0.2.0 production docs + full-coverage showcase`), `git push origin master`.
- [ ] **Step 3:** `git tag v0.2.0 && git push origin v0.2.0` (publish.yml trigger).
- [ ] **Step 4:** no cleanup guides to delete (done at D1) — final grep check passes.
