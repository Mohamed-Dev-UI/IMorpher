# IMorpher Goals

## Purpose

Define the work needed to grow IMorpher from an `ImageVector` morphing engine into a comprehensive Android Jetpack Compose library for morphing and transitioning vector geometry, painters, raster images, video frames, and composable content—without implying that every kind of content can be geometrically interpolated.

This is a goals and acceptance-criteria document, not a claim that the listed capabilities are already implemented. The current project is Android-only and has separate `vector-morph-core` and `vector-morph-compose` modules. Keep new work compatible with that structure unless a later design demonstrates a concrete need for another module.

## Product principles

1. **Use the strongest valid morph for the source.** Interpolate geometry when both endpoints expose compatible geometry. For opaque pixels or opaque composable content, offer well-defined visual transitions instead of pretending to interpolate hidden structure.
2. **One progress clock.** All endpoint types, effects, and timelines can be driven by the same normalized progress, either by the caller or by Compose animation APIs. Retargeting, interruption, seeking, and reduced-motion behavior must be explicit and consistent.
3. **No silent no-ops or overrides.** A public option must affect rendering in a documented context, be reported as ineffective, or be a documented alias/deprecated API. Conflicting options need one deterministic precedence contract.
4. **Safe by default.** Bound memory and per-frame work, keep decoding and expensive preparation off the UI thread, preserve accessibility semantics, and provide an explicit fallback when a requested morph cannot be honored.
5. **Composable and incremental.** Existing `ImageVector` use remains first-class. New input families are added through capability-focused adapters and APIs, not by forcing unrelated content into vector-specific configuration.

## Vocabulary and guarantees

- **Geometry morph:** Interpolate corresponding vector/path geometry and its supported paint properties. This requires inspectable geometry on both endpoints.
- **Visual transition:** Composite two rendered endpoints over time using effects such as crossfade, wipe, clip, mask, scale, or translation. It does not claim to interpolate their internal geometry.
- **Content endpoint:** A typed input plus the capabilities it can expose (for example, paths, a painter, a bitmap/frame, or a composable slot).
- **Fallback:** The documented transition used when geometry or an effect is unsupported, incompatible, unavailable, or too expensive. The caller can inspect the resolved choice and warnings.

A single API may offer both geometry morph and visual-transition modes, but must clearly identify which mode was selected and why.

## Current baseline

The following is the starting point for the roadmap, based on the current source and docs:

- `vector-morph-core` compiles Compose `ImageVector` hierarchies into prepared, normalized cubic geometry. It retains viewport and path/group information, bakes static group transforms, captures inherited group clips, and precomputes path features.
- The planner matches paths and contours, aligns starts and winding, normalizes viewports, produces compatibility/fallback reports, and caches plans.
- `vector-morph-compose` draws planned vectors with Compose `DrawScope`; public entry points include `MorphIcon`, `MorphVector`, `MorphingIcon`, `MorphShape`, `MorphPath`, `MorphPainter`, `MorphImage`, `MorphContent`, `MorphingContent`, `MorphFrames`, `VectorAnimation`, `VectorAnimator`, and `DrawIcon`.
- Existing vector behavior includes path/group/vector timelines, transforms, alpha, stroke width, fills and strokes, built-in gradient interpolation, reveals, clip masks, missing-path handling, custom-geometry callbacks, tinting, motion preferences, diagnostics, and a versioned animation codec.
- Built-in solid/linear/radial/sweep brushes are parameterized where supported. Unknown/custom brushes and incompatible gradient kinds use fallback rendering rather than general parameter interpolation.
- Vector geometry morphing uses `ImageVector` as the reference input; Compose `Shape` and explicit `Path` endpoints are now adapted through `MorphShape`/`MorphPath` and core `PathGeometry`. Rectangle and rounded-rectangle outlines (including `CircleShape`) are exact, generic outlines and paths are sampled with contour direction, holes, and open/closed state preserved, and empty geometry defers to the planner's reported fallback. Both adapters accept per-endpoint `MorphPaint` fills and strokes, including built-in gradient brushes, alphas, and dp stroke widths; solid-color overloads remain as a convenience. Paint interpolation reuses the vector brush pipeline, including its crossfade fallback for incompatible or custom brushes. A visual-transition layer now accepts opaque `Painter`, `ImageBitmap`, and arbitrary composable slots through `MorphPainter`, `MorphImage`, and `MorphContent`: crossfade, fade-over, cardinal directional wipes, and per-endpoint alpha/scale/translation/rotation effects over the shared progress contract. It composites rendered endpoints and never interpolates their internal geometry. Host-owned frame sources are supported through `FrameSource` — a latest-wins, single-slot buffer with `READY`/`UNAVAILABLE`/`ENDED` status and submit/presentation/drop counters — and `MorphFrames`, which runs the shared content transitions over two such sources while the host keeps owning decoding, playback, timestamps, and disposal (see `docs/CONTENT-TRANSITIONS.md`).
- Vector and content rendering both expose Compose's `ContentScale` (`Fit` is the default and reproduces the historical aspect-fit letterbox; `Crop`, `FillBounds`, `FillWidth`, `FillHeight`, `None`, `Inside`, and custom values follow Compose's contract) with `alignment` placement: vector entries map the plan's viewport into the layout box, painter/image/frame endpoints map their source into it. Compose Multiplatform is not a target; this roadmap targets Android Compose and does not commit to KMP.
- `MorphValidator.validateConfiguration` already checks a focused set of configuration/timeline conflicts. It is a foundation, not a guarantee that every parameter, source capability, and interaction has been exhaustively audited.

