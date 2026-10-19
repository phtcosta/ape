# scoring-pipeline Specification

## Purpose
The scoring pipeline turns the mounted behaviour groups' knowledge about the current screen into additive terms on each candidate action. Each `ScoringPass` is a scorer owned by one group (ADDONS, STATIC or MOP); it computes its term from the screen, the model and its own data, writes it to its own field, and adds it to the action's priority. SATA reads the terms only where upstream APE reads weights — the roulettes and the least-visited tie. The pipeline is assembled once from the resolved plan, and the per-step decision outcome is attributed back to the record that chose the action.

A scorer never reads the current priority or another scorer's field (rule R1), so the chain is commutative and mounting one scorer never changes what another computes. ADDONS owns the coverage and form scorers; STATIC owns the navigation frontier (`NavFrontierPass`, reading `StaticGuiModel`, field `navFrontierBoost`, `dec.navf`); MOP owns the MOP widget, MOP frontier, WTG-MOP and menu-gateway scorers. Each pass is mounted by its member feature, except `MopWidgetPass`, which the group root `MOP` mounts (design D-B1, D-B4, D-B12). `wtgBoost` has one producer, `WtgPass`.

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

`isEnabled()` SHALL be decided once when the pass is constructed — from the presence of its mounting feature (its member feature, or the root `MOP` for `MopWidgetPass`) in the resolved plan, carried by the `ScoringParams` value object injected at construction, and from its data gate where it has one — never from static `Config`, and SHALL NOT be re-evaluated per `apply` call. A pass that is disabled SHALL NOT be added to the assembled pipeline; therefore a disabled pass SHALL make no priority change, set no scorer field, and emit no log line. Each pass SHALL read its weights from its injected `ScoringParams` and its collaborators (its group's provider — `StaticGuiModel` or `MopData` — the coverage tracker, the form context, the graph) exclusively through the `ScoringContext` passed to `apply`, and only those its group owns ("Scorer Ownership by Group"); a pass SHALL NOT hold run-mutable state of its own, so that the pass stays unit-testable with a stub context and explicit params. `MopScorer`'s weights (`mopWeightDirect`, `mopWeightTransitive`, `mopWeightOpenMenu`) SHALL likewise be supplied as parameters by the calling pass; `MopScorer` SHALL NOT read static `Config`.

- **INV-ARCH-02**: A `ScoringPass` whose `isEnabled()` returned `false` at construction SHALL NOT appear in the assembled pipeline and SHALL therefore be a strict no-op for the run — zero priority mutations, zero scorer-field writes, zero log lines. A `ScoringPass` SHALL hold no run-mutable field.
- **INV-ARCH-11**: No scoring pass, and no helper it calls (`MopScorer` included), SHALL read static `Config` at any time after construction; every weight and gate SHALL arrive via `ScoringParams` injection. Tests SHALL construct `ScoringParams` directly, never mutate `Config`.
- **INV-ARCH-12**: Because INV-ARCH-11 makes every pass test blind to the values a real plan resolves to, the plan → `ScoringParams` mapping SHALL itself be guarded: a `ScoringParams` derived from each cell preset SHALL equal that cell's weights and feature gates, asserted as literals in one test per cell. Nothing else in the change set observes a scoring default — the parity goldens never reach the scoring pipeline (it runs above their entry point) and the `RUN_START` echo is write-only by owner decision D1 of `rearch-02-runspec`.

#### Scenario: disabled pass is absent and inert
- **WHEN** a `ScoringPass` is constructed with `ScoringParams` whose member feature is absent (for example `NavFrontierPass` in the `base` cell)
- **THEN** it SHALL NOT be included in the pipeline built by `ScoringPipeline.fromParams`
- **AND** no action priority, scorer field, or log line SHALL be attributable to it during the run

#### Scenario: enabled pass reads collaborators from the context
- **WHEN** an enabled pass's `apply(state, actions, ctx)` needs its provider — `NavFrontierPass` the `StaticGuiModel`, `MopWidgetPass` the `MopData`
- **THEN** it SHALL obtain it from `ctx` (the `ScoringContext`), not from a field of its own
- **AND** a STATIC pass SHALL find no `MopData` it is allowed to read

#### Scenario: pass weights are injected, not read from Config
- **WHEN** a test constructs `CoveragePass` with `ScoringParams` carrying `COVERAGE_BOOST` and `coverageBoostWeight = 100`
- **THEN** the pass SHALL boost with weight 100 regardless of any `Config` state
- **AND** the same construction without `COVERAGE_BOOST` SHALL yield `isEnabled() == false`

#### Scenario: a changed scoring default fails a test
- **WHEN** a value of a cell block — say `mopWeightWtg` in the MOP block from `200` to `150` — is changed and no other edit is made
- **THEN** the per-cell defaults-guard test SHALL fail, naming the field and both values
- **AND** the per-cell parity goldens SHALL still pass, because the scoring pipeline never executes under them — which is why the guard is a separate test and not a golden

### Requirement: Scoring Pipeline Assembly from Config

`ScoringPipeline.fromParams(ScoringParams, ScoringContext)` SHALL be the single point that maps the resolved plan's scoring configuration to the ordered list of enabled passes. It SHALL construct the **seven** candidate passes in the fixed order `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `NavFrontierPass` → `MopFrontierPass` → `CoveragePass` → `FormCompletionPass` (the frontier family contiguous), retain only those whose `isEnabled()` is `true` — that is, whose member feature is in the plan and whose data gate holds ("Scorer Ownership by Group") — and emit exactly one `PIPELINE` sink record (event-sink capability) whose `passes` member lists the retained passes' `name()` values in pipeline order. No other code path SHALL assemble or reorder the pipeline. The class documentation SHALL state the seven-pass roster.

Under rule R1 the order has no effect on any term or on the final priorities ("Scorer Independence (Rule R1)"); it is kept fixed so the `PIPELINE` record and the assembly tests stay deterministic. The sole caller (`StatefulAgent`, at construction) SHALL pass the real `ScoringParams` derived from the `RunSpec`.

**The same assembly SHALL also record the candidates it dropped.** Beside the retained list — whose content and format are unchanged — assembly SHALL record every candidate pass with whether it was constructed, carried by the `PIPELINE` record's `candidates` member. The census SHALL be produced inside `fromParams`, where the full candidate list is still in scope: the pipeline discards the disabled passes at construction and has no handle on them afterwards.

The record is data-bearing rather than plan-derivable: `WtgPass` and `MopFrontierPass` gate on `StaticGuiModel.hasWtgData()` as well as on their feature, and `NavFrontierPass` on the same data, which nothing in the plan reveals.

No `reason` field SHALL be added to the entries, and no `disabledReason()` method to the `ScoringPass` interface. Each gate is a conjunction of a feature in the plan and, for the WTG family, `hasWtgData()`, and both conjuncts are already recorded elsewhere in the same trace (`RUN_START.features`, `MOP_DATA.wtgEdges`), so the reason is a lookup rather than a field.

- **INV-ARCH-03**: The pipeline order SHALL be `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `NavFrontierPass`, `MopFrontierPass`, `CoveragePass`, `FormCompletionPass` among the retained passes. `MopFrontierPass` SHALL sit immediately after `NavFrontierPass` when both are retained.
- **INV-ARCH-04**: `ScoringPipeline.fromParams` SHALL be the sole assembly point. The `PIPELINE` record's `passes` member SHALL list exactly the enabled passes, in pipeline order, and nothing else SHALL construct a pipeline. The candidate census (`PIPELINE.candidates`) is a **sibling** record member, never a widening of this list.

#### Scenario: only enabled passes are assembled and logged
- **WHEN** `ScoringPipeline.fromParams(params, ctx)` runs for a plan with `ADDONS` (`COVERAGE_BOOST` at `coverageBoostWeight=100`, `FORM_COMPLETION`) and without `STATIC` or `MOP`
- **THEN** the pipeline SHALL contain `CoveragePass`, `FormCompletionPass` in that order and no MOP, WTG or frontier pass
- **AND** one `PIPELINE` record SHALL be emitted with `passes:["CoveragePass","FormCompletionPass"]`

#### Scenario: full MOP arm assembles the ordered set
- **WHEN** `fromParams` runs for the `as_mop` cell with WTG data present (`mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`, `mopWeightOpenMenu=250`, `coverageBoostWeight=100`, `formCompletionEnabled=true`)
- **THEN** the pipeline order SHALL be `MopWidgetPass, MenuGatewayPass, WtgPass, NavFrontierPass, MopFrontierPass, CoveragePass, FormCompletionPass`

#### Scenario: empty pipeline under the pure arm
- **WHEN** `fromParams` runs for the `base` cell
- **THEN** the pipeline SHALL contain zero passes
- **AND** the emitted record SHALL carry `passes:[]`

#### Scenario: injection is real — no decorative parameter
- **WHEN** two `ScoringParams` differing only in the presence of `MOP_FRONTIER` (`mopFrontierWeight` 0 vs 200) are used to assemble pipelines over the same context
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

The roster SHALL be exactly these seven passes, each gated by its mounting feature (the root `MOP` for `MopWidgetPass`, a member feature for the others) and, where listed, by data. Subsequent changes MAY introduce additional passes through the same `ScoringPass` interface and `ScoringPipeline.fromParams` assembly point, subject to INV-ARCH-02/04/13/14 and to declaring the new pass's member feature and group in the run-spec `Feature` model (the key-ownership totality test enforces this).

| Pass | Group | `isEnabled()` gate |
|---|---|---|
| `MopWidgetPass` | MOP | `MOP` in the plan |
| `MenuGatewayPass` | MOP | `MENU_GATEWAY` in the plan |
| `WtgPass` | MOP | `WTG` in the plan `&& StaticGuiModel.hasWtgData()` |
| `NavFrontierPass` | STATIC | `NAV_FRONTIER` in the plan `&& StaticGuiModel.hasWtgData()` |
| `MopFrontierPass` | MOP | `MOP_FRONTIER` in the plan `&& StaticGuiModel.hasWtgData()` |
| `CoveragePass` | ADDONS | `COVERAGE_BOOST` in the plan |
| `FormCompletionPass` | ADDONS | `FORM_COMPLETION` in the plan |

A weight-activated feature (`WTG`, `NAV_FRONTIER`, `MOP_FRONTIER`, `MENU_GATEWAY`, `COVERAGE_BOOST`) is absent from the plan when its weight is at the neutral value 0 or when its group is off (`run-spec` INV-RUN-05). Each pass's scoring semantics (what term it computes) SHALL remain as its originating capability specifies, written to its own field ("Scorer Ownership by Group").

#### Scenario: FormCompletionPass gated by the new flag

- **WHEN** a cell with `ADDONS` sets `ape.formCompletionEnabled=false`, or the cell has no `ADDONS`
- **THEN** `FORM_COMPLETION` SHALL be absent from the plan, `FormCompletionPass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline
- **AND** the form-completion boost, the deterministic fill and the submit exclusion SHALL NOT occur (`form-completion`)

#### Scenario: weight-gated pass off via its existing knob

- **WHEN** a cell with `ADDONS` sets `ape.coverageBoostWeight=0`
- **THEN** `COVERAGE_BOOST` SHALL be absent from the plan, `CoveragePass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline

### Requirement: Parity Configuration Flags

The following keys SHALL be the activation keys of ADDONS member features (`guidance-groups`, design D-B1): each feature depends on `ADDONS`, so with `ape.addons=false` (the jar default and the `base`/`mop` cells) every one of them is absent from the resolved plan and its mechanism is not constructed, whatever its own key says — unless the key is set explicitly, which aborts resolution with `MISSING_DEPENDENCY`. With `ape.addons=true`, each feature is present at its default and a single key can switch it off. Every read site SHALL read the plan (`spec.has(Feature.X)`), never `Config` (design D-B6).

| Property Key | Type | Default | Feature | Gate |
|-------------|------|---------|---------|------|
| `ape.formCompletionEnabled` | boolean | `true` | `FORM_COMPLETION` | `FormCompletionPass`, the deterministic-fill branch in `ApeAgent.checkInput()` and the submit exclusion |
| `ape.modelMenuEnabled` | boolean | `true` | `MODEL_MENU` | inclusion of the fork `menuAction` in `State.getActions()` |
| `ape.treeEnhancementsEnabled` | boolean | `true` | `TREE_ENHANCEMENTS` | the three `GUITreeBuilder` perception enhancements (WebView-prune actionable count, AndroidX actionability, ViewPager scrollable) |
| `ape.activityBudgetEnabled` | boolean | `true` | `ACTIVITY_BUDGET` | `ActivityBudgetTracker` instantiation + the `BUDGET` guard stage |

`ape.leastVisitedPriorityTiebreak` is retired: "replaced by the scorer-sum tie (X1/R2), which equals APE with zero scorers" (design D-B4); setting it aborts with `retired_key`. The former `stepTelemetryEnabled` flag is deleted (step recording is always-on) and the former `apePureMode` mechanism is deleted; the `base` cell is the control.

- **INV-ARCH-07**: With `ape.addons=true` and none of these keys set, the four features SHALL be in the plan. With `ape.addons=false` and none of these keys set, none of them SHALL be in the plan, and the menu action, the tree enhancements, the activity budget and form completion SHALL be absent, as in upstream APE.

#### Scenario: defaults preserve current behavior

- **WHEN** `ape.properties` sets `ape.addons=true` and none of the four keys
- **THEN** `FORM_COMPLETION`, `MODEL_MENU`, `TREE_ENHANCEMENTS` and `ACTIVITY_BUDGET` SHALL be in the plan's `features`
- **AND WHEN** it sets neither `ape.addons` nor the four keys
- **THEN** none of the four SHALL be in the plan, and the fork `menuAction` SHALL be absent from `State.getActions()`

#### Scenario: a single gate overridden without the kill-switch

- **WHEN** `ape.properties` sets `ape.addons=true` and only `ape.modelMenuEnabled=false`
- **THEN** `MODEL_MENU` SHALL be absent from the plan and the fork `menuAction` SHALL be absent from `State.getActions()`
- **AND** the other three features SHALL remain in the plan
- **AND WHEN** it sets `ape.modelMenuEnabled=true` with `ape.addons=false`
- **THEN** resolution SHALL abort with `MISSING_DEPENDENCY` naming `MODEL_MENU` and `ADDONS`

#### Scenario: retired kill-switch key aborts

- **WHEN** `ape.properties` sets `ape.apePureMode=true`, or `ape.leastVisitedPriorityTiebreak=false`
- **THEN** resolution SHALL abort with a retired-key diagnostic before step 1

#### Scenario: telemetry has no gate

- **WHEN** `ape.properties` sets `ape.stepTelemetryEnabled=false` (a removed key)
- **THEN** the run SHALL abort at plan validation with an unknown-key error (fail-fast, run-spec capability) — there is no configuration that suppresses step recording

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) is a MOP scorer, mounted by `MOP_FRONTIER` (activated by `ape.mopFrontierWeight`, POSITIVE, neutral 0, depends on `MOP`). It SHALL add `mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by `WtgPass` and `NavFrontierPass`), obtained via its own `StaticGuiModel.getWtgTransitions(activity)` lookup;
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the term recedes once the target is visited).

