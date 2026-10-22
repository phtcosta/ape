# form-completion — delta: align-specs-with-code

## Invariants

- **INV-FORM-01**: The form-completion boost pass SHALL be a no-op — no priority change, no `formBoost` set, and no `FORM boost` log line — when the state has zero unfilled `EditText` actions (no valid, target-requiring action resolved at the current timestamp whose node `isEditText()`, has `getInputText() == null`, and has `getText()` null or empty; `FormCompletion.isUnfilledEditText`).

## MODIFIED Requirements

### Requirement: Form-completion boost pass placement and provenance

Form completion is the plan feature `Feature.FORM_COMPLETION`, an ADDONS member activated by `ape.formCompletionEnabled` (rule `TRUE`, jar default `true`, requires `Feature.ADDONS`), read through the plan and never through `Config` (INV-FORM-08). Its read sites are `StatefulAgent.inFormCompletionContext()` (the deterministic fill, overriding `ApeAgent.inFormCompletionContext()`, which returns `false`), `SataAgent`'s submit exclusion (set at composition from `spec.has(Feature.FORM_COMPLETION)`), the MOP coin's eligibility (`DecisionPipeline` passes `spec.has(Feature.FORM_COMPLETION)` to `MopCoinStage`), and the pass (`ScoringParams.formCompletionEnabled`); they are listed in `guidance-groups` "Addon Read Sites Read the Plan". It governs all three halves together. When the feature is in the plan, the boost SHALL be applied by the `FormCompletionPass` in the scoring pipeline — the last pass, running after the coverage pass; the deterministic-fill branch in `ApeAgent.checkInput()` SHALL apply as specified by "Fill all unfilled EditText fields deterministically in form context"; and the submit exclusion SHALL apply as specified by "Prioritize a single submit candidate". When the feature is not in the plan — `ape.addons` false (cells `base`, `mop`), or `ape.formCompletionEnabled=false` — `FormCompletionPass` SHALL be absent from the pipeline (it is not enabled, so the pipeline does not run it: a strict no-op, no priority change, no `formBoost`, no `FORM boost` log line), `inFormCompletionContext()` SHALL return `false`, so the deterministic-fill branch SHALL NOT apply and `ApeAgent.checkInput()` SHALL retain the legacy `RandomHelper.toss(ape.inputRate)` per-field gate for all states (INV-FORM-03 legacy path), and no selection path SHALL exclude a submit candidate. This reproduces upstream APE's input behaviour by construction, because every half reads the same feature.

When enabled, the pass SHALL set `ModelAction.formBoost` on each boosted action via an accessor mirroring `setCoverageBoost`/`setMopBoost`, so that per-action telemetry can report the form boost alongside the other scorer fields. The pass SHALL emit at most one log line per state, and only when the form-completion context holds.

The step's `StepRecord` decision section (`event-sink` capability) SHALL include a `form` scorer field alongside `mop`/`mopf`/`wtg`/`navf`/`cov`/`menu`, reporting `ModelAction.getFormBoost()` for the selected action, so the form boost has the same per-step visibility as the other passes. Per the defaults-omitted rule (`event-sink` INV-SNK-05) the field is present only when non-zero; absence means `0`. On the roulette and least-visited picks, `dec.cfc.form` records whether the pick would have differed without it. Recording is unconditional — no plan key gates telemetry — so there is no configuration under which a form boost is applied but not recorded.

#### Scenario: Pass runs after coverage and records provenance (flag on)
- **WHEN** `Feature.FORM_COMPLETION` is in the plan, the form-completion context holds, and the pass boosts an unfilled `EditText` action by the field boost
- **THEN** that action's `getFormBoost()` SHALL equal the applied field boost
- **AND** the action's priority SHALL reflect the base SATA priority plus the boosts of the mounted scorers plus the form boost

#### Scenario: Single log line per form state (flag on)
- **WHEN** `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds for a state with three unfilled `EditText` fields and one submit candidate with id `btn_encrypt`
- **THEN** exactly one line SHALL be emitted: `[APE-RV] FORM boost: state=<activity>#<key>, fields=3, submit=btn_encrypt`

#### Scenario: Form boost reported on the per-step line
- **WHEN** the selected action carries a form boost of `W_FILL` set by the pass
- **THEN** the step's `StepRecord` SHALL carry `dec.form:<W_FILL>` alongside the scorer fields that are non-zero

#### Scenario: No log line when context absent
- **WHEN** the form-completion context is `false` for the state
- **THEN** no `FORM boost` line SHALL be emitted (INV-FORM-01)

#### Scenario: Pass and deterministic fill both disabled when the flag is off
- **WHEN** `Feature.FORM_COMPLETION` is not in the plan (cell `base`, or `ape.addons=true` with `ape.formCompletionEnabled=false`) and a state carries two unfilled `EditText` fields and a submit `Button`
- **THEN** `FormCompletionPass` SHALL be absent from the pipeline — no priority change, no `formBoost`, and no `FORM boost` log line for that state
- **AND** `ApeAgent.checkInput()` SHALL fill a selected unfilled `EditText` only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy per-field gate, upstream behavior)
- **AND** the submit `Button` SHALL NOT be excluded from any selection path
- **AND** no step record of the run SHALL carry `dec.inp:"form"`
