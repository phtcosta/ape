# scoring-pipeline Specification

## Purpose
TBD - created by archiving change rv-scoring-pipeline. Update Purpose after archive.
## Requirements
### Requirement: ScoringPass Plugin Interface

The scoring path SHALL be expressed as an ordered sequence of `ScoringPass` objects in the package `com.android.commands.monkey.ape.agent.scoring`. The interface SHALL be:

```java
public interface ScoringPass {
    String name();                                        // for the assembly log and tests
    boolean isEnabled();                                  // decided in the constructor from injected params
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}
```

`isEnabled()` SHALL be decided once when the pass is constructed — from the `ScoringParams` value object injected at construction (derived from the resolved `RunSpec`), never from static `Config` — and SHALL NOT be re-evaluated per `apply` call. A pass that is disabled SHALL NOT be added to the assembled pipeline; therefore a disabled pass SHALL make no priority change, set no provenance field, and emit no log line. Each pass SHALL read its weights and gates from its injected `ScoringParams` and its collaborators (MopData, coverage tracker, graph, pick counters) exclusively through the `ScoringContext` passed to `apply`; a pass SHALL NOT hold run-mutable state of its own, so that per-run counters live on the context and the pass stays unit-testable with a stub context and explicit params. `MopScorer`'s weights (`mopWeightDirect`, `mopWeightTransitive`, `mopWeightOpenMenu`) SHALL likewise be supplied as parameters by the calling pass; `MopScorer` SHALL NOT read static `Config`.

- **INV-ARCH-02**: A `ScoringPass` whose `isEnabled()` returned `false` at construction SHALL NOT appear in the assembled pipeline and SHALL therefore be a strict no-op for the run — zero priority mutations, zero provenance writes, zero log lines. A `ScoringPass` SHALL hold no run-mutable field; all cross-step mutable state (pick counters) SHALL live on the `ScoringContext`.
- **INV-ARCH-11**: No scoring pass, and no helper it calls (`MopScorer` included), SHALL read static `Config` at any time after construction; every weight and gate SHALL arrive via `ScoringParams` injection. Tests SHALL construct `ScoringParams` directly, never mutate `Config`.
- **INV-ARCH-12**: Because INV-ARCH-11 makes every pass test blind to the values a real plan resolves to, the plan → `ScoringParams` mapping SHALL itself be guarded: a `ScoringParams` derived from a default plan SHALL equal the eight jar-default weights and gates, asserted as literals in one test. Nothing else in the change set observes a scoring default — the parity goldens never reach the scoring pipeline (it runs above their entry point, `StatefulAgent.java:1537-1538`) and the `RUN_START` echo is write-only by owner decision D1.

#### Scenario: disabled pass is absent and inert
- **WHEN** a `ScoringPass` is constructed with `ScoringParams` values that make `isEnabled()` return `false`
- **THEN** it SHALL NOT be included in the pipeline built by `ScoringPipeline.fromParams`
- **AND** no action priority, provenance field, or log line SHALL be attributable to it during the run

#### Scenario: enabled pass reads collaborators from the context
- **WHEN** an enabled pass's `apply(state, actions, ctx)` needs `MopData` or the per-run MOP-target pick counter
- **THEN** it SHALL obtain them from `ctx` (the `ScoringContext`), not from a field of its own

#### Scenario: pass weights are injected, not read from Config
- **WHEN** a test constructs `CoveragePass` with `ScoringParams.coverageBoostWeight = 100`
- **THEN** the pass SHALL boost with weight 100 regardless of any `Config` state
- **AND** the same construction with weight 0 SHALL yield `isEnabled() == false`

#### Scenario: a changed scoring default fails a test
- **WHEN** a jar default among the eight `ScoringParams` fields is changed — say `mopWeightDirect` from `500` to `400` — and no other edit is made
- **THEN** the defaults-guard test SHALL fail, naming the field and both values
- **AND** the per-preset parity goldens SHALL still pass, because the scoring pipeline never executes under them — which is why the guard is a separate test and not a golden

---

### Requirement: Scoring Pipeline Assembly from Config

