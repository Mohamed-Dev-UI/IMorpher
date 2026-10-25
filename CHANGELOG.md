# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
