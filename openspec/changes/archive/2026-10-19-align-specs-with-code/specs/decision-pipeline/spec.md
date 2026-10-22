## Purpose

The decision pipeline is the explicit form of APE-RV's action-selection policy for the `SataAgent`. A
`DecisionPipeline` is an ordered list of named `DecisionStage`s, assembled exactly once per run from the `RunSpec`
by `DecisionPipeline.fromSpec(RunSpec, StageCollaborators)`, which the `SataAgent` constructor calls. `RandomAgent`
and `ReplayAgent` have no pipeline. Each stage's `decide(StepContext)` returns a `StageResult`, a closed sum type with
three variants: `Select` (an action and its owner; the step is decided, later stages are not evaluated), `Continue`
(pass to the next stage), and `SideEffect` (an effect was performed and recorded, selection continues — the
component trigger's shape). A feature absent from the plan means its stage is absent from the list: absence is
structural, not a kill-switch, which is the scoring pipeline's INV-ARCH-02 rule ("disabled pass is absent and
inert") applied to the whole decision path.

The pipeline is policy structure: stage order is fixed in code (`DecisionPipeline.Candidate`), never editable per
cell, and it is hard precedence, never weighted sampling. The step is a fixed sequence of **owners** (design D-B7):

    Budget, Restart, LlmNewState, LlmStagnation, LlmCoin, MopLauncher, ComponentTrigger, MopCoin, SataChain

(the stage `name()` values; the `Candidate` constants spell the same order `BUDGET, RESTART, LLM_NEW_STATE, …,
SATA_CHAIN`). Each step records the owner that took it. The restart is a guard stage that runs before any
selection, so no restart replaces an action after selection. At step open, before any stage runs,
`StepRouting.open` draws the LLM coin `u_L`, the MOP coin `u_M` and the component coin `u_C` of the mounted groups,
each from its own `StepRng` stream. The fuzz toss draws from a fourth `StepRng` stream, once per step, in
`MonkeySourceApe.generateEvents` after the step's action events are generated. Mounting a gate, a coin or fuzzing
therefore never shifts SATA's draws (P-3, D-B9, D-B13). The component trigger is a STATIC member (D-B13). The
launcher's cadence is global (D-A16, D-B11): it advances on every step, whichever stage takes it. An owner that
preempts a non-empty SATA buffer abandons it without weakening the graph (P-4). Episode-scoped state has single
owners: the LLM stagnation single-shot flag lives in the `LlmStagnation` stage; the launcher's cadence and launch
state live in the `MopLauncher` stage; the component round-robin cursor lives in the `ComponentTrigger` stage;
resets travel over the `onStateTransition` hook. LLM fallback is structural: an LLM stage that declines, fails, or is
refused by a gate returns `Continue`, and the remainder of the cell's assembled pipeline decides the step. The
`parity-oracle` goldens gate the pipeline.

## Invariants

- **INV-DP-01**: The pipeline SHALL be assembled exactly once per run, from the `RunSpec`, by
  `DecisionPipeline.fromSpec` in the `SataAgent` constructor, before the first step. Stage order SHALL be fixed in
  code (the `DecisionPipeline.Candidate` order) — never data, never per cell — and the assembled roster SHALL be
  echoed once, as the Logger line `[APE-ARCH] stages=[...]` and as the `PIPELINE` record's `stages` and
  `stageCandidates`.
- **INV-DP-02**: Preemption SHALL be hard: the first stage returning `Select` decides the step, and no later stage's
  `decide()` SHALL be evaluated on that step, including any counter change or draw that `decide()` would have
  made. The step-open work — the `StepRouting` coin draws and every assembled stage's `onStepOpen` — is not part of
  `decide()` and runs on every step (INV-DP-08, INV-DP-13). Weighted or probabilistic arbitration between stages
  SHALL NOT exist.
- **INV-DP-03**: A feature absent from the plan SHALL have no stage in the pipeline (feature absent = stage absent).
  There SHALL be no runtime "disabled stage" no-op object; the candidate census (`PIPELINE.stageCandidates`) is a
  static list of names, never a constructed stage. This is the scoring pipeline's INV-ARCH-02 rule applied to the
  decision path.
- **INV-DP-04**: `StageResult` SHALL be a closed sum type (`SELECT` / `CONTINUE` / `SIDE_EFFECT`); `decide()` SHALL
  never return null. A `SELECT` SHALL carry a non-null action and a non-null owner label (`StageResult.select`
  rejects null for either). When the action is a `ModelAction`, the label SHALL equal
  `action.getDecisionSource().owner()`; this equality is asserted per stage by tests, not enforced by the factory.
- **INV-DP-05**: A `SIDE_EFFECT` result SHALL never end the step; `DecisionPipeline.decide` SHALL record its
  description (`lastStepSideEffects()`) and continue with the next stage. The component dispatch itself is written
  on the step record as `dec.trig`.
- **INV-DP-06**: The terminal stage (`SataChain`) SHALL always be present (its candidate has no gate) and last, and
  SHALL never return `Continue`: it selects an action or throws `BadStateException`. If the walk ever reaches the
  end of the roster, `DecisionPipeline.decide` SHALL throw `IllegalStateException` rather than return null.
- **INV-DP-07**: Every piece of episode-scoped decision state SHALL be owned by exactly one stage: the stagnation
  single-shot flag (`firedThisEpisode`) by `LlmStagnation`; the cadence counter, the launch count, the round-robin
  cursor and the launched-activity set by `MopLauncher`; the component round-robin cursor by `ComponentTrigger`.
  It SHALL change only through the stage's own `onStepOpen`, `decide()` or `onStateTransition`; no stage SHALL read
  or write another stage's episode state. `graphStableCounter` is exploration state shared with the stability
  restart, not episode state; it stays agent-owned, and the `LlmStagnation` stage's reset on an accepted escape
  (`StepContext.resetGraphStableCounter()`) is the single stage-side write.
- **INV-DP-09**: The `parity-oracle` goldens SHALL gate the pipeline: for each cell preset (`base`, `mop`, `as`,
  `as_mop`, `as_llm`, `as_mop_llm`), the decision sequence replayed under the golden's seed, fixtures and scripted
  LLM SHALL equal that cell's committed golden. A change that alters a sequence SHALL declare the behaviour change
  and recapture the goldens on purpose (`parity-oracle` INV-ORA-08).
- **INV-DP-10** (retired): the pre-pipeline draw-order parity it required is superseded by INV-DP-13 — the coins and
  the fuzz toss draw from their own `StepRng` streams, so no stage shares a stream position with SATA.
- **INV-DP-11**: An LLM stage SHALL return `Continue` when its trigger does not hold, when the step's one LLM call is
  already spent, when a gate refuses (`MinActionsGate`, `BreakerGate`), and on every null engine answer (error,
  screenshot failure, no-match, decline, and the refusals the engine reports as null). LLM fallback SHALL be
  realized only by this fall-through to the remainder of the assembled pipeline — never by retry, never by throw.
- **INV-DP-12**: No stage, LLM unit, scoring pass, `MopScorer`, or the agent and model classes the decision path runs
  through (`SataAgent`, `StatefulAgent`, `State`) SHALL read `Config` at decision time; every behavioural parameter
  of the decision path SHALL be injected from the `RunSpec` at assembly or construction. "Read `Config`" covers a
  qualified `Config.<field>`, a bare identifier behind `import static ...Config.<field>`, and the ambient form, a
  plan value fetched at a decision site through `RunContext.current()` rather than received at assembly. Reaching
  the run context at assembly or construction (a constructor deriving its parameters once, e.g.
  `ScoringParams.fromSpec`, `SataAgent.mountExtensionPoints`) is injection. Documented residues of the ambient form:
  the `MODEL_MENU` gate in the `State` constructor (`RunContext.current().spec().has(Feature.MODEL_MENU)`, read once
  per `State`) and `StatefulAgent.inFormCompletionContext()` (`FORM_COMPLETION`, read by `checkInput` after
  selection).
- **INV-DP-13**: `StepRouting.open`, called by `DecisionPipeline.decide` before any stage's `onStepOpen` or
  `decide()`, SHALL draw `u_L` when the `LLM` group is mounted, `u_M` when the `MOP` group is mounted, and the
  component coin `u_C` when `COMPONENT_TRIGGER` is mounted. The fuzz toss SHALL be drawn once per step from
  `StepRng`'s `fuzz` stream, in `MonkeySourceApe.generateEvents` after the step's action events are generated,
  whether or not `FUZZING` is in the plan. Each comes from its own `Random`, derived from the run seed. No LLM
  stage, gate or coin, no MOP coin, no component coin and no fuzz toss SHALL draw from the agent stream
  (`ape.getRandom()`) or the helper stream (`RandomHelper`). For one seed and one fixture set, two runs that differ
  only in mounting or unmounting an LLM gate, or in mounting the MOP coin at `p_M = 0`, SHALL consume the same
  agent- and helper-stream draws and SHALL make the same SATA choices on every step that both runs hand to
  `SataChain`.

## MODIFIED Requirements

### Requirement: DecisionStage Contract and StageResult Sum Type

The decision path SHALL be expressed as an ordered sequence of `DecisionStage` objects. The interface SHALL be:

```java
public interface DecisionStage {
    String name();                                    // for the assembly echo and telemetry
    StageResult decide(StepContext ctx);
    default void onStepOpen(StepContext ctx) { }      // runs for every assembled stage, every step
    default void onStateTransition(StateTransition edge) { }  // episode-state reset channel
}
```

`onStepOpen` SHALL be called once per step for every assembled stage, in assembled order. It runs after
`StepRouting` has drawn the step's coins (INV-DP-13) and before the first `decide()`. It runs whichever stage then
takes the step, including a guard. It SHALL NOT select an action, draw from any random stream, or write the step
record. It exists so that a stage's per-step bookkeeping does not depend on whether the step reaches the stage
(the launcher's cadence, INV-DP-08).

`StageResult` SHALL be a single final class with a private constructor, a `Kind` enum tag (`SELECT`, `CONTINUE`, `SIDE_EFFECT`), and static factories `select(Action, String decisionSource)`, `continueChain()` (a shared singleton), and `sideEffect(String description)`. Variant accessors (`action()`, `decisionSource()`, `description()`) SHALL throw `IllegalStateException` when read on the wrong variant. `decide()` SHALL never return null (INV-DP-04). For a `ModelAction`, the selecting stage SHALL stamp the action's `decisionSource` and `pickChannel` provenance, and the `select(...)` label SHALL equal the stamped source's owner value (`ModelAction.DecisionSource.owner()`: `budget`, `restart`, `llm`, `launcher`, `mop`, `sata`).

#### Scenario: Select carries action and source
- **WHEN** a stage returns `StageResult.select(action, "llm")` for a `ModelAction` whose stamped `decisionSource` is the owner `llm`
- **THEN** `kind()` SHALL be `SELECT`, `action()` SHALL return the action, and `decisionSource()` SHALL return `"llm"`
- **AND** the label SHALL equal the owner value of `action.getDecisionSource()` (`action-selection` "Per-action decision-source telemetry")

#### Scenario: wrong-variant accessor fails loudly
- **WHEN** `action()` is invoked on `StageResult.continueChain()`
- **THEN** an `IllegalStateException` SHALL be thrown

#### Scenario: decide is total
- **WHEN** any assembled stage's `decide(ctx)` runs, on any step
- **THEN** it SHALL return exactly one non-null `StageResult`
- **AND** it SHALL NOT throw, except the terminal stage's `BadStateException` (INV-DP-06)

#### Scenario: step-open hook runs on a step a guard takes
- **WHEN** the plan assembles `[Budget, Restart, MopLauncher, MopCoin, SataChain]` and the `Restart` stage selects step N
- **THEN** `onStepOpen` SHALL have run once for each of the five stages at step N's open
- **AND** `decide()` SHALL have run only for `Budget` and `Restart`

### Requirement: Budget Stage Semantics

The `Budget` stage (`BudgetStage.decide`) SHALL implement the activity-budget gate. When the current activity's
budget is exhausted (`ActivityBudgetTracker.isBudgetExhausted`) and the agent's trivial-activity navigation
(`StageCollaborators.budgetNavigation()`) yields an action, it SHALL log
`[APE-RV] Budget exhausted for <activity>, navigating to trivial activity`, stamp the action
`DecisionSource.Budget` and `PickChannel.BUDGET`, and return `StageResult.select(action, "budget")` (the gate
**returns** in this case). When the budget is exhausted but no trivial-activity action exists, and when the budget
is not exhausted, it SHALL return `Continue` (advisory fall-through — no BACK, no RESTART, per the gh9 semantics).
The trivial-activity search runs only when the budget is exhausted.

#### Scenario: exhausted budget with trivial action selects it
- **WHEN** the activity's budget is exhausted and `selectNewActionForTrivialActivity()` yields a non-null action
- **THEN** the stage SHALL return `Select` with the owner label `"budget"`
- **AND** the step record SHALL carry `dec.src="budget"` and `dec.ch="budget"`

#### Scenario: exhausted budget without trivial action falls through
- **WHEN** the budget is exhausted and no trivial-activity action exists
- **THEN** the stage SHALL return `Continue` and the remaining stages SHALL evaluate normally

### Requirement: Per-Step Own Random Streams

`StepRng` SHALL derive four `java.util.Random` instances from the run seed, `seed ^ k` with a fixed odd
constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin` and `fuzz`. When the step opens
(`StepRouting.open`, before any stage's `onStepOpen` or `decide()`), the pipeline SHALL draw:
- `u_L = llmCoin.nextDouble()` when `LLM` is in the plan;
- `u_M = mopCoin.nextDouble()` when `MOP` is in the plan;
- `u_C = componentCoin.nextDouble()` when `COMPONENT_TRIGGER` is in the plan; the `ComponentTrigger` stage fires
  when `u_C < componentPercentage`.

The fuzz toss (`StepRng.fuzzToss(fuzzingRate)`) SHALL be drawn once per step by `MonkeySourceApe.generateEvents`,
after the step's action events are generated, whether or not `FUZZING` is in the plan and whatever the step's
owner (`exploration` "Fuzzing Integration").

Each value is drawn whether or not a later stage reads it (INV-DP-13). `u_L` and `u_M` reach the stages through
the `StepContext`. When their group is mounted, they are written in `dec.own` as `uL` and `uM`. The agent stream
and the helper stream keep every other draw: the ε-greedy toss, the roulettes, `randomPick`, typed text, and the
restart threshold and kind. No coin draws from the agent stream, so mounting the trigger never shifts SATA's draws.

#### Scenario: an LLM gate does not shift SATA's draws
- **WHEN** two runs of the `as_llm` cell share seed `42` and fixtures, and differ only in `ape.llmGateMinActions` (`3` against `0`, the latter leaving the gate unmounted)
- **THEN** every step that both runs hand to `SataChain` SHALL select the same action in both
- **AND** the helper-stream draw count before each such step SHALL be equal in both

#### Scenario: the coin is drawn on steps a guard takes
- **WHEN** the `LLM` group is mounted and the `Budget` stage selects step N
- **THEN** `u_L` SHALL still have been drawn once at step N's open
- **AND** step N+1's `u_L` SHALL be the stream's next value

#### Scenario: no coin stream is read without its group
- **WHEN** the plan is the `base` cell
- **THEN** no `u_L`, `u_M` or `u_C` SHALL be drawn, and `dec.own` SHALL carry neither `uL` nor `uM`
- **AND** the fuzz toss SHALL still be drawn once on every step, after the step's action events are generated

### Requirement: Buffer Abandonment by a Preempting Owner

When an LLM stage, the `MopLauncher` stage or the `MopCoin` stage selects while `actionBufferSize() > 0`, the
selecting stage SHALL itself call the agent's `abandonBuffer(owner)` — injected at assembly as
`StageCollaborators::abandonBuffer` — with its owner (`llm`, `launcher` or `mop`) before returning `Select` (design
D-B14.13): the LLM stages through `LlmCall.select`, the launcher and the MOP coin in their own `decide()`.
`DecisionPipeline.decide` does not call it. The `Budget` stage abandons the buffer it preempts with owner `budget`
through `budgetNavigation()`, described below (D-B20). `abandonBuffer` SHALL clear the buffer **without**
`weakenStateTransition`, record `dec.buf=<owner>`, and add one to `RUN_END.counters.bufAbandoned`. The edge the
buffer had planned did not fail, so the graph SHALL NOT be penalized (P-4, INV-DP-15). Inside `SataChain` a buffer
whose next action no longer matches the screen is a real path failure, and it SHALL keep weakening as in APE
(`StatefulAgent.clearBuffer`). The `Restart` stage does not call `abandonBuffer`: `StatefulAgent.restartAction()`
empties the buffer before the new episode starts, also without weakening, and the step carries no `dec.buf`
(D-B14.13).

The `Budget` stage's navigation fills the buffer itself, so its abandonment SHALL precede the search: the agent's
`StageCollaborators.budgetNavigation()` detaches the buffer without weakening, runs
`selectNewActionForTrivialActivity()`, restores the detached buffer when the search returns null, and otherwise
records the abandonment of the detached buffer when it was non-empty, leaving the newly planned path in the buffer
(design D-B20). The `Budget` stage SHALL NOT call `abandonBuffer` after the search.

#### Scenario: LLM coin preempts a two-step path
- **WHEN** the buffer holds two planned transitions, `u_L < p_L`, the gates admit, and the LLM returns a matched action
- **THEN** the step's action SHALL be the LLM's, the buffer SHALL be empty after the step, and no `weakenStateTransition` call SHALL have been made
- **AND** the step record SHALL carry `dec.buf="llm"`

#### Scenario: a failed path still weakens
- **WHEN** no preempting owner takes the step and the `SataChain` buffer rung finds that the buffered action's source state is not the current state
- **THEN** the planned edge SHALL be weakened and the buffer cleared, as in APE
- **AND** no `dec.buf` SHALL be written
