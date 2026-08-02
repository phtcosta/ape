# action-selection Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the per-action decision attribution stops being an `[APE-STEP]` `key=value` line and becomes the envelope and `dec` section of the step's `StepRecord` (event-sink capability). Everything the line carried — decision source, pick channel, priority, per-mechanism boosts (including `mop_frontier`), patch provenance, counterfactual result, wall clock, step key — remains recorded; the boost-attribution rule and the `decisionSource`/`pickChannel` provenance fields on `ModelAction` are unchanged. The `stepTelemetryEnabled` gate is deleted: recording is always-on and identical for all arms, and the protected property moves to the neutrality invariant (INV-SNK-07). This delta is written against the behavior at HEAD (`5dcf225`), which includes the `telemetry-proof-llm-efficacy` fields (`mop_frontier`, `pick_channel`, `patched`, counterfactual), and is expressed to apply on top of `rearch-03-decision-pipeline`'s stage-stamped restatement of this requirement (stage stamping, the `StageResult`-label equality, and the channel production rules live in the decision-pipeline capability and are unchanged here).

## MODIFIED Requirements

### Requirement: Per-action decision-source telemetry

For every action returned by `selectNewActionNonnull()`, `StatefulAgent.resolveNewAction()` SHALL record one `StepRecord` decision section (event-sink capability), after the action is finalized and before it is executed. Recording is unconditional — no configuration key gates it, and every experimental arm records identically. The record SHALL attribute the action to a `decision_source` (`dec.src`) and a pick channel (`dec.ch`), and include the non-zero per-mechanism boosts.

To attribute LLM and other early-return paths that bypass `logActionSelected`, `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection (by the selecting `DecisionPipeline` stage, per the decision-pipeline capability). The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`) or the EARLY_STAGE MOP preference (`pickBestMopTarget`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getMopFrontierBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage`. In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority (graph-navigation and shortest-path picks, the Back-/Menu-unvisited short-circuits, and `greedyPickLeastVisited`, where priority is only a tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome — the counterfactual claim is exactly what `dec.cf` carries, on the four MOP-sensitive pick channels only.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `MopFrontier`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`.

The record's envelope SHALL carry the step number `s` — the agent exploration timestamp (`getTimestamp()`) at selection time, incremented exactly once per agent step, hence unique within a run — and the time `t` in milliseconds since `RUN_START` (whose record carries the epoch base; the device wall clock remains recoverable as `t0 + t`, preserving offline temporal joins with externally collected artifacts without any APE↔logcat coupling — the heartbeat aside, APE SHALL NOT read from logcat). There is no join key: the outcome attaches to the same record (scoring-pipeline capability).

- **INV-SEL-04**: Exactly one `StepRecord` SHALL be recorded per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns, in every arm. The record SHALL carry a `dec.src` from the fixed enum, never a free-form string. The boost-attribution rule SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate records, and SHALL NOT modify any boost field.

#### Scenario: SATA-selected action recorded

- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `StepRecord` SHALL be recorded with `dec.src:"SATA"`
- **AND** its envelope SHALL include `s` and `t`, and its `dec` SHALL omit all boost fields (defaults-omitted, INV-SNK-05)

#### Scenario: MOP-boosted roulette pick attributed to MOP

- **WHEN** the EARLY_STAGE unvisited roulette picks a `ModelAction` whose boosts are `mop=500`, all others 0
- **THEN** the action's `decisionSource` SHALL be `MOP` and the record SHALL carry `dec.src:"MOP"` and `dec.mop:500`

#### Scenario: Tie precedence includes MopFrontier

- **WHEN** a roulette pick carries boosts `mop=300, mopf=300`
- **THEN** the record SHALL carry `dec.src:"MOP"`
- **AND** when the tie is instead `mopf=300, wtg=300`, `dec.src` SHALL be `"MopFrontier"`

#### Scenario: Boosted action in a priority-blind branch stays SATA

- **WHEN** a `ModelAction` carrying `mop=500` is selected by a branch that does not consume priority (e.g. `greedyPickLeastVisited`, visit-count minimum)
- **THEN** the record SHALL carry `dec.src:"SATA"` with `dec.mop:500` still visible as a boost field

#### Scenario: LLM early-return attributed

- **WHEN** the new-state LLM hook returns a non-null action, bypassing `logActionSelected`
- **THEN** that action's `decisionSource` SHALL be `LLM` and exactly one record SHALL carry `dec.src:"LLM"` for it, with the call's `llm[]` sub-event in the same record

#### Scenario: Every step is attributable in every arm

- **WHEN** any run completes, under any preset
- **THEN** every executed action SHALL have exactly one `StepRecord`
- **AND** no selection path SHALL produce zero or more than one record for a single action
- **AND** no configuration SHALL exist that suppresses or alters the recording (the removed `ape.stepTelemetryEnabled` key aborts plan validation as unknown)
