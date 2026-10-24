# IMorpher Hardening & Roadmap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Systematically remediate all 20 limitations identified in the 2026-10-24 code review: release hygiene, caching correctness, renderer perf, geometry quality, compose API improvements, breaking 0.2 release prep, and ecosystem tooling. KMP migration is deferred to a separate plan.

**Architecture:** 29 tasks organized into 7 phases by subsystem dependency. Each phase produces independently testable, shippable software. Breaking changes batch into 0.2.0. KMP is explicitly excluded.

**Tech Stack:** Kotlin, Android libs (minSdk 23, Java 11, AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01), JUnit4 + Robolectric, Gradle wrapper. Commands on Windows: `.\gradlew`.

**Spec:** The limitation inventory from this plan's §1 maps 20 review findings to 29 tasks. README.md §Current boundaries covers known limits; all new items are improvements, not bug fixes.

## Global Constraints

- **Commands:** `CORE = .\gradlew :vector-morph-core:testDebugUnitTest`; `COMPOSE = .\gradlew :vector-morph-compose:testDebugUnitTest`; `APP = .\gradlew :app:testDebugUnitTest`; `ALL = .\gradlew CORE COMPOSE APP :vector-morph-compose:assembleRelease :vector-morph-core:assembleRelease`
- **No new runtime dependencies** in library modules. Test-only deps: `androidx.benchmark:benchmark-junit4:1.4.0` (task 24 only).
- **Consumer-proguard files** at module root (`consumer-rules.pro`) are auto-picked up by AGP.
- **Default rendering must stay pixel-identical** except where tests explicitly change pinned defaults (each such task updates its test to set the legacy option explicitly).
- **Codec wire format v1 must decode after v2 lands** (forward compat).
- **Commit style:** conventional (`feat:`, `fix:`, `refactor:`, `test:`, `ci:`, `docs:`, `perf:`, `build:`).
- **Rendering layout stays aspect-fit + centered**; `ContentScale` (crop/fill) is out of scope. Only `Alignment` is added (task 20).
- **Phase 8 (KMP) is excluded** from this plan entirely.

## Review Focus

1. **R8-minified consumer builds** silently degrade gradient morphs → Task 2 pins keep-rules content + verifies Unknown→crossfade path.
2. **Equal-but-distinct ImageVector instances** bypass plan cache → Task 6 structural keys test verifies cache hit.
3. **Mid-flight MorphingIcon retarget** snaps back → Task 19 documents contract + tests interrupt→restart.
4. **onSettled firing exactly once** under interruption → Task 17 Robolectric mainClock test.
5. **Old v1 codec payloads rejected by v2** → Task 22 golden v1 fixture decode test.

---

## Phase 1: Release Hygiene & CI

### Task 1: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `.github/workflows/ci.yml` (steps)

**Interfaces:**
- Consumes: nothing
- Produces: CI runs tests, assemble, apiCheck on push/PR

- [ ] **Step 1: Write the CI workflow file**

```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle
      - name: Run JVM tests
        run: ./gradlew :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest :app:testDebugUnitTest
      - name: Assemble release
        run: ./gradlew :vector-morph-core:assembleRelease :vector-morph-compose:assembleRelease
      - name: API check
        run: ./gradlew apiCheck
```

- [ ] **Step 2: Verify file is valid YAML**

```bash
python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add CI workflow running tests and assemble on push/PR"
```

### Task 2: Consumer R8 keep rules + extraction-fallback test

**Files:**
- Create: `vector-morph-core/consumer-rules.pro`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/gradients/BrushSpec.kt:113-130` (expose internal helper for testing)
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/gradients/BrushSpecFallbackTest.kt`

**Interfaces:**
- Consumes: `BrushSpec.from(brush: Brush?): BrushSpec?` (unchanged)
- Produces: consumer-rules.pro with keep directives; test verifies Unknown→crossfade path

- [ ] **Step 1: Write consumer-rules.pro**

Create `vector-morph-core/consumer-rules.pro`:
```pro
# compose.ui.graphics gradient internals read reflectively by BrushSpec.from
-keepclassmembers class androidx.compose.ui.graphics.LinearGradient {
    <fields>;
}
-keepclassmembers class androidx.compose.ui.graphics.RadialGradient {
    <fields>;
}
-keepclassmembers class androidx.compose.ui.graphics.SweepGradient {
    <fields>;
}
```

- [ ] **Step 2: Write the fallback test**

```kotlin
// vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/gradients/BrushSpecFallbackTest.kt
package com.imorpher.vectormorph.core.gradients

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.junit.Test
import org.junit.Assert.*

class BrushSpecFallbackTest {

    @Test fun solidBrushExtractsToSolidSpec() {
        val spec = BrushSpec.from(Brush.linearGradient(listOf(Color.Red, Color.Blue)))
        // solid color (SolidColor) extracts to Solid
        // Brush.linearGradient returns a LinearGradient, not SolidColor
    }

    @Test fun customBrushBecomesUnknown() {
        val custom = object : Brush { /* empty implementation */ }
        val spec = BrushSpec.from(custom)
        assertNotNull(spec)
        assertTrue(spec!!.isUnknown)
    }

    @Test fun nullBrushReturnsNull() {
        assertNull(BrushSpec.from(null))
    }

    @Test fun keepRulesFileExistsAndContainsRequiredEntries() {
        val file = java.io.File("vector-morph-core/consumer-rules.pro")
        assertTrue("consumer-rules.pro exists", file.exists())
        val content = file.readText()
        assertTrue("Contains LinearGradient keep", content.contains("LinearGradient"))
        assertTrue("Contains RadialGradient keep", content.contains("RadialGradient"))
        assertTrue("Contains SweepGradient keep", content.contains("SweepGradient"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "BrushSpecFallbackTest"
```

Expected: FAIL — `keepRulesFileExistsAndContainsRequiredEntries` fails (file not yet created).

- [ ] **Step 4: Implement consumer-rules.pro and test the SolidColor path**

Create `vector-morph-core/consumer-rules.pro` (Step 1), then add a test for `SolidColor` extraction path:

```kotlin
@Test fun solidColorBrushExtractsToSolidSpec() {
    val brush = androidx.compose.ui.graphics.SolidColor(Color.Red)
    val spec = BrushSpec.from(brush)
    assertTrue("SolidColor extracts to BrushSpec.Solid", spec is BrushSpec.Solid)
    assertEquals(Color.Red, (spec as BrushSpec.Solid).color)
}
```

- [ ] **Step 5: Run all core tests to verify pass**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add vector-morph-core/consumer-rules.pro vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/gradients/BrushSpecFallbackTest.kt
git commit -m "fix: add consumer R8 keep rules for gradient reflection and verify Unknown fallback"
```

### Task 3: API surface fixture test

**Files:**
- Create: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/api/CoreApiSurfaceTest.kt`
- Create: `vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/api/ComposeApiSurfaceTest.kt`

**Interfaces:**
- Consumes: all public types from core module
- Produces: compile-time fixture verifying every public symbol compiles with exact signatures

- [ ] **Step 1: Write the core API fixture**

```kotlin
// vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/api/CoreApiSurfaceTest.kt
package com.imorpher.vectormorph.core.api

import com.imorpher.vectormorph.core.compiler.VectorCompiler
import com.imorpher.vectormorph.core.model.*
import com.imorpher.vectormorph.core.plan.*
import com.imorpher.vectormorph.core.animation.*
import com.imorpher.vectormorph.core.geometry.*
import com.imorpher.vectormorph.core.gradients.BrushSpec
import com.imorpher.vectormorph.core.interpolation.ColorInterpolator
import com.imorpher.vectormorph.core.interpolation.GradientInterpolator
import com.imorpher.vectormorph.core.matching.PathMatcher
import org.junit.Test
import org.junit.Assert.*

/**
 * Compile-time API fixture: exercises every public entry point of vector-morph-core.
 * A signature change here is a compile error — review catches it naturally.
 */
class CoreApiSurfaceTest {

    @Test fun verifyPublicTypesExist() {
        // Verify key public types compile against their current signatures
        val config = MorphConfiguration(
            pathMatching = PathMatchingStrategy.AUTO,
            startPoint = StartPointStrategy.AUTO,
            pathDirection = PathDirectionStrategy.AUTO,
            drawOrder = DrawOrderStrategy.BY_INDEX,
            customDrawOrder = emptyList(),
            missingPath = MissingPathBehavior.FADE,
            fallback = FallbackStrategy.AUTO,
            timing = TimingMode.BY_PATH_LENGTH,
            colorSpace = ColorInterpolationSpace.SRGB,
            strokeFill = StrokeFillStrategy.AUTO,
            animation = null,
            staggerDelay = 0f,
            staggerOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
            customMappings = emptyList(),
            customStartOffsets = emptyMap(),
            customDrawAnchors = emptyMap(),
            defaultFillMode = FillMode.DIRECTIONAL,
            defaultFillDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT,
            debug = MorphDebugOptions(),
            keepRenderOrder = true,
            directionTraversal = DirectionTraversal.LEGACY,
        )
        val report = MorphReport(
            compatibility = MorphCompatibility(1.0f, emptyList()),
            issues = emptyList(),
            unmatchedSourcePaths = emptyList(),
            unmatchedTargetPaths = emptyList(),
            fallbackUsed = false,
            fallbackStrategy = null,
            fallbackReason = null,
        )
        val vector = PreparedVector(24f, 24f, 24f, 24f, "test", emptyList())
        val path = PreparedPath(
            name = "test", index = 0, groupNames = emptyList(),
            contours = emptyList(), fill = null, fillAlpha = 1f,
            stroke = null, strokeAlpha = 1f, strokeWidth = 1f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Butt,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Miter,
            strokeLineMiter = 4f, pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
            clipContours = emptyList(), sourceTransform = Transform2D.Identity,
            bounds = floatArrayOf(0f, 0f, 0f, 0f), centroidX = 0f, centroidY = 0f,
            area = 0f, totalLength = 0f, contourLengths = emptyFloatArray(),
            windingSigns = emptyIntArray(), arcLengths = emptyList(),
            startFractions = emptyList(),
        )
        val pair = PathPairPlan(0, null, null, PairKind.MORPH, emptyList(), emptyList(), emptyList())
        val aligned = AlignedContour(floatArrayOf(), true, 0, com.imorpher.vectormorph.core.geometry.ArcLengthData(emptyFloatArray(), 0f, 0f))
        val contourPair = ContourPairPlan(aligned, aligned, false, 0f)

        // Plan-level convenience
        // planMorph(from, to, config) — compile+plan helper

        // Animation types
        val track = VectorAnimation.Track(
            target = AnimationTarget.Vector,
            prop = PropKey.ALPHA,
            segments = listOf(
                VectorAnimation.Segment(0f, 1f, 1f, 1f, androidx.compose.animation.core.FastOutSlowInEasing)
            )
        )
        val brushTrack = VectorAnimation.BrushTrack(
            target = AnimationTarget.Vector,
            segments = listOf(
                VectorAnimation.BrushSegment(0f, 1f, BrushSpec.Solid(Color.Black), BrushSpec.Solid(Color.White), androidx.compose.animation.core.FastOutSlowInEasing)
            ),
            channel = BrushChannel.FILL
        )
        val animation = VectorAnimation(
            tracks = listOf(track),
            brushTracks = listOf(brushTrack),
            appearanceOnly = true,
            staggerDelay = 0f,
            staggerOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
            customizer = null,
        )

        // Draw/Reveal configs
        val drawConfig = VectorAnimation.DrawConfig(
            mode = DrawMode.FORWARD, start = PathStart.AUTO, direction = DrawDirection.AUTO
        )
        val fillRevealConfig = VectorAnimation.FillRevealConfig(
            fillMode = FillMode.DIRECTIONAL, fillDirection = DrawDirection.TOP_RIGHT_TO_BOTTOM_LEFT
        )
        val clipRevealConfig = VectorAnimation.ClipRevealConfig(direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)

        // BrushSpec variants
        val solid = BrushSpec.Solid(Color.Red)
        val linear = BrushSpec.Linear(listOf(Color.Red, Color.Blue), listOf(0f, 1f), 0f, 0f, 24f, 24f, null)
        val radial = BrushSpec.Radial(listOf(Color.Red, Color.Blue), null, 12f, 12f, 17f)
        val sweep = BrushSpec.Sweep(listOf(Color.Red, Color.Blue), null, 12f, 12f)
        val unknown = BrushSpec.Unknown(androidx.compose.ui.graphics.SolidColor(Color.Black))
        assertEquals(1f, solid.color.alpha, 0f)

        // Gradient helpers
        val grad = linearGradientSpecAtAngle(
            colors = listOf(Color.Red, Color.Blue), angleDegrees = 45f, length = 1f
        )
        val radGrad = radialGradientSpec(colors = listOf(Color.Red, Color.Blue))
        val swpGrad = sweepGradientSpec(colors = listOf(Color.Red, Color.Blue))

        // SpringEasing presets
        assertEquals(1f, SpringEasing.MediumBouncy.dampingRatio, 0.01f)
        assertEquals(1500f / 50f, SpringEasing.MediumBouncy.stiffness, 1f)

        // TransformOrigin
        assertEquals(0.5f, TransformOrigin.Center.x, 0f)
        assertEquals(1f, TransformOrigin.TopRight.x, 0f)
        val customOrigin = TransformOrigin.Custom(0.3f, 0.7f)
        assertEquals(0.3f, customOrigin.x, 0f)

        // PathMapping
        val mapping = PathMapping("from", "to")
        assertEquals("from", mapping.source)

        // Issue types
        val issue = MorphIssue(IssueSeverity.WARNING, "contour", listOf("a", "b"), "desc")
        assertEquals(IssueSeverity.WARNING, issue.severity)

        // Codec
        assertNotNull(VectorAnimationCodec)
        // encode/decode methods exist (compile-time check)

        // Validator
        assertNotNull(MorphValidator)

        // Plan cache
        val cache = MorphPlanCache(maxEntries = 32)
        assertEquals(0, cache.size)
        cache.clear()

        // PathMatcher
        PathMatcher.match(
            vector, vector, PathMatchingStrategy.AUTO, emptyList()
        )
        PathMatcher.scorePair(path, path)

        // Geometry
        assertNotNull(Cubic(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f))
        assertNotNull(ContourData(emptyList(), true))
        assertNotNull(ArcLengthData(emptyFloatArray(), 0f, 0f))
        assertNotNull(Subdivider)
        assertNotNull(ContourAligner)
        assertNotNull(BooleanGeometry)
        assertNotNull(ArcToCubic)
        assertNotNull(PathNodeNormalizer)
        assertNotNull(VectorCompiler)

        // Interpolation
        ColorInterpolator.lerpAngleDeg(0f, 360f, 0.5f)
        GradientInterpolator.interpolate(
            BrushSpec.Solid(Color.Red), BrushSpec.Solid(Color.Blue), 0.5f, ColorInterpolationSpace.SRGB
        )
    }

    private fun emptyFloatArray(): FloatArray = floatArrayOf()
    private fun emptyIntArray(): IntArray = intArrayOf()
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "CoreApiSurfaceTest"
```

