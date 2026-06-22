## Context

This change bundles five independent defects in `phtcosta/ape`, all confirmed on `master` (issue #15, master plan §3). They share one repo, one validation pass (a real-device run plus the partial JUnit suite on `MopScorer`/`MopData`), and one goal: make the `sata`/`sata_mop`/`sata_mop_llm` comparison valid and the MOP/coverage/telemetry/LLM signals faithful. APE has no full automated test suite — validation is JUnit where it exists plus device runs (P-context).

The proximal experiment defect is A-3: `Config.componentPercentage` derives its default from `mopDataPath`, confounding the arms (proven: `[APE-RV] Triggering` in `sata_mop` 502/507 traces, `sata` 0/507). The other four (A-2 scorer, A-4 coverage, A-5 telemetry, A-6 LLM) are independent and touch disjoint files, so they compose without ordering constraints.

**Source paths.** All classes are under `src/main/java/com/android/commands/monkey/ape/`: `utils/Config.java`, `utils/MopScorer.java`, `utils/MopData.java`, `utils/UICoverageTracker.java`, `agent/SataAgent.java`, `agent/StatefulAgent.java`, `model/ModelAction.java`, `model/StateKey.java`, `llm/LlmRouter.java`, `llm/ScreenshotCapture.java`, `naming/Name.java`. Bare filenames below resolve to these paths.

**Stale base spec (out of gh15 scope, flagged for sync).** The live `openspec/specs/mop-guidance/spec.md` lags the code: its invariants stop at INV-MOP-06 while the code references INV-MOP-13/14, and it still uses pre-gh13 JSON-key language (`reachesMop`/`directlyReachesMop`). This change's mop-guidance delta uses the **code's** terminology (`directMop`/`transitiveMop`) and invariant numbers in the gap (07/08), which collide with neither the spec nor the code. Reconciling the base spec to gh13 is a separate concern, not part of gh15.

## Architecture

```
Config ──(componentPercentage, llmPercentage)──> SataAgent / LlmRouter
StatefulAgent.resolveNewAction() ──> selectNewActionNonnull() ──> [APE-STEP] emit
StatefulAgent.adjustActionsByGUITree() ──> MopScorer.score(...) ──> MopData lookup (+ containment)
StatefulAgent ──> UICoverageTracker (bounded stateData + per-Activity rollup)
LlmRouter.selectAction() ──> ScreenshotCapture (null → breaker.recordFailure)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config` | Load/clamp flags | `ape.properties` | `componentPercentage` (default 0.0), `llmPercentage` (clamped [0,1]) |
| `MopScorer.score` | MOP priority boost | activity, shortId, MopData, eventType | int boost (activity fallback for resolved-but-unflagged) |
| `StatefulAgent.adjustActionsByGUITree` | Candidate-id containment + scoring | GUI node, MopData | per-action boosts |
| `StatefulAgent.resolveNewAction` | Emit `[APE-STEP]` | finalized `ModelAction` | one telemetry line/action |
| `ModelAction` | Carry decision provenance | selection path | `decisionSource` field |
| `UICoverageTracker` | Faithful coverage | State, actions | bounded stateData, per-Activity rollup |
| `LlmRouter.selectAction` | LLM routing | GUITree, state | breaker trips on null screenshot |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| component-triggering: Config — componentPercentage; INV-CT-01 | `Config.java:169-170` (default `0.0`) | JUnit `ConfigTest` (default + override); device: `sata_mop` trace has 0 `[APE-RV] Triggering` unless explicit |
| mop-guidance: MopScorer Priority Boost (B4); INV-MOP-07 | `MopScorer.java:40-54` (reorder; remove early `return 0`) | JUnit `MopScorerTest` resolved-but-unflagged → +100 |
| mop-guidance: granularity (B3) | `StatefulAgent.java:1364` caller-side candidate ids | JUnit `MopScorerTest`/`MopDataTest` parent-flagged/child-clicked |
| mop-guidance: eventType normalization (B6); INV-MOP-08 | `MopData`/`MopScorer` normalize fn | JUnit snake⇄camel equal |
| ui-coverage: Widget Registration (action type); INV-COV-06 | `UICoverageTracker.java:191-199` key `xpath|type` | JUnit/device: two scroll types tracked distinctly |
| ui-coverage: per-Activity aggregation | `UICoverageTracker` rollup map | device: fragmented Activity reports once |
| ui-coverage: Bounded stateData; INV-COV-05 | `UICoverageTracker` bounded map + rollup fold | device: memory bounded, coverage not zeroed |
| action-selection: [APE-STEP] telemetry; INV-SEL-04 | `ModelAction.decisionSource`; `StatefulAgent.java:1259` emit; `SataAgent.java:317,328,339,348` set source | device: every executed action has one `[APE-STEP]` |
| llm-routing: pipeline null-screenshot breaker; INV-RTR (Error) | `LlmRouter.java:245-249` add `breaker.recordFailure()` | JUnit `LlmRouter`/device: secure app opens breaker |
| llm-routing: llmPercentage clamp; INV-RTR-08 | `Config.java:153` clamp | JUnit clamp 1.5→1.0, -0.2→0.0 |

## Goals / Non-Goals

**Goals:** decouple triggering from MOP; make the scorer reach the activity fallback and reconcile parent/child granularity; faithful, bounded coverage; one attributable telemetry line per action; LLM breaker trips on null screenshot; clamp `llmPercentage`.

**Non-Goals:** G-1 (gator handler-reachability, repo `PAMunb/rvsec`); A-1 (discarded); calibrating the *magnitude* of `llmPercentage` or editing experiment property files (experiment-owner domain); changing the gh13 JSON schema or the gator producer (`eventType` is normalized consumer-side); keeping the old coverage-gap semantics in parallel (replaced).

## Decisions

**D1 — A-3 default `0.0`, no derivation.** `componentPercentage` default becomes a literal `0.0`; triggering only via explicit `ape.componentPercentage`. Alternative (a separate `ape.componentTriggering` boolean) rejected as redundant — one knob (P1).

**D2 — A-2/B3 containment resolved caller-side, NOT via `score()` signature change.** `MopScorer.score()` keeps its `String`-only signature; `StatefulAgent.adjustActionsByGUITree()` (which already holds the `GUITreeNode` at `:1364`) computes the candidate id set `{shortId} ∪ ancestorIds(≤2) ∪ childIds(≤2)` and calls the existing `score()` per candidate, taking the max boost. Rationale: keeps `score()` a pure string function (unit-testable without a GUI tree), confines tree traversal to the agent that owns the tree, avoids a public-API change to a method with 6+ test call sites. Alternative (add `GUITreeNode` param to `score()`) rejected: couples the scorer to the UI tree and forces every test to build a node. The depth-≤2 bound + a hit-rate log guard against over-boost.

**D3 — A-5 `decisionSource` enum field on `ModelAction`.** `ModelAction` gains a `decisionSource` (enum `SATA|MOP|Coverage|LLM|Fuzz|Menu|WTG|Component|Budget`), set on every return path in `SataAgent.selectNewActionNonnull()` including the four LLM/budget early-returns (`:317,328,339,348`). `StatefulAgent.resolveNewAction()` emits one `[APE-STEP]` line for the finalized action. Alternative (parse existing aggregate boost logs heuristically) rejected — the master plan explicitly forbids heuristic log parsing; the early-returns produce no SATA log today.

**D4 — A-4 bounded access-ordered map + per-Activity rollup.** `stateData` becomes a bounded `LinkedHashMap` (access-order, `removeEldestEntry` past the bound). On eviction the entry's counts are folded into an `activityRollup: Map<String,Coverage>` keyed by Activity, so reporting aggregates per Activity and eviction never zeroes counted coverage. The widget key gains `|actionType` for target actions. Steering may stay fragment-level; only the *reported* metric aggregates. This replaces the gap semantics (decision: substitute, per plan).

**D5 — A-6 breaker on null + clamp at load.** `LlmRouter.selectAction()` calls `breaker.recordFailure()` on a null screenshot (parity with the network/parse branches), so a 100%-null app opens the breaker after the failure threshold and stops retrying. `Config.llmPercentage` is clamped to `[0,1]` at load. `llmMaxCalls` does not exist in source; only the stale `CLAUDE.md:133` doc line is removed.

## API Design

### `MopScorer.score(String activity, String shortId, MopData data, String eventType) -> int`
- Precondition: `data != null`. Signature unchanged (D2).
- Postcondition: returns `mopWeightDirect | mopWeightTransitive | mopWeightActivity | 0`; the activity fallback is reachable when the widget resolves but is unflagged (INV-MOP-07).

### `ModelAction.decisionSource: DecisionSource`
- Enum field; default unset is illegal at execution — every selection path sets it (INV-SEL-04).

### `LlmRouter.selectAction(...)`
- On null screenshot: `breaker.recordFailure()` then return null (changed). Other steps unchanged.

## Data Flow

`ape.properties` → `Config` (clamped/defaulted flags) → `SataAgent`/`StatefulAgent`/`LlmRouter`. During a step: `adjustActionsByGUITree()` builds candidate ids from the GUI node, scores each via `MopScorer`, applies WTG/coverage boosts; `resolveNewAction()` finalizes the action, reads its `decisionSource`, emits `[APE-STEP]`. `UICoverageTracker` records the interaction, folding evicted state coverage into the Activity rollup.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Malformed `componentPercentage`/`llmPercentage` | `ape.properties` | Default 0.0 / clamp [0,1] | Use sanitized value |
| Null screenshot | secure window via `ScreenshotCapture` | `breaker.recordFailure()` + short-circuit | Breaker opens; SATA takes over |
| stateData at bound | `UICoverageTracker` | Evict eldest, fold into rollup | Reported coverage preserved |

## Risks / Trade-offs

- [A-3 changes effective experiment default for `sata_mop`] -> intentional; experiment configs that want triggering set `ape.componentPercentage` explicitly (owner domain, documented).
- [A-4 redefines the gap metric, breaking historical comparability] -> accepted; the gap is an internal steering signal, headline coverage is independent (rv-android).
- [B3 over-boost from a single MOP child marking a root container] -> depth-≤2 bound + hit-rate log; revisit if log shows inflation.
- [A-5 adds a field + log volume] -> structured single line, toggleable verbosity; covers all paths to avoid silent gaps.
- [A-6 breaker on null may suppress LLM on apps with transient null capture] -> breaker recovers after the window; net win on persistent-null apps.
- [Delta sync of non-Requirement sections] -> the `llm-routing` delta edits `## Data Contracts → Error` and several deltas add `## Invariants`. The live specs already contain these sections, so the sync carries them, but at `/opsx:sync`/archive (Phase 6) the merged Error contract and the new INV-* SHALL be visually confirmed in the base specs before archiving.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JUnit) | `MopScorer` fallback/containment/eventType; `Config` defaults+clamp | Existing `MopScorerTest`/`MopDataTest`/`ConfigTest`, no device | ~10-12 |
| Device | A-3 trace has 0 triggers without explicit pct; `[APE-STEP]` one-per-action; secure app opens breaker; coverage bounded | Real-device run on a small APK set | manual gates |

No PBT layer (APE has no PBT harness). A-4/A-5 logic that needs the live agent/tree is validated on device, not JUnit.

## Open Questions

- Exact `stateData` bound value (configurable; pick a default during implementation that covers observed state counts — mean ~22/Activity).
- Whether `[APE-STEP]` verbosity needs its own Config toggle or reuses an existing log-level flag (decide in implementation; default on).