Under rule R1 the pass SHALL compute its term without reading priority or any other scorer field, write it to its own field `mopFrontierBoost` and add it to the priority (`action.setPriority(action.getPriority() + term)` — the steering mechanism, since `mopFrontierBoost` never enters `getPriority()`). It SHALL NOT write `wtgBoost` or `navFrontierBoost`. Its term is visible in the step record as `dec.mopf`, and its causal effect at a weighted pick as `dec.cfc.mopf` (`action-selection`).

`MopFrontierPass.isEnabled()` SHALL be true only when `MOP_FRONTIER` is in the plan and `StaticGuiModel.hasWtgData()` holds. It is independent of and additive to the STATIC `NavFrontierPass` (which requires only an unvisited target, not a MOP one): B is the strictly narrower predicate (unvisited AND MOP), and the two terms stay in separate fields. An explicit non-zero `ape.mopFrontierWeight` on a plan without `MOP` aborts resolution as a missing dependency (`run-spec` INV-RUN-05).

- **INV-MFP-01**: `MopFrontierPass` SHALL add `mopFrontierWeight` to an action **only** when its matched WTG transition target satisfies both `activityHasMop(target) == true` AND `Graph.getActivityNode(target) == null` at scoring time. An action failing either condition SHALL receive nothing from this pass.
- **INV-MFP-02**: The term SHALL be added to the priority AND written to `mopFrontierBoost`; because `mopFrontierBoost` is never read by `getPriority()`, the priority increment is mandatory for the term to steer. `MopFrontierPass` SHALL NOT write `wtgBoost` or `navFrontierBoost`; when `WtgPass` and/or `NavFrontierPass` co-apply to the same action, each field SHALL reflect only its own pass's term.
- **INV-MFP-03**: With `MOP_FRONTIER` absent from the plan, the scoring outcome SHALL be identical to the pipeline without `MopFrontierPass`.

