## Purpose

APE-RV's exploration engine tracks action visits (per `ModelAction.visitedCount`) but lacks a per-state widget-level coverage metric. When the agent enters a state with 20 interactable widgets and tests only 5 before navigating away, there is no signal that 75% of the state remains unexplored. The `UICoverageTracker` fills this gap by maintaining a registry of all interactable widgets per state and recording which have been interacted with. The resulting coverage gap metric (fraction of untested widgets, 0.0-1.0) feeds into `StatefulAgent.adjustActionsByGUITree()` as a per-action priority boost, directing the agent toward untested widgets within underexplored states.

### State Identification

The tracker keys coverage data by `State` object identity. Each `State` in APE-RV is uniquely identified by its `StateKey` (activity + naming level + widget Name array). Using the `State` object directly as the map key leverages the existing `equals()`/`hashCode()` contract, which delegates to `StateKey`. This is consistent with how the rest of the system identifies states and avoids inventing a parallel identification scheme.

No `getStructuralHash()` method exists or is needed — the `State` object IS the key.

### Naming Refinement Behavior

When NamingFactory refines a State (splits it into finer-grained States), the UICoverageTracker naturally loses coverage data for the old State because the new State has a different StateKey (different Naming + different widgets[]). This is **by design**, not a bug:

- Refinement means the old abstraction was too coarse (e.g., 10 aliased nodes collapsed into 1 action)
- The new State has more distinct actions to test
- Coverage gap resets to 1.0 for the new State, forcing re-exploration
- This is correct in the CEGAR paradigm — discovery of finer structure requires re-exploration

### Infinite Scroll Handling

For infinite scroll screens (RecyclerView loading items on demand), each scroll may reveal new items with new Names, creating new States. Coverage gap never converges to 0 because new widgets keep appearing. This is handled by the `ActivityBudgetTracker`: after N interactions in the activity, budget exhausts and forces navigation elsewhere. The `activityStableRestartThreshold=200` provides a secondary safety net.

### Widget Identification

Widgets are identified by `Name.toXPath()` — the same abstraction the rest of APE-RV uses to identify widgets. This is superior to a coordinate-based scheme (`coords:centerX,centerY`) because:

1. **Scroll-stable**: When content scrolls, `getBoundsInScreen()` changes but `Name` remains the same. A coordinate-based ID would re-register the same widget as "new" after every scroll, causing the coverage gap to oscillate instead of converge (violating INV-COV-02).
2. **Consistent with the model**: APE-RV already abstracts widgets via `Name` (text, class, index, resourceId combinations). Using a different identification scheme in the tracker creates a parallel, disconnected abstraction that can disagree with the model.
3. **1:1 with ModelAction targets**: Each `ModelAction` with a target has `action.getTarget()` returning a `Name`. Using `Name.toXPath()` as the widget ID means the tracker and the action selection operate on the same vocabulary.

For non-targeted actions (BACK, MENU, scroll), the tracker uses the `ActionType` name as the widget ID (e.g., `"MODEL_BACK"`).

### Coverage Boost Design

The coverage boost is **per-action**, not uniform. Each action receives a boost proportional to whether its specific target widget has been tested. An action whose widget has never been interacted with receives the full boost; an action whose widget has been extensively tested receives zero. This ensures the priority distribution steers toward untested widgets, not just toward underexplored states uniformly.

## Data Contracts

### Input
- `state: State` — the current State object (source: `StatefulAgent.newState`)
- `widgets: List<GUITreeNode>` — interactable widgets in the current GUITree (source: `GUITree` nodes where `clickable || longClickable || scrollable > 0`)
- `action: ModelAction` — the action whose widget was interacted with (source: resolved `ModelAction` from `moveForward()`)

### Output
- `coverageGap: float` — fraction of registered widgets NOT yet interacted with, range [0.0, 1.0] (consumer: `adjustActionsByGUITree()` for dynamic epsilon, `selectNewActionNonnull()` for navigation decisions)
- `getInteractionCount(state, widgetId): int` — how many times a specific widget was interacted with in a state (consumer: `adjustActionsByGUITree()` for per-action boost)
- `totalElements: int` — count of unique widgets registered across all states (consumer: logging/telemetry)
- `totalInteractions: int` — sum of all interaction counts (consumer: logging/telemetry)

