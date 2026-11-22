# Configuration Reference (0.3.0)

This document is the authoritative reference for every public parameter of IMorpher
0.3.0: the fields of `MorphConfiguration` and `MorphDebugOptions`, every enum, and the
resolution contracts that decide how `start`, `direction`, fills, and clips behave.

The default constructor is `MorphConfiguration.Default` — a plain, well-behaved
geometry morph with auto strategies. `MorphConfiguration` is a data class, so
remember-style caching can key on it safely (diagnostics callbacks are not part of it).

---

## MorphConfiguration fields

Package: `com.imorpher.vectormorph.core.model`.

### Pairing and alignment

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `pathMatching` | `PathMatchingStrategy` | `AUTO` | How source paths pair with target paths before any geometry is touched. |
| `customMappings` | `List<PathMapping>` | `emptyList()` | Explicit `PathMapping(source, target)` pairs; the exclusive match source for `PathMatchingStrategy.CUSTOM`, and overrides under `AUTO`. Active lists are checked for blank and duplicate names; vector-aware unknown endpoints appear as plan warnings. Duplicate handling remains first-entry/first-target-wins. |
| `startPoint` | `StartPointStrategy` | `AUTO` | How closed contours align their starting points before morphing. |
| `pathDirection` | `PathDirectionStrategy` | `AUTO` | How contour winding (cw/ccw) is normalized between the pair. |

### Reveal scheduling

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `drawOrder` | `DrawOrderStrategy` | `BY_INDEX` | Controls render z-order only when `keepRenderOrder = false`; it does not control stagger timing (`staggerOrder` does). |
| `customDrawOrder` | `List<String>` | `emptyList()` | Explicit path-name order consumed by render ordering when `drawOrder == CUSTOM` and `keepRenderOrder = false`, or by path-timeline ordering when the resolved `staggerOrder == CUSTOM` and that order is consumed by a delay or `timing = BY_PATH`. Active lists are checked for blank, duplicate, and unknown names. Partial orders are valid; omitted paths retain stable prior order. |
| `staggerDelay` | `Float` | `0f` | Stagger applied to vector-level draw/reveal intervals, in normalized timeline units. |
| `staggerOrder` | `DrawOrderStrategy` | `TOP_LEFT_TO_BOTTOM_RIGHT` | Order used for path stagger offsets and `timing = BY_PATH` ranks. The config supplies the timeline order when `staggerDelay > 0` **or** `staggerOrder` differs from the default; otherwise the timeline order stands. The delay resolves independently: a positive config delay overrides the timeline delay, otherwise the timeline delay is inherited. A non-default configured order can therefore be active with a zero config delay. |
| `keepRenderOrder` | `Boolean` | `true` | Render paths strictly in declaration order; when true, `drawOrder` does not reorder paint and only `staggerOrder` affects timeline path ordering. |
| `timing` | `TimingMode` | `BY_PATH_LENGTH` | How draw/reveal time maps onto geometry; `BY_PATH` uses `staggerOrder` to assign per-path ranks even when stagger delay is zero. |
| `directionTraversal` | `DirectionTraversal` | `HONOR_DIRECTION` | How directional (`FORWARD`) stroke reveals travel along open contours. |

### Paint

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `colorSpace` | `ColorInterpolationSpace` | `SRGB` | Interpolation space for color and gradient animation. |
| `strokeFill` | `StrokeFillStrategy` | `AUTO` | Strategy for stroke↔fill transitions (outlined icon → filled icon). |
| `defaultFillMode` | `FillMode` | `DIRECTIONAL` | Fill-reveal default for paths animated through `fillProgress` without an explicit mode. |
| `defaultFillDirection` | `DrawDirection` | `TOP_RIGHT_TO_BOTTOM_LEFT` | Default fill sweep direction when a fill reveal has no direction of its own. |

### Fallbacks and missing paths

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `missingPath` | `MissingPathBehavior` | `FADE` | What happens to paths that only exist on one side of the morph. Ignored (with a plan WARNING) whenever a crossfade fallback (`CROSSFADE`/`SCALE_CROSSFADE`) crossfades every pair. |
| `fallback` | `FallbackStrategy` | `AUTO` | What to do when a clean geometric morph is impossible. |

