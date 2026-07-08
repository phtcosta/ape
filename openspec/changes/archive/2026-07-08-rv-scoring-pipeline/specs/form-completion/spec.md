# form-completion — delta: rv-scoring-pipeline

## Purpose

Give the form-completion behavior its first off switch. Before this change the form-completion boost pass and its deterministic-fill branch had no flag — they fired on every state with an unfilled `EditText`, which is the single strongest deviation from upstream APE selection and made an `ape_pure` arm inexpressible. This delta gates both halves behind `Config.formCompletionEnabled` (declared by the `scoring-pipeline` capability; default `true`), and re-homes the boost pass as `FormCompletionPass` in the scoring pipeline. Default `true` preserves current behavior. `INV-FORM-01` and `INV-FORM-03` describe the default (flag-on) behavior.

## MODIFIED Requirements

### Requirement: Form-completion boost pass placement and provenance

The form-completion boost is gated by `Config.formCompletionEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `formCompletionEnabled` is `true` (default), the boost SHALL be applied by the `FormCompletionPass` in the scoring pipeline — the last pass, running after the coverage pass — reproducing the pre-refactor inline behavior exactly; and the deterministic-fill branch in `ApeAgent.checkInput()` SHALL apply as specified by the "Fill all unfilled EditText fields deterministically in form context" requirement. When `formCompletionEnabled` is `false` (the `ape_pure` arm), `FormCompletionPass` SHALL be absent from the pipeline (a strict no-op: no priority change, no `formBoost`, no `FORM boost` log line) AND the deterministic-fill branch SHALL NOT apply — `ApeAgent.checkInput()` SHALL retain the legacy `RandomHelper.toss(ape.inputRate)` per-field gate for all states (INV-FORM-03 legacy path), reproducing upstream APE.

When enabled, the pass SHALL set `ModelAction.formBoost` on each boosted action via an accessor mirroring `setCoverageBoost`/`setMopBoost`, so that per-action telemetry can report the form boost alongside the MOP, WTG, coverage, and menu boosts. The pass SHALL emit at most one log line per state, and only when the form-completion context holds.

The per-step `[APE-STEP]` decision-attribution line (`StatefulAgent.resolveNewAction`, `StatefulAgent.java:1266-1272`) SHALL include a `form=<formBoost>` field alongside the existing `mop=`/`wtg=`/`coverage=`/`menu=` fields, reporting `ModelAction.getFormBoost()` for the selected action, so the form boost has the same per-step visibility as the other passes. (Emission of the `[APE-STEP]` line itself is gated by `stepTelemetryEnabled`, per the action-selection spec.)

#### Scenario: Pass runs after coverage and records provenance (flag on)
- **WHEN** `Config.formCompletionEnabled` is `true`, the form-completion context holds, and the pass boosts an unfilled `EditText` action by the field boost
- **THEN** that action's `getFormBoost()` SHALL equal the applied field boost
- **AND** the action's priority SHALL reflect the base SATA priority plus any MOP/WTG/coverage boosts plus the form boost

#### Scenario: Single log line per form state (flag on)
- **WHEN** `Config.formCompletionEnabled` is `true`, the form-completion context holds for a state with three unfilled `EditText` fields and one submit candidate with id `btn_encrypt`
- **THEN** exactly one line SHALL be emitted: `[APE-RV] FORM boost: state=<activity>#<key>, fields=3, submit=btn_encrypt`

#### Scenario: Form boost reported on the per-step line
- **WHEN** the selected action carries a form boost of `W_FILL` set by the pass and `stepTelemetryEnabled` is `true`
- **THEN** the `[APE-STEP]` line for that step SHALL include `form=<W_FILL>` alongside the `mop=`/`wtg=`/`coverage=`/`menu=` fields

#### Scenario: No log line when context absent
- **WHEN** the form-completion context is `false` for the state
- **THEN** no `FORM boost` line SHALL be emitted (INV-FORM-01)

#### Scenario: Pass and deterministic fill both disabled when the flag is off
- **WHEN** `Config.formCompletionEnabled` is `false` and a state carries two unfilled `EditText` fields and a submit `Button`
- **THEN** `FormCompletionPass` SHALL be absent from the pipeline — no priority change, no `formBoost`, and no `FORM boost` log line for that state
- **AND** `ApeAgent.checkInput()` SHALL fill a selected unfilled `EditText` only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy per-field gate, upstream behavior)