Before implementation, recheck these baseline statements against the code at that time; update this document if behavior has moved.

## Capability roadmap

| Input family | Target behavior | What “morph” means here | Required fallback/limits |
| --- | --- | --- | --- |
| `ImageVector` | Keep as the reference-quality path; complete edge-case, paint, transform, diagnostic, and performance coverage. | True geometry and supported paint interpolation using the existing planner/renderer. | Per-pair reported fallback for unsupported or incompatible geometry/paint. |
| Vector-capable `Painter` / explicit path geometry | Add adapters for inputs that can expose stable paths, viewport, paints, and clipping. Provide a public, explicit geometry adapter where automatic extraction is impossible. | Route into the same prepared geometry/planning pipeline when the adapter guarantees equivalent data. | Opaque painters use the painter/image transition route; do not introspect arbitrary painter internals. |
| Compose `Shape` / `Path` | Support shapes that can produce an outline at a known size and explicit paths, with documented size/coordinate normalization. | Geometry morph when both outlines can be sampled or normalized as compatible contours. | Report open/unsupported outline types and use a selected visual transition. Re-evaluate geometry when size or layout direction changes. |
| Raster images (`ImageBitmap`, image-backed painters) | Offer endpoint tint/alpha and visual transitions: crossfade, transform, wipe/clip, and explicit masks where supported. Preserve image sampling, alpha, aspect ratio, and caller-selected bounds. | Pixel compositing, not automatic semantic shape or feature correspondence. | Never decode large images synchronously during composition/drawing. Bound temporary render targets; define behavior for differing dimensions, color spaces, and unavailable content. |
| Video / animated frame streams | Allow host-provided frame sources to participate in time-synchronized visual transitions and shared effects. Keep frame production and codec ownership outside the core engine initially. | Composite current frames; optional explicit masks/effects. Not per-pixel temporal geometry inference. | No implicit Media3/codec dependency in the core library. Define frame ownership, timestamp/progress synchronization, backpressure, dropped-frame behavior, pause/disposal, and memory limits. If frame access is unavailable, use a host-composable transition or report the limitation. |
| Arbitrary composable content | Add slot-based content-to-content transitions driven by the shared progress/effect model, with semantics and lifecycle contracts. | Visual transition between two independently composed/rendered slots; no automatic structural diff or path extraction. | Document composition duplication and state/lifecycle behavior while both endpoints are present. Provide explicit geometry adapters for callers who need true shape morphing. Snapshot/capture-based morphing is optional research, not a prerequisite for useful support. |
| Text and other draw/layout content | Make content eligible for slot-based effects; allow opt-in geometry only where a caller provides stable, explicit outlines. | Visual transition by default; explicit glyph/path geometry only under a documented adapter contract. | Do not promise glyph correspondence, font-independent results, or invisible layout-tree introspection. |

### Shared transition and effect suite

Create one consistent effect vocabulary that can be used across compatible source families, with capabilities restricting what actually applies:

- Progress-driven crossfade, through/over compositing, clip/wipe, directional reveal, mask reveal, scale, rotation, translation, and alpha.
- Clear rules for content bounds, alignment, aspect handling, clipping, z-order, blend/compositing, and endpoint paint/tint interaction.
- Reusable effect/timeline composition where effects can be combined. Define order of operations; reject, warn, or explicitly resolve combinations that cannot commute or cannot apply to a source type.
- A stable caller-owned progress API plus Compose-driven one-shot, state-driven, retargetable, and externally controlled playback where appropriate.
- Predictable behavior at progress 0 and 1: each endpoint reproduces its documented output, except for explicitly configured tint/transform/crop effects.
- Reduced-motion and instant modes across every source family; no new API bypasses the existing accessibility intent.

## Parameter and feature-interaction goals

### 1. Establish an authoritative interaction inventory

Maintain a parameter matrix covering every public configuration field, DSL property, source option, and effect. For each entry record:

