# Delta: Decision Pipeline (isolate-guidance-groups)

## Purpose

Before this change the pipeline's order was `Budget → LlmNewState → LlmStagnation → LlmRandom → MopLauncher →
ComponentTrigger → SataChain`. The restart was applied after selection (`ApeAgent.java:363`), so a restart left
`dec.a` naming the action it replaced. The LLM coin was drawn from the agent's stream only after the shared
precondition passed, so mounting a gate shifted SATA's draws. The launcher's cadence advanced only on the steps
that reached it. And an LLM step could not take a step whose SATA buffer was non-empty, because the buffer was a
gate.

This delta makes the step a fixed sequence of **owners** (design D-B7):

    BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN, MOP_LAUNCHER, COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN

Each step records the owner that took it. The restart becomes a guard stage that runs before any selection. The
two coins, the component coin and the fuzz toss draw from streams of their own, every step, before any stage runs
(P-3, D-B9, D-B13). The component trigger becomes a STATIC member (D-B13). The
launcher's cadence is global (D-A16, D-B11). An owner that preempts a non-empty SATA buffer abandons it without
weakening the graph (P-4).

Stage `name()` values are CamelCase, as before: `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`,
`MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain`. The uppercase list above is the design's spelling of
the same order.

## Invariants

- **INV-DP-08 (amended)**: The launcher's cadence counter SHALL advance exactly once per step, in the
  `MopLauncher` stage's `onStepOpen` hook, whichever stage takes the step. A step decided by an earlier stage
  advances the counter and defers the firing to the next step that reaches `MopLauncher`. This replaces the
  former statement that an LLM-preempted step does not advance the counter (finding 3.3-1).
- **INV-DP-13**: `StepRng` SHALL draw, at step open and before any stage's `onStepOpen` or `decide()`, `u_L` when
  the `LLM` group is mounted, `u_M` when the `MOP` group is mounted, the component coin `u_C` when
  `COMPONENT_TRIGGER` is mounted, and the fuzz toss on every step. Each comes from its own `Random`, derived from
  the run seed. No LLM stage, gate or coin, no MOP coin and no component coin SHALL draw from the agent stream (`ape.getRandom()`) or the helper stream (`RandomHelper`). For one seed and one fixture set, two
  runs that differ only in mounting or unmounting an LLM gate, or in mounting the MOP coin at `p_M = 0`, SHALL
  consume the same agent- and helper-stream draws and SHALL make the same SATA choices on every step that both
  runs hand to `SataChain`.
- **INV-DP-14**: A restart SHALL be selected only by the `Restart` stage, before any LLM, MOP or SATA stage is
  evaluated. No restart SHALL replace an action after selection. On a restart step `dec.src` SHALL be `restart`,
  `dec.restart` SHALL name its kind, and `dec.a` SHALL name the start action that ran.
- **INV-DP-15**: When the `Budget` stage, an LLM stage, the `MopLauncher` stage or the `MopCoin` stage selects a
  step's action while the SATA action buffer is non-empty, the buffer SHALL be cleared without calling
  `weakenStateTransition`, and `dec.buf` SHALL name that owner; for the `Budget` stage the buffer cleared is the
  one it preempted, and the path its navigation planned stays buffered (D-B20). When the `Restart` stage takes the step, the new
  episode it starts SHALL clear the buffer, also without `weakenStateTransition` and without `dec.buf` (design
  D-B14.13). A path failure detected inside `SataChain` SHALL still weaken the planned edge, as in APE.

## ADDED Requirements

### Requirement: Restart Guard Stage

The `Restart` stage SHALL be the restart decision of the run. It is a guard of the BASE group, assembled second,
after `Budget` and before every LLM, MOP and SATA stage, and it is mounted if and only if `ape.checkRestart` is
`true` (BASE, jar default `true`; design D-B14.12). It replaces the post-selection `checkRestart` that
`ApeAgent.updateStateWrapper` applied to the selected action (`checkInput(checkFuzzing(checkRestart(...)))`).
For an agent that has a pipeline (`SataAgent`), that chain no longer contains `checkRestart` (INV-DP-14).
`RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart` wrapping unchanged;
the wrapping is skipped only for an agent that has a pipeline.

A restart is due on a step when either of the following holds:
- **Stability flag.** The flag that the stability checks of step 2 (`checkStable` → `onGraphStable`,
  `onStateStable`, `onActivityStable`) raise through `requestRestart()` is up. The stage records the kind of the
  check that raised it: `graph`, `state` or `activity`.
- **Periodic threshold.** The steps since the last restart exceed `nextRestartThreshold`, and the **previous**
  step's selection did not call `disableRestart()`. The kind is `periodic`.

The selection that can disable the restart now runs after the guard, so the guard reads the latch the previous
step left. The design declares this (§ Risks): the restart sequence differs from `eea2b1a9`, and the goldens are
recaptured. When a restart is due, the stage SHALL:
1. lower the stability flag;
2. set `lastRestartStep`;
3. redraw the periodic threshold when the kind is `periodic`;
4. start a new episode, which clears a non-empty SATA buffer without weakening and without `dec.buf` (D-B14.13);
5. return `Select` with the start action `restartAction()` yields.

The start action keeps APE's kind toss: `EVENT_CLEAN_RESTART` with probability 0.2, else `EVENT_RESTART`. The
threshold and the kind toss stay on the helper stream. The step's owner SHALL be `restart`, and `dec.restart`
SHALL be the kind. When no restart is due, the stage returns `Continue`. The stage reaches the agent only
through `StageCollaborators.restartDue()` and `restartAction()`.

No target retirement count is pending on a restart step, because no model action was selected. The
`StatefulAgent.checkRestart` override no longer applies or discards one.

#### Scenario: graph-stability restart is decided before the LLM
- **WHEN** `graphStableCounter` reaches `101` with `graphStableRestartThreshold=100`, so step 2 raises the restart flag with kind `graph`
- **AND** the current state is new and the `LlmNewState` stage is assembled
- **THEN** the `Restart` stage SHALL select the start action, and no LLM stage SHALL be evaluated on that step
- **AND** the step record SHALL carry `dec.src="restart"`, `dec.restart="graph"`, and a `dec.a` naming the start action

#### Scenario: periodic restart honours the previous step's latch
- **WHEN** 41 steps have passed since the last restart, `nextRestartThreshold` is `40`, and the previous step's SATA selection called `disableRestart()`
- **THEN** the `Restart` stage SHALL return `Continue` on this step
- **AND WHEN** the next step opens and this step's selection did not call `disableRestart()`
- **THEN** the `Restart` stage SHALL select a start action with `dec.restart="periodic"`

#### Scenario: no restart replaces a selected action
- **WHEN** any stage after `Restart` selects a step's action
- **THEN** the action that runs SHALL be that action, and `dec.a` SHALL name it

#### Scenario: the guard follows ape.checkRestart
- **WHEN** a `SataAgent` plan sets `ape.checkRestart=false`
- **THEN** no `Restart` stage SHALL be assembled, and no restart SHALL be selected on any step
- **AND WHEN** the agent is `RandomAgent` or `ReplayAgent`
- **THEN** no pipeline exists and the post-selection `checkRestart` wrapping SHALL apply unchanged

---

### Requirement: Per-Step Own Random Streams

`StepRng` SHALL derive four `java.util.Random` instances from the run seed, `seed ^ k` with a fixed odd
constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin` and `fuzz`. When the step record opens, before any stage runs,
`StepRng` SHALL draw:
- `u_L = llmCoin.nextDouble()` when `LLM` is in the plan;
- `u_M = mopCoin.nextDouble()` when `MOP` is in the plan;
- `u_C = componentCoin.nextDouble()` when `COMPONENT_TRIGGER` is in the plan; the `ComponentTrigger` stage fires
  when `u_C < componentPercentage`;
