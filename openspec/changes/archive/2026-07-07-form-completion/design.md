## Context

The investigation `docs/20260622_investigacao_mop.md` §1 (Layer 3) and §7-#1 establish that crypto/JCA MOP targets are predominantly submit handlers: they execute only after a form's fields hold valid text and a submit control is clicked. APE-RV's current input path defeats this:

- There is NO `SET_TEXT` action type. `ActionType` is `BACK, MENU, CLICK, LONG_CLICK` plus four scroll variants. Text is a side-effect of `MODEL_CLICK` on an `EditText`.
- `ApeAgent.checkInput(action)` (`ApeAgent.java:185-195`) fills the single acted-on `EditText` only when `node.getInputText() == null` AND `RandomHelper.toss(inputRate)` succeeds. `ape.inputRate` defaults to 0.8 (`Config.java:64`); observed effective fill ≈42% per field. For a k-field form, P(all filled before submit) ≈ `inputRate^k` → negligible.
- `MonkeySourceApe.doInput` types `node.getInputText()` when non-null, else sends ESCAPE.
- `ApeAgent.generateInputText(node)` (`ApeAgent.java:203`) is already type-aware (uses `TypedInputGenerator` from MOP `inputType`/`hint`, else `InputValueGenerator`/`StringCache`). Per-field text generation is adequate; what is missing is FORM COMPLETENESS and submit sequencing.
- Priority boosts are applied in `StatefulAgent.adjustActionsByGUITree()` (`StatefulAgent.java:1309`): the MOP pass (`:1367`), the WTG pass (`:1408`), and the coverage pass (`:1434`) each add to `action.priority`. This is the natural extension point for a new form-completion pass that mirrors the coverage pass.

This design adds a minimal form-completion behavior. It is intentionally small (P1): no form classifier, no new text generator, no machine learning. It reuses the existing type-aware generator and the existing boost-pass mechanism.

## Architecture

```
adjustActionsByGUITree()  (StatefulAgent.java:1309)
  ├─ base SATA priority
  ├─ MOP pass            (:1367)   ── sets action.mopBoost
  ├─ WTG pass            (:1408)   ── sets action.wtgBoost
  ├─ coverage pass       (:1434)   ── sets action.coverageBoost
  └─ form-completion pass (NEW)    ── sets action.formBoost
        detect: state has ≥1 unfilled EditText action (resolved, getInputText()==null)
        if none → no-op (INV-FORM-01)
        else:
          boost every unfilled-EditText action  (so fields get filled first)
          pick ONE submit candidate, boost it
            submit candidate = MOP-boosted target action if action.mopBoost>0 exists,
                               else minimal heuristic (lone Button / submit-word clickable)

checkInput(action)  (ApeAgent.java:185)
  if node.isEditText() && node.getInputText()==null:
     if form-completion context applies on the state → fill deterministically
                                                       (no inputRate toss)
     else                                             → fill with toss(inputRate)  (legacy)
     fill text comes from generateInputText(node)  (unchanged, type-aware)
```

The "form-completion context applies" predicate is the same `State` predicate used by the boost pass: the state has at least one unfilled `EditText` action. Computing it once per state and reading it in both the pass and `checkInput()` keeps the two halves consistent.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `FormCompletion.hasUnfilledEditText(State, GUITree)` | Detection predicate: does the state carry ≥1 unfilled `EditText` action? | `State`, `GUITree`, timestamp | `boolean` |
| `FormCompletion.isUnfilledEditText(ModelAction)` | Per-action test: resolved `EditText` action with `getInputText()==null` | `ModelAction` | `boolean` |
| `FormCompletion.selectSubmitCandidate(State)` | Choose ONE submit candidate: MOP-boosted target if present, else heuristic | `State` (post MOP pass) | `ModelAction` or null |
| `StatefulAgent.adjustActionsByGUITree()` | Run the form-completion boost pass after coverage pass | `newState`, `newGUITree` | mutated `action.priority`, `action.formBoost` |
| `ApeAgent.checkInput(Action)` | Deterministic fill in form context, else legacy toss | `Action` | same `Action`, `node.inputText` set |
| `ModelAction.setFormBoost(int)` / `getFormBoost()` | Per-action form boost provenance (mirrors `setCoverageBoost`) | `int` | stored boost |

