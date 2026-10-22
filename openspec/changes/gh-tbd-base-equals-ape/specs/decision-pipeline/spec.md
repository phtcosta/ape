# Delta: Decision Pipeline (gh-tbd-base-equals-ape)

## Purpose

The restart has two paths, and the plan chooses one for the run. With `APE_FIXES` in the plan (an ADDONS member,
`ape.apeFixes`, default `true` with ADDONS; the `as`, `as_mop`, `as_llm` and `as_mop_llm` cells), the `Restart`
stage is a guard that runs before any selection, so no restart replaces an action after selection. Without
`APE_FIXES` (the `base` and `mop` cells), the pipeline assembles no `Restart` stage and the agent applies
`checkRestart` after selection, as APE does: the selected action is marked visited, the restart may replace it by a
start action, and the step is recorded as a restart decision.

The fuzz toss follows the same member. With `APE_FIXES` it draws from a fourth `StepRng` stream, once per step, in
`MonkeySourceApe.generateEvents` after the step's action events are generated. Without it the toss is
`RandomHelper.toss(fuzzingRate)`, drawn only when fuzzing is on, as in APE.

This replaces, in the main spec's Purpose, the sentences "The restart is a guard stage that runs before any
selection, so no restart replaces an action after selection." and "The fuzz toss draws from a fourth `StepRng`
stream, once per step, in `MonkeySourceApe.generateEvents` after the step's action events are generated." The
statement that mounting a gate, a coin or fuzzing never shifts SATA's draws holds for the plans that carry
`APE_FIXES`; without it, turning fuzzing on adds one helper-stream draw per step, as in APE.

The main spec's invariants INV-DP-13, INV-DP-14 and INV-DP-15 take the amended text stated in "Per-Step Own
Random Streams", "Restart Guard Stage" and "Post-Selection Restart Model Effect" below.

## ADDED Requirements

### Requirement: Post-Selection Restart

When `ape.checkRestart` is `true` and `ApeAgent.restartStageMounted()` is `false` (a `SataAgent` without
`APE_FIXES`, as in `base` and `mop`, and every `RandomAgent` and `ReplayAgent`), `StatefulAgent.updateStateInternal`
SHALL resolve the step's action, mark it visited when it is a model action, call `ApeAgent.checkRestart(action)`,
and then run `recordActionHistory` and `moveForward` for a kept action or `resetTrace` for a replaced one. The
replaced action MAY come from any owner.

#### Scenario: base cell has no Restart stage
- **WHEN** a `SataAgent` run is resolved from the `base` cell with `ape.checkRestart=true`
- **THEN** its pipeline SHALL be `[SataChain]`, `PIPELINE.stageCandidates` SHALL carry `Restart` with `enabled:false`, and `restartStageMounted()` SHALL return `false`
- **AND** every restart of the run SHALL be applied by `ApeAgent.checkRestart` after the step's selection

#### Scenario: a disabled restart keeps the selected action
- **WHEN** the stage is not mounted, no stability flag is up, the periodic threshold has passed, and the step's selection called `disableRestart()`
- **THEN** `checkRestart` SHALL return the selected action, which SHALL be recorded and advance the trace through `moveForward`

#### Scenario: the replaced action of the MOP coin is not executed
- **WHEN** in the `mop` cell the `MopCoin` stage selects an action with `mopWeight=500` on a step on which a stability restart is due
- **THEN** `checkRestart` SHALL replace it by the start action, and the step record SHALL carry `dec.src:"restart"`
- **AND** the `MopInteractionLedger` SHALL NOT record the replaced action's key, and no target SHALL be counted for it

### Requirement: Post-Selection Restart Decision

`ApeAgent.checkRestart(origin)` SHALL return `origin` when the `Restart` stage is mounted, when `ape.checkRestart`
is `false`, on the step's `BadStateException` retry, or when `restartDue()` is false. Otherwise it SHALL return the start action of
`ApeAgent.startRestart()`, which lowers the stability flag, sets `lastRestartStep`, redraws a `periodic`
threshold, starts a new episode, writes `dec.restart`, counts one `restarts`, and tosses the start action's kind on
the helper stream.