#### Scenario: unvisited MOP target boosted into its own field
- **WHEN** widget W's WTG transition targets `com.x.CryptoActivity`, `activityHasMop("com.x.CryptoActivity")==true`, `Graph.getActivityNode("com.x.CryptoActivity")==null`, and `ape.mopFrontierWeight=200` in a cell with `MOP`
- **THEN** W's action priority SHALL be increased by 200
- **AND** its `mopFrontierBoost` SHALL be 200 and its `wtgBoost` and `navFrontierBoost` SHALL be unchanged by this pass

#### Scenario: MOP but already visited — no boost
- **WHEN** the transition target is MOP-bearing but `Graph.getActivityNode(target)` is non-null (visited)
- **THEN** `MopFrontierPass` SHALL add nothing to that action

#### Scenario: unvisited but non-MOP — no boost
- **WHEN** the transition target is unvisited but `activityHasMop(target)==false`
- **THEN** `MopFrontierPass` SHALL add nothing (this is `NavFrontierPass`'s job, not B's)

#### Scenario: co-applying boosts stay decomposable
- **WHEN** in the `as_mop` cell the target is MOP-bearing AND unvisited, with `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`
- **THEN** the action's priority SHALL gain +600 total
- **AND** its `wtgBoost` SHALL be 200, its `navFrontierBoost` 200 and its `mopFrontierBoost` 200 — the three mechanisms are separable in the record as `dec.wtg`, `dec.navf` and `dec.mopf`

