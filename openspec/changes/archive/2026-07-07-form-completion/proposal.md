## Why

Crypto/JCA MOP targets are overwhelmingly submit handlers: code that runs only after a form's fields are filled and a submit control is clicked. APE-RV has no "fill all fields, then submit" sequencing. Text is a side-effect of `MODEL_CLICK` on an `EditText`, and `ApeAgent.checkInput()` fills only the single acted-on field, and only with probability `ape.inputRate` (default 0.8, observed effective fill ≈42% per field). For a k-field form, the probability that all fields are filled before the submit click is roughly `inputRate^k`, which is negligible. As a result, the monitored operations behind those handlers rarely execute even when the screen carrying the form is reached. This change is item #1 of `docs/20260622_investigacao_mop.md` §7, verified against source.

## What Changes

- Introduce a `form-completion` behavior in the agent's per-state action adjustment: when the current state carries at least one unfilled `EditText` (a resolved, target-requiring action whose node `getInputText() == null`), the agent SHALL (a) deterministically pre-populate input text for ALL such fields and (b) raise the priority of a single submit candidate so it is selected after the fields are filled.
- Add a "form-completion boost" pass to `StatefulAgent.adjustActionsByGUITree()` (`StatefulAgent.java:1309`), mirroring the existing coverage pass (`:1434`): it raises the priority of unfilled-`EditText` actions and of the submit candidate, and is a no-op when the state has no unfilled `EditText`.
- Make `ApeAgent.checkInput()` (`ApeAgent.java:185`) fill deterministically (bypassing the `RandomHelper.toss(inputRate)` gate) when the form-completion context applies, so a selected `EditText` in a form context always receives text. The legacy probabilistic gate remains for states with no form context.
- Reuse the existing type-aware `ApeAgent.generateInputText()` (`ApeAgent.java:203`) for per-field text — no new text generator, no form classifier, no machine learning.
- Submit-candidate selection composes with MOP guidance: the candidate is the MOP-boosted target action on the state if one exists (change `mop-discriminative-boost`); otherwise a minimal heuristic (a lone `Button`, or a clickable whose text matches submit-like words).
- **Verified-defect fixes (2026-07-02 synthesis, tasks group 7)**: the form-completion context is read from `currentState` (the `newState` field is nulled by `moveForward()` before `checkInput` runs — the deterministic fill was dead code); "unfilled" also inspects the captured `getText()` so a typed field registers as filled on the next capture (the `getInputText()`-only predicate never converged); the submit exclusion (INV-FORM-06) extends beyond the MOP short-circuit to the EARLY_STAGE roulette and `greedyPickLeastVisited` (both could click the submit on an empty form); and `isEditText()` unifies on the canonical 4-class static set (`AutoCompleteTextView` et al. were silently skipped).

## Capabilities

### New Capabilities
- `form-completion`: Detecting a state that carries unfilled `EditText` widgets, deterministically filling all of them, and prioritizing a single submit candidate so the form is submitted after completion. Covers the detection predicate, the fill-all behavior, the submit-candidate selection (MOP-boosted action or minimal heuristic), and the boost pass that realizes the priorities.

### Modified Capabilities
- `heuristic-input`: `ApeAgent.checkInput()` gains a deterministic-fill path that bypasses the `ape.inputRate` toss when the form-completion context applies. The existing probabilistic behavior is preserved for non-form states. This is a requirement-level change to an existing capability, so a delta spec is included.

## Impact

- Affected source: `com.android.commands.monkey.ape.agent.StatefulAgent` (new boost pass in `adjustActionsByGUITree()`; `form=%d` field added to the `[APE-STEP]` line in `resolveNewAction()`; the `mop-discriminative-boost` short-circuit in `SataAgent.selectNewActionEpsilonGreedyRandomly` gains the INV-FORM-06 guard), `com.android.commands.monkey.ape.agent.ApeAgent` (`checkInput()` deterministic-fill path; `generateInputText()` reused unchanged), `com.android.commands.monkey.ape.model.ModelAction` (per-action form boost accessor, mirroring `setCoverageBoost`/`setMopBoost`).
- Composition: the submit-candidate selection composes with `mop-discriminative-boost` (the MOP-boosted target is the preferred submit candidate when present). Because that MOP target is also what #2's greedy short-circuit selects, this change adds the INV-FORM-06 guard so the submit is not clicked on an empty form — so it SHALL be applied after `mop-discriminative-boost`.
- Configuration: `ape.inputRate` semantics are unchanged for non-form states; a single optional flag MAY gate the whole behavior (default ON) and is decided in design. No removal of existing flags.
- Validation: device-only (no automated Android-runtime suite). Validated on the 19-APK substrate subset described in `docs/20260622_investigacao_mop.md` §7.5, paired `aperv:sata` vs `aperv:sata_mop`, measuring monitored-operation counts and distinct MOP-bearing states visited.