- the fuzz toss, on every step.

Each value is drawn whether or not a later stage reads it (INV-DP-13). `u_L` and `u_M` reach the stages through
the `StepContext`. When their group is mounted, they are written in `dec.own` as `uL` and `uM`. The agent stream
and the helper stream keep every other draw: the ε-greedy toss, the roulettes, `randomPick`, typed text, and the
restart threshold and kind. The component coin no longer draws from the agent stream, so mounting the trigger
never shifts SATA's draws.

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
- **AND** the fuzz toss SHALL still be drawn on every step

---

### Requirement: Buffer Abandonment by a Preempting Owner

When an LLM stage, the `MopLauncher` stage or the `MopCoin` stage returns `Select` while
`actionBufferSize() > 0`, the pipeline SHALL call `StageCollaborators.abandonBuffer(owner)` with that owner
(`llm`, `launcher` or `mop`) before the step ends (design D-B14.13); the `Budget` stage abandons the buffer it
preempts with owner `budget` through `budgetNavigation()`, described below (D-B20). `abandonBuffer` SHALL clear the buffer **without**
`weakenStateTransition`, record `dec.buf=<owner>`, and add one to `RUN_END.counters.bufAbandoned`. The edge the
buffer had planned did not fail, so the graph SHALL NOT be penalized (P-4, INV-DP-15). Inside `SataChain` a buffer
whose next action no longer matches the screen is a real path failure, and it SHALL keep weakening as in APE
(`StatefulAgent.clearBuffer`). The `Restart` stage does not call `abandonBuffer`: the new episode it starts clears
the buffer, also without weakening, and the step carries no `dec.buf` (D-B14.13).

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
`StepRng` has drawn the step's values (INV-DP-13) and before the first `decide()`. It runs whichever stage then
takes the step, including a guard. It SHALL NOT select an action, draw from any random stream, or write the step
record. It exists so that a stage's per-step bookkeeping does not depend on whether the step reaches the stage
(the launcher's cadence, INV-DP-08).

`StageResult` SHALL be a single final class with a private constructor, a `Kind` enum tag (`SELECT`, `CONTINUE`, `SIDE_EFFECT`), and static factories `select(Action, String decisionSource)`, `continueChain()` (a shared singleton), and `sideEffect(String description)`. Variant accessors (`action()`, `decisionSource()`, `description()`) SHALL throw `IllegalStateException` when read on the wrong variant. `decide()` SHALL never return null (INV-DP-04). For a `ModelAction`, the stage SHALL stamp the action's `decisionSource`/`pickChannel` provenance exactly as the pre-change pick sites did, and the `select(...)` label SHALL equal the stamped source's name.

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

---

### Requirement: Pipeline Assembly from the RunSpec

`DecisionPipeline.fromSpec(RunSpec, StageCollaborators)` SHALL be the single assembly point. The plan decides
which stages exist, and the collaborators supply the agent behaviours each stage invokes (design D14 of `rearch-03-decision-pipeline`). It SHALL
append stages in the fixed order `Budget → Restart → LlmNewState → LlmStagnation → LlmCoin → MopLauncher →
ComponentTrigger → MopCoin → SataChain`. It SHALL skip every stage whose gating feature is absent from the plan
(INV-DP-03). The stage that selects names the step's executed owner, written as `dec.src` ("Hard Preemption
Order" gives `dec.own.i`, the first claimer):

| Stage | Owner | Present when |
|---|---|---|
| `Budget` | `budget` | `ACTIVITY_BUDGET` in the plan (an ADDONS member) |
| `Restart` | `restart` | `ape.checkRestart` is `true` (BASE, jar default `true`; D-B14.12) |
| `LlmNewState` | `llm` | `LLM_NEW_STATE` (LLM ∧ `llm.onNewState`) |
| `LlmStagnation` | `llm` | `LLM_STAGNATION` (LLM ∧ `llm.onStagnation`) |
| `LlmCoin` | `llm` | `LLM_COIN` (LLM ∧ `p_L = ape.llmPercentage > 0`; renamed from `LLM_RANDOM`) |
| `MopLauncher` | `launcher` | `MOP_LAUNCHER` (a MOP member, key `ape.activityTriggerEnabled`; renamed from `ACTIVITY_TRIGGER`) |
| `ComponentTrigger` | none (side effect) | `COMPONENT_TRIGGER` (a STATIC member, `componentPercentage > 0`; D-B13) |
| `MopCoin` | `mop` | `MOP_COIN` (a MOP member, `p_M = ape.mopCoinPercentage > 0`) |
| `SataChain` | `sata` | always |

The order puts the guards first (P-2), then the LLM (D-A4), then MOP (D-A10). The launcher is never before the
LLM (D-A8), and SATA comes last.

`Feature.ACTIVITY_TRIGGER` is renamed `MOP_LAUNCHER`, because the feature mounts the `MopLauncher` stage and nothing
else; its key `ape.activityTriggerEnabled` is unchanged. The two stages that read static data read each provider
for what it owns:
- `MopLauncher` reads the manifest activities, the main activity and the package from `StaticGuiModel`, and the
  MOP activities and the `activityDist` distances from `MopData`.
- `ComponentTrigger` reads its component pool (receivers, services and providers) from `StaticGuiModel`, through
  the `StepContext` accessor `staticModel()`, and SHALL NOT read `ctx.mopData()`. Its rate is injected at
  assembly from `spec.staticData().componentPercentage()`, never from `spec.mop()`. The stage is therefore
  assembled, and fires, in the `as` and `as_llm` cells, where no `MopData` exists.

Assembly SHALL happen exactly once per run and SHALL emit one `[APE-ARCH] stages=[...]` line listing the assembled stages' `name()` values in order (INV-DP-01). Every parameter a stage reads SHALL be injected at assembly from the `RunSpec` (INV-DP-12); a stage SHALL NOT read static `Config` in `decide()`.

**The echo SHALL also name the candidate stages, as a static list.** Beside the assembled stages, assembly SHALL emit the full fixed candidate order with, for each, whether it was assembled — carried by the stage-4 `PIPELINE` record as `stages` and `candidates`. On the stage side this costs nothing beyond the list itself and needs no reason field: a stage is absent exactly when its gating feature is absent from the plan (INV-DP-03), which `RUN_START.features` already states. The list SHALL be a static declaration of names — never a constructed no-op stage object to enumerate, which INV-DP-03 forbids and which this requirement must not be read as reintroducing.

It matters on the *pass* side, and that is where the evidence is. The scoring passes become absent for a reason nothing in the plan reveals — `WtgPass`, `NavFrontierPass` and `MopFrontierPass` each gate on run-time data (`StaticGuiModel.hasWtgData()`) — and over the decisive campaign the whole family is never constructed in 25 of the 40 applications. Making the stage census static and the pass census data-bearing is the distinction; the pass half is specified in the scoring-pipeline capability.

**On the scenario headers below.** `openspec archive` matches scenarios by name, so the header "the four shipped
presets" is kept. Its body now covers the six cell presets that replaced those four presets.

#### Scenario: a plan carrying every feature assembles every stage
- **WHEN** the plan carries the activity-budget, LLM (all three modes, `p_L > 0`), MOP, MOP-launcher, component-trigger and MOP-coin features
- **THEN** the pipeline SHALL be `[Budget, Restart, LlmNewState, LlmStagnation, LlmCoin, MopLauncher, ComponentTrigger, MopCoin, SataChain]`
- **AND** one `[APE-ARCH] stages=[Budget, Restart, LlmNewState, LlmStagnation, LlmCoin, MopLauncher, ComponentTrigger, MopCoin, SataChain]` line SHALL be emitted
- **AND** the `as_mop_llm` cell SHALL state such a plan: its STATIC block sets `ape.componentPercentage=0.03` and its MOP block sets `ape.activityTriggerEnabled=true`

#### Scenario: the four shipped presets assemble the rosters their keys state
- **WHEN** a plan is resolved from each cell preset plus the deployment keys the tool passes (`ape.staticDataPath`, `ape.llmUrl`, `ape.variant`)
- **THEN** `base` SHALL assemble `[Restart, SataChain]`
- **AND** `mop` SHALL assemble `[Restart, MopLauncher, MopCoin, SataChain]`, with no `Budget` because `ACTIVITY_BUDGET` is an ADDONS member
- **AND** `as` SHALL assemble `[Budget, Restart, ComponentTrigger, SataChain]`, and `as_mop` SHALL assemble `[Budget, Restart, MopLauncher, ComponentTrigger, MopCoin, SataChain]`
- **AND** `as_llm` SHALL assemble `[Budget, Restart, LlmNewState, LlmStagnation, LlmCoin, ComponentTrigger, SataChain]`, and `as_mop_llm` SHALL assemble all nine stages
- **AND** the `[APE-ARCH]` echo SHALL name exactly that roster

#### Scenario: aperv plan assembles the minimal pipeline
- **WHEN** the plan carries no LLM, no MOP and no component-trigger feature, with the activity budget on (the `as` cell with `ape.componentPercentage=0`)
- **THEN** the pipeline SHALL be `[Budget, Restart, SataChain]`
- **AND** no LLM unit, launcher counter, MOP coin, coin stream or trigger machinery SHALL exist in the run (feature absent = stage absent, structurally)

#### Scenario: stage order is not configurable
- **WHEN** any plan is resolved, with any overrides
- **THEN** the relative order of the assembled stages SHALL be the fixed order above
- **AND** no configuration key SHALL exist that reorders stages

---

### Requirement: Hard Preemption Order

The pipeline loop SHALL evaluate stages strictly in assembled order and SHALL end the step at the first `Select`
(INV-DP-02). A `SideEffect` SHALL be recorded and evaluation SHALL continue (INV-DP-05). The order is the owner
sequence of design D-B7:
- the guards (`Budget`, `Restart`) preempt everything;
- the LLM stages preempt the launcher, the MOP coin and SATA;
- the launcher preempts the MOP coin;
- the MOP coin preempts SATA;
- the component trigger never ends a step;
- the SATA chain is the fallback of every assembled pipeline.

The SATA buffer is not a precondition of any stage. An owner that preempts a non-empty buffer abandons it
without weakening (INV-DP-15).

The selecting stage SHALL write its owner as `dec.src` (its meaning is now the executed owner, no longer the
largest boost). `dec.own.i` SHALL be the intended owner, the **first claimer** of the step in pipeline order
(design D-B14.6, `event-sink`): a due guard (`budget` or `restart`), a fired LLM trigger or a won LLM coin
(`llm`), a due launcher (`launcher`), a won and eligible MOP coin (`mop`), and otherwise `sata`. A lost coin, an
ineligible MOP coin and a launcher below its cadence claim nothing. `dec.own.i` equals `dec.src` exactly when the
first claimer also took the step; when an LLM stage claimed the step and then did not take it, `dec.own.i` is
`llm`, `dec.src` names the later owner, and `dec.own` SHALL also carry the gate that barred the call (`g`) and the
fallback reason (`fb`) (`llm-routing`).

The guard steps carry their own pick channel: a step the `Budget` stage takes carries `dec.ch:"budget"`, and a step
the `Restart` stage takes carries `dec.ch:"restart"` (`action-selection`, `event-sink`).

**At most one LLM call per step** (design D-B8, D-B14.14). The three LLM stages share one gate chain and one call
budget: once any LLM stage of the step has called the engine — whatever the call returned, including `null`, a
decline or an error — every later LLM stage of the same step SHALL return `Continue` without evaluating its trigger
or its gates and without calling, so the stagnation flag is not burned by a step that already called. `u_L` was
drawn at step open regardless (INV-DP-13), and `dec.own.i` stays `llm`, the first claimer. A step therefore spends at most one LLM round trip, and a failed call falls through to the non-LLM remainder.

#### Scenario: preemption golden — all contenders qualify at once (report Sec. 9.4)
- **WHEN** a synthetic step at once satisfies the LLM new-state trigger, the launcher's firing condition, the component-trigger coin and the MOP coin's eligibility with `u_M < p_M`, has SATA candidates and a non-empty buffer, and the stubbed LLM returns an accepted action
- **THEN** the step's action SHALL come from `LlmNewState` with `dec.src="llm"`
- **AND** the `MopLauncher` cadence counter SHALL have advanced at step open, and the launcher SHALL NOT fire on that step (INV-DP-08)
- **AND** no component trigger SHALL fire, the MOP coin SHALL NOT be evaluated, and no SATA rung SHALL be evaluated
- **AND** the buffer SHALL be abandoned without weakening, with `dec.buf="llm"`

#### Scenario: LLM preemption does not advance the launcher cadence (finding 3.3-1, pinned)
- **WHEN** the launcher counter is at N at the end of step K, and an LLM stage selects step K+1's action
- **THEN** the counter SHALL be N+1 after step K+1, because `onStepOpen` advanced it before any stage ran (global cadence, D-A16)
- **AND WHEN** the counter reaches the cadence on a step that an LLM stage takes
- **THEN** the launcher SHALL fire on the next step that falls through the LLM stages to `MopLauncher`, with the counter still at or past the cadence
- **AND** this body replaces the pinned 3.3-1 behaviour: the header is kept because `openspec archive` matches scenarios by name

#### Scenario: at most one LLM call per step
- **WHEN** in the `as_mop_llm` cell step N reaches a new state with 5 actions, the stagnation trigger also holds (counter `50` with `graphStableRestartThreshold=100`, flag armed), `u_L=0.01 < p_L=0.02`, the gates admit, and the stubbed LLM returns `null` to the `LlmNewState` call
- **THEN** exactly one LLM call SHALL be made on step N, and the record's `llm[]` SHALL hold exactly one entry
- **AND** `LlmStagnation` and `LlmCoin` SHALL return `Continue` without calling, and the stagnation flag SHALL stay armed
- **AND** the step SHALL be taken by a later non-LLM stage, with `dec.own.i="llm"`, `dec.own.trg="new_state"` and `dec.own.fb` naming the null result

#### Scenario: the first claimer is recorded when a later owner takes the step
- **WHEN** the launcher is due on step N (counter at its cadence, an eligible candidate exists), `u_L=0.01 < p_L=0.02`, and the LLM call times out
- **THEN** the step SHALL be taken by `MopLauncher`, with `dec.src="launcher"` and `dec.ch="launcher"`
- **AND** `dec.own.i` SHALL be `llm` and `dec.own.fb` SHALL be `error`, because the LLM coin claimed the step first

#### Scenario: guard steps carry their own channel
- **WHEN** the `Budget` stage takes step N and the `Restart` stage takes step N+5
- **THEN** step N's record SHALL carry `dec.src="budget"`, `dec.ch="budget"` and `dec.own.i="budget"`
- **AND** step N+5's record SHALL carry `dec.src="restart"`, `dec.ch="restart"` and `dec.own.i="restart"`

#### Scenario: side effect never decides the step
- **WHEN** the `ComponentTrigger` stage fires its coin and dispatches a broadcast
- **THEN** it SHALL return `SideEffect`, the dispatch SHALL be recorded on the step's record as `dec.trig:{"k":"broadcast","c":"<receiver class>"}` (`k` one of `broadcast`, `service`, `provider`; `dec.comp` stays the launcher's launch outcome), and the `MopCoin` stage (when it is assembled and takes the step) or else the `SataChain` stage SHALL still select the step's action