#### Scenario: disabled
- **WHEN** `ape.mopFrontierWeight=0`, or the cell has no `MOP`
- **THEN** `MOP_FRONTIER` SHALL be absent from the plan and the scoring pipeline SHALL behave exactly as without `MopFrontierPass`, with `mopFrontierBoost` 0 on every action

### Requirement: Per-Step Decision Outcome Attribution

After a state transition is recorded (`Model.addTransition(source, action, target, ...)` in `StatefulAgent.updateGraph()`), `StatefulAgent` SHALL attribute the executed action's result back to the decision that selected it by attaching the `out` section to that decision's pending `StepRecord` and closing it (event-sink capability). Attribution is unconditional — there is no telemetry gate and no arm-level variation. Call, decision, and outcome share one record: no offline join exists.

The record closed SHALL be the one opened at the exploration step at which the executed action was **selected**. Because an action is selected at step N and its resulting transition is only observed during step N+1's processing (the agent timestamp has already advanced), `StatefulAgent` SHALL buffer the selection step and the selected action at decision time, and consume the buffer at outcome time.

**Buffer discipline.** The non-model decision branch (event-level actions, e.g. the stagnation activity launcher) SHALL clear the buffer instead of writing it: non-model actions do not produce transitions under their own identity, and a stale model-action buffer could otherwise be resurrected later by state recovery (`recoverCurrentState()` re-installs the last history action as `currentAction`). The non-model step's record is closed without an `out` member.

