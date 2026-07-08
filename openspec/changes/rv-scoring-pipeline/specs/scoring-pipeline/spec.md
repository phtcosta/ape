# scoring-pipeline — delta: rv-scoring-pipeline

## Purpose

The `scoring-pipeline` capability gives APE-RV a single, composable place for the priority-boost passes that steer action selection, and a single kill-switch that reduces the agent to upstream-APE-equivalent selection. Before this capability, the RV scoring path was six `if`-blocks stacked inline in `StatefulAgent.adjustActionsByGUITree()` plus a seventh (form-completion) with no flag, and four more fork-only behaviors were always-on with no flag. This made an "APE-pure" experiment arm inexpressible and the priority path untestable in isolation.

This capability defines: a `ScoringPass` plugin interface and a `ScoringContext`; a single assembly point `ScoringPipeline.fromConfig(Config)` that maps flags to an ordered list of enabled passes and logs the assembly; the reduction of `adjustActionsByGUITree()` to the upstream base-priority loop followed by one pipeline invocation; a set of parity flags that gate the previously-flagless fork behaviors; and the `apePureMode` kill-switch with its parity invariant. It changes no default behavior — every flag defaults to current aperv behavior and every pass reproduces its inline block at default flags.

The scoring **semantics** of each pass remain owned by the pass's originating capability (`mop-guidance`, `ui-coverage`, `form-completion`, and the archived `activity-frontier`/`sibling-state-depriority`/`back-menu-pick-cap`). This capability owns only the structural contract (the pipeline) and the parity contract (the kill-switch and its flags).

## Data Contracts

### Input
- `Config` — the resolved configuration; `ScoringPipeline.fromConfig(Config)` reads the pass gates and parity flags once at construction.
- `ScoringContext` — bundle of the collaborators the passes read: `MopData` (nullable), `UICoverageTracker` (nullable), a graph/`ActivityNode` accessor, and the per-run pick counters (MOP-target and BACK/MENU pick counts) that the passes read and increment.