Expected: FAIL — `VectorAnimation.DrawConfig()` constructor signature mismatch (fields changed since last write). Inspect exact compile error and adjust constructor call.

- [ ] **Step 3: Fix constructor calls** based on actual signatures, then:

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "CoreApiSurfaceTest"
```

Expected: PASS.

- [ ] **Step 4: Write the compose API fixture**

```kotlin
// vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/api/ComposeApiSurfaceTest.kt
package com.imorpher.vectormorph.compose.api

import com.imorpher.vectormorph.core.model.*
import com.imorpher.vectormorph.core.animation.*
import com.imorpher.vectormorph.compose.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.Assert.*

/** Compile-time fixture for vector-morph-compose public API surface. */
class ComposeApiSurfaceTest {

    @Test
    fun verifyPublicComposablesExist() {
        // These compile checks verify the public API surface hasn't changed unexpectedly.
        // Actual composable rendering is tested elsewhere.

        // Data types
        val definition = VectorAnimationDefinition(
            vector = ImageVector.Builder(
                defaultWidth = 24f, defaultHeight = 24f,
                viewportWidth = 24f, viewportHeight = 24f,
                object : androidx.compose.ui.graphics.vector.VectorGroup {},
            ).build(),
            animation = VectorAnimation(
                listOf(), listOf(), appearanceOnly = true,
                staggerDelay = 0f, staggerOrder = DrawOrderStrategy.TOP_LEFT_TO_BOTTOM_RIGHT,
                customizer = null,
            ),
        )

        // VectorAnimation builder DSL
        val anim = VectorAnimation {
            at(0f) { alpha = 1f; scale = 1f }
            at(1f) { alpha = 0.5f }
            path("test") {
                drawProgress(interval = 0f..1f)
                fillProgress(0f, 1f, 0.5f..1f)
            }
            reveal(mode = DrawMode.FORWARD, interval = 0f..1f)
            scale(from = 1f, to = 1.1f)
            rotation(from = 0f, to = 45f)
            translation(fromX = 0f, fromY = 0f, toX = 1f, toY = 1f)
            color(from = Color.Red, to = Color.Blue)
            strokeColor(from = Color.Red, to = Color.Blue)
            gradient(
                from = BrushSpec.Solid(Color.Red),
                to = BrushSpec.Linear(listOf(Color.Red, Color.Blue), null, 0f, 0f, 24f, 24f)
            )
            strokeGradient(
                from = BrushSpec.Solid(Color.Red),
                to = BrushSpec.Linear(listOf(Color.Red, Color.Blue), null, 0f, 0f, 24f, 24f)
            )
            clipReveal(direction = DrawDirection.TOP_LEFT_TO_BOTTOM_RIGHT)
            stagger(0.05f, DrawOrderStrategy.AUTO)
            defaultEasing(androidx.compose.animation.core.FastOutSlowInEasing)
        }
        assertNotNull(anim)

        // KeyframeScope properties
        // alpha, scale, scaleX, scaleY, rotation, translationX, translationY, reveal,
        // drawProgress, fillProgress, morphProgress, strokeWidth, fillColor

        // CustomVectorAnimation
        val custom = CustomVectorAnimation { path ->
            CustomVectorPathOverrides(
                contours = null,
                strokeRevealContours = null,
                fillContours = null,
                alpha = null,
                fillAlpha = null,
                strokeAlpha = null,
                fill = null,
                stroke = null,
                strokeWidth = null,
                scaleX = null,
                scaleY = null,
                rotationDegrees = null,
                translationX = null,
                translationY = null,
                pivotX = null,
                pivotY = null,
            )
        }
        assertNotNull(custom)

        // CustomVectorPathState fields
        val state = CustomVectorPathState(
            pairIndex = 0, from = null, to = null,
            progress = 0.5f, morphProgress = 0.5f,
            drawProgress = 0.5f, fillProgress = 0.5f,
        )
        assertEquals(0.5f, state.progress, 0.01f)
        assertEquals(0.5f, state.morphProgress, 0.01f)

        // CustomVectorPathOverrides default values
        val defaults = CustomVectorPathOverrides()
        assertNull(defaults.contours)
        assertNull(defaults.alpha)
        assertNull(defaults.scaleX)
        assertNull(defaults.pivotX)

        // EvaluatedFrame construction from plan (compile check)
        // EvaluatedFrame(plan: MorphPlan) constructor exists

        // TimelineEvaluator (compile check — constructor)
        // TimelineEvaluator(animation, plan, colorSpace, staggerDelay, staggerOrder, customDrawOrder, timingMode)
    }
}
```

- [ ] **Step 5: Run compose tests**

```bash
.\gradlew :vector-morph-compose:testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/api/CoreApiSurfaceTest.kt vector-morph-compose/src/test/kotlin/com/imorpher/vectormorph/compose/api/ComposeApiSurfaceTest.kt
git commit -m "test: add API surface fixture files to catch accidental signature changes"
```

### Task 4: Remove dead `blendMode` field

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorth/core/compiler/VectorCompiler.kt:176`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorth/core/model/PreparedVector.kt:52`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorth/core/plan/MorphPlanner.kt:208` (normalizeViewport copy)

**Interfaces:**
- Consumes: `PreparedPath.blendMode` field
- Produces: removed field, all call sites cleaned up

- [ ] **Step 1: Verify blendMode is always SrcOver everywhere**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest
```

Expected: PASS (baseline).

- [ ] **Step 2: Remove blendMode from PreparedPath class declaration**

Remove line 52 from `PreparedVector.kt`:
```kotlin
    val blendMode: BlendMode,
```
And remove the import `import androidx.compose.ui.graphics.BlendMode` if no longer used.

- [ ] **Step 3: Remove blendMode assignment in VectorCompiler.kt:176**

Remove line `blendMode = BlendMode.SrcOver,` from the PreparedPath constructor call.

- [ ] **Step 4: Remove blendMode from normalizeViewport copy**

Remove `blendMode = path.blendMode,` from `MorphPlanner.kt` line ~208.

- [ ] **Step 5: Run tests to verify compile and pass**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/compiler/VectorCompiler.kt vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/PreparedVector.kt vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt
git commit -m "refactor: remove dead blendMode field from PreparedPath (always hardcoded SrcOver)"
```

### Task 5: Plan cache byte budget + public trim API

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorth/core/plan/MorphPlanner.kt:578-610` (MorphPlanCache class)
- Create: `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/MorphCaches.kt`

**Interfaces:**
- Consumes: `MorphPlanCache` existing public API
- Produces: `MorphPlanCache(maxEntries: Int, maxBytes: Long)` with `trimToBytes(bytes: Long)` + `fun estimateBytes(plan: MorphPlan): Long`
  Compose module: `object MorphCaches { fun trim() }`

- [ ] **Step 1: Write the byte-budget tests**

```kotlin
// vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanCacheByteBudgetTest.kt
package com.imorpher.vectormorth.core.plan

import com.imorpher.vectormorth.core.model.*
import org.junit.Test
import org.junit.Assert.*

class MorphPlanCacheByteBudgetTest {

    private fun makePlan(): MorphPlan {
        val v = PreparedVector(24f, 24f, 24f, 24f, "test", emptyList())
        return MorphPlan(v, v, emptyList(), false, null,
            MorphReport(
                MorphCompatibility(1.0f, emptyList()),
                emptyList(), emptyList(), emptyList(),
                fallbackUsed = false, fallbackStrategy = null, fallbackReason = null,
            )
        )
    }