`ScoringPipeline.fromParams(ScoringParams, ScoringContext)` SHALL be the single point that maps the resolved plan's scoring configuration to the ordered list of enabled passes. It SHALL construct the **seven** passes in the fixed order `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `FrontierPass` → `MopFrontierPass` → `CoveragePass` → `FormCompletionPass` (the frontier family contiguous), retain only those whose `isEnabled()` is `true`, and emit exactly one `[APE-ARCH] passes=[...]` line listing the retained passes' `name()` values in pipeline order. No other code path SHALL assemble or reorder the pipeline. The former `fromConfig(Config, ScoringContext)` entry point — whose `Config` parameter was decorative (the sole caller passed `null` while the passes read `Config` statics directly) — SHALL be deleted, not shimmed (P3). The class documentation SHALL state the seven-pass roster; the stale "six passes" wording is corrected.

The fixed order SHALL equal the order of the pre-refactor inline scoring blocks in `StatefulAgent.adjustActionsByGUITree()`, which is the order the `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion` specs already require (base → MOP-widget → menu-gateway → WTG → frontier → MOP-frontier → coverage → form). The injection change SHALL NOT alter pass order, boost arithmetic, provenance writes, or the `[APE-ARCH]` line format; the sole caller (`StatefulAgent`, at construction) SHALL pass the real `ScoringParams` derived from the `RunSpec` in place of the former `null`.

**The same assembly SHALL also record the candidates it dropped.** Beside the retained list — whose content and format are unchanged — assembly SHALL record every candidate pass with whether it was constructed, carried by the stage-4 `PIPELINE` record's `candidates` member. The census SHALL be produced inside `fromParams`, where the full candidate list is still in scope: the pipeline discards the disabled passes at construction and has no handle on them afterwards, and retaining them in a field only to enumerate them would keep disabled objects alive for telemetry's sake.

This is the pass-side counterpart of the decision-pipeline's static stage list, and unlike that one it is data-bearing rather than plan-derivable: `WtgPass`, `FrontierPass` and `MopFrontierPass` each gate on `mopData.hasWtgData()`, which nothing in the plan reveals. The measurement that motivates it — across the decisive campaign's 360 runs the `[APE-ARCH] passes=` line takes exactly three values, split 45/75 in every arm, because **the whole frontier family is never constructed in 25 of the 40 applications**, and the only trace evidence of that today is the absence of three names from a list every analyst read as a configuration echo.

No `reason` field SHALL be added to the entries, and no `disabledReason()` method to the `ScoringPass` interface. Each gate is a conjunction of `mopData != null`, `hasWtgData()` and a weight, and all three conjuncts are already recorded elsewhere in the same trace (`MOP_DATA.status`, `MOP_DATA.wtgEdges`, `RUN_START.params`), so the reason is a lookup rather than a field — and adding it would touch all seven implementations plus the test double for a value that is already there. It would also be unreliable: the three passes do not evaluate their conjuncts in the same order, so a "first failing conjunct" would report source-code order rather than cause, and two passes absent for the same reason would disagree about what it was.

- **INV-ARCH-03**: The pipeline order SHALL equal the order of the pre-refactor inline scoring blocks and SHALL satisfy the pass-order contracts already asserted by `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion`. `MopWidgetPass` SHALL precede `WtgPass`, which SHALL precede `CoveragePass`, which SHALL precede `FormCompletionPass`; `MopFrontierPass` SHALL sit immediately after `FrontierPass`.
- **INV-ARCH-04**: `ScoringPipeline.fromParams` SHALL be the sole assembly point. The `[APE-ARCH] passes=[...]` startup line SHALL list exactly the enabled passes, in pipeline order, and nothing else SHALL construct a pipeline. The candidate census (`PIPELINE.candidates`) is a **sibling** record member, never a widening of this list: any consumer reading `passes` SHALL continue to see exactly the constructed passes.

#### Scenario: only enabled passes are assembled and logged
- **WHEN** `ScoringPipeline.fromParams(params, ctx)` runs with MopData absent (MOP passes off), `coverageBoostWeight=100`, `formCompletionEnabled=true`
- **THEN** the pipeline SHALL contain `CoveragePass`, `FormCompletionPass` in that order and no MOP/WTG/Frontier pass
- **AND** one `[APE-ARCH] passes=[CoveragePass, FormCompletionPass]` line SHALL be emitted

