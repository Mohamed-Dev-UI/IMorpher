# Morphing Compose Shapes and Paths

`MorphShape` and `MorphPath` extend the geometry morph to inputs that are not `ImageVector`s.
They convert the endpoint to normalized cubic contours, then use the same matching, alignment,
interpolation, paint, and fallback pipeline as icons. This is a real geometry morph, not a
crossfade of rendered pixels.

```kotlin
MorphShape(
    from = RoundedCornerShape(4.dp),
    to = CircleShape,
    progress = lerp(0f, 1f, fraction),
    fillFrom = Color(0xFF1565C0),
    fillTo = Color(0xFFAD1457),
    width = 64.dp,
    height = 64.dp,
    contentDescription = "Progress ring",
)
```

```kotlin
val square = Path().apply { addRect(Rect(4f, 4f, 20f, 20f)) }
val circle = Path().apply { addOval(Rect(4f, 4f, 20f, 20f)) }

MorphPath(
    from = square,
    to = circle,
    progress = fraction,
    viewportWidth = 24f,   // the paths are authored in a 24×24 space
    viewportHeight = 24f,
    width = 96.dp,
    height = 96.dp,
)
```

Brush fills and strokes use the `MorphPaint` overloads. For paths, brush coordinates stay in
the authored 24×24 viewport; `strokeWidth` is a physical dp width:

```kotlin
MorphPath(
    from = square,
    to = circle,
    progress = fraction,
    paintFrom = MorphPaint(
        fill = Brush.linearGradient(
            colors = listOf(Color(0xFF1565C0), Color(0xFFAD1457)),
            start = Offset(0f, 0f),
            end = Offset(24f, 24f),
        ),
        stroke = SolidColor(Color.White),
        strokeWidth = 1.5.dp,
    ),
    paintTo = MorphPaint.solid(Color(0xFFAD1457)),
    viewportWidth = 24f,
    viewportHeight = 24f,
    width = 96.dp,
    height = 96.dp,
)
```

Progress is caller-owned and clamped by the renderer. At `0` the source endpoint draws exactly;
at `1` the target does. Interruption, seeking, reduced motion, and animation specs are the same
concerns as the vector entry points — use `rememberVectorAnimation` and pass its value as
`progress` when you want Compose to drive the clock.

## Coordinate and size semantics

| Entry point | Geometry resolved from | Viewport | Re-resolved when |
| --- | --- | --- | --- |
| `MorphShape` | `Shape.createOutline(size, layoutDirection, density)` at the rendered pixel size | The resolved pixel size (`width × height` at the current density) | Shape, width, height, layout direction, or density changes |
| `MorphPath` | The path's own coordinates | Explicit `viewportWidth × viewportHeight` | A path, viewport, paint, fill type, or rendered size (for stroke-width conversion) changes |

`MorphShape` maps geometry 1:1 into the canvas because it resolves the outline at the exact
composed size. A shape that changes with size (a corner radius in dp, a percentage radius, a
custom outline) is therefore re-evaluated rather than stretched from a stale plan.

`MorphPath` never assumes that path units are pixels. The declared viewport is fitted uniformly
into the layout and centered (or aligned by `alignment`), so one `24 × 24` path morphs cleanly at
any rendered size. If the declared viewport's aspect ratio differs from the layout, the renderer
letterboxes instead of stretching.

`MorphShape`'s default size is `24.dp × 24.dp`. Pass explicit `width`/`height` to control both resolution
and layout; a conflicting size modifier is not used to resolve geometry, so keep these parameters
authoritative.

## Which outlines are exact, and which are sampled

Compose has three `Outline` kinds; all are supported:

| Outline | Conversion |
| --- | --- |
| `Outline.Rectangle` | Four exact line cubics. |
| `Outline.Rounded` | Edges plus exact quarter-ellipse cubics at each corner. `CircleShape` and `RoundedCornerShape(50)` resolve here, so circles stay smooth with four segments. Corner radii are clamped to half the rectangle's width/height. |
| `Outline.Generic` | The path is sampled adaptively (below). |

Explicit `Path` inputs are always sampled. Sampling walks every contour with Android's
`PathMeasure`, collects positions, simplifies them with Douglas–Peucker at
`PathGeometry.DEFAULT_TOLERANCE` (`0.25` viewport units), and emits line cubics. The cap is
`PathGeometry.DEFAULT_MAX_SEGMENTS_PER_CONTOUR` (512 samples per contour), which keeps
preparation bounded on very long paths.

Sampling preserves:

- **Contour count and order** — multi-contour paths stay multi-contour.
- **Direction** — clockwise/counter-clockwise winding survives, so `NonZero` fill-rule holes
  still cancel their enclosing contour.
- **Open/closed state** — measured from the platform path, not inferred from the endpoint
  position. Open contours remain open and are reported in `PathGeometryResult.openContourCount`.

The tolerance is a visual-quality knob, not a coordinate normalization step. Smaller values give
smoother curves and more segments; larger values simplify faster. For most icon and shape sizes,
the default is visually indistinguishable from the source at normal scale.

## Paint and fill

Shapes and paths carry no intrinsic paint. The solid-color overloads accept `fillFrom`/`fillTo`
(progress `0`/`1`) and paint a solid endpoint fill; the default is opaque black at both endpoints.
For gradient fills, strokes, and independent fill/stroke styling, use the overload that takes a
[MorphPaint](../vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/MorphPaint.kt)
per endpoint:

