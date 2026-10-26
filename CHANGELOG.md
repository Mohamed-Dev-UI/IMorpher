# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — Unreleased

Behavior and reference docs: [docs/CONFIGURATION.md](docs/CONFIGURATION.md),
[docs/MIGRATION.md](docs/MIGRATION.md) §8.

### Added
- `VectorAnimation.stableKey()`: content key over the whole timeline; recomposing
  compositions should key `remember`/`LaunchedEffect` on it — the compose auto-play
  entries already do, so inline-DSL one-shots rebuilt per recomposition no longer
  restart forever.
- `MorphPrecompiler.isPrepared(from, to, configuration)`: probe the caller's
  configuration with the same `planningKey()` `warm` used (2-argument calls keep
  working via the `Default` default).
- `MorphValidator.validateConfiguration(config)`: static audit of parameter conflicts
  (dead-wire knobs, cross-fallback conflicts, sweep-on-stroke notes) — no vectors
  compiled, never throws.
- `MorphDebugOptions.markerArcFraction` renders moving magenta pen-position rings along
  contour arcs (0/1 draw nothing; previously reserved).

### Fixed
- Staggered reveals are actually squeezed: stagger offsets compress the per-path draw
  windows instead of slipping past them, and a `MIN_STAGGER_WINDOW` floor keeps short
  paths visible under heavy stagger + `BY_PATH_LENGTH`.
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
  removal in 0.4) and the ignored-dot `customStartOffsets` alignment lane documented
  as interim until the 0.3 alignment home lands.
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
