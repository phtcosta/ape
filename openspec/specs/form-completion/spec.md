## Purpose

The `form-completion` capability makes APE-RV exercise form-submit code paths that its base exploration strategy almost never reaches. Many monitored operations of interest — in particular crypto/JCA (JavaMOP) targets — live behind submit handlers: methods that run only after a form's text fields hold input and a submit control is clicked. APE-RV has no notion of a form as a unit. There is no `SET_TEXT` action type; text is a side-effect of `MODEL_CLICK` on an `EditText`, and `ApeAgent.checkInput()` fills only the single acted-on field, and only with probability `ape.inputRate` (default 0.8, observed effective fill ≈42% per field). For a k-field form the probability that every field is filled before the submit click is roughly `inputRate^k`, which collapses to near zero. Consequently the submit handler — the monitored operation — rarely runs even when the screen carrying the form is on display.

This capability adds the missing form sequencing with a deliberately small mechanism (P1): no `SET_TEXT` action type, no form classifier, no field-dependency model, no new text generator, no machine learning. It reuses two existing facilities — the per-state priority-boost pass in `StatefulAgent.adjustActionsByGUITree()` (the same extension point used by the MOP, WTG, and coverage passes) and the already type-aware `ApeAgent.generateInputText()` (which draws from `TypedInputGenerator`/`InputValueGenerator`/`StringCache`).

The capability has three responsibilities. (i) Detection: recognize when the current state carries at least one unfilled `EditText` — a resolved, target-requiring action whose resolved node `isEditText()` and holds no text: `getInputText() == null` **and** `getText()` null or empty. The two-part test is what makes the predicate converge across captures: `inputText` is a per-capture annotation (set only when APE itself types, never re-seeded on the next `GUITree` capture), while `getText()` carries the field's actual content as read from the accessibility node — so a field typed on step N reads as filled on step N+1 through `getText()`. `isEditText()` SHALL recognize the canonical widget-class set of the static helper `GUITreeBuilder.isEditText` (`EditText`, `ExtractEditText`, `AutoCompleteTextView`, `MultiAutoCompleteTextView`), not the single exact class `android.widget.EditText`. (ii) Filling: when that form-completion context holds, deterministically fill ALL unfilled `EditText` fields using `generateInputText()`, bypassing the `ape.inputRate` toss that otherwise gates per-field input. (iii) Submission: raise the priority of exactly one submit candidate so it is selected after the fields are filled. The submit candidate is the MOP-boosted target action on the state when one exists (composing with the `mop-discriminative-boost` change — the action the run already steers toward is the submit handler), otherwise a minimal heuristic: a lone enabled `Button`, else a clickable whose visible text matches a small fixed set of submit-like words.

The behavior is realized as a new "form-completion boost" pass that runs after the coverage pass in `adjustActionsByGUITree()`, mirroring the coverage pass structure, plus a deterministic-fill branch in `ApeAgent.checkInput()` keyed on the same form-completion-context predicate. Both halves read the same predicate so the boost and the fill stay consistent. The pass is a strict no-op on states with no unfilled `EditText`, so it never perturbs exploration of non-form screens. An implementer or LLM reading this spec should be able to add the pass, the predicate helper, and the `checkInput()` branch without further context.

## Data Contracts

### Input
- `state: State` — the current abstract state (source: `StatefulAgent.newState`). Its `getActions()` provide candidate `ModelAction`s.
- `tree: GUITree` — the current screen tree (source: `StatefulAgent.newGUITree`), used to resolve nodes.
- `timestamp: int` — the agent's current resolution timestamp (source: `StatefulAgent.timestamp`).
- `action: ModelAction` — a candidate action; `action.getResolvedNode()` yields the `GUITreeNode` when resolved at `timestamp`.
- `node.getInputText(): String` — text APE typed into the node during the current capture; `null` when APE has not typed this capture.
- `node.getText(): String` — the node's visible text as captured from the accessibility tree (`GUITreeBuilder.fillNode`); carries previously typed content across captures. A node is "unfilled" only when `getInputText() == null` AND `getText()` is null or empty.
- `action.getMopBoost(): int` — MOP boost set by the preceding MOP pass (consumed by submit-candidate selection).

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