### Side-Effects
- **[Memory]**: `stateData` is bounded to `Config.coverageMaxStates` live entries; the least-recently-updated entries are evicted after their counts are folded into the per-Activity rollup (INV-COV-05), so memory is bounded without zeroing reported coverage.

### Error
- None. All methods are null-safe and return default values for unknown states.

## Invariants

- **INV-COV-01**: `getCoverageGap(state)` SHALL return a value in the closed interval [0.0, 1.0].
- **INV-COV-02**: For a given state, the coverage gap SHALL monotonically decrease (or remain equal) as interactions are recorded. It SHALL NOT increase unless new widgets are registered for the same state.
- **INV-COV-03**: `getCoverageGap(unknownState)` SHALL return 1.0 for any state that has not been registered.
- **INV-COV-04**: `widgetId(action)` SHALL return a non-null, non-empty string for any non-null `ModelAction`.
- **INV-COV-05**: `stateData` SHALL be bounded. When the bound is reached, the tracker SHALL evict entries but SHALL preserve the per-Activity rollup so that already-counted coverage is not zeroed mid-run.
- **INV-COV-06**: The coverage key for a target action SHALL incorporate the action type, so two distinct action types on the same target widget are tracked as distinct coverage elements.
- **INV-COV-07**: The coverage dump SHALL be read-only and SHALL run at most once per tracked state at teardown (with an optional additional emission at LRU eviction for that state). It SHALL NOT alter `stateData`, `activityRollup`, registered-widget sets, or any interaction count; `getTotalElements()` and `getTotalInteractions()` SHALL be invariant across the dump.
- **INV-COV-10**: The teardown coverage dump SHALL be emitted exactly once per run, strictly before every other teardown step that writes output (re-anchored, not dissolved: the pre-change phrasing bound it to the `saveGraph` model-serialization step, which no longer exists, so the binding is now to chain position among writers — today the action-history save); the emission SHALL be read-only (INV-COV-07 applies). It has exactly one call site, so no cross-path idempotence guard is required. This invariant governs the teardown dump only; it makes no claim about the optional per-state emission at LRU eviction that INV-COV-07 and "UICoverageTracker — Coverage Dump" permit.
## Requirements
### Requirement: UICoverageTracker — Widget Registration

`UICoverageTracker.registerScreenElements(State state, List<ModelAction> actions)` SHALL register all actions of the state as trackable widgets. The widget ID for each action SHALL incorporate the action type for ALL actions:
- For actions with a target (`requireTarget() == true`): `action.getTarget().toXPath() + "|" + action.getType().name()`
- For actions without a target (BACK, MENU): `action.getType().name()`

Including the action type for target actions ensures distinct action types on the same target widget are tracked separately (INV-COV-06). Registration is idempotent — re-registering the same state replaces the widget set while preserving prior interaction counts for widgets still present.

#### Scenario: Register distinct action types on the same target
- **WHEN** `registerScreenElements(stateA, actions)` is called for a state where the same `RecyclerView` target has both `MODEL_SCROLL_DOWN` and `MODEL_SCROLL_UP` actions
- **THEN** the state SHALL have two distinct registered elements
- **AND** their IDs SHALL be `"<xpath>|MODEL_SCROLL_DOWN"` and `"<xpath>|MODEL_SCROLL_UP"`

#### Scenario: Non-target action keyed by type
- **WHEN** the state has a `MODEL_BACK` action
- **THEN** its element ID SHALL be `"MODEL_BACK"`

#### Scenario: Re-register preserves counts for present widgets
- **WHEN** `registerScreenElements(stateA, newActions)` is called after a previous registration
- **THEN** widgets still present SHALL retain their prior interaction counts
- **AND** widgets no longer present SHALL be dropped

### Requirement: Per-Activity coverage aggregation for reporting

Coverage reporting SHALL aggregate per Activity, collapsing the `naming`-level fragments that `StateKey` (`StateKey.java:47,57`) produces for one Activity. The reported coverage for an Activity SHALL be computed over the union of its fragments' registered widgets and interactions, so that finer NamingFactory refinement does not inflate the reported gap. The per-`State` tracking used for steering MAY remain fragment-level; only the reported metric aggregates.

#### Scenario: Fragments of one Activity report as one
- **WHEN** `MainActivity` has fragmented into 4 `StateKey`s via naming refinement
- **THEN** the reported coverage for `MainActivity` SHALL aggregate all 4 fragments' widgets and interactions
- **AND** the reported gap SHALL NOT count the same Activity four times