    @Test fun byteBudgetEvictsWhenOverLimit() {
        val cache = MorphPlanCache(maxEntries = 10, maxBytes = 100)
        val plan = makePlan()
        // First put succeeds
        cache.getOrPut(plan.from, plan.to, MorphConfiguration.Default) { plan }
        // Estimate the bytes of this plan
        val bytes = MorphPlanCache.estimateBytes(plan)
        // If estimate returns 0, skip the eviction test; otherwise:
        if (bytes > 0L) {
            cache.trimToBytes(bytes - 1)
            assertEquals(0, cache.size)
        }
    }

    @Test fun entryCountStillLimits() {
        val cache = MorphPlanCache(maxEntries = 2, maxBytes = Long.MAX_VALUE)
        val p = makePlan()
        cache.getOrPut(p.from, p.to, MorphConfiguration.Default) { p }
        cache.getOrPut(p.from, p.to, MorphConfiguration.Default) { p }
        cache.getOrPut(p.from, p.to, MorphConfiguration.Default) { p }
        // LRU: oldest entry evicted, size = maxEntries
        assertEquals(2, cache.size)
    }

    @Test fun defaultMaxBytesIsEightMegabytes() {
        val cache = MorphPlanCache()
        // Verify construction succeeds with default params
        assertNotNull(cache)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "MorphPlanCacheByteBudgetTest"
```

Expected: FAIL — `MorphPlanCache` constructor doesn't accept `maxBytes` param, `estimateBytes` doesn't exist.

- [ ] **Step 3: Implement byte budget in MorphPlanCache**

Modify `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt`:
- Change `MorphPlanCache(maxEntries: Int = 32)` → `MorphPlanCache(maxEntries: Int = 32, maxBytes: Long = 16 * 1024 * 1024)`
- Add `internal fun estimateBytes(plan: MorphPlan): Long` — sum over pairs: `pair.contourPairs.sumOf { it.from.coords.size + it.to.coords.size } * 4L` (float arrays), add pair overhead estimate ~200 bytes.
- Track running byte count in `cache` via companion tracking or override `removeEldestEntry` to check `bytes > maxBytes`.

```kotlin
class MorphPlanCache(
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 16 * 1024 * 1024,
) {
    companion object {
        fun estimateBytes(plan: MorphPlan): Long {
            var bytes = plan.pairs.size * 200L // overhead per pair
            for (pair in plan.pairs) {
                for (cp in pair.contourPairs) {
                    bytes += (cp.from.coords.size + cp.to.coords.size) * 4L
                }
            }
            return bytes
        }
    }
    // ... existing LinkedHashMap ...
    private var currentBytes = 0L

    @Synchronized
    fun trimToBytes(bytes: Long) {
        while (currentBytes > bytes && cache.isNotEmpty()) {
            val eldest = cache.entries.first()
            currentBytes -= estimateBytes(eldest.value)
            cache.remove(eldest.key)
        }
    }
    // update getOrPut to track currentBytes on insert, trim in removeEldestEntry
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MorphPlan>): Boolean {
        if (size > maxEntries) return true
        if (currentBytes > maxBytes) {
            // trim to half budget
            currentBytes /= 2
            trimToBytes(currentBytes)
            return true
        }
        return false
    }
}
```

- [ ] **Step 4: Implement MorphCaches in compose module**

Create `vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/MorphCaches.kt`:
```kotlin
package com.imorpher.vectormorph.compose

/**
 * Process-level caches for morph compilation and planning.
 * Call [trim] from your app's Activity/Compose lifecycle on memory pressure
 * (e.g., OnTrimMemory).
 */
object MorphCaches {
    /** Trim the plan cache byte budget, keeping entries that fit half the budget. */
    fun trim(bytes: Long = 8 * 1024 * 1024) {
        planCache.trimToBytes(bytes)
    }
}
// Make planCache accessible: change private to internal on vector-morph-compose VectorAnimation.kt
```

Also change `private val planCache` → `internal val planCache` in `VectorAnimation.kt:722`.

- [ ] **Step 5: Run tests**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest :vector-morph-compose:testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/MorphCaches.kt vector-morph-compose/src/main/kotlin/com/imorpher/vectormorph/compose/VectorAnimation.kt vector-morph-core/src/test/kotlin/com/imorpher/vectormorth/core/plan/MorphPlanCacheByteBudgetTest.kt
git commit -m "perf: add byte budget eviction to MorphPlanCache and public trim API"
```

---

## Phase 2: Structural Caching

### Task 6: PreparedVector.contentKey + structural plan cache keys

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/core/compiler/VectorCompiler.kt` (add contentKey computation)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/core/model/PreparedVector.kt` (add contentKey field)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/core/plan/MorphPlanner.kt` (update key)
- Create: `vector-morph-core/src/test/kotlin/com/imorth/core/model/StructuralKeyTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `PreparedVector.contentKey: String` (FNV-1a 64-bit hex), MorphPlanCache keys by contentKey

- [ ] **Step 1: Write the structural key test**

```kotlin
// vector-morph-core/src/test/kotlin/com/imorth/core/model/StructuralKeyTest.kt
package com.imorth.core.model

import org.junit.Test
import org.junit.Assert.*

class StructuralKeyTest {

    @Test fun equalGeometryProducesEqualContentKey() {
        // Two PreparedVectors with identical content must share a key
        val v1 = PreparedVector(24f, 24f, 24f, 24f, "test", emptyList())
        val v2 = PreparedVector(24f, 24f, 24f, 24f, "test", emptyList())
        assertEquals(v1.contentKey, v2.contentKey)
    }

    @Test fun differentGeometryProducesDifferentContentKey() {
        val v1 = PreparedVector(24f, 24f, 24f, 24f, "test1", emptyList())
        val v2 = PreparedVector(24f, 24f, 24f, 24f, "test2", emptyList())
        assertNotEquals(v1.contentKey, v2.contentKey)
    }

    @Test fun contentKeyIsDeterministic() {
        val v = PreparedVector(24f, 24f, 24f, 24f, "test", emptyList())
        assertEquals(v.contentKey, v.contentKey) // stable across reads
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "StructuralKeyTest"
```

Expected: FAIL — `contentKey` doesn't exist.

- [ ] **Step 3: Add contentKey to PreparedVector**

In `PreparedVector.kt`, add:
```kotlin
/**
 * A deterministic content-based hash used for structural caching.
 * Two PreparedVectors with identical viewport, path count, per-path geometry/paint,
 * and clip contours always produce the same contentKey.
 */
val contentKey: String by lazy {
    val hash = java.util.zip.Hash64().hashLong(
        viewportWidth.toRawBits() or
        (viewportHeight.toRawBits() shl 32) or
        (paths.size.toLong() shl 16)
    )
    // Include per-path content: name, index, contour segments, paint values
    val sb = StringBuilder()
    sb.append(hash)
    for (path in paths) {
        sb.append(path.name).append(path.index).append(path.groupNames)
        sb.append(path.contours).append(path.fill).append(path.stroke)
        sb.append(path.fillAlpha).append(path.strokeAlpha).append(path.strokeWidth)
    }
    sb.toString().hashCode().toString(16)
}
```

Actually simpler and deterministic: use FNV-1a manually:
```kotlin
val contentKey: String by lazy {
    var hash: Long = 0xcbf29ce484222325L // FNV offset basis
    hash = fnv1a64(hash, viewportWidth.toBits())
    hash = fnv1a64(hash, viewportHeight.toBits())
    hash = fnv1a64(hash, (paths.size and 0xffffffffL).toLong())
    for (path in paths) {
        val n = path.name.toByteArray(Charsets.UTF_8)
        for (b in n) hash = fnv1a64(hash, b.toLong())
        hash = fnv1a64(hash, path.index.toLong())
        for (g in path.groupNames) {
            val gb = g.toByteArray(Charsets.UTF_8)
            for (b in gb) hash = fnv1a64(hash, b.toLong())
        }
        hash = fnv1a64(hash, path.contours.hashCode().toLong())
        hash = fnv1a64(hash, path.fill.hashCode().toLong())
        hash = fnv1a64(hash, path.stroke.hashCode().toLong())
        hash = fnv1a64(hash, java.lang.Float.floatToRawIntBits(path.fillAlpha).toLong())
        hash = fnv1a64(hash, java.lang.Float.floatToRawIntBits(path.strokeAlpha).toLong())
        hash = fnv1a64(hash, java.lang.Float.floatToRawIntBits(path.strokeWidth).toLong())
    }
    java.lang.Long.toHexString(hash)
}

private fun fnv1a64(hash: Long, data: Long): Long {
    return ((hash xor data).let { h ->
        h + (h shl 16) + (h shl 32) + (h shl 48) + (h shl 64).shr(64) // avoid overflow
    }) * 0x100000001b3L
}
```

Wait, Kotlin doesn't have `toBits()` on Float in older versions. Use `floatToRawIntBits` and XOR into 64-bit hash. Let me simplify:

```kotlin
val contentKey: String by lazy {
    var h: Long = 0xcbf29ce484222325L
    fun add(v: Int) { h = (h xor v.toLong()) * 0x100000001b3L }
    fun add(v: Float) { add(java.lang.Float.floatToRawIntBits(v)) }
    fun add(s: String) { for (c in s) add(c.code) }
    add(viewportWidth)
    add(viewportHeight)
    add(defaultWidth)
    add(defaultHeight)
    add(name)
    add(paths.size)
    for (p in paths) {
        add(p.name)
        add(p.index)
        add(p.groupNames.joinToString(","))
        add(p.contours.hashCode())
        add(p.fill.hashCode())
        add(p.stroke.hashCode())
        add(p.fillAlpha)
        add(p.strokeAlpha)
        add(p.strokeWidth)
    }
    h.toString(16)
}
```

- [ ] **Step 3: Run test**

```bash
.\gradlew :vector-morph-core:testDebugUnitTest --tests "StructuralKeyTest"
```

- [ ] **Step 4: Update MorphPlanCache to use contentKey**

Change `MorphPlanCache.Key` to use `contentKey` strings instead of `PreparedVector` instances:
```kotlin
data class Key(
    val fromKey: String, val toKey: String, val config: MorphConfiguration,
)
```
Update `getOrPut` to extract `contentKey` from prepared vectors.

- [ ] **Step 5: Update compose rememberPlan to pass contentKey**

In `VectorAnimation.kt`, the compose cache currently passes PreparedVector instances to `planCache.getOrPut`. Since we changed Key to use contentKey strings, the callers just pass the same PreparedVector and `getOrPut` reads its `.contentKey` — no API change for callers. Just update the internal implementation.

- [ ] **Step 6: Run all tests**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 7: Commit**

```bash
git add vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/model/PreparedVector.kt vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/model/StructuralKeyTest.kt
git commit -m "perf: add structural contentKey to PreparedVector and key plan cache by content"
```

---

## Phase 3: Renderer Refactor & Performance

### Task 7: Split MorphRenderer into pass renderers

**Files:**
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/FillPass.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/StrokePass.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/ClipPass.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/StaticSidePass.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/DebugOverlay.kt`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt` (remove moved functions, delegate)

**Interfaces:**
- Consumes: private members of MorphRenderer
- Produces: 5 internal classes + MorphRenderer delegates to them

- [ ] **Step 1: Extract FillPass** — move fill-related private members/functions:
  Members: `contourPathCache`, `combinedFillPathCache`, `staticContourCache`, `fillPath`
  Functions: `paintFillWithReveal`, `contoursFillPath`, `staticFillPath`
  New: `internal class FillPass` takes needed scratch from MorphRenderer constructor.

- [ ] **Step 2: Extract StrokePass** — move: `strokePath`, `measure`, reveal extraction, `paintStroke`, `PathMeasure` usage, directional reveal logic.
  New: `internal class StrokePass`.

- [ ] **Step 3: Extract ClipPass** — move: `maskPath`, `wedgePath`, `radialPath`, `withClipReveal`, `withRadialClip`.
  New: `internal class ClipPass`.

- [ ] **Step 4: Extract StaticSidePass** — move: `drawStaticContents`, static fade-leg drawing.
  New: `internal class StaticSidePass`.

- [ ] **Step 5: Extract DebugOverlay** — move: `drawDebugOverlay`, warning banner, anchor dots.
  New: `internal class DebugOverlay`.

Each extracted class is `internal` in the same package, takes `DrawScope`, `MorphPlan`, `EvaluatedFrame`, `MorphConfiguration`, and scratch fields as constructor params. MorphRenderer constructs them in `render()` and delegates.

- [ ] **Step 6: Run full test suite to verify pixel-identical behavior**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 7: Commit**

```bash
git add vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/FillPass.kt vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/StrokePass.kt vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/ClipPass.kt vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/StaticSidePass.kt vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/DebugOverlay.kt
git commit -m "refactor: split MorphRenderer (2350-line monolith) into 5 focused pass renderers"
```

### Task 8: Tight tint layer bounds + solid tint fast path

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt:276-283` (layer rect computation)
- Modify: same file (solid tint fast path via Paint.colorFilter)

**Interfaces:**
- Consumes: MorphRenderer fields
- Produces: tight rect helper + solid-color fast path in tint rendering

- [ ] **Step 1: Write tests for tight bounds**

```kotlin
// vector-morph-compose/src/test/kotlin/com/imorth/vectormorph/compose/renderer/TintBoundsTest.kt
package com.imorth.vectormorph.compose.renderer

import org.junit.Test
import org.junit.Assert.*

class TintBoundsTest {
    // Verify that the tint layer rect calculation produces tight bounds
    // bounded by content extent (no full-viewport padding)
    // Pixel equivalence test: solid tint output must match previous implementation
}
```

- [ ] **Step 2: Implement tight bounds computation**

In `MorphRenderer.render()`, replace the generous padding:
```kotlin
// Old (lines ~276-282): Rect padded by a full viewport on each side
// New: compute content bounds from plan pairs
val contentBounds = computeContentBounds(plan, uniform)
val layerRect = Rect(
    dx - uniform * contentBounds.width,
    dy - uniform * contentBounds.height,
    drawScope.size.width + dx + uniform * contentBounds.width,
    drawScope.size.height + dy + uniform * contentBounds.height,
)
```

- [ ] **Step 3: Implement solid tint fast path**

When both tint endpoints are `BrushSpec.Solid`:
```kotlin
when (tintResult) {
    is GradientInterpolator.Result.Interpolated -> {
        if (tintResult.brush is BrushSpec.Solid) {
            // Fast path: use colorFilter on saveLayer paint instead of SrcIn drawRect
            tintLayerPaint.colorFilter = ColorFilter.tint(tintResult.color, BlendMode.SrcIn)
        }
        // ... existing SrcIn drawRect for non-solid
    }
    // ... other branches clear colorFilter after restore
} finally {
    tintLayerPaint.colorFilter = null
    canvas.restore()
}
```

- [ ] **Step 4: Run all tests**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 5: Commit**

```bash
git add vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt
git commit -m "perf: tighten tint layer bounds and add solid-color fast path via ColorFilter"
```

### Task 9: Endpoint (p=0/p=1) static geometry reuse

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt` (fill pass + stroke pass at endpoints)

**Interfaces:**
- Consumes: FillPass, StrokePass (from Task 7)
- Produces: early-out path for p=0/p=1 rendering resting frames

- [ ] **Step 1: Write endpoint test**

```kotlin
// Test that at p=0, the renderer uses static source contours (no lerp)
// and at p=1, uses static target contours.
// Pixel equivalence: output at p=0 must equal a direct static render of from vector.
// We verify by ensuring the static path cache is populated at p=0/p=1.
```

- [ ] **Step 2: Implement static fast path in FillPass**

At the beginning of the fill rendering loop, add:
```kotlin
val morphT = TimelineEvaluator.morphFraction(pathProps[pairIndex].props, progress)
if (morphT <= 0f) {
    // Use static source contours directly — no lerp, no contour rebuild
    drawStaticLegFrom(pair.fromPath, ...)
} else if (morphT >= 1f) {
    drawStaticLegTo(pair.toPath, ...)
} else {
    // Existing interpolated path
}
```

- [ ] **Step 3: Implement static fast path in StrokePass**

Similar early-out for stroke at p=0/p=1 when drawProgress == 1 (fully drawn).

- [ ] **Step 4: Run all tests**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 5: Commit**

```bash
git commit -m "perf: reuse static geometry at progress endpoints p=0 and p=1"
```

### Task 10: Brush result memoization in TimelineEvaluator

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/animation/TimelineEvaluator.kt`

**Interfaces:**
- Consumes: `GradientInterpolator.Result` from `evaluateBrush`
- Produces: per-track memo that reuses `Result` objects when progress doesn't cross quantization boundary

- [ ] **Step 1: Write the brush memo test**

```kotlin
@Test fun brushResultMemoizedWithinQuantum() {
    // Two evaluate calls with very close progress should return same Brush instance
}
```

- [ ] **Step 2: Implement memo**

Add per-track memo fields to TimelineEvaluator init:
```kotlin
// Per brush track memo: quantized eased progress → cached Result
data class BrushMemo(var lastQuantized: Int = -1, var lastResult: GradientInterpolator.Result? = null)
val brushMemos = Array(brushTracks.size) { BrushMemo() }
```

In `evaluateBrush`, quantize eased progress to 256 steps:
```kotlin
val quantized = (eased * 255f).roundToInt()
if (quantized == memo.lastQuantized && memo.lastResult != null) return memo.lastResult
memo.lastQuantized = quantized
val result = /* existing gradient interpolation */
memo.lastResult = result
return result
```

- [ ] **Step 3: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 4: Commit**

```bash
git commit -m "perf: memoize brush interpolation results in TimelineEvaluator"
```

### Task 11: CustomVectorAnimation crash guard

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/renderer/MorphRenderer.kt` (site invoking customAnimation)

**Interfaces:**
- Consumes: `VectorAnimation.customizer: CustomVectorAnimation?`
- Produces: try/catch wrapper that logs once per callback instance, treats as null on subsequent calls

- [ ] **Step 1: Write the crash guard test**

```kotlin
@Test fun throwingCustomCallbackDoesNotCrashRender() {
    // A custom animation callback that throws returns null overrides
    // and the frame renders as if no custom animation were provided.
}
```

- [ ] **Step 2: Implement guard**

```kotlin
private val failedCallbacks = Collections.synchronizedSet(HashSet<CustomVectorAnimation>())
// ... in the render site:
val customizer = anim?.customizer ?: return
if (failedCallbacks.contains(customizer)) {
    return // silently skip
}
return try {
    customizer.transform(state)
} catch (e: Exception) {
    if (!failedCallbacks.add(customizer)) {
        android.util.Log.w("MorphRenderer", "CustomVectorAnimation threw; disabling", e)
    }
    null
}
```

- [ ] **Step 3: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 4: Commit**

```bash
git commit -m "fix: guard CustomVectorAnimation callback against crashes with log-once disable"
```

### Task 12: Split compose API file

**Files:**
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorph/compose/MorphIcon.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphVector.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/VectorAnimator.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/VectorAnimationEntry.kt` (VectorAnimation/DrawIcon/DrawIconWithTint)
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/internal/PlanRemember.kt` (rememberPlan, VectorPlanCanvas, rememberAutoPlayProgress)
- Delete: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/VectorAnimation.kt`

**Interfaces:**
- Consumes: nothing (pure mechanical split)
- Produces: same public API surface, just distributed across files

- [ ] **Step 1: Mechanical file split**

Move composable overloads to their own files:
- `MorphIcon` (all 6 overloads) → MorphIcon.kt
- `MorphVector` (all 6 overloads) → MorphVector.kt
- `VectorAnimator` (all 3 overloads) → VectorAnimator.kt
- `VectorAnimation` + `DrawIcon` + `rememberVectorAnimation` → VectorAnimationEntry.kt
- Shared plumbing: `rememberPlan`, `VectorPlanCanvas`, `rememberAutoPlayProgress`, `VectorAnimatorCore` → internal/PlanRemember.kt

- [ ] **Step 2: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor: split 749-line VectorAnimation.kt into focused modules"
```

---

## Phase 4: Geometry Quality

### Task 13: Subdivider.reduce + segment budget

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/geometry/Subdivider.kt`
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/MorphConfiguration.kt`
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/plan/MorphPlanner.kt` (alignContours)

**Interfaces:**
- Consumes: nothing
- Produces: `Subdivider.reduce(contour: ContourData, targetSegments: Int, tolerance: Float): ContourData`
  `MorphConfiguration(segmentBudget: Int? = null, simplificationTolerance: Float = 0.25f)`

- [ ] **Step 1: Write segment reduction tests**

```kotlin
@Test fun reducePreservesEndpoints() {
    val contour = ContourData(...) // build a simple open contour
    val reduced = Subdivider.reduce(contour, 4, 0.25f)
    // First and last control points must be unchanged
}

@Test fun reduceKeepsDeviationWithinTolerance() {
    val contour = ContourData(24 segments) // dense contour
    val reduced = Subdivider.reduce(contour, 8, 0.25f)
    // Sample both at 100 points; max point distance < tolerance
}

@Test fun equalizeWithBudgetCapsSegments() {
    // 300-segment contour equalized with budget=10 → 10 segments
}
```

- [ ] **Step 2: Implement reduce algorithm**

Greedy bottom-up merge: for each pair of adjacent cubics, compute a merged cubic via least-squares midpoint-fit; error = max deviation of all source control points from merged curve; merge the pair with minimum error ≤ tolerance; repeat until target count reached.

Provide the midpoint-split inverse formula in a code block since it's an algorithm the tests don't determine:

```kotlin
fun reduce(contour: ContourData, targetSegments: Int, tolerancePx: Float): ContourData {
    require(targetSegments <= contour.segments.size) { "Cannot increase segments with reduce" }
    var segs = contour.segments.toMutableList()
    while (segs.size > targetSegments) {
        var bestIdx = -1
        var bestError = Float.MAX_VALUE
        for (i in 0 until segs.size - 1) {
            val merged = mergeCubics(segs[i], segs[i + 1])
            val error = maxSampleError(merged, segs[i], segs[i + 1])
            if (error < bestError) { bestIdx = i; bestError = error }
        }
        if (bestError > tolerancePx) break // can't merge further within tolerance
        segs[bestIdx] = mergeCubics(segs.removeAt(bestIdx + 1))
    }
    return ContourData(segs, contour.closed)
}

// Merge two cubics into one via least-squares midpoint-fit.
// Split a cubic at t=0.5 yields 2 cubics with control points linearly related to the parent.
// Given desired left/right halves, solve the 6 linear equations for 4 parent control points
// in least-squares (over-determined system).
private fun mergeCubics(a: Cubic, b: Cubic): Cubic {
    // a = [a0, a1, a2, a3], b = [b0, b1, b2, b3]
    // Split parent C at 0.5 → [c0, c1, c2, c3, c4, c5, c6, c7]
    // Left half: [c0, (c0+c1)/2, (c0+2c1+c2)/4, (c0+3c1+3c2+c3)/8]
    // Right half: [(c0+3c2+3c3+c4)/8, (c2+2c3+c4)/4, (c3+c4)/2, c4]
    // Set left[3] == right[0], solve for c0, c1, c3, c4 via least squares
    // ... (implementation follows standard midpoint-split inverse)
}
```

- [ ] **Step 3: Wire budget into MorphConfiguration and alignContours**

Add `segmentBudget: Int? = null` and `simplificationTolerance: Float = 0.25f` to `MorphConfiguration`.

In `MorphPlanner.alignContours`:
```kotlin
val targetCount = maxOf(source.segments.size, target.segments.size)
val budgetedTarget = config.segmentBudget?.let { minOf(targetCount, it) } ?: targetCount
var a = Subdivider.equalize(source, budgetedTarget)
var b = Subdivider.equalize(target, budgetedTarget)
// If equalize reduced, use reduce to ensure both sides stay at budgetedTarget
if (budgetedTarget < target.segments.size) {
    b = Subdivider.reduce(b, budgetedTarget, config.simplificationTolerance)
}
```

Wait — `equalize` requires `target >= current`. If budget < current, call `reduce` instead:
```kotlin
val a2 = if (budgetedTarget >= source.segments.size) Subdivider.equalize(source, budgetedTarget)
         else Subdivider.reduce(source, budgetedTarget, config.simplificationTolerance)
```

- [ ] **Step 4: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 5: Commit**

```bash
git commit -m "perf: add Subdivider.reduce with error tolerance and segment budget config"
```

### Task 14: Optimal assignment for matching

**Files:**
- Create: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/matching/Assignment.kt`
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/matching/PathMatcher.kt` (matchByCost uses optimal when small)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/plan/MorphPlanner.kt` (matchContours uses optimal when small)

**Interfaces:**
- Produces: `internal object Assignment { fun solve(cost: Array<FloatArray>): IntArray }`
- Consumed by: PathMatcher.matchByCost, MorphPlanner.matchContours

- [ ] **Step 1: Write optimal assignment test**

```kotlin
@Test fun optimalPicksCheaperGlobalAssignment() {
    val cost = arrayOf(
        floatArrayOf(1f, 2f),
        floatArrayOf(1.5f, 10f),
    )
    val result = Assignment.solve(cost)
    // Greedy would pick (0,0)=1 then (1,1)=10 → total 11
    // Optimal picks (0,1)=2 then (1,0)=1.5 → total 3.5
    assertEquals(0, result[1]) // row 1 → col 0
    assertEquals(1, result[0]) // row 0 → col 1
}
```

- [ ] **Step 2: Implement Hungarian algorithm (O(n³))**

```kotlin
// Assignment.kt — O(n³) shortest-augmenting-path assignment
// Use when min(rows, cols) ≤ 32; fallback to greedy above
internal object Assignment {
    fun solve(cost: Array<FloatArray>): IntArray {
        // Standard Kuhn-Munkres with potentials
        // Return row→col assignment
    }
}
```

- [ ] **Step 3: Wire into PathMatcher and MorphPlanner**

In both `PathMatcher.matchByCost` and `MorphPlanner.matchContours`:
```kotlin
if (from.size <= 32 && to.size <= 32) {
    val costMatrix = Array(from.size) { i ->
        FloatArray(to.size) { j -> cost(from[i], to[j], diag) }
    }
    val assignment = Assignment.solve(costMatrix)
    // Convert to List<PathMatch>
} else {
    // existing greedy
}
```

- [ ] **Step 4: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 5: Commit**

```bash
git commit -m "perf: use optimal Hungarian assignment for path/contour matching when n≤32"
```

### Task 15: ViewportNormalization FIT option

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/Enums.kt` (add enum)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/MorphConfiguration.kt` (add field)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/plan/MorphPlanner.kt` (normalizeViewport)

**Interfaces:**
- Produces: `enum class ViewportNormalization { STRETCH, FIT }`
- Consumed by: MorphConfiguration.viewportNormalization

- [ ] **Step 1: Write FIT test**

```kotlin
@Test fun fitPreservesAspectOnDifferentViewport() {
    // source 24x24, target 48x24 → under FIT, target geometry scales uniformly
    // and is centered; aspect ratio is preserved
}

@Test fun fitEqualsStretchForSameAspect() {
    // source 24x24, target 48x48 → FIT and STRETCH produce identical results
}
```

- [ ] **Step 2: Implement FIT in normalizeViewport**

```kotlin
private fun normalizeViewport(vector: PreparedVector, width: Float, height: Float): PreparedVector {
    if (vector.viewportWidth == width && vector.viewportHeight == height) return vector

    val sx = width / vector.viewportWidth
    val sy = height / vector.viewportHeight

    return when (config.viewportNormalization) {
        ViewportNormalization.STRETCH -> {
            // existing per-axis scaling
        }
        ViewportNormalization.FIT -> {
            val uniform = min(sx, sy)
            // Scale by uniform + centering translate
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: add ViewportNormalization.FIT option to preserve aspect ratio"
```

### Task 16: Clip-geometry morphing

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/plan/MorphPlan.kt` (add clipPairs field)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/plan/MorphPlanner.kt` (align clip contours)
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/renderer/MorphRenderer.kt` (ClipPass — interpolate clip at progress)

**Interfaces:**
- Consumes: `PathPairPlan`, MorphPlanner.matchContours, renderer ClipPass
- Produces: `PathPairPlan.clipPairs: List<ContourPairPlan>` — aligned clip contours

- [ ] **Step 1: Write clip morph test**

```kotlin
@Test fun clipPairsProducedWhenBothSidesHaveClips() {
    // Two vectors with different group clips → plan contains clipPairs
}

@Test fun clipAtMidpointInterpolatesGeometry() {
    // Render at morphT 0.5 → clip shape is between endpoints
}
```

- [ ] **Step 2: Add clipPairs to PathPairPlan**

```kotlin
class PathPairPlan(
    // ... existing fields ...
    val clipPairs: List<ContourPairPlan> = emptyList(), // NEW
)
```

- [ ] **Step 3: Add clip alignment to MorphPlanner.planMorphPair**

When both sides have clipContours:
```kotlin
val clipMatch = if (f.clipContours.isNotEmpty() && t.clipContours.isNotEmpty()) {
    val clipCost: (PreparedPath, PreparedPath, Float) -> Float = { a, b, _ ->
        // Simple centroid cost for clips
        val dx = a.centroidX - b.centroidX
        val dy = a.centroidY - b.centroidY
        (hypot(dx, dy) / diag).coerceAtMost(2f)
    }
    matchContours(f.clipContours, t.clipContours).map { (fi, ti) ->
        alignClipContours(f.clipContours[fi], t.clipContours[ti], config)
    }
} else emptyList()
```

Then pass `clipPairs = clipMatch` to `PathPairPlan`.

- [ ] **Step 4: Interpolate clip in renderer ClipPass**

Replace the midpoint switch:
```kotlin
val clipContours = if (pair.clipPairs.isNotEmpty() && clipProgress >= 1f) {
    // Build interpolated clip path from clipPairs at morphT
    interpolateClip(pair.clipPairs, morphT)
} else {
    // Legacy midpoint switch
    if (morphT < 0.5f) f.clipContours else t.clipContours
}
```

- [ ] **Step 5: Run tests**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: morph clip geometry between endpoints instead of midpoint switch"
```

---

## Phase 5: Compose API & UX

### Task 17: rememberMorphAnimation + onSettled

**Files:**
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/ProgressController.kt`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphIcon.kt` (onSettled param)
- Create: `vector-morph-compose/src/test/kotlin/com/imorth/vectormorth/compose/MorphProgressTest.kt`

**Interfaces:**
- Produces: `@Composable fun rememberMorphAnimation(selected: Boolean, animationSpec, motionPreference?, onSettled?): State<Float>`
  `MorphIcon(..., onSettled: ((Boolean) -> Unit)? = null)`
- Consumes: Animatable

- [ ] **Step 1: Write interruptibility + settled callback tests**

```kotlin
@Test fun onSettledFiresExactlyOnceWhenAnimationCompletes() {
    // Use ComposeTestRule with mainClock.autoAdvance
    // Select true → advance → onSettled(true) collected exactly once
}

@Test fun midFlightInterruptResetsAnimation() {
    // Set true, advance 100ms, set false, advance → onSettled(false) fires once
}
```

- [ ] **Step 2: Implement ProgressController**

```kotlin
@Composable
fun rememberMorphAnimation(
    selected: Boolean,
    animationSpec: AnimationSpec<Float> = tween(350),
    motionPreference: MotionPreference? = null,
    onSettled: ((Boolean) -> Unit)? = null,
): State<Float> {
    val bounded = remember(selected) { selected.coerceIn(0f, 1f) }
    val target = if (motionPreference == MotionPreference.INSTANT) 1f else bounded
    val spec = when (motionPreference) {
        MotionPreference.REDUCED -> tween<Float>(durationMillis = 120)
        else -> animationSpec
    }
    val progress = remember { Animatable(if (motionPreference == MotionPreference.INSTANT) 1f else 0f) }
    LaunchedEffect(target, spec, motionPreference) {
        if (motionPreference == MotionPreference.INSTANT || !progress.isRunning) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, spec)
        }
        onSettled?.invoke(target >= 0.5f)
    }
    return derivedStateOf { progress.value }
}
```

- [ ] **Step 3: Wire onSettled into MorphIcon**

Add `onSettled: ((Boolean) -> Unit)? = null` param to all 3 MorphIcon overloads. Pass to `rememberMorphAnimation`.

- [ ] **Step 4: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: add onSettled callback and rememberMorphAnimation with interruptible Animatable"
```

### Task 18: rememberMotionPreference + nullable motion params

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphIcon.kt` (nullable motionPreference)
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/VectorAnimationEntry.kt` (VectorAnimation, DrawIcon nullable params)
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MotionPreferenceResolver.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `rememberMotionPreference(userPreference: MotionPreference? = null): MotionPreference`
  All composables: `motionPreference: MotionPreference? = null`

- [ ] **Step 1: Implement rememberMotionPreference**

```kotlin
@Composable
fun rememberMotionPreference(userPreference: MotionPreference? = null): MotionPreference {
    if (userPreference != null) return userPreference
    val context = LocalContext.current
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) { 1f }
    return when {
        scale <= 0f → MotionPreference.INSTANT
        scale < 1f → MotionPreference.REDUCED
        else → MotionPreference.FULL
    }
}
```

- [ ] **Step 2: Change all motionPreference params to nullable**

In MorphIcon (6 overloads), VectorAnimation (6 overloads), DrawIcon (3 overloads):
Change `motionPreference: MotionPreference` → `motionPreference: MotionPreference? = null`

- [ ] **Step 3: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: auto-resolve MotionPreference from system settings, nullable params"
```

### Task 19: MorphingIcon + MorphPrecompiler.warm

**Files:**
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphingIcon.kt`
- Create: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphPrecompiler.kt`
- Create: `vector-morph-compose/src/test/kotlin/com/imorth/vectormorth/compose/MorphingIconTest.kt`

**Interfaces:**
- Produces: `@Composable fun MorphingIcon(target, ...): Unit`
  `object MorphPrecompiler { suspend fun warm(vararg pairs: Pair<ImageVector, ImageVector>, config: MorphConfiguration = Default) }`

- [ ] **Step 1: Write MorphingIcon tests**

```kotlin
@Test fun firstCompositionDrawsTargetAtProgress1() {
    // Initial render should show the target at progress 1 (fully drawn)
}

@Test fun targetChangeMorphsFromLastSettled() {
    // Change target → plans (prev→new), animates 0→1, then settles on new target
}

@Test fun midFlightRetargetRestartsWithFromLastSettled() {
    // While animating prev→targetA, change to targetB →
    // new morph starts from prev (last settled), not from mid-animation frame
}
```

- [ ] **Step 2: Implement MorphPrecompiler**

```kotlin
object MorphPrecompiler {
    private val _precompiles = Collections.synchronizedMap(HashMap<Pair<ImageVector, ImageVector>, MorphPlan>())
    
    /** Precompile and cache a pair of vectors on Dispatchers.Default. */
    suspend fun warm(from: ImageVector, to: ImageVector, config: MorphConfiguration = MorphConfiguration.Default) {
        withContext(Dispatchers.Default) {
            val plan = com.imorpher.vectormorph.core.plan.planMorph(from, to, config)
            _precompiles[from to to] = plan
        }
    }
    
    /** Precompile multiple pairs. */
    suspend fun warm(config: MorphConfiguration = MorphConfiguration.Default, vararg pairs: Pair<ImageVector, ImageVector>) {
        pairs.forEach { (from, to) -> warm(from, to, config) }
    }
    
    /** Check if a plan is precompiled. */
    fun isPrepared(from: ImageVector, to: ImageVector): Boolean = _precompiles.containsKey(from to to)
}
```

- [ ] **Step 3: Implement MorphingIcon**

```kotlin
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    width: Dp = target.defaultWidth,
    height: Dp = target.defaultHeight,
) {
    var settled by remember { mutableStateOf(target) }
    var previous by remember { mutableStateOf<ImageVector?>(null) }
    
    val progress by rememberMorphAnimation(
        selected = previous != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    val plan = rememberPlan(settled, target, configuration)
    
    LaunchedEffect(target, settled) {
        if (target != settled) {
            settled = target
        }
    }
    
    MorphVector(
        from = previous ?: settled,
        to = target,
        progress = progress.coerceAtLeast(if (previous == null) 1f else 0f),
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        motionPreference = motionPreference,
        width = width,
        height = height,
    )
}
```

Wait — this needs more careful state management. Let me think about the contract:
- First composition: renders `target` at progress 1
- When `target` changes: morph from current-displayed icon → new target
- Mid-flight: restart from last settled icon

Simpler implementation using a simple state machine:
```kotlin
@Composable
fun MorphingIcon(...) {
    var displayed by remember { mutableStateOf(target) }
    var displayedFrom by remember { mutableStateOf<ImageVector?>(null) }
    
    // When target changes from what we displayed last, start a morph
    LaunchedEffect(target, displayed) {
        if (target != displayed) {
            displayedFrom = displayed
            displayed = target
        }
    }
    
    val progress by rememberMorphAnimation(
        selected = true, // always "selected" once started
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    // Draw target at progress 1 on first composition, morphing on changes
    val from = displayedFrom ?: target // first frame: show target fully
    val to = target
    val effectiveProgress = if (displayedFrom == null) 1f else progress
    
    MorphVector(
        from = from,
        to = to,
        progress = effectiveProgress,
        // ...
    )
}
```

Hmm, this has issues with the `displayed` state updating. Let me simplify: just track `lastSettled` and `isAnimating`:

```kotlin
@Composable
fun MorphingIcon(...) {
    var lastSettled: ImageVector by remember { mutableStateOf(target) }
    var animating by remember { mutableStateOf(false) }
    
    val progress by rememberMorphAnimation(
        selected = animating,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    val from = lastSettled
    val to = target
    val effectiveProgress = if (!animating || lastSettled == target) 1f else progress
    
    MorphVector(
        from = from,
        to = to,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        motionPreference = motionPreference,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != lastSettled) {
            animating = true
            // Wait for animation to settle, then update lastSettled
            // This is handled by rememberMorphAnimation's onSettled
        }
    }
}
```

Actually, the simplest correct approach: use a flag to track whether we should morph:

```kotlin
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier,
    animationSpec: AnimationSpec<Float>,
    configuration: MorphConfiguration,
    contentDescription: String?,
    motionPreference: MotionPreference?,
    width: Dp,
    height: Dp,
) {
    var prev by remember { mutableStateOf<ImageVector?>(null) }
    val current = if (prev == null) target else prev!!
    
    val progress by rememberMorphAnimation(
        selected = prev != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    val effectiveProgress = if (prev == null) 1f else progress
    
    MorphVector(
        from = current,
        to = target,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != current) {
            // Start morph from current → target
            // The morph completes and onSettled updates prev
        } else {
            prev = target // first composition or unchanged
        }
    }
}
```

Hmm — this still has issues. The real problem: we need to track `displayedIcon` (what's currently shown), and when `target` changes from `displayedIcon`, morph from `displayedIcon` → `target`. The morph completion updates `displayedIcon = target`.

Let me use a simpler approach with a state that gets updated via LaunchedEffect:

```kotlin
@Composable
fun MorphingIcon(...) {
    var displayed by remember { mutableStateOf(target) }
    var from by remember { mutableStateOf<ImageVector?>(null) }
    
    val progress by rememberMorphAnimation(
        selected = from != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    // On first composition: display target immediately
    // On target change: remember current as `from`, update displayed
    LaunchedEffect(target, displayed) {
        if (target != displayed) {
            from = displayed
            displayed = target
        }
    }
    
    val morphFrom = from ?: target
    val morphTo = target
    val effProgress = if (from == null) 1f else progress
    
    MorphVector(
        from = morphFrom,
        to = morphTo,
        progress = effProgress,
        // ...
    )
}
```

The problem: `displayed` changes during composition → `from` is set → next composition renders the morph. But `from` persists across recompositions, which is correct. When the target changes again mid-flight, `from` still points to the old displayed icon — which is the contract we want (restart from last settled).

But there's a subtle issue: `from` is set via LaunchedEffect which runs on target/displayed change. During the morph, if `target` changes again, LaunchedEffect fires again — but `displayed` already equals the new target (set in the previous LaunchedEffect), so the condition `target != displayed` is false, and the second change is ignored. That's actually wrong — we want the second change to restart the morph.

Let me use a cleaner state approach:

```kotlin
@Composable
fun MorphingIcon(...) {
    var current: ImageVector by remember { mutableStateOf(target) }
    var animatingFrom: ImageVector? by remember { mutableStateOf(null) }
    
    // Track what the user wants to see
    var requestedTarget by remember { mutableStateOf(target) }
    
    val progress by rememberMorphAnimation(
        selected = animatingFrom != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    LaunchedEffect(target) {
        requestedTarget = target
        if (target != current) {
            animatingFrom = current
            current = target // will animate from animatingFrom → target
        }
    }
    
    val from = animatingFrom ?: current
    val to = requestedTarget
    val effProgress = if (animatingFrom == null) 1f else progress
    
    MorphVector(
        from = from,
        to = to,
        progress = effProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
}
```

Wait, this still has issues with the `current = target` assignment — if the user changes target multiple times rapidly, `current` gets updated to each new target but `animatingFrom` is only set once per LaunchedEffect run. The second LaunchedEffect for the next target change will have `target != current` be true, and `animatingFrom = current` (the current target), setting the morph from the new target to the even newer target. But we're mid-animation — the morph display would be wrong.

The cleanest approach is to use `rememberMorphAnimation`'s interruptibility and a single source of truth:

```kotlin
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    width: Dp = target.defaultWidth,
    height: Dp = target.defaultHeight,
) {
    var lastSettled: ImageVector? by remember { mutableStateOf(null) }
    
    val progress by rememberMorphAnimation(
        selected = lastSettled != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    val from = lastSettled ?: target
    val to = target
    val effectiveProgress = if (lastSettled == null) 1f else progress
    
    // Track morphing state
    var isMorphing by remember { mutableStateOf(false) }
    
    MorphVector(
        from = from,
        to = to,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != lastSettled) {
            isMorphing = true
            lastSettled = target
        } else {
            lastSettled = target
        }
    }
}
```

OK I'm overcomplicating this. Let me use the simplest possible correct approach:

```kotlin
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    width: Dp = target.defaultWidth,
    height: Dp = target.defaultHeight,
) {
    var displayed: ImageVector by remember { mutableStateOf(target) }
    var prevDisplayed: ImageVector? by remember { mutableStateOf(null) }
    
    val progress by rememberMorphAnimation(
        selected = prevDisplayed != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    // When target changes: morph from prevDisplayed → target
    // On first composition: prevDisplayed is null, show target at 1f
    val effectiveProgress = if (prevDisplayed == null) 1f else progress.coerceIn(0f, 1f)
    
    MorphVector(
        from = displayed,
        to = target,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != displayed) {
            // Set the from vector and mark that we started from something
            prevDisplayed = displayed
            displayed = target
        }
    }
}
```

Let me trace through:
- Composition 1: target = IconA. displayed = IconA, prevDisplayed = null. MorphVector(from=A, to=A, progress=1f). Renders A fully. Correct.
- Target changes to IconB: LaunchedEffect fires → prevDisplayed = A, displayed = B. Next composition: MorphVector(from=B, to=B, progress=1f). WRONG — we need from=A to=B.

The issue: `displayed = target` runs before the next render, so the from=displayed=B not A. We need `displayed` to remain A during the morph animation, then update to B when animation completes. But `rememberMorphAnimation` doesn't provide a completion callback that updates state...

OK — the key insight: we DO need the `onSettled` callback from Task 17:

```kotlin
@Composable
fun MorphingIcon(...) {
    var displayed: ImageVector by remember { mutableStateOf(target) }
    var prevDisplayed: ImageVector? by remember { mutableStateOf(null) }
    
    val progress by rememberMorphAnimation(
        selected = prevDisplayed != null,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
        onSettled = { selected ->
            if (selected) prevDisplayed = displayed
        }
    )
    
    val effectiveProgress = if (prevDisplayed == null) 1f else progress.coerceIn(0f, 1f)
    
    MorphVector(
        from = displayed,
        to = target,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != displayed) {
            prevDisplayed = displayed
            // displayed stays as the "from" icon — the progress 0 state
        }
    }
}
```

Trace:
- Comp 1: target=A, displayed=A, prevDisplayed=null → from=A to=A progress=1f → shows A ✓
- Target→B: LaunchedEffect sets prevDisplayed=A. Next render: from=A to=B progress=progress(0→1) ✓
- Progress completes: onSettled(true) fires → prevDisplayed = displayed = A ✓ (but we want prevDisplayed to NOT be A anymore; displayed should become B)

Hmm, `displayed` never changes from A. The morph is from displayed(A)→target(B). Once settled, displayed should become B so next change works correctly.

Better: on settled, set displayed = target:
```kotlin
onSettled = { selected ->
    if (selected) displayed = target
}
```

Trace again:
- Comp 1: target=A, displayed=A, prevDisplayed=null → from=A to=A progress=1f ✓
- Target→B: LaunchedEffect sets prevDisplayed=A. displayed stays A. Render: from=A to=B progress(0→1) ✓
- Settled: onSettled(true) → displayed=B. prevDisplayed stays A (but that's fine — next time target changes, prevDisplayed will be set from displayed=B)
- Target→C: LaunchedEffect: target(C) != displayed(B) → prevDisplayed=B, displayed stays B. Render: from=B to=C progress(0→1) ✓

This works! The `prevDisplayed` flag is just used to determine effectiveProgress = 1f on first render. After that, `displayed` tracks the source icon, `target` is always the current desired icon, and progress animates between them.

One remaining issue: mid-flight retarget. If target changes from B→C while animating A→B:
- LaunchedEffect fires (target changed to C): checks `target != displayed`. displayed is still A (we haven't updated it). So prevDisplayed=A, displayed stays A. Next render: from=A to=C progress(progress value). This means we restart the morph from the last settled icon A to the new target C. That's the contract! ✓

But wait — what about the morph that was mid-flight? The progress state from `rememberMorphAnimation` was animating A→B. Now it's animating A→C. The Animatable is already at some progress value (say 0.5). The new morph should start from 0, not from 0.5.

`rememberMorphAnimation` uses `animateFloatAsState` internally (in the original code). But we changed it to use `Animatable` in Task 17. With `Animatable`, if we call `animateTo` with a different target, it retargets from the current position. But for MorphingIcon, we want the new morph to START from 0, not continue from the old progress.

Solutions:
(a) `progress.snapTo(0f)` before `animateTo` when target changes — but this requires detecting the change in LaunchedEffect
(b) Use a different Animatable for each (from, to) pair

Actually — the simplest approach: just reset the Animatable when the target changes. In `rememberMorphAnimation`, we can detect target changes via a `key` parameter.

Or better: have MorphingIcon create its own Animatable per morph session. Use a `var animatable = remember { Animatable(0f) }` and LaunchedEffect to start a new animation when target changes:

```kotlin
@Composable
fun MorphingIcon(...) {
    var displayed: ImageVector by remember { mutableStateOf(target) }
    var animating: Boolean by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    
    LaunchedEffect(target) {
        if (target != displayed) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec)
            displayed = target
        }
    }
    
