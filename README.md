# ImageVector Morph

An ImageVector-first vector morphing and drawing engine for Jetpack Compose. It compiles
`ImageVector` hierarchies once into normalized cubic geometry, plans path correspondence and
fallbacks before animation starts, then renders frames directly with Compose `DrawScope`.
It does not require hand-authored `AnimatedVectorDrawable` XML.

The repository is split into two Android library modules:

- `vector-morph-core`: vector compilation, cubic normalization, matching, morph plans,
  timelines, gradients, diagnostics, and caching.
- `vector-morph-compose`: composables and the `DrawScope` renderer.

## Installation

Add Maven Central to the repositories used by your Android project and depend on the Compose
artifact:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.mohamed-dev-ui:vector-morph-compose:<version>")
}
```

The Compose artifact exposes core APIs transitively. It targets Android API 23+ and uses Jetpack
Compose. Replace `<version>` with the latest release available from Maven Central. Enable the
Kotlin Compose compiler plugin in the consuming Android app module.

## Demo app

The `app` module contains a runnable showcase for state-driven morphs, manual progress, directional
stroke drawing, keyframe timelines, group transforms, gradient interpolation, and reduced motion.
Open the project in Android Studio and run the `app` configuration.

## Basic morph

```kotlin
MorphIcon(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    selected = selected,
    animationSpec = tween(350),
    contentDescription = "Home",
)
```

`MorphIcon` animates from the currently displayed progress when `selected` changes quickly.
The composable owns its progress; `MorphVector` is the corresponding caller-controlled API:

```kotlin
MorphVector(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    progress = dragProgress.coerceIn(0f, 1f),
    configuration = MorphConfiguration(pathMatching = PathMatchingStrategy.BY_NAME),
)
```

The default size comes from the target vector's intrinsic dimensions. Pass `width` and `height`
to override it. Rendering preserves the source viewport's aspect ratio and centers it inside the
layout bounds.

## Navigation icons and reduced motion

Use a stable state and supply a content description (or put semantics on the surrounding
clickable if that is where the action belongs):

```kotlin
MorphIcon(
    from = IMorpherIcons.Home.Outlined,
    to = IMorpherIcons.Home.Filled,
    selected = selected,
    contentDescription = "Home",
    motionPreference = MotionPreference.REDUCED,
)
```

`MotionPreference` supports `FULL`, `REDUCED` (short tween), `CROSSFADE_ONLY`, and `INSTANT`.
The animation canvas does not consume pointer input; the caller owns click handling and touch
targets. A provided `contentDescription` is exposed as Compose semantics.

## Tinting

Every public composable (`MorphIcon`, `MorphVector`, `VectorAnimation`, `VectorAnimator`,
`DrawIcon`) has two tinted overloads: one taking colors, one taking brushes. Each overload takes
**two endpoint tints** — `tintFrom` and `tintTo` — and interpolates between them as the morph
progresses, so the tint itself transitions cleanly from the unselected to the selected look:

```kotlin
MorphIcon(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    selected = selected,
    tintFrom = MaterialTheme.colorScheme.onSurfaceVariant,
    tintTo = MaterialTheme.colorScheme.primary,
    contentDescription = "Home",
)
```

`tintTo` defaults to `tintFrom`, so a single-color call keeps one constant tint. Pass a
[Compose `Brush`](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush)
to the brush overloads for gradients (`Brush.linearGradient`, `Brush.radialGradient`,
`Brush.sweepGradient`, or any custom `Brush`). Built-in brushes of the same kind interpolate
smoothly — geometry, colors, and stops — while mismatched kinds, custom brushes, and null
endpoints crossfade (a null endpoint fades the tint in or out from transparency):

```kotlin
MorphVector(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    progress = dragProgress,
    tintFrom = Brush.linearGradient(listOf(Color(0xFF536DFE), Color(0xFF00BCD4))),
    tintTo = Brush.linearGradient(listOf(Color(0xFFFFC107), Color(0xFFFF5722))),
)
```

Either way, the tint replaces whatever paint the icons carry — fills, strokes, and animated
gradients — while preserving each pixel's alpha, so fades, stroke reveals, and antialiased edges
stay intact. Tint gradient coordinates resolve against the icon's layout bounds, matching
`Modifier.background(brush)`. Color tints interpolate in the configuration's
`ColorInterpolationSpace`. Tinting adds one offscreen layer pass per frame, so use the untinted
overloads when the icons' own paint is wanted.

## A single vector and manual progress

```kotlin
val definition = VectorAnimationDefinition(
    vector = IMorpherIcons.Automation,
    animation = VectorAnimation {
        reveal(
            direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            interval = 0f..0.6f,
        )
        scale(from = 0.92f, to = 1f, interval = 0.55f..1f)
    },
)

