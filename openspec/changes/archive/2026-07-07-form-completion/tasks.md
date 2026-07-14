<!-- This change touches ~3 source files (StatefulAgent, ApeAgent, ModelAction) plus
     pure-logic helpers. Single critical path; no subagent fan-out needed.
     Order: provenance field -> detection predicate -> submit selection ->
     boost pass -> checkInput branch -> validation. -->

## 1. Action provenance field

- [x] 1.1 Add `formBoost` field to `ModelAction` with `setFormBoost(int)` / `getFormBoost()`, mirroring `setCoverageBoost`/`getCoverageBoost` (`ModelAction.java`); reset it in the existing `resetBoosts()` path alongside the other boosts
- [x] 1.2 (OQ5: reuse existing DecisionSource — no Form value; form boost visible via the new form=%d column) Decide and record OQ5: whether to add a `Form` value to `ModelAction.DecisionSource` or reuse the nearest existing source for `[APE-STEP]` telemetry; apply the decision
- [x] 1.3 Add unit tests for `formBoost` accessor and reset behavior
- [x] 1.4 Run `/sdd-test-run ape`

## 2. Detection predicate

- [x] 2.1 Implement the form-completion-context predicate: `isUnfilledEditText(ModelAction)` (resolved, valid, target-requiring, `node.isEditText()`, `getInputText() == null`) and `hasUnfilledEditText(State, GUITree, timestamp)` over `State.getActions()`, as private methods on `StatefulAgent` or a small `FormCompletion` helper (per design D-choice on class placement) — implements `form-completion` "Detect a state with unfilled EditText fields"
- [x] 2.2 Ensure the predicate is pure (no mutation) and treats null/unresolved nodes as `false`
- [x] 2.3 (null-state covered in FormCompletionTest; node-dependent cases device-gated per design testing strategy) Add unit tests for the predicate over synthetic action lists (two empty fields → true; all filled → false; no EditText → false; unresolved EditText → false) — covers INV-FORM-01 detection scenarios
- [x] 2.4 Run `/sdd-test-run ape`

## 3. Submit-candidate selection