`FormCompletion` is a small static-method helper (or private methods on `StatefulAgent`); a separate class is preferred only if it keeps `StatefulAgent` readable. Decided below.

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| FR01: Detect state with unfilled EditTexts | `FormCompletion.hasUnfilledEditText()`, `isUnfilledEditText()` | Device run on a multi-field form; trace shows the form-completion pass firing |
| FR02: Fill ALL unfilled EditTexts deterministically | `ApeAgent.checkInput()` deterministic-fill path; form pass boosts every unfilled `EditText` | Device run: all `EditText` fields hold text before submit; `getInputText()!=null` on each |
| FR03: Prioritize a single submit candidate | `FormCompletion.selectSubmitCandidate()`; form pass adds boost to it | Device run: submit action selected after fields filled; `[APE-RV] FORM boost` line names the submit candidate |
| INV-FORM-01 (no-op when no unfilled EditText) | Pass guarded by `hasUnfilledEditText()` returning early | Device run on a no-EditText state: no `FORM boost` line; priorities unchanged |
| INV-FORM-02 (never reduces priority) | Pass only does `setPriority(getPriority() + boost)` with `boost > 0` | Code review; device trace shows monotonic priority for boosted actions |
| INV-FORM-03 (deterministic fill only in form context) | `checkInput()` branches on the same state predicate | Device run on a single-EditText non-form-target state: legacy toss path still used |
| INV-FORM-04 (at most one submit candidate boosted) | `selectSubmitCandidate()` returns ≤1 action | Unit-style assertion on candidate selection over a synthetic action list |
| INV-FORM-06 (no premature MOP short-circuit on submit) | Guard in `SataAgent.selectNewActionEpsilonGreedyRandomly`: skip submit candidate while `hasUnfilledEditText(state)` | Selection unit test: unvisited `mopBoost>0` submit not picked while a field is unfilled; picked once filled |
| INV-INP-04 (heuristic-input delta: deterministic fill bypasses toss) | `checkInput()` form-context branch | Device run; field fill rate = 100% in form context vs ≈42% outside |

There is no Android-runtime automated suite; "test" entries that are not pure-logic are device-validated per `docs/20260622_investigacao_mop.md` §7.5.

## Goals / Non-Goals

**Goals:**
- On a state with ≥1 unfilled `EditText`, fill ALL unfilled `EditText` fields deterministically using the existing type-aware generator.
- Raise the priority of a single submit candidate so it is selected after the fields are filled.
- Compose with `mop-discriminative-boost`: prefer the MOP-boosted target action as the submit candidate when one exists.
- Keep the change minimal: one boost pass + one `checkInput()` branch + one `ModelAction` boost field.

**Non-Goals:**
- No form classifier, no field-dependency model, no ML.
- No new `SET_TEXT` action type. Text remains a side-effect of `MODEL_CLICK`.
- No multi-screen form flows (wizard pagination); a "form" is the set of `EditText` actions on a single state.
- No change to `generateInputText()` / `TypedInputGenerator` text quality.
- No removal or repurposing of `ape.inputRate` for non-form states.

## Decisions

**D1 — Realize the behavior as a boost pass + a `checkInput()` branch, not a new action type.**
A `SET_TEXT` action type would touch `ActionType`, the Monkey event system, the model graph, and every agent. The investigation explicitly notes text is a side-effect of `MODEL_CLICK`; the boost-pass + deterministic-fill approach reuses the existing `MODEL_CLICK`-on-`EditText` path and the existing extension point (`adjustActionsByGUITree`). Chosen over a new action type for P1 simplicity and to mirror the coverage pass.

**D2 — Fill ALL unfilled fields by boosting each unfilled-`EditText` action AND filling deterministically in `checkInput()`.**
The agent still selects one action per step. Boosting every unfilled `EditText` makes the agent walk through them; the `checkInput()` deterministic branch guarantees each selected `EditText` actually receives text (instead of a 0.8 toss). Together they drive the form toward completeness without a buffered macro. Alternative considered: a buffered "fill-all-then-submit" macro injected into the action buffer — rejected as more machinery than P1 warrants and harder to reconcile with refinement/relocation.

