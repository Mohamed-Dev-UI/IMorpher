# Diagnostics, Debugging, and Cache Control

## Planning reports

Planning is deterministic and pure, so it is safe to probe from tests:

```kotlin
val plan = planMorph(fromVector, toVector, configuration)   // compiled + planned
val report = plan.report
```

`MorphReport` carries:

| Field | Meaning |
| --- | --- |
| `compatibility` | 0–1 geometric compatibility score (`MorphCompatibility.score`); below ~0.45 the morph may look elastic; `warnings` lists why. |
| `issues` | `MorphIssue` list with severity (`INFO`/`WARNING`/`ERROR`), category (e.g. "contour-count", "gradient", "missing"), affected path names, and a message. |
| `unmatchedSourcePaths` / `unmatchedTargetPaths` | Paths left without a partner. |
| `fallbackUsed`, `fallbackStrategy`, `fallbackReason` | Whether/why a fallback ran. |

Static pre-flight without planning: `MorphValidator.validate(from, to, config)` returns
the same issue list. `MorphValidator.validateConfiguration(config)` audits **parameter
conflicts** without any geometry: knobs that silently do nothing, contradict each other,
or carry narrow semantics — usable on any configuration at any time (pure, never
throws). Categories:

| Category | Severity | When |
| --- | --- | --- |
| `custom-anchors-unused` | WARNING | `customDrawAnchors` non-empty but no draw track requests `PathStart.CUSTOM`. |
| `custom-start-offsets-unused` | WARNING | Deprecated `customStartOffsets` has no consumer: neither `StartPointStrategy.CUSTOM` alignment nor a `PathStart.CUSTOM` draw reveal is active. |
| `custom-start-alignments-unused` | WARNING | `customStartAlignments` has no consumer: it only moves geometry under `StartPointStrategy.CUSTOM` (it is not a draw-anchor fallback). |
| `custom-start-alignment-invalid` | WARNING | An active `customStartAlignments` entry is not a finite value in 0..1; finite out-of-range values are clamped, NaN is not a valid offset. |
| `custom-start-alignment-conflict` | WARNING | `customStartAlignments` and the deprecated `customStartOffsets` carry different values for the same path; alignment reads `customStartAlignments` first and the deprecated map keeps its legacy fallback. |
| `custom-draw-anchor-invalid` | WARNING | An active custom draw anchor is non-finite or outside 0..1; the renderer clamps it. |
| `custom-start-offset-invalid` | WARNING | An active legacy custom start offset is non-finite or outside 0..1; alignment and draw anchoring clamp it. |
| `stagger-delay-invalid` | WARNING | Configuration or animation stagger delay is negative or non-finite and is ignored. |
| `custom-callback-missing` | WARNING | A custom stroke/fill reveal mode or `missingPath = CUSTOM` requires an animation callback; callback output remains runtime-dependent. |
| `custom-order-unused` | WARNING | `customDrawOrder` non-empty with no active consumer: render ordering requires `drawOrder = CUSTOM` and `keepRenderOrder = false`, or a path-scoped timeline whose resolved `staggerOrder = CUSTOM` is consumed by a positive delay or `timing = BY_PATH`. |
| `missing-path-under-crossfade` | WARNING | `missingPath != FADE` under `fallback = CROSSFADE`/`SCALE_CROSSFADE` — crossfades discard the behavior. |
| `fill-direction-ignored` | WARNING | A `FADE`/`RADIAL` fill reveal declares a travel direction its mode ignores. |
| `fallback-throw-scope` | INFO | `fallback = THROW` throws only when one side has no paths at all; other artifacts degrade silently — check the plan report. |
| `draw-order-not-rendered` | INFO | A non-default `drawOrder` does not change z-order while `keepRenderOrder = true`; it also does not control stagger timing (`staggerOrder` does). |
| `stagger-order-without-delay` | INFO | A non-default configured `staggerOrder` has no resolved path-scoped consumer: it needs a positive resolved stagger delay or `timing = BY_PATH`. A timeline delay can activate a configured order that overrides the timeline order. |
| `sweep-direction-on-stroke` | INFO | `CENTER_OUT`/`OUTSIDE_IN` on a stroke reveal (fill-sweep start-angle selectors only). |
| `custom-mappings-unused` | INFO | `customMappings` under a matching strategy other than `AUTO`/`CUSTOM`. |
| `custom-mapping-name-empty` | WARNING | An active `AUTO`/`CUSTOM` mapping has a blank source or target selector. |
| `custom-mapping-source-duplicate` | WARNING | An active mapping source occurs more than once; the first mapping wins and shadows later entries. |
| `custom-mapping-target-duplicate` | WARNING | Multiple active mappings claim one target; the first source to claim it wins. |
| `custom-order-name-empty` | WARNING | An active custom draw-order entry is blank. |
| `custom-order-name-duplicate` | WARNING | An active custom draw-order name repeats; the last occurrence supplies its rank. |
| `segment-budget-clamped` | INFO | `segmentBudget` is below the planner minimum of 2 and is raised to 2. |
| `simplification-tolerance-invalid` | WARNING | `simplificationTolerance` is negative or non-finite; planning treats it as zero to prevent an accidental unrestricted merge. |
| `simplification-tolerance-unused` | INFO | A non-default `simplificationTolerance` has no effect while `segmentBudget = null`. |

Active custom offset values are checked only when their consumer exists; inactive maps report the unused-map finding instead. Custom callback presence is checked statically, but callback results cannot be validated without rendering.

