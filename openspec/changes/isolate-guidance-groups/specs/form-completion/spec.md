# Delta: Form Completion (isolate-guidance-groups)

## Purpose

Addon F of the plan's inventory (plan §2.2) — the deterministic fill of every unfilled `EditText` in a form context and the exclusion of the submit candidate from selection while a field is still empty — had no key: only the boost pass (addon E) read `formCompletionEnabled`, while the fill branch in `ApeAgent.checkInput()` and the submit exclusion in `SataAgent` ran whenever the predicate held (`ApeAgent.java:199`, `StatefulAgent.java:305`, `SataAgent.java:558,1477`). The main spec already said the flag switched both off; the code did not. `isolate-guidance-groups` makes `base` a cell without addons (design D-B1, D-B6), so an addon that cannot be switched off would leak into the control.

After this change the three halves — boost pass, deterministic fill, submit exclusion — are governed together by `Feature.FORM_COMPLETION`, the ADDONS member activated by `ape.formCompletionEnabled` and read from the plan (P-6). The MOP shortcut that the exclusion guarded is now the MOP coin (`mop-guidance`, design D-B10), which honours the exclusion when `FORM_COMPLETION` is mounted.

## Invariants

- **INV-FORM-03** (amended by `isolate-guidance-groups`): Deterministic fill in `ApeAgent.checkInput()` (bypassing the `ape.inputRate` toss) SHALL apply ONLY when `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds for the current state. Otherwise `checkInput()` SHALL retain the `RandomHelper.toss(inputRate)` gate.
- **INV-FORM-05** (amended by `isolate-guidance-groups`, design D-B14.10): The submit candidate SHALL be chosen by the minimal heuristic only — a single enabled `Button` action when exactly one is present, otherwise a clickable action whose visible text matches the fixed submit-word set. The choice SHALL NOT read `mopBoost`, `mopWeight` or any other scorer's field: form completion is an ADDONS member, and reading a MOP scorer's field would couple ADDONS to MOP (rule R1, `scoring-pipeline`). The candidate is therefore the same in a cell with and without `MOP`.
- **INV-FORM-06** (amended by `isolate-guidance-groups`): While `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds (≥1 unfilled `EditText` on the state), no selection path SHALL select the form's submit candidate — not the MOP coin (whose eligibility excludes it), not the EARLY_STAGE unvisited roulette (`findGreedyActionForward`), and not the least-visited pick. Deterministic field-filling takes precedence; the submit candidate becomes eligible only once no unfilled `EditText` remains. This prevents the submit handler — chosen by the heuristic of INV-FORM-05, and in a cell with `MOP` often an action with a MOP weight — from being clicked on an empty form, which would waste the click and the monitored-operation attempt. (A guard on the MOP coin alone is insufficient: EARLY_STAGE consumes unvisited actions before SATA's later rungs, and the `W_SUBMIT` boost raises exactly the roulette weight those paths consult.) This invariant is safe only together with the convergent unfilled predicate: fields register as filled on the next capture via `getText()`, so the exclusion always lifts.
- **INV-FORM-07** (amended by `isolate-guidance-groups`): `inFormCompletionContext()` SHALL be evaluated against the state that reflects the current screen at the moment `checkInput()` runs. It SHALL NOT read a pipeline field that an earlier stage has already cleared. For an agent with a pipeline the chain is `checkInput(checkFuzzing(updateStateInternal(...)))`: the restart is the `Restart` guard stage, which runs before selection (`decision-pipeline` "Restart Guard Stage", design D-B7), so no `checkRestart` sits in the chain; `RandomAgent` and `ReplayAgent` keep `checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))` (design D-B14.12). In both chains `moveForward()` nulls `newState` before `checkInput` executes, and the predicate reads `currentState`, which holds the just-built state at that point.
- **INV-FORM-08**: Every half of form completion — the `FormCompletionPass`, the deterministic fill, and the submit exclusion — SHALL be governed by the single plan feature `Feature.FORM_COMPLETION` (`ape.formCompletionEnabled`, ADDONS), read through `RunContext.current().spec().has(Feature.FORM_COMPLETION)`. No half SHALL read `Config.formCompletionEnabled`, and without the feature `inFormCompletionContext()` SHALL return `false`.

## MODIFIED Requirements

### Requirement: Fill all unfilled EditText fields deterministically in form context