#### Scenario: launcher preempts the SATA chain when it fires
- **WHEN** no guard and no LLM stage selects, the launcher's counter is at or past its cadence, and an eligible census candidate exists
- **THEN** the step's action SHALL be the `EVENT_TRIGGER_ACTIVITY` action with `dec.src="launcher"`
- **AND** neither the `MopCoin` stage nor the SATA chain SHALL be evaluated that step

---

### Requirement: Episode-State Ownership and Reset

Episode-scoped decision state SHALL live inside its owning stage (INV-DP-07):
- the stagnation single-shot flag in `LlmStagnation`;
- the launcher cadence counter, the per-run launch budget counter, the round-robin cursor and the set of activities launched in this run (design D-B15) in `MopLauncher`;
- the component round-robin cursor in `ComponentTrigger`.

The `MopLauncher` cadence counter is advanced only by the stage's own `onStepOpen` hook, once per step
(INV-DP-08). The agent SHALL forward each visited `StateTransition` to every assembled stage's
`onStateTransition(edge)` hook exactly once, after its own stability-counter bookkeeping. `LlmStagnation` SHALL
re-arm its flag on `NEW_ACTION`/`NEW_ACTION_TARGET` edges, the same event that resets `graphStableCounter`.

`graphStableCounter` itself SHALL remain shared exploration state owned by the agent and `RunContext`, because the
stability restart consumes it through step 2's `checkStable`. `LlmStagnation` MAY reset it to 0 on an accepted
escape, through the `StepContext`'s single declared write method; no other stage SHALL write it. The restart
flag, its kind, `lastRestartStep`, `nextRestartThreshold` and the `disableRestart` latch remain agent state. The
`Restart` stage reads them only through `StageCollaborators.restartDue()` and `restartAction()`. The
`disableRestart` latch SHALL no longer be cleared at step open. The `Restart` stage's evaluation SHALL read it and
then clear it, so the guard reads the value that the previous step's selection left (INV-DP-14). A step that the
`Budget` stage takes never reaches `Restart`, so the latch is left for the next step (design D-B14.16).

