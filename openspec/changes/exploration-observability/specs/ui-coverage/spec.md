## ADDED Requirements

### Requirement: UICoverageTracker — Coverage Dump

`UICoverageTracker` SHALL expose a read-only dump method that emits, at agent teardown, one `[APE-RV] UICOV` line per tracked state. The line SHALL report the per-state widget-level coverage already held in `stateData`, plus a `mopReach` flag indicating whether the state's Activity gates a MOP target. The dump exists so that per-screen exploration completeness — invisible in the trace today — becomes observable at the end of a run.

The dump SHALL be invoked from the agent teardown path (`SataAgent.tearDown()`, `SataAgent.java:234`, which holds `_coverageTracker` and `_mopData` via `StatefulAgent`). The `mopReach` value SHALL be computed at the call site as `_mopData != null && _mopData.activityHasMop(state.getActivity())` (`MopData.java:649`), because `UICoverageTracker` does not hold a `MopData` reference; the value SHALL be supplied to the dump (e.g. via a predicate over `State`).

For each tracked state the line format SHALL be:

```
[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=MODEL_CLICK:a/b,MODEL_LONG_CLICK:c/d mopReach=<0|1>
```

where `discovered` (`W`) is the count of registered widgets for the state, `interacted` (`D`) is the count of distinct registered widgets whose interaction count is greater than 0, `gap` is `1 - D/W` (or `1.0` when `W == 0`), and `mopReach` is `1` when the supplied predicate returns true for the state and `0` otherwise. `byType` is a per-action-type breakdown of `interacted/discovered`, where the type label is the `TYPE` segment of each element key — the `ActionType.name()` value (`MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_*`, …), parsed as the substring after the last `|` or the whole key when no `|` is present (the `widgetId()` convention, `UICoverageTracker.java:215`). The breakdown distinguishes **action types**, not widget classes: there is no `Edit` or `Button` action type — a clicked `EditText` and a clicked `Button` are both `MODEL_CLICK`. Only types actually registered for the state appear; the order follows `ActionType` declaration order.

The dump SHALL be read-only: it SHALL NOT register widgets, record interactions, evict entries, or mutate `stateData`, `activityRollup`, or any interaction count. It MAY additionally emit one line for a state at the moment that state is evicted from the bounded `stateData` (LRU eviction), so that states evicted before teardown are still reported once.

#### Scenario: State with partial coverage emits one line
- **WHEN** at teardown a tracked state `MainActivity` has 10 registered widgets and 3 distinct interacted widgets
- **THEN** exactly one `[APE-RV] UICOV` line SHALL be emitted for that state
- **AND** it SHALL report `discovered=10 interacted=3 gap=0.7`

#### Scenario: Fully unexplored state reports gap 1.0
- **WHEN** a tracked state has 8 registered widgets and 0 interactions
- **THEN** its `[APE-RV] UICOV` line SHALL report `discovered=8 interacted=0 gap=1.0`

#### Scenario: byType breakdown reflects per-action-type coverage
- **WHEN** a state registered 3 `MODEL_CLICK` actions (2 interacted) and 1 `MODEL_LONG_CLICK` action (0 interacted)
- **THEN** the line SHALL include `byType=MODEL_CLICK:2/3,MODEL_LONG_CLICK:0/1`

#### Scenario: mopReach reflects activityHasMop
- **WHEN** the supplied predicate returns true for the state's Activity (i.e. `_mopData != null && _mopData.activityHasMop(activity)`)
- **THEN** the line SHALL report `mopReach=1`
- **AND** when `_mopData` is null or the Activity gates no MOP target the line SHALL report `mopReach=0`

#### Scenario: One line per tracked state at teardown
- **WHEN** `stateData` holds 5 tracked states at teardown
- **THEN** the dump SHALL emit exactly 5 `[APE-RV] UICOV` lines, one per tracked state

#### Scenario: Dump does not alter counts
- **WHEN** the dump runs at teardown
- **THEN** `getTotalElements()` and `getTotalInteractions()` SHALL return the same values immediately before and immediately after the dump

## Invariants

- **INV-COV-07**: The coverage dump SHALL be read-only and SHALL run at most once per tracked state at teardown (with an optional additional emission at LRU eviction for that state). It SHALL NOT alter `stateData`, `activityRollup`, registered-widget sets, or any interaction count; `getTotalElements()` and `getTotalInteractions()` SHALL be invariant across the dump.
