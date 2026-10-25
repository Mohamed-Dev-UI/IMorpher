# Showcase app tour

The `:app` module is a single-activity demo of everything **ImageVector Morph 0.2.0**
can do. Every screen drives the real library APIs — nothing is mocked — and the four
API playgrounds share one full-coverage configuration panel so you can learn each
control once and know it everywhere.

Run it from the repository root:

```bash
./gradlew :app:installDebug   # or launch from Android Studio
```

---

## Home

The home screen lists five topic cards. Each card plays one of the library's visual
signatures (a clip reveal for Diagnostics, a state-driven icon morph for MorphIcon,
a scrubbed morph for MorphVector, a keyframed playhead for VectorAnimator, a pen
reveal for DrawIcon) and states what the topic covers: **N showcases · 1
playground**, or **N diagnostic screens** for the Diagnostics topic, which
intentionally has no state-driven playground.

The top bar carries the shared **Reset playgrounds** action: it restores every
playground state to defaults without leaving the app. Platform back (and the page's
own back button) always returns the same way the system UI expects.

---

## Topics and screens

### Diagnostics — *look inside the engine*

| Screen | What it demonstrates |
| --- | --- |
| Plan report | Pairing kinds and counts (morph / fade / crossfade), compatibility score, fallback flags, unmatched source/target paths, and the validation issue list — all read live from `planMorph` for the chosen catalog pair. |
| Plan caches | Cold `planMorph` timing plus the live plan cache via `MorphCaches`: entry count, estimated bytes, `trim()` under simulated memory pressure. |
| Codec round-trip | Encodes a hand-built timeline with `VectorAnimationCodec`, prints byte count and decode warnings, then replays the decoded bytes through `DrawIcon` — exactly what loading a persisted animation does. |
| Custom geometry | A `CustomVectorAnimation` callback that slices visible geometry per frame with `sliceArcFraction` and shrinks the stroke as it draws — the extension point for fully bespoke reveals. |

### MorphIcon — *state one-step icons*

| Screen | What it demonstrates |
| --- | --- |
| State-driven icon morph | `MorphIcon` owns progress and retargets smoothly when state changes quickly; the toggle is the state. |
| Tint transition | Endpoint colors interpolate across the morph; tint replaces all paint (fill, stroke, gradient) while keeping alpha. |
| Stroke → fill handover | How an outline hands itself over to a fill: `StrokeFillStrategy.MORPH`, `CROSSFADE`, `DRAW`, and `AUTO`, one button each. |
| Chained icon retargeting | `MorphingIcon` always morphs from the **last settled** icon (never from a mid-flight frame); tap mid-morph to see it restart from the settle point. |
| Motion preference | Full, reduced, crossfade-only, and instant preferences side by side for accessibility. |
| Playground | Every parameter live. |

### MorphVector — *caller-owned progress*

| Screen | What it demonstrates |
| --- | --- |
| Manually scrub a morph | A slider stands in for the gesture (pager, scroll, drag) that owns progress. |
| Unmatched paths | Fade, scale, collapse, and keep behaviors for paths that exist on only one side. |
| Viewport normalization | A wide banner target meets a square source: `STRETCH` distorts each axis, `FIT` letterboxes. |
| Playground | Every parameter live (no duration spec — progress is driven directly). |

### VectorAnimator — *seekable, reusable timelines*

| Screen | What it demonstrates |
| --- | --- |
| Keyframe timeline | Vector keyframes (`scale`, `rotation` over the whole vector) with a scrubbable playhead. |
| Staggered paths | `stagger(delay, order)` fans each path's clock out around the chosen draw order. |
| Fill reveal modes | Every `FillMode` (fade, radial, linear, directional, sweep, path traversal) plus a travel direction over one pair. |
| Gradient endpoints | `gradient()` and `strokeGradient()` move fill and stroke paints between brush specs (solid ↔ linear ramp). |
| Playground | Compose keyframes, stroke reveals, color tracks, and stagger from every control. |

### DrawIcon — *one-shot pen reveals*

| Screen | What it demonstrates |
| --- | --- |
| Draw an icon with a pen | Named paths reveal from their start anchors along measured arc length, with a replay control. |
| Draw then fill | `reveal(followWithFill = true)`: the stroke draws and the fill sweeps in behind it from ~65% of the timeline. |
| Clip reveal | `clipReveal` opens a named group's inherited mask along a directional edge. |
| Playground | Every draw parameter live: start anchor, direction, mode, duration, and tint. |

