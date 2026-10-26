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
| `custom-anchors-unused` | WARNING | `customDrawAnchors` non-empty but no draw track requests `PathStart.CUSTOM` (the `customStartOffsets` fallback is equally idle without one). |
| `custom-order-unused` | WARNING | `customDrawOrder` non-empty while `drawOrder != CUSTOM` (only `CUSTOM` consults the list). |
| `missing-path-under-crossfade` | WARNING | `missingPath != FADE` under `fallback = CROSSFADE`/`SCALE_CROSSFADE` — crossfades discard the behavior. |
| `fill-direction-ignored` | WARNING | A `FADE`/`RADIAL` fill reveal declares a travel direction its mode ignores. |
| `fallback-throw-scope` | INFO | `fallback = THROW` throws only when one side has no paths at all; other artifacts degrade silently — check the plan report. |
| `draw-order-not-rendered` | INFO | A non-default `drawOrder` shapes reveal timing while `keepRenderOrder = true` keeps declaration paint (z-)order. |
| `stagger-order-without-delay` | INFO | `staggerOrder` set while `staggerDelay == 0f` — the order needs a delay to do anything. |
| `sweep-direction-on-stroke` | INFO | `CENTER_OUT`/`OUTSIDE_IN` on a stroke reveal (fill-sweep start-angle selectors only). |
| `custom-mappings-unused` | INFO | `customMappings` under a matching strategy other than `AUTO`/`CUSTOM`. |

Plan-level warnings escape through `MorphPlan.report.issues` instead — e.g. a crossfade
fallback that discards `missingPath` surfaces on the affected FADE pairs as a WARNING in
the `missing` category, message `"FallbackStrategy <strategy> crossfades every pair;
missingPath <behavior> is ignored"`.

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
