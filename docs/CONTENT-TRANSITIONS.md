# Content Transitions

Applies to **0.3.0**.

`MorphVector` and `MorphIcon` interpolate vector geometry. Most content cannot do that:
an `ImageBitmap` has no paths, a `Painter` may paint anything, and a composable slot has no
inspectable tree. IMorpher's content transitions composite two **rendered endpoints** over the
same caller-owned progress value instead. They never claim to interpolate hidden structure.

| Entry point | Endpoint type | Typical use |
| --- | --- | --- |
| `MorphPainter` | `Painter` | Custom drawing, image-backed painters, host-provided frames |
| `MorphImage` | `ImageBitmap` | Raster images and video frames |
| `MorphFrames` | `FrameSource` | Changing frames: decoders feeding two live sources |
| `MorphContent` | Composable slots | Cards, screens, text, arbitrary UI |

All four share `ContentTransition` and `ContentEffect` from `vector-morph-core`, so the same
transition definition renders consistently across source families. `MorphFrames` is `MorphImage`
over two host-owned frame buffers — every transition, effect, sizing rule, and
`motionPreference` rule below applies to it unchanged.

## Quick start

```kotlin
MorphImage(
    from = startFrame,
    to = endFrame,
    progress = playbackProgress,          // caller-owned, 0..1
    transition = ContentTransition.Wipe(
        direction = WipeDirection.LEFT_TO_RIGHT,
        feather = 0.2f,
    ),
    contentScale = ContentScale.Crop,
    modifier = Modifier.size(320.dp, 180.dp),
    contentDescription = "Clip transition",
)
```

```kotlin
MorphContent(
    progress = screenProgress,
    from = { HomeScreen() },
    to = { DetailsScreen() },
    transition = ContentTransition.Crossfade(),
    modifier = Modifier.fillMaxSize(),
)
```

Changing frames (video, animation) go through a `FrameSource` per endpoint — the host decodes
and pushes frames while the caller still owns `progress`:

```kotlin
MorphFrames(
    from = leftDecoder,          // FrameSource fed with submit(...)
    to = rightDecoder,
    progress = playbackProgress, // caller-owned; never derived from playback speed
    transition = ContentTransition.Wipe(direction = WipeDirection.LEFT_TO_RIGHT),
    width = 320.dp,
    height = 180.dp,             // explicit size: layout must not jump on a stalled frame
    contentScale = ContentScale.Crop,
    contentDescription = "Clip transition",
)
```

`FrameSource` is latest-wins: `submit(frame, presentationTimeMillis)` replaces any frame the
renderer has not presented yet (the overwrite is counted in `droppedFrames`),
`markUnavailable()` composites the endpoint as absent (decode failure, protected content — it
does not throw), and `markEnded()` freezes on the last frame. `presentationTimeMillis` is
metadata for host-side alignment only; one caller-owned clock drives both endpoints.

## Progress contract

- Progress is **caller-owned**: use a `State<Float>`, a drag, an animation, or `Animatable`.
  For state-driven chaining, `MorphingContent` mirrors the `MorphIcon` autoplay/retarget
  contract: the first composition renders the target slot directly, a `target` change morphs
  from the last settled slot content with a caller-chosen progress `animationSpec`, a
  mid-flight retarget restarts from the last settled content, and reduced/instant motion
  preferences shorten the transition (`REDUCED` = 120 ms, `INSTANT` = endpoint jump).
  `CROSSFADE_ONLY` resolves the visual transition to a crossfade. Manual-progress entries
  stay manual; the vector entries keep their own `MorphIcon`/`VectorAnimator` playback APIs.
- Values are clamped to `0..1`. `NaN` resolves to `0`; infinities resolve to the nearest
  endpoint.
- At `0` the outgoing endpoint renders exactly; at `1` the incoming endpoint does. Explicit
  effects can intentionally break that guarantee (for example, a `Fade` from `1` to `0`).
- The transition is deterministic and seekable: the same progress always resolves to the same
  layer state.

## Modes

| Mode | Composition | Midpoint |
| --- | --- | --- |
| `ContentTransition.Crossfade()` | Outgoing alpha `1 - p`, incoming alpha `p`. | Both endpoints at half alpha; the background shows through (the "through" composite). |
| `ContentTransition.FadeOver()` | Outgoing alpha `1`, incoming alpha `p`. | Incoming painted over the still-opaque outgoing endpoint (the "over" composite). |
| `ContentTransition.Wipe(direction, feather)` | Both endpoints opaque; the incoming endpoint is masked by a moving edge. | The requested fraction of the incoming endpoint is visible. |

`Wipe` supports the four cardinal axes: `LEFT_TO_RIGHT`, `RIGHT_TO_LEFT`,
`TOP_TO_BOTTOM`, `BOTTOM_TO_TOP`. `feather` is the soft-edge width as a fraction of the wipe
axis; `0` is a hard clip and `0.5` is the useful maximum. Values outside `0..0.5` are clamped,
and non-finite values resolve to `0`. At progress `0` none of the incoming endpoint is drawn;
at `1` all of it is. A feathered wipe draws the incoming layer once into a save layer and
multiplies its alpha by a clamped linear gradient (`DstIn`), the same recipe the vector fill
sweeps use.