### Requirement: Bounded stateData

`UICoverageTracker.stateData` SHALL be bounded to a configurable maximum number of entries. When the bound is exceeded, the tracker SHALL evict the least-recently-updated entries, after folding their coverage into the per-Activity rollup so that eviction does not zero already-counted coverage mid-run (INV-COV-05). This replaces the current unbounded growth (`StatefulAgent.java:163`, no prune).

#### Scenario: Eviction preserves the rollup
- **WHEN** `stateData` reaches its bound and an entry is evicted
- **THEN** the evicted entry's interaction counts SHALL already be folded into the Activity rollup
- **AND** the reported per-Activity coverage SHALL NOT decrease due to the eviction

#### Scenario: Bound configurable
- **WHEN** the configured bound is `N`
- **THEN** `stateData` SHALL hold at most `N` live entries at any time

### Requirement: UICoverageTracker — Interaction Recording

`UICoverageTracker.recordInteraction(State state, ModelAction action)` SHALL increment the interaction count for the widget corresponding to the given action. The widget ID is derived from the action using the same scheme as registration.

#### Scenario: Record first interaction
- **WHEN** `recordInteraction(stateA, clickOkAction)` is called for the first time
- **THEN** the interaction count for `stateA + "//Button[@text=\"OK\"]"` SHALL be 1

#### Scenario: Record repeated interaction
- **WHEN** `recordInteraction(stateA, clickOkAction)` is called 3 times
- **THEN** the interaction count SHALL be 3

### Requirement: UICoverageTracker — Coverage Gap Computation

`UICoverageTracker.getCoverageGap(State state)` SHALL return the fraction of registered widgets that have NOT been interacted with at least once. The formula is: `1.0 - (interactedCount / totalRegistered)`.

#### Scenario: No interactions yet
- **WHEN** state has 10 registered widgets and 0 interactions
- **THEN** `getCoverageGap(state)` SHALL return 1.0

#### Scenario: Partial coverage
- **WHEN** state has 10 registered widgets and 3 distinct widgets interacted with
- **THEN** `getCoverageGap(state)` SHALL return 0.7

#### Scenario: Full coverage
- **WHEN** all 10 registered widgets have been interacted with at least once
- **THEN** `getCoverageGap(state)` SHALL return 0.0

#### Scenario: Unknown state
- **WHEN** `getCoverageGap(unknownState)` is called for an unregistered state
- **THEN** the result SHALL be 1.0

### Requirement: UICoverageTracker — Per-Widget Interaction Count

`UICoverageTracker.getInteractionCount(State state, String widgetId)` SHALL return the number of times the specific widget has been interacted with in the given state. Returns 0 for unknown state/widget combinations.

#### Scenario: Query interaction count
- **WHEN** widget "//Button[@text=\"OK\"]" in stateA has been interacted with 5 times
- **THEN** `getInteractionCount(stateA, "//Button[@text=\"OK\"]")` SHALL return 5

#### Scenario: Unknown widget
- **WHEN** widget "//Button[@text=\"Cancel\"]" has never been interacted with
- **THEN** `getInteractionCount(stateA, "//Button[@text=\"Cancel\"]")` SHALL return 0

### Requirement: Coverage Boost in Action Scoring — Per-Action

`StatefulAgent.adjustActionsByGUITree()` SHALL include a coverage boost pass after the WTG scoring pass. For each valid, target-requiring, resolved action, the boost SHALL be computed per-action based on whether the specific widget has been tested anywhere in the current Activity:

```
widgetId = UICoverageTracker.widgetId(action)
interacted = _coverageTracker.hasActivityInteraction(newState.getActivity(), widgetId)
stateVisits = newState.getVisitedCount()
if (!interacted) {
    boost = Config.coverageBoostWeight / (1 + stateVisits / 5)
} else {
    boost = 0
}
action.setPriority(action.getPriority() + boost)
```

This ensures widgets untested anywhere in the Activity receive the boost while widgets already tested — in this abstract state or any sibling fragment of the same Activity — receive zero. The differential therefore survives NamingFactory refinement: a fragment split does not re-arm the boost for already-exercised widgets.

#### Scenario: Untested widget in new state receives full boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=0, `Config.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 0/5) = 100

