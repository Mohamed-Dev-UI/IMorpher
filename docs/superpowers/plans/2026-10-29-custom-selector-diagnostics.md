# Custom Selector Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Report malformed, ambiguous, and unresolved active custom path mappings and draw-order names without changing their current resolution behavior.

**Architecture:** Keep endpoint-independent selector integrity checks in `MorphValidator.validateConfiguration`. Add endpoint-aware selector reference findings to `MorphPlan.report.issues` during planning, and let the existing warning aggregation surface those findings without duplicate text. Pin current deterministic matching/order behavior in focused tests; update the configuration and diagnostics references in the same work.

**Tech Stack:** Kotlin, Jetpack Compose vector models, Gradle, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-29-custom-selector-diagnostics-design.md`

## Global Constraints

- Preserve `PathMatcher` pairing, `DrawOrder` rank resolution, fallback behavior, pair ordering, and all public configuration fields.
- Do not add dependencies or new content/source families.
- Use the spec's stable categories and severities; do not promote recoverable selector problems to errors.
- Keep default and inactive selector configurations free of selector-content warnings.
- Do not warn for paths omitted from a partial custom order.
- Do not commit changes; commits were not requested.

## Review Focus

1. **Duplicate source mappings** — the first entry currently wins; later entries must be diagnosed without changing path pairs. Test in Task 1 and assert pair behavior in Task 3.
2. **Duplicate target mappings** — the first source to claim a target currently wins; later sources remain unmatched. Test in Task 1 and assert pair behavior in Task 3.
3. **Empty or whitespace-only selectors** — diagnose only when the selector list has a consumer; inactive values retain the existing unused-setting diagnostic. Test in Task 1.
4. **Unknown mapping endpoints** — report missing active source/target names in the plan while preserving current fades/auto-rematching. Test in Task 2.
5. **Unknown and omitted custom-order names** — report unknown entries, but keep partial orders valid and preserve stable order for unlisted pairs. Test in Task 2 and Task 3.

---

## File Map

- Modify `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/validation/MorphValidator.kt`: static selector-content validation plus endpoint-aware selector reference validation.
- Modify `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt`: merge endpoint-aware selector issues into the plan report before compatibility warnings are assembled.
- Modify `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/validation/MorphValidatorTest.kt`: configuration-only integrity diagnostics and consumer activation tests.
- Modify `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/MorphPlanningTest.kt`: endpoint-aware issue categories and `MorphValidator.validate` coverage.
- Modify `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/matching/PathMatcherTest.kt`: preserve first-mapping-wins behavior on duplicate source names and target claims.
- Modify `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrderAndDirectionTest.kt`: preserve last-rank/unknown-last semantics and partial-order stability.
- Modify `docs/CONFIGURATION.md`: explain selector integrity and partial custom orders in the interaction matrix and enum reference.
- Modify `docs/DIAGNOSTICS.md`: list the stable new categories, severities, and when vector-aware findings appear.

## Task 1: Validate Active Selector List Integrity

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/validation/MorphValidator.kt`
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/validation/MorphValidatorTest.kt`

**Interfaces:**
- Consumes: `MorphValidator.validateConfiguration(config: MorphConfiguration, animation: VectorAnimation? = config.animation): List<MorphIssue>` and the existing consumer-resolution rules.
- Produces: static issues with categories `custom-mapping-name-empty`, `custom-mapping-source-duplicate`, `custom-mapping-target-duplicate`, `custom-order-name-empty`, and `custom-order-name-duplicate`.

- [x] **Step 1: Write failing table-driven tests**

Add focused tests asserting:
- Active `AUTO`/`CUSTOM` mapping lists report blank or whitespace-only source/target names as WARNING `custom-mapping-name-empty`.
- Repeated nonblank source names report WARNING `custom-mapping-source-duplicate`; repeated nonblank target names report WARNING `custom-mapping-target-duplicate`.
- Active custom order reports blank/whitespace entries as WARNING `custom-order-name-empty` and repeated names as WARNING `custom-order-name-duplicate`.
- An inactive malformed mapping list reports the existing `custom-mappings-unused` only, not selector-content categories; inactive custom order reports the existing `custom-order-unused` only.
- `MorphConfiguration.Default` remains issue-free.

- [x] **Step 2: Run the focused tests and verify failure**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest --tests 'com.imorpher.vectormorph.core.validation.MorphValidatorTest'`
Expected: FAIL because the selector-content categories are not emitted.

- [x] **Step 3: Implement static integrity diagnostics**

In `MorphValidator.validateConfiguration`, inspect `customMappings` only when `pathMatching` is `AUTO` or `CUSTOM`, and inspect `customDrawOrder` only when its resolved render-order or path-timeline consumer is active. Emit one warning per distinct nonblank duplicate selector name and an empty-name warning for each affected mapping/order entry. Reuse/extract the custom-order consumer predicate so static validation and later plan validation use identical activation logic. Messages must state the current first-source/first-target or last-order-rank resolution. Do not trim nonblank selectors before matching or mutate the configuration.