#### Scenario: full MOP arm assembles the ordered set
- **WHEN** `fromParams` runs with MopData present, `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`, `coverageBoostWeight=100`, `formCompletionEnabled=true`
- **THEN** the pipeline order SHALL be `MopWidgetPass, MenuGatewayPass, WtgPass, FrontierPass, MopFrontierPass, CoveragePass, FormCompletionPass`

#### Scenario: empty pipeline under the pure arm
- **WHEN** `fromParams` runs with a plan carrying no scoring feature (all gates off/zero)
- **THEN** the pipeline SHALL contain zero passes
- **AND** the emitted line SHALL be `[APE-ARCH] passes=[]`

#### Scenario: injection is real — no decorative parameter
- **WHEN** two `ScoringParams` differing only in `mopFrontierWeight` (0 vs 200) are used to assemble pipelines over the same context
- **THEN** the first SHALL exclude `MopFrontierPass` and the second SHALL include it
- **AND** no `Config` mutation SHALL be needed to produce the contrast

### Requirement: adjustActionsByGUITree Delegates to the Pipeline

`StatefulAgent.adjustActionsByGUITree(State, ModelAction[])` SHALL consist of the upstream APE base-priority loop followed by exactly one pipeline invocation `pipeline.apply(state, actions, ctx)`. The base-priority loop SHALL be byte-identical to upstream APE (`github.com/tianxiaogu/ape` @ `8f51b99`). No RV-specific scoring term SHALL remain inline in the method body — every RV term SHALL be a `ScoringPass` in the pipeline. The `StatefulAgent` SHALL hold one `ScoringPipeline`, built once (at construction) from `Config` and the `ScoringContext`.

This preserves `INV-EXPL-11` (adjustActionsByGUITree is called after base priority assignment and before selection) and `INV-MOP-03` (boosts are additive over the base priority): the base loop runs first, the pipeline adds on top.

- **INV-ARCH-05**: The non-pipeline body of `adjustActionsByGUITree()` SHALL be byte-identical to the upstream APE base-priority loop; the single `pipeline.apply(...)` call SHALL be the only RV addition to the method. There SHALL be no inline RV scoring `if`-block in the method after this change.

#### Scenario: method body is upstream loop plus one pipeline call
- **WHEN** `adjustActionsByGUITree(state, actions)` is invoked
- **THEN** it SHALL run the upstream base-priority loop over `actions`
- **AND** it SHALL then invoke `pipeline.apply(state, actions, ctx)` exactly once
- **AND** it SHALL contain no other RV scoring logic

#### Scenario: empty pipeline yields upstream priorities
- **WHEN** the pipeline is empty (pure arm) and `adjustActionsByGUITree` runs
- **THEN** the resulting action priorities SHALL equal the upstream base-priority loop's output for the same actions and seed

---

### Requirement: Scoring Pass Roster and Gates

The extraction SHALL yield exactly these six passes, each extracting the inline block named, each gated by the flag named. The roster is the extraction set, not a closed list: subsequent changes MAY introduce additional passes through the same `ScoringPass` interface and `ScoringPipeline.fromConfig` assembly point, subject to INV-ARCH-02/04 and to declaring any new RV key's ownership in the run-spec `Feature` model (a new pass's gate key must be owned by a feature or by `ExplorationParams`; the key-ownership totality test enforces this). (`MopFrontierPass`, strategy B, is introduced by the change `mop-reach-strategies`, not by this one.)

| Pass | Extracted inline block | `isEnabled()` gate |
|---|---|---|
| `MopWidgetPass` | MOP-widget boost | `ScoringContext.getMopData() != null` |
| `MenuGatewayPass` | OPTIONSMENU-gateway menu boost | `ScoringContext.getMopData() != null` |
| `WtgPass` | WTG-reach boost | `getMopData() != null && hasWtgData() && Config.mopWeightWtg != 0` |
| `FrontierPass` | WTG frontier (unvisited-activity) boost | `mopData != null && hasWtgData() && Config.frontierBoostWeight > 0` |
| `CoveragePass` | per-action coverage boost | `Config.coverageBoostWeight != 0` |
| `FormCompletionPass` | form-completion boost | `Config.formCompletionEnabled` |