---

## The shared configuration panel

All four playgrounds render the same `ConfigurationPanel`: twelve collapsible
sections (Matching and Start & direction start expanded), with **default-valued
controls never posed as decisions** — you only see sections that can change the
render's behavior, not a wall of knobs.

| Section | Controls |
| --- | --- |
| Matching | `pathMatching` dropdown (`BY_INDEX`, `BY_NAME`, `BY_ID`, `BY_GEOMETRY`, `BY_AREA`, `BY_CENTROID`, `AUTO`, `CUSTOM`); under `CUSTOM`, the source→target mapping editor (built from the pair's real compiled path names). |
| Start & direction | `startPoint` strategy, `pathDirection` strategy, back/direction `directionTraversal` policy (Honor vs Legacy), and the per-path draw-anchor editor (pen start as an arc fraction per path name). |
| Draw order & stagger | The compromise reveal ordering `drawOrder` (`BY_INDEX`, `TOP_LEFT_TO_BOTTOM_RIGHT`, `CLOCKWISE`, `COUNTER_CLOCKWISE`, `AUTO`; plus a custom order editor built from real names under `CUSTOM`), `keepRenderOrder` switch, and `stagger` delay (0–0.3) + order. |
| Paint & color | `colorSpace` interpolation (SRGB / linear sRGB / OKLab / OKLCH) and `strokeFill` (`MORPH`, `CROSSFADE`, `DRAW`, `AUTO`). |
| Fill reveal | `defaultFillMode` for fill-progress animations (fade / radial / linear / directional / sweep / path traversal) and `defaultFillDirection`. |
| Missing & fallback | `missingPath` behavior (fade / scale / collapse / keep / draw) and `fallback` strategy when direct morphs are impossible. |
| Timing & easing | `timing` (by path length / uniform / by command). The two spec-owning APIs (MorphIcon, DrawIcon) also get the duration slider (100–1500 ms) and overall spec shape (tween / linear tween / bouncy spring / no-bounce spring); manual-progress playgrounds show an explanatory note instead. |
| Motion | `motionPreference`: Full, Reduced, Crossfade-only, Instant. |
| Geometry budget | `segmentBudget` switch + slider (4–48 cubic segments) and `simplificationTolerance` (0.1–1.0 viewport units) — the trade-off between silhouette detail and per-frame cost. |
| Viewport | `viewportNormalization`: `STRETCH` (per-axis scaling) vs `FIT` (letterbox). |
| Size & placement | Optional explicit dp size override (20–96 dp) and out-of-center placements (Center start/end, Top/Bottom center). |
| Debug overlays | Master switch, then the seven engine overlays — path points, path numbers, bounding boxes, centroids, direction arrows, start anchors, raw path matching — plus the debug marker's arc fraction. |

Every dropdown is fed straight from the engine enum (`EnumClass.entries`), so the
app cannot drift from the library: a new enum value appears in the UI automatically.

---

## Copy-ready snippets and ranked pairs

* **Copy Kotlin snippet** — each playground generates paste-ready code
  that mirrors the live state: icons as `IMorpherIcons.` accessors, tint as real
  `Color(0x…)` literals or `Brush.linearGradient(...)` builders, and **only the
  non-default parameters** (an all-default configuration is simply omitted so
  pasted code reads like the README).

* **Suggested pairs** — engine-ranked chips (`score` percentage) computed by
  `planMorph` off the main thread. Chips surface engine diagnostics inline:
  **fallback** when the engine had to fall back for that pair, and a warning
  count when the plan carries validation warnings.

---

## Where to read more

* [CONFIGURATION.md](CONFIGURATION.md) — the reference for every parameter the
  panel exposes.
* [TIMELINE-DSL.md](TIMELINE-DSL.md) — the `VectorAnimation { … }` block the
  VectorAnimator/DrawIcon playgrounds generate snippets for.
* [DIAGNOSTICS.md](DIAGNOSTICS.md) — plan reports, caches, and debug overlays
  behind the Diagnostics screens.
* [CUSTOM-GEOMETRY.md](CUSTOM-GEOMETRY.md) — the custom callback and clip
  mechanics demonstrated in the "Custom geometry" and "Clip reveal" screens.
