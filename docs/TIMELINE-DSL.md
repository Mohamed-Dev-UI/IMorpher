# Timeline DSL Cookbook

The timeline is a `VectorAnimation`, built with the top-level factory:

```kotlin
VectorAnimation { /* builder scope */ }
```

A timeline with no tracks is a plain morph evaluation; tracks overlay per-property
interpolation on top of the planned geometry. Attach it through the Compose API family:

```kotlin
// definition + manual driver (you own progress)
val definition = VectorAnimationDefinition(vector = icon, animation = timeline)
VectorAnimator(animation = definition, progress = progress)

// auto-playing one-shot (plays 0→1 once; pass progress = x to follow external progress)
VectorAnimation(vector = icon, animation = timeline)

// convenience alias of VectorAnimation
DrawIcon(vector = icon, animation = animationSpecDrivenTimeline)
```

Reuse one `VectorAnimationDefinition` across compositions, remember the built
`VectorAnimation` at definition time (it is immutable), and pass a Compose
`AnimationSpec<Float>` (tween/spring/infinite repeatables) to drive overall progress.

---

## Scopes and targets

Within the builder scope:

| Method | Target |
| --- | --- |
| (default) | The whole rendered vector. |
| `path("name") { }` | Every occurrence of that named path. |
| `pathIndex(2) { }` | The path at that declaration index. |
| `allPaths { }` | Each path individually (stagger interacts naturally). |
| `group("name") { }` | All paths under a named group, at any depth. |

Duplicate `at(time)` keyframes merge; later keys win. Every `interval` is normalized
`0f..1f`; overlapping explicit segments are legal and build warnings are collected
into `animation.warnings` (advisory only).

**Per-scope easing:** inside `path/group/allPaths`, set `easing = ...` to override the
default easing for everything below it in that scope.

---

## Vector-level tracks (inside the builder scope)

```kotlin
VectorAnimation {
    reveal(mode = DrawMode.FORWARD, start = PathStart.AUTO,
           direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT, interval = 0f..0.7f)
    drawProgress(from = 0f, to = 1f, interval = 0f..0.7f)   // raw reveal shorthand
    fillProgress(from = 0f, to = 1f, interval = 0.5f..0.9f)
    fill(mode = FillMode.DIRECTIONAL, direction = null, interval = 0.5f..0.9f)
    alpha(from = 0f, to = 1f, interval = 0f..0.3f)
    scale(from = 0.92f, to = 1f, interval = 0.2f..1f, origin = TransformOrigin.Center)
    rotation(from = -24f, to = 0f, interval = 0f..1f, pivot = TransformOrigin.Center)
    translation(fromX = 0f, fromY = -4f, toX = 0f, toY = 0f)
    color(from = Color.Gray, to = Color.Black)          // fills — use strokeColor for strokes
    strokeColor(from = Color.Gray, to = Color.Black)
    gradient(from = linearGradientSpec(...), to = radialGradientSpec(...))
    morphProgress(from = 0f, to = 1f, interval = 0f..1f)
    clipReveal(from = 0f, to = 1f, direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)
    stagger(delay = 0.08f, order = DrawOrderStrategy.CLOCKWISE)
}
```

Notes that pin behavior:

- `reveal(..., followWithFill = true)` (default) trails a directional fill after the
  stroke, starting at `interval.start + span × 0.65`, using `FillMode.DIRECTIONAL` in
  the stroke direction when the fill itself has none.
- `fill(...)` without an explicit direction inherits the target's first explicitly
  declared non-AUTO stroke direction **at build time**; otherwise it falls back to
  `MorphConfiguration.defaultFillDirection`. Explicit directions are never overridden.
- `gradient`/`strokeGradient` interpolate known linear/radial/sweep pairs of the same
  kind (geometry, colors, stops). Mismatched kinds crossfade.
- `rotation` takes degrees, clockwise, shortest angular route; anchors via `pivot`.
- Stroke widths animate per path (`strokeWidth(from, to)` in a path scope; viewport
  units). The outline→filled handover itself is **not a track** — `strokeFill` does not
  exist in the DSL; that behavior comes from `MorphConfiguration.strokeFill`
  (`MORPH`, `CROSSFADE`, `DRAW`, `AUTO`), see [CONFIGURATION.md](CONFIGURATION.md).

## Keyframes