#### Scenario: stagnation flag burns on firing and re-arms on a new edge
- **WHEN** the stagnation trigger fires (counter ≥ threshold/2, flag armed), the gate chain admits, and the LLM returns null
- **THEN** the flag SHALL be burned (no second attempt this episode) while the counter keeps incrementing toward the restart threshold
- **AND WHEN** a later step records a `NEW_ACTION` edge
- **THEN** the hook's `onStateTransition` SHALL re-arm the flag (new episode)

#### Scenario: accepted escape resets the shared counter
- **WHEN** the stagnation trigger fires and the LLM returns an accepted action
- **THEN** the stage SHALL reset `graphStableCounter` to 0 through the `StepContext` write method
- **AND** no other counter or stage state SHALL be touched

#### Scenario: launcher counters are invisible outside the stage
- **WHEN** any code outside `MopLauncher` executes
- **THEN** it SHALL have no read or write access to the cadence counter, launch budget counter, round-robin cursor, or launched set
- **AND** the counter SHALL change only in the stage's `onStepOpen` (advance) and `decide()` (reset at firing)

---

### Requirement: LLM Fallback Realized Structurally

The LLM stages SHALL implement fallback exclusively as `Continue` (INV-DP-11). The rest of the assembled pipeline
is the fallback, and the plan declares it through the mounted groups:
- with MOP: `MopLauncher`, `ComponentTrigger` (when STATIC mounts it), `MopCoin`, then `SataChain` with the MOP
  scorers;
