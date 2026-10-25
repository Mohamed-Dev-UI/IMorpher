# Configuration Reference (0.2.0)

This document is the authoritative reference for every public parameter of IMorpher
0.2.0: the fields of `MorphConfiguration` and `MorphDebugOptions`, every enum, and the
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
| `customMappings` | `List<PathMapping>` | `emptyList()` | Explicit `PathMapping(source, target)` pairs; the exclusive match source for `PathMatchingStrategy.CUSTOM`, and overrides under `AUTO`. |
| `startPoint` | `StartPointStrategy` | `AUTO` | How closed contours align their starting points before morphing. |
| `pathDirection` | `PathDirectionStrategy` | `AUTO` | How contour winding (cw/ccw) is normalized between the pair. |

### Reveal scheduling

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `drawOrder` | `DrawOrderStrategy` | `BY_INDEX` | Ordering of path reveals; drives stagger timing, and render z-order only when `keepRenderOrder = false`. |
| `customDrawOrder` | `List<String>` | `emptyList()` | Explicit path-name order used when `drawOrder == CUSTOM`. |
| `staggerDelay` | `Float` | `0f` | Stagger applied to vector-level draw/reveal intervals, in normalized timeline units. |
| `staggerOrder` | `DrawOrderStrategy` | `TOP_LEFT_TO_BOTTOM_RIGHT` | Order used to assign stagger offsets. |
| `keepRenderOrder` | `Boolean` | `true` | Render paths strictly in declaration order even when a draw-order strategy reorders reveals (strategies only affect stagger timing while true). |
| `timing` | `TimingMode` | `BY_PATH_LENGTH` | How draw/reveal time maps onto geometry. |
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
| `missingPath` | `MissingPathBehavior` | `FADE` | What happens to paths that only exist on one side of the morph. |
| `fallback` | `FallbackStrategy` | `AUTO` | What to do when a clean geometric morph is impossible. |

### Geometry budget and viewport

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `segmentBudget` | `Int?` | `null` | Cap on cubic segments per contour pair; `null` keeps the natural `max(source, target)` count. Reduction merges adjacent curves by least-squares fit and re-equalizes at the largest count that fits the budget when no merge stays within tolerance. |
| `simplificationTolerance` | `Float` | `0.25f` | Maximum sampled deviation (viewport units) tolerated while `segmentBudget` reduces a contour. |
| `viewportNormalization` | `ViewportNormalization` | `STRETCH` | How the target viewport maps into the source viewport during planning. |

### Draw anchors, timelines, and debug

| Field | Type | Default | Semantics |
| --- | --- | --- | --- |
| `customDrawAnchors` | `Map<String, Float>` | `emptyMap()` | Normalized arc fraction (0–1) per path name for `PathStart.CUSTOM` draw anchoring — where the stroke pen begins on that path. |
| `animation` | `VectorAnimation?` | `null` | Optional timeline. When null, a plain geometry/color morph drives everything. See the [DSL guide](TIMELINE-DSL.md). |
| `debug` | `MorphDebugOptions?` | `null` | Debug overlay options; null disables debug rendering. |
| `customStartOffsets` | `Map<String, Float>` | `emptyMap()` | **Deprecated.** Legacy per-path alignment offsets for `StartPointStrategy.CUSTOM`, retained as the fallback draw-anchor source for `PathStart.CUSTOM` when `customDrawAnchors` has no entry for that path. Use `customDrawAnchors` for pen anchors. |

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
| `markerArcFraction` | `0f` | **Reserved in 0.2.0.** Accepted, validated and persisted on `MorphDebugOptions`, but no renderer consumes it yet — setting it has no visual effect. |

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
| `CUSTOM` | Uses the deprecated `customStartOffsets` map (normalized arc fraction per path). |

### `PathDirectionStrategy` (default `AUTO`)

| Entry | Behavior |
| --- | --- |
| `AUTO` | Reverse the target contour when its signed winding opposes the source. |
| `PRESERVE` | Never reverse; opposing windings may twist. |
| `CLOCKWISE` | Force both contours clockwise. |
| `COUNTER_CLOCKWISE` | Force both contours counter-clockwise. |

### `DrawOrderStrategy` (default `BY_INDEX`)

Stagger timing always uses it; render z-order only with `keepRenderOrder = false`.

| Entry | Behavior |
| --- | --- |
| `BY_INDEX` | Declaration order. |
| `BY_NAME` | Sorted by path name. |
| `BY_POSITION` | Top-left corner sort (same as `AUTO`). |
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
| `BY_COMMAND` | Linear in command/segment index. |
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

## Viewport and layout

Different target viewport dimensions are scaled into source-viewport space during
planning (`ViewportNormalization`). Intrinsic layout dimensions remain caller
controlled (`width`/`height` default to the target vector's). Layout is always
aspect-fit; `alignment` placement only. Crop/fill `ContentScale` and multiplatform
(KMP) rendering are out of scope of this release.

## Limits

The engine preserves what Compose `ImageVector` exposes: viewport and intrinsic size,
static nested group transforms, paths, fill/stroke brushes and alpha, stroke geometry
(width/cap/join/miter are per-path authored; width is animatable through the timeline),
path order, and group clip paths. Boolean geometry functions operate on Compose `Path`s
and are opt-in ([custom geometry](CUSTOM-GEOMETRY.md)). Custom callbacks run on the
drawing thread and must be deterministic and lightweight; a callback that throws is
disabled after its first failure instead of crashing the frame (`CustomizerFailures`).
Unknown custom brushes crossfade.