#### Scenario: Widget tested in a sibling fragment receives no boost
- **WHEN** widget X was interacted with in fragment S1 of `MainActivity`, and the agent now scores actions in fragment S2 of `MainActivity` where X is registered but locally untested
- **THEN** X's boost in S2 SHALL be 0

#### Scenario: Untested widget in visited state receives decayed boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=10, `Config.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 10/5) = 100 / 3 = 33

#### Scenario: Untested widget in heavily visited state receives minimal boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=25, `Config.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 25/5) = 100 / 6 = 16

#### Scenario: Tested widget receives no boost regardless of state visits
- **WHEN** widget has been interacted somewhere in the Activity (`hasActivityInteraction`=true)
- **THEN** boost = 0 (regardless of stateVisits)

**Rationale**: Without decay, the coverage boost traps the agent in complex states with many widgets (e.g., SettingsActivity with 27 widgets). E2E testing on 20 APKs showed simplenotes regressing -12.56pp because the agent spent all time in SettingsActivity exploring color pickers instead of visiting other activities. The decay formula `weight / (1 + stateVisits / 5)` provides full boost on first visit and diminishes to ~16% after 25 visits, allowing the agent to explore elsewhere. The decay is deliberately kept state-scoped while the novelty test is activity-scoped: decay protects against dwelling, novelty must survive fragmentation.

#### Scenario: Coverage boost disabled
- **WHEN** `Config.coverageBoostWeight` is 0
- **THEN** no coverage boost SHALL be applied to any action

### Requirement: Config Flag for Coverage Boost

`Config.java` SHALL declare the following flag:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `coverageBoostWeight` | `ape.coverageBoostWeight` | int | 100 | Priority boost for untested widgets (0 = disabled) |

#### Scenario: Config flag loaded
- **WHEN** `ape.properties` contains `ape.coverageBoostWeight=150`
- **THEN** `Config.coverageBoostWeight` SHALL be 150

#### Scenario: Default value
- **WHEN** `ape.coverageBoostWeight` is not set in properties
- **THEN** `Config.coverageBoostWeight` SHALL be 100

---

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

---

### Requirement: UICoverageTracker — Activity-Scoped Interaction Membership

`UICoverageTracker` SHALL expose `hasActivityInteraction(activity, widgetId)` returning whether the widget key has been interacted with at least once anywhere in the Activity. The lookup SHALL be O(1) — a `Set` membership test, with no `stateData` scan and no rollup consultation: `recordInteraction` SHALL add the widget key to a cumulative `activity → {widgetId}` set keyed by the state's Activity, and this update SHALL occur before the unregistered-state early return (or in both branches) so first-touch interactions on not-yet-registered states are captured. The set is a superset of any interaction later folded into the eviction rollup, so the read needs no rollup consultation.

- **INV-COV-09**: `hasActivityInteraction(a, w)` SHALL be monotonic within a run (never reset, not subject to state eviction) and SHALL be true from the first `recordInteraction` of `w` in any fragment of Activity `a` onward. Consequently the coverage boost SHALL never re-fire for a widget already interacted anywhere in `a` — including after NamingFactory refinement mints sibling fragments and after state eviction followed by revisit (which resets per-state counts).

#### Scenario: interaction visible across fragments
- **WHEN** widget X is interacted with in fragment state S1 of `MainActivity`
- **THEN** `hasActivityInteraction("MainActivity", X)` SHALL be true
- **AND** it SHALL remain true after S1 is LRU-evicted

#### Scenario: unknown widget
- **WHEN** widget Y was never interacted with anywhere in `MainActivity`
- **THEN** `hasActivityInteraction("MainActivity", Y)` SHALL be false

---

### Requirement: UICoverageTracker — Per-Activity Coverage Dump

The teardown dump SHALL additionally emit one `[APE-RV] UICOV-ACT` line per Activity that has at least one tracked fragment (live `stateData` entry or eviction-rollup entry). The `UICOV-ACT` lines SHALL be emitted in lexicographic activity-name order (the grouped activity keys sorted before emission) so the output is deterministic across runs. The aggregation SHALL be the one "Per-Activity coverage aggregation for reporting" defines: the union of the Activity's fragments' widget keys, with each widget's interaction count taken as the maximum across fragments and rollup (`mergeMax`). The line format SHALL be:

```
[APE-RV] UICOV-ACT activity=<activity> discovered=<W> interacted=<D> gap=<1-D/W> byType=<TYPE:d/w,...> liveStates=<liveFragmentCount>
```

where `discovered`/`interacted`/`gap`/`byType` follow the per-state `UICOV` line conventions (same key/type-segment parsing, `Locale.ROOT` gap formatting) computed over the aggregated map, and `liveStates` is the number of live (non-evicted) fragments of that Activity grouped into the line at teardown. Activities present only in the eviction rollup report `liveStates=0`; the historical fold count is not tracked (by design, `activityRollup` stores only per-widget maxima). The per-state `UICOV` lines SHALL remain unchanged and SHALL be emitted alongside.

The aggregated dump SHALL be read-only: no registration, recording, eviction, or mutation of `stateData`, `activityRollup`, or interaction counts (same constraint as the per-state dump, INV-COV-07).

- **INV-COV-08**: For every Activity line, `discovered` SHALL equal the size of the union of widget keys across that Activity's live fragments and its rollup entry, and `interacted` SHALL equal the number of keys in that union whose merged (`mergeMax`) count is greater than 0.

#### Scenario: fragments collapse into one activity line
- **WHEN** `MainActivity` fragmented into 3 states registering the same 10 widget keys, with widget X interacted only in fragment 2
- **THEN** exactly one `UICOV-ACT activity=MainActivity` line SHALL be emitted
- **AND** it SHALL report `discovered=10` (union, not 30) with X counted as interacted

#### Scenario: evicted fragments still counted
- **WHEN** a fragment of `SettingsActivity` was LRU-evicted mid-run (its counts folded into `activityRollup`) and one live fragment remains
- **THEN** the `UICOV-ACT` line for `SettingsActivity` SHALL aggregate the rollup and the live fragment

#### Scenario: per-state lines unchanged
- **WHEN** the teardown dump runs
- **THEN** every per-state `[APE-RV] UICOV` line SHALL be emitted exactly as specified by the coverage-dump requirement, in addition to the `UICOV-ACT` lines

#### Scenario: rollup-only activity reports liveStates=0
- **WHEN** every fragment of `LoginActivity` was LRU-evicted mid-run (all its counts folded into `activityRollup`) and no live fragment remains at teardown
- **THEN** exactly one `UICOV-ACT activity=LoginActivity` line SHALL be emitted, sourced purely from the rollup
- **AND** it SHALL report `liveStates=0`

#### Scenario: activity lines emitted in deterministic order
- **WHEN** the teardown dump aggregates multiple Activities
- **THEN** the `UICOV-ACT` lines SHALL be emitted in lexicographic activity-name order, identical across runs with the same tracked data

### Requirement: Coverage Dump Emitted First Among Teardown Writers

The coverage dump (the per-state `[APE-RV] UICOV` and per-Activity `[APE-RV] UICOV-ACT` emissions specified by "UICoverageTracker — Coverage Dump" and "UICoverageTracker — Per-Activity Coverage Dump") SHALL be emitted **before any other teardown step that produces output**.