VectorAnimator(animation = definition, progress = externallyControlledProgress)
```

For an automatically played one-shot, use `VectorAnimation(vector, animation)` or its
`DrawIcon` convenience wrapper. To animate a `selected` Boolean with the same interruption
behavior as `MorphIcon`, use `rememberVectorAnimation(targetProgress, animationSpec)`.

## Drawing and stroke reveal

Path lengths are measured during vector preparation. Stroke reveal uses Compose `PathMeasure`
and extracts segments by distance, so timing follows geometric length rather than command count.
Use path names to give separate parts different reveal styles:

```kotlin
val animation = VectorAnimation {
    path("outline") {
        strokeReveal(
            start = PathStart.TOP_RIGHT,
            direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            interval = 0f..0.55f,
        )
    }
    path("detail") {
        drawProgress(interval = 0.25f..0.8f, easing = LinearEasing)
    }
}

DrawIcon(vector = IMorpherIcons.Automation, animation = animation)
```

`DrawMode` includes forward, reverse, center-out, outside-in, and radial-style center-out.
`PathStart` may be an endpoint, center, side, corner, or normalized custom offset.
For closed contours, the planner can normalize winding and choose a low-error start alignment.
With no explicit anchor, `DrawDirection.AUTO` chooses the geometric top-left point. The direction
enum otherwise selects a geometric start anchor and the stroke continues in contour traversal
order. Directional fill reveal is a separate clipping operation. `DrawOrderStrategy.AUTO` orders
stagger timing from top-left to bottom-right; render z-order stays in the source declaration order.

## Fill, color, and gradients

Every path can animate fill/stroke alpha and width, fill and stroke paint, and fill reveal. Solid
colors are interpolated in the configured `ColorInterpolationSpace` (`SRGB`, `LINEAR_SRGB`,
`OKLAB`, or `OKLCH`). Known Compose solid, linear, radial, and sweep brushes can be interpolated
by gradient geometry, colors, and stops. Incompatible gradient kinds and custom brush classes
crossfade.

```kotlin
VectorAnimation {
    path("body") {
        fill(mode = FillMode.DIRECTIONAL,
            direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            interval = 0.2f..0.75f)
        gradient(
            from = BrushSpec.Solid(Color.Gray),
            to = BrushSpec.Linear(
                colors = listOf(Color(0xFFFF5722), Color(0xFFFFC107)),
                stops = listOf(0f, 1f),
                startX = 0f, startY = 0f, endX = 24f, endY = 24f,
            ),
        )
        strokeWidth(from = 1f, to = 1.5f)
        strokeColor(from = Color.DarkGray, to = Color(0xFFFF5722))
    }
}
```

The gradient track constructors are explicit `BrushSpec` values because Compose `Brush` does not
provide a stable public API for reading all shader parameters. `BrushSpec.from` recognizes
Compose's built-in gradient implementations; if parameter extraction is unavailable for a
Compose version or a custom brush, the engine keeps the original brush and reports/crossfades
where possible.

## Path order, matching, and transforms

`MorphConfiguration` selects a path matching strategy (`AUTO`, by index/name/id, geometry,
area, centroid, or custom). Supply `customMappings = listOf(PathMapping("outline", "body"))`
when names differ. Unmatched paths are handled by `MissingPathBehavior` (fade, draw, scale,
collapse, keep, or one-to-one morphing from the nearest unused source path). Draw order strategies
control stagger assignment. Set `keepRenderOrder = false` to also render in the configured order;
the default preserves declaration order to retain authored overlap/z-order.

The timeline DSL supports vector, path, all-path, and named-group targets. Tracks can overlap or
run in sequence using normalized `0f..1f` intervals. `at(time) { ... }` adds keyframes; path
scopes have their own easing; `stagger(delay, order)` offsets path timelines. Compose
`AnimationSpec<Float>` drives the overall progress and supports tweens and spring specs. Individual
tracks accept a deterministic spring-shaped easing:

```kotlin
VectorAnimation {
    at(0f) { alpha = 0f; scale = 0.9f }
    at(0.18f) { reveal = 1f }
    at(0.65f) { fillProgress = 1f }
    at(1f) { scale = 1f; rotation = 8f }
    path("highlight") {
        easing = SpringEasing.MediumBouncy
        alpha(from = 0f, to = 1f, interval = 0.35f..0.8f)
    }
}
```

Use `infiniteRepeatable(tween(...), repeatMode = RepeatMode.Reverse)` as the driving
`animationSpec` for a repeated Compose animation.

Supported transform properties are vector-, group-, and path-level alpha, scale, rotation,
translation, and fractional pivot. Rotation takes the shortest angular route. Named group scopes
target all descendant paths; nested group alpha, paint, and transforms are evaluated independently.
Each group transform uses a shared viewport-space box spanning both endpoints, then composes
outer-to-inner. Static Compose group transforms are baked into prepared geometry.

`MorphConfiguration.timing` controls path scheduling. `BY_PATH_LENGTH` gives longer paths a
longer draw interval while keeping stroke travel distance-based; `BY_PATH` sequences whole paths
in draw order; `BY_COMMAND` allocates equal time to each path segment; `UNIFORM` uses one shared
timeline.

## Advanced configuration, diagnostics, and fallback

```kotlin
val config = MorphConfiguration(
    pathMatching = PathMatchingStrategy.BY_GEOMETRY,
    startPoint = StartPointStrategy.AUTO,
    pathDirection = PathDirectionStrategy.AUTO,
    missingPath = MissingPathBehavior.DRAW,
    fallback = FallbackStrategy.AUTO,
    colorSpace = ColorInterpolationSpace.OKLAB,
)