Each pass's scoring semantics (what boost it computes and where it writes provenance) SHALL remain exactly as its originating capability specifies; this capability SHALL NOT alter those semantics. Six passes reuse a pre-existing weight/data gate; only `FormCompletionPass` introduces a new boolean gate (`formCompletionEnabled`), because the form-completion block had no off switch before this change.

#### Scenario: FormCompletionPass gated by the new flag

- **WHEN** `Config.formCompletionEnabled=false`
- **THEN** `FormCompletionPass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline
- **AND** the form-completion boost and deterministic fill SHALL NOT occur (form-completion spec's flag scenario)

#### Scenario: weight-gated pass off via its existing knob

- **WHEN** `Config.coverageBoostWeight=0`
- **THEN** `CoveragePass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline

### Requirement: Parity Configuration Flags

`Config.java` SHALL declare the following flags, loaded from `ape.properties` at class-loading time. Every default SHALL preserve current aperv behavior. Each flag is the activation key of the corresponding `Feature` in the run-spec capability's feature model; with a flag `false`, the feature is absent from the resolved plan and its mechanism is not constructed.

| Flag | Property Key | Type | Default | Gate |
|------|-------------|------|---------|------|
| `formCompletionEnabled` | `ape.formCompletionEnabled` | boolean | `true` | `FormCompletionPass` + the deterministic-fill branch in `ApeAgent.checkInput()` |
| `stepTelemetryEnabled` | `ape.stepTelemetryEnabled` | boolean | `true` | the `[APE-STEP]` per-step line + per-action timing |
| `modelMenuEnabled` | `ape.modelMenuEnabled` | boolean | `true` | inclusion of the fork `menuAction` in `State.getActions()` |
| `leastVisitedPriorityTiebreak` | `ape.leastVisitedPriorityTiebreak` | boolean | `true` | the priority tiebreak in `State.greedyPickLeastVisited()` |
| `treeEnhancementsEnabled` | `ape.treeEnhancementsEnabled` | boolean | `true` | the three `GUITreeBuilder` perception enhancements (WebView-prune actionable count, AndroidX actionability, ViewPager scrollable) |
| `activityBudgetEnabled` | `ape.activityBudgetEnabled` | boolean | `true` | `ActivityBudgetTracker` instantiation + the budget check in `SataAgent.selectNewActionNonnull()` |

The `apePureMode` row no longer exists (`ape.apePureMode` is a retired key that aborts resolution — see the REMOVED requirement above).

- **INV-ARCH-07**: With none of these keys set in `ape.properties`, `formCompletionEnabled`, `stepTelemetryEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, and `activityBudgetEnabled` SHALL be `true`, and the agent's action-selection behavior SHALL be identical to the pre-change aperv.

#### Scenario: defaults preserve current behavior

- **WHEN** `ape.properties` sets none of the parity flags
- **THEN** the six behavior gates SHALL be `true`
- **AND** the pipeline, telemetry, menu action, tiebreak, tree perception, and activity budget SHALL all be active as before

#### Scenario: a single gate overridden without the kill-switch

- **WHEN** `ape.properties` sets only `ape.stepTelemetryEnabled=false`
- **THEN** `Config.stepTelemetryEnabled` SHALL be `false` and no `[APE-STEP]` line SHALL be emitted
- **AND** all other gates SHALL retain their `true` defaults

#### Scenario: retired kill-switch key aborts

- **WHEN** `ape.properties` sets `ape.apePureMode=true`
- **THEN** resolution SHALL abort with a retired-key diagnostic before step 1

---

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) SHALL add `Config.mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by the WTG-MOP boost and the generic frontier pass), obtained via the pass's own `MopData.getWtgTransitions(activity)` lookup (it SHALL NOT ride `MopScorer.scoreWtg`'s `int` return, which hides the target activity and only fires when MOP-reachable);
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the boost recedes once the target is visited).