#### Scenario: a stability restart replaces the pick whatever the latch
- **WHEN** the stage is not mounted, the graph-stability flag is up, and the step's SATA selection called `disableRestart()`
- **THEN** `checkRestart` SHALL return a start action and the step SHALL carry `dec.restart="graph"`
- **AND** the stability flag SHALL be lowered and `lastRestartStep` set to the step

#### Scenario: no restart is due
- **WHEN** the stage is not mounted, no stability flag is up, and the steps since the last restart do not exceed `nextRestartThreshold`
- **THEN** `checkRestart` SHALL return the selected action and SHALL NOT call `startRestart()`

#### Scenario: startRestart without a due restart fails
- **WHEN** `ApeAgent.startRestart()` is called on a step on which `restartDue()` is false
- **THEN** it SHALL throw `IllegalStateException`

### Requirement: Post-Selection Restart Model Effect

A replaced action SHALL stay marked visited, and the restart's new episode SHALL clear the SATA buffer through
`clearBuffer`, weakening the planned edge, with no `dec.buf` (INV-DP-15, as amended: when a post-selection restart
replaces the selected action, the buffer is cleared as APE's is). The ledger, retirement, UI coverage, budget
iteration and LLM action history SHALL NOT record the replaced action.

#### Scenario: the replaced action is not recorded by the fork's bookkeeping
- **WHEN** in the `base` cell a stability restart replaces a SATA pick of a widget never touched in its activity
- **THEN** `UICoverageTracker` SHALL NOT count that widget's interaction, and the LLM action history SHALL NOT hold the replaced action

#### Scenario: a SATA pick replaced by a periodic restart is marked visited and the planned edge weakened
- **WHEN** in the `base` cell 41 steps have passed since the last restart, `nextRestartThreshold` is `40`, the SATA buffer holds a planned transition, and the step's SATA selection does not call `disableRestart()`
- **THEN** the selected model action SHALL be marked visited before `checkRestart` runs
- **AND** `checkRestart` SHALL return a start action of kind `periodic`, `moveForward` and `recordActionHistory` SHALL NOT run for the selected action, and the trace SHALL be reset
- **AND** the new episode SHALL clear the buffer through `clearBuffer`, weakening the planned edge, with no `dec.buf`

### Requirement: Post-Selection Restart on the BadStateException Retry

The post-selection restart SHALL run only on the step's first `updateStateInternal`. On the
`BadStateException` retry of `ApeAgent.updateStateWrapper`, `checkRestart` SHALL return the retry's selected
action unchanged, as APE's retry, which has no `checkRestart`, does. The `badStateRetry` flag marks the retry: set
before it, cleared at step start, read only by `checkRestart`. On the stage path the retry runs `Restart` again.

#### Scenario: the BadStateException retry does not restart
- **WHEN** in the `mop` cell step 41 opens with `nextRestartThreshold` `40` and no restart since step 0, the step's first state build throws `BadStateException`, and the retry's SATA selection does not call `disableRestart()`
- **THEN** `checkRestart` SHALL return the retry's selected action, no `restarts` SHALL be counted and `lastRestartStep` SHALL stay `0`
- **AND** the kept action SHALL be recorded and advance the trace through `moveForward`
- **AND WHEN** step 42 opens and its first `updateStateInternal` selects an action
- **THEN** `checkRestart` SHALL replace it by a start action of kind `periodic`

### Requirement: Post-Selection Restart Launch Commit

A replaced launch of the `MopLauncher` stage SHALL commit nothing: its activity SHALL NOT enter the stage's
launched set, the launch budget SHALL NOT be consumed, and `RUN_END.counters.launches` SHALL NOT count it. The
stage commits a launch in `onSelectionKept`, which runs only for a kept action (`component-triggering`
"Cadence-Based MOP Activity Launch"). The cadence reset and the round-robin cursor move at the firing, as for a
kept launch.

#### Scenario: a replaced launch frees its activity
- **WHEN** in the `mop` cell the `MopLauncher` stage selects `com.x.Target` on a step on which a periodic restart is due
- **THEN** `checkRestart` SHALL replace the launch by a start action, `com.x.Target` SHALL stay eligible, the launch budget SHALL be unchanged and `launches` SHALL NOT be counted
- **AND WHEN** on a later step on which no restart is due the stage selects `com.x.Target` and the action is kept
- **THEN** `com.x.Target` SHALL enter the launched set and `launches` SHALL count one

### Requirement: Post-Selection Restart Record

After a replacement, `StatefulAgent` SHALL write
`sink().decisionReplaced(start.toString(), DecisionSource.RESTART.owner(), PickChannel.RESTART.getLabel())` and
clear `lastDecisionAction`, so the record is the one a step the `Restart` stage takes writes (`event-sink`
"StepRecord Schema"). LLM attempts made on the step before the replacement SHALL stay in `llm[]`.

#### Scenario: the step record carries src and ch restart
- **WHEN** in the `base` cell a graph-stability restart is due after SATA selected step 300's action through the ε roulette
- **THEN** the `s:300` record's `dec` SHALL carry `src:"restart"`, `ch:"restart"`, `restart:"graph"` and an `a` naming the start action
- **AND** it SHALL carry no `strat`, `draw`, `cfc`, `pri` or scorer field of the replaced decision
- **AND** `RUN_END.counters.restarts` SHALL count the step once

## MODIFIED Requirements

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
| `Restart` | `restart` | `ape.checkRestart` is `true` (jar default `true`; D-B14.12) and `APE_FIXES` is in the plan (an ADDONS member, key `ape.apeFixes`) |
| `LlmNewState` | `llm` | `LLM_NEW_STATE` (LLM ∧ `llm.onNewState`) |
| `LlmStagnation` | `llm` | `LLM_STAGNATION` (LLM ∧ `llm.onStagnation`) |
| `LlmCoin` | `llm` | `LLM_COIN` (LLM ∧ `p_L = ape.llmPercentage > 0`; renamed from `LLM_RANDOM`) |
| `MopLauncher` | `launcher` | `MOP_LAUNCHER` (a MOP member, key `ape.activityTriggerEnabled`; renamed from `ACTIVITY_TRIGGER`) |
| `ComponentTrigger` | none (side effect) | `COMPONENT_TRIGGER` (a STATIC member, `componentPercentage > 0`; D-B13) |
| `MopCoin` | `mop` | `MOP_COIN` (a MOP member, `p_M = ape.mopCoinPercentage > 0`) |
| `SataChain` | `sata` | always |

The `Restart` candidate's gate is `Feature.APE_FIXES` and its condition is
`spec.exploration().checkRestart() && spec.has(Feature.APE_FIXES)`. A plan without ADDONS (the `base` and `mop`
cells) and a plan that states `ape.apeFixes=false` therefore assemble no `Restart` stage, and their restart is
applied after selection ("Post-Selection Restart").

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

**The echo SHALL also name the candidate stages, as a static list.** Beside the assembled stages, assembly SHALL emit the full fixed candidate order with, for each, whether it was assembled — carried by the stage-4 `PIPELINE` record as `stages` (the assembled names, in order) and `stageCandidates` (every candidate stage of the fixed order, each as `{name, enabled}`; design D-B23). The record's `candidates` member stays the scoring-pass census of `scoring-pipeline`; the stage census does not reuse it. On the stage side this costs nothing beyond the list itself and needs no reason field: a stage is absent exactly when its gating feature is absent from the plan (INV-DP-03), which `RUN_START.features` already states. The list SHALL be a static declaration of names — never a constructed no-op stage object to enumerate, which INV-DP-03 forbids and which this requirement must not be read as reintroducing.

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
- **THEN** `base` SHALL assemble `[SataChain]`
- **AND** `mop` SHALL assemble `[MopLauncher, MopCoin, SataChain]`, with no `Budget` because `ACTIVITY_BUDGET` is an ADDONS member and no `Restart` because `APE_FIXES` is an ADDONS member
- **AND** `as` SHALL assemble `[Budget, Restart, ComponentTrigger, SataChain]`, and `as_mop` SHALL assemble `[Budget, Restart, MopLauncher, ComponentTrigger, MopCoin, SataChain]`
- **AND** `as_llm` SHALL assemble `[Budget, Restart, LlmNewState, LlmStagnation, LlmCoin, ComponentTrigger, SataChain]`, and `as_mop_llm` SHALL assemble all nine stages
- **AND** the `[APE-ARCH]` echo SHALL name exactly that roster

#### Scenario: aperv plan assembles the minimal pipeline
- **WHEN** the plan carries no LLM, no MOP and no component-trigger feature, with the activity budget on (the `as` cell with `ape.componentPercentage=0`)
- **THEN** the pipeline SHALL be `[Budget, Restart, SataChain]`
- **AND** no LLM unit, launcher counter, MOP coin, coin stream or trigger machinery SHALL exist in the run (feature absent = stage absent, structurally)

#### Scenario: the stage census names the stages that were not assembled
- **WHEN** the `mop` cell assembles `[MopLauncher, MopCoin, SataChain]`
- **THEN** the `PIPELINE` record's `stageCandidates` SHALL carry all nine candidates in the fixed order, with `enabled:true` on exactly `MopLauncher`, `MopCoin` and `SataChain` and `enabled:false` on `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin` and `ComponentTrigger`
- **AND** its `stages` SHALL be `[MopLauncher, MopCoin, SataChain]`
- **AND** no stage object SHALL have been constructed for a candidate with `enabled:false`

#### Scenario: stage order is not configurable
- **WHEN** any plan is resolved, with any overrides
- **THEN** the relative order of the assembled stages SHALL be the fixed order above
- **AND** no configuration key SHALL exist that reorders stages

#### Scenario: the Restart candidate follows APE_FIXES
- **WHEN** a plan resolved from the `as` cell plus `ape.apeFixes=false` is assembled, with `ape.checkRestart=true`
- **THEN** the pipeline SHALL be `[Budget, ComponentTrigger, SataChain]`, and the `PIPELINE` record's `stageCandidates` SHALL carry `Restart` with `enabled:false`
- **AND** the restart SHALL be applied after selection ("Post-Selection Restart")

### Requirement: Episode-State Ownership and Reset

Episode-scoped decision state SHALL live inside its owning stage (INV-DP-07):
- the stagnation single-shot flag in `LlmStagnation`;
- the launcher cadence counter, the per-run launch budget counter, the round-robin cursor and the set of activities launched in this run (design D-B15) in `MopLauncher`;
- the component round-robin cursor in `ComponentTrigger`.

The `MopLauncher` cadence counter is advanced only by the stage's own `onStepOpen` hook, once per step
(INV-DP-08). The agent SHALL forward each visited `StateTransition` to every assembled stage's
`onStateTransition(edge)` hook exactly once, after its own stability-counter bookkeeping. The agent SHALL forward
the step's selected action to every assembled stage's `onSelectionKept(ctx, action)` hook once, after
`checkRestart`, when no post-selection restart replaced it; `MopLauncher` commits its launch there (the launched
set, the launch budget and `launches`), so a replaced launch commits nothing ("Post-Selection Restart Model
Effect"). `LlmStagnation` SHALL
re-arm its flag on `NEW_ACTION`/`NEW_ACTION_TARGET` edges, the same event that resets `graphStableCounter`.

`graphStableCounter` itself SHALL remain shared exploration state owned by the agent and `RunContext`, because the
stability restart consumes it through step 2's `checkStable`. `LlmStagnation` MAY reset it to 0 on an accepted
escape, through the `StepContext`'s single declared write method; no other stage SHALL write it. The restart
flag, its kind, `lastRestartStep`, `nextRestartThreshold` and the `disableRestart` latch remain agent state. The
`Restart` stage reads them only through `StageCollaborators.restartDue()` and `restartAction()`. The latch has
two semantics, one per restart path, and the path is fixed for the run by whether the pipeline assembled
`Restart` (`ApeAgent.restartStageMounted()`):
- **Stage path** (`Restart` assembled). The `disableRestart` latch SHALL NOT be cleared at step open. The
  `Restart` stage's evaluation SHALL read it and then clear it, so the guard reads the value that the previous
  step's selection left (INV-DP-14). A step that the `Budget` stage takes never reaches `Restart`, so the latch is
  left for the next step (design D-B14.16).
- **Post-selection path** (`Restart` not assembled). The latch SHALL be cleared at step start, before selection,
  and read by `ApeAgent.checkRestart` after this step's selection, as APE does (`disableRestart = false` before
  selection). A selection that calls `disableRestart()` therefore prevents the periodic restart on its own step
  ("Post-Selection Restart").

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

#### Scenario: the post-selection path reads the latch its own step set
- **WHEN** in the `base` cell 41 steps have passed since the last restart, `nextRestartThreshold` is `40`, no stability flag is up, and this step's SATA selection calls `disableRestart()`
- **THEN** `checkRestart` SHALL return the selected action and no restart SHALL run on this step
- **AND WHEN** the next step opens, the latch is cleared, and that step's selection does not call `disableRestart()`
- **THEN** `checkRestart` SHALL replace that step's selected action by a start action, with `dec.restart="periodic"`

### Requirement: Restart Guard Stage

The `Restart` stage SHALL be the restart decision of a run whose pipeline assembled it. It is a guard, assembled
second, after `Budget` and before every LLM, MOP and SATA stage, and it is mounted if and only if
`ape.checkRestart` is `true` (jar default `true`; design D-B14.12) and `APE_FIXES` is in the plan (an ADDONS
member, `ape.apeFixes`, default `true` with ADDONS; the candidate's gate is `Feature.APE_FIXES`). The cells that
mount ADDONS (`as`, `as_mop`, `as_llm`, `as_mop_llm`) assemble it; `base` and `mop` do not.

`ApeAgent.restartStageMounted()` is the agent's single question about the path: it returns `false` in `ApeAgent`,
and `SataAgent` overrides it with whether its assembled pipeline contains `RESTART`. When it returns `true`,
`ApeAgent.checkRestart(origin)` returns `origin` unchanged and no restart replaces a selected action
(INV-DP-14). When it returns `false` — `SataAgent` without the stage, and `RandomAgent` and `ReplayAgent`, which
have no pipeline — the restart is applied after selection ("Post-Selection Restart"). `ApeAgent.updateStateWrapper`
is one path for every agent, `checkInput(checkFuzzing(updateStateInternal(...)))`.

A restart is due on a step when either of the following holds:
- **Stability flag.** The flag that the stability checks of step 2 (`checkStable` → `onGraphStable`,
  `onStateStable`, `onActivityStable`) raise through `requestRestart()` is up. The stage records the kind of the
  check that raised it: `graph`, `state` or `activity`.
- **Periodic threshold.** The steps since the last restart exceed `nextRestartThreshold`, and the **previous**
  step's selection did not call `disableRestart()`. The kind is `periodic`.

The selection that can disable the restart runs after the guard, so the guard reads the latch the previous
step left. When a restart is due, the stage SHALL:
1. lower the stability flag;
2. set `lastRestartStep`;
3. redraw the periodic threshold when the kind is `periodic`;
4. start a new episode, which clears a non-empty SATA buffer without weakening and without `dec.buf` (D-B14.13);
5. return `Select` with the start action `restartAction()` yields. `StatefulAgent.restartAction()` is
   `actionBuffer.clear(); return startRestart();`: the buffer drop without weakening belongs to the stage path
   only, and `ApeAgent.startRestart()` performs steps 1–4 and yields the start action.

The start action keeps APE's kind toss: `EVENT_CLEAN_RESTART` with probability 0.2, else `EVENT_RESTART`. The
threshold and the kind toss stay on the helper stream. The step's owner SHALL be `restart`, and `dec.restart`
SHALL be the kind. When no restart is due, the stage returns `Continue`. The stage reaches the agent only
through `StageCollaborators.restartDue()` and `restartAction()`.

No target retirement count is pending on a restart step the stage takes, because no model action was selected.
No `StatefulAgent.checkRestart` override applies or discards one.

INV-DP-14, as amended: in a run whose pipeline assembled the `Restart` stage, a restart SHALL be selected only by
that stage, before any LLM, MOP or SATA stage is evaluated, and no restart SHALL replace an action after
selection. In a run whose pipeline did not assemble it and with `ape.checkRestart=true`, a due restart SHALL be
applied after selection and SHALL replace the selected action of whichever owner selected it ("Post-Selection
Restart"). On every restart step, of either path, `dec.src` SHALL be `restart`, `dec.restart` SHALL name its kind,
and `dec.a` SHALL name the start action that ran.

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
- **WHEN** the pipeline assembled `Restart` and any stage after `Restart` selects a step's action
- **THEN** the action that runs SHALL be that action, and `dec.a` SHALL name it
- **AND** `ApeAgent.checkRestart` SHALL return that action unchanged

#### Scenario: the guard follows ape.checkRestart
- **WHEN** a `SataAgent` plan sets `ape.checkRestart=false`, with or without `APE_FIXES`
- **THEN** no `Restart` stage SHALL be assembled, `ApeAgent.checkRestart` SHALL return every selected action unchanged, and no restart SHALL run on any step
- **AND WHEN** the plan sets `ape.checkRestart=true` and the agent is `RandomAgent` or `ReplayAgent`
- **THEN** no pipeline exists, `restartStageMounted()` SHALL return `false`, and the restart SHALL be applied after selection

### Requirement: Per-Step Own Random Streams

`StepRng` SHALL derive four `java.util.Random` instances from the run seed, `seed ^ k` with a fixed odd
constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin` and `fuzz`. When the step opens
(`StepRouting.open`, before any stage's `onStepOpen` or `decide()`), the pipeline SHALL draw:
- `u_L = llmCoin.nextDouble()` when `LLM` is in the plan;
- `u_M = mopCoin.nextDouble()` when `MOP` is in the plan;
- `u_C = componentCoin.nextDouble()` when `COMPONENT_TRIGGER` is in the plan; the `ComponentTrigger` stage fires
  when `u_C < componentPercentage`.

The fuzz toss source depends on `APE_FIXES` (`exploration` "Fuzzing Integration"):
- with `APE_FIXES` in the plan, the toss (`StepRng.fuzzToss(fuzzingRate)`) SHALL be drawn once per step by
  `MonkeySourceApe.generateEvents`, after the step's action events are generated, whether or not `FUZZING` is in
  the plan and whatever the step's owner;
- without `APE_FIXES` (the `base` and `mop` cells), the toss SHALL be `RandomHelper.toss(fuzzingRate)`, drawn from
  the helper stream only when `FUZZING` is in the plan, as in APE; the `fuzz` stream is not read.

Each value is drawn whether or not a later stage reads it (INV-DP-13). `u_L` and `u_M` reach the stages through
the `StepContext`. When their group is mounted, they are written in `dec.own` as `uL` and `uM`. The agent stream
and the helper stream keep every other draw: the ε-greedy toss, the roulettes, `randomPick`, typed text, and the
restart threshold and kind. No coin draws from the agent stream, so mounting the trigger never shifts SATA's draws.

INV-DP-13, as amended: `StepRouting.open` SHALL draw `u_L`, `u_M` and `u_C` as listed above. With `APE_FIXES` in
the plan, the fuzz toss SHALL be drawn once per step from the `fuzz` stream, whether or not `FUZZING` is in the
plan, and SHALL NOT draw from the agent or helper stream; without `APE_FIXES`, it SHALL be
`RandomHelper.toss(fuzzingRate)`, drawn only when `FUZZING` is in the plan, and the `fuzz` stream SHALL NOT be
read. No LLM stage, gate or coin, no MOP coin and no component coin SHALL draw from the agent stream or the helper
stream. For one seed and one fixture set, two runs that differ only in mounting or unmounting an LLM gate, or in
mounting the MOP coin at `p_M = 0`, SHALL consume the same agent- and helper-stream draws and SHALL make the same
SATA choices on every step that both runs hand to `SataChain`.

#### Scenario: an LLM gate does not shift SATA's draws
- **WHEN** two runs of the `as_llm` cell share seed `42` and fixtures, and differ only in `ape.llmGateMinActions` (`3` against `0`, the latter leaving the gate unmounted)
- **THEN** every step that both runs hand to `SataChain` SHALL select the same action in both
- **AND** the helper-stream draw count before each such step SHALL be equal in both

#### Scenario: the coin is drawn on steps a guard takes
- **WHEN** the `LLM` group is mounted and the `Budget` stage selects step N
- **THEN** `u_L` SHALL still have been drawn once at step N's open
- **AND** step N+1's `u_L` SHALL be the stream's next value

#### Scenario: no coin stream is read without its group
- **WHEN** the plan is the `base` cell, with `FUZZING` in the plan
- **THEN** no `u_L`, `u_M` or `u_C` SHALL be drawn, and `dec.own` SHALL carry neither `uL` nor `uM`
- **AND** the fuzz toss SHALL be drawn from `RandomHelper` once per step, after the step's action events are generated, and the `fuzz` stream SHALL NOT be read
- **AND WHEN** the plan is the `as` cell
- **THEN** the fuzz toss SHALL be drawn from the `fuzz` stream once on every step, whether or not `FUZZING` is in the plan
