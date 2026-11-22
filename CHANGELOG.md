# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-11-22

Behavior and reference docs: [docs/CONFIGURATION.md](docs/CONFIGURATION.md),
[docs/MIGRATION.md](docs/MIGRATION.md) §8.

### Added
- Shape and path geometry adapters: `MorphShape` morphs Compose `Shape` outlines and `MorphPath`
  morphs explicit `Path`s through the same matching/interpolation pipeline as `ImageVector`s.
  Rectangle and rounded-rectangle outlines (including `CircleShape`) are emitted exactly; generic
  outlines and paths are sampled adaptively while preserving contour direction, holes, and
  open/closed state. Geometry re-resolves on shape/size/direction/density changes, and empty or
  unmeasurable endpoints report and use the planner's structural fallback. `MorphPaint` adds
  per-endpoint gradient/brush fills, strokes, alpha, and dp stroke widths with the same
  interpolation and crossfade fallbacks as vector paint; the original solid-color overloads
  remain and delegate to it. `PathGeometry` exposes the conversion and prepared-vector compilation
  for preflight and tooling. See [docs/SHAPE-PATH-MORPHING.md](docs/SHAPE-PATH-MORPHING.md).
- Shape & Path paint playground in the `:app` showcase: switch between Compose `Shape`
  and authored `Path` endpoints while tuning linear/radial/sweep/solid fills, per-endpoint
  colors, fill/stroke opacity, dp stroke width, cap, join, and render size. See
  [docs/SHOWCASE-APP.md](docs/SHOWCASE-APP.md).
- Content transitions for endpoints without interpolatable geometry: `MorphPainter`,
  `MorphImage`, and `MorphContent` composite opaque painters, raster images, or composable
  slots over the shared caller-owned progress contract. `ContentTransition.Crossfade`,
  `FadeOver`, and `Wipe` (four cardinal directions, `0..0.5` feather) plus per-endpoint
  `ContentEffect.Fade`/`Scale`/`Translate`/`Rotate` are pure data in `vector-morph-core`
  (`ContentTransitionResolver`), so one definition renders consistently across source
  families. Effects are draw-only (no layout or hit-testing changes), both slots stay
  composed, and endpoints that are fully transparent or unrevealed are hidden from
  accessibility. See [docs/CONTENT-TRANSITIONS.md](docs/CONTENT-TRANSITIONS.md).
- `VectorAnimation.stableKey()`: content key over the whole timeline; recomposing
  compositions should key `remember`/`LaunchedEffect` on it — the compose auto-play
  entries already do, so inline-DSL one-shots rebuilt per recomposition no longer
  restart forever.
- `MorphingContent`: the state-driven autoplay/retarget wrapper for composable content,
  with the `MorphingIcon` contract applied to `MorphContent` endpoints — the first
  composition renders the target slot directly (no intro animation), changes morph from
  the last settled slot content under a caller-chosen progress spec, mid-flight retargets
  restart from the last settled content, and motion preferences control the progress
  clock while `CROSSFADE_ONLY` resolves the visuals to a crossfade.