## Effects

`ContentEffect` entries add layer properties on top of the mode. Each targets one endpoint.

| Effect | Interpolation | Composition |
| --- | --- | --- |
| `Fade(endpoint, from, to)` | Alpha multiplier, values clamped `0..1` (non-finite -> `1`). | Multiplies the mode's alpha. Prefer the mode for an ordinary crossfade; use this for extra fades. |
| `Scale(endpoint, from, to)` | Uniform scale, `0..4`. | Multiplies; repeated scales compound. Non-finite input resolves to `0`. |
| `Translate(endpoint, fromX, fromY, toX, toY)` | Offset in fractions of the layer size. | Adds; repeated translations compound. |
| `Rotate(endpoint, fromDegrees, toDegrees)` | Degrees around the layer center, clockwise in Y-down space. | Adds; repeated rotations compound. |

Effects are draw-only: they never change measurement, layout, or hit-testing. Each layer is
drawn with translation, then rotation, then scale, then alpha; because the four properties are
independent, the effect list's order never changes the result. A wipe is applied **outside**
the layer effects, so the edge masks the transformed endpoint in container space.

```kotlin
ContentTransition.Crossfade(
    effects = listOf(
        ContentEffect.Scale(ContentEndpoint.FROM, from = 1f, to = 0.85f),
        ContentEffect.Scale(ContentEndpoint.TO, from = 0.85f, to = 1f),
        ContentEffect.Fade(ContentEndpoint.TO, from = 0f, to = 1f),
    ),
)
```

## Sizing, scale, and alignment

`MorphPainter`/`MorphImage` draw each endpoint inside a shared box:

- `contentScale` (`Fit` by default) and `alignment` resolve the source inside that box, like
  Compose's `Image`. `MorphFrames` draws through `MorphImage`, so the same rule applies to
  each submitted frame.
- When `width`/`height` are omitted, the box uses the larger usable intrinsic size of the two
  painters, per axis, falling back to `24.dp`. An explicit `modifier.size(...)` takes
  precedence.
- `MorphContent` sizes itself from its slots. Each slot is laid out by the outer `Box` and
  aligned with `contentAlignment`; a wipe reveals the incoming slot within that slot's own
  bounds.

## Lifecycle, input, and accessibility

- **Both endpoints stay composed** for the whole transition. State inside each slot
  (`remember`, scroll position, running animations) survives seek and retarget, and there is no
  composition boundary at the midpoint. The cost is that both trees exist at once.
- The incoming endpoint is drawn on top. At overlapping pixels it owns pointer input, including
  during a crossfade; keep interactive content out of the outgoing slot once it should no
  longer accept taps.
- `contentDescription` is applied to the container. `MorphPainter`/`MorphImage` leave the
  painter children undescribed; `MorphContent` slots keep their own semantics.
- An endpoint whose resolved alpha is `0`, or a wiped endpoint that is not yet revealed, is
  marked hidden from accessibility services. It is still composed and still measured.
- Caller-owned progress is the reduced-motion escape hatch: pass the settled `0f`/`1f` to skip
  the transition. There is no implicit `MotionPreference` handling in these manual-progress
  entries, matching `MorphVector`.

## Performance and limits

- Bitmaps are never decoded, resized, or uploaded by the library. Hosts own loading, decoding,
  frame production, and disposal; pass stable `Painter`s (or `remember` a `BitmapPainter`) so a
  recomposition does not allocate per frame.
- Both endpoints are drawn every frame. Avoid stacking many transitions over expensive trees,
  and keep per-frame effects draw-only (as they are here).
- Frame ownership is the host's: the library never decodes, resizes, or uploads a bitmap, and
  a `FrameSource` never recycles or mutates one — `release()` drops the reference so the host
  can dispose it. Backpressure is one pending frame per source, so a decoder running ahead of
  the UI cannot queue unbounded frames. A/V synchronization, decoder choice, and playback
  control remain host responsibilities.
- This layer does **not** perform geometry morphing, structural diffing, or automatic shape
  extraction. Use `MorphVector`/`MorphIcon` when both endpoints expose compatible paths, and
  the custom-geometry callback ([CUSTOM-GEOMETRY.md](CUSTOM-GEOMETRY.md)) when a caller can
  supply matching contours explicitly.
- Only cardinal wipes are supported in this release. `ContentTransition` is a sealed type, so
  unsupported modes cannot be configured into a silent no-op.

## Roadmap placement

This is the first slice of `GOALS.md` Phases 3–5: the shared visual-effect vocabulary, the
raster/painter and composable-content endpoints, and the host-owned frame-source boundary
(`FrameSource`/`MorphFrames`). `MorphingContent` closes the autoplay/retarget wrapper slice
(state-driven chaining with the `MorphIcon` contract for composable slots). Still open:
mask/through refinements, benchmark budgets on representative devices, and the showcase
coverage that accompanies a public release.