- **INV-FORM-01**: The form-completion boost pass SHALL be a no-op — no priority change, no `formBoost` set, and no `FORM boost` log line — when the state has zero unfilled `EditText` actions (no resolved target action whose node `isEditText()` and `getInputText() == null`).
- **INV-FORM-02**: The form-completion boost pass SHALL only increase `ModelAction.priority`; it SHALL NEVER reduce an action's priority. Every applied form boost SHALL be strictly positive.
- **INV-FORM-03**: Deterministic fill in `ApeAgent.checkInput()` (bypassing the `ape.inputRate` toss) SHALL apply ONLY when the form-completion context holds for the current state. Outside that context `checkInput()` SHALL retain the `RandomHelper.toss(inputRate)` gate.
- **INV-FORM-04**: At most one submit candidate SHALL be boosted per state. `selectSubmitCandidate(state)` SHALL return at most one `ModelAction` (or none).
- **INV-FORM-05**: When the state carries an MOP-boosted target action (`getMopBoost() > 0`), the submit candidate SHALL be that action (the highest-`mopBoost` one); the text-word heuristic SHALL be used only when no MOP-boosted target exists on the state.
- **INV-FORM-06**: While the form-completion context holds (≥1 unfilled `EditText` on the state), no priority-consuming selection path SHALL select the form's submit candidate — not the MOP-target greedy short-circuit, not the EARLY_STAGE unvisited roulette (`findGreedyActionForward`), and not `greedyPickLeastVisited`. Deterministic field-filling takes precedence; the submit candidate becomes eligible only once no unfilled `EditText` remains. This prevents the submit handler — typically the `mopBoost>0` action (INV-FORM-05) — from being clicked on an empty form, which would waste the click and the monitored-operation attempt. (A guard on the short-circuit alone is insufficient: EARLY_STAGE consumes unvisited actions before the short-circuit ever runs, and the `W_SUBMIT` boost raises exactly the roulette weight and tie-break priority those paths consult.) This invariant is safe only together with the convergent unfilled predicate: fields register as filled on the next capture via `getText()`, so the exclusion always lifts.
- **INV-FORM-07**: `inFormCompletionContext()` SHALL be evaluated against the state that reflects the current screen at the moment `checkInput()` runs. It SHALL NOT read a pipeline field that an earlier stage has already cleared (in the `checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))` chain, `moveForward()` nulls `newState` before `checkInput` executes — the predicate reads `currentState`, which holds the just-built state at that point).
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

When the form-completion context holds, the system SHALL fill EVERY unfilled `EditText` field deterministically, rather than probabilistically. In `ApeAgent.checkInput()`, when the selected action targets an `EditText` node with `getInputText() == null` and the form-completion context applies, the system SHALL set the node's input text from `generateInputText(node)` unconditionally — without the `RandomHelper.toss(ape.inputRate)` gate. The form-completion boost pass SHALL raise the priority of every unfilled-`EditText` action so the agent walks through and fills them. Filling SHALL reuse the existing type-aware `ApeAgent.generateInputText()`; this capability SHALL NOT introduce a new text generator. Fields that already hold text — `getInputText() != null` or non-empty `getText()` — SHALL NOT be re-filled.

#### Scenario: Both empty fields get text in a form context
- **WHEN** the form-completion context holds and the agent selects each of the two unfilled `EditText` actions on successive steps
- **THEN** `ApeAgent.checkInput()` SHALL set each node's input text via `generateInputText(node)` without consulting `ape.inputRate`
- **AND** after both selections both nodes SHALL have `getInputText() != null`

#### Scenario: Deterministic fill bypasses the inputRate toss
- **WHEN** `ape.inputRate` is `0.8`, the form-completion context holds, and an unfilled `EditText` action is selected
- **THEN** the node SHALL be filled with probability 1.0 (the `RandomHelper.toss(inputRate)` gate SHALL NOT be evaluated) (INV-FORM-03)

#### Scenario: Already-filled field is not overwritten
- **WHEN** the form-completion context holds and one `EditText` already has `getInputText() == "test@example.com"`
- **THEN** `checkInput()` SHALL NOT change that field's text
- **AND** only the remaining unfilled `EditText` fields SHALL be filled

#### Scenario: Legacy toss preserved outside form context
- **WHEN** the state has a single `EditText` action with `getInputText() == null` but the form-completion context is `false` for that state
- **THEN** `checkInput()` SHALL fill the field only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy behavior, INV-FORM-03)

### Requirement: Prioritize a single submit candidate

