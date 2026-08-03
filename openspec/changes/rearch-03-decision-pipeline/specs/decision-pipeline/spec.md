## Purpose

The decision pipeline is the explicit form of APE-RV's action-selection policy. Before this capability, the policy — the research-relevant hard-precedence ladder in which the budget gate, the three LLM hooks, the MOP census launcher, the component trigger, and the SATA strategy chain compete for each step — existed only as the textual order of guarded `if` blocks inside `SataAgent.selectNewActionNonnull()` (141 LOC, verified V1 of `docs/analise_fable-selecao.md` rev. 3). The order was correct and semantically deliberate (owner decision Q1: hard preemption, never weighted sampling), but nothing in the code declared it, nothing could enumerate it, an undocumented cross-feature interaction (finding 3.3-1: an LLM-preempted step does not advance the launcher cadence) existed only by accident of line order, and the return convention overloaded `null` to mean three different things.

This capability replaces the ladder's *form* while preserving its *semantics exactly*. A `DecisionPipeline` is an ordered list of named `DecisionStage`s, assembled exactly once per run from the `RunSpec` (the immutable plan introduced by `run-spec`). Each stage's `decide(StepContext)` returns a `StageResult` — a closed sum type with three variants: `Select` (an action and its decision source; the step is decided, later stages are not evaluated), `Continue` (pass to the next stage), and `SideEffect` (an effect was performed and recorded, selection continues — the component trigger's shape). A feature absent from the plan means its stage is absent from the list: purity is structural, not a kill-switch — this generalizes the scoring pipeline's INV-ARCH-03 ("disabled pass is absent and inert") to the whole decision path.

The pipeline is policy structure, not policy change: stage order is fixed in code (precedence is research semantics, stable per release, never editable per arm — report Sec. 7), every guard and RNG draw relocates verbatim, and the migration is gated by the `parity-oracle` per-preset goldens. Episode-scoped state acquires single owners: the LLM stagnation single-shot flag lives in the `LlmStagnation` stage; the launcher's cadence, budget, and round-robin counters live in the `MopLauncher` stage; resets travel over an explicit transition-event hook instead of scattered field writes across three classes. LLM fallback is declared in the plan and realized structurally: an LLM stage that declines, fails, or is blocked by the circuit breaker returns `Continue`, and the remainder of the configured pipeline — which *is* the fallback base (`mop` or `aperv`) — decides the step.

## Data Contracts

### Input
- `RunSpec` — the validated immutable plan (features, `ExplorationParams`, `MopParams`, `LlmParams`) — source: `run-spec` capability, resolved at bootstrap
- `RunContext` — per-run mutable state owner (model, graph, seeded RNG, MopData, LLM units, trackers)
- `StepContext` — per-step view: current `State`/`GUITree`, action buffer size, `graphStableCounter` (read + a single reset method), timestamp, RNG, MopData, graph, budget tracker, action history

### Output
- `Action` — the selected action for the step (widget action, trivial-navigation action, LLM-matched or synthesized action, or `EVENT_TRIGGER_ACTIVITY`)
- `StageResult.decisionSource: String` — the decision-source label of the selecting stage, consumed by step telemetry

### Side-Effects
- **[Trace]**: one `[APE-ARCH] stages=[...]` line at assembly, listing the assembled stages in order (R6: stage order echoed at run start)
- **[Trace]**: unchanged emission of `[APE-STEP]`, `[APE-OUTCOME]`, and `[APE-LLM-*]` lines by their existing owners
- **[Android runtime]**: component trigger side effects (broadcast/service) via the `ComponentTrigger` stage, semantics unchanged

### Error
- `BadStateException` — thrown by the terminal `SataChain` stage when no rung yields an action (unchanged from `SataAgent.java:588`)
- `IllegalStateException` — wrong-variant accessor read on a `StageResult` (consumer bug; unreachable in the shipping pipeline loop)

## Invariants

- **INV-DP-01**: The pipeline SHALL be assembled exactly once per run, from the `RunSpec`, before the first step. Stage order SHALL be fixed in code — never data, never per-arm — and SHALL be echoed once as `[APE-ARCH] stages=[...]`.
- **INV-DP-02**: Preemption SHALL be hard: the first stage returning `Select` decides the step, and no later stage's `decide()` is evaluated on that step — including any counter increments or RNG draws those stages would have performed. Weighted or probabilistic arbitration between stages SHALL NOT exist.
- **INV-DP-03**: A feature absent from the plan SHALL have no stage in the pipeline (feature absent = stage absent). There SHALL be no runtime "disabled stage" no-op object; absence is structural. This generalizes scoring-pipeline INV-ARCH-03.
- **INV-DP-04**: `StageResult` SHALL be a closed sum type (`SELECT` / `CONTINUE` / `SIDE_EFFECT`); `decide()` SHALL never return null. A `SELECT` SHALL carry a non-null action and a non-null decision-source label; when the action is a `ModelAction`, the label SHALL equal `action.getDecisionSource().name()`.
- **INV-DP-05**: A `SIDE_EFFECT` result SHALL never end the step; the pipeline SHALL record it and continue with the next stage.
- **INV-DP-06**: The terminal stage (`SataChain`) SHALL always be present and SHALL never return `Continue`: it selects an action or throws `BadStateException`. The pipeline SHALL never fall off the end of the stage list.
- **INV-DP-07**: Every piece of episode-scoped decision state SHALL be owned by exactly one stage (stagnation single-shot flag → `LlmStagnation`; launcher cadence/budget/round-robin counters → `MopLauncher`; component round-robin cursor → `ComponentTrigger`). Resets SHALL occur only through the declared transition-event hook or the stage's own `decide()`; no stage SHALL read or write another stage's episode state. `graphStableCounter` is exploration state shared with the forced-restart mechanism, not episode state; it stays agent/`RunContext`-owned, with the `LlmStagnation` stage's reset-on-escape as the single stage-side write.
- **INV-DP-08**: A step decided by an LLM stage SHALL NOT advance the `MopLauncher` cadence counter (finding 3.3-1, preserved current behavior, decided and pinned by a permanent test). The counter advances only on steps where the `MopLauncher` stage is actually evaluated.
- **INV-DP-09**: The pipeline SHALL reproduce the pre-change decision sequences exactly: for each preset (`aperv`, `mop`, `llm`, `llm_mop`), the `parity-oracle` goldens under identical seeds SHALL pass. This is the acceptance gate of the capability.
- **INV-DP-10**: The pipeline SHALL consume the seeded RNG stream in exactly the pre-change draw order: conditional draws (LLM random coin, component-trigger coin, SATA roulettes) SHALL occur at the same points, under the same guards, in the same order.
- **INV-DP-11**: An LLM stage SHALL return `Continue` on precondition failure, trigger-predicate failure, circuit-breaker denial, and every engine failure or refused answer (decline, no-match, dead-pair ban, boundary/degenerate reject). LLM fallback SHALL be realized only by this fall-through to the remainder of the configured pipeline — never by retry, never by throw.
- **INV-DP-12**: No stage, engine, LLM unit, or scoring pass SHALL read static `Config` at decision time; every behavioral parameter of the decision path SHALL be injected from the `RunSpec` at assembly. (Documented residue: `State.getActions()`'s `modelMenuEnabled` structural gate — see design D9.)

## ADDED Requirements

### Requirement: DecisionStage Contract and StageResult Sum Type

The decision path SHALL be expressed as an ordered sequence of `DecisionStage` objects. The interface SHALL be:

```java
public interface DecisionStage {
    String name();                                    // for the assembly echo and telemetry
    StageResult decide(StepContext ctx);
    default void onStateTransition(StateTransition edge) { }  // episode-state reset channel
}
```

`StageResult` SHALL be a single final class with a private constructor, a `Kind` enum tag (`SELECT`, `CONTINUE`, `SIDE_EFFECT`), and static factories `select(Action, String decisionSource)`, `continueChain()` (a shared singleton), and `sideEffect(String description)`. Variant accessors (`action()`, `decisionSource()`, `description()`) SHALL throw `IllegalStateException` when read on the wrong variant. `decide()` SHALL never return null (INV-DP-04). For a `ModelAction`, the stage SHALL stamp the action's `decisionSource`/`pickChannel` provenance exactly as the pre-change pick sites did, and the `select(...)` label SHALL equal the stamped source's name.

#### Scenario: Select carries action and source
- **WHEN** a stage returns `StageResult.select(action, "LLM")` for a `ModelAction` whose stamped provenance is `DecisionSource.LLM`
- **THEN** `kind()` SHALL be `SELECT`, `action()` SHALL return the action, and `decisionSource()` SHALL return `"LLM"`
- **AND** the label SHALL equal `action.getDecisionSource().name()`

#### Scenario: wrong-variant accessor fails loudly
- **WHEN** `action()` is invoked on `StageResult.continueChain()`
- **THEN** an `IllegalStateException` SHALL be thrown

#### Scenario: decide is total
- **WHEN** any assembled stage's `decide(ctx)` runs, on any step
- **THEN** it SHALL return exactly one non-null `StageResult`
- **AND** it SHALL NOT throw, except the terminal stage's `BadStateException` (INV-DP-06)

---

### Requirement: Pipeline Assembly from the RunSpec

`DecisionPipeline.fromSpec(RunSpec, RunContext)` SHALL be the single assembly point. It SHALL append stages in the fixed order `Budget → LlmNewState → LlmStagnation → LlmRandom → MopLauncher → ComponentTrigger → SataChain`, skipping every stage whose gating feature is absent from the plan (INV-DP-03):

| Stage | Present when |
|---|---|
| `Budget` | activity-budget feature in the plan |
| `LlmNewState` | LLM feature ∧ `llm.onNewState` |
| `LlmStagnation` | LLM feature ∧ `llm.onStagnation` |
| `LlmRandom` | LLM feature ∧ `llm.percentage > 0` |
| `MopLauncher` | activity-trigger feature (requires MOP) |
| `ComponentTrigger` | component-trigger feature (`componentPercentage > 0`, requires MOP) |
| `SataChain` | always |

Assembly SHALL happen exactly once per run and SHALL emit one `[APE-ARCH] stages=[...]` line listing the assembled stages' `name()` values in order (INV-DP-01). Every parameter a stage reads SHALL be injected at assembly from the `RunSpec` (INV-DP-12); a stage SHALL NOT read static `Config` in `decide()`.

**The echo SHALL also name the candidate stages, as a static list.** Beside the assembled stages, assembly SHALL emit the full fixed candidate order with, for each, whether it was assembled — carried by the stage-4 `PIPELINE` record as `stages` and `candidates`. On the stage side this costs nothing beyond the list itself and needs no reason field: a stage is absent exactly when its gating feature is absent from the plan (INV-DP-03), which `RUN_START.features` already states. The list SHALL be a static declaration of names — never a constructed no-op stage object to enumerate, which INV-DP-03 forbids and which this requirement must not be read as reintroducing.

It matters on the *pass* side, and that is where the evidence is. The scoring passes become absent for a reason nothing in the plan reveals — `WtgPass`, `FrontierPass` and `MopFrontierPass` each gate on run-time data — and over the decisive campaign the whole family is never constructed in 25 of the 40 applications. Making the stage census static and the pass census data-bearing is the distinction; the pass half is specified in the scoring-pipeline capability.

#### Scenario: full llm_mop plan assembles all stages
- **WHEN** the plan carries the activity-budget, LLM (all three modes), MOP, activity-trigger, and component-trigger features
- **THEN** the pipeline SHALL be `[Budget, LlmNewState, LlmStagnation, LlmRandom, MopLauncher, ComponentTrigger, SataChain]`
- **AND** one `[APE-ARCH] stages=[Budget, LlmNewState, LlmStagnation, LlmRandom, MopLauncher, ComponentTrigger, SataChain]` line SHALL be emitted

#### Scenario: aperv plan assembles the minimal pipeline
- **WHEN** the plan carries no LLM, no MOP, no component-trigger feature (activity budget on)
- **THEN** the pipeline SHALL be `[Budget, SataChain]`
- **AND** no LLM unit, launcher counter, or trigger machinery SHALL exist in the run (feature absent = stage absent, structurally)

#### Scenario: stage order is not configurable
- **WHEN** any plan is resolved, with any overrides
- **THEN** the relative order of the assembled stages SHALL be the fixed order above
- **AND** no configuration key SHALL exist that reorders stages

---

### Requirement: Hard Preemption Order

The pipeline loop SHALL evaluate stages strictly in assembled order and SHALL end the step at the first `Select` (INV-DP-02). A `SideEffect` SHALL be recorded and evaluation SHALL continue (INV-DP-05). The order reproduces the verified ladder (V1/V4): the LLM stages preempt the MOP launcher; the component trigger never ends a step; the SATA chain is the fallback of every configured pipeline.

#### Scenario: preemption golden — all contenders qualify at once (report Sec. 9.4)
- **WHEN** a synthetic step simultaneously satisfies the LLM new-state trigger, the launcher firing point, the component-trigger coin, and has SATA candidates, and the stubbed LLM returns an accepted action
- **THEN** the step's action SHALL come from `LlmNewState` with `decision_source=LLM`
- **AND** the `MopLauncher` cadence counter SHALL NOT advance on that step (INV-DP-08)
- **AND** no component trigger SHALL fire and no SATA rung SHALL be evaluated

#### Scenario: LLM preemption does not advance the launcher cadence (finding 3.3-1, pinned)
- **WHEN** the launcher counter is at N and an LLM stage selects the step's action
- **THEN** the counter SHALL still be N after the step
- **AND WHEN** the next step falls through the LLM stages to the launcher
- **THEN** the counter SHALL be N+1 at the launcher's firing check

#### Scenario: side effect never decides the step
- **WHEN** the `ComponentTrigger` stage fires its coin and dispatches a broadcast
- **THEN** it SHALL return `SideEffect`, the dispatch SHALL be recorded, and the `SataChain` stage SHALL still select the step's action

#### Scenario: launcher preempts the SATA chain when it fires
- **WHEN** no LLM stage selects, the launcher is at its firing point, and an eligible census candidate exists
- **THEN** the step's action SHALL be the `EVENT_TRIGGER_ACTIVITY` action with `decision_source=Component`
- **AND** the SATA chain SHALL NOT be evaluated that step

---

### Requirement: Budget Stage Semantics

The `Budget` stage SHALL reproduce the budget gate (`SataAgent.java:468-477`): when the current activity's budget is exhausted and a trivial-activity navigation action exists, it SHALL return `Select(trivial, Budget)` with the action stamped `DecisionSource.Budget` / `PickChannel.SATA_OTHER` (the gate **returns** in this case — report Sec. 3.2 correction); when the budget is exhausted but no trivial action exists, and when the budget is not exhausted, it SHALL return `Continue` (advisory fall-through — no BACK, no RESTART, per the gh9 semantics).

#### Scenario: exhausted budget with trivial action selects it
- **WHEN** the activity's budget is exhausted and `selectNewActionForTrivialActivity()` yields a non-null action
- **THEN** the stage SHALL return `Select` with `decision_source=Budget`

#### Scenario: exhausted budget without trivial action falls through
- **WHEN** the budget is exhausted and no trivial-activity action exists
- **THEN** the stage SHALL return `Continue` and the remaining stages SHALL evaluate normally

---

### Requirement: Episode-State Ownership and Reset

Episode-scoped decision state SHALL live inside its owning stage (INV-DP-07): the stagnation single-shot flag in `LlmStagnation`; the launcher cadence counter, per-run launch budget counter, and round-robin cursor in `MopLauncher`; the component round-robin cursor in `ComponentTrigger`. The agent SHALL forward each visited `StateTransition` to every assembled stage's `onStateTransition(edge)` hook exactly once, after its own stability-counter bookkeeping; `LlmStagnation` SHALL re-arm its flag on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (the same event that resets `graphStableCounter`). `graphStableCounter` itself SHALL remain agent/`RunContext`-owned shared exploration state (the forced-restart mechanism consumes it); `LlmStagnation` MAY reset it to 0 on an accepted escape through the `StepContext`'s single declared write method, and no other stage SHALL write it.

#### Scenario: stagnation flag burns on firing and re-arms on a new edge
- **WHEN** the stagnation trigger fires (counter ≥ threshold/2, flag armed) and the LLM returns null
- **THEN** the flag SHALL be burned (no second attempt this episode) while the counter keeps incrementing toward the restart threshold
- **AND WHEN** a later step records a `NEW_ACTION` edge
- **THEN** the hook's `onStateTransition` SHALL re-arm the flag (new episode)

#### Scenario: accepted escape resets the shared counter
- **WHEN** the stagnation trigger fires and the LLM returns an accepted action
- **THEN** the stage SHALL reset `graphStableCounter` to 0 through the `StepContext` write method
- **AND** no other counter or stage state SHALL be touched

#### Scenario: launcher counters are invisible outside the stage
- **WHEN** any code outside `MopLauncher` executes
- **THEN** it SHALL have no read or write access to the cadence counter, launch budget counter, or round-robin cursor

---

### Requirement: LLM Fallback Realized Structurally

The LLM stages SHALL implement fallback exclusively as `Continue` (INV-DP-11): the remainder of the assembled pipeline is the declared fallback base from the plan (`mop` ⇒ launcher/trigger/SATA-with-MOP-scoring; `aperv` ⇒ SATA chain). Decline, no-match, dead-pair ban, boundary/degenerate reject, screenshot/image/HTTP/parse failure, and breaker denial SHALL all take this one path. No LLM stage SHALL retry within a step, throw, or select a substitute action itself.

#### Scenario: LLM failure falls through to the configured remainder (report Sec. 9.5)
- **WHEN** the plan is `llm_mop`, the stubbed LLM times out on a step, and the launcher is not at a firing point
- **THEN** the step's action SHALL come from the `SataChain` stage
- **AND** its `decision_source` SHALL be the chain's attribution (`SATA` or a boost source), never `LLM`

#### Scenario: breaker-open steps decide without the LLM
- **WHEN** the circuit breaker is OPEN for a window spanning several steps
- **THEN** every step in the window SHALL be decided by the non-LLM remainder of the pipeline
- **AND** the breaker-open trace line SHALL be emitted once per open episode (unchanged latch semantics)

---

### Requirement: Terminal SataChain Stage

The `SataChain` stage SHALL always be assembled last and SHALL preserve the seven rungs in order — buffer, back-to-activity, early-stage forward, trivial-activity, early-stage backward, epsilon-greedy, null-handler — with the same `SataEventType` logging per rung and `BadStateException` when all rungs yield null (INV-DP-06). Internally the 7× copied `resolved`/`if`/`return` pattern MAY be expressed as an ordered rung table walked by one loop; the rung order, per-rung logging, RNG consumption, and provenance stamping SHALL be unchanged. The scoring of candidate actions remains the `scoring-pipeline` capability's job, executed in `adjustActionsByGUITree()` before selection (INV-EXPL-11 unchanged), now with real parameter injection.

#### Scenario: rung order preserved
- **WHEN** the buffer is empty and the back-to-activity rung yields an action
- **THEN** the stage SHALL return it with `SataEventType.TRIVIAL_ACTIVITY` logged, without evaluating later rungs

#### Scenario: exhausted chain throws
- **WHEN** all seven rungs return null
- **THEN** the stage SHALL throw `BadStateException("No available action on the current state")`

---

### Requirement: Migration Parity Gate

The capability SHALL be accepted only when the `parity-oracle` per-preset goldens pass against the pipeline implementation (INV-DP-09): identical seeds, fixtures, and stubbed LLM scripts produce byte-identical decision sequences for `aperv`, `mop`, `llm`, and `llm_mop`. The preemption golden (incl. the 3.3-1 pin) and the structural-fallback test SHALL be promoted into the permanent test suite of this capability.

#### Scenario: per-preset golden equality
- **WHEN** the golden harness replays each preset's fixture set against the pipeline build
- **THEN** the recorded decision sequence SHALL be identical to the captured pre-change golden for every preset

#### Scenario: goldens gate every extraction step
- **WHEN** any single stage extraction lands
- **THEN** the full golden suite SHALL pass before the next extraction begins