When `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds, the system SHALL fill EVERY unfilled `EditText` field deterministically, rather than probabilistically. In `ApeAgent.checkInput()`, when the selected action targets an `EditText` node with `getInputText() == null` and the form-completion context applies, the system SHALL set the node's input text from `generateInputText(node)` unconditionally — without the `RandomHelper.toss(ape.inputRate)` gate — and the step record SHALL carry `dec.inp:"form"` (`heuristic-input`, `event-sink`). The form-completion boost pass SHALL raise the priority of every unfilled-`EditText` action so the agent walks through and fills them. Filling SHALL reuse the existing `ApeAgent.generateInputText()`, whose generator (typed, heuristic or random) is chosen by the plan (`heuristic-input`); this capability SHALL NOT introduce a new text generator. Fields that already hold text — `getInputText() != null` or non-empty `getText()` — SHALL NOT be re-filled.

When `Feature.FORM_COMPLETION` is not in the plan, `inFormCompletionContext()` returns `false` on every state (INV-FORM-08), so no fill bypasses the toss.

#### Scenario: Both empty fields get text in a form context
- **WHEN** `Feature.FORM_COMPLETION` is in the plan, the form-completion context holds, and the agent selects each of the two unfilled `EditText` actions on successive steps
- **THEN** `ApeAgent.checkInput()` SHALL set each node's input text via `generateInputText(node)` without consulting `ape.inputRate`
- **AND** after both selections both nodes SHALL have `getInputText() != null`
- **AND** both step records SHALL carry `dec.inp:"form"`

#### Scenario: Deterministic fill bypasses the inputRate toss
- **WHEN** `ape.inputRate` is `0.8`, `Feature.FORM_COMPLETION` is in the plan, the form-completion context holds, and an unfilled `EditText` action is selected
- **THEN** the node SHALL be filled with probability 1.0 (the `RandomHelper.toss(inputRate)` gate SHALL NOT be evaluated) (INV-FORM-03)

#### Scenario: Already-filled field is not overwritten
- **WHEN** the form-completion context holds and one `EditText` already has `getInputText() == "test@example.com"`
- **THEN** `checkInput()` SHALL NOT change that field's text
- **AND** only the remaining unfilled `EditText` fields SHALL be filled

#### Scenario: Legacy toss preserved outside form context
- **WHEN** the state has a single `EditText` action with `getInputText() == null` but the form-completion context is `false` for that state — because `Feature.FORM_COMPLETION` is not in the plan (cells `base` and `mop`), or because the predicate does not hold
- **THEN** `checkInput()` SHALL fill the field only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy behavior, INV-FORM-03)

### Requirement: Prioritize a single submit candidate

When `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds, the system SHALL select exactly one submit candidate and raise its priority so it is selected after the fields are filled. The submit candidate SHALL be chosen by a minimal heuristic only (INV-FORM-05, design D-B14.10): a single enabled `Button` action if exactly one is present, otherwise a clickable action whose visible text matches a small fixed set of submit-like words. The choice SHALL NOT read `mopBoost` or any other scorer's field: form completion belongs to ADDONS, and reading a MOP scorer's field would couple ADDONS to MOP (plan §1.3, rule R1), so the candidate is the same with and without `Feature.MOP`. The system SHALL NOT build a form/submit classifier. When no submit candidate can be identified, the pass SHALL boost only the fields and SHALL report `submit=none`. The submit boost SHALL be strictly positive (INV-FORM-02) and SHALL be applied to at most one action (INV-FORM-04).

While the context holds, the submit candidate SHALL be excluded from every selection path (INV-FORM-06):
- the **MOP coin** (`mop-guidance`, design D-B10), which replaced the MOP-target shortcut that used to sit inside `SataAgent`: its eligibility excludes the submit candidate when `Feature.FORM_COMPLETION` is in the plan, so a screen whose only eligible MOP action is the submit candidate makes the coin ineligible, and the step continues to the SATA chain (`dec.src:"sata"`);
- the EARLY_STAGE unvisited roulette;
- the least-visited pick.

When `Feature.FORM_COMPLETION` is not in the plan, there is no context, no submit candidate and no exclusion: the MOP coin and SATA see the submit action like any other.

#### Scenario: MOP-boosted target is the submit candidate
- **WHEN** `Feature.MOP` and `Feature.FORM_COMPLETION` are in the plan (cell `as_mop`), the form-completion context holds, one clickable `ImageView` on the state has `getMopBoost() == 500`, and the state has exactly one enabled `Button` action, with no MOP weight
- **THEN** the `Button` action SHALL be the submit candidate and its priority SHALL be increased by the submit boost
- **AND** the `ImageView` action SHALL NOT be the submit candidate: the choice SHALL NOT read `mopBoost` (INV-FORM-05, design D-B14.10)
- **AND** the same screen in the `as` cell SHALL yield the same submit candidate
- **AND** the header is kept because `openspec archive` matches scenarios by name: the MOP-boosted precedence it names is removed