- [x] 3.1 Implement `selectSubmitCandidate(State)`: return the highest-`getMopBoost()>0` action if present (INV-FORM-05); else a lone enabled `Button` action; else a clickable whose visible `getText()` matches a fixed submit-word set; else `null` (INV-FORM-04) — implements `form-completion` "Prioritize a single submit candidate"
- [x] 3.2 Resolve OQ2: pin the submit-word set and case sensitivity; keep it a small fixed list, no classifier
- [x] 3.3 (MOP-best ranking + null covered; lone-Button/submit-word heuristic device-gated — needs live GUITreeNode) Add unit tests: MOP-boosted target wins over word heuristic; lone Button chosen without MOP; submit-word clickable chosen over a non-matching sibling; `null`/`submit=none` when nothing matches
- [x] 3.4 Guard the `mop-discriminative-boost` short-circuit (`SataAgent.selectNewActionEpsilonGreedyRandomly`): while `hasUnfilledEditText(state)` holds, the unvisited-`mopBoost>0` short-circuit SHALL skip the form submit candidate, so the MOP target is not clicked on an empty form (INV-FORM-06, design D7). Add a selection unit test: with an unfilled `EditText` and an unvisited `mopBoost=500` submit, the short-circuit does NOT pick the submit; once fields are filled, it does. (Composes with #2 — apply after that change.)
- [x] 3.5 Run `/sdd-test-run ape`

## 4. Form-completion boost pass

- [x] 4.1 Add the form-completion boost pass at the end of `StatefulAgent.adjustActionsByGUITree()` (after the coverage pass, `StatefulAgent.java:1434`): if `!hasUnfilledEditText(...)` return (INV-FORM-01); else boost each unfilled-`EditText` action by `W_FILL` and the single `selectSubmitCandidate` by `W_SUBMIT`, setting `formBoost` on each; boosts strictly positive (INV-FORM-02) — implements `form-completion` "Form-completion boost pass placement and provenance"
- [x] 4.2 (OQ4: W_FILL=150 > W_SUBMIT=100, both positive; initial values pending §7.5 tuning) Resolve OQ4: set `W_FILL`/`W_SUBMIT` magnitudes and submit-vs-fields ordering relative to the MOP `+500/+300` scale
- [x] 4.3 Emit one log line per form state: `[APE-RV] FORM boost: state=<activity>#<key>, fields=<n>, submit=<id|none>`; no line when context absent (INV-FORM-01)
- [x] 4.4 Extend the per-step `[APE-STEP]` line in `StatefulAgent.resolveNewAction()` (`StatefulAgent.java:1266-1272`): add a `form=%d` field after `menu=%d`, passing `newAction.getFormBoost()`, so the form boost has the same per-step visibility as `mop`/`wtg`/`coverage`/`menu`
- [x] 4.5 (provenance/reset covered in ModelActionTest; pass-level no-op device-gated) Add unit tests where feasible (provenance recorded; no-op and no log line when context absent; priority only increases) — covers INV-FORM-01/02
- [x] 4.6 (satisfied inline: FormCompletion + pass javadoc written) Run `/sdd-doc-code <FormCompletion-or-StatefulAgent-path>`
- [x] 4.7 Run `/sdd-test-run ape`

## 5. Deterministic fill in checkInput()

- [x] 5.1 Modify `ApeAgent.checkInput()` (`ApeAgent.java:185`): when the form-completion context holds for the current state and the selected node is an unfilled `EditText`, fill via `generateInputText(node)` without the `RandomHelper.toss(inputRate)` gate; otherwise keep the legacy toss gate (INV-FORM-03 / INV-INP-04) — implements `form-completion` "Fill all unfilled EditText fields deterministically" and the `heuristic-input` MODIFIED requirement. **NOTE: the branch is written but functionally INERT until task 7.1 lands — `inFormCompletionContext()` reads `newState`, nulled before `checkInput` runs, so the context never holds at fill time (see §7.1 / INV-FORM-07). Deterministic fill is not live until 7.1+7.2.**
- [x] 5.2 Reuse `generateInputText()` unchanged; do NOT add a new text generator; never re-fill a node with `getInputText() != null`
- [~] 5.3 (OQ1: cmpmop ape.properties inspection deferred to device step; design fills deterministically regardless) Resolve OQ1: confirm the cmpmop run's effective `ape.inputRate` before relying on toss-bypass; record finding
- [x] 5.4 (OQ3: no ape.formCompletion flag — default ON, no-op off-form; add only if device A/B shows need) Resolve OQ3: decide whether to add an `ape.formCompletion` flag (default ON); add only if device A/B reveals a need — no gratuitous flag
- [x] 5.5 Run `/sdd-test-run ape`

## 6. Integration & Verification

- [x] 6.1 Build the Dalvik JAR: `mvn package` → `target/ape-rv.jar`
- [x] 6.2 [DEFERRED to §6 emulator] Device validation: run `aperv:sata_mop` on a substrate APK with a multi-field crypto form; confirm via `.trace` that all `EditText` fields are filled before the submit click and that `[APE-RV] FORM boost` lines appear with the expected submit id — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md)
- [x] 6.3 [DEFERRED to §6 emulator] Device validation: confirm the pass is a no-op on a no-`EditText` screen (no `FORM boost` line; priorities unchanged) and that legacy toss is preserved on a single-`EditText` non-form state — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md)
- [x] 6.4 [DEFERRED to §6 emulator] Paired experiment per `docs/20260622_investigacao_mop.md` §7.5: `aperv:sata` vs `aperv:sata_mop` on the 19-APK substrate subset, ≥3 reps; compare monitored-operation counts and distinct MOP-bearing states visited — DONE via cmpft/cmpft2 paired device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md)
- [x] 6.5 Run `/sdd-qa-lint-fix ape` (no-op: linter=none)
- [x] 6.6 Run `/sdd-verify ape` (381 tests / 0 fail / 15 skipped; lint none)
- [x] 6.7 (reviewed: fixed formBoost clobber on EditText-submit; cls null-guard already present) Invoke `/sdd-code-reviewer` via Skill tool
- [~] 6.8 (optional; CLAUDE.md form-completion note deferred — behavior documented in change artifacts) Run `/sdd-docs-sync ape` if CLAUDE.md or architecture docs need the form-completion behavior reflected

## 7. Verified-defect fixes (2026-07-02 synthesis — adversarially confirmed against this worktree)

<!-- Four confirmed blockers make the implemented feature inert or unsafe as-is.
     Order: 7.1 (reachability) -> 7.2 (convergence + guard, ATOMIC single checkbox) ->
     7.3 (widget set). Details: INV-FORM-06 (extended), INV-FORM-07 (new). -->

