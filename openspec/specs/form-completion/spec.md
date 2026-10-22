## Purpose

The `form-completion` capability makes APE-RV exercise form-submit code paths that its base exploration strategy almost never reaches. Many monitored operations of interest — in particular crypto/JCA (JavaMOP) targets — live behind submit handlers: methods that run only after a form's text fields hold input and a submit control is clicked. APE-RV has no notion of a form as a unit. There is no `SET_TEXT` action type; text is a side-effect of `MODEL_CLICK` on an `EditText`, and `ApeAgent.checkInput()` fills only the single acted-on field, and only with probability `ape.inputRate` (default 0.8, observed effective fill ≈42% per field). For a k-field form the probability that every field is filled before the submit click is roughly `inputRate^k`, which collapses to near zero. Consequently the submit handler — the monitored operation — rarely runs even when the screen carrying the form is on display.

This capability adds the missing form sequencing with a deliberately small mechanism (P1): no `SET_TEXT` action type, no form classifier, no field-dependency model, no new text generator, no machine learning. It reuses two existing facilities — the per-state priority-boost pass in `StatefulAgent.adjustActionsByGUITree()` (the same extension point used by the MOP, WTG, and coverage passes) and the already type-aware `ApeAgent.generateInputText()` (which draws from `TypedInputGenerator`/`InputValueGenerator`/`StringCache`).

The capability has three responsibilities. (i) Detection: recognize when the current state carries at least one unfilled `EditText` — a resolved, target-requiring action whose resolved node `isEditText()` and holds no text: `getInputText() == null` **and** `getText()` null or empty. The two-part test is what makes the predicate converge across captures: `inputText` is a per-capture annotation (set only when APE itself types, never re-seeded on the next `GUITree` capture), while `getText()` carries the field's actual content as read from the accessibility node — so a field typed on step N reads as filled on step N+1 through `getText()`. `isEditText()` SHALL recognize the canonical widget-class set of the static helper `GUITreeBuilder.isEditText` (`EditText`, `ExtractEditText`, `AutoCompleteTextView`, `MultiAutoCompleteTextView`), not the single exact class `android.widget.EditText`. (ii) Filling: when that form-completion context holds, deterministically fill ALL unfilled `EditText` fields using `generateInputText()`, bypassing the `ape.inputRate` toss that otherwise gates per-field input. (iii) Submission: raise the priority of exactly one submit candidate so it is selected after the fields are filled. The submit candidate is chosen by a minimal heuristic only — a lone enabled `Button`, else a clickable whose visible text matches a small fixed set of submit-like words — and never reads `mopBoost`, `mopWeight` or any other scorer's field (design D-B14.10, rule R1 of `scoring-pipeline`), so the candidate is the same in a cell with and without MOP.

