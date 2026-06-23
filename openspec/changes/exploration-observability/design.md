## Context

This change bundles the two observability items from `docs/20260622_investigacao_mop.md` §7 (#3 `decision_source` attribution, #4 UICoverage teardown dump). Both are trace-fidelity fixes: they make the existing exploration loop observable without altering selection, scoring, or memory behavior.

Current state, verified in source:

- `StatefulAgent.resolveNewAction()` (`StatefulAgent.java:1256`) calls `selectNewActionNonnull()` then emits a single `[APE-STEP]` line (`:1266-1272`) from the finalized `ModelAction`, printing `decisionSource` and the per-mechanism boosts `getMopBoost`/`getWtgBoost`/`getCoverageBoost`/`getMenuBoost`.
- `SataAgent.logActionSelected(action, type)` (`SataAgent.java:218`) unconditionally calls `setDecisionSource(SATA)` (`:224`) for any `ModelAction` chosen by the SATA chain. The LLM hooks (`:335,346,357`) and the budget-exhausted path (`:323`) set their own source and bypass `logActionSelected`.
- `SataAgent.SataEventType` (`SataAgent.java:181`) = {TRIVIAL_ACTIVITY, SATURATED_STATE, USE_BUFFER, EARLY_STAGE, EPSILON_GREEDY, RANDOM, NULL, BUFFER_LOSS, FILL_BUFFER, BAD_STATE}. Only `EARLY_STAGE` and `EPSILON_GREEDY` consume `priority` (EARLY_STAGE → priority-weighted pick; EPSILON_GREEDY → greedy priority-tiebreak / roulette). The other branches ignore priority.
- `ModelAction.DecisionSource` (`ModelAction.java:42`) = {SATA, MOP, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget}; default `SATA` (`:58`); boost fields `mopBoost`/`wtgBoost`/`coverageBoost`/`menuBoost` (`:59-62`).
- `UICoverageTracker` (`UICoverageTracker.java:51`) holds `stateData` (access-ordered, bounded by `Config.coverageMaxStates`, `:54`) and `activityRollup` (`:67`), and exposes `getCoverageGap` (`:172`), `getInteractionCount` (`:197`), `getTotalElements` (`:290`), `getTotalInteractions` (`:295`), and `widgetId(action)` (`:215`). Element IDs are `"<xpath>|<TYPE>"` for targeted actions and `"<TYPE>"` for non-targeted. There is no dump method today.
- `StatefulAgent` holds `_mopData` (`:135`) and `_coverageTracker` (`:136`); `MopData.activityHasMop(activity)` (`MopData.java:649`) reports whether an activity gates a MOP target. `SataAgent.tearDown()` exists (`:234`) and currently calls `printCounters()`.

These are the only two requirements affected: a MODIFIED `action-selection` requirement and an ADDED `ui-coverage` requirement.

## Architecture

```
SataAgent.logActionSelected(action, type)        [Item #3 — attribution]
   └─ if action is ModelAction
        ├─ if type ∈ {EARLY_STAGE, EPSILON_GREEDY} and maxBoost > 0
        │     └─ setDecisionSource(argmax boost; tie MOP>WTG>Menu>Coverage)
        └─ else setDecisionSource(SATA)
                   │
                   ▼
StatefulAgent.resolveNewAction()  ──emits──▶  [APE-STEP] ... decision_source=<src>

SataAgent.tearDown()                              [Item #4 — coverage dump]
   └─ _coverageTracker.dump(mopReachFn)
        └─ for each tracked state:  [APE-RV] UICOV state=... mopReach=<0|1>
                  ▲
                  └─ mopReachFn(state) = _mopData != null && _mopData.activityHasMop(state.getActivity())
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `SataAgent.logActionSelected(action, type)` | Set `decisionSource` to the largest-boost mechanism on priority-consuming branches, else `SATA` | `Action`, `SataEventType` | side-effect: `ModelAction.decisionSource` |
| `StatefulAgent.resolveNewAction()` | Emit the single `[APE-STEP]` line (unchanged) | finalized `Action` | `[APE-STEP]` log line |
| `UICoverageTracker.dump(...)` | Emit one read-only `[APE-RV] UICOV` line per tracked state | `mopReach` predicate over `State` | `[APE-RV] UICOV` log lines |
| `SataAgent.tearDown()` | Invoke the dump at end of run | none | triggers dump |
| `MopData.activityHasMop(activity)` | Report whether an activity gates a MOP target | activity name | boolean (feeds `mopReach`) |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| `action-selection` MODIFIED "Per-action decision-source telemetry" | `SataAgent.logActionSelected()` boost-attribution rule (`SataAgent.java:218`) | Device run: assert `[APE-STEP]` lines show `decision_source=MOP` on a MOP-boosted EARLY_STAGE step |
| INV-SEL-04 (one line, fixed enum) | `StatefulAgent.resolveNewAction()` emit path (`:1266-1272`) unchanged | Device run: exactly one `[APE-STEP]` line per executed action |
| `ui-coverage` ADDED "UICoverageTracker — Coverage Dump" | `UICoverageTracker.dump()` over `stateData` + getters; called from `SataAgent.tearDown()` | Device run: one `[APE-RV] UICOV` line per tracked state with correct discovered/interacted/gap |
| INV-COV-07 (read-only, once per tracked state at teardown) | `dump()` reads only, mutates nothing; invoked once from `tearDown()` | Device run: `getTotalInteractions()` identical before and after dump |

## Goals / Non-Goals

**Goals:**
- Make `decision_source` reflect which boost mechanism most contributed to the chosen action, but only on branches that actually consumed priority.
- Emit a per-state UI-coverage line at teardown so per-screen exploration completeness is visible in the trace.
- Keep both changes additive to the trace; do not alter selection, scoring, or memory bounds.

**Non-Goals:**
- No counterfactual "decisiveness" computation (would the action still have been chosen without the boost?). Too expensive and not honest to claim from this rule.
- No new config flag. No reintroduction of `llmMaxCalls` (it does not exist and never will).
- No per-action coverage logging (no per-step spam); the dump is teardown-only (optionally also on LRU eviction).
- No new metric machinery beyond the existing tracker getters.

## Decisions

**D1 — Attribute by largest boost, only on priority-consuming branches.** The only branches that read `priority` are `EARLY_STAGE` and `EPSILON_GREEDY`. On other branches the boost did not influence the choice, so attributing to a mechanism there would be false. Restricting attribution to the two priority-consuming branches keeps the claim truthful. Alternative considered: attribute whenever any boost > 0 regardless of branch — rejected because `USE_BUFFER`/`TRIVIAL_ACTIVITY`/`SATURATED_STATE` ignore priority and the boost was irrelevant to selection there.

**D2 — Tie precedence MOP > WTG > Menu > Coverage.** When two mechanisms tie on the largest boost, prefer the mechanism that is most semantically specific to the steering goal (MOP first, generic coverage last). Deterministic and documented; avoids arbitrary enum-order ties.

**D3 — No counterfactual decisiveness.** The rule reports "which mechanism most boosted the chosen action on a priority-consuming branch", not "the boost changed the outcome". This is cheap (compare four ints) and does not overclaim. Documented in code comments per P4.

**D4 — Dump at teardown, computed `mopReach` at the call site.** `mopReach` needs `MopData`, which `UICoverageTracker` does not hold. Passing a predicate (or computing the flag at the `StatefulAgent`/`SataAgent` call site, which has `_mopData`) keeps the tracker decoupled from `MopData`. Alternative: inject `MopData` into the tracker — rejected as unnecessary coupling for one boolean (P1).

**D5 — No new config flag.** Both features are pure observability and run unconditionally. Adding a toggle would be speculative configuration (P1). The dump volume is bounded by the number of tracked states (`Config.coverageMaxStates`), so it cannot spam.

## API Design

### `SataAgent.logActionSelected(Action action, SataEventType type)`

- **Preconditions**: called from the SATA selection chain after an action is chosen; `action` may be null.
- **Behavior**:
  - If `action == null` or `!action.isModelAction()` → set nothing (unchanged); still `logEvent(type)`.
  - Else let `m = ((ModelAction) action)`. If `type ∈ {EARLY_STAGE, EPSILON_GREEDY}` and `max(m.getMopBoost(), m.getWtgBoost(), m.getMenuBoost(), m.getCoverageBoost()) > 0`, set `decisionSource` to the mechanism holding that maximum, resolving ties in order MOP, WTG, Menu, Coverage. Otherwise set `decisionSource = SATA`.
- **Postconditions**: `m.getDecisionSource()` is one of {SATA, MOP, WTG, Menu, Coverage}. No boost field is modified.
- **Errors**: none.

### `UICoverageTracker.dump(Function<State,Boolean> mopReach)` (or equivalent predicate)

- **Preconditions**: called once, at teardown; `mopReach` non-null. (An overload may also be called on LRU eviction for a single state.)
- **Behavior**: for each `State` in `stateData`, compute `discovered = totalRegistered(state)`, `interacted = distinct widgets with count > 0`, `gap = 1 - interacted/discovered` (or `1.0` when `discovered == 0`), a per-action-type breakdown `byType=Type:interacted/discovered,...` derived from the `"<xpath>|<TYPE>"` / `"<TYPE>"` element keys, and `mopReach.apply(state) ? 1 : 0`. Emit one line:
  `[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=Click:a/b,Edit:c/d,Button:e/f mopReach=<0|1>`
- **Postconditions**: `stateData`, `activityRollup`, and all counts are unchanged (read-only). One line per tracked state.
- **Errors**: none; null/empty states are skipped or reported with `gap=1.0`.

## Data Flow

1. During selection, `adjustActionsByGUITree()` (existing) sets per-mechanism boosts on candidate actions.
2. SATA chooses an action and calls `logActionSelected(action, type)`. The new rule sets `decisionSource` from the boosts when the branch is priority-consuming.
3. `resolveNewAction()` emits the `[APE-STEP]` line reading `getDecisionSource()` and the boosts (unchanged emit code).
4. At end of run, `tearDown()` calls the tracker dump. The dump reads `stateData` and, via the supplied predicate, `MopData.activityHasMop(state.getActivity())`, emitting one `UICOV` line per tracked state.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `action == null` in `logActionSelected` | SATA chain `NULL` branch | Skip attribution, keep existing `logEvent(type)` | none needed |
| `discovered == 0` for a state | tracker dump | Report `gap=1.0`, `byType` empty | none needed |
| `_mopData == null` | dump call site | `mopReach = 0` for all states | none needed |
| evicted state not in `stateData` at teardown | LRU eviction | Its counts already folded into `activityRollup`; optional eviction-time line covers it | none needed |

## Risks / Trade-offs

- [Attribution is contribution, not decisiveness] -> Documented in code and spec; the rule is explicitly scoped to priority-consuming branches and labeled as "largest contributing boost", never "decisive".
- [`byType` breakdown depends on parsing the `"<xpath>|<TYPE>"` key convention] -> The convention is owned by `UICoverageTracker.widgetId()` (`:215`); the dump derives the type from the same key, so the two stay in sync within one class.
- [Dump volume grows with tracked states] -> Bounded by `Config.coverageMaxStates` (the `stateData` bound), so worst case is one line per live entry at teardown.

## Testing Strategy

The project has no automated unit suite for agent runtime paths (Android runtime required); validation is on a real device against a MOP-bearing fixture (`test-apks/cryptoapp.apk` + `.apk.json`).

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Device run (attribution) | `decision_source` shows MOP/WTG/Menu/Coverage on boosted EARLY_STAGE/EPSILON_GREEDY steps; stays SATA on USE_BUFFER and on unboosted steps; tie precedence MOP>WTG>Menu>Coverage | Run `--ape sata` with `ape.mopDataPath` set; grep `[APE-STEP]` | ~4 checks |
| Device run (dump) | One `[APE-RV] UICOV` line per tracked state at teardown; gap matches discovered/interacted; `mopReach` matches `activityHasMop` | Run to teardown; grep `[APE-RV] UICOV` | ~3 checks |
| Invariant (INV-SEL-04) | Exactly one `[APE-STEP]` per executed action | Count lines vs executed steps | 1 check |
| Invariant (INV-COV-07) | `getTotalInteractions()` unchanged across dump | Compare before/after | 1 check |

## Open Questions

- Whether to also emit a `UICOV` line on LRU eviction (so a state evicted before teardown is still reported once). The spec permits it; default is teardown-only to avoid mid-run noise.
