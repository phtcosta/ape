## Invariants

- **INV-ARCH-01** (retired): its subject, the `apePureMode` kill-switch and its upstream-parity claim, was removed
  by `rearch-02-runspec`; the `base` cell is the control, and telemetry neutrality is `event-sink` INV-SNK-07.
  Citations of it elsewhere are historical.
- **INV-ARCH-06** (retired): the kill-switch registry it required was dissolved with `apePureMode`; its substitute is
  `run-spec` INV-RUN-05 (a parameter of an absent feature has no constructed mechanism). Citations of it elsewhere
  are historical.

## RENAMED Requirements

- FROM: `### Requirement: Scoring Pipeline Assembly from Config`
- TO: `### Requirement: Scoring Pipeline Assembly from the RunSpec`

## MODIFIED Requirements

### Requirement: ScoringPass Plugin Interface

The scoring path SHALL be expressed as an ordered sequence of `ScoringPass` objects in the package `com.android.commands.monkey.ape.agent.scoring`. The interface SHALL be:

```java
public interface ScoringPass {
    String name();                                        // for the PIPELINE record and tests
    boolean isEnabled();                                  // decided in the constructor from injected params
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}
```

`isEnabled()` SHALL be decided once when the pass is constructed — from the presence of its mounting feature in the resolved plan and from its data gate where it has one — never from static `Config`, and SHALL NOT be re-evaluated per `apply` call. For the member-feature passes the feature's presence is carried by the `ScoringParams` value injected at construction (`ScoringParams.fromSpec` writes the off value, `0` or `false`, when the feature is absent); `MopWidgetPass`, mounted by the root `MOP`, is enabled iff the `ScoringContext` carries a `MopContext`, which it does exactly when `MOP` is in the plan. A pass that is disabled SHALL NOT be added to the assembled pipeline; therefore a disabled pass SHALL make no priority change, set no scorer field, and emit no log line. Each pass SHALL read its weights from its injected `ScoringParams` — or, for `MopWidgetPass`, from the run's `MopContext` (`ScoringContext.getMopContext()`, built from `spec().mop()` and the loaded providers), which carries the scoring mode and the `flag`/`distance` weights — and its collaborators (its group's provider — `StaticGuiModel` or `MopData` — the coverage tracker, the form context, the graph) exclusively through the `ScoringContext` passed to `apply`, and only those its group owns ("Scorer Ownership by Group"); a pass SHALL NOT hold run-mutable state of its own, so that the pass stays unit-testable with a stub context and explicit params. `MopScorer` SHALL receive its weights as arguments from the calling pass: the `MopContext` for the widget weights (`mopWeightDirect`, `mopWeightTransitive`, `mopWeightD1..3`), and an explicit integer for `mopWeightOpenMenu` (`MopScorer.scoreOpenMenu`) and `mopWeightWtg` (`MopScorer.scoreWtg`); `MopScorer` SHALL NOT read static `Config`.

- **INV-ARCH-02**: A `ScoringPass` whose `isEnabled()` returned `false` at construction SHALL NOT appear in the assembled pipeline and SHALL therefore be a strict no-op for the run — zero priority mutations, zero scorer-field writes, zero log lines. A `ScoringPass` SHALL hold no run-mutable field.
- **INV-ARCH-11**: No scoring pass, and no helper it calls (`MopScorer` included), SHALL read static `Config` at any time; every weight and gate SHALL arrive by injection — through `ScoringParams`, or, for the MOP widget weights and mode, through the run's `MopContext` handed in by the `ScoringContext`. Tests SHALL construct `ScoringParams` (and a `MopContext` where needed) directly, never mutate `Config`.
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

### Requirement: Scoring Pipeline Assembly from the RunSpec

`ScoringPipeline.fromParams(ScoringParams, ScoringContext, EventSink)` SHALL be the single point that maps the resolved plan's scoring configuration to the ordered list of enabled passes. It SHALL construct the **seven** candidate passes in the fixed order `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `NavFrontierPass` → `MopFrontierPass` → `CoveragePass` → `FormCompletionPass` (the frontier family contiguous), retain only those whose `isEnabled()` is `true` — that is, whose mounting feature is in the plan and whose data gate holds ("Scorer Ownership by Group") — and emit exactly one `PIPELINE` sink record (event-sink capability) whose `passes` member lists the retained passes' `name()` values in pipeline order. The `EventSink` argument is handed to `MopWidgetPass`, the one pass that records something of its own (the MOP exposure pair). No other code path SHALL assemble or reorder the pipeline. The class documentation SHALL state the seven-pass roster.

Under rule R1 the order has no effect on any term or on the final priorities ("Scorer Independence (Rule R1)"); it is kept fixed so the `PIPELINE` record and the assembly tests stay deterministic. The sole caller (the `StatefulAgent` constructor) SHALL pass the real `ScoringParams` derived from the `RunSpec` (`ScoringParams.fromSpec(spec)`), the agent's `ScoringContext` and the run's sink (`RunContext.current().sink()`).

**The same assembly SHALL also record the candidates it dropped.** Beside the retained list — whose content and format are unchanged — assembly SHALL record every candidate pass with whether it was constructed, carried by the `PIPELINE` record's `candidates` member. The census SHALL be produced inside `fromParams`, where the full candidate list is still in scope: the pipeline discards the disabled passes at construction and has no handle on them afterwards.

The record is data-bearing rather than plan-derivable: `WtgPass` and `MopFrontierPass` gate on `StaticGuiModel.hasWtgData()` as well as on their feature, and `NavFrontierPass` on the same data, which nothing in the plan reveals.

No `reason` field SHALL be added to the entries, and no `disabledReason()` method to the `ScoringPass` interface. Each gate is a conjunction of a feature in the plan and, for the WTG family, `hasWtgData()`, and both conjuncts are already recorded elsewhere in the same trace (`RUN_START.features`, `MOP_DATA.wtgEdges`), so the reason is a lookup rather than a field.

- **INV-ARCH-03**: The pipeline order SHALL be `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `NavFrontierPass`, `MopFrontierPass`, `CoveragePass`, `FormCompletionPass` among the retained passes. `MopFrontierPass` SHALL sit immediately after `NavFrontierPass` when both are retained.
- **INV-ARCH-04**: `ScoringPipeline.fromParams` SHALL be the sole assembly point. The `PIPELINE` record's `passes` member SHALL list exactly the enabled passes, in pipeline order, and nothing else SHALL construct a pipeline. The candidate census (`PIPELINE.candidates`) is a **sibling** record member, never a widening of this list.

#### Scenario: only enabled passes are assembled and logged
- **WHEN** `ScoringPipeline.fromParams(params, ctx, sink)` runs for a plan with `ADDONS` (`COVERAGE_BOOST` at `coverageBoostWeight=100`, `FORM_COMPLETION`) and without `STATIC` or `MOP`
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

`StatefulAgent.adjustActionsByGUITree()` (no arguments; it iterates `newState.getActions()`) SHALL consist of the upstream APE base-priority loop followed by exactly one pipeline invocation `scoringPipeline.apply(newState, actions, scoringContext)`. The base-priority loop SHALL be the upstream APE loop (`github.com/tianxiaogu/ape` @ `8f51b99`), term for term and in the same order; its one parameter, `maxExtraPriorityAliasedActions`, is read from the plan's injected exploration parameters. No RV-specific scoring term SHALL remain inline in the method body — every RV term SHALL be a `ScoringPass` in the pipeline. The `StatefulAgent` SHALL hold one `ScoringPipeline`, built once in its constructor from the `RunSpec` (`ScoringParams.fromSpec`), the `ScoringContext` and the run's sink. `ScoringPipeline.apply` is a strict no-op when the pipeline is empty; otherwise it clears the actions' boost fields (`resetBoosts()`) and runs the enabled passes.

This preserves `INV-EXPL-11` (adjustActionsByGUITree is called after base priority assignment and before selection) and `INV-MOP-03` (boosts are additive over the base priority): the base loop runs first, the pipeline adds on top.

- **INV-ARCH-05**: The non-pipeline body of `adjustActionsByGUITree()` SHALL be the upstream APE base-priority loop (same terms, same order, its bound read from the plan instead of `Config`); the single `scoringPipeline.apply(...)` call SHALL be the only RV addition to the method. There SHALL be no inline RV scoring `if`-block in the method.

#### Scenario: method body is upstream loop plus one pipeline call
- **WHEN** `adjustActionsByGUITree()` is invoked on the new state
- **THEN** it SHALL run the upstream base-priority loop over `newState.getActions()`
- **AND** it SHALL then invoke `scoringPipeline.apply(newState, actions, scoringContext)` exactly once
- **AND** it SHALL contain no other RV scoring logic

#### Scenario: empty pipeline yields upstream priorities
- **WHEN** the pipeline is empty (pure arm) and `adjustActionsByGUITree` runs
- **THEN** the resulting action priorities SHALL equal the upstream base-priority loop's output for the same actions and seed

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) is a MOP scorer, mounted by `MOP_FRONTIER` (activated by `ape.mopFrontierWeight`, POSITIVE, neutral 0, depends on `MOP`). It SHALL add `mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by `WtgPass` and `NavFrontierPass`), obtained via its own `StaticGuiModel.wtgTransitions(activity)` lookup;
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the term recedes once the target is visited).