### Geometry budget and viewport

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `segmentBudget` | `Int?` | `null` | Target ceiling for cubic segments per contour pair; `null` keeps the natural `max(source, target)` count. Values below 2 are raised to the planner minimum of 2 (`segment-budget-clamped` INFO). Each side's error-driven reduction may stop above the target when the next merge exceeds tolerance; the sides are then re-equalized at the largest surviving count, so silhouette quality can take precedence over the budget. |
| `simplificationTolerance` | `Float` | `0.25f` | Maximum finite, non-negative sampled deviation (viewport units) tolerated while `segmentBudget` reduces a contour. Invalid negative/non-finite values are treated as zero and reported (`simplification-tolerance-invalid` WARNING); a non-default valid tolerance without a budget has no effect (`simplification-tolerance-unused` INFO). |
| `viewportNormalization` | `ViewportNormalization` | `STRETCH` | How the target viewport maps into the source viewport during planning. |

### Draw anchors, timelines, and debug

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `customDrawAnchors` | `Map<String, Float>` | `emptyMap()` | Normalized arc fraction (0–1) per path name for `PathStart.CUSTOM` draw anchoring — where the stroke pen begins on that path. Requires a `PathStart.CUSTOM` on the draw track; otherwise silently unused — `MorphValidator.validateConfiguration` warns (`custom-anchors-unused`). Active values outside finite 0..1 are warned and clamped. |
| `animation` | `VectorAnimation?` | `null` | Optional timeline. When null, a plain geometry/color morph drives everything. See the [DSL guide](TIMELINE-DSL.md). |
| `debug` | `MorphDebugOptions?` | `null` | Debug overlay options; null disables debug rendering. |
| `customStartOffsets` | `Map<String, Float>` | `emptyMap()` | **Deprecated.** Legacy per-path alignment offsets: under `StartPointStrategy.CUSTOM` alignment reads this map only for paths `customStartAlignments` leaves unmapped (the 0.3 alignment home landed — see `customStartAlignments`). `customDrawAnchors` never moves geometry (it anchors pens only); this map remains the fallback draw-anchor source for `PathStart.CUSTOM` when `customDrawAnchors` has no entry for that path. Warned unused unless either alignment or a custom-start draw reveal is active; active values outside finite 0..1 are warned and clamped. Disagreeing entries for the same path are warned as `custom-start-alignment-conflict`. Migration checklist: give pen anchors to `customDrawAnchors`, then move alignment offsets to `customStartAlignments` (see [MIGRATION.md](MIGRATION.md) §2). |
| `customStartAlignments` | `Map<String, Float>` | `emptyMap()` | Per-path normalized arc-fraction (0..1) start overrides for `StartPointStrategy.CUSTOM` geometry correspondence — the modern home for `customStartOffsets`. Alignment-only: it never anchors `PathStart.CUSTOM` pens and is not consulted for draw anchoring. Warned unused without `StartPointStrategy.CUSTOM` (`custom-start-alignments-unused`); active values outside finite 0..1 are warned and clamped. Part of the planning key. |

---

## Consumer and interaction matrix

This matrix complements the field semantics above with the consuming layer and the
important precedence or capability relationships. “Planner” means plan-time geometry;
“Evaluator” means per-progress timeline values; “Renderer” means paint/layout-time work.

