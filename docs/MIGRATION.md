# Migrating 0.1.x → 0.2.0

This guide covers every deliberate behavior/API change in 0.2.0 and how to restore the
0.1.x result where one is wanted. Nothing in this file is optional reading unless your
icons use stroke reveals, custom anchors, or saved animation codecs.

## 1. Directional stroke reveal traversal (`directionTraversal`)

**What changed:** `MorphConfiguration.directionTraversal` now defaults to
`HONOR_DIRECTION`. A `FORWARD` stroke reveal with an explicit direction now travels
toward that direction's axis even when the contour's own point order runs the other
way. In 0.1.x the traversal always followed the contour point order, so a
contradicting direction had no effect.

**When you will see it:** icons whose contours were authored in the opposite order of
the requested `DrawDirection`. Frames pin the start anchor (yellow dot in the debug
overlay) but sweep along the requested axis.

```kotlin
val config = MorphConfiguration(
    directionTraversal = DirectionTraversal.HONOR_DIRECTION, // default since 0.2.0
)
```

**Restore 0.1.x:** set `directionTraversal = DirectionTraversal.LEGACY` explicitly.

Notes: explicit `REVERSE`/`CENTER_OUT`/`OUTSIDE_IN` modes always win over the policy;
closed contours are untouched (their control stays the `CLOCKWISE`/
`COUNTER_CLOCKWISE` winding reversal); custom-geometry, static-side, and
`PATH_TRAVERSAL` fill render sites don't honor the policy.

## 2. Draw anchors split from the alignment map

**What changed:** `MorphConfiguration.customDrawAnchors` (new) is the source for
`PathStart.CUSTOM` pen anchoring. `customStartOffsets` is deprecated but still works:
for any path with no `customDrawAnchors` entry, the renderer falls back to
`customStartOffsets` (one release window), preserving 0.1.x pixels. The deprecated
map keeps its alignment role for `StartPointStrategy.CUSTOM` correspondences.

```kotlin
// 0.1.x — alignment map doing double duty:
MorphConfiguration(
    customStartOffsets = mapOf("p" to 0.75f),
    startPoint = StartPointStrategy.CUSTOM,
)

// 0.2.x — split by intent:
MorphConfiguration(
    startPoint = StartPointStrategy.CUSTOM,               // alignment via correspondences
    customStartOffsets = mapOf("p" to 0.75f),             // … migrated to explicit pairs
    customDrawAnchors = mapOf("p" to 0.75f),              // pen anchor, when wanted
)
```

**Restore 0.1.x drawing behavior:** keep populating `customStartOffsets` and leave
`customDrawAnchors` empty; remove those uses still before 0.3.0.

## 3. `PathStart.START` vs `PathStart.AUTO`

**What changed:** `AUTO` is now the sentinel that derives the anchor from
`direction` (top-left geometric anchor when the direction is `AUTO`). `START` became
a literal anchor at arc fraction 0 of the normalized contour. In pre-release 0.2
candidates, `START` was the default and resolved **through** the direction table —
that derived behavior now lives in `AUTO`.

```kotlin
strokeReveal(start = PathStart.AUTO, direction = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT)
// anchor = TOP_RIGHT, derived from the direction (was implicitly yours in 0.1.x via START)
```

**Restore:** if you pinned `start = PathStart.START` meaning "derive it," switch to
`PathStart.AUTO`. If you want the pen to begin at the contour's own first point, keep
`START` — it now does exactly that.

Also new: `start = AUTO` + `direction = DrawDirection.CUSTOM` has no concrete anchor
to derive; it anchors top-left and records `MISSING_CONCRETE_ANCHOR` in the debug
banner (0.1.x rendered this combo anchored at arc 0).

## 4. Typed reveal lookups (codec-adjacent)

**What changed:** reveal configs are typed per property. Use
`VectorAnimation.strokeRevealFor(...)`, `fillRevealFor(...)`, or read
`Segment.reveal` directly. The old `drawConfigFor` shim is deprecated and resolves
only stroke (`DRAW_PROGRESS`) reveals.

```kotlin
val draw = animation.strokeRevealFor(fromName, toName, fromIndex, toIndex, fromGroups, toGroups)
val fill = animation.fillRevealFor(fromName, toName, fromIndex, toIndex, fromGroups, toGroups)
```

## 5. `VectorAnimationCodec` wire format v2

**What changed:** the codec writes version 2 bytes with typed reveal records. Version
1 payloads (files and caches from 0.1.x) still decode — migrate lazily. There is no
v2 → v1 writer. Custom `VectorAnimation` callbacks and custom `Brush`es remain
rejected at encode time (they are executable/runtime values).

## 6. Viewport scaling is now configurable

**What changed:** target viewports that differ from the source viewport are scaled
during planning. 0.1.x behavior is `ViewportNormalization.STRETCH` (per-axis scaling,
the default). `ViewportNormalization.FIT` scales uniformly and letterboxes the target
instead. No action needed to keep 0.1.x look; use `FIT` to stop authored aspect
ratios from being distorted.

## 7. Everything else

`MorphConfiguration.Default`, `pathMatching`, `missingPath`, `fallback`, `timing`,
color spaces, stagger, transforms, gradient interpolation semantics, debug options,
and cache APIs are unchanged from 0.1.x. New capability in this release:
`segmentBudget`/`simplificationTolerance` (geometry cost cap — pass `null`/default to
keep 0.1.x full detail) and aligned group-clip-mask morphing (previously clip masks
switched; they now morph when both sides declare the same clip count, which is
strictly more faithful).
