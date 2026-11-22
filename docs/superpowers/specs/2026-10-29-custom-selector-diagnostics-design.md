# Design: Diagnostics for Custom Mapping and Draw-Order Selectors

- Date: 2026-10-29
- Status: Draft for review
- Roadmap: `GOALS.md`, Phase 1 — Parameter effectiveness and diagnostics
- Scope: configuration preflight and vector-aware plan diagnostics for `customMappings` and `customDrawOrder`

## Goal

Make malformed, ambiguous, and unresolved custom path selectors visible through the library's existing diagnostics before or during planning, while preserving the current matching and ordering behavior. A caller should be able to tell which entries were ignored, shadowed, or could not match actual paths without reverse-engineering deterministic fallback behavior.

## Current behavior

- `MorphValidator.validateConfiguration` reports when `customMappings` or `customDrawOrder` has no active consumer, but does not inspect list contents.
- `PathMatcher.matchByNames` uses the first mapping for a source name. A missing target adds a string warning, but a source repeated with another target is not identified as an ambiguous selector. If multiple source mappings claim one target, only the first source claims it; later sources fade out without a specific collision finding.
- `DrawOrder.sortByNames` builds a name-to-rank map, so a repeated name is overwritten by its last occurrence. Names not represented by a path receive no rank and sort after named paths. Partial lists are valid: omitted paths remain stable relative to their prior order.
- Path-matcher warning strings are included in plan compatibility warnings, but selector failures are not consistently represented as structured `MorphIssue`s in `MorphPlan.report.issues`.

## Scope and behavior

### Configuration-only preflight

Extend the existing pure, non-throwing `MorphValidator.validateConfiguration(config, animation)` audit. Inspect selector contents only when the corresponding custom mode has a consumer, using the existing consumer-resolution rules:

- `customMappings` is active for `PathMatchingStrategy.CUSTOM` and `AUTO`.
- `customDrawOrder` is active for custom z-order (`drawOrder == CUSTOM && !keepRenderOrder`) or for a resolved `staggerOrder == CUSTOM` consumed by path-scoped stagger delay or `TimingMode.BY_PATH`.

When active, report:

| Category | Severity | Condition |
| --- | --- | --- |
| `custom-mapping-name-empty` | WARNING | A mapping has a blank/whitespace-only source or target name. |
| `custom-mapping-source-duplicate` | WARNING | A source name occurs in more than one mapping; current first-entry resolution shadows later entries, including exact duplicate entries. |
| `custom-mapping-target-duplicate` | WARNING | More than one mapping claims the same target name; current first claimant wins and later sources remain unmatched. |
| `custom-order-name-empty` | WARNING | A custom order entry is blank/whitespace-only. |
| `custom-order-name-duplicate` | WARNING | A path name occurs more than once; current rank-map behavior uses its last occurrence. |

Diagnostics are emitted once per affected selector name/condition where practical, with a message that states the current deterministic resolution. No new error severity is introduced: these inputs can still produce a renderable plan.

### Vector-aware plan diagnostics

During planning, validate active selectors against the actual endpoint path names and resolved pair representatives, and expose findings as structured `MorphIssue`s in `MorphPlan.report.issues` (also returned by `MorphValidator.validate(from, to, config)`). Preserve the existing plan's geometry and rendering decisions.

| Category | Severity | Condition |
| --- | --- | --- |
| `custom-mapping-source-not-found` | WARNING | An active mapping's source name is absent from the source vector. |
| `custom-mapping-target-not-found` | WARNING | An active mapping's target name is absent from the target vector. |
| `custom-order-name-not-found` | WARNING | An active order entry does not match any resolved pair representative name. A representative is the same target-preferred path (`toPath ?: fromPath`) used by `DrawOrder` today. |

Unknown mapping references may still lead to the existing fade/geometric-rematching behavior; unknown custom-order names remain ignored. Findings must describe that fallback rather than reject configuration. Do not warn merely because a custom order omits some paths: a partial explicit order is supported, and unlisted pairs keep their stable prior order. Do not duplicate the same selector finding as both a mapping issue and a generic unmatched-path issue unless they describe distinct facts.

Plan-level selector issues must also be available through the existing compatibility warning surface without duplicating their text. Static configuration diagnostics remain endpoint-independent; vector-aware findings belong to the plan report.

## Compatibility constraints

- No change to `PathMatcher` pairing, `DrawOrder` rank resolution, fallback behavior, pair ordering, or public configuration fields.
- Do not change `PathMatchingStrategy`/`DrawOrderStrategy` aliases or deprecations.
- Do not add dependencies, source families, or decoder/content APIs.
- Preserve stable `MorphIssue.category` names once released; selector reports are additive to existing categories.
- Keep the default configuration silent and keep inactive custom lists under the existing unused-setting diagnostics rather than additionally reporting their internal contents.

## Implementation boundaries

- `MorphValidator.validateConfiguration`: inspect active selector list integrity and produce stable issue categories.
- `PathMatcher` / planning diagnostics: expose per-entry reference problems without changing the matcher’s result or its fallback policy.
- `MorphPlanner` and `MorphReport`: merge the selector issues into the existing report and compatibility warning surfaces once.
- `MorphValidatorTest`: table-driven preflight tests for whitespace names, duplicate sources/targets, duplicate order names, active/inactive consumers, and clean defaults.
- `PathMatcherTest` and planner-report tests: missing source/target references, a reused target, and an unknown order name; assert both structured issue category/path context and unchanged pairs/order.
- `DrawOrderAndDirectionTest`: pin that duplicate/unknown names keep their current last-rank/unknown-last behavior; pin that partial orders remain valid and stable.
- `docs/CONFIGURATION.md` and `docs/DIAGNOSTICS.md`: document the new issue categories and partial-order behavior alongside the consumer matrix.

## Acceptance criteria

1. Every malformed/ambiguous active selector above has a focused configuration-preflight regression test.
2. Vector-aware missing references appear as structured plan issues and through `MorphValidator.validate`.
3. Tests prove matching, fallback, and ordering outputs are unchanged for duplicate, unknown, and partial selector lists.
4. Default and inactive configurations do not gain spurious selector-content warnings.
5. Documentation lists stable categories, severity, and the intentional behavior for unlisted order names.

## Out of scope

- Changing duplicate handling to reject entries, choose a different winner, or deduplicate lists.
- Automatically completing partial custom orders or deriving new mappings.
- Auditing unrelated public fields or beginning Phase 2 geometry adapters.
- Renaming `customMappings`, `customDrawOrder`, or changing their configuration schema.