The five custom selector integrity categories above are configuration-only and are emitted
only when their custom mapping/order list has a consumer. Inactive lists retain their
existing `custom-mappings-unused` / `custom-order-unused` finding without additional content
warnings. Duplicate selectors are diagnosed but never rejected or rewritten.

Vector-aware custom selector references are checked during planning and appear as WARNING
issues in `MorphPlan.report.issues` (and therefore in `MorphValidator.validate(from, to,
config)`):

| Category | Severity | When |
| --- | --- | --- |
| `custom-mapping-source-not-found` | WARNING | An active mapping source is not in the source vector. |
| `custom-mapping-target-not-found` | WARNING | An active mapping target is not in the target vector. |
| `custom-order-name-not-found` | WARNING | An active custom-order name matches no resolved pair representative (`toPath ?: fromPath`). |

Unknown mapping endpoints and unknown order names are reported without rejecting the plan;
existing matching/fallback behavior remains in effect, and unknown order names remain ignored
by rank resolution. A partial custom order is valid: omitted path names are not warned about,
and their pairs retain stable prior order.

Other plan-level warnings appear through `MorphPlan.report.issues` as well — e.g. a
crossfade fallback that discards `missingPath` surfaces on the affected FADE pairs as a
WARNING in the `missing` category, message `"FallbackStrategy <strategy> crossfades every
pair; missingPath <behavior> is ignored"`.

The showcase app surfaces both lists in the plan-report card.

## Debug overlays

Enable during development to see what the planner resolved, then remove before
shipping UI:

```kotlin
MorphConfiguration(
    debug = MorphDebugOptions(
        showPathPoints = false,
        showPathNumbers = true,      // 7-segment index labels near centroids
        showBoundingBoxes = false,
        showCentroids = false,
        showDirection = true,        // resolved travel arrow (magenta)
        showStartPoints = true,      // resolved anchor dot (yellow)
        showPathMatching = true,     // cyan centroid↔centroid match lines
        markerArcFraction = 0.5f,    // magenta ring at the half-arc position of each contour
    ),
)
```

The red resolution-warning banner appears whenever any debug option is enabled and
the resolved draws recorded conflicts:

| `DrawResolutionWarning` | Meaning |
| --- | --- |
| `START_OVERRIDES_DIRECTION` | An explicit start anchor overrode the direction's implicit anchor. |
| `ANCHOR_IGNORED_BY_MODE` | The mode (e.g. `OUTSIDE_IN`) does not use the start anchor. |
| `CENTER_OUT_DIRECTION_ON_STROKE` | A sweep-only direction appeared on a stroke reveal. |
| `MISSING_CONCRETE_ANCHOR` | `start = AUTO` + `direction = CUSTOM` anchored top-left. |
| `CUSTOM_ANIMATION_FAILED` | The custom callback threw; it is now disabled. |

Frame-time resolution warnings are additionally readable from the renderer's test
seam; for app-side checks prefer `DirectionMath.resolve(start, direction, mode)`
directly (pure). The showcase surfaces this in `DrawConflictHint`.

## Plan cache and memory

```kotlin
MorphCaches.trim(bytes = 8L * 1024 * 1024)   // release plans under memory pressure
MorphCaches.size      // cached plan count
MorphCaches.sizeBytes // estimated bytes
```

Wire `trim` to `ComponentCallbacks2.onTrimMemory`; plans rebuild lazily on the next
composition. Rough byte accounting is per-plan (the showcase prints both numbers).
The shared cache keeps up to 64 entries (a `MorphPlanCache(maxEntries = 64)`, 16 MiB
byte budget by default for standalone caches), keyed by
`PreparedVector.contentKey` + `MorphConfiguration.planningKey()` — equal-but-distinct
`ImageVector` instances share one plan, and render-only configuration changes reuse the
cached plan instead of re-planning.

## Precompilation

Plan construction is synchronous the first time a pair is seen. Warm pairs that must
animate on first display:

```kotlin
LaunchedEffect(expectingSoon) {
    MorphPrecompiler.warm(from, to)                 // or warm(config, pair1, pair2, ...)
}

// Probe the same key `warm` used: planning fields only — render-only config changes
// never invalidate a cached plan. Historical 2-argument calls keep working (Default).
if (MorphPrecompiler.isPrepared(from, to, warmConfig)) { /* will not cost plan time */ }
```

`warm` runs on `Dispatchers.Default`, keys the cache on `config.planningKey()` (the
planning-field subset; `debug`, stagger, timing, color space etc. never fragment it),
strips the animation definition out of the planning config (timelines never influence
the plan), and is idempotent for cached pairs (`isPrepared` turns false again after
eviction/trim).

## Custom callback health

A throwing `CustomVectorAnimation` is disabled process-wide after its first failure —
the frame falls back to normal rendering instead of crashing:

```kotlin
CustomizerFailures.count       // failures since reset
CustomizerFailures.lastMessage // exception message of the most recent failure
CustomizerFailures.reset()
```

Customization of the surface is covered in [CUSTOM-GEOMETRY.md](CUSTOM-GEOMETRY.md).

## Testing advice

- Pin behavior with Robolectric pixel probes rather than formula tests: the repo's own
  suites assert that each configuration knob changes the rendered frame measurably,
  that reveals stay subsets of their final shapes, and that line morphs never invent
  geometry. Follow the same pattern for new features.
- `Enum`-complete scans catch null-holes cheaply: plan every pair in your icon set
  once per configuration knob set.