```kotlin
VectorAnimation {
    at(0f) { alpha = 0f; scale = 0.9f }
    at(0.28f) { rotation = 12f }
    at(0.65f) { fillProgress = 1f }
    at(1f) { scale = 1f; rotation = 8f }
    path("highlight") {
        easing = SpringEasing.MediumBouncy
        alpha(from = 0f, to = 1f, interval = 0.35f..0.8f)
    }
}
```

Available keyframe properties: `alpha`, `scale`, `scaleX`, `scaleY`, `rotation`,
`translationX`, `translationY`, `reveal` (draw/reveal shorthand), `drawProgress`,
`fillProgress`, `morphProgress`, `strokeWidth`, `fillColor`.

---

## Per-path tracks

Inside `path("name") { }`, `pathIndex(n) { }`, `allPaths { }`, `group("name") { }`:

```kotlin
path("outline") {
    strokeReveal(
        start = PathStart.TOP_RIGHT,
        direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
        interval = 0f..0.55f,
        mode = DrawMode.FORWARD,
        easing = LinearEasing,
    )
    drawProgress(from = 0f, to = 1f, interval = 0f..0.6f, mode = DrawMode.CENTER_OUT)
    fillProgress(from = 0f, to = 1f, interval = 0.4f..0.8f)
    fill(mode = FillMode.RADIAL, direction = null, interval = 0.4f..0.8f)
    clipReveal(from = 0f, to = 1f, direction = DrawDirection.LEFT_TO_RIGHT)
    alpha / fillAlpha / strokeAlpha / translation / strokeWidth (viewport units)
    color(from, to, interval)            // fill paint
    strokeColor(from, to, interval)      // stroke paint
    gradient(from, to, interval)         // fill from BrushSpec
    strokeGradient(from, to, interval)   // stroke from BrushSpec
    scale(from, to, interval, origin = TransformOrigin.Center)
    rotation(from, to, interval, pivot)
    morph(from = 0f, to = 1f, interval = 0f..1f)
}
```

Draw resolutions follow one precedence contract (start owns the anchor, direction
owns the travel, sweep-only directions warn on strokes) — documented fully in
[CONFIGURATION.md](CONFIGURATION.md#the-startdirection-resolution-contract).

---

## Easings

Composing `AnimationSpec<Float>` drives the total progress; tracks accept a
deterministic spring shape:

```kotlin
SpringEasing.MediumBouncy   // damping 0.5, stiffness 30
SpringEasing.LowBouncy      // damping 0.75, stiffness 400
SpringEasing.NoBouncy       // critically damped
SpringEasing.of(dampingRatio, stiffness)
```

Springs are numerically integrated once at construction and baked into a sampled
curve, so per-frame evaluation stays deterministic. For cycling icon states:

```kotlin
MorphIcon(
    from = a, to = b, selected = selected,
    animationSpec = infiniteRepeatable(tween(1600), repeatMode = RepeatMode.Reverse),
)
```

---

## Stagger

```kotlin
VectorAnimation {
    stagger(delay = 0.08f, order = DrawOrderStrategy.CLOCKWISE)
    ...
}
```

`pairDelay = rank × delay`, optionally squeezed so the last path still starts within
the timeline (capped at 0.95). Order affects position in the stagger queue; rendering
z-order stays in declaration order unless `keepRenderOrder = false`.

---

## Gradient specs

Compose `Brush` does not expose a stable parameter-reading API, so the engine's
`BrushSpec` model carries the geometry. Built-in factories (viewport/unit space):

```kotlin
linearGradientSpec(colors, startX = 0f, startY = 0f, endX = 1f, endY = 0f)
linearGradientSpecAtAngle(colors, angleDegrees, length = 1f,
                          centerX = 0.5f, centerY = 0.5f, stops = null)
radialGradientSpec(colors, centerX = 0.5f, centerY = 0.5f, radius = 0.7f)
sweepGradientSpec(colors, centerX = 0.5f, centerY = 0.5f)
```

Angle-marked linear endpoints interpolate along the shortest angular arc while
keeping their center and axis length. `BrushSpec.from(brush)` extracts parameters
from Compose's built-in brushes; unknown brushes degrade to crossfades.

## Serialization

```kotlin
val bytes = VectorAnimationCodec.encode(animation)
val restored = VectorAnimationCodec.decode(bytes)
VectorAnimator(VectorAnimationDefinition(icon, restored), progress)
```

Wire format v2 stores typed reveal records; v1 bytes still decode. Easings are baked
to 257 samples. Custom animation callbacks and custom `Brush` subclasses are
rejected at encode time with descriptive errors.
