# Documentation Index

Guides for **ImageVector Morph 0.2.0**. The top-level [README](../README.md) is the
entry point; this directory holds the deep dives.

| Document | Purpose |
| --- | --- |
| [CONFIGURATION.md](CONFIGURATION.md) | Every `MorphConfiguration` parameter and enum; the start/direction precedence contract; fill direction inheritance; viewport and layout rules. |
| [TIMELINE-DSL.md](TIMELINE-DSL.md) | Building and wiring animation timelines: targets, tracks, keyframes, easings, stagger, gradient specs, serialization. |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | Plan reports, validation, debug overlays, resolution warnings, cache control, precompilation, custom-callback health. |
| [CUSTOM-GEOMETRY.md](CUSTOM-GEOMETRY.md) | The per-frame override callback, custom draw/fill contours, clip reveals and morphing masks, boolean path operations. |
| [MIGRATION.md](MIGRATION.md) | 0.1.x → 0.2.0 changes with restore recipes. |
| [SHOWCASE-APP.md](SHOWCASE-APP.md) | Tour of the demo application and its parameter coverage. |
| [CHANGELOG](../CHANGELOG.md) | Release history. |

The generated HTML API reference comes from Dokka:
`./gradlew dokkaGenerate` → `build/dokka`.

## Internal working documents

`docs/superpowers/` holds this repository's *process* artifacts used during
development — design specs (`docs/superpowers/specs/`) and implementation plans with
their execution ledgers (`docs/superpowers/plans/`). They are internal history: they
describe how releases were built, not how the library behaves. When a guide here and
an internal document disagree, trust the guides and CHANGELOG.