When the form-completion context holds, the system SHALL select exactly one submit candidate and raise its priority so it is selected after the fields are filled. The submit candidate SHALL be the MOP-boosted target action on the state — the action with the highest `getMopBoost() > 0` — when one exists (INV-FORM-05), composing with the `mop-discriminative-boost` change. When no MOP-boosted target exists on the state, the submit candidate SHALL be chosen by a minimal heuristic: a single enabled `Button` action if exactly one is present, otherwise a clickable action whose visible text matches a small fixed set of submit-like words. The system SHALL NOT build a form/submit classifier. When no submit candidate can be identified, the pass SHALL boost only the fields and SHALL report `submit=none`. The submit boost SHALL be strictly positive (INV-FORM-02) and SHALL be applied to at most one action (INV-FORM-04).

#### Scenario: MOP-boosted target is the submit candidate
- **WHEN** the form-completion context holds and one action on the state has `getMopBoost() == 500`
- **THEN** that action SHALL be the submit candidate
- **AND** its priority SHALL be increased by the submit boost
- **AND** the heuristic submit-word matching SHALL NOT be consulted (INV-FORM-05)

#### Scenario: Lone Button is the submit candidate without MOP data
- **WHEN** the form-completion context holds, no action has `getMopBoost() > 0`, and the state has exactly one enabled `Button` action
- **THEN** that `Button` action SHALL be the submit candidate
- **AND** its priority SHALL be increased by the submit boost

#### Scenario: Submit-word clickable is the candidate when several buttons exist
- **WHEN** the form-completion context holds, no action has `getMopBoost() > 0`, and the state has two clickables with visible texts `"Cancel"` and `"Encrypt"` where `"Encrypt"` matches the submit-word set
- **THEN** the `"Encrypt"` clickable SHALL be the submit candidate
- **AND** the `"Cancel"` clickable SHALL NOT receive the submit boost

#### Scenario: MOP submit candidate is not clicked before fields are filled
- **WHEN** the form-completion context holds, the submit candidate has `getMopBoost() == 500` and is unvisited, and at least one `EditText` on the state is still unfilled
- **THEN** the MOP-target greedy short-circuit SHALL NOT select the submit candidate on that step (INV-FORM-06)
- **AND** selection SHALL proceed so an unfilled `EditText` action is filled first
- **AND** once all `EditText` fields are filled, the submit candidate SHALL become selectable

#### Scenario: Submit excluded from the EARLY_STAGE roulette while unfilled
- **WHEN** the form-completion context holds, the submit candidate is unvisited (boosted by `W_SUBMIT`), and the EARLY_STAGE forward path collects the state's unvisited actions for `randomPickWithPriority`
- **THEN** the submit candidate SHALL be excluded from that roulette's candidate list (INV-FORM-06)
- **AND** the unfilled `EditText` actions SHALL remain eligible

#### Scenario: Submit excluded from least-visited greedy while unfilled
- **WHEN** the form-completion context holds and `greedyPickLeastVisited` would otherwise return the unvisited submit candidate (visit-count tie broken by its boosted priority)
- **THEN** the submit candidate SHALL be excluded from that selection (INV-FORM-06)
- **AND** once no unfilled `EditText` remains, it SHALL be eligible again

#### Scenario: No identifiable submit candidate
- **WHEN** the form-completion context holds, no action has `getMopBoost() > 0`, there is no lone `Button`, and no clickable's text matches a submit-word
- **THEN** the pass SHALL boost only the unfilled `EditText` actions
- **AND** the `FORM boost` log line SHALL report `submit=none`

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
- **WHEN** the selected action carries a form boost of `W_FILL` set by the pass
- **THEN** the step's `StepRecord` SHALL carry `dec.form:<W_FILL>` alongside the `dec.mop`/`dec.wtg`/`dec.cov`/`dec.menu` fields that are non-zero

#### Scenario: No log line when context absent
- **WHEN** the form-completion context is `false` for the state
- **THEN** no `FORM boost` line SHALL be emitted (INV-FORM-01)

#### Scenario: Pass and deterministic fill both disabled when the flag is off
- **WHEN** `Config.formCompletionEnabled` is `false` and a state carries two unfilled `EditText` fields and a submit `Button`
- **THEN** `FormCompletionPass` SHALL be absent from the pipeline — no priority change, no `formBoost`, and no `FORM boost` log line for that state
- **AND** `ApeAgent.checkInput()` SHALL fill a selected unfilled `EditText` only when `RandomHelper.toss(ape.inputRate)` succeeds (legacy per-field gate, upstream behavior)

