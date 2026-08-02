# Delta Specification: scoring-pipeline (rearch-02-runspec)

## REMOVED Requirements

### Requirement: apePureMode Kill-Switch and Parity

**Reason**: The kill-switch mechanism — `Config.apePureMode`, `forceApePureModeInto` (a `Properties` overwrite executed before the field initializers), and the three string registries `rvForcedOffValues()` (27 keys), `rvUnsetKeys()` (2), `rvExemptReasons()` (21) — is deleted without replacement of the *mechanism* (P3: no shim). The registries are string literals the compiler cannot bind to the fields they name (verified V8), the forcing had undocumented collateral (`activityStableRestartThreshold` silently forced to `Integer.MAX_VALUE`, V25), and the property it bought — an RV feature cannot silently leak into a control arm — is now **structural**: a feature absent from the resolved plan does not exist in the run, and the effective plan is echoed in `RUN_START` (`run-spec` capability). The `ape.apePureMode` key is retired and aborts resolution with a message referencing this decision.

Invariant dispositions, recorded per the roadmap's dissolution rule:

- **INV-ARCH-06** (every RV flag registered in the registry; registered flags forced off) — **dissolved**. Substitute: run-spec INV-RUN-05 — a sub-parameter of an absent feature does not exist in the plan (accepted only at its neutral value, echoed as `inert`; non-neutral aborts). There is no registry to keep complete because there is no forcing to perform; key ownership totality is compiler-visible `Feature` metadata guarded by a totality test (report Sec. 8).
- **INV-ARCH-01** (with `apePureMode=true`, selection equivalent to upstream APE) — **removed with its subject**. Owner decision D3 (final) descopes the stock-APE mode entirely: the campaign control is the minimal `aperv` preset, and comparison with original APE stays anchored on the frozen phase-2 data. No upstream-parity claim survives in the specs; the telemetry half of the old blindness argument is addressed at stage 4 by universal, neutrality-tested telemetry (R7).
- The two "always-on exceptions" the requirement documented (the `ApePinchOrZoomEvent` crash fix; seeding via `RandomHelper.seed(-s)`) are not exceptions to anything anymore — they are plain current behavior, the latter specified by `exploration`'s "Seeded Agent Decision Reproducibility" and run-spec's "Run Identity and Seed".

The `ape_pure` Python arm, which pushes `ape.apePureMode=true`, aborts loudly against the stage-2 jar. This is deliberate (D3; the arm is not part of any campaign preset) and its removal is stage-5 work.

## MODIFIED Requirements

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

#### Scenario: a single gate overridden

- **WHEN** `ape.properties` sets only `ape.stepTelemetryEnabled=false`
- **THEN** `Config.stepTelemetryEnabled` SHALL be `false` and no `[APE-STEP]` line SHALL be emitted
- **AND** all other gates SHALL retain their `true` defaults

#### Scenario: retired kill-switch key aborts

- **WHEN** `ape.properties` sets `ape.apePureMode=true`
- **THEN** resolution SHALL abort with a retired-key diagnostic before step 1

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