| Parameters | Consumer | Interaction / effectiveness contract |
| --- | --- | --- |
| `pathMatching`, `customMappings` | Planner: `PathMatcher` | `customMappings` are exclusive under `CUSTOM`, overrides under `AUTO`, and unused by other matching strategies. Active mappings with blank/duplicate names are diagnosed; missing vector endpoints appear in plan issues without changing fallback behavior. |
| `startPoint`, `customStartAlignments`, `customStartOffsets` | Planner: `ContourAligner` | `customStartAlignments`/`customStartOffsets` affect geometry alignment only for `StartPointStrategy.CUSTOM` (the new map wins per path); `customDrawAnchors` do not move geometry. |
| `pathDirection` | Planner: `ContourAligner` | Controls contour winding normalization; fill-rule-hole parity can force preservation under `AUTO`. |
| `missingPath`, `fallback` | Planner and renderer | Whole-vector crossfade fallbacks ignore non-`FADE` missing-path behavior and report it; `DRAW_REVEAL` uses the stroke reveal path. |
| `strokeFill` | Planner and renderer | Selects stroke↔fill choreography per pair; `AUTO` resolves from pair paint/geometry. |
| `segmentBudget`, `simplificationTolerance` | Planner: contour reduction | Budget is a target ceiling (minimum 2), not a hard guarantee: reduction stops before an over-tolerance merge and re-equalizes at the surviving count. Tolerance matters only with a budget; invalid values are treated as zero and diagnosed. |
| `viewportNormalization` | Planner: target normalization | `FIT` vs `STRETCH` changes normalized geometry before path matching and planning. |
| `animation` | Evaluator: `TimelineEvaluator`; renderer fallback | With a Compose animation entry, that entry's timeline drives values; renderer uses it for reveal payloads. Configuration may carry a default timeline when no explicit entry supersedes it. |
| `staggerDelay`, `staggerOrder`, `customDrawOrder` | Evaluator: `TimelineEvaluator` | Positive config delay overrides timeline delay; otherwise timeline delay is inherited. Config order overrides timeline order when config delay is positive or config order is non-default. Negative/non-finite config or animation delays are ignored and reported as `stagger-delay-invalid`. `customDrawOrder` is consumed for `CUSTOM` render order or active `CUSTOM` path-timeline ordering. |
| `timing`, `staggerOrder` | Evaluator and renderer | `BY_PATH` uses path ranks even without delay; delay staggering also uses path ranks. Vector/group tracks share their own clocks and do not consume path-order ranks. |
| `drawOrder`, `keepRenderOrder`, `customDrawOrder` | Renderer: pair ordering | With `keepRenderOrder = true`, declaration paint order wins. Otherwise `drawOrder` selects z-order; it does not define stagger timing. Duplicate order names retain their last rank; unknown names are ignored after a plan warning, and omitted names in partial orders remain stable. |
| `colorSpace` | Evaluator and renderer | Applies to animated solid/gradient interpolation and tint interpolation; does not change geometry plans. |
| `customDrawAnchors`, `customStartOffsets` | Renderer: stroke reveal resolution | `PathStart.CUSTOM` consumes the new anchor map first, then the deprecated map as per-path fallback; without a custom-start reveal both are idle. |
| `defaultFillMode`, `defaultFillDirection` | Renderer: fill reveal sites | Used only when the resolved fill reveal payload omits its mode/direction; explicit timeline values take precedence. Modes may ignore direction (e.g. `FADE`/`RADIAL`). |
| `directionTraversal` | Renderer: open-contour `FORWARD` reveal | Governs directional traversal; explicit reveal modes and closed-contour winding behavior take precedence. |
| `debug` | Renderer: overlay | `null` disables the overlay. With any non-null options object, resolution warnings can still draw; anchor collection is enabled only for `showStartPoints` or `showDirection`. |

Custom stroke/fill reveal modes (`DrawMode.CUSTOM`, `DrawDirection.CUSTOM`) and `missingPath = CUSTOM` require a configured `CustomVectorAnimation`; `validateConfiguration` warns when it is absent (`custom-callback-missing`). The callback must still return the relevant geometry at runtime.

The fields of `MorphDebugOptions` are all renderer-owned: `showPathPoints`,
`showPathNumbers`, `showBoundingBoxes`, `showCentroids`, and `showPathMatching` toggle
geometry annotations; `showStartPoints` and `showDirection` additionally request resolved
anchor collection; `markerArcFraction` places the current-point ring only for fractions
strictly between 0 and 1. None participates in the plan cache key. Note that a debug
options object can still display recorded resolution warnings even when every geometry
toggle is false.

---

## MorphDebugOptions

Drawn over the animated vector whenever `debug` is non-null. The resolution-warning
banner (red triangle + count, top-left) appears whenever warnings were recorded — with
any toggle enabled, not only the ones below.