**Emission point and guards.** The outcome closure SHALL live in `StatefulAgent.updateGraph()`, immediately after `Model.addTransition(...)` returns — NOT inside `Model`/`Graph` — so the refinement rebuild replay (which re-records transitions via the `Graph.addTransition(GUITreeTransition)` overload, bypassing `Model.addTransition`) cannot close spurious records. Closure with `out` SHALL occur only when ALL of the following hold, and the buffer SHALL be consumed (cleared) upon closure:

1. the `StateTransition` returned by `Model.addTransition` is non-null (`addTransition` returns null on the run's first step, after restarts, and on the stale-ephemeral drop);
2. a decision is buffered and the buffered action is reference-equal to `currentAction` (state recovery and non-model interludes install a different action object; a mismatch means the recorded transition does not belong to the buffered decision);
3. the buffer has not already been consumed for this decision — single-shot consumption guarantees that a second `addTransition` for the same decision (the `BadStateException` selection-retry path re-enters the update without advancing the timestamp; recovery can re-record the last history action) cannot attach a duplicate outcome.

**Refinement remap.** Model refinement (`preEvolveModel()` → `updateModel()`) replaces `currentAction` with the corresponding action object of the rebuilt model before `updateGraph()` runs. `updateModel()` SHALL remap the buffered action through the same `model.update(...)` mapping applied to `currentAction`, so refinement steps still receive their `out`. Without the remap, the reference guard would fail exactly on the non-deterministic steps — a systematic attribution bias against the most informative steps.

The `out` section SHALL carry:

| Field | Source |
|-------|--------|
| `new_state` | `true` when the target state was visited for the first time (`_isNewState`); omitted when `false` |
| `target` | target state's run-local `STATE` dictionary ID (the state key is on the dictionary entry) |
| `act_changed` | `true` when the target activity differs from the source activity (negation of the recorded `StateTransition.isSameActivity()`); omitted when `false` |

`decision_source` is not repeated in `out` — it is the same record's `dec.src` by construction. The outcome-side `activity_has_mop` is derivable via `out.target → STATE.act → ACT.mop`. `target` reports the state observed at the next model update: when fuzzing piggybacks events after the selected action, or a bad-state `EVENT_ACTIVATE` interlude executes, the recorded transition — and hence `target`/`new_state` — reflects the selected action plus those trailing events; offline analysis SHOULD treat the outcome as "state reached by the step", not "immediate post-action state".

- **INV-ARCH-09**: A `StepRecord`'s `out` section SHALL describe the transition of the action recorded in that same record's `dec` section (enforced by the reference-equality buffer guard), and at most one `out` SHALL ever be attached per record. A record MAY be closed without an `out` member — when the selected action produced no recorded transition (restart, refinement discard, non-model action, run end) — which is a legitimate, informative absence, not an error; it is distinct from the teardown flush encoding `out:{"resolved":false}`.

**Where the outcome line's two dropped fields went.** Two scenarios below keep their pre-change
header over a body this change contradicts:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `outcome on a non-MOP screen` | `activity_has_mop=0\|1` on the outcome line, the landing half of the evidential link | `out.target` → that `STATE` entry's `act` → that `ACT` entry's `mop`. The bit is recorded once per activity, never per step, on either the selection or the landing side (`action-selection`, `event-sink :: Dictionary Events and Run-Local IDs`). The link is unchanged; it is one dereference away instead of inline |
| `Outcome line suppressed under pure mode` | zero outcome lines when the arm sets the telemetry gate | **nothing, deliberately** — the gate is deleted with its key, so no arm can suppress attribution. What protects the control arm's integrity is now a property that can be checked instead of a switch that must be trusted: sink neutrality (`event-sink` INV-SNK-07), asserted by a permanent test |

#### Scenario: LLM decision attributed to a new-state discovery on a MOP screen

- **WHEN** an action with `decision_source=LLM` selected at step 42 executes, the resulting transition reaches a state visited for the first time, and the target activity is in the MOP-activity set
- **THEN** the `s:42` record SHALL be closed with `out` carrying `new_state:true` and the target state's dictionary ID
- **AND** the landing activity's MOP status SHALL be recoverable as `out.target` → `STATE.act` → `ACT.mop:1`
- **AND** the LLM call's sub-event, the decision, and the outcome SHALL all be members of that single record — no join key exists or is needed

#### Scenario: outcome on a non-MOP screen

- **WHEN** the recorded transition's target activity is not in the MOP-activity set (or `MopData` is null)
- **THEN** the dereference `out.target` → `STATE.act` → `ACT.mop` SHALL yield `0`
- **AND** the record SHALL carry no MOP-screen field of its own — the outcome half of the evidential link is preserved as a lookup, not as a per-step copy of a per-activity constant

#### Scenario: Outcome line suppressed under pure mode

- **WHEN** an arm sets `ape.stepTelemetryEnabled=false`
- **THEN** plan resolution SHALL abort with an unknown-key diagnostic and no run SHALL start — there is no suppressed mode, in the `ape_pure` arm or any other
- **AND** the control arm's integrity SHALL rest on the neutrality gate (INV-SNK-07) rather than on the absence of records

#### Scenario: Attribution identical in every arm

- **WHEN** the minimal control preset runs the same APK and seed as the full MOP+LLM preset
- **THEN** both runs SHALL attach `out` sections under identical rules (no gate, no arm-level suppression)
- **AND** the neutrality gate (INV-SNK-07) — not telemetry absence — is what protects the control arm's integrity

#### Scenario: Selected action with no recorded transition

- **WHEN** an action is selected at step 50 but the step ends in a restart before any transition is recorded (or it is the run's first step, where `addTransition` returns null)
- **THEN** the `s:50` record SHALL be closed without an `out` member
- **AND** this absence SHALL NOT be treated as an error by offline analysis ("selected, no clean transition")

#### Scenario: BadStateException retry emits a single outcome

- **WHEN** the transition for the action selected at step 60 is recorded, and action selection then throws `BadStateException`, causing the update to re-run `Model.addTransition` within the same step
- **THEN** the `s:60` record SHALL be closed exactly once with exactly one `out` (the buffer was consumed by the first closure)

#### Scenario: Refinement step still attributed

- **WHEN** the action selected at step 70 executes, and model refinement replaces `currentAction` with the rebuilt model's action object before `updateGraph()` runs
- **THEN** the buffered action SHALL be remapped alongside `currentAction`
- **AND** the `s:70` record SHALL be closed with its `out` for the recorded transition

### Requirement: Scorer Independence (Rule R1)

Every `ScoringPass` is a **scorer**: one term added to the priority of each candidate (plan §4.3, D-A4). Each scorer SHALL compute its term for an action only from:

- the screen (the new state, its actions and their resolved nodes);
- the model (graph, visit counts, activity nodes);
- its own data: its group's provider (`StaticGuiModel` for STATIC, `MopData`, `StaticGuiModel` and the read-only `MopInteractionLedger` for MOP, the coverage tracker or form context for ADDONS) and its own injected parameters.

It SHALL NOT read the action's current priority, nor any scorer field, its own included, from a previous write within the same scoring call. It SHALL write its term to its own field (assigned, one field per scorer, "Scorer Ownership by Group") and add the same term to the priority. Because the terms do not depend on one another, the chain is commutative: mounting or unmounting scorer B never changes the term scorer A computes (INV-ARCH-13). The sum of the seven fields is the action's **scorer sum**, the quantity X1 compares (`action-selection` rule R2) and the counterfactual zeroes field by field (`dec.cfc`).

A term may depend on the other actions of the same screen through the scorer's own computation — the `distance` tie split of `MopWidgetPass` divides a weight among the actions that hold the state's best level — because that is a function of the screen and the scorer's own data, not of another scorer's output.

#### Scenario: the order of the chain does not matter
- **WHEN** the `as_mop` cell's seven passes are applied to one state's actions in pipeline order and, in a test, in reverse order, starting from the same base priorities
- **THEN** every action SHALL end with the same priority and the same seven field values in both runs

#### Scenario: mounting a scorer does not change another's term
- **WHEN** a state is scored once with `CoveragePass` mounted (`coverageBoostWeight=100`) and once without it, with `MopWidgetPass` mounted both times
- **THEN** every action's `mopBoost` and `mopWeight` SHALL be identical in the two scorings, and only `coverageBoost` and the priority SHALL differ

#### Scenario: a scorer does not read priority
- **WHEN** two actions with identical node, data and visit history differ only in base priority (32 and 52)
- **THEN** every mounted pass SHALL write the same term to both actions

### Requirement: Scorer Ownership by Group

Each pass SHALL belong to exactly one group, SHALL be mounted by exactly one feature of that group — a member feature, or, for `MopWidgetPass` alone, the group root `MOP` (`guidance-groups`) — and SHALL write exactly one scorer field, rendered in the step record under its own name (`event-sink`):

| Pass | Group | Mounting feature (key) | Field | Record name |
|---|---|---|---|---|
| `MopWidgetPass` | MOP | the group root `MOP` (`ape.mop`) | `mopBoost` (and the undivided `mopWeight`) | `mop` |
| `MenuGatewayPass` | MOP | `MENU_GATEWAY` (`ape.mopWeightOpenMenu`; also requires `MODEL_MENU`) | `menuBoost` | `menu` |
| `WtgPass` | MOP | `WTG` (`ape.mopWeightWtg`) | `wtgBoost` | `wtg` |
| `NavFrontierPass` | STATIC | `NAV_FRONTIER` (`ape.frontierBoostWeight`) | `navFrontierBoost` | `navf` |
| `MopFrontierPass` | MOP | `MOP_FRONTIER` (`ape.mopFrontierWeight`) | `mopFrontierBoost` | `mopf` |
| `CoveragePass` | ADDONS | `COVERAGE_BOOST` (`ape.coverageBoostWeight`) | `coverageBoost` | `cov` |
| `FormCompletionPass` | ADDONS | `FORM_COMPLETION` (`ape.formCompletionEnabled`) | `formBoost` | `form` |

`mopWeight` is not a separate scorer: it is the MOP widget scorer's undivided weight, read by the MOP coin (`mop-guidance`) and never summed into the scorer sum. A pass reads only its group's provider (INV-ARCH-14). `ModelAction` SHALL carry no `wtgsrc` field and the record no `dec.wtgsrc` (P3): with one producer per field the stamp has nothing to tell apart.

#### Scenario: the base cell mounts no scorer
- **WHEN** the `base` cell resolves
- **THEN** no pass SHALL be constructed and every action's seven fields SHALL be 0

#### Scenario: the as cell mounts the ADDONS and STATIC scorers only
- **WHEN** the `as` cell resolves with the artifact loaded and WTG data present
- **THEN** the pipeline SHALL hold `NavFrontierPass`, `CoveragePass` and `FormCompletionPass`, and no MOP pass
- **AND** no record of the run SHALL carry `dec.mop`, `dec.mopf`, `dec.wtg` or `dec.menu`

#### Scenario: the mop cell mounts the MOP scorers without the addon-dependent one
- **WHEN** the `mop` cell resolves with WTG data present
- **THEN** the pipeline SHALL hold `MopWidgetPass`, `WtgPass` and `MopFrontierPass`
- **AND** `MenuGatewayPass` SHALL be absent, because `MENU_GATEWAY` requires `MODEL_MENU`, an ADDONS member

## Invariants

- **INV-ARCH-10**: The seven scorer fields SHALL be disjoint accumulators, one per pass: `mopBoost` (MopWidgetPass), `menuBoost` (MenuGatewayPass), `wtgBoost` (WtgPass), `navFrontierBoost` (NavFrontierPass), `mopFrontierBoost` (MopFrontierPass), `coverageBoost` (CoveragePass), `formBoost` (FormCompletionPass). The total priority gain of an action from the pipeline SHALL equal the sum of its seven fields. No pass SHALL write another pass's field.
- **INV-ARCH-13**: No scoring pass SHALL read `ModelAction.getPriority()` or another pass's field to compute its term. Applying the mounted passes in any order SHALL leave every action with the same priority and the same seven fields.
- **INV-ARCH-14**: A pass SHALL be constructed if and only if its mounting feature — its member feature, or the group root `MOP` for `MopWidgetPass` — is in the resolved plan (and its data gate, where it has one, holds). A pass owned by a group SHALL read only that group's provider: a STATIC pass reads `StaticGuiModel` and never `MopData`; an ADDONS pass reads neither. No pass reads the run's `MopInteractionLedger` (`mop-guidance`, design D-B19 reverted).
- **INV-MFP-04**: `MopFrontierPass` SHALL be mounted only with `MOP_FRONTIER`, a MOP member; in a cell without `MOP`, `mopFrontierBoost` SHALL be 0 on every action and `dec.mopf` SHALL never be written.
