# form-completion Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the form-completion pass and its placement are unchanged; what changes is where its provenance is recorded. The `form=` field moves from the `[APE-STEP]` `key=value` line to the `form` member of the step's `StepRecord` decision section, and the `stepTelemetryEnabled` gate that conditioned the emission is deleted with the key (recording is always-on and identical for all arms). The flag-off branch is additionally re-grounded: it was described as "the `ape_pure` arm", an arm retired by `rearch-05-thin-python-arms` — `formCompletionEnabled` is the activation key of its `Feature`, and with the feature absent the pass is not constructed.

## MODIFIED Requirements

### Requirement: Form-completion boost pass placement and provenance

The form-completion boost is gated by `Config.formCompletionEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `formCompletionEnabled` is `true` (default), the boost SHALL be applied by the `FormCompletionPass` in the scoring pipeline — the last pass, running after the coverage pass — reproducing the pre-refactor inline behavior exactly; and the deterministic-fill branch in `ApeAgent.checkInput()` SHALL apply as specified by the "Fill all unfilled EditText fields deterministically in form context" requirement. When `formCompletionEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), `FormCompletionPass` SHALL be absent from the pipeline (a strict no-op: no priority change, no `formBoost`, no `FORM boost` log line) AND the deterministic-fill branch SHALL NOT apply — `ApeAgent.checkInput()` SHALL retain the legacy `RandomHelper.toss(ape.inputRate)` per-field gate for all states (INV-FORM-03 legacy path), reproducing upstream APE.

When enabled, the pass SHALL set `ModelAction.formBoost` on each boosted action via an accessor mirroring `setCoverageBoost`/`setMopBoost`, so that per-action telemetry can report the form boost alongside the MOP, WTG, coverage, and menu boosts. The pass SHALL emit at most one log line per state, and only when the form-completion context holds.

The step's `StepRecord` decision section (`event-sink` capability) SHALL include a `form` boost field alongside `mop`/`mopf`/`wtg`/`coverage`/`menu`, reporting `ModelAction.getFormBoost()` for the selected action, so the form boost has the same per-step visibility as the other passes. Per the defaults-omitted rule (`event-sink` INV-SNK-05) the field is present only when non-zero; absence means `0`. Recording is unconditional — the `stepTelemetryEnabled` gate is deleted by this change and the key aborts plan validation as unknown, so there is no configuration under which a form boost is applied but not recorded.

#### Scenario: Pass runs after coverage and records provenance (flag on)
- **WHEN** `Config.formCompletionEnabled` is `true`, the form-completion context holds, and the pass boosts an unfilled `EditText` action by the field boost
- **THEN** that action's `getFormBoost()` SHALL equal the applied field boost
- **AND** the action's priority SHALL reflect the base SATA priority plus any MOP/WTG/coverage boosts plus the form boost

#### Scenario: Single log line per form state (flag on)
- **WHEN** `Config.formCompletionEnabled` is `true`, the form-completion context holds for a state with three unfilled `EditText` fields and one submit candidate with id `btn_encrypt`
- **THEN** exactly one line SHALL be emitted: `[APE-RV] FORM boost: state=<activity>#<key>, fields=3, submit=btn_encrypt`

#### Scenario: Form boost reported on the per-step line
- **WHEN** the selected action carries a form boost of `W_FILL` set by the pass and `stepTelemetryEnabled` is `true`
- **THEN** the step's `StepRecord` SHALL carry `dec.form:<W_FILL>` alongside the `dec.mop`/`dec.wtg`/`dec.cov`/`dec.menu` fields that are non-zero

#### Scenario: No log line when context absent
- **WHEN** the form-completion context is `false` for the state
- **THEN** no `FORM boost` line SHALL be emitted (INV-FORM-01)

#### Scenario: Pass and deterministic fill both disabled when the flag is off
- **WHEN** `Config.formCompletionEnabled` is `false` and a state carries two unfilled `EditText` fields and a submit `Button`
- **THEN** `FormCompletionPass` SHALL be absent from the pipeline — no priority change, no `formBoost`, and no `FORM boost` log line for that state
- **AND** `ApeAgent.checkInput()` SHALL fill a selected unfilled `EditText` only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy per-field gate, upstream behavior)