Under rule R1 the pass SHALL compute its term without reading priority or any other scorer field, write it to its own field `mopFrontierBoost` and add it to the priority (`action.setPriority(action.getPriority() + term)` — the steering mechanism, since `mopFrontierBoost` never enters `getPriority()`). It SHALL NOT write `wtgBoost` or `navFrontierBoost`. Its term is visible in the step record as `dec.mopf`, and its causal effect at a weighted pick as `dec.cfc.mopf` (`action-selection`).

`MopFrontierPass.isEnabled()` SHALL be true only when `MOP_FRONTIER` is in the plan (`mopFrontierWeight > 0` in its `ScoringParams`), the context carries `MopData`, and `StaticGuiModel.hasWtgData()` holds. It is independent of and additive to the STATIC `NavFrontierPass` (which requires only an unvisited target, not a MOP one): B is the strictly narrower predicate (unvisited AND MOP), and the two terms stay in separate fields. An explicit non-zero `ape.mopFrontierWeight` on a plan without `MOP` aborts resolution as a missing dependency (`run-spec` INV-RUN-05).

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

### Requirement: Scorer Independence (Rule R1)

Every `ScoringPass` is a **scorer**: one term added to the priority of each candidate (plan §4.3, D-A4). Each scorer SHALL compute its term for an action only from:

- the screen (the new state, its actions and their resolved nodes);
- the model (graph, visit counts, activity nodes);
- its own data: its group's provider (`StaticGuiModel` for STATIC, `MopData` and `StaticGuiModel` for MOP, the coverage tracker or form context for ADDONS) and its own injected parameters. No scorer reads the run's `MopInteractionLedger` (INV-ARCH-14).

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
