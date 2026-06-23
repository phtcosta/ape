## Why

The MOP-guidance investigation (`docs/20260622_investigacao_mop.md` §7, items #3 and #4) found two observability blind spots that make the exploration loop hard to evaluate. First, `decision_source` in the `[APE-STEP]` trace is always `SATA` for SATA-chain selections (0 of 132,552 steps ever reported `MOP`), so a run cannot reveal when a MOP/WTG/Menu/Coverage boost actually shaped the chosen action. Second, there is no per-screen UI-coverage line in the trace at all, so the fraction of each state left unexercised at the end of a run is invisible. Both gaps obscure whether the steering mechanisms are doing anything, which is a prerequisite for the larger MOP-substrate work that follows.

## What Changes

- Attribute `decision_source` to the boost mechanism that most contributed to the chosen action, but only on priority-consuming SATA branches (`EARLY_STAGE`, `EPSILON_GREEDY`). When the chosen `ModelAction` carries any boost greater than 0, `SataAgent.logActionSelected()` sets `decisionSource` to the mechanism with the largest boost, with tie precedence MOP > WTG > Menu > Coverage; otherwise it keeps `SATA`. Priority-blind branches (`USE_BUFFER`, `TRIVIAL_ACTIVITY`, `SATURATED_STATE`, `NULL`) always stay `SATA`. This is honest contribution attribution, not a counterfactual decisiveness claim.
- Add a per-state UI-coverage dump emitted at agent teardown. For each tracked state, `UICoverageTracker` emits one `[APE-RV] UICOV` line carrying discovered/interacted widget counts, the coverage gap, a per-action-type breakdown, and a `mopReach` flag computed by the caller from `MopData.activityHasMop()`.
- No new configuration flag. No change to selection behavior, scoring, or memory bounds. The decision-source change is attribution-only; the coverage dump is read-only.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `action-selection`: Modify the existing requirement "Per-action decision-source telemetry" so that on priority-consuming SATA branches a boosted action is attributed to its largest-boost mechanism instead of unconditionally `SATA`. INV-SEL-04 (exactly one line, fixed enum) is preserved.
- `ui-coverage`: Add a requirement for a teardown coverage dump that emits one read-only line per tracked state, plus an invariant that the dump never alters counts and runs once per tracked state at teardown.

## Impact

- `src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java` — `logActionSelected()` (`:218`) gains the boost-attribution rule; `tearDown()` (`:234`) invokes the coverage dump.
- `src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java` — gains a read-only dump method over existing `stateData` / `activityRollup` / getters (`getTotalElements` `:290`, `getTotalInteractions` `:295`, `getInteractionCount`, `getCoverageGap`).
- `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java` — the dump call site reads `_mopData` (`:135`) and `_coverageTracker` (`:136`) to supply `mopReach`.
- `src/main/java/com/android/commands/monkey/ape/model/ModelAction.java` — read-only consumers of `getDecisionSource()` / `getMopBoost()` / `getWtgBoost()` / `getCoverageBoost()` / `getMenuBoost()`; no field changes.
- `src/main/java/com/android/commands/monkey/ape/utils/MopData.java` — read-only consumer of `activityHasMop()` (`:649`).
- Downstream trace consumers (the `.trace` analysis in rv-android) gain a populated `decision_source` distribution and a new `UICOV` line; no schema break since both are additive log content.