#### Scenario: Lone Button is the submit candidate without MOP data
- **WHEN** the form-completion context holds and the state has exactly one enabled `Button` action, whatever the actions' MOP fields (in cell `as` no action has any)
- **THEN** that `Button` action SHALL be the submit candidate
- **AND** its priority SHALL be increased by the submit boost

#### Scenario: Submit-word clickable is the candidate when several buttons exist
- **WHEN** the form-completion context holds and the state has two clickables with visible texts `"Cancel"` and `"Encrypt"` where `"Encrypt"` matches the submit-word set
- **THEN** the `"Encrypt"` clickable SHALL be the submit candidate
- **AND** the `"Cancel"` clickable SHALL NOT receive the submit boost

#### Scenario: MOP submit candidate is not clicked before fields are filled
- **WHEN** in cell `as_mop` the form-completion context holds, the submit candidate (the state's lone enabled `Button`, chosen by the heuristic) also has an unretired MOP weight of 500 and no first interaction in this activity, it is the only such action on the screen, and at least one `EditText` on the state is still unfilled
- **THEN** the MOP coin SHALL be ineligible on that step and SHALL NOT select the submit candidate (INV-FORM-06)
- **AND** selection SHALL proceed so an unfilled `EditText` action is filled first
- **AND** once all `EditText` fields are filled, the submit candidate SHALL become eligible for the MOP coin again

#### Scenario: Submit excluded from the EARLY_STAGE roulette while unfilled
- **WHEN** the form-completion context holds, the submit candidate is unvisited (boosted by `W_SUBMIT`), and the EARLY_STAGE forward path collects the state's unvisited actions for `randomPickWithPriority`
- **THEN** the submit candidate SHALL be excluded from that roulette's candidate list (INV-FORM-06)
- **AND** the unfilled `EditText` actions SHALL remain eligible

#### Scenario: Submit excluded from least-visited greedy while unfilled
- **WHEN** the form-completion context holds and the least-visited pick would otherwise return the unvisited submit candidate (visit-count tie broken by X1, the larger scorer sum, which its `form` term raises)
- **THEN** the submit candidate SHALL be excluded from that selection (INV-FORM-06)
- **AND** once no unfilled `EditText` remains, it SHALL be eligible again

#### Scenario: No identifiable submit candidate
- **WHEN** the form-completion context holds, there is no lone `Button`, and no clickable's text matches a submit-word, whatever the actions' MOP fields
- **THEN** the pass SHALL boost only the unfilled `EditText` actions
- **AND** the `FORM boost` log line SHALL report `submit=none`

#### Scenario: no exclusion without the feature
- **WHEN** in cell `mop` (no ADDONS group, so no `Feature.FORM_COMPLETION`) a screen holds two empty `EditText` fields and a button whose click has an unretired MOP weight of 500 and no first interaction in this activity
- **THEN** the MOP coin SHALL be eligible and, with `uM < p_M`, SHALL select the button

### Requirement: Form-completion boost pass placement and provenance

Form completion is the plan feature `Feature.FORM_COMPLETION`, an ADDONS member activated by `ape.formCompletionEnabled` (rule `TRUE`, jar default `true`, requires `Feature.ADDONS`), read through the plan and never through `Config` (INV-FORM-08); its read sites (`agent/ApeAgent.java:199`, `agent/StatefulAgent.java:305`, `agent/SataAgent.java:558,1477`, formerly `Config.formCompletionEnabled`) are listed in `guidance-groups` "Addon Read Sites Read the Plan". It governs all three halves together. When the feature is in the plan, the boost SHALL be applied by the `FormCompletionPass` in the scoring pipeline — the last pass, running after the coverage pass; the deterministic-fill branch in `ApeAgent.checkInput()` SHALL apply as specified by "Fill all unfilled EditText fields deterministically in form context"; and the submit exclusion SHALL apply as specified by "Prioritize a single submit candidate". When the feature is not in the plan — `ape.addons` false (cells `base`, `mop`), or `ape.formCompletionEnabled=false` — `FormCompletionPass` SHALL be absent from the pipeline (a strict no-op: no priority change, no `formBoost`, no `FORM boost` log line), `inFormCompletionContext()` SHALL return `false`, so the deterministic-fill branch SHALL NOT apply and `ApeAgent.checkInput()` SHALL retain the legacy `RandomHelper.toss(ape.inputRate)` per-field gate for all states (INV-FORM-03 legacy path), and no selection path SHALL exclude a submit candidate. This reproduces upstream APE's input behaviour by construction, because every half reads the same feature.

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