**D3 — Submit candidate = MOP-boosted target if present, else minimal heuristic.**
When `mop-discriminative-boost` is active, the MOP-boosted target action IS the submit handler the run is steering toward, so reusing it is exact and free. Without MOP data, fall back to a lone `Button`, else a clickable whose visible text matches a small fixed submit-word set (e.g. submit, ok, send, login, sign in, encrypt, save, confirm, next, done — exact set is an open question). Keep the heuristic small (P1); do not build a classifier.

**D4 — Deterministic fill is scoped to the form-completion context.**
Outside a form context (no unfilled `EditText` on the state, or the predicate otherwise false) `checkInput()` keeps the legacy `toss(inputRate)` behavior. This preserves existing exploration behavior on screens that are not forms and keeps the change auditable (INV-FORM-03 / INV-INP-04).

**D5 — Config flag only if needed; default ON.**
The behavior is a strict improvement for form screens and a no-op elsewhere (INV-FORM-01), so a flag is not required for safety. A single boolean (e.g. `ape.formCompletion`, default true) MAY be added if device validation shows a need to A/B it; no per-knob flags. Decided during validation, not pre-emptively (P1: no gratuitous flags).

**D6 — `formBoost` provenance field on `ModelAction`.**
Mirror the existing `mopBoost`/`coverageBoost`/`wtgBoost`/`menuBoost` accessors so the `[APE-STEP]` telemetry line can report the form boost, consistent with the other passes. The `[APE-STEP]` line (`StatefulAgent.resolveNewAction`, `:1266-1272`) gains a `form=%d` field. Reuse `DecisionSource` for attribution; whether to add a `Form` enum value is an open question (the enum currently has `SATA, MOP, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget`).