The boundary is "first among writers", not a fixed index. Before this change the boundary was the model serialization step (`saveGraph`), which this change deletes together with `sataModel.obj`, `readGraph` and `--ape-model` (V14, finding 3.3-6; R1/R3 — clean runs, no read-back). The chain after this change is `flushPendingStep → superTearDown → coverage dump → action counters → activity nodes → naming dump → model counters → runEnd` (this change's `exploration` delta, INV-EXPL-29): the dump still lands third, and the first writer it must precede is now the `actionCounters` free-text dump. **`flushPendingStep` precedes the dump and is deliberately outside the boundary.** It writes one already-serialized `StepRecord` line to stdout — not an artifact, and not an expensive one — and it runs first for the same reason the dump runs early: it is loss-bounding, closing the record that a SIGKILL would otherwise take (`event-sink` INV-SNK-08). Ordering two loss-bounding emissions against each other would protect nothing; the boundary this requirement states is against the steps that *cost* something to produce. Stating the requirement on "first among writers" keeps it aligned with what recovers the lost dumps and with what the tests assert, and keeps it verifiable as later stages remove further steps from the chain — a requirement written on a chain index would break at stage 4.

Before the fix that introduced this requirement, the dump was the **last** step: `StatefulAgent.tearDown()` ran its full chain and only then did `SataAgent.tearDown()` reach `getCoverageTracker().dump(...)`. The most fragile item was emitted after the most expensive write.

The consequence is measured: **338 of the 800 `aperv` calibration runs (42.3%) carry no dump**, and **330 of those 338 end on the line `Save graph data to /sdcard/sata-…`** — cut *during* the `/sdcard` serialization, three steps before the dump would have run. A further 3 are cut after that write but before the dump. Emitting the dump first recovers **333 of the 338**. This measurement is the historical justification for the ordering and is retained as the record of why the requirement exists. **Both writes it names are now gone**: `rearch-02-runspec` deleted the model serialization and this change deletes the action-history save, so after this stage **no teardown step writes a file at all** — every surviving step emits free text or a sink record to stdout. The ordering is kept rather than dissolved because what it protects is not specific to `/sdcard`: the dump is the run's most fragile output, it is worth more than the counters and dumps that follow it, and a mid-teardown cut should cost those and not it. The requirement therefore keeps its subject and moves its boundary for the second time — from the serialization step, to the action-history save, to the first free-text dump — which is exactly the portability the "first among writers" phrasing was chosen for.

**Why not a shutdown hook.** An idempotent JVM shutdown hook was the original design for this requirement and is explicitly **not** adopted, because on the measured failure path it recovers nothing. The constraint is not which signal reaches the JVM — it is that the destination is already gone. `Logger` writes only to `System.out`; the trace file is the host-side `adb` stdout opened by the harness; on timeout the harness sends `SIGKILL` to the `adb` process and closes that file. Anything the device process emits afterwards — from a hook or otherwise, and regardless of whether the device itself received `SIGTERM` — cannot reach the trace. There is no second sink: the `.logcat` sibling of a lossy run contains zero `APE` lines. A requirement written on the signal axis ("orderly termination yes, SIGKILL no") would describe a boundary that is not the operative one.

**Scope of the guarantee, stated honestly.** This requirement recovers runs whose teardown *began*. It does not recover a run killed before teardown — in the calibration corpus, 5 of the 338. No teardown-side mechanism can reach those, and this specification does not claim otherwise.

**Partial dumps are valid output.** Emitting first does not make the dump atomic: 3 of the 462 runs that do dump today are truncated mid-`UICOV-ACT`. A consumer SHALL treat a truncated final line as a partial dump of an otherwise valid run, not as a corrupt run, and SHALL NOT discard the complete lines that precede it.

The dump remains read-only with respect to tracker state (INV-COV-07). Because the teardown dump has exactly one call site, no idempotence flag is required. This says nothing about the optional per-state emission at LRU eviction that "UICoverageTracker — Coverage Dump" permits (`MAY additionally emit one line for a state at the moment that state is evicted`): that emission is a distinct, mid-run, per-state path, it is not part of the teardown dump this requirement orders, and it is not currently implemented.

#### Scenario: dump precedes model serialization

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` and `[APE-RV] UICOV-ACT` lines SHALL appear in the trace before any output produced by a later step of the teardown chain — both writers this scenario has ordered against in turn (the model serialization, then the action-history save) are deleted, so the boundary is now the first *surviving* producer of output, the `actionCounters` dump
- **AND** the `flushPendingStep` record written before the dump SHALL NOT count against the boundary (it is loss-bounding, not an artifact write)
- **AND** the dump SHALL be read-only (tracker counts unchanged by the emission)

#### Scenario: run cut during model serialization still has its dump

- **WHEN** a run is cut short after teardown has begun
- **THEN** the dump SHALL already be in the trace, because it precedes every writer of the chain
- **AND** the specific window this scenario was written for SHALL no longer exist: no `Save graph data to …` line, `sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js` or `action-history.log` is produced, so there is no file write left in the chain to be cut during. The protection strengthens rather than lapses — every write the dump used to race is gone, and the ordering still holds against whatever produces output first

#### Scenario: run killed before teardown has no dump

- **WHEN** the process is terminated before the teardown chain begins
- **THEN** no dump is emitted (documented limitation — the emission point was never reached; this is not a defect of the mechanism)

#### Scenario: truncated dump is a partial dump, not a corrupt run

- **WHEN** the capture ends mid-way through the `UICOV-ACT` block, leaving an incomplete final line
- **THEN** the run SHALL be treated as carrying a partial dump
- **AND** every complete line preceding the truncation SHALL remain usable

