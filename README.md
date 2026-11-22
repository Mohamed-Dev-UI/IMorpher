# ImageVector Morph

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mohamed-dev-ui/vector-morph-compose)](https://central.sonatype.com/artifact/io.github.mohamed-dev-ui/vector-morph-compose)
[![CI](https://github.com/Mohamed-dev-ui/IMorpher/actions/workflows/ci.yml/badge.svg)](https://github.com/Mohamed-dev-ui/IMorpher/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

An ImageVector-first vector morphing and drawing engine for Jetpack Compose. It compiles
`ImageVector` hierarchies once into normalized cubic geometry, plans path correspondence and
fallbacks before animation starts, then renders frames directly with Compose `DrawScope`.
It does not require hand-authored `AnimatedVectorDrawable` XML.

The repository is split into two Android library modules:

- `vector-morph-core`: vector compilation, cubic normalization, matching, morph plans,
  timelines, gradients, diagnostics, and caching.
- `vector-morph-compose`: composables and the `DrawScope` renderer.

## Installation

Add it through your version catalog and use it like any other library. IMorpher is
published to Maven Central:

```toml
[versions]
imorpher = "0.3.0"

[libraries]
imorpher-compose = { group = "io.github.mohamed-dev-ui", name = "vector-morph-compose", version.ref = "imorpher" }
```

```kotlin
dependencies {
    implementation(libs.imorpher.compose)
}
```

The Compose artifact targets Android API 23+ and depends transitively on
`vector-morph-core`; add the core artifact
(`io.github.mohamed-dev-ui:vector-morph-core`) explicitly only when you need the engine
types directly. Without a catalog:

```kotlin
dependencies {
    implementation("io.github.mohamed-dev-ui:vector-morph-compose:0.3.0")
}
```

`mavenCentral()` is already part of every standard Android Studio template. Enable the
Kotlin Compose compiler plugin in the consuming Android app module, as with any Compose
dependency. The latest release number is always visible on the
[Maven Central badge](https://central.sonatype.com/artifact/io.github.mohamed-dev-ui/vector-morph-compose).

## Quickstart

**State-driven morph** — the icon animates from its current progress whenever
`selected` flips, so rapid taps never jump:

```kotlin
MorphIcon(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    selected = selected,
    tintFrom = MaterialTheme.colorScheme.onSurfaceVariant,
    tintTo = MaterialTheme.colorScheme.primary,
    animationSpec = tween(350),
    contentDescription = "Home",
)
```

**Manual progress** — you own the value; both sides interpolate as one:

```kotlin
MorphVector(
    from = Icons.Outlined.Home,
    to = Icons.Filled.Home,
    progress = dragProgress.coerceIn(0f, 1f),
    configuration = MorphConfiguration(pathMatching = PathMatchingStrategy.BY_NAME),
)
```

**Chained targets** — always morphs from the last settled icon; a retarget mid-flight
restarts from that settled state, never from an intermediate frame:

```kotlin
MorphingIcon(
    target = currentIcon,
    contentDescription = "Status",
    motionPreference = MotionPreference.REDUCED,
)
```

**Content transitions** — when the endpoints have no matching geometry (a raster image, an
opaque `Painter`, or arbitrary composable content), composite them instead of morphing. The
same caller-owned progress drives painters, bitmaps, and slots:

```kotlin
MorphImage(
    from = startFrame,
    to = endFrame,
    progress = playbackProgress,
    transition = ContentTransition.Wipe(
        direction = WipeDirection.LEFT_TO_RIGHT,
        feather = 0.2f,
    ),
    contentScale = ContentScale.Crop,
)
```

Changing frames go through `FrameSource`/`MorphFrames` — the host pushes decoded frames into
two latest-wins buffers while the caller owns `progress`:

```kotlin
MorphFrames(
    from = leftDecoder,          // FrameSource fed with submit(frame)
    to = rightDecoder,
    progress = playbackProgress, // one caller-owned clock for both endpoints
    transition = ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT),
    width = 320.dp,
    height = 180.dp,
    contentScale = ContentScale.Crop,
)
```

Single-vector drawing (`DrawIcon`, `VectorAnimation`), externally controlled timelines
(`VectorAnimator`), and the full timeline DSL are covered in
[the DSL guide](docs/TIMELINE-DSL.md) and
[the configuration guide](docs/CONFIGURATION.md).

## What's new in 0.3.0

- `MorphShape` and `MorphPath` extend geometry morphing to Compose `Shape`s and explicit
  `Path`s, with exact rectangle/rounded-rectangle outlines, adaptive sampling, and
  per-endpoint `MorphPaint` gradient/stroke paint.
- Content transitions composite endpoints without interpolatable geometry:
  `MorphPainter`, `MorphImage`, `MorphContent`, and `MorphingContent` support crossfade,
  fade-over, directional wipes, and per-endpoint alpha/scale/translation/rotation.
  `FrameSource`/`MorphFrames` add host-owned, latest-wins frame buffers for video and
  animation playback.
- `contentScale` + `alignment` on every vector entry (`Fit` is the default and keeps the
  historical aspect-fit letterbox).
- `customStartAlignments` is the modern alignment home for `StartPointStrategy.CUSTOM`;
  deprecated `customStartOffsets` remains as the legacy fallback for one release.
- `MorphValidator.validateConfiguration` audits conflicting parameter combinations, and
  `VectorAnimation.stableKey()` keeps inline-DSL one-shots from restarting on
  recomposition.
- Fixes: staggered reveals are squeezed into their draw windows, `FillMode.LINEAR` and
  `DIRECTIONAL` now render differently, `strokeFill = MORPH` implements its documented
  choreography, and direction/timing/custom-anchor rules apply at every reveal site.

See [docs/MIGRATION.md](docs/MIGRATION.md) for 0.1 → 0.2 code changes and the 0.3.0
deprecation sweep.

## Feature matrix

| Area | What you get | Guide |
| --- | --- | --- |
| Composables | `MorphIcon`, `MorphVector`, `MorphingIcon`, `VectorAnimator`, `VectorAnimation`, `DrawIcon` — untinted, color-tint, and gradient-tint overloads; `contentScale` + `alignment` place the icon viewport in its box (`Fit` default) | [DSL guide](docs/TIMELINE-DSL.md) |
| Morph intelligence | 8 path-matching strategies, winding normalization, start-point alignment, viewport normalization, compatibility scoring, automatic fallbacks | [Configuration guide](docs/CONFIGURATION.md) |
| Drawing & reveal | Stroke reveals (forward/reverse/center-out/outside-in/radial), fill sweeps (linear/radial/angular/path traversal), clip reveals, stagger | [Configuration guide](docs/CONFIGURATION.md) |
| Paint animation | Color and gradient interpolation in `SRGB`/`LINEAR_SRGB`/`OKLAB`/`OKLCH`, gradient geometry + stops, stroke↔fill strategies | [DSL guide](docs/TIMELINE-DSL.md) |
| Timelines | Vector/path/group/all-path tracks, keyframes, spring easings, stagger, codec serialization | [DSL guide](docs/TIMELINE-DSL.md) |
| Custom geometry | Per-path override callbacks, custom draw/fill contours, boolean path ops | [Custom geometry](docs/CUSTOM-GEOMETRY.md) |
| Shape & path morphing | `MorphShape`, `MorphPath` — true geometry morphs for Compose shapes and explicit paths, with exact rectangle/rounded-rectangle outlines, adaptive path sampling, gradient fills, and strokes | [Shape & path guide](docs/SHAPE-PATH-MORPHING.md) |
| Content transitions | `MorphPainter`, `MorphImage`, `MorphFrames`, `MorphContent` — crossfade, fade-over, directional wipe, and per-endpoint alpha/scale/translation/rotation for opaque content and host-fed frames | [Content transitions](docs/CONTENT-TRANSITIONS.md) |
| Diagnostics | Compatibility reports, validation, debug overlays, plan cache control, precompilation | [Diagnostics guide](docs/DIAGNOSTICS.md) |

## Configuration overview

`MorphConfiguration` is the single tuning surface; the default (`MorphConfiguration.Default`)
plans and renders a sane geometry morph. Every field has a full entry in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md):

| Parameter | Default | Meaning |
| --- | --- | --- |
| `pathMatching` | `AUTO` | How source paths pair with target paths (index, name, id, geometry, area, centroid, custom). |
| `startPoint` | `AUTO` | How closed contours align start points before morphing. |
| `pathDirection` | `AUTO` | Contour winding normalization. |
| `drawOrder` | `BY_INDEX` | Render z-order only when `keepRenderOrder = false`; stagger timing comes from `staggerOrder`. |
| `customDrawOrder` | — | Explicit path order for `DrawOrderStrategy.CUSTOM`. |
| `missingPath` | `FADE` | Behavior for single-sided paths (fade/draw/scale/collapse/nearest/keep/custom). |
| `fallback` | `AUTO` | Strategy when a clean morph is impossible (crossfade variants, draw reveal, best effort, throw). |
| `timing` | `BY_PATH_LENGTH` | Constant-speed draw timing; alternatives map by command, per path, or uniform. |
| `colorSpace` | `SRGB` | Color/gradient interpolation space. |
| `strokeFill` | `AUTO` | Outline→filled transition strategy (morph, crossfade, draw-then-fill). |
| `animation` | `null` | Optional `VectorAnimation` timeline (see [DSL guide](docs/TIMELINE-DSL.md)). |
| `staggerDelay` / `staggerOrder` | `0f` / `TOP_LEFT_TO_BOTTOM_RIGHT` | Vector-level stagger across paths. |
| `customMappings` | `[]` | Explicit `PathMapping(source, target)` pairs. |
| `customDrawAnchors` | `{}` | Per-path normalized arc fraction (0–1) driving `PathStart.CUSTOM` pen anchoring. |
| `defaultFillMode` / `defaultFillDirection` | `DIRECTIONAL` / `TOP_RIGHT_TO_BOTTOM_LEFT` | Defaults for fill reveals without their own settings. |
| `keepRenderOrder` | `true` | Preserve authored z-order while strategies reorder reveal timing. |
| `directionTraversal` | `HONOR_DIRECTION` | Whether directional stroke reveals travel along their requested axis on open contours. |
| `segmentBudget` / `simplificationTolerance` | `null` / `0.25f` | Cap cubic segments per contour pair; tolerance bounds detail loss. |
| `viewportNormalization` | `STRETCH` | `STRETCH` scales target axes independently; `FIT` letterboxes uniformly. |
| `debug` | `null` | `MorphDebugOptions` overlay set for development (see [diagnostics](docs/DIAGNOSTICS.md)). |
| `customStartOffsets` | `{}` | **Deprecated** — legacy alignment fallback under `StartPointStrategy.CUSTOM`; the modern home is `customStartAlignments` (new map wins per path). Also the draw-anchor fallback for `PathStart.CUSTOM` when `customDrawAnchors` has no entry. |
| `customStartAlignments` | `{}` | Per-path 0..1 arc-fraction start overrides for `StartPointStrategy.CUSTOM` geometry alignment; never anchors draws. |

## Performance

- Iterative planning results are cached against a content hash (`contentKey`), so
  equal-but-distinct `ImageVector` instances share one plan; the cache enforces an entry
  cap and a byte budget (16 MiB by default). Call `MorphCaches.trim(bytes)` from
  `onTrimMemory`.
- Path and contour matching solve exactly (Hungarian algorithm) for up to 32 candidates.
- `segmentBudget` plus `simplificationTolerance` bound per-frame interpolation cost.
- Tinting adds one offscreen layer per frame; the untinted overloads avoid it.
- Plan construction is synchronous on first use for a pair — warm big vectors on
  `Dispatchers.Default` with `MorphPrecompiler.warm(...)`.

Details and numbers live in [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

## Troubleshooting

- **My gradient crossfades instead of interpolating** — only built-in Compose
  linear/radial/sweep brushes of the same kind interpolate; custom `Brush` subclasses and
  mismatched kinds crossfade by design. R8 keeps the fields `BrushSpec.from` reads
  reflectively via the artifact's consumer rules; keep R8 minification enabled and
  update any local ProGuard overrides instead of dropping them.
- **The icon doesn't react to clicks** — drawing surfaces never consume pointer input;
  put `clickable` on the surrounding composable every time. A supplied
  `contentDescription` is published as Compose semantics.
- **Animations follow the system accessibility setting** — leave `motionPreference`
  null to resolve `MotionPreference` from the system animator-duration scale
  ("Remove animations" is honored automatically); an explicit value always wins.
- **Severe frame jank on the first frame of a new pair** — that is planning cost, not
  rendering. Warm the pair ahead of time or trim the geometry budget, as above.
- **Path matching pairs the wrong paths** — supply `customMappings`, or switch
  `pathMatching` to `BY_NAME` (stable names survive any declaration-order change).

## Showcase app

The `app` module is a runnable tour: every configuration parameter has a control in the
shared playground panel, and dedicated screens cover `MorphingIcon`, fill reveals,
gradient endpoints, plan diagnostics, precompile/cache, codec round-trips, and custom
geometry. A shape/path paint playground exercises `MorphShape` and `MorphPath` with
linear/radial/sweep/solid fills, endpoint colors, opacity, and stroke width/cap/join.
Open the project in Android Studio and run the `app` configuration — a tour of the
screens is in [docs/SHOWCASE-APP.md](docs/SHOWCASE-APP.md).

## Documentation

| Document | Purpose |
| --- | --- |
| [docs/README.md](docs/README.md) | Index of all guides. |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Every parameter and enum; the start/direction precedence contract, the fill-mode rendering map, the `directionTraversal` site list, and parameter quick-links (each row links the dead-wire warning it can trip). |
| [docs/TIMELINE-DSL.md](docs/TIMELINE-DSL.md) | Building timelines, reveals, gradients, springs, codec. |
| [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md) | Validation (`validate` + `validateConfiguration` categories), reports, debug overlays, cache and precompiler. |
| [docs/CUSTOM-GEOMETRY.md](docs/CUSTOM-GEOMETRY.md) | Override callbacks, custom contours, clips and boolean operations. |
| [docs/SHAPE-PATH-MORPHING.md](docs/SHAPE-PATH-MORPHING.md) | `MorphShape`/`MorphPath`: exact outlines, path sampling, viewport rules, gradient/stroke paint, and fallback reporting. |
| [docs/MIGRATION.md](docs/MIGRATION.md) | 0.1 → 0.2 changes plus the 0.3 deprecation sweep (`BY_POSITION`, `MorphIcon.animation`, `rememberVectorAnimation(definition)`). |
| [docs/SHOWCASE-APP.md](docs/SHOWCASE-APP.md) | Tour of the demo application. |

Parameter quick-links: `customDrawAnchors` needs a `PathStart.CUSTOM` draw track,
`customDrawOrder` needs `drawOrder = CUSTOM`, `customMappings` apply under
`AUTO`/`CUSTOM` matching, and `missingPath` is ignored under crossfade fallbacks —
`MorphValidator.validateConfiguration(config)` warns about each combo before any icon
renders (see [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)).

API reference HTML is generated with Dokka (`./gradlew dokkaGenerate` → `build/dokka`)
and attached to release uploads by the publish workflow.

## Contributing

- **Tests are the spec's teeth.** JVM suites cover geometry, interpolation, matching,
  planning, animation, the codec, and Robolectric pixel tests that pin every rendered
  behavior named in the guides:
  ```shell
  ./gradlew :vector-morph-core:testDebugUnitTest
  ./gradlew :vector-morph-compose:testDebugUnitTest
  ./gradlew :app:testDebugUnitTest
  ```
- Device-side instrumented tests (screenshot rendering, showcase UI):
  ```shell
  ./gradlew :vector-morph-compose:connectedDebugAndroidTest :app:connectedDebugAndroidTest
  ```
- Keep `ImageVector`s and animation definitions stable (top-level or `remember`) so
  caches can reuse preparation work.
- Custom callbacks run on the drawing thread: deterministic and lightweight.
- Plan and design documents live under `docs/superpowers/`; consult them before
  proposing changes that touch contracts documented here.

## Releases

1. Bump `VERSION_NAME` in `gradle.properties` and update `CHANGELOG.md`.
2. Tag the commit (`git tag v<version> && git push origin v<version>`) — GitHub Actions
   publishes both artifacts to Maven Central automatically.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the
full text.