The boost SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + mopFrontierWeight)` — the steering mechanism, unchanged) AND recorded in a **dedicated telemetry field** `ModelAction.mopFrontierBoost` via read-modify-write accumulation (`action.setMopFrontierBoost(action.getMopFrontierBoost() + mopFrontierWeight)`). It SHALL NOT write the `wtgBoost` field. This de-aliases the previous behavior, where `MopFrontierPass` accumulated into the same `wtgBoost` that `WtgPass` and the generic `FrontierPass` write — so `decision_source=WTG` conflated the MOP-frontier mechanism with generic WTG navigation, and the corpus's stacked values (400/600 in the `wtg=` field) could not be decomposed by mechanism. The boost is attributable via a new `[APE-STEP] ... mop_frontier=` field and its own `decision_source` value `MopFrontier` in the largest-boost attribution (`action-selection` capability, "Per-action decision-source telemetry").

`MopFrontierPass.isEnabled()` SHALL be true only when `Config.mopFrontierWeight > 0` AND `MopData` is non-null with WTG data present. With `Config.mopFrontierWeight == 0` (default) the pass SHALL be byte-identical to being absent from the pipeline. The pass is independent of and additive to the generic `frontierBoostWeight` (which requires only unvisited, not MOP) — B is the strictly narrower predicate (unvisited AND MOP).

`Config.mopFrontierWeight` SHALL be declared in `Config.java`, loaded via `ape.mopFrontierWeight`, default `0`, and SHALL be declared in the run-spec `Feature` model as the activation key of the `MOP_FRONTIER` feature, which requires `MOP`: an explicit non-zero weight on a plan without MOP data aborts resolution as a missing dependency, and with the feature absent the pass is not constructed (`run-spec` INV-RUN-05 — the recorded substitute for the dissolved INV-ARCH-06 registry). `ScoringPipeline.fromConfig` SHALL assemble `MopFrontierPass` immediately after the generic `FrontierPass` and before `CoveragePass` — the frontier family stays contiguous and the relative-order contracts of INV-ARCH-03 are preserved.

- **INV-MFP-01**: `MopFrontierPass` SHALL add `mopFrontierWeight` to an action **only** when its matched WTG transition target satisfies both `activityHasMop(target) == true` AND `Graph.getActivityNode(target) == null` at scoring time. An action failing either condition SHALL receive nothing from this pass.
- **INV-MFP-02**: The boost SHALL be applied as a `setPriority` increment AND recorded into `mopFrontierBoost` by read-modify-write; because `mopFrontierBoost` is never read by `getPriority()`, the `setPriority` increment is mandatory for the boost to steer. `MopFrontierPass` SHALL NOT write `wtgBoost`; when `mopWeightWtg` and/or `frontierBoostWeight` co-apply to the same action, `wtgBoost` SHALL reflect only those WTG-family contributions and `mopFrontierBoost` only the MOP-frontier contribution.
- **INV-MFP-03**: With `Config.mopFrontierWeight == 0`, the scoring outcome SHALL be identical to the pipeline without `MopFrontierPass`.

#### Scenario: unvisited MOP target boosted into its own field
- **WHEN** widget W's WTG transition targets `com.x.CryptoActivity`, `activityHasMop("com.x.CryptoActivity")==true`, `Graph.getActivityNode("com.x.CryptoActivity")==null`, and `ape.mopFrontierWeight=200`
- **THEN** W's action priority SHALL be increased by 200
- **AND** its `mopFrontierBoost` SHALL be 200 and its `wtgBoost` SHALL be unchanged by this pass

#### Scenario: MOP but already visited — no boost
- **WHEN** the transition target is MOP-bearing but `Graph.getActivityNode(target)` is non-null (visited)
- **THEN** `MopFrontierPass` SHALL add nothing to that action

#### Scenario: unvisited but non-MOP — no boost
- **WHEN** the transition target is unvisited but `activityHasMop(target)==false`
- **THEN** `MopFrontierPass` SHALL add nothing (this is the generic frontier pass's job, not B's)

#### Scenario: co-applying boosts stay decomposable
- **WHEN** the target is MOP-bearing AND unvisited, with `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`
- **THEN** the action's priority SHALL gain +600 total
- **AND** its `wtgBoost` SHALL be 400 (WTG-MOP + generic frontier) and its `mopFrontierBoost` SHALL be 200 — the mechanisms are separable in the `[APE-STEP]` line

#### Scenario: disabled
- **WHEN** `ape.mopFrontierWeight=0`
- **THEN** the scoring pipeline SHALL behave exactly as without `MopFrontierPass`, with no boost recorded in either field

### Requirement: Per-Step Decision Outcome Attribution

When `stepTelemetryEnabled` is true, after a state transition is recorded (`Model.addTransition(source, action, target, ...)` in `StatefulAgent.updateGraph()`), `StatefulAgent` SHALL emit one `[APE-OUTCOME]` line attributing the executed action's result back to the `decision_source` that selected it. The line SHALL be correlated to the action's `[APE-STEP]` line by a shared `step` value, so an offline join on `step` pairs each decision with its outcome without any timestamp reconstruction. The `step=<N>` field contract on `[APE-STEP]` is defined by the `action-selection` capability (`Per-action decision-source telemetry`). LLM routing attempts carry the same `step` on their `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines (llm-routing capability), so for LLM-routed decisions the call, the decision, and the outcome all join on one key.