- without MOP: `ComponentTrigger` (when mounted), then `SataChain`.

All of the following SHALL take this one path:
- a lost coin and a gate that bars the step;
- a decline, a no-match and a dead-pair ban;
- a boundary or degenerate reject;
- a screenshot, image, HTTP or parse failure.

No LLM stage SHALL retry within a step, throw, or select a substitute action itself. The fallback reason SHALL be
recorded in `dec.own.fb` (`llm-routing` "Declared LLM Fallback").

#### Scenario: LLM failure falls through to the configured remainder (report Sec. 9.5)
- **WHEN** the plan is the `as_mop_llm` cell, the stubbed LLM times out on a coin step, the launcher's counter is below its cadence, and no on-screen action is eligible for the MOP coin
- **THEN** the step's action SHALL come from the `SataChain` stage, with `dec.src="sata"`, never `llm`
- **AND** `dec.own` SHALL carry `i="llm"`, `trg="coin"`, `fb="error"`

#### Scenario: breaker-open steps decide without the LLM
- **WHEN** the circuit breaker is OPEN for a window spanning several steps
- **THEN** every step in the window SHALL be decided by the non-LLM remainder of the pipeline, and each step whose trigger or coin held SHALL carry `dec.own.g="breaker"`
- **AND** the breaker-open trace line SHALL be emitted once per open episode (unchanged latch semantics)

---

### Requirement: Migration Parity Gate

The capability SHALL be accepted only when the `parity-oracle` goldens pass against the pipeline (INV-DP-09):
identical seeds, fixtures and stubbed LLM scripts produce byte-identical decision sequences. Before this change
there was one golden per preset. This change alters the random sequence on purpose: own streams (INV-DP-13), the
restart guard (INV-DP-14), the global cadence (INV-DP-08) and the X3/X4 defaults. So the goldens of the former
presets `aperv`, `mop`, `llm` and `llm_mop` SHALL be deleted with those presets. One golden per cell preset
(`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`) SHALL be captured on purpose, from the build that
implements this change. The preemption golden and the structural-fallback test SHALL stay in the permanent test
suite of this capability, restated for the order above.

#### Scenario: per-preset golden equality
- **WHEN** the golden harness replays each cell preset's fixture set against the pipeline build
- **THEN** the recorded decision sequence SHALL be identical to that cell's golden, captured on purpose for this change
- **AND** no golden of the four former presets SHALL remain in the suite

#### Scenario: goldens gate every extraction step
- **WHEN** any later change to a stage lands
- **THEN** the full per-cell golden suite SHALL pass before the next change begins, unless that change declares a behaviour change and recaptures the goldens on purpose