- [x] **Step 4: Run the focused test class and verify it passes**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest --tests 'com.imorpher.vectormorph.core.validation.MorphValidatorTest'`
Expected: `BUILD SUCCESSFUL`; default and inactive-consumer assertions remain clean apart from their established unused-setting findings.

## Task 2: Add Endpoint-Aware Selector Reference Issues

**Files:**
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/validation/MorphValidator.kt`
- Modify: `vector-morph-core/src/main/kotlin/com/imorpher/vectormorph/core/plan/MorphPlanner.kt`
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/MorphPlanningTest.kt`

**Interfaces:**
- Consumes: Task 1's shared custom-order consumer predicate; `PreparedVector.paths`, resolved `PathPairPlan`s, and `MorphConfiguration`.
- Produces: `internal fun MorphValidator.validateSelectorReferences(config: MorphConfiguration, from: PreparedVector, to: PreparedVector, pairs: List<PathPairPlan>, animation: VectorAnimation? = config.animation): List<MorphIssue>`; planner merges its findings into `MorphReport.issues`.

- [x] **Step 1: Write failing planner/report tests**

Add tests asserting:
- An active mapping with an absent source produces WARNING `custom-mapping-source-not-found`; an active mapping with an absent target produces WARNING `custom-mapping-target-not-found`.
- An active custom order containing a name absent from resolved target-preferred pair representatives produces WARNING `custom-order-name-not-found`.
- Each finding appears in `MorphPlanner.plan(...).report.issues`; missing mapping findings are also returned by `MorphValidator.validate(from, to, config)`.
- Blank selectors already covered by Task 1 do not produce redundant plan-level not-found findings.
- The existing default plan gains no custom-selector issues.

- [x] **Step 2: Run the focused tests and verify failure**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest --tests 'com.imorpher.vectormorph.core.MorphPlanningTest'`
Expected: FAIL because endpoint-aware selector issues are not yet added to the report.

- [x] **Step 3: Implement endpoint-aware selector validation and report integration**

Implement the stated internal `validateSelectorReferences` signature. Check mapping endpoints only for `AUTO`/`CUSTOM`, and custom-order names only when the resolved custom order has a consumer. Compare exact names (except skip blank/whitespace-only entries already diagnosed statically); deduplicate repeated missing names. For custom order, derive represented names with the same target-preferred `toPath ?: fromPath` rule as `DrawOrder`. Return WARNING issues with the referenced selector in `pathNames` and messages explaining that current fallback/resolution behavior is retained. Call this validator from `MorphPlanner.plan` after pairs are created, append its issues before building `MorphReport`, and rely on existing non-INFO warning aggregation with its current distinct-text behavior.

- [x] **Step 4: Run the focused planner test class and verify it passes**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest --tests 'com.imorpher.vectormorph.core.MorphPlanningTest'`
Expected: `BUILD SUCCESSFUL`; plan issues and compatibility warnings expose the findings.

## Task 3: Lock Compatibility Behavior and Document the Contract

**Files:**
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/matching/PathMatcherTest.kt`
- Test: `vector-morph-core/src/test/kotlin/com/imorpher/vectormorph/core/reveal/DrawOrderAndDirectionTest.kt`
- Modify: `docs/CONFIGURATION.md`
- Modify: `docs/DIAGNOSTICS.md`

**Interfaces:**
- Consumes: Task 1's stable configuration categories and Task 2's vector-aware plan categories.
- Produces: regression locks and user-facing selector diagnostics documentation; no source API changes.

- [x] **Step 1: Add or complete behavior-preservation tests**

Assert in `PathMatcherTest` that duplicate source mappings still resolve using the first mapping and that two sources claiming one target still leave the later source unmatched. Assert in `DrawOrderAndDirectionTest` that duplicate custom-order names retain the current last-rank behavior, unknown names remain last/ignored by ranking, and a partial custom order leaves unlisted pairs in their stable prior order. Include a contrasting valid unique order to ensure a real requested reorder remains effective.

- [x] **Step 2: Run the focused matcher and ordering tests**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest --tests 'com.imorpher.vectormorph.core.matching.PathMatcherTest' --tests 'com.imorpher.vectormorph.core.reveal.DrawOrderAndDirectionTest'`
Expected: `BUILD SUCCESSFUL` with selector-resolution behavior unchanged.

- [x] **Step 3: Update the configuration and diagnostics references**

Document all eight categories with severity and activation conditions. State that duplicate mappings retain the current first-entry behavior, duplicate custom-order names retain the current last-rank behavior, unknown names are diagnosed but not rejected, and omitted names in a partial custom order are intentionally valid. Link the plan-level categories to `MorphPlan.report.issues` and keep the configuration-only versus endpoint-aware distinction explicit.

- [x] **Step 4: Run the focused core suite and check the final diff**

Run: `./gradlew.bat :vector-morph-core:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. Then run `git diff --check` and inspect only the task-owned files; do not stage or commit unrelated worktree changes.

---

## Plan Self-Review

- **Spec coverage:** Static integrity checks, endpoint existence checks, compatibility warning exposure, unchanged resolution behavior, partial-order semantics, and both reference-doc updates are covered by Tasks 1–3.
- **Step granularity:** Each test/write/implementation/verification step has a named artifact or focused Gradle command and a clear expected result.
- **Type consistency:** The endpoint-aware validator returns `List<MorphIssue>` and receives the exact model types consumed by the planner; `MorphPlanner` already owns both endpoint vectors and its resolved pair list.
- **Review focus:** Each listed failure mode maps to a task-level test. Task 1 covers blank/inactive and duplicate diagnostics; Task 2 covers missing endpoint names; Task 3 pins the legacy winner and partial-order behavior.
- **Proportion:** Three tasks align with static validation, plan integration, and compatibility/docs; no new module or abstraction is planned beyond a shared consumer predicate and one internal validator function.