- Owning layer: planning, timeline/evaluation, rendering, layout, accessibility, or source lifecycle.
- Inputs and source capabilities required for the option to apply.
- Default, valid domain, units, and behavior at boundary values.
- Other options it composes with, overrides, or is overridden by.
- Render sites and source families where it applies—and where it intentionally does not.
- Diagnostics, tests, and documentation links proving the contract.

Keep the matrix near the configuration/API reference and update it in the same change as each public behavior change.

### 2. Preserve and extend known precedence contracts

The following current contracts must remain explicit and testable; changes require deliberate API/migration decisions:

- `drawOrder` controls rendered z-order only when `keepRenderOrder = false`; `staggerOrder` controls stagger ranking. `customDrawOrder` is meaningful only for `CUSTOM` ordering.
- Configuration-level stagger fields and timeline-level stagger have documented resolution rules; a configuration must not appear to cancel a timeline setting if it cannot.
- `missingPath` behavior is not presented as effective under a fallback that crossfades all pairs; the resolved fallback and warning remain discoverable.
- `customDrawAnchors` anchors the reveal pen; `StartPointStrategy.CUSTOM` controls geometry correspondence. The legacy `customStartOffsets` overlap has a planned migration/removal home (0.3: `customStartAlignments` owns alignment first, the deprecated map is its per-path legacy fallback and the `PathStart.CUSTOM` anchor fallback; removal rides the 0.4 deprecation window), so both meanings no longer ambiguously co-own one map.
- A reveal direction or mode that has no meaning for the selected fill/stroke effect is reported, or is removed/renamed through a compatible deprecation path.
- Timeline tracks, configuration defaults, endpoint properties, Compose modifiers, tinting, and source-specific effects each need a documented winner/combination rule. A caller must be able to tell whether a value affects geometry, paint, a wrapper layer, or layout.
- Motion preference may deliberately resolve to a different fallback, but that decision and any planning/cache consequence must be visible and tested.

### 3. Make diagnostics capability-aware and exhaustive

Extend configuration validation and plan/source diagnostics so they can report:

- A configured value that has no consumer in the selected source/effect/mode.
- An override, shadowed value, incompatible combination, unsupported source capability, or implicit fallback.
- Empty, malformed, out-of-range, duplicate, or conflicting mappings/orders/intervals where relevant.
- Per-pair or per-endpoint resolution so one incompatible path does not hide the state of the rest of the morph.

Use stable categories and severity. Validation should be pure and safe for preflight use; runtime planning should report actual resolved behavior. Errors should be reserved for cases where rendering cannot produce a valid result, while recoverable cases explain the chosen fallback. The default configuration should remain free of spurious warnings.

### 4. Prevent duplicate or ineffective public knobs

- Give each concept one canonical API home. If both a convenience parameter and configuration field must coexist, specify precedence, warn/deprecate the shadowing home, and test compatibility.
- Avoid exposing knobs that are only consumed by one input family without saying so. Prefer typed source/effect options over a flat configuration that accumulates irrelevant properties.
- If two enum values or effects are intentionally equivalent, make the alias explicit and deprecate the duplicate; otherwise prove their distinct output with a behavioral test.
- Keep render-only and planning-only inputs separated for cache keys and recomposition. Any input that changes the plan must be part of the planning key; render-only inputs must not cause unnecessary re-planning.

## Implementation phases and exit criteria

### Phase 0 — Capability and API contract

- Re-audit the current main-source behavior and write the authoritative parameter/source/effect matrix.
- Inventory public parameters and enumerate pairwise conflicts, aliases, overrides, ignored combinations, and source-specific behavior.
- Define common terminology, endpoint capability reporting, fallback reporting, and the shared progress contract without prematurely committing to a concrete public type layout.

**Exit:** Every existing public knob maps to a real consumer or a documented migration; known cases have focused regression coverage; the roadmap’s current-state statements match code and docs.

### Phase 1 — Parameter effectiveness and diagnostics

- Close any remaining gaps in parameter precedence, validator coverage, runtime warnings, and cache invalidation.
- Add table-driven tests for every public parameter and tests for each documented pairwise conflict/override.
- Verify aliases and deprecations rather than silently retaining duplicate behaviors.

**Exit:** No known accepted option silently does nothing in its documented context; intentional no-ops are statically diagnosed; output differences are pinned where two modes promise different rendering.

### Phase 2 — Complete the vector geometry path

- Treat `ImageVector` as the reference implementation while expanding explicit shape/path/vector-painter adapters where geometry is genuinely available.
- Cover open/closed and degenerate contours, multi-contour and hole/fill-rule behavior, clipping, transforms, differing viewports/sizes, mismatched path/contour counts, empty inputs, winding/start alignment, and geometry simplification.
- Define which group/path paint properties interpolate, which crossfade, and which are unsupported; surface the decision in reports.
- Complete timeline/property conflict coverage for geometry, fill/stroke paint, tint, transform, clipping, reveal, and missing paths.