| Field | Default | Draws |
| --- | --- | --- |
| `showPathPoints` | `false` | Segment start points of each contour. |
| `showPathNumbers` | `false` | Compact 7-segment path index labels near centroids. |
| `showBoundingBoxes` | `false` | Per-path bounding boxes. |
| `showCentroids` | `false` | Per-path centroid dots. |
| `showDirection` | `false` | The resolved travel arrow (magenta) per revealed contour. |
| `showStartPoints` | `false` | The resolved anchor dot (yellow) per revealed contour. |
| `showPathMatching` | `false` | Cyan centroid→centroid matching lines. |
| `markerArcFraction` | `0f` | Draws a small magenta **ring** at each contour position `length × fraction` along the pair's representative contours (PathMeasure); the ring moves with the option value so the pen position is visible. `0` (default) and `1` draw nothing. |

---

## Enums

### `PathMatchingStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `BY_INDEX` | Pair by declaration order; requires equal path counts. |
| `BY_NAME` | Pair by path name. |
| `BY_ID` | Pair by the id assigned at compile time (index in the vector). |
| `BY_GEOMETRY` | Pair by geometric similarity (centroid, area, length, contour structure). |
| `BY_AREA` | Pair by filled-area similarity. |
| `BY_CENTROID` | Pair by centroid proximity. |
| `AUTO` | Index when counts match and geometry corroborates; otherwise geometry matching. |
| `CUSTOM` | Pairs exclusively from `customMappings`. |

### `StartPointStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `AUTO` | Rotate the target contour's start to minimize total point-to-point correspondence distance (prevents rotational deformation between identical shapes that start at different vertices). |
| `MATCH_SOURCE` | Target aligns to the source's start point. |
| `MATCH_TARGET` | Source aligns to the target's start point. |
| `BEST_GEOMETRIC_MATCH` | Both align to the same geometric feature (e.g. dominant extreme point). |
| `CUSTOM` | Uses `customStartAlignments` (normalized arc fraction per path), falling back to the deprecated `customStartOffsets` map for paths the new map leaves unmapped. |

### `PathDirectionStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `AUTO` | Reverse the target contour when its signed winding opposes the source. |
| `PRESERVE` | Never reverse; opposing windings may twist. |
| `CLOCKWISE` | Force both contours clockwise. |
| `COUNTER_CLOCKWISE` | Force both contours counter-clockwise. |

### `DrawOrderStrategy` (default `BY_INDEX`)

Timeline staggering and `BY_PATH` rank timing use `staggerOrder`; render z-order uses `drawOrder` only with `keepRenderOrder = false`.

| Entry | Behavior |
| --- | --- |
| `BY_INDEX` | Declaration order. |
| `BY_NAME` | Sorted by path name; the `customDrawOrder` list is ignored here (reserved for `CUSTOM`). |
| `BY_POSITION` | **Deprecated (0.3.0).** Alias of `TOP_LEFT_TO_BOTTOM_RIGHT`; use `AUTO`/`TOP_LEFT_TO_BOTTOM_RIGHT`. Removal in 0.4. |
| `TOP_LEFT_TO_BOTTOM_RIGHT` | Diagonal sweep from the top-left corner. |
| `TOP_RIGHT_TO_BOTTOM_LEFT` | Diagonal sweep from the top-right corner. |
| `BOTTOM_LEFT_TO_TOP_RIGHT` | Diagonal sweep from the bottom-left corner. |
| `BOTTOM_RIGHT_TO_TOP_LEFT` | Diagonal sweep from the bottom-right corner. |
| `CLOCKWISE` | Polar angle around the viewport center. |
| `COUNTER_CLOCKWISE` | Reverse polar angle around the viewport center. |
| `AUTO` | Geometry-based top-left→bottom-right order for stagger timing. |
| `CUSTOM` | Uses the explicit `customDrawOrder` list. |

### `MissingPathBehavior` (default `FADE`)

For paths that exist on only one side:

