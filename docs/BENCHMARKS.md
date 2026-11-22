# Microbenchmark Budgets (measured)

Measured performance budgets for the `:benchmark` module (AndroidX Microbenchmark,
`androidx.benchmark:benchmark-junit4:1.4.0`). These are **recorded measurements**,
not inferred or estimated numbers. Re-run them as described below whenever the
planner, evaluator, codec, or subdivider hot paths change, and compare against
these baselines (approximate \(±\) variance grows outside the recorded environment).

## Recorded environment

- **Device:** AVD `Pixel_9_Pro_XL` (self-hosted emulator, headless
  `-no-window`, SwiftShader GPU), `Pixel_9_Pro_XL(AVD) - 16`
- **OS/build:** Android 16 (API 36), `google/sdk_gphone64_x86_64`,
  fingerprint `BE2A.250530.026.D1`, ABI x86_64
- **Host runtime:** 4 CPU cores @ 2.0 GHz, ~4.1 GB device RAM,
  ART `compilationMode = verify`
- **Runner:** `androidx.benchmark.junit4.AndroidBenchmarkRunner` (IsolationActivity)
  with `androidx.benchmark.suppressErrors = EMULATOR` — emulator results are
  explicitly **not representative of real user devices**; treat these numbers as
  regression baselines only, and re-measure on a physical device before shipping
  any performance claim.

## Measured budgets (medians over 25+ iterations)

| Benchmark | Median time (µs) | Median allocations | Time CV |
| --- | ---: | ---: | ---: |
| `CodecBenchmark.decode` (v2 stream, small vector) | 194 | 103 | 0.15 |
| `CodecBenchmark.encode` | 843 | 28 | 0.33 |
| `EvaluatorBenchmark.evaluateOneFrame` (one planned frame) | 294 | 958 | 0.15 |
| `PlannerBenchmark.planStructuralCacheHit` | 0.45 | 2 | 0.89 |
| `PlannerBenchmark.planCold` | 433 | 1 338 | 0.25 |
| `SubdividerBenchmark.splitSingleSegmentInto8` | 6.0 | 28 | 0.44 |
| `SubdividerBenchmark.equalizeTo64Segments` | 65 | 234 | 0.67 |

Memory/GC framing: per-frame allocation is dominated by evaluation geometry
(~958 allocations/frame) and cold planning (~1 338 allocations once, then the
structural cache hit costs ~2 allocations and ≈0.45 µs — the planner cache is
doing its job at these sizes).

## Re-running

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest   # requires a booted device/emulator
```

The AndroidX JSON output lands in
`benchmark/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest/connected/`(\*)
`*​-benchmarkData.json` (regenerated per run, not versioned). Known flake: on
emulator teardown the instrumentation can die with a
`UiAutomation.disconnect → DeadObjectException` after the run and produce an
empty-failure XML; a clean retry passes (verified locally).

## Benchmark classes

`benchmark/src/androidTest/java/com/imorpher/vectormorph/benchmark/`:
`CodecBenchmark`, `EvaluatorBenchmark`, `PlannerBenchmark`,
`SubdividerBenchmark` — added as the module's four Microbenchmark classes.
Only the `benchmark`-variant build type is instrumented (release-style
optimizations plus a stable, comparable timing signature).