**D7 — Guard the `mop-discriminative-boost` short-circuit against a premature submit.**
`mop-discriminative-boost` (#2) adds a greedy short-circuit in `SataAgent.selectNewActionEpsilonGreedyRandomly`: an unvisited action with `mopBoost>0` is selected before the roulette/least-visited step, bypassing `priority` entirely. The submit candidate of a MOP-bearing form IS that `mopBoost>0` action (D3 / INV-FORM-05), while the unfilled `EditText` fields carry only `formBoost` (a `priority` boost). So on first entry to the form the short-circuit would click the submit on an empty form before any field is filled — wasting the click and the monitored-operation attempt; it self-corrects only after the submit becomes "visited", costing one wasted submit. The fix is a one-line guard on the #2 short-circuit: skip an action that is the current state's form submit candidate while `hasUnfilledEditText(state)` holds (INV-FORM-06). Because this change ships after #2 and already owns the `hasUnfilledEditText` predicate and `selectSubmitCandidate`, the guard lives here, refining the short-circuit rather than duplicating form logic into #2. This subsumes the priority-ordering half of OQ4 (fields are filled before the submit regardless of `W_SUBMIT` vs `W_FILL`); `W_SUBMIT`/`W_FILL` magnitudes still tune the non-short-circuit roulette path.

## API Design

### `boolean FormCompletion.isUnfilledEditText(ModelAction action)`
- **Pre:** `action != null`.
- **Returns** `true` iff `action.requireTarget()`, `action.isValid()`, `action.isResolvedAt(timestamp)`, the resolved node `isEditText()`, and `node.getInputText() == null`.
- **Errors:** none; null/unresolved nodes yield `false`.

### `boolean FormCompletion.hasUnfilledEditText(State state, GUITree tree, int timestamp)`
- **Pre:** `state != null`.
- **Returns** `true` iff at least one action in `state.getActions()` satisfies `isUnfilledEditText`.
- **Postcondition:** pure; no mutation. This is the form-completion-context predicate read by both the boost pass and `checkInput()`.

### `ModelAction FormCompletion.selectSubmitCandidate(State state)`
- **Pre:** called after the MOP pass has run (so `mopBoost` is populated).
- **Returns** the MOP-boosted target action (highest `mopBoost > 0`) if any; else a lone enabled `Button` action; else a clickable action whose visible text matches the submit-word set; else `null`.
- **Postcondition:** returns at most one action (INV-FORM-04). No mutation.

### form-completion pass in `StatefulAgent.adjustActionsByGUITree()`
- Runs after the coverage pass.
- If `!hasUnfilledEditText(newState, newGUITree, timestamp)` → return (INV-FORM-01).
- Else: for each action where `isUnfilledEditText(action)`, `action.setPriority(action.getPriority() + W_FILL)` and `action.setFormBoost(W_FILL)`; and for `submit = selectSubmitCandidate(newState)`, if non-null, `submit.setPriority(submit.getPriority() + W_SUBMIT)` and `submit.setFormBoost(W_SUBMIT)`.
- Emits one log line: `[APE-RV] FORM boost: state=<activity>#<key>, fields=<n>, submit=<id|none>`.
- `W_FILL` and `W_SUBMIT` are positive (boost-only). `W_SUBMIT < W_FILL` so fields are walked before the submit dominates, OR `W_SUBMIT` is applied only once the fields are already filled — exact ordering is an open question (see below).

### `ApeAgent.checkInput(Action action)` — modified
- Unchanged signature.
- When `node.isEditText() && node.getInputText() == null`: if the form-completion context applies on the current state, set `node.setInputText(generateInputText(node))` unconditionally; else keep the `RandomHelper.toss(inputRate)` gate.
- **Side-effect:** `node.inputText` set. **Errors:** none.

## Data Flow

1. New `GUITree` captured; `StatefulAgent.resolveNewAction()` calls `adjustActionsByGUITree()`.
2. Base SATA priority, then MOP / WTG / coverage passes set their boosts.
3. Form-completion pass reads `newState.getActions()`: computes `hasUnfilledEditText`; if true, boosts every unfilled `EditText` action and the single submit candidate; sets `formBoost`.
4. Agent selects an action via the SATA strategy (priority influences greedy tiebreak / roulette per `action-selection`).
5. If the selected action is a `MODEL_CLICK` on an unfilled `EditText`, `checkInput()` fills it deterministically (form context) via `generateInputText()`.
6. `MonkeySourceApe.doInput` types `node.getInputText()`.
7. Repeated over steps, fields fill; once filled, `hasUnfilledEditText` becomes false for those fields and the submit candidate's relative priority lets it be selected.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Null/unresolved node | `isUnfilledEditText` over an unresolved action | Treat as not-an-unfilled-EditText (`false`) | Action simply not boosted |
| No submit candidate found | `selectSubmitCandidate` on a state with fields but no obvious submit | Return `null`; pass boosts only the fields | Fields still get filled; exploration proceeds via normal SATA |
| `generateInputText` returns generic text | type-aware generator has no `inputType`/`hint` | Accept generic text (existing behavior) | Field still filled; submit still attemptable |
| Form spans screens | Multi-state wizard | Out of scope (Non-Goal); per-state predicate only | Each state's fields filled independently |

## Risks / Trade-offs

- [Deterministic fill changes exploration on form screens] -> Scoped to states with unfilled `EditText`s (INV-FORM-03); non-form screens keep legacy `toss(inputRate)`. Gate behind `ape.formCompletion` if device A/B shows regression.
- [Submit heuristic mis-identifies the submit control] -> When MOP data is present the MOP-boosted target is used (exact); the heuristic only fires without MOP data and only boosts (never forces) the candidate, so a wrong guess costs priority, not correctness.
- [Boosting every field could starve other useful actions on the same screen] -> Boost is additive and bounded; SATA's least-visited/roulette still applies. `W_FILL`/`W_SUBMIT` magnitudes tuned against the existing `+500/+300/+100` MOP scale during validation.
- [No buffered macro means fill order is emergent, not guaranteed] -> Acceptable for P1; the combination of per-field boost + deterministic fill empirically drives completeness; validated on the 19-APK substrate subset.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (pure logic) | `isUnfilledEditText`, `hasUnfilledEditText`, `selectSubmitCandidate` over synthetic action lists | JVM unit tests with stub `ModelAction`/`State` where feasible | ~6 tests |
| Device (integration) | Fill-all-then-submit on a multi-field crypto form; no-op on a no-EditText screen; legacy toss preserved on non-form `EditText` | Run `aperv:sata_mop` on a substrate APK; inspect `.trace` for `FORM boost` lines and per-field `getInputText()` | substrate subset |
| Device (paired experiment) | Monitored-operation counts and distinct MOP-bearing states visited, `aperv:sata` vs `aperv:sata_mop` | `docs/20260622_investigacao_mop.md` §7.5 protocol, ≥3 reps | 19 APKs |

## Open Questions

- **OQ1 — Deterministic fill vs keep the toss.** The design fills deterministically in form context (D2/D4). Confirm against the observed ≈42% vs `inputRate=0.8` discrepancy (is the run setting a lower `ape.inputRate`?) before concluding the toss must be bypassed rather than just raised. Resolve by inspecting the cmpmop run's `ape.properties`.
- **OQ2 — Exact submit-detection heuristic.** The MOP-boosted-target path is settled; the no-MOP fallback word set and the lone-Button rule need pinning. Which submit-words, case sensitivity, and how to break ties when several clickables match.
- **OQ3 — Whether a config flag is warranted.** D5 defaults ON with no flag; add `ape.formCompletion` only if device A/B reveals a regression on non-form-heavy apps. Decide during §7.5 validation, not before.
- **OQ4 — Submit-vs-fields ordering and boost magnitudes.** The short-circuit-ordering half is settled by D7 / INV-FORM-06 (the submit is not selectable while any field is unfilled). What remains open is the absolute `W_FILL`/`W_SUBMIT` magnitudes on the roulette/tiebreaker path, relative to the MOP `+500/+300` scale.
- **OQ5 — `DecisionSource.Form` enum value.** Whether to add a `Form` provenance value for `[APE-STEP]` telemetry or reuse the nearest existing source. The enum currently lacks `Form`.

## Verified-Defect Fixes (tasks group 7)

Adversarial verification of the implemented feature (2026-07-02, three independent code-verification passes over this worktree) confirmed four defects that make it inert or unsafe as designed above. The fixes and their rationale:

1. **Context reachability (C1 / INV-FORM-07).** The pipeline is `checkInput(checkFuzzing(checkRestart(updateStateInternal(...))))` (`ApeAgent.java:337`); `updateStateInternal` (`StatefulAgent.java:674`) builds `newState` (`:676`) then ends in `moveForward()` (`:702`) — which sets `currentState = newState` (`:1207`) then `newState = null` (`:1212`) — or `resetTrace()` (`:496-503`), which nulls both, before `checkInput` runs. `inFormCompletionContext()` (`:202-204`) reads `newState` → constant false → the deterministic-fill branch never executed and the legacy toss governed 100% of steps. Fix: read `currentState`, which receives the just-built state via `moveForward` (`:1207`) and is the correct "current screen" reference at `checkInput` time. One-identifier change. [anchors re-verified 2026-07-05]

2. **Predicate convergence (C2).** `inputText` is a per-capture annotation: set only when APE types (`checkInput` → `setInputText`), never copied to the next capture's fresh `GUITreeNode`s, and `fillNode` populates `text` from the accessibility node (`GUITreeBuilder.java:586`) but never `inputText`. So `getInputText() == null` is true for every field on every fresh capture, the form context never cleared, fields were re-boosted forever, and the submit stayed excluded indefinitely. Fix: "unfilled" also requires `getText()` null/empty — the captured text carries typed content across captures, so the predicate converges. (Android exposes hint text separately from `getText()`, so hints do not read as content.)

3. **Guard scope (C4, extended INV-FORM-06).** The submit exclusion lived only inside `selectUnvisitedMopTarget` — but EARLY_STAGE forward consumes unvisited actions via `randomPickWithPriority` (`SataAgent.java:1072`) before the short-circuit branch is ever reached, and `greedyPickLeastVisited` (`State.java:124`) breaks visit-count ties by the very priority `W_SUBMIT` raises. Both paths could click the submit on an empty form. Fix: compute the excluded submit once per step and filter it from those two paths while the context holds. **Must land together with fix 2**: with a non-convergent predicate this exclusion would block the submit permanently.

4. **Widget-class set.** `GUITreeNode.isEditText()` exact-matches `android.widget.EditText` (`:199-201`) while the canonical static set (`GUITreeBuilder.editTextWidgets`, 4 classes) is what text-input assignment uses — `FormCompletion` silently ignored `AutoCompleteTextView`/`MultiAutoCompleteTextView`/`ExtractEditText` fields. Fix: the instance method delegates to the static helper — one source of truth. Widening beyond the 4 classes (AppCompat/Compose) stays out: the accessibility layer usually normalizes `getClassName()` to `android.widget.EditText`, and there is no device evidence yet that widening is needed (open question for §6 device validation).