```kotlin
MorphPaint(
    fill = Brush.linearGradient(...),  // or null for stroke-only geometry
    fillAlpha = 1f,
    stroke = SolidColor(Color.White),
    strokeAlpha = 1f,
    strokeWidth = 2.dp,                 // 0.dp disables the stroke
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    strokeLineMiter = 4f,
)
```

- **Brush coordinate space** matches the geometry viewport. `MorphShape` resolves in layout
  pixels, so a 96 px wide shape uses gradient coordinates from `Offset(0f, …)` to
  `Offset(96f, …)`. `MorphPath` keeps its authored units, so the same gradient usually uses the
  declared `viewportWidth`/`viewportHeight` instead. Brush coordinates are not re-normalized.
- **Stroke width** is a `Dp` width, not a viewport value. It is converted through the renderer's
  uniform viewport-to-layout scale, so a stroke keeps its physical thickness when the component
  or path viewport changes. `0.dp` or a null stroke disables stroking.
- **Two independent layers.** Fill and stroke each carry a brush and an alpha. A null fill leaves
  the interior empty; a null stroke leaves the outline unpainted. Endpoint alphas, widths, and
  supported brush parameters interpolate with progress.
- **Supported interpolation.** `SolidColor`, `LinearGradient`, `RadialGradient`, and
  `SweepGradient` are parameterized. Same-kind gradients interpolate smoothly; a solid endpoint
  is promoted to the other endpoint's gradient kind where possible. Incompatible gradient kinds
  and custom `Brush` subclasses use the existing paint crossfade fallback instead of inventing
  parameters.
- **Fill rule** is shared by both endpoints. `MorphPath` accepts `pathFillType` (default
  `NonZero`), so holes from opposite contour winding survive the morph.

The fill is rendered through the same two complementary geometry legs as vector pairs. At the
midpoint, opaque overlapping fills composite to `0.75` alpha; this is the established vector
fill contract, not a fallback. Endpoints are opaque and exact.

## Diagnostics and fallback

Empty or unmeasurable geometry (an empty path, a zero-sized shape, a shape whose
`createOutline` throws) yields a `PathGeometryResult` with `isEmpty = true` and a warning. The
adapter then exposes a `PreparedVector` with no paths, which makes the planner's existing
structural fallback select a visual transition and record it in `MorphPlan.report`
(`fallbackUsed`, `fallbackReason`, and compatibility issues). No correspondence is invented.

Use the core adapter directly to preflight geometry before composing:

```kotlin
val geometry = PathGeometry.contours(
    shape = myShape,
    size = Size(widthPx, heightPx),
    layoutDirection = LayoutDirection.Ltr,
    density = Density(1f, 1f),
)
if (geometry.isEmpty) {
    // choose MorphContent / MorphPainter instead
}
geometry.openContourCount      // open contours morph with open ends
geometry.contours.size         // including holes
geometry.warnings              // empty or failed extraction
```

`PathGeometry.preparedVector(...)` is the same compilation step the composables use. It is public
so tooling and tests can inspect the viewport, single path, fill spec, and precomputed features
without composing.

## Performance and lifecycle notes

- Geometry resolution and planning run on first composition for a given
  (shape/path, size, direction, density, paint, configuration) tuple. The plan is stored in the
  shared structural cache, so equal geometry shares one plan. Changing a brush, alpha, or stroke
  setting rebuilds the prepared vector and plan without re-sampling the shape/path geometry.
- Pass stable `Shape` and `Path` instances. A `RoundedCornerShape` is a data class and compares
  structurally; a `Path` is identity-keyed, so rebuilding it each frame re-samples it. Mutating a
  path in place is not detected — build a new `Path` when its geometry changes.
- Sampling work is proportional to path length, not to frame count. It happens during
  composition/remember, never per frame.
- The sampled contour arrays are bounded by the segment cap; planning still honors
  `segmentBudget`/`simplificationTolerance` for interpolation density.
- `MorphShape` re-resolves when density changes (for example, a configuration change). This is
  intentional: corner radii authored in dp must stay physically sized.

## Comparison with the other entry points

| API | Input | Mode |
| --- | --- | --- |
| `MorphVector` / `MorphIcon` | `ImageVector` | Geometry + supported paint interpolation |
| `MorphShape` | Compose `Shape` | Geometry morph, solid or brush fill + stroke |
| `MorphPath` | Compose `Path` | Geometry morph, solid or brush fill + stroke |
| `MorphPainter` / `MorphImage` | Opaque painter or bitmap | Visual transition (no geometry inference) |
| `MorphContent` | Arbitrary composable slots | Visual transition (no tree introspection) |

## Non-goals

- Extracting geometry from `Painter`s or composables automatically.
- Guaranteeing semantic correspondence between unrelated paths; the planner matches by index,
  geometry, or explicit mappings exactly as it does for icons.
- Compose Multiplatform. The sampler uses the Android `PathMeasure`, matching the library's
  Android target.
- Per-endpoint dash arrays or per-contour paints. Paint is one fill plus one stroke per
  endpoint, applied to the whole endpoint path; `contentScale`/`alignment` only map the
  endpoint's viewport (the authored `viewportWidth`/`viewportHeight`, or the resolved layout
  size for shapes) into the layout box.
