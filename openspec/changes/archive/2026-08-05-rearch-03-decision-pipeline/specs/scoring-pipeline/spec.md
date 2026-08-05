## MODIFIED Requirements

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
