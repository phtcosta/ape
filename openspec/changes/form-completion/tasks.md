<!-- This change touches ~3 source files (StatefulAgent, ApeAgent, ModelAction) plus
     pure-logic helpers. Single critical path; no subagent fan-out needed.
     Order: provenance field -> detection predicate -> submit selection ->
     boost pass -> checkInput branch -> validation. -->

## 1. Action provenance field

- [ ] 1.1 Add `formBoost` field to `ModelAction` with `setFormBoost(int)` / `getFormBoost()`, mirroring `setCoverageBoost`/`getCoverageBoost` (`ModelAction.java`); reset it in the existing `resetBoosts()` path alongside the other boosts
- [ ] 1.2 Decide and record OQ5: whether to add a `Form` value to `ModelAction.DecisionSource` or reuse the nearest existing source for `[APE-STEP]` telemetry; apply the decision
- [ ] 1.3 Add unit tests for `formBoost` accessor and reset behavior
- [ ] 1.4 Run `/sdd-test-run ape`

## 2. Detection predicate

- [ ] 2.1 Implement the form-completion-context predicate: `isUnfilledEditText(ModelAction)` (resolved, valid, target-requiring, `node.isEditText()`, `getInputText() == null`) and `hasUnfilledEditText(State, GUITree, timestamp)` over `State.getActions()`, as private methods on `StatefulAgent` or a small `FormCompletion` helper (per design D-choice on class placement) — implements `form-completion` "Detect a state with unfilled EditText fields"
- [ ] 2.2 Ensure the predicate is pure (no mutation) and treats null/unresolved nodes as `false`
- [ ] 2.3 Add unit tests for the predicate over synthetic action lists (two empty fields → true; all filled → false; no EditText → false; unresolved EditText → false) — covers INV-FORM-01 detection scenarios
- [ ] 2.4 Run `/sdd-test-run ape`

## 3. Submit-candidate selection

- [ ] 3.1 Implement `selectSubmitCandidate(State)`: return the highest-`getMopBoost()>0` action if present (INV-FORM-05); else a lone enabled `Button` action; else a clickable whose visible `getText()` matches a fixed submit-word set; else `null` (INV-FORM-04) — implements `form-completion` "Prioritize a single submit candidate"
- [ ] 3.2 Resolve OQ2: pin the submit-word set and case sensitivity; keep it a small fixed list, no classifier
- [ ] 3.3 Add unit tests: MOP-boosted target wins over word heuristic; lone Button chosen without MOP; submit-word clickable chosen over a non-matching sibling; `null`/`submit=none` when nothing matches
- [ ] 3.4 Run `/sdd-test-run ape`

## 4. Form-completion boost pass

- [ ] 4.1 Add the form-completion boost pass at the end of `StatefulAgent.adjustActionsByGUITree()` (after the coverage pass, `StatefulAgent.java:1434`): if `!hasUnfilledEditText(...)` return (INV-FORM-01); else boost each unfilled-`EditText` action by `W_FILL` and the single `selectSubmitCandidate` by `W_SUBMIT`, setting `formBoost` on each; boosts strictly positive (INV-FORM-02) — implements `form-completion` "Form-completion boost pass placement and provenance"
- [ ] 4.2 Resolve OQ4: set `W_FILL`/`W_SUBMIT` magnitudes and submit-vs-fields ordering relative to the MOP `+500/+300` scale
- [ ] 4.3 Emit one log line per form state: `[APE-RV] FORM boost: state=<activity>#<key>, fields=<n>, submit=<id|none>`; no line when context absent (INV-FORM-01)
- [ ] 4.4 Add unit tests where feasible (provenance recorded; no-op and no log line when context absent; priority only increases) — covers INV-FORM-01/02
- [ ] 4.5 Run `/sdd-doc-code <FormCompletion-or-StatefulAgent-path>`
- [ ] 4.6 Run `/sdd-test-run ape`

## 5. Deterministic fill in checkInput()

- [ ] 5.1 Modify `ApeAgent.checkInput()` (`ApeAgent.java:185`): when the form-completion context holds for the current state and the selected node is an unfilled `EditText`, fill via `generateInputText(node)` without the `RandomHelper.toss(inputRate)` gate; otherwise keep the legacy toss gate (INV-FORM-03 / INV-INP-04) — implements `form-completion` "Fill all unfilled EditText fields deterministically" and the `heuristic-input` MODIFIED requirement
- [ ] 5.2 Reuse `generateInputText()` unchanged; do NOT add a new text generator; never re-fill a node with `getInputText() != null`
- [ ] 5.3 Resolve OQ1: confirm the cmpmop run's effective `ape.inputRate` before relying on toss-bypass; record finding
- [ ] 5.4 Resolve OQ3: decide whether to add an `ape.formCompletion` flag (default ON); add only if device A/B reveals a need — no gratuitous flag
- [ ] 5.5 Run `/sdd-test-run ape`

## 6. Integration & Verification

- [ ] 6.1 Build the Dalvik JAR: `mvn package` → `target/ape-rv.jar`
- [ ] 6.2 Device validation: run `aperv:sata_mop` on a substrate APK with a multi-field crypto form; confirm via `.trace` that all `EditText` fields are filled before the submit click and that `[APE-RV] FORM boost` lines appear with the expected submit id
- [ ] 6.3 Device validation: confirm the pass is a no-op on a no-`EditText` screen (no `FORM boost` line; priorities unchanged) and that legacy toss is preserved on a single-`EditText` non-form state
- [ ] 6.4 Paired experiment per `docs/20260622_investigacao_mop.md` §7.5: `aperv:sata` vs `aperv:sata_mop` on the 19-APK substrate subset, ≥3 reps; compare monitored-operation counts and distinct MOP-bearing states visited
- [ ] 6.5 Run `/sdd-qa-lint-fix ape`
- [ ] 6.6 Run `/sdd-verify ape`
- [ ] 6.7 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 6.8 Run `/sdd-docs-sync ape` if CLAUDE.md or architecture docs need the form-completion behavior reflected
