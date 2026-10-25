# Custom Geometry, Clips, and Boolean Paths

## The override callback

`CustomVectorAnimation` runs once per rendered path pair, per frame, on the drawing
thread. It is a `fun interface` registered inside a timeline; return `null` to leave
the normal renderer in control of that pair:

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
DrawIcon(vector = icon, animation = custom)
```

Callback inputs (`CustomVectorPathState`) — `pairIndex`, `from`/`to`
(`PreparedPath?`), `progress`, `morphProgress`, `drawProgress`, `fillProgress`.

Each `CustomVectorPathOverrides` field is independent; `null` keeps the normal
renderer's value for that property:

| Field | Effect |
| --- | --- |
| `contours` | Replaces geometry for both paint passes (fills too — contours fill together, so fill-rule holes keep working). |
| `strokeRevealContours` | The progressive stroke-reveal geometry; **required** for `DrawMode.CUSTOM` (the renderer throws without it). |
| `fillContours` | The fill-reveal geometry; **required** for `DrawDirection.CUSTOM` fills. |
| `alpha`, `fillAlpha`, `strokeAlpha` | Multipliers. |
| `fill`, `stroke` | Paint overrides as `BrushSpec` values. |
| `strokeWidth` | Viewport units; null keeps the path's own. |
| `scaleX`, `scaleY` | Transform (null scaleY follows scaleX). |
| `rotationDegrees`, `translationX/Y`, `pivotX/Y` | Rotation (degrees clockwise, shortest route), translation, and fractional pivot. |

**Rules of engagement:**

- Deterministic and lightweight — no allocations you wouldn't do per-frame, no
  logging of exceptions you don't fix. A callback that throws is disabled
  process-wide after its first failure (`CustomizerFailures`) and the pair renders
  normally; `DrawResolutionWarning.CUSTOM_ANIMATION_FAILED` is recorded.
- `MissingPathBehavior.CUSTOM` requires the callback to return geometry for the
  unmatched paths; a missing result throws with the affected pair index instead of
  silently choosing a fallback.
- Custom callbacks and custom `Brush`es are executable/runtime values: the
  `VectorAnimationCodec` rejects them at encode time with a descriptive error.

## Clip reveals and morphing masks

Group `clipPathData` is retained during compilation. `clipReveal(...)` progressively
opens the inherited mask along a directional edge, and the renderer applies clips to
morphing, static, and fading paths. When both sides of a pair declare the same number
of inherited clip contours, the masks are matched and interpolated **with** the
geometry, so the mask itself morphs; mismatched or one-sided masks switch source/target
at the midpoint. `DrawDirection.CUSTOM` is not available on `clipReveal(...)` — build
the mask in a custom callback instead.

```kotlin
path("masked") {
    clipReveal(from = 0f, to = 1f, direction = DrawDirection.TOP_TO_BOTTOM, interval = 0.1f..0.6f)
}
```

## Boolean geometry

Optional boolean operations on Compose `Path`s, for preparation-time work outside the
normal compiler. Convert normalized contours first:

```kotlin
val contourPath = BooleanGeometry.path(contourData)     // normalized contour → Compose Path
val outline = BooleanGeometry.rectangle(0f, 0f, 24f, 24f)

val result = BooleanGeometry.union(outline, contourPath)
// same namespace: intersection, difference, subtract
```

`union`/`intersection`/`difference`/`subtract` run during preparation or in callback
work — they are not part of the per-frame geometry compiler. A classic use is a
custom callback that precomputes one boolean result once, then hands its contours
back per frame (convert the `Path` via `PathNodeNormalizer`-style extraction only at
preparation time).

## Response-time constraints summary

Callbacks run on the drawing thread — keep them pure over their inputs. Anything you
can compute once (boolean results, static keyframe geometry, cached BrushSpec tables)
should be computed at preparation time and closed over, so the per-frame model of the
showcase app remains: decide geometry, return overrides, go home.