| Entry | Behavior |
| --- | --- |
| `FADE` | Fade alpha in/out. |
| `DRAW` | Enter/exit with the stroke reveal. |
| `SCALE` | Scale from/to 0 at the centroid. |
| `COLLAPSE` | Scale to 0 with an ease-out "pop" (slight overshoot). |
| `MORPH_FROM_NEAREST` | Morph from the geometrically nearest path on the other side. |
| `KEEP` | Keep rendering the existing side while the other fades in on top. |
| `CUSTOM` | Supplied by a `CustomVectorAnimation` (see [custom geometry](CUSTOM-GEOMETRY.md)); requires the callback to return contours, otherwise the renderer throws. |

### `TimingMode` (default `BY_PATH_LENGTH`)

| Entry | Behavior |
| --- | --- |
| `BY_PATH_LENGTH` | Distance along the path — short paths finish early (constant speed). |
| `BY_COMMAND` | Linear in command/segment index. **At the vector/group clock this behaves like `UNIFORM`**; only per-contour stroke tracing applies command-count remapping. |
| `BY_PATH` | Whole paths complete one after another. |
| `UNIFORM` | Everything at the same normalized rate. |

### `DirectionTraversal` (default `HONOR_DIRECTION`)

How a `DrawMode.FORWARD` stroke reveal travels along an **open** contour. Explicit
`REVERSE`/`CENTER_OUT`/`OUTSIDE_IN` modes always win, closed contours keep their
winding-reversal behavior, and the policy applies only to the live morph-pair stroke
pass (custom-geometry, static-side, and `PATH_TRAVERSAL` fill sites don't honor it).

| Entry | Behavior |
| --- | --- |
| `HONOR_DIRECTION` | The reveal traverses **toward the requested axis**: when the contour leaves the resolved anchor heading opposite the direction's axis, the reveal plays the anchor's backwards arc instead. |
| `LEGACY` | Traversal follows the contour's own point order — the 0.1.x behavior; a contradicting direction has no effect. |

### `FillMode` (default `DIRECTIONAL`)

| Entry | Behavior |
| --- | --- |
| `FADE` | Plain alpha fade. |
| `RADIAL` | Expanding circle from the centroid. |
| `LINEAR` | Hard-edged linear sweep across the bounds. |
| `DIRECTIONAL` | Directional sweep between two corners/edges of the bounds. |
| `SWEEP` | Angular sweep around the centroid (`DrawDirection.CENTER_OUT`/`OUTSIDE_IN` choose the start angle). |
| `PATH_TRAVERSAL` | The fill boundary follows the actual path traversal (thick-stroke dash trick). |

### `ColorInterpolationSpace` (default `SRGB`)

| Entry | Behavior |
| --- | --- |
| `SRGB` | sRGB interpolation (historical). |
| `LINEAR_SRGB` | Physically linear; brighter midpoints. |
| `OKLAB` | Perceptual lightness; smooth desaturation. |
| `OKLCH` | Perceptual lightness/hue; hues spin the short way (multi-turn keeps spinning). |

### `StrokeFillStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `MORPH` | Stroke shrinks to nothing while the fill grows in. |
| `CROSSFADE` | Straight alpha crossfade. |
| `DRAW` | Stroke draws first, then the fill sweeps in. |
| `AUTO` | Analyze the pair and pick the most visually stable strategy. |

### `FallbackStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `AUTO` | Best available: geometry for compatible pairs, crossfade otherwise. |
| `BEST_EFFORT` | Always attempt a geometry morph even when the report warns about artifacts. |
| `CROSSFADE` | Pure alpha crossfade between the two vectors. |
| `SCALE_CROSSFADE` | Crossfade plus a subtle scale pulse (0.94 → 1). |
| `DRAW_REVEAL` | Reveal the target with the draw animation over the fading source. |
| `THROW` | Throw at planning time instead of silently degrading. |

### `ViewportNormalization` (default `STRETCH`)

| Entry | Behavior |
| --- | --- |
| `STRETCH` | Scale X and Y independently so the target fills the source viewport exactly (historical). |
| `FIT` | Scale uniformly by the smaller axis ratio and center the result: the target keeps its authored aspect ratio, letterboxed inside the source viewport. |

### `DrawMode` — stroke-reveal shapes

| Entry | Behavior |
| --- | --- |
| `FORWARD` | Start → end along the (policied) path direction. |
| `REVERSE` | End → start. |
| `CENTER_OUT` | Grows from the arc midpoint towards both ends simultaneously. |
| `OUTSIDE_IN` | Grows from both ends inwards towards the arc midpoint. |
| `RADIAL` | Revealed through an expanding circular clip centered on the path centroid. |
| `CUSTOM` | Supplied by `CustomVectorPathOverrides.strokeRevealContours` (see [custom geometry](CUSTOM-GEOMETRY.md)); the renderer throws without it. |

### `DrawDirection` — travel direction

`AUTO` derives the top-left anchor; the eight corner/axis directions
(`TOP_LEFT_TO_BOTTOM_RIGHT`, `TOP_RIGHT_TO_BOTTOM_LEFT`, `BOTTOM_LEFT_TO_TOP_RIGHT`,
`BOTTOM_RIGHT_TO_TOP_LEFT`, `LEFT_TO_RIGHT`, `RIGHT_TO_LEFT`, `TOP_TO_BOTTOM`,
`BOTTOM_TO_TOP`) claim a concrete travel axis; `CLOCKWISE`/`COUNTER_CLOCKWISE` anchor
at `PathStart.TOP` and drive winding traversal (never a travel axis);
`CENTER_OUT`/`OUTSIDE_IN` are fill-sweep start-angle selectors and carry no travel
meaning on strokes (warning `CENTER_OUT_DIRECTION_ON_STROKE`); `CUSTOM` requires
`CustomVectorPathOverrides.fillContours`.

### `PathStart` — pen anchors

`AUTO` (derive from `DrawDirection`), `START` (literal arc fraction 0), `END`,
`CENTER`, `TOP`, `RIGHT`, `BOTTOM`, `LEFT`, `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`,
`BOTTOM_RIGHT`, `CUSTOM` (from `customDrawAnchors`).

### `MotionPreference` — accessibility

| Entry | Behavior |
| --- | --- |
| `FULL` | Full animation. |
| `REDUCED` | Shortened, gentler animation (~120 ms tween). |
| `CROSSFADE_ONLY` | Geometry morphs become a simple crossfade (composables force `FallbackStrategy.CROSSFADE`). |
| `INSTANT` | Jump to the end state. |

Leave `motionPreference` null to resolve it from the system animator-duration scale:
`0` → `INSTANT`, `< 1` → `REDUCED`, normal → `FULL`.

### `TransformOrigin`

`TransformOrigin(x, y)` fractional within target bounds (0 = left/top, 1 = right/bottom);
companions `Center`, `TopLeft`, `TopRight`, `BottomLeft`, `BottomRight`, `Custom(x, y)`.

---

## The start/direction resolution contract

`start` (`PathStart`) and `direction` (`DrawDirection`) are two independent parameters,
resolved by one precedence contract (`DirectionMath.resolve`) — the same precedence at
every stroke-reveal site:

- **`start` owns the anchor.** An explicit `start != AUTO` always wins. A direction
  that carries its own anchor and would travel from a different anchor is recorded as
  `DrawResolutionWarning.START_OVERRIDES_DIRECTION` (surfaced in the debug banner),
  never silently dropped. `start = AUTO` derives the anchor from `direction` — with
  `direction = AUTO` that anchor is the geometric top-left point of the contour.
- **`start = START` is a literal anchor** — arc position 0 of the normalized contour.
- **`AUTO` + `CUSTOM` direction has no concrete anchor**: the reveal anchors top-left
  and records `MISSING_CONCRETE_ANCHOR` (intended; in 0.1.x this combo anchored at
  arc 0).
- **`direction` owns the travel.** With `directionTraversal = HONOR_DIRECTION`, a
  `FORWARD` stroke reveal on an open contour traverses toward the requested axis
  whenever the contour's own point order runs the other way. `LEGACY` keeps the
  contour-point-order traversal. Closed contours are untouched by the policy — their
  traversal control is the `CLOCKWISE`/`COUNTER_CLOCKWISE` winding reversal. Explicit
  `REVERSE`/`CENTER_OUT`/`OUTSIDE_IN` modes always win over the policy, and
  `OUTSIDE_IN` ignores the start anchor entirely (warning
  `ANCHOR_IGNORED_BY_MODE`).
- **Sweep-only directions.** `DrawDirection.CENTER_OUT` and `OUTSIDE_IN` are fill-sweep
  semantics (a `FillMode.SWEEP` start angle). On a stroke reveal they carry no travel
  meaning and emit `CENTER_OUT_DIRECTION_ON_STROKE` via the resolver.

## Fill direction inheritance

A per-path or vector-level `fill()` without an explicit direction inherits the target's
first explicitly-declared non-AUTO stroke draw direction **at build time**.
`AUTO`-default strokes are not sources, so such fills fall back to
`MorphConfiguration.defaultFillDirection`. Explicit fill directions are never overridden.

A direction a mode cannot use is *not* an error — it is silently ignored, and
`MorphValidator.validateConfiguration` reports the combo statically
(`fill-direction-ignored` for `FADE`/`RADIAL`, `sweep-direction-on-stroke` for
`CENTER_OUT`/`OUTSIDE_IN` on strokes).

## Fill-mode rendering map

What each `FillMode` does with a fill-reveal `direction` at render time:

| `FillMode` | `direction` consumption |
| --- | --- |
| `FADE` | Ignored — no travel axis; a plain alpha fade. |
| `RADIAL` | Ignored — an expanding circle from the centroid. |
| `LINEAR` | Hard wipe: the region *behind the edge* reveals instantly (hard clip edge, no feather). |
| `DIRECTIONAL` | Feathered sweep: full within the revealed region, an alpha ramp (~15% of the sweep distance) across the edge band via a `BlendMode.DstIn` layer mask. |
| `SWEEP` | Angular sweep; `DrawDirection.CENTER_OUT`/`OUTSIDE_IN` pick the start angle — resolved identically at **both** sites (clip masks and fill painting share one `sweepStartAngle`). |
| `PATH_TRAVERSAL` | The fill boundary follows the actual path traversal; it inherits the stroke choreography (mode/start/direction) like a drawn stroke. |

## Sites that honor `directionTraversal`

`DirectionTraversal` (contour-order vs. direction-axis traversal for `DrawMode.FORWARD`
strokes on **open contours**) applies at all four reveal sites:

1. morph-pair stroke drawing,
2. missing-path `DRAW` reveal (appearing/disappearing paths),
3. `FillMode.PATH_TRAVERSAL` fill tracing,
4. custom-geometry contours.

Still excluded, by design: **closed contours** — their traversal control remains the
`CLOCKWISE`/`COUNTER_CLOCKWISE` winding reversal — and explicit
`REVERSE`/`CENTER_OUT`/`OUTSIDE_IN` modes, which always win over the policy.

## Viewport and layout

Different target viewport dimensions are scaled into source-viewport space during
planning (`ViewportNormalization`). Intrinsic layout dimensions remain caller
controlled (`width`/`height` default to the target vector's). Layout resolves the plan's
viewport into the layout box through Compose's `ContentScale` — `Fit` (the default) keeps the
historical aspect-fit letterbox; crop/fill/custom values follow Compose's own contract with
`alignment` placement. Multiplatform (KMP) rendering is out of scope of this release.

## Limits

The engine preserves what Compose `ImageVector` exposes: viewport and intrinsic size,
static nested group transforms, paths, fill/stroke brushes and alpha, stroke geometry
(width/cap/join/miter are per-path authored; width is animatable through the timeline),
path order, and group clip paths. Boolean geometry functions operate on Compose `Path`s
and are opt-in ([custom geometry](CUSTOM-GEOMETRY.md)). Custom callbacks run on the
drawing thread and must be deterministic and lightweight; a callback that throws is
disabled after its first failure instead of crashing the frame (`CustomizerFailures`).
Unknown custom brushes crossfade.