The behavior is realized as a new "form-completion boost" pass that runs after the coverage pass in `adjustActionsByGUITree()`, mirroring the coverage pass structure, plus a deterministic-fill branch in `ApeAgent.checkInput()` keyed on the same form-completion-context predicate. Both halves read the same predicate so the boost and the fill stay consistent, and the submit exclusion on the selection paths (including the MOP coin's eligibility) reads it too. All three halves — boost pass, deterministic fill, submit exclusion — are governed together by the plan feature `Feature.FORM_COMPLETION`, the ADDONS member activated by `ape.formCompletionEnabled` (design D-B1, D-B6): without it (cells `base`, `mop`, or the key false) none of them applies and input behaves as upstream APE. The pass is a strict no-op on states with no unfilled `EditText`, so it never perturbs exploration of non-form screens. An implementer or LLM reading this spec should be able to add the pass, the predicate helper, and the `checkInput()` branch without further context.

## Data Contracts

### Input
- `state: State` — the current abstract state (source: `StatefulAgent.newState`). Its `getActions()` provide candidate `ModelAction`s.
- `tree: GUITree` — the current screen tree (source: `StatefulAgent.newGUITree`), used to resolve nodes.
- `timestamp: int` — the agent's current resolution timestamp (source: `StatefulAgent.timestamp`).
- `action: ModelAction` — a candidate action; `action.getResolvedNode()` yields the `GUITreeNode` when resolved at `timestamp`.
- `node.getInputText(): String` — text APE typed into the node during the current capture; `null` when APE has not typed this capture.
- `node.getText(): String` — the node's visible text as captured from the accessibility tree (`GUITreeBuilder.fillNode`); carries previously typed content across captures. A node is "unfilled" only when `getInputText() == null` AND `getText()` is null or empty.

### Output
- `action.getPriority(): int` — increased (never decreased) for unfilled-`EditText` actions and for the single submit candidate.
- `action.getFormBoost(): int` — per-action form-completion boost provenance, mirroring `getCoverageBoost()`/`getMopBoost()`.
- `node.getInputText(): String` — set to a non-null value (via `generateInputText`) for every selected unfilled `EditText` in the form-completion context (destination: `MonkeySourceApe.doInput`).

### Side-Effects
- **[Agent]**: One log line per state that has a form-completion context: `[APE-RV] FORM boost: state=<activity>#<key>, fields=<n>, submit=<id|none>`.
- **[Model]**: `ModelAction.priority` and `ModelAction.formBoost` mutated for boosted actions during the pass.

### Error
- None raised. Null or unresolved nodes are treated as not-an-unfilled-`EditText`. A state with unfilled fields but no identifiable submit candidate proceeds with fields-only boosting (`submit=none`).

## Invariants

- **INV-FORM-01**: The form-completion boost pass SHALL be a no-op — no priority change, no `formBoost` set, and no `FORM boost` log line — when the state has zero unfilled `EditText` actions (no valid, target-requiring action resolved at the current timestamp whose node `isEditText()`, has `getInputText() == null`, and has `getText()` null or empty; `FormCompletion.isUnfilledEditText`).
- **INV-FORM-02**: The form-completion boost pass SHALL only increase `ModelAction.priority`; it SHALL NEVER reduce an action's priority. Every applied form boost SHALL be strictly positive.
- **INV-FORM-03**: Deterministic fill in `ApeAgent.checkInput()` (bypassing the `ape.inputRate` toss) SHALL apply ONLY when `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds for the current state. Otherwise `checkInput()` SHALL retain the `RandomHelper.toss(inputRate)` gate.
- **INV-FORM-04**: At most one submit candidate SHALL be boosted per state. `selectSubmitCandidate(state)` SHALL return at most one `ModelAction` (or none).
- **INV-FORM-05**: The submit candidate SHALL be chosen by the minimal heuristic only — a single enabled `Button` action when exactly one is present, otherwise a clickable action whose visible text matches the fixed submit-word set. The choice SHALL NOT read `mopBoost`, `mopWeight` or any other scorer's field: form completion is an ADDONS member, and reading a MOP scorer's field would couple ADDONS to MOP (rule R1, `scoring-pipeline`). The candidate is therefore the same in a cell with and without `MOP`.
- **INV-FORM-06**: While `Feature.FORM_COMPLETION` is in the plan and the form-completion context holds (≥1 unfilled `EditText` on the state), no selection path SHALL select the form's submit candidate — not the MOP coin (whose eligibility excludes it), not the EARLY_STAGE unvisited roulette (`findGreedyActionForward`), and not the least-visited pick. Deterministic field-filling takes precedence; the submit candidate becomes eligible only once no unfilled `EditText` remains. This prevents the submit handler — chosen by the heuristic of INV-FORM-05, and in a cell with `MOP` often an action with a MOP weight — from being clicked on an empty form, which would waste the click and the monitored-operation attempt. (A guard on the MOP coin alone is insufficient: EARLY_STAGE consumes unvisited actions before SATA's later rungs, and the `W_SUBMIT` boost raises exactly the roulette weight those paths consult.) This invariant is safe only together with the convergent unfilled predicate: fields register as filled on the next capture via `getText()`, so the exclusion always lifts.
- **INV-FORM-07**: `inFormCompletionContext()` SHALL be evaluated against the state that reflects the current screen at the moment `checkInput()` runs. It SHALL NOT read a pipeline field that an earlier stage has already cleared. For an agent with a pipeline the chain is `checkInput(checkFuzzing(updateStateInternal(...)))`: the restart is the `Restart` guard stage, which runs before selection (`decision-pipeline` "Restart Guard Stage", design D-B7), so no `checkRestart` sits in the chain; `RandomAgent` and `ReplayAgent` keep `checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))` (design D-B14.12). In both chains `moveForward()` nulls `newState` before `checkInput` executes, and the predicate reads `currentState`, which holds the just-built state at that point.
- **INV-FORM-08**: Every half of form completion — the `FormCompletionPass`, the deterministic fill, and the submit exclusion — SHALL be governed by the single plan feature `Feature.FORM_COMPLETION` (`ape.formCompletionEnabled`, ADDONS), read through `RunContext.current().spec().has(Feature.FORM_COMPLETION)`. No half SHALL read `Config.formCompletionEnabled`, and without the feature `inFormCompletionContext()` SHALL return `false`.

## Requirements

### Requirement: Detect a state with unfilled EditText fields

The system SHALL determine whether the current state carries at least one unfilled `EditText` field. An action counts as an unfilled `EditText` when it requires a target (`requireTarget()`), is valid (`isValid()`), is resolved at the current timestamp (`isResolvedAt(timestamp)`), its resolved node `isEditText()`, and the node holds no text — `getInputText() == null` AND `getText()` null or empty. `isEditText()` SHALL recognize the same widget-class set as the canonical static helper (`GUITreeBuilder.isEditText`: `EditText`, `ExtractEditText`, `AutoCompleteTextView`, `MultiAutoCompleteTextView`). This predicate defines the "form-completion context" and SHALL be computed from `State.getActions()` without mutating any action. The same predicate SHALL be read by both the form-completion boost pass and `ApeAgent.checkInput()`, and it SHALL be evaluated against the current screen's state at `checkInput` time (INV-FORM-07).

#### Scenario: State with two empty text fields is a form context
- **WHEN** the current state has actions resolving to two `EditText` nodes, both with `getInputText() == null`, and a `Button` action
- **THEN** the form-completion context SHALL be `true`
- **AND** the number of unfilled `EditText` fields SHALL be reported as `2` in the `[APE-RV] FORM boost` line

#### Scenario: State with all text fields already filled is not a form context
- **WHEN** the state's only two `EditText` actions both have `getInputText() != null`
- **THEN** the form-completion context SHALL be `false`
- **AND** the form-completion boost pass SHALL make no change to any action priority (INV-FORM-01)

#### Scenario: State with no EditText is not a form context
- **WHEN** the state has only `Button` and `TextView` actions and no `EditText` action
- **THEN** the form-completion context SHALL be `false`
- **AND** no `FORM boost` log line SHALL be emitted (INV-FORM-01)

#### Scenario: Unresolved action does not count as unfilled EditText
- **WHEN** an `EditText` action is present but `isResolvedAt(timestamp)` returns `false`
- **THEN** that action SHALL NOT contribute to the form-completion context
- **AND** the predicate SHALL evaluate it as not-an-unfilled-`EditText`

#### Scenario: Typed field registers as filled on the next capture
- **WHEN** APE types into an `EditText` on step N and the screen is re-captured on step N+1 (fresh `GUITreeNode`s, `getInputText() == null`, `getText() == "test@example.com"`)
- **THEN** on step N+1 that field SHALL count as filled
- **AND** a form whose every field has been typed SHALL leave the form-completion context (the predicate converges)

#### Scenario: EditText subclass counts as a field
- **WHEN** the state has one unfilled node of class `android.widget.AutoCompleteTextView`
- **THEN** the form-completion context SHALL be `true` (canonical `isEditText` set, not exact-class match)

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
