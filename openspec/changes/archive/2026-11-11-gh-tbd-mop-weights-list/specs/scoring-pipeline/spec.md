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

`isEnabled()` SHALL be decided once when the pass is constructed — from the presence of its mounting feature in the resolved plan and from its data gate where it has one — never from static `Config`, and SHALL NOT be re-evaluated per `apply` call. For the member-feature passes the feature's presence is carried by the `ScoringParams` value injected at construction (`ScoringParams.fromSpec` writes the off value, `0` or `false`, when the feature is absent); `MopWidgetPass`, mounted by the root `MOP`, is enabled iff the `ScoringContext` carries a `MopContext`, which it does exactly when `MOP` is in the plan. A pass that is disabled SHALL NOT be added to the assembled pipeline; therefore a disabled pass SHALL make no priority change, set no scorer field, and emit no log line. Each pass SHALL read its weights from its injected `ScoringParams` — or, for `MopWidgetPass`, from the run's `MopContext` (`ScoringContext.getMopContext()`, built from `spec().mop()` and the loaded providers), which carries the scoring mode and the `flag`/`distance` weights — and its collaborators (its group's provider — `StaticGuiModel` or `MopData` — the coverage tracker, the form context, the graph) exclusively through the `ScoringContext` passed to `apply`, and only those its group owns ("Scorer Ownership by Group"); a pass SHALL NOT hold run-mutable state of its own, so that the pass stays unit-testable with a stub context and explicit params. `MopScorer` SHALL receive its weights as arguments from the calling pass: the `MopContext` for the widget weights (`mopWeightDirect`, `mopWeightTransitive`, `ape.mopWeights`, the `distance` ladder), and an explicit integer for `mopWeightOpenMenu` (`MopScorer.scoreOpenMenu`) and `mopWeightWtg` (`MopScorer.scoreWtg`); `MopScorer` SHALL NOT read static `Config`.

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