val issues = MorphValidator.validate(source, target, config)
```

`planMorph` and `MorphPlanner.plan` expose a reusable `MorphPlan`. Its report includes a
compatibility score, unmatched paths, warnings, and whether a fallback was used. Fallback modes
include automatic selection, best effort, crossfade, scale crossfade, draw reveal, and throwing
on structural incompatibility. `MorphConfiguration.debug` enables the renderer's geometry and
matching overlays for development.

## Custom geometry, clips, and boolean paths

`CustomVectorAnimation` runs once per rendered pair and can return viewport-space cubic contours,
stroke-only reveal contours, fill-only contours, brush overrides, alpha, width, and transforms.
Returning `strokeRevealContours` implements `DrawMode.CUSTOM`; returning `fillContours` implements
`DrawDirection.CUSTOM`. `MissingPathBehavior.CUSTOM` requires the callback to return geometry for
unmatched paths; a missing result throws with the affected pair index instead of silently choosing
a fallback.

```kotlin
val custom = VectorAnimation {
    path("outline") { strokeReveal(mode = DrawMode.CUSTOM) }
    customAnimation { path ->
        val shape = path.to ?: path.from
        val visible = shape?.contours?.mapIndexed { index, contour ->
            contour.sliceArcFraction(0f, path.drawProgress, shape.arcLengths[index])
        }
        CustomVectorPathOverrides(
            contours = shape?.contours,
            strokeRevealContours = visible,
            alpha = 1f,
        )
    }
}
```

Group `clipPathData` is retained during compilation. `clipReveal(...)` progressively opens the
inherited mask along a directional edge, and the renderer applies clips to both morphing and
static/fading paths. For custom masks and optional geometry boolean operations, convert normalized
contours with `BooleanGeometry.path(...)`, then use `union`, `intersection`, `difference`, or
`subtract` on Compose `Path`s. These operations run during preparation/callback work, not in the
normal geometry compiler.

## Reusable definitions and gradient angles

`VectorAnimationCodec` writes versioned bytes for scalar/brush timelines, including path targets,
keyframe/easing samples, draw settings, colors, and built-in linear/radial/sweep brushes. The
`ImageVector` stays app-owned and is supplied when reconstructing a compose definition:

```kotlin
val savedBytes = VectorAnimationCodec.encode(selectionAnimation)
val restored = VectorAnimationCodec.decode(savedBytes)
VectorAnimator(VectorAnimationDefinition(icon, restored), progress)
```

The codec samples each Compose `Easing` at 129 points. Custom callbacks and custom `Brush`
implementations are executable/runtime values and are rejected with a descriptive error. Linear
gradient angles can be declared with `linearGradientSpecAtAngle(...)`; angle-marked endpoints take
the shortest route through 360 degrees while preserving their center and interpolated axis length.

## Performance

`VectorCompiler` converts paths into cubic contours and precomputes bounds, centroids, winding,
and arc lengths. `MorphPlanner` matches paths, normalizes winding/start points, subdivides cubic
segments with exact de Casteljau splits, and stores packed geometry. `MorphPlanCache` and the
Compose entry points reuse compiled vectors/plans. The renderer owns reusable Compose paths and
coordinate buffers. Color and gradient interpolation creates new Compose brush values as progress
changes, so prefer geometry-only or static-paint tracks for very high-frequency icon swarms.
Keep `ImageVector`s and animation definitions stable (for example, top-level
or `remember` values) so Compose and the caches can reuse preparation work.

Plan construction is synchronous the first time a vector pair is seen. For large custom vectors,
precompile and plan them before the animation is displayed. The current renderer targets Android
Compose `DrawScope`; it is not a multiplatform renderer.

## Current boundaries

The engine preserves the data exposed by Compose `ImageVector`: viewport and intrinsic size,
static nested group transforms, paths, fill/stroke brushes and alpha, stroke geometry, path order,
and group clipping paths. Clip geometry switches between source and target at the midpoint of a
morph; it is not itself geometrically morphed. Boolean geometry functions operate on Compose
`Path`s and are opt-in. Custom callbacks run on the drawing thread and should be deterministic and
lightweight; precompute large contours outside the callback. Unknown custom brushes crossfade.
Different target viewport dimensions are scaled into source viewport space during planning. If
the viewport aspect ratios differ, that normalization stretches target geometry per axis; use
matching aspect ratios when that is not desired. Intrinsic layout dimensions remain caller
controlled.

## Tests

Core regression tests cover path command normalization, cubic subdivision, winding/start-point
alignment, vector compilation, path mapping and unmatched paths, timeline evaluation, color and
gradient interpolation, validator output, plan caching, animation serialization, and path-length
timing. A Compose instrumentation screenshot test checks fixed-progress pixels, determinism, and
semantics. Run the JVM and compile checks with:

```shell
./gradlew :vector-morph-core:testDebugUnitTest
./gradlew :vector-morph-compose:compileDebugKotlin
./gradlew :vector-morph-compose:compileDebugAndroidTestKotlin
```

Run the device-side screenshot test with `./gradlew :vector-morph-compose:connectedDebugAndroidTest`.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the full text.