### Output
- Mutated `ModelAction.priority` (and the passes' provenance fields — `mopBoost`/`wtgBoost`/`coverageBoost`/`formBoost`) on the current state's actions, as each enabled pass specifies.

### Side-Effects
- **[Trace]**: one `[APE-ARCH] passes=[<Pass1>, <Pass2>, ...]` line emitted once at pipeline construction, listing the enabled passes in pipeline order.
- **[Trace]**: when `apePureMode=true`, one `[APE-ARCH] apePureMode forced <key>=<value>` line per RV-defining flag the kill-switch overwrote.

## ADDED Requirements

### Requirement: ScoringPass Plugin Interface

The scoring path SHALL be expressed as an ordered sequence of `ScoringPass` objects in the package `com.android.commands.monkey.ape.agent.scoring`. The interface SHALL be:

```java
public interface ScoringPass {
    String name();                                        // for the assembly log and tests
    boolean isEnabled();                                  // decided in the constructor from Config
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}
```

`isEnabled()` SHALL be decided once when the pass is constructed (reading `Config`), and SHALL NOT be re-evaluated per `apply` call. A pass that is disabled SHALL NOT be added to the assembled pipeline; therefore a disabled pass SHALL make no priority change, set no provenance field, and emit no log line. Each pass SHALL read its collaborators (MopData, coverage tracker, graph, pick counters) exclusively through the `ScoringContext` passed to `apply`; a pass SHALL NOT hold run-mutable state of its own, so that per-run counters live on the context and the pass stays unit-testable with a stub context.

- **INV-ARCH-02**: A `ScoringPass` whose `isEnabled()` returned `false` at construction SHALL NOT appear in the assembled pipeline and SHALL therefore be a strict no-op for the run — zero priority mutations, zero provenance writes, zero log lines. A `ScoringPass` SHALL hold no run-mutable field; all cross-step mutable state (pick counters) SHALL live on the `ScoringContext`.

#### Scenario: disabled pass is absent and inert
- **WHEN** a `ScoringPass` is constructed with `Config` values that make `isEnabled()` return `false`
- **THEN** it SHALL NOT be included in the pipeline built by `ScoringPipeline.fromConfig`
- **AND** no action priority, provenance field, or log line SHALL be attributable to it during the run

#### Scenario: enabled pass reads collaborators from the context
- **WHEN** an enabled pass's `apply(state, actions, ctx)` needs `MopData` or the per-run MOP-target pick counter
- **THEN** it SHALL obtain them from `ctx` (the `ScoringContext`), not from a field of its own

---

### Requirement: Scoring Pipeline Assembly from Config

`ScoringPipeline.fromConfig(Config)` SHALL be the single point that maps configuration to the ordered list of enabled passes. It SHALL construct the seven passes in the fixed order `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `FrontierPass` → `CoveragePass` → `SiblingPenaltyPass` → `FormCompletionPass`, retain only those whose `isEnabled()` is `true`, and emit exactly one `[APE-ARCH] passes=[...]` line listing the retained passes' `name()` values in pipeline order. No other code path SHALL assemble or reorder the pipeline.

The fixed order SHALL equal the order of the pre-refactor inline scoring blocks in `StatefulAgent.adjustActionsByGUITree()`, which is the order the `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion` specs already require (base → MOP-widget → menu-gateway → WTG → frontier → coverage → sibling → form). Extracting the blocks into passes SHALL NOT change this order.

- **INV-ARCH-03**: The pipeline order SHALL equal the order of the pre-refactor inline scoring blocks and SHALL satisfy the pass-order contracts already asserted by `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion`. `MopWidgetPass` SHALL precede `WtgPass`, which SHALL precede `CoveragePass`, which SHALL precede `FormCompletionPass`.
- **INV-ARCH-04**: `ScoringPipeline.fromConfig` SHALL be the sole assembly point. The `[APE-ARCH] passes=[...]` startup line SHALL list exactly the enabled passes, in pipeline order, and nothing else SHALL construct a pipeline.

#### Scenario: only enabled passes are assembled and logged
- **WHEN** `ScoringPipeline.fromConfig(Config)` runs with `mopDataPath=null` (MOP passes off), `coverageBoostWeight=100`, `siblingStatePenalty=24`, `formCompletionEnabled=true`
- **THEN** the pipeline SHALL contain `CoveragePass`, `SiblingPenaltyPass`, `FormCompletionPass` in that order and no MOP/WTG/Frontier pass
- **AND** one `[APE-ARCH] passes=[CoveragePass, SiblingPenaltyPass, FormCompletionPass]` line SHALL be emitted

#### Scenario: full MOP arm assembles the ordered set
- **WHEN** `fromConfig` runs with MopData present, `mopWeightWtg=200`, `frontierBoostWeight=200`, `coverageBoostWeight=100`, `siblingStatePenalty=24`, `formCompletionEnabled=true`
- **THEN** the pipeline order SHALL be `MopWidgetPass, MenuGatewayPass, WtgPass, FrontierPass, CoveragePass, SiblingPenaltyPass, FormCompletionPass`

#### Scenario: empty pipeline under the pure arm
- **WHEN** `fromConfig` runs with `apePureMode=true` (all pass gates forced off)
- **THEN** the pipeline SHALL contain zero passes
- **AND** the emitted line SHALL be `[APE-ARCH] passes=[]`

---

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

The extraction SHALL yield exactly these seven passes, each extracting the inline block named, each gated by the flag named. The roster is the extraction set, not a closed list: subsequent changes MAY introduce additional passes through the same `ScoringPass` interface and `ScoringPipeline.fromConfig` assembly point, subject to INV-ARCH-02/04 and to registering any new RV flag in the `apePureMode` registry (INV-ARCH-06). (`MopFrontierPass`, strategy B, is introduced by the change `mop-reach-strategies`, not by this one.)

| Pass | Extracted inline block | `isEnabled()` gate |
|---|---|---|
| `MopWidgetPass` | MOP-widget boost | `ScoringContext.getMopData() != null` |
| `MenuGatewayPass` | OPTIONSMENU-gateway menu boost | `ScoringContext.getMopData() != null` |
| `WtgPass` | WTG-reach boost | `getMopData() != null && hasWtgData() && Config.mopWeightWtg != 0` |
| `FrontierPass` | WTG frontier (unvisited-activity) boost | `Config.frontierBoostWeight > 0` |
| `CoveragePass` | per-action coverage boost | `Config.coverageBoostWeight != 0` |
| `SiblingPenaltyPass` | sibling-redundancy deprioritization | `Config.siblingStatePenalty > 0` |
| `FormCompletionPass` | form-completion boost | `Config.formCompletionEnabled` |

Each pass's scoring semantics (what boost it computes and where it writes provenance) SHALL remain exactly as its originating capability specifies; this capability SHALL NOT alter those semantics. Six passes reuse a pre-existing weight/data gate; only `FormCompletionPass` introduces a new boolean gate (`formCompletionEnabled`), because the form-completion block had no off switch before this change.

#### Scenario: FormCompletionPass gated by the new flag
- **WHEN** `Config.formCompletionEnabled=false`
- **THEN** `FormCompletionPass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline
- **AND** the form-completion boost and deterministic fill SHALL NOT occur (form-completion spec's flag scenario)

#### Scenario: weight-gated pass off via its existing knob
- **WHEN** `Config.coverageBoostWeight=0`
- **THEN** `CoveragePass.isEnabled()` SHALL return `false` and the pass SHALL be absent from the pipeline

---

### Requirement: apePureMode Kill-Switch and Parity

`Config.apePureMode` (`ape.apePureMode`, boolean, default `false`) SHALL be a kill-switch enforced in `Config.load`: after the normal property load, when `apePureMode` is `true`, `load` SHALL overwrite **every** RV-defining flag to its off/inert value — boolean flags to `false`, weight/boost integers to `0`, and RV-activated thresholds to their upstream-inert value (in particular `activityStableRestartThreshold` to `Integer.MAX_VALUE`) — and SHALL emit one `[APE-ARCH] apePureMode forced <key>=<value>` line per overwritten flag. The set of RV-defining flags SHALL be a single registry that both `Config.load` and the completeness guard test consult, so that a newly added RV flag that is not registered is detected by the guard.

With `apePureMode=true`, APE-RV action selection SHALL be equivalent to upstream APE (`8f51b99`). Two behaviors SHALL remain on as documented exceptions, because neither changes what the agent *selects*: the `ApePinchOrZoomEvent` array-sizing/emit crash fix (a crash is not a selection behavior) and seed handling via `RandomHelper.seed(-s)` (reproducibility infrastructure, arm-neutral).

- **INV-ARCH-01**: With `apePureMode=true`, the sequence of selected actions for a given APK, seed, and configuration SHALL be equivalent to upstream APE's selection, except for the two documented always-on behaviors (the `ApePinchOrZoomEvent` crash fix and seed handling). Concretely, under `apePureMode=true`: the scoring pipeline SHALL be empty; `State.getActions()` SHALL contain no `MODEL_MENU`; the epsilon SHALL be the fixed `Config.defaultEpsilon`; input SHALL use the legacy `StringCache` generator; and zero `[APE-STEP]` lines SHALL be emitted.
- **INV-ARCH-06**: Every RV-defining `Config` flag SHALL be a member of the kill-switch registry, and `apePureMode=true` SHALL force each registered flag to its off/inert value. A registered flag that `load` fails to force, or an RV flag absent from the registry, SHALL be a guard-test failure.

#### Scenario: kill-switch forces the RV flags off and logs them
- **WHEN** `ape.properties` sets `ape.apePureMode=true` (and, redundantly, some RV flags to non-default values)
- **THEN** after `Config.load`, `formCompletionEnabled`, `stepTelemetryEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, and `activityBudgetEnabled` SHALL all be `false`
- **AND** every RV weight/boost integer (`frontierBoostWeight`, `coverageBoostWeight`, `siblingStatePenalty`, `mopWeightWtg`, `mopWeightOpenMenu`, ...) SHALL be `0`
- **AND** `activityStableRestartThreshold` SHALL be `Integer.MAX_VALUE`
- **AND** one `[APE-ARCH] apePureMode forced <key>=<value>` line SHALL be emitted per overwritten flag

#### Scenario: pure arm selects like upstream APE
- **WHEN** a run is launched with `apePureMode=true` on a fixed APK and seed
- **THEN** the scoring pipeline SHALL be empty
- **AND** `State.getActions()` SHALL contain no `MODEL_MENU` action
- **AND** the epsilon-greedy epsilon SHALL be the fixed `Config.defaultEpsilon`
- **AND** input generation SHALL use the legacy `StringCache` path
- **AND** zero `[APE-STEP]` lines SHALL be emitted for the run

#### Scenario: always-on exceptions survive the pure arm
- **WHEN** `apePureMode=true`
- **THEN** the `ApePinchOrZoomEvent` fix SHALL remain active (a malformed points array SHALL be rejected, never dereferenced)
- **AND** `RandomHelper` SHALL still be seeded from the Monkey `-s` value

#### Scenario: unregistered RV flag fails the completeness guard
- **WHEN** a new RV-defining `Config` flag is added but not registered in the kill-switch registry
- **THEN** the completeness guard test SHALL fail
- **AND** the failure SHALL identify the unregistered flag

---

### Requirement: Parity Configuration Flags

`Config.java` SHALL declare the following flags, loaded from `ape.properties` at class-loading time. Every default SHALL preserve current aperv behavior; this change SHALL NOT alter any default.

| Flag | Property Key | Type | Default | Gate |
|------|-------------|------|---------|------|
| `formCompletionEnabled` | `ape.formCompletionEnabled` | boolean | `true` | `FormCompletionPass` + the deterministic-fill branch in `ApeAgent.checkInput()` |
| `stepTelemetryEnabled` | `ape.stepTelemetryEnabled` | boolean | `true` | the `[APE-STEP]` per-step line + per-action timing |
| `modelMenuEnabled` | `ape.modelMenuEnabled` | boolean | `true` | inclusion of the fork `menuAction` in `State.getActions()` |
| `leastVisitedPriorityTiebreak` | `ape.leastVisitedPriorityTiebreak` | boolean | `true` | the priority tiebreak in `State.greedyPickLeastVisited()` |
| `treeEnhancementsEnabled` | `ape.treeEnhancementsEnabled` | boolean | `true` | the three `GUITreeBuilder` perception enhancements (WebView-prune actionable count, AndroidX actionability, ViewPager scrollable) |
| `activityBudgetEnabled` | `ape.activityBudgetEnabled` | boolean | `true` | `ActivityBudgetTracker` instantiation + the budget check in `SataAgent.selectNewActionNonnull()` |
| `apePureMode` | `ape.apePureMode` | boolean | `false` | kill-switch (see "apePureMode Kill-Switch and Parity") |

- **INV-ARCH-07**: With none of these keys set in `ape.properties`, `formCompletionEnabled`, `stepTelemetryEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, and `activityBudgetEnabled` SHALL be `true` and `apePureMode` SHALL be `false`, and the agent's action-selection behavior SHALL be identical to the pre-change aperv.

#### Scenario: defaults preserve current behavior
- **WHEN** `ape.properties` sets none of the parity flags
- **THEN** the six behavior gates SHALL be `true` and `apePureMode` SHALL be `false`
- **AND** the pipeline, telemetry, menu action, tiebreak, tree perception, and activity budget SHALL all be active as before this change

#### Scenario: a single gate overridden without the kill-switch
- **WHEN** `ape.properties` sets only `ape.stepTelemetryEnabled=false`
- **THEN** `Config.stepTelemetryEnabled` SHALL be `false` and no `[APE-STEP]` line SHALL be emitted
- **AND** all other gates SHALL retain their `true` defaults