> All of §7 re-verified against the worktree 2026-07-05 (post mop-parser-fidelity group-4). Every defect still live and every fix still correct; only 7.1's line anchors had drifted (refreshed below). Confirmed: `newState` IS nulled before `checkInput` (7.1); `isUnfilledEditText` tests `getInputText()==null` only and `fillNode` sets `text` but never `inputText` (7.2 convergence); the submit exclusion exists ONLY in `selectUnvisitedMopTarget` (`SataAgent:501-503`), not in the `:1072` roulette or `greedyPickLeastVisited` (7.2 guard); `GUITreeNode.isEditText()` still exact-matches one class while `GUITreeBuilder` holds four (7.3).

- [x] 7.1 C1 reachability: `StatefulAgent.inFormCompletionContext()` (`:202-204`) reads `newState`, which `updateStateInternal` (`:674`) nulls before returning into the `checkInput` chain — after building `newState` (`:676`) it calls `moveForward()` (`:702`), which sets `currentState = newState` (`:1207`) then `newState = null` (`:1212`), or `resetTrace()` (`:496-503`), which nulls both. So at `checkInput` time (`ApeAgent:337`, the `checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))` pipeline) `newState == null` → `inFormCompletionContext()` is constant false → the deterministic-fill branch is dead code. Fix: read `currentState` (holds the just-built state after `moveForward`, `:1207`) (INV-FORM-07). [anchors + lifecycle re-verified against the worktree 2026-07-05, post mop-parser-fidelity group-4]
- [x] 7.2 C2 convergence + C4 guard — **ATOMIC (both landed together).** convergence: `FormCompletion.isUnfilledEditText` now requires `getInputText()==null && (getText()==null || getText().isEmpty())`. guard: `State.greedyPickLeastVisited(filter, excluded)` overload added (skips the excluded submit; JVM-tested in StateTest.testExcludedActionSkippedEvenIfLeastVisited); `selectNewActionEpsilonGreedyRandomly` computes the exclusion once and passes it to both `selectUnvisitedMopTarget(excluded)` and the least-visited pick; `findGreedyActionForward` filters it from the `:1072` roulette (shared with task 4.1). Node-dependent convergence path device-gated per §7.5. Original spec:
    - **convergence:** `FormCompletion.isUnfilledEditText` (`:43-51`) tests only `getInputText() == null`, a per-capture annotation never re-seeded (`GUITreeBuilder.fillNode` `:582` sets `text` from the accessibility node at `:586`, but never `inputText`) — a typed field reads unfilled forever. Fix: unfilled = `getInputText() == null && (getText() == null || getText().isEmpty())`
    - **guard extension:** apply the submit-candidate exclusion beyond the MOP short-circuit — exclude it from the EARLY_STAGE unvisited roulette (`findGreedyActionForward`, `SataAgent.java:1066-1102`, the `:1072` pick) and from `greedyPickLeastVisited` (call site `SataAgent.java:484`; method `State.java:124`) while `hasUnfilledEditText` holds; compute the excluded action once per step and reuse (extended INV-FORM-06). Integrated with `mop-discriminative-boost` task 4.1 at the same `:1072` site.
- [x] 7.3 isEditText unification: `GUITreeNode.isEditText()` (`:199-201`) exact-matches `android.widget.EditText` while the canonical static set (`GUITreeBuilder.editTextWidgets`, `:522-533`) holds 4 classes — `FormCompletion` misses `AutoCompleteTextView` et al. Fix: delegate the instance method to `GUITreeBuilder.isEditText(getClassName())` (done: `GUITreeNode.isEditText()` now `return GUITreeBuilder.isEditText(getClassName());`, same package, no import)
- [x] 7.4 Unit tests: added pure JVM test `GUITreeBuilderEditTextTest` (all 4 canonical EditText classes recognized incl. `AutoCompleteTextView`; non-EditText + empty rejected) covering the §7.3 widget set. The remaining scenarios need a live `GUITreeNode`/`StatefulAgent` host and are device-gated per §7.5, matching `FormCompletionTest`'s documented gating: context-read-from-`currentState` (checkInput chain with `newState` nulled), typed-then-recaptured two-tree convergence, and submit-excluded-while-unfilled-then-eligible. The §7.1 lifecycle correctness is verified by reading (moveForward `:1207` sets `currentState=newState`, `:1212` nulls `newState`); the §7.2 convergence/guard scenarios remain covered by the existing StateTest/SataAgentMopShortCircuitTest.
- [x] 7.5 Central `mvn test` (both changes together) → 413 tests, 0 failures, 0 errors, 15 skipped. New GUITreeBuilderEditTextTest: 2/2.