**Exit:** Geometry adapters have deterministic coordinate/size semantics; the established vector suite remains pixel- and behavior-tested; every unsupported vector feature has an explicit fallback and diagnostic.

### Phase 3 — Raster image and opaque painter transitions

- Add image/painter endpoints for visual transitions independent of vector compilation.
- Implement and test the shared visual effects that are meaningful for raster endpoints, including alpha, crossfade, transform, clip/wipe, and explicit masks.
- Specify image bounds, sampling, aspect, color/alpha, tint, and memory/lifecycle behavior; keep image decoding and loading under host control.

**Exit:** Raster endpoints render correctly at start, midpoint, and end; no frame performs unbounded allocation or synchronous image decoding; unsupported geometric morph requests resolve visibly and diagnostically.

### Phase 4 — Composable content transitions

- Add a slot/content API using shared progress and visual effects.
- Specify endpoint composition, state retention/disposal, z-order, input/pointer ownership, semantics/content descriptions, layout size changes, and interruption/retarget behavior.
- Keep structural or geometry morphing opt-in through explicit adapters; do not infer a composable’s internal tree.

**Exit:** Two arbitrary composable slots can transition with correct lifecycle, accessibility, and progress behavior; documented limits make clear that this is not automatic tree morphing.

### Phase 5 — Video and changing frame sources

- Define a host-owned frame-source boundary that does not force a particular decoder/player dependency on core consumers.
- Synchronize frame updates with the transition clock without making video playback speed an accidental alias for transition progress.
- Specify timestamp alignment, buffering/backpressure, stale/dropped frames, paused/ended sources, disposal, and protected/unavailable frames.
- Reuse raster compositing/effects where possible; only add video-specific code when a validated requirement cannot be served at the frame-source boundary.

**Exit:** Live frames from two host-provided sources can participate in supported effects without blocking the UI, leaking frame resources, or confusing playback control with morph progress; failure/unavailable-frame behavior is documented and tested.

### Phase 6 — Performance, accessibility, compatibility, and release readiness

- Benchmark planning, vector rendering, raster compositing, layered composables, and live-frame transitions separately on representative Android devices/configurations. *(First measured budgets are recorded in `docs/BENCHMARKS.md` — emulator configuration; physical-device re-measurement still required before closing this line.)*
- Bound caches, bitmap/layer sizes, temporary buffers, and per-frame work; document memory-pressure and cleanup behavior.
- Verify reduced-motion, instant transitions, content semantics, and deterministic endpoints for every new input family.
- Update API docs, migration notes, samples/showcase, and compatibility tests in the same release as new public APIs.

**Exit:** Measured performance and memory budgets are recorded (not inferred); release tests cover source families, parameter interactions, accessibility, lifecycle, and binary/source compatibility; no unsupported source is described as fully morphed.

## Cross-cutting verification requirements

- **Behavior, not only compilation:** For each option claimed to alter output, render controlled inputs at start, intermediate progress, and end; compare pixels or source/plan results as appropriate. Distinct modes must be observably distinct under fixtures that expose their difference.
- **Parameter matrix coverage:** A generated or table-driven test inventory links every public parameter to a consumer, source capability, and test. Add pairwise tests for every precedence rule and a default-configuration silence test for diagnostics.
- **Fallback coverage:** Exercise unsupported brush kinds, mismatched geometry, absent paths, missing/invalid image frames, unavailable video frames, and content-size changes. Assert both the visible fallback and its report/diagnostic.
- **Compose behavior:** Check recomposition keys, stable animation definitions, seeking, retargeting/interruption, callback counts, layout changes, and lifecycle/disposal without re-planning on render-only state changes.
- **Resource behavior:** Test bounded allocation/cache behavior and cleanup for image/frame/layer sources; do not claim performance wins without benchmark measurements.
- **Accessibility:** Verify semantics/content descriptions remain available and motion settings apply consistently to each new API family.
- **Compatibility:** Preserve the existing animation codec’s supported decoding contract. Any wire/API change needs an explicit versioning and migration decision.

## Explicit non-goals for this roadmap

- Compose Multiplatform targets; this roadmap is Android Jetpack Compose only.
- Automatic structural diffing or reflection/introspection of arbitrary composable trees.
- A guarantee that raster images or video can be converted into semantically correct vector geometry.
- A required built-in decoder, player, network loader, or image-loading SDK. Host applications own acquisition and decoding unless a later, separately scoped decision adds an optional integration.
- Unbounded real-time optical flow, segmentation, or AI reconstruction as a prerequisite for useful image/video transitions.

These may be explored separately, but they must not blur the support and fallback contracts above.
