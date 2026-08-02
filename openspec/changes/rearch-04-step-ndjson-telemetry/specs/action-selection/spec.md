# action-selection Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the per-action decision attribution stops being an `[APE-STEP]` `key=value` line and becomes the envelope and `dec` section of the step's `StepRecord` (event-sink capability). Everything the line carried — decision source, pick channel, priority, per-mechanism boosts (including `mop_frontier`), patch provenance, counterfactual result, wall clock, step key — remains recorded; the boost-attribution rule and the `decisionSource`/`pickChannel` provenance fields on `ModelAction` are unchanged. The `stepTelemetryEnabled` gate is deleted: recording is always-on and identical for all arms, and the protected property moves to the neutrality invariant (INV-SNK-07). This delta is written against the behavior at HEAD (`5dcf225`), which includes the `telemetry-proof-llm-efficacy` fields (`mop_frontier`, `pick_channel`, `patched`, counterfactual), and is expressed to apply on top of `rearch-03-decision-pipeline`'s stage-stamped restatement of this requirement (stage stamping, the `StageResult`-label equality, and the channel production rules live in the decision-pipeline capability and are unchanged here).

## Invariants

Disposition of the capability's `INV-SEL-*` block, which the stage-4 telemetry change invalidates in place (these invariants live in a top-level `## Invariants` section of `openspec/specs/action-selection/spec.md`, not inside a requirement, so they are dispositioned here rather than by a requirement operation):

- **INV-SEL-04** — restated below under "Per-action decision-source telemetry": the `stepTelemetryEnabled` gate and the "zero `[APE-STEP]` lines in the `ape_pure` arm" clause are **deleted with their subjects** (the key is removed by this change and aborts plan validation as unknown; the `ape_pure` arm is retired by `rearch-05-thin-python-arms`). Exactly one `StepRecord` per finally-selected action, in every arm, is the replacement.
- **INV-SEL-05** — restated: the per-step `pick_channel` becomes `dec.ch` on the `StepRecord`, carrying exactly one member of the same fixed enum (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`, `launcher`, `llm`, `buffer`, `sata_other`), never a free-form string, set as provenance at the pick site and covering every early-return path. The `stepTelemetryEnabled` precondition is dropped: recording is unconditional.
- **INV-SEL-06** — restated: the MOP-screen bit is no longer a field repeated on every line. It is carried once per activity on the `ACT` dictionary record (`event-sink` INV-SNK-06) and reached from a step as `st → ACT.mop`; its value SHALL still equal `MopData.activityHasMop(<current activity>)` when `MopData` is non-null and `0` when it is null. The bit SHALL be resolvable for every recorded step, never absent.
- **INV-SEL-01/02/03** are untouched by this change (tiebreak semantics); INV-SEL-01's `ape_pure` framing is dispositioned by `rearch-03-decision-pipeline`, which owns the tiebreaker requirement.

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

### Requirement: Per-step counterfactual attribution

When the step's action was picked by one of the four MOP-sensitive channels (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`), the `StepRecord`'s decision section SHALL additionally carry `dec.cf` with the counterfactual action and whether it differs from the factual pick: the action the same channel would have selected with `mopBoost` and `mopFrontierBoost` zeroed on every candidate (all other boosts and the candidate set unchanged). The counterfactual action SHALL use the same action rendering as `dec.action`. Records from other channels (LLM, launcher, buffer, `sata_other`) SHALL NOT carry `dec.cf` — MOP boosts do not participate in those picks. `dec.cf` is exempt from the defaults-omitted rule: its absence is itself information, so it is emitted whenever defined (`event-sink` INV-SNK-05). The `stepTelemetryEnabled` gate of the pre-change requirement is deleted with the key: the counterfactual is recorded unconditionally, in every arm.

Channel semantics:
- The two short-circuits exist only because of MOP boosts: their counterfactual is the pick of the channel they short-circuit (the roulette/least-visited fall-through with MOP weights zeroed), and the changed flag is set whenever the short-circuit fired on a target the fall-through would not have picked.
- The two roulettes recompute the weighted pick over the same candidates with per-action priority reduced by that action's `mopBoost + mopFrontierBoost`.

**RNG stream isolation (hard constraint).** The counterfactual recomputation SHALL NOT advance or otherwise perturb the seeded RNG stream that drives selection (INV-EXPL-14 — the seeded run's action sequence must be bit-identical with the counterfactual computation on or off). The roulette counterfactual SHALL reuse the factual pick's recorded draw (as a fraction of total weight) rather than drawing again; the short-circuit counterfactuals are deterministic and draw nothing. A dedicated test SHALL assert sequence identity under a fixed seed with the counterfactual enabled and disabled — perturbing the stream would be a silent bias of the exact class the calibration autopsy catalogued. This is a distinct property from sink neutrality (`event-sink` INV-SNK-07) and both tests SHALL exist.

**Honest caveat (interpretation rule, part of this contract):** the counterfactual is 1-step myopic. It establishes the *divergence point* — "did the MOP boost change THIS pick?" — not the cumulative trajectory effect, which only an arm-level contrast (MOP-off arm, rv-android side) can measure. Offline analysis SHALL NOT sum the changed flag into a claim about end-of-run coverage.

**Failure containment:** if the recomputation fails for any reason, the record SHALL carry the counterfactual as unchanged and selection SHALL be unaffected (the factual pick was already made before the counterfactual runs).

#### Scenario: MOP short-circuit divergence recorded

- **WHEN** the unvisited-MOP short-circuit selects action A (`mopBoost=500`) and the fall-through roulette with MOP weights zeroed would have picked action B
- **THEN** the record SHALL carry `dec.ch:"short_circuit_unvisited"` and a `dec.cf` naming B as the counterfactual action with the changed flag set

#### Scenario: roulette pick unchanged by MOP weights

- **WHEN** the epsilon-greedy roulette picks action C and the recomputation with `mopBoost`/`mopFrontierBoost` zeroed — replaying the same recorded draw fraction — also picks C
- **THEN** `dec.cf` SHALL report the counterfactual action as C with the changed flag clear

#### Scenario: MOP-off arm is counterfactually inert

- **WHEN** a run executes with all MOP weights zero (MOP-off arm)
- **THEN** every emitted `dec.cf` SHALL have the changed flag clear
- **AND** any set flag in such a run SHALL be treated as a defect in the counterfactual implementation (smoke-gate invariant)

#### Scenario: seeded sequence identical with counterfactual on and off

- **WHEN** two runs execute with the same seed, APK, and configuration, one with the counterfactual computation enabled and one with it disabled
- **THEN** the sequence of selected actions SHALL be identical (the live RNG stream consumed exactly the same draws)

#### Scenario: non-MOP channels carry no counterfactual

- **WHEN** a step is picked by the LLM stage or the buffer
- **THEN** its `StepRecord` SHALL NOT contain a `dec.cf` member