The `step` carried by `[APE-OUTCOME]` SHALL be the exploration step at which the executed action was **selected** (the `step` value emitted on that action's `[APE-STEP]` line), NOT the step at emission time. Because an action is selected at step N and its resulting transition is only observed during step N+1's processing (the agent timestamp has already advanced), `StatefulAgent` SHALL buffer the selection step and the selected action when the model-action `[APE-STEP]` line is emitted, and read the buffered values back when emitting `[APE-OUTCOME]`.

**Buffer discipline.** The non-model `[APE-STEP]` emission branch (event-level actions, e.g. the stagnation activity launcher) SHALL clear the buffer instead of writing it: non-model actions do not produce transitions under their own identity, and a stale model-action buffer could otherwise be resurrected later by state recovery (`recoverCurrentState()` re-installs the last history action as `currentAction`).

**Emission point and guards.** The `[APE-OUTCOME]` emission SHALL live in `StatefulAgent.updateGraph()`, immediately after `Model.addTransition(...)` returns — NOT inside `Model`/`Graph` — so the refinement rebuild replay (which re-records transitions via the `Graph.addTransition(GUITreeTransition)` overload, bypassing `Model.addTransition`) cannot emit spurious lines. Emission SHALL occur only when ALL of the following hold, and the buffer SHALL be consumed (cleared) upon emission:

1. the `StateTransition` returned by `Model.addTransition` is non-null (`addTransition` returns null on the run's first step, after restarts, and on the stale-ephemeral drop; an unguarded emission would fault on the very first step of every run);
2. a decision is buffered and the buffered action is reference-equal to `currentAction` (state recovery and non-model interludes install a different action object; a mismatch means the recorded transition does not belong to the buffered decision);
3. the buffer has not already been consumed for this decision — single-shot consumption guarantees that a second `addTransition` for the same decision (the `BadStateException` selection-retry path re-enters the update without advancing the timestamp; recovery can re-record the last history action) cannot emit a duplicate `[APE-OUTCOME]`.

**Refinement remap.** Model refinement (`preEvolveModel()` → `updateModel()`) replaces `currentAction` with the corresponding action object of the rebuilt model before `updateGraph()` runs. `updateModel()` SHALL remap the buffered action through the same `model.update(...)` mapping applied to `currentAction`, so refinement steps still emit their `[APE-OUTCOME]`. Without the remap, the reference guard would fail exactly on the non-deterministic steps — a systematic attribution bias against the most informative steps.

The line SHALL carry the following fields:

| Field | Source |
|-------|--------|
| `step` | buffered selection step of the executed action (join key to `[APE-STEP]`) |
| `decision_source` | `executedAction.getDecisionSource().name()` |
| `new_state` | `true` when the target state was visited for the first time (`_isNewState`), else `false` |
| `target_state` | target `State.getStateKey()` |
| `activity_changed` | `true` when the target activity differs from the source activity (negation of the recorded `StateTransition.isSameActivity()`) |
| `activity_has_mop` | `1` when `MopData` is non-null AND `MopData.activityHasMop(<target activity>)` is true, else `0` — whether the step **landed on** a MOP screen, the outcome half of the evidential link that `activity_has_mop` on `[APE-STEP]` opens (where the step started) |

`target_state` reports the state observed at the next model update. When fuzzing piggybacks events after the selected action, or a bad-state `EVENT_ACTIVATE` interlude executes, the recorded transition — and hence `target_state` / `new_state` — reflects the selected action plus those trailing events. The `step` join remains exact; offline analysis SHOULD treat the outcome as "state reached by the step", not "immediate post-action state".

Example:
```
[APE-STEP]    step=42 clock=... activity=A state=S1 action=MODEL_CLICK decision_source=LLM ... activity_has_mop=0 ...
[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=S7 activity_changed=true activity_has_mop=1
```

- **INV-ARCH-08**: The `[APE-OUTCOME]` line SHALL be gated by `stepTelemetryEnabled` exactly as the `[APE-STEP]` line is. With `ape.stepTelemetryEnabled=false` — which an arm now states directly, the `apePureMode` switch that used to force it having been retired with its registry (INV-ARCH-06 dissolved; substitute `run-spec` INV-RUN-05) — zero `[APE-OUTCOME]` lines SHALL be emitted.
- **INV-ARCH-09**: The `step` on an `[APE-OUTCOME]` line SHALL equal the `step` on the `[APE-STEP]` line of the action whose transition it reports. Every emitted `[APE-OUTCOME]` line SHALL have a matching `[APE-STEP]` line with the same `step`, and at most one `[APE-OUTCOME]` line SHALL be emitted per `step` value. An `[APE-STEP]` line MAY have no matching `[APE-OUTCOME]` line — when the selected action produced no recorded transition (restart, refinement discard, run end) — which is a legitimate, informative absence, not an error.

#### Scenario: LLM decision attributed to a new-state discovery on a MOP screen

- **WHEN** an action with `decision_source=LLM` selected at step 42 executes, the resulting transition reaches a first-visit state, and the target activity is in the MOP-activity set
- **THEN** an `[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=<key> activity_changed=<bool> activity_has_mop=1` line SHALL be emitted
- **AND** it SHALL be joinable to the `[APE-STEP] step=42` line by the shared `step=42`

#### Scenario: outcome on a non-MOP screen

- **WHEN** the recorded transition's target activity is not in the MOP-activity set (or `MopData` is null)
- **THEN** the `[APE-OUTCOME]` line SHALL carry `activity_has_mop=0`

#### Scenario: Outcome line suppressed under pure mode

- **WHEN** an arm states `ape.stepTelemetryEnabled=false` (the `ape_pure` arm does; the retired `apePureMode` key can no longer force it)
- **THEN** zero `[APE-OUTCOME]` lines SHALL be emitted for the run
- **AND** zero `[APE-STEP]` lines SHALL be emitted

#### Scenario: Selected action with no recorded transition

- **WHEN** an action is selected and emits an `[APE-STEP] step=50` line but the step ends in a restart before any transition is recorded (or it is the run's first step, where `addTransition` returns null)
- **THEN** no `[APE-OUTCOME] step=50` line SHALL be emitted
- **AND** this absence SHALL NOT be treated as an error by offline analysis

#### Scenario: BadStateException retry emits a single outcome

- **WHEN** the transition for the action selected at step 60 is recorded, and action selection then throws `BadStateException`, causing the update to re-run `Model.addTransition` within the same step
- **THEN** exactly one `[APE-OUTCOME] step=60` line SHALL be emitted (the buffer was consumed by the first emission)

#### Scenario: Refinement step still attributed

- **WHEN** the action selected at step 70 executes, and model refinement replaces `currentAction` with the rebuilt model's action object before `updateGraph()` runs
- **THEN** the buffered action SHALL be remapped alongside `currentAction`
- **AND** an `[APE-OUTCOME] step=70` line SHALL be emitted for the recorded transition

## Invariants

- **INV-ARCH-10**: `wtgBoost` and `mopFrontierBoost` SHALL be disjoint accumulators: `wtgBoost` receives only the WTG-MOP and generic-frontier contributions, `mopFrontierBoost` only the MOP-frontier contribution, and the total priority steering from the three passes SHALL equal the sum of the two fields. No pass SHALL write both fields for one contribution.