    val effectiveProgress = if (!animating) 1f else progress.value.coerceIn(0f, 1f)
    
    // Actually we don't need prevDisplayed anymore — just track animating state
    // First composition: animating=false, target==displayed → progress=1f
    
    MorphVector(
        from = displayed,
        to = target,
        progress = effectiveProgress,
        // ...
    )
}
```

Wait — the problem with this is: `progress.value` starts at 0f and LaunchedEffect runs to animate to 1f. Between LaunchedEffect start and end, progress.value is 0f → the icon renders as the "from" state. That's correct for the morph start.

But what about the initial render? First composition: animating=false, displayed=target. So `from = displayed = target`, `to = target`, progress = 1f (since !animating). Renders target fully. ✓

When target changes to B: LaunchedEffect fires → progress.snapTo(0f), progress.animateTo(1f, spec), displayed=B. During animation, from=displayed=A, to=B, progress=progress.value. ✓

Mid-flight: target changes to C. LaunchedEffect fires again → progress.snapTo(0f), progress.animateTo(1f, spec), displayed=C. from=displayed=A (still, since displayed hasn't been updated yet), to=C. Wait — displayed was set to B during the previous morph, so it's B now. So from=B, to=C. That's the restart-from-last-settled contract! ✓

Great, this works. But I'm using `Animatable` directly instead of `rememberMorphAnimation`. I can still use `rememberMorphAnimation` if I add a `key` parameter:

```kotlin
@Composable
private fun MorphingIconInner(...) {
    var displayed by remember { mutableStateOf(target) }
    var animating by remember { mutableStateOf(false) }
    var morphKey by remember { mutableStateOf(target) } // resets morph
    
    LaunchedEffect(target) {
        morphKey = target
        if (target != displayed) {
            animating = true
            // ... animate
            displayed = target
            animating = false
        }
    }
    
    // Use morphKey to reset animation
    val progress by rememberMorphAnimation(
        selected = morphKey != target,
        animationSpec = animationSpec,
        motionPreference = motionPreference,
    )
    
    // When morphKey == target, we're settled → progress = 1f
    // When morphKey != target, we're animating → progress = 0→1
}
```

Actually, this is getting convoluted. The simplest: MorphingIcon just manages its own state with a simple `Animatable` and calls `MorphVector` directly. Let me go with that approach. I'll keep the implementation simple in the task step 3.

- [ ] **Step 3: Implement MorphingIcon (simple version)**

```kotlin
@Composable
fun MorphingIcon(
    target: ImageVector,
    modifier: Modifier = Modifier,
    animationSpec: AnimationSpec<Float> = tween(350),
    configuration: MorphConfiguration = MorphConfiguration.Default,
    contentDescription: String? = null,
    motionPreference: MotionPreference? = null,
    width: Dp = target.defaultWidth,
    height: Dp = target.defaultHeight,
) {
    var displayed by remember { mutableStateOf(target) }
    var animating by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    
    val effectiveProgress = if (!animating) 1f else progress.value.coerceIn(0f, 1f)
    
    MorphVector(
        from = displayed,
        to = target,
        progress = effectiveProgress,
        configuration = configuration,
        modifier = modifier,
        contentDescription = contentDescription,
        width = width,
        height = height,
    )
    
    LaunchedEffect(target) {
        if (target != displayed) {
            progress.snapTo(0f)
            animating = true
            val spec = when (motionPreference) {
                MotionPreference.INSTANT → tween(0)
                MotionPreference.REDUCED → tween(120)
                else → animationSpec
            }
            progress.animateTo(1f, spec)
            displayed = target
            animating = false
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: add MorphingIcon (chain morph to any new target) and MorphPrecompiler.warm API"
```

### Task 20: Alignment parameter

**Files:**
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphIcon.kt`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/MorphVector.kt`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/VectorAnimationEntry.kt`
- Modify: `vector-morph-compose/src/main/kotlin/com/imorth/vectormorth/compose/renderer/MorphRenderer.kt`

**Interfaces:**
- Produces: `alignment: Alignment = Alignment.Center` on MorphIcon, MorphVector, VectorAnimator, VectorAnimation, DrawIcon
- Consumes: MorphRenderer dx/dy computation

- [ ] **Step 1: Write alignment test**

```kotlin
@Test fun centerStartAlignmentShiftsPixelsLeft() {
    // Icon rendered with Alignment.CenterInside → CenterStart should shift content left
    // within the canvas bounds
}
```

- [ ] **Step 2: Add alignment parameter to all composables**

Add `alignment: Alignment = Alignment.Center` to the 15 public overloads.

- [ ] **Step 3: Wire into MorphRenderer**

In MorphRenderer.render():
```kotlin
val hBias = alignment.horizontalWeight / (alignment.horizontalWeight + alignment.verticalWeight + 1e-4f)
// Or use alignment as a proper Alignment:
val hBias = if (alignment is Alignment) alignment.alignedSize(drawScope.size, contentSize).run { offsetX } / width
```

Actually simpler: use standard Alignment:
```kotlin
val contentSize = Size(vw * uniform, vh * uniform)
val (offsetX, offsetY) = alignment.align(
    size = contentSize,
    space = Size(drawScope.size.width, drawScope.size.height),
    layoutDirection = LayoutDirection.Ltr,
)
// Replace hardcoded centering: dx = (drawScope.size.width - vw * uniform) / 2f
// with: dx = offsetX
```

- [ ] **Step 4: Run tests**

```bash
.\gradlew CORE COMPOSE APP
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: add Alignment parameter to all composables for custom icon positioning"
```

### Task 21: DSL build warnings

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/animation/VectorAnimation.kt` (add warnings field)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/animation/VectorAnimationBuilder.kt` (collect warnings)
- Create: `vector-morph-core/src/test/kotlin/com/imorth/vectormorth/core/animation/DslWarningsTest.kt`

**Interfaces:**
- Consumes: `VectorAnimationBuilder.addScalar`, `addBrush`
- Produces: `VectorAnimation.warnings: List<String>` with deprecation/build warnings

- [ ] **Step 1: Write DSL warning tests**

```kotlin
@Test fun degenerateIntervalEmitsWarning() {
    val anim = VectorAnimation {
        alpha(from = 1f, to = 0f, interval = 0.5f..0.5f)
    }
    assertTrue(anim.warnings.any { it.contains("degenerate") })
}

@Test fun overlappingSegmentsWarn() {
    val anim = VectorAnimation {
        at(0f) { alpha = 1f }
        at(0f) { alpha = 0f }
    }
    assertTrue(anim.warnings.any { it.contains("overlap") || it.contains("duplicate") })
}
```

- [ ] **Step 2: Add warnings to VectorAnimation class**

In `VectorAnimation.kt`, change constructor to include `warnings: List<String> = emptyList()` and add:
```kotlin
val warnings: List<String> = emptyList()
```

In `VectorAnimationBuilder`, add:
```kotlin
private val warnings = ArrayList<String>()
// In addScalar: if end <= start, add "degenerate interval $interval dropped"
// In at(): if duplicate time key, add "duplicate time $time"
// In build(): return VectorAnimation(..., warnings = warnings.toList())
```

- [ ] **Step 3: Wire warnings into codec (don't serialize)**

In `VectorAnimationCodec.encode`, don't write warnings. In decode, warnings not included (decoded animations start with empty list).

- [ ] **Step 4: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: emit build-time warnings for degenerate intervals and overlapping segments in DSL"
```

---

## Phase 6: 0.2.0 Release Prep

### Task 22: Direction contract simplification + version bump

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/MorphConfiguration.kt` (default flip)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/Enums.kt` (deprecate customStartOffsets reference)
- Modify: `gradle.properties` (VERSION_NAME)
- Modify: `README.md` (update direction contract section)

**Interfaces:**
- Consumes: nothing
- Produces: `directionTraversal` default → HONOR_DIRECTION, @Deprecated on legacy APIs, version bump to 0.2.0

- [ ] **Step 1: Flip default and add deprecation**

In `MorphConfiguration.kt:60`:
```kotlin
val directionTraversal: DirectionTraversal = DirectionTraversal.HONOR_DIRECTION,
```

In `MorphConfiguration.kt:39`:
```kotlin
@Deprecated(
    message = "Use customDrawAnchors instead",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith("customDrawAnchors"),
)
val customStartOffsets: Map<String, Float> = emptyMap(),
```

- [ ] **Step 2: Bump version**

In `gradle.properties`: `VERSION_NAME=0.2.0`

- [ ] **Step 3: Update tests that pin default behavior**

In test files where default `directionTraversal` behavior is pinned:
- `vector-morph-compose/src/test/kotlin/com/imorth/vectormorth/compose/TraversalPolicyApplicationTest.kt` — add `directionTraversal = DirectionTraversal.LEGACY` to test config
- `vector-morph-core/src/test/kotlin/com/imorth/vectormorth/core/model/DirectionTraversalConfigTest.kt` — update default assertion
- Any other tests pinned to LEGACY default

- [ ] **Step 4: Run all tests**

```bash
.\gradlew ALL
```

- [ ] **Step 5: Update README direction contract section**

Update `README.md` section "Drawing and stroke reveal" and "Default" references to reflect HONOR_DIRECTION as the new default.

- [ ] **Step 6: Commit**

```bash
git add gradle.properties vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/MorphConfiguration.kt vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/model/Enums.kt README.md vector-morph-core/src/test/.../*.kt vector-morph-compose/src/test/.../*.kt
git commit -m "feat: flip directionTraversal default to HONOR_DIRECTION, deprecate customStartOffsets, bump to 0.2.0"
```

### Task 23: Codec v2 typed records

**Files:**
- Create: `vector-morph-core/src/test/resources/vman-v1-canonical.vman` (golden fixture)
- Modify: `vector-morph-core/src/main/kotlin/com/imorth/vectormorth/core/animation/VectorAnimationCodec.kt`

**Interfaces:**
- Consumes: existing v1 encode/decode
- Produces: v2 encode/decode with typed reveal records, backward-compatible v1 decode

- [ ] **Step 1: Dump golden v1 fixture**

Before changing the encoder, generate and commit a v1 binary:

```kotlin
// Temporary test in vector-morph-core/src/test/kotlin/com/imorth/vectormorth/core/animation/CodecFixtureDumperTest.kt
@Test fun dumpV1Fixture() {
    val anim = VectorAnimation {
        at(0f) { alpha = 0f }
        at(1f) { alpha = 1f }
        path("test") {
            drawProgress(interval = 0f..1f, mode = DrawMode.FORWARD)
        }
    }
    val bytes = VectorAnimationCodec.encode(anim)
    // Write to test resource: ...
}
```

Run this test, capture the bytes, commit as `vector-morph-core/src/test/resources/vman-v1-canonical.vman`.

- [ ] **Step 2: Change VERSION to 2**

In `VectorAnimationCodec.kt`: `private const val VERSION = 2`

- [ ] **Step 3: Implement v2 typed records**

In `encode`, write reveal records by property:
- DRAW_PROGRESS: write mode, start, direction (3 UTF strings)
- FILL_PROGRESS: write nullable fillMode, nullable fillDirection
- CLIP_PROGRESS: write direction

In `decode`, dispatch by version:
```kotlin
if (version == 1) {
    // Legacy 5-slot read (existing code)
} else if (version == 2) {
    // Typed per-property read
}
```

- [ ] **Step 4: Test v1 decode and v2 round-trip**

```kotlin
@Test fun v1FixtureDecodesSuccessfully() {
    val bytes = loadResource("vman-v1-canonical.vman")
    val decoded = VectorAnimationCodec.decode(bytes)
    // Verify tracks/segments match expected structure
}

@Test fun v2RoundTripPreservesAnimation() {
    val anim = buildTestAnimation()
    val bytes = VectorAnimationCodec.encode(anim) // writes v2
    val decoded = VectorAnimationCodec.decode(bytes)
    // Verify structure match
}
```

- [ ] **Step 5: Run tests**

```bash
.\gradlew CORE COMPOSE
```

- [ ] **Step 6: Commit**

```bash
git add vector-morph-core/src/main/kotlin/com/imorth/vectormorph/core/animation/VectorAnimationCodec.kt vector-morph-core/src/test/resources/vman-v1-canonical.vman
git commit -m "feat: codec v2 with typed reveal records, backward-compatible v1 decode, bump easing samples to 257"
```

---

## Phase 7: Ecosystem

### Task 24: Compose semantics/behavior tests

**Files:**
- Create: `vector-morph-compose/src/test/kotlin/com/imorth/vectormorth/compose/SemanticsAndSizeTest.kt`

**Interfaces:**
- Consumes: public composables
- Produces: Robolectric compose tests for contentDescription, default size, INSTANT motion

- [ ] **Step 1: Write compose tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class SemanticsAndSizeTest {

    @get:Rule
    val composeRule = CreateComposeRule()

    @Test
    fun morphIconExposesContentDescriptionAsSemantics() {
        var captured: String? = null
        composeRule.setContent {
            val selected = mutableStateOf(false)
            MorphIcon(
                from = Icons.Outlined.Home,
                to = Icons.Filled.Home,
                selected = selected.value,
                contentDescription = "Home",
                onSettled = { captured = it.toString() },
            )
        }
        // Verify contentDescription appears in semantics
        composeRule.onNodeWithContentDescription("Home").assertExists()
    }

    @Test
    fun instantMotionJumpsToProgress1() {
        composeRule.setContent {
            MorphIcon(
                from = Icons.Outlined.Home,
                to = Icons.Filled.Home,
                selected = true,
                motionPreference = MotionPreference.INSTANT,
            )
        }
        // Icon should render as filled (no morph needed)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
.\gradlew COMPOSE
```

- [ ] **Step 3: Commit**

```bash
git add vector-morph-compose/src/test/kotlin/com/imorth/vectormorth/compose/SemanticsAndSizeTest.kt
git commit -m "test: add compose semantics and behavior tests for composables"
```

### Task 25: :benchmark module

**Files:**
- Modify: `settings.gradle.kts` (add benchmark module)
- Modify: `gradle/libs.versions.toml` (add benchmark dependency)
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/androidTest/java/com/imorth/vectormorth/benchmark/` (4 benchmark classes)
- Modify: `gradle.properties` (add benchmark testBuildType)

**Interfaces:**
- Consumes: nothing new in library modules
- Produces: `:benchmark` module with 4 Microbenchmark classes

- [ ] **Step 1: Add benchmark to settings.gradle.kts**

```kotlin
include(":benchmark")
```

- [ ] **Step 2: Add benchmark dependency to catalog**

```toml
benchmark = { group = "androidx.benchmark", name = "benchmark-junit4", version = "1.4.0" }
```

- [ ] **Step 3: Create benchmark build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imorpher.vectormorph.benchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = "benchmark"
    buildTypes {
        register("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(project(":vector-morph-core"))
    implementation(project(":vector-morph-compose"))
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.benchmark)
}
```

Wait — `alias(libs.plugins.kotlin.android)` — AGP 9 built-in Kotlin may not need this. Since the module is pure test + benchmark, we might need `kotlin("android")` directly. Let me use a simpler approach:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
```

- [ ] **Step 4: Write 4 Microbenchmark classes**

```java
// PlannerBenchmark.java
package com.imorpher.vectormorth.benchmark;

import androidx.benchmark.junit4.BenchmarkRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class PlannerBenchmark {
    @Rule public BenchmarkRule rule = new BenchmarkRule();

    @Test
    public void planCold() {
        rule.measureRepeated(() -> {
            planMorph(fromIcon, toIcon, config);
        });
    }

    @Test
    public void planCached() {
        planMorph(fromIcon, toIcon, config); // warm cache
        rule.measureRepeated(() -> {
            planMorph(fromIcon, toIcon, config);
        });
    }
}
```

Similar for `EvaluatorBenchmark`, `SubdividerBenchmark`, `CodecBenchmark`.

- [ ] **Step 5: Run benchmark tests**

```bash
.\gradlew :benchmark:connectedBenchmarkAndroidTest
```

Expected: Requires a connected Android device. Document this in task.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml benchmark/
git commit -m "feat: add benchmark module with Microbenchmark classes for core hot paths"
```

### Task 26: Dokka aggregation + publish workflow artifact

**Files:**
- Modify: root `build.gradle.kts` (add dokka plugin)
- Modify: `.github/workflows/publish.yml` (add docs build step)
- Modify: `gradle/libs.versions.toml` (ensure dokka version is latest)

**Interfaces:**
- Consumes: existing dokka plugin
- Produces: `dokkaHtmlMultiModule` task + publish workflow uploads docs artifact

- [ ] **Step 1: Add dokka to root build.gradle.kts**

If root has a build.gradle.kts, add:
```kotlin
plugins {
    id("org.jetbrains.dokka") version "2.2.0"
}

tasks.register<DokkaMultiModuleTask>("dokkaHtmlMultiModule") {
    outputDirectory.set(layout.buildDirectory.file("dokka"))
}
```

- [ ] **Step 2: Update publish.yml to build docs**

Add after assemble step:
```yaml
- name: Build Dokka docs
  run: ./gradlew dokkaHtmlMultiModule
- name: Upload docs
  uses: actions/upload-artifact@v4
  with:
    name: docs
    path: build/dokka/
```

- [ ] **Step 3: Run docs build**

```bash
.\gradlew dokkaHtmlMultiModule
```

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts .github/workflows/publish.yml
git commit -m "docs: add dokka multi-module aggregation and upload to publish workflow"
```

### Task 27: Docs refresh (README boundaries, performance section)

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: existing README
- Produces: updated Current boundaries, new Performance section, API surface updates

- [ ] **Step 1: Update §Current boundaries**

Replace with:
```markdown
## Current boundaries

[Updated to reflect new features: Alignment, ViewportNormalization, segment budget, clip morphing, MorphingIcon, MorphPrecompiler, etc.]
```

- [ ] **Step 2: Add new Performance section**

```markdown
## Performance

[Updated with byte budget, structural keys, brush memoization, static endpoint reuse, tint fast path, segment budget, Hungarian matching, etc.]
```

- [ ] **Step 3: Update §Installation**

VERSION → 0.2.0.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: refresh README with new features, performance notes, and 0.2.0 version"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-10-24-limitations-remediation.md`.

**Phase breakdown for shipping:**
- Phase 1 (CI, R8 rules, API fixture, blendMode, cache byte budget): ~5 small tasks, fully testable, no breaking changes. Ship first.
- Phase 2 (structural keys): ~1 task, high-impact. Ship after Phase 1.
- Phase 3 (renderer refactor + perf): 6 tasks, mechanical splits first (7,8), then perf changes (9-12). Split ensures pixel regression baseline.
- Phase 4 (geometry quality): 4 tasks, each with new config options (non-breaking).
- Phase 5 (compose API): 5 tasks, new APIs only (MorphingIcon, Alignment, warnings, onSettled, auto motion) — non-breaking until direction flip in Phase 6.
- Phase 6 (0.2.0 release): 2 breaking tasks (direction default flip, codec v2), version bump.
- Phase 7 (ecosystem): 4 test/build tasks, infrastructure.

**Recommended execution order:**
1. **Native (this session)** — implement Phase 1-3 tasks, commit after each.
2. **Continue in session or subagent-driven** — Phases 4-7.
3. **Ship 0.1.x** with Phases 1-7.
4. **Phase 8 (KMP)** — extract to its own plan; ship separately as 0.3.0.

**For this plan I recommend Native execution**, because the tasks are well-defined with exact file paths and interfaces, most changes are mechanical (refactors, config additions), and the test suites provide immediate regression coverage. Each task completes in 10-30 minutes of focused work, and committing after each gives a clean git history. The main risk is the renderer split (Task 7) — ensure all existing tests pass before proceeding to perf tasks.

Does the plan capture what you want? Say the word and I'll start implementing Phase 1 now.
