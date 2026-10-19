# parity-oracle — delta: isolate-guidance-groups

## Purpose

The parity oracle was built to prove that a restructuring changed no decision. `isolate-guidance-groups` changes
decisions on purpose, and the oracle changes with it in four ways.

1. **One golden set per cell.** The presets `aperv`, `mop`, `llm` and `llm_mop` are deleted, and their goldens go
   with them. The six cell presets (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`, `guidance-groups`)
   each get a golden set, captured on purpose once the change is integrated (wave 2). The recapture is the
   decided behaviour change the oracle's regeneration procedure exists for. Three causes shift the random sequence
   relative to `eea2b1a9`: the LLM coin, the MOP coin, the component coin and the fuzz toss draw from the four
   seeded `StepRng` streams (design D-B9); the restart is a guard ahead of selection (D-B7); and the X3/X4 defaults draw as upstream APE
   draws (D-B12). The old goldens cannot be kept green, and a red golden would not be a regression.
2. **Base-cell parity.** The `base` cell is the oracle's reference for upstream APE. The extension points X1, X3
   and X4 SHALL take the APE default there, including RNG consumption, and the oracle pins that equality on
   fixtures against the upstream behaviour of `ape.jar` `8f51b99c`.
3. **The preemption order changes.** The pipeline is BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN,
   MOP_LAUNCHER, COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN (`decision-pipeline`). The preemption golden pins that
   order.
4. **The launcher cadence is global.** Finding 3.3-1 recorded that a step preempted by the LLM did not advance
   the launcher's cadence counter. Stage 3 was to replace that captured behaviour with a decided one, and this
   change makes the decision (D-A16, D-B11): the counter advances on every step, LLM steps included, and the
   launcher fires on the first step at which the cadence is due and the pipeline reaches `MOP_LAUNCHER`.

## Data Contracts

### Input

- `preset injection profile` — one profile per cell preset; the cell's resolved plan decides which mechanisms
  the scaffold mounts (`StaticGuiModel`, `MopData`, the scripted LLM router, the gates, the MOP coin, the X1/X3/X4
  variants) (source: `OracleScaffold`)
- `MopData fixture` — `src/test/resources/cryptoapp.apk.mop.json` (wire format 2), loaded through the production
  `StaticArtifactLoader` for the cells that contain STATIC or MOP
- `seed: long` — seeds every RNG stream of the run: the agent stream, the helper stream, and the four
  `StepRng` streams derived from it (`llmCoin`, `mopCoin`, `componentCoin`, the fuzz toss)

### Output

- `src/test/resources/goldens/<cell>/<scenario>.ndjson` — one directory per cell preset; the directories
  `aperv/`, `llm/` and `llm_mop/`, and the former content of `mop/`, are removed

## Invariants

- **INV-ORA-05** *(replaces the stage-1 text)*: The preemption golden SHALL assert that the launcher's cadence
  counter advances on every step, including a step taken by an LLM stage, and that the launcher fires on the first
  step at which the counter has reached the cadence and the pipeline reaches `MOP_LAUNCHER`.
- **INV-ORA-08**: There SHALL be one committed golden set per cell preset, and no golden SHALL name a preset
  outside `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`. The sets SHALL be captured once, in wave 2 of
  `isolate-guidance-groups`, through the deliberate regeneration procedure, with a commit message that cites this
  change. Coverage: `ParityOracle*Test`, the goldens README.
- **INV-ORA-09**: In the `base` cell, X1, X3 and X4 SHALL decide as upstream APE (`8f51b99c`) decides on the same
  candidates and the same seed. X1 SHALL return the first least-visited action of the array; X3 SHALL never
  replace the incumbent on a tie and SHALL draw nothing; X4 SHALL always take `RandomHelper.randomPick`. Coverage:
  `ParityOracleBaseTest`, `SataExtensionPointTest`.

## MODIFIED Requirements

### Requirement: Per-Preset Golden Decision-Sequence Capture

The oracle SHALL capture and commit, per target preset, the ordered action-selection sequence produced by the agent's selection entry point (`SataAgent.selectNewActionNonnull()`, which runs the `DecisionPipeline`) over that preset's scenario scripts under a fixed declared seed. The target presets SHALL be the six cell presets: `base` (no group), `mop` (MOP over BASE, data loaded from the committed format-2 fixture), `as` (ADDONS + STATIC), `as_mop` (ADDONS + STATIC + MOP), `as_llm` (ADDONS + STATIC + scripted LLM router), and `as_mop_llm` (all four groups). A preset SHALL be realized by the harness's injection profile mirroring the composition root's wiring for the cell's resolved plan: which providers, router, gates, coins and extension-point variants exist is decided by the plan's features, exactly as in production.

Each preset's capture SHALL include at least one multi-step baseline scenario exercising: the SATA chain fall-through (buffer, early-stage, epsilon-greedy rungs), and — where the preset enables them — MOP-coin picks, the cadence launcher firing, and the LLM triggers and coin with accept, decline, and timeout verdicts.

Three of those inputs are **declared by the scenario**, for the same reason action validity and priority already are: the ladder reads them but nothing below the oracle's entry point assigns them. A scenario that wants a MOP-coin step SHALL declare the widget's `mopWeight` (no code below the entry point writes one, so an undeclared weight is zero and the MOP coin is ineligible); a scenario that wants the launcher to fire within a readable number of steps SHALL declare the seeded value of the cadence counter (the launcher fires on the first step at which the counter is at least `ape.activityTriggerStagnationStep` and the pipeline reaches `MOP_LAUNCHER`); and a scenario that wants the budget block entered SHALL declare which activity is exhausted (an unregistered activity reports no exhaustion, and registration happens above the entry point). Each declared value SHALL be visible in the scenario script, so a golden's reader can tell an input from a decision.

Each preset capture test SHALL guard-assert the values the ladder reads for that preset — the cell plan's values (e.g. `ape.activityBudgetEnabled`, `ape.activityTriggerEnabled`, `ape.activityTriggerStagnationStep`, `ape.componentPercentage`, `ape.llmPercentage`, `ape.mopCoinPercentage`) and the jar-default `Config` values that remain outside the plan — so a future default change fails the guard explicitly instead of surfacing as an unexplained golden divergence.

The scope of that guard is exactly "the values the ladder reads", and the boundary matters because it is easy to over-read. The scoring weights (`mopWeightDirect`, `mopWeightTransitive`, `mopWeightOpenMenu`, `mopWeightWtg`, `frontierBoostWeight`, `mopFrontierWeight`, `coverageBoostWeight`, `formCompletionEnabled`) are **not** among them: scoring happens above this oracle's entry point, so no golden record depends on a scoring weight and no capture test can guard one. Their guard belongs where the injection is built — `rearch-03-decision-pipeline`, INV-ARCH-12 — and this requirement SHALL NOT be cited as covering them.

#### Scenario: aperv preset golden compares green at HEAD

- **WHEN** `mvn test` runs the `as` cell capture test (the cell whose ADDONS block is the former `aperv` preset) in compare mode against the committed golden at the commit that captured it
- **THEN** the replayed decision sequence SHALL equal the golden record-by-record, including record count
- **AND** every record SHALL carry `actionType` and, for targeted actions, the `Name.toXPath()` target
- **AND** no `src/test/resources/goldens/aperv/` directory SHALL exist

#### Scenario: mop preset exercises MOP mechanisms against the committed fixture

- **WHEN** the `mop` cell baseline scenario runs with the providers loaded from `cryptoapp.apk.mop.json` and no LLM router
- **THEN** the golden SHALL contain at least one step owned by a MOP mechanism (a MOP-coin pick or a launcher `EVENT_TRIGGER_ACTIVITY` step)
- **AND** the LLM field SHALL be absent from every record

#### Scenario: Config default drift is caught by the guard, not the golden

- **WHEN** a future change alters a value that the selection ladder reads in a cell (e.g. the `ape.activityTriggerStagnationStep` default, or the MOP block's `ape.mopCoinPercentage`)
- **THEN** the cell's guard assertion SHALL fail with the changed key named
- **AND** the failure SHALL NOT be reported as a golden divergence

#### Scenario: The epsilon-greedy RNG seam stays overridable

- **WHEN** a test subclass of `SataAgent` overrides `getRandom()` and the ladder descends into `selectNewActionEpsilonGreedyRandomly()` on a state whose actions are all saturated
- **THEN** the epsilon-greedy coin flip in `egreedy()` SHALL draw from the overridden stream, not from `ape.getRandom()`
- **AND** the run SHALL complete with a null `ape` field, so both legs of the rung — the least-visited pick and the priority roulette — are capturable

#### Scenario: Determinism — double capture is identical

- **WHEN** the same cell scenario is captured twice in one JVM with the same seed (every RNG stream, the four `StepRng` streams included, re-seeded before each run)
- **THEN** the two decision-record sequences SHALL be identical

#### Scenario: the six cells each have a golden set

- **WHEN** `mvn test` runs the oracle in compare mode after wave 2
- **THEN** a golden directory SHALL exist for each of `base`, `mop`, `as`, `as_mop`, `as_llm` and `as_mop_llm`, and each SHALL compare green

### Requirement: Preemption-Order Golden

The oracle SHALL include a preemption golden, captured in the `as_mop_llm` cell, built on synthetic states that simultaneously qualify the LLM stages, the MOP launcher (cadence reached, census candidate available), the MOP coin (an eligible weighted action on screen), and the SATA chain. It SHALL assert the precedence order of the `DecisionPipeline`: BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN, MOP_LAUNCHER, COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN. An accepted LLM result preempts the launcher; the launcher, when it fires, preempts the MOP coin; a won MOP coin preempts the SATA chain; the SATA chain is the fallback. The budget block precedes them all and SHALL be pinned **at its gate**: a scenario declaring the current activity's budget exhausted SHALL show the block entered and falling through, and a scenario declaring no exhaustion SHALL show it not entered at all. Its trivial-action return is outside the capture boundary — see Purpose of the main spec for why reaching it would widen the boundary rather than close a gap in it — and SHALL NOT be claimed by any golden. A due restart is selected by the RESTART guard ahead of every LLM stage.
The component-trigger stage sits between the launcher and the MOP coin and is a side effect that never returns an action (design D-B14.5). Its dispatch is device-bound (`android.content.Intent`), so `OracleSataAgent` SHALL override `triggerComponent()` as a recorded no-op: it dispatches nothing and records the tuple the round-robin cursor selected. The coin SHALL still draw from `StepRng.componentCoin` on every step, exactly as in production, so the oracle shifts no agent, helper or other coin draw. The step on which the trigger fires SHALL carry `dec.trig:{"k":"broadcast"|"service"|"provider","c":"<class>"}` (design decision A7), and the golden sets of the four `as*` cells, where the STATIC block mounts the trigger at `ape.componentPercentage=0.03`, SHALL contain those `dec.trig` members.

The preemption golden SHALL additionally pin:

1. **Global cadence** (INV-ORA-05, replacing finding 3.3-1): on a step taken by an LLM stage, the launcher's cadence counter SHALL advance; on the first later step that reaches `MOP_LAUNCHER` with the counter at or above the cadence, the launcher SHALL fire.
2. **Stagnation single-shot burn**: when the stagnation stage is consulted, `stagnationHookFired` SHALL become `true` regardless of the verdict — an LLM decline still burns the episode's shot.
3. **Counter reset on accept only**: `graphStableCounter` SHALL be reset to 0 by a stagnation accept and SHALL be left unchanged by a stagnation decline.
4. **Stage order**: on a step where several LLM stages would route, the new-state stage SHALL be consulted before stagnation, and stagnation before the coin; at most one of them calls the model on a step (design decision A3).

#### Scenario: LLM accept preempts a launcher that was due to fire

- **WHEN** a step's state qualifies the new-state LLM stage (scripted accept) AND the cadence counter is one below `ape.activityTriggerStagnationStep` with an unvisited census candidate available
- **THEN** the selected action SHALL be the LLM-accepted `ModelAction`
- **AND** the cadence counter SHALL have advanced by one during that step, reaching the cadence
- **AND** the next step that reaches `MOP_LAUNCHER` SHALL fire the launcher

#### Scenario: LLM decline falls through to the launcher

- **WHEN** the same simultaneous-qualification state runs with every scripted LLM verdict set to decline and the cadence reached
- **THEN** the selected action SHALL be the launcher's `EVENT_TRIGGER_ACTIVITY`
- **AND** the step's record SHALL carry `llm:"declined"`

#### Scenario: Stagnation decline burns the single shot

- **WHEN** the stagnation stage routes and the scripted verdict is decline, and a later step in the same episode would qualify the stagnation stage again
- **THEN** the first step SHALL set `stagnationHookFired` to `true` with `graphStableCounter` unchanged
- **AND** the later step SHALL NOT consult the stagnation stage (the stub honors the `firedThisEpisode` argument)

#### Scenario: Budget exhaustion is pinned at the gate, not at the trivial return

- **WHEN** a scenario declares the current activity's budget exhausted and the same step qualifies the LLM stages, with no trivial navigation action available — and none can be, since the trivial-activity path search walks graph edges the harness never records
- **THEN** the step SHALL fall through the budget block to the stages below it, and the golden SHALL record the LLM stage's outcome for that step
- **AND** the trivial-action return SHALL be documented as outside the capture boundary, so its absence from every golden reads as a stated exclusion rather than as untested behavior

#### Scenario: the MOP coin follows the launcher and precedes SATA

- **WHEN** a step with every LLM verdict declined, the cadence not reached, and an eligible weighted action on screen runs with `ape.mopCoinPercentage=1.0`
- **THEN** the selected action SHALL be the MOP coin's pick, the action with the largest `mopWeight`
- **AND WHEN** the same step runs with the cadence reached
- **THEN** the selected action SHALL be the launcher's `EVENT_TRIGGER_ACTIVITY`

#### Scenario: the component trigger is a recorded no-op that decides nothing

- **WHEN** an `as` cell step reaches `COMPONENT_TRIGGER` with `u_C = 0.01 < 0.03` and the cursor selects the provider tuple of `androidx.startup.InitializationProvider`, the fixture's one provider
- **THEN** `OracleSataAgent.triggerComponent()` SHALL dispatch no intent and the step's record SHALL carry `"trig":{"k":"provider","c":"androidx.startup.InitializationProvider"}`
- **AND** the step's action SHALL be selected by the `MOP_COIN` or `SATA_CHAIN` stage, and the next step's `u_C` SHALL be the next value of `StepRng.componentCoin`

### Requirement: Deterministic LLM Stubbing

LLM-cell goldens SHALL be captured with a scripted router (`ScriptedLlmRouter extends LlmRouter`) and never a live SGLang server. The stub SHALL override the routing predicates with per-step scripted verdicts that honor the agent-side arguments (`shouldRouteNewState` honors `isNewState`; `shouldRouteStagnation` honors `firedThisEpisode`), and SHALL override `selectAction` with scripted results: **accept** (a `ModelAction` chosen from the passed action list by a deterministic selector named in the script), **decline** (null), or **timeout** (null, recorded distinctly as provenance). The LLM coin is not scripted: it is decided by the seeded `u_L` draw and the plan's `p_L`, and the declared gates are mounted from the plan as in production. The stub SHALL NOT perform HTTP, SHALL NOT capture screenshots, and SHALL NOT record breaker transitions; scripts SHALL NOT model breaker-open episodes (out of oracle scope — wall-clock dependent). Script exhaustion, or a scripted consultation the agent never performed, SHALL fail the test.

#### Scenario: Accept returns a deterministic member of the offered actions

- **WHEN** a scripted accept names selector "first-unvisited-targeted" and the step offers a stable action list
- **THEN** `selectAction` SHALL return that exact `ModelAction` on every capture and replay
- **AND** no network connection SHALL be attempted at any point

#### Scenario: Decline and timeout share the fall-through, differ in provenance

- **WHEN** one step is scripted decline and another timeout
- **THEN** both steps SHALL fall through to the stages below the LLM stages
- **AND** their golden records SHALL carry `llm:"declined"` and `llm:"timeout"` respectively

#### Scenario: Unconsumed script entry fails loudly

- **WHEN** the script declares a routed consultation at step 5 but a declared gate bars it (for example the minimum-actions gate, on a screen with two actions under `ape.llmGateMinActions=3`)
- **THEN** the harness SHALL fail the test naming the unconsumed entry
- **AND** SHALL NOT silently continue to the next entry

### Requirement: Golden Comparison as the Migration Gate

The compare mode SHALL be the acceptance gate for every change that restructures action selection without deciding a behaviour change: such a change lands only with every cell golden and the preemption golden green under `mvn test`. On divergence the comparator SHALL report the first divergent field with preset, scenario, step, golden value, and actual value (INV-ORA-06). A change that does decide a behaviour change — as `isolate-guidance-groups` does — SHALL recapture the affected goldens through the deliberate regeneration procedure, once, and SHALL say in the commit which decision changed them (INV-ORA-08); the goldens of the deleted presets are deleted, not regenerated.

#### Scenario: Stage-3 regression is caught at the first divergent step

- **WHEN** a pipeline restructuring accidentally evaluates `MOP_COIN` before `LLM_COIN` and the `as_mop_llm` preemption scenario is replayed
- **THEN** the comparison SHALL fail at the first step whose selected action differs
- **AND** the report SHALL name the preset, scenario, step index, and the divergent field with both values

#### Scenario: Missing golden never auto-captures

- **WHEN** compare mode runs and the golden file for a scenario does not exist
- **THEN** the test SHALL fail with a message containing the deliberate-regeneration instructions
- **AND** no golden file SHALL be written

## ADDED Requirements

### Requirement: Base-Cell Parity of the SATA Extension Points

The oracle SHALL pin, in the `base` cell, that the SATA extension points decide as upstream APE (`ape.jar`, `8f51b99c`) on the same candidates and the same seed (INV-ORA-09):

- **X1** `LeastVisitedTie`: with zero mounted scorers, the pick SHALL be the first least-visited action of the array, as upstream `model/State.java:185-190`;
- **X3** `AbaTargetTie`: on a tie between two ABA targets the incumbent SHALL be kept, and no RNG draw SHALL occur;
- **X4** `TrivialPathPick`: the path to a trivial activity SHALL always be taken by `RandomHelper.randomPick`, consuming exactly one helper-stream draw.

The fixtures SHALL be synthetic states that make each tie occur. The expected picks SHALL be derived from the upstream rule as stated above and committed with the test. The `base` cell's golden SHALL then be captured over the same oracle scaffold as the other cells.

#### Scenario: X1 with zero scorers takes the first of the array

- **WHEN** the `base` cell reaches the least-visited pick on a state whose actions `b0`, `b1`, `b2` all have visit count 0
- **THEN** the selected action SHALL be `b0`
- **AND** the record SHALL carry no scorer-sum tie-break

#### Scenario: X3 keeps the incumbent and draws nothing

- **WHEN** the `base` cell compares two ABA targets whose states tie, one of them in a MOP activity of the fixture
- **THEN** the incumbent SHALL be kept
- **AND** the helper and agent streams SHALL be at the same position after the comparison as before it

#### Scenario: X4 draws once with randomPick

- **WHEN** the `base` cell chooses among three paths to a trivial activity whose end states have different MOP densities
- **THEN** the chosen path SHALL be the one `RandomHelper.randomPick` returns under the scenario's seed
- **AND** exactly one helper-stream draw SHALL have been consumed