- `customStartAlignments`: the modern home for the deprecated `customStartOffsets`
  alignment role. `StartPointStrategy.CUSTOM` geometry alignment reads the new map first
  and the deprecated map keeps serving as its legacy fallback (one release window), so
  moving entries one at a time preserves pixels. The flag never anchors `PathStart.CUSTOM`
  draws (that remains `customDrawAnchors`' job). Validation reports
  `custom-start-alignments-unused`, `custom-start-alignment-invalid`, and
  `custom-start-alignment-conflict` (both maps disagreeing for one path). The new map is
  part of the planning key.
- `MorphPrecompiler.isPrepared(from, to, configuration)`: probe the caller's
  configuration with the same `planningKey()` `warm` used (2-argument calls keep
  working via the `Default` default).
- Host-owned frame sources: `FrameSource` is a latest-wins, single-slot frame buffer
  (`submit`/`markUnavailable`/`markEnded`, `READY`/`UNAVAILABLE`/`ENDED` status, and
  submitted/presented/dropped counters) and `MorphFrames` runs the shared content
  transitions over two such sources. The host keeps decoding, playback, timestamps, and
  disposal; progress stays caller-owned (`presentationTimeMillis` is alignment metadata,
  never a second clock), backpressure is one pending frame per source, and an
  unavailable endpoint composites as absent instead of throwing. See
  [docs/CONTENT-TRANSITIONS.md](docs/CONTENT-TRANSITIONS.md).
- `ContentScale` on every vector entry: `MorphVector`, `MorphIcon`, `MorphingIcon`,
  `VectorAnimation`, `VectorAnimator`, `DrawIcon`, `MorphShape`, and `MorphPath` resolve
  their viewport inside the layout box with Compose's own `ContentScale` contract plus
  `alignment` placement. `Fit` (the default) reproduces the previous aspect-fit letterbox
  exactly; `Crop`/`FillBounds`/`FillWidth`/`FillHeight`/`None`, `Inside`, and custom
  implementations follow Compose semantics.
- `MorphValidator.validateConfiguration(config)`: static audit of parameter conflicts
  (dead-wire knobs, cross-fallback conflicts, sweep-on-stroke notes) — no vectors
  compiled, never throws.
- Custom selector diagnostics: `validateConfiguration` reports blank and duplicate
  entries in active `customMappings`/`customDrawOrder` lists, and planning reports
  unresolved mapping endpoints and unknown order names as structured plan issues.
  Resolution behavior is unchanged (first mapping/target wins, last duplicate order
  rank, omitted names stable). See [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).
- `MorphDebugOptions.markerArcFraction` renders moving magenta pen-position rings along
  contour arcs (0/1 draw nothing; previously reserved).

### Fixed
- Staggered reveals are actually squeezed: stagger offsets compress the per-path draw
  windows instead of slipping past them, and a `MIN_STAGGER_WINDOW` floor keeps short
  paths visible under heavy stagger + `BY_PATH_LENGTH`.
- The `:benchmark` module now instruments through `androidx.benchmark.junit4.AndroidBenchmarkRunner`
  (IsolationActivity isolation) with an explicit `EMULATOR` suppression, so
  `connectedBenchmarkAndroidTest` can actually run on emulator/CI configurations; measured
  budgets are recorded in `docs/BENCHMARKS.md`.
- `DrawOrderStrategy.BY_NAME` no longer consults `customDrawOrder` (the list is
  reserved for `CUSTOM`), keeping the two strategies distinguishable.
- `strokeFill = MORPH` applies its documented stroke-shrink-fill-grow transition
  (`AUTO` resolves today's behavior exactly, pixel-stable).
- `FillMode.LINEAR` is the hard wipe (instant behind-the-edge reveal) and
  `DIRECTIONAL` is the feathered sweep (DstIn alpha ramp across the edge band) — the
  two modes rendered identically before.
- One `SWEEP` start angle per direction at both fill sites (clip mask + fill painting
  previously disagreed).
- `directionTraversal`, per-path timing, and custom draw anchors now also apply at the
  missing-side `DRAW` reveal, custom-geometry contours, and `PATH_TRAVERSAL` fill sites
  (previously stroke-pair-only).
- Crossfade fallbacks that discard a meaningful `missingPath` record the conflict on
  the plan (`FallbackStrategy <strategy> crossfades every pair; missingPath <behavior>
  is ignored`) instead of silently dropping it.

### Changed
- **Planning key split.** The plan cache keys on a `planningKey()` — the planning-field
  subset of `MorphConfiguration` — so render-only changes (debug, timing, stagger,
  color space, draw order…) reuse the cached plan instead of re-planning
  (`MorphPrecompiler.warm`/`isPrepared` follow the same key; the embedded timeline no
  longer routes through a configuration fallback).
- Deprecated `DrawOrderStrategy.BY_POSITION` (alias of `TOP_LEFT_TO_BOTTOM_RIGHT`;
  removal in 0.4) and moved the `customStartOffsets` alignment role to
  `customStartAlignments`; the deprecated map remains the legacy fallback for one release.
- Deprecated `MorphIcon`'s `animation` parameter (set `configuration.animation`) and
  the ignored `rememberVectorAnimation(definition)` overload.

## [0.2.0] — 2026-10-24

### Added
- `MorphingIcon` composable: chained morphing to a changing `target` from the last
  settled icon; mid-flight retargets restart from that settled state.
- Typed reveal lookups on `VectorAnimation`: `strokeRevealFor(...)` and
  `fillRevealFor(...)` replace the untyped `drawConfigFor` shim (deprecated).
- `MorphConfiguration.viewportNormalization`: `STRETCH` (historical per-axis scaling)
  or `FIT` (uniform scale, letterboxed center).
- `MorphConfiguration.segmentBudget` and `simplificationTolerance`: cap cubic
  segments per contour pair with a bounded least-squares reduction.
- `MorphConfiguration.directionTraversal`: `HONOR_DIRECTION` (new default) travels
  directional stroke reveals toward their requested axis; `LEGACY` keeps the
  historical contour-order traversal.
- Aligned group clip masks morph with the geometry; one-sided or mismatched masks
  switch at the midpoint.
- Draw anchors split out of the alignment map: `MorphConfiguration.customDrawAnchors`
  drives `PathStart.CUSTOM` pen anchoring (preview shows per-path arc fractions).
- Wire format v2 in `VectorAnimationCodec` with typed reveal records; v1 payloads
  keep decoding, so cached definitions remain compatible.

### Changed
- `PathStart.AUTO` is the derived-anchor sentinel (derives the anchor from the
  direction, geometric top-left when the direction is `AUTO`); `PathStart.START` is a
  literal anchor at arc fraction 0 of the normalized contour.
- `MorphConfiguration.directionTraversal` default flipped to `HONOR_DIRECTION`
  (from `LEGACY`). Restore the historical behavior explicitly when needed.
- Reveal configs are typed per property: stroke reveals carry mode/start/direction,
  fills carry mode/direction, clip reveals carry direction only.
- `customStartOffsets` deprecated: use `customDrawAnchors` for pen anchors and
  `StartPointStrategy.CUSTOM` correspondences for alignment offsets; the legacy map
  remains honored as a fallback for one release.

### Performance
- Plan cache enforces an entry cap and byte budget; trim via `MorphCaches.trim`.
- Tint layers use content bounds plus a stroke margin, and solid tints apply a color
  filter instead of a full-canvas layer.

## [0.1.0] — Initial public release

- Core engine: `ImageVector` compilation to normalized cubic geometry, path matching
  (index, name, id, geometry, area, centroid, custom), winding/start-point alignment,
  subdivision, morph plans with compatibility reports, and plan caching.
- Compose rendering: `MorphIcon`, `MorphVector`, `VectorAnimator`, `VectorAnimation`,
  `DrawIcon` with untinted/color/brush overloads, stroke reveals, fill reveals,
  stagger, keyframe timelines, spring easings, and the binary animation codec.
- Diagnostics: `MorphValidator`, `MorphPlan.report`, debug overlays, and a
  process-wide plan cache with memory trimming.
