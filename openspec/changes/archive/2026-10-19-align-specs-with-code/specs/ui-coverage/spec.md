## Purpose

APE-RV's exploration engine tracks action visits (per `ModelAction.visitedCount`) but lacks a per-state widget-level coverage metric. When the agent enters a state with 20 interactable widgets and tests only 5 before navigating away, there is no signal that 75% of the state remains unexplored. The `UICoverageTracker` fills this gap by maintaining a registry of all interactable widgets per state and recording which have been interacted with. The resulting coverage gap metric (fraction of untested widgets, 0.0-1.0) feeds two ADDONS members: `CoveragePass` (`COVERAGE_BOOST`), the scoring pass that `StatefulAgent.adjustActionsByGUITree()` runs as a per-action priority boost directing the agent toward untested widgets, and the dynamic epsilon (`DYNAMIC_EPSILON`, `SataAgent.computeDynamicEpsilon()`). The tracker itself, its visit and coverage counts, and the teardown coverage dump (`[APE-RV] UICOV`, one line per tracked state) are not MOP and exist in every cell. The dump's last token, `mopReach`, is computed from the agent's `MopData` and is written only when `MOP` is mounted (`getMopData()` is null otherwise, even when the artifact was loaded for STATIC; design D-B14.20, D-B14.21), consistent with `ACT.mop`; the clean-control gate (`event-sink` INV-SNK-18) counts it as a MOP signal.

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

For infinite scroll screens (RecyclerView loading items on demand), each scroll may reveal new items with new Names, creating new States. Coverage gap never converges to 0 because new widgets keep appearing. When `ACTIVITY_BUDGET` (ADDONS) is in the plan, this is handled by the `ActivityBudgetTracker`: after N interactions in the activity, budget exhausts and forces navigation elsewhere. `ape.activityStableRestartThreshold` (`ACTIVITY_STABLE_RESTART`, default 200) provides a secondary safety net.

### Widget Identification

Widgets are identified by `Name.toXPath()` — the same abstraction the rest of APE-RV uses to identify widgets — suffixed with the action type: `UICoverageTracker.widgetId` returns `<xpath>|<ActionType.name()>` for a target action (INV-COV-06). This is superior to a coordinate-based scheme (`coords:centerX,centerY`) because:

1. **Scroll-stable**: When content scrolls, `getBoundsInScreen()` changes but `Name` remains the same. A coordinate-based ID would re-register the same widget as "new" after every scroll, causing the coverage gap to oscillate instead of converge (violating INV-COV-02).
2. **Consistent with the model**: APE-RV already abstracts widgets via `Name` (text, class, index, resourceId combinations). Using a different identification scheme in the tracker creates a parallel, disconnected abstraction that can disagree with the model.
3. **1:1 with ModelAction targets**: Each `ModelAction` with a target has `action.getTarget()` returning a `Name`. Using `Name.toXPath()` as the widget ID means the tracker and the action selection operate on the same vocabulary.

For targetless actions (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`, and any action whose target is null), the tracker uses the `ActionType` name alone as the widget ID (e.g., `"MODEL_BACK"`). Scroll actions require a target, so they are keyed `<xpath>|MODEL_SCROLL_TOP_DOWN` and so on.

### Coverage Boost Design

The coverage boost is **per-action**, not uniform. Each action receives a boost proportional to whether its specific target widget has been tested. An action whose widget has never been interacted with receives the full boost; an action whose widget has been extensively tested receives zero. This ensures the priority distribution steers toward untested widgets, not just toward underexplored states uniformly.

## Invariants

- **INV-COV-10**: The teardown coverage dump SHALL be emitted exactly once per run, strictly before every other teardown step that writes output. The binding is to chain position among writers: in `StatefulAgent.tearDown` the chain is `flushPendingStep → superTearDown → coverageDump → actionCounters → activityNodes → namingDump → modelCounters → runEnd`, so the boundary today is the `actionCounters` dump (no teardown step writes a file any more). `flushPendingStep` precedes it and is outside the boundary (it writes one already-serialized `StepRecord`, `event-sink` INV-SNK-08). The emission SHALL be read-only (INV-COV-07 applies). It has exactly one call site, the `coverageDump` step, so no cross-path idempotence guard is required. This invariant governs the teardown dump only; it makes no claim about the optional per-state emission at LRU eviction that INV-COV-07 and "UICoverageTracker — Coverage Dump" permit.

## MODIFIED Requirements

### Requirement: UICoverageTracker — Widget Registration

`UICoverageTracker.registerScreenElements(State state, List<ModelAction> actions)` SHALL register all actions of the state as trackable widgets. The widget ID for each action SHALL incorporate the action type for ALL actions:
- For actions with a target (`requireTarget() == true` and a non-null target): `action.getTarget().toXPath() + "|" + action.getType().name()`
- For every other action (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`): `action.getType().name()`

Including the action type for target actions ensures distinct action types on the same target widget are tracked separately (INV-COV-06). Registration is idempotent — re-registering the same state replaces the widget set while preserving prior interaction counts for widgets still present.

#### Scenario: Register distinct action types on the same target
- **WHEN** `registerScreenElements(stateA, actions)` is called for a state where the same `RecyclerView` target has both `MODEL_SCROLL_TOP_DOWN` and `MODEL_SCROLL_BOTTOM_UP` actions
- **THEN** the state SHALL have two distinct registered elements
- **AND** their IDs SHALL be `"<xpath>|MODEL_SCROLL_TOP_DOWN"` and `"<xpath>|MODEL_SCROLL_BOTTOM_UP"`

#### Scenario: Non-target action keyed by type
- **WHEN** the state has a `MODEL_BACK` action
- **THEN** its element ID SHALL be `"MODEL_BACK"`

#### Scenario: Re-register preserves counts for present widgets
- **WHEN** `registerScreenElements(stateA, newActions)` is called after a previous registration
- **THEN** widgets still present SHALL retain their prior interaction counts
- **AND** widgets no longer present SHALL be dropped

### Requirement: Per-Activity coverage aggregation for reporting

Coverage reporting SHALL aggregate per Activity, collapsing the `naming`-level fragments that `StateKey` (activity + naming + widgets) produces for one Activity. The reported coverage for an Activity SHALL be computed over the union of its fragments' registered widgets and interactions, so that finer NamingFactory refinement does not inflate the reported gap. The per-`State` tracking used for steering MAY remain fragment-level; only the reported metric aggregates.

#### Scenario: Fragments of one Activity report as one
- **WHEN** `MainActivity` has fragmented into 4 `StateKey`s via naming refinement
- **THEN** the reported coverage for `MainActivity` SHALL aggregate all 4 fragments' widgets and interactions
- **AND** the reported gap SHALL NOT count the same Activity four times

### Requirement: Bounded stateData

`UICoverageTracker.stateData` SHALL be bounded to a configurable maximum number of entries (`Config.coverageMaxStates`, plan key `ape.coverageMaxStates` owned by `COVERAGE_BOOST`, default 2000), as an access-ordered map whose eldest entry is removed once its size exceeds the bound. When the bound is exceeded, the tracker SHALL evict the least-recently-updated entries, after folding their coverage into the per-Activity rollup so that eviction does not zero already-counted coverage mid-run (INV-COV-05).

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
- **THEN** the interaction count for widget `"//Button[@text=\"OK\"]|MODEL_CLICK"` in `stateA` SHALL be 1

#### Scenario: Record repeated interaction
- **WHEN** `recordInteraction(stateA, clickOkAction)` is called 3 times
- **THEN** the interaction count SHALL be 3

### Requirement: UICoverageTracker — Per-Widget Interaction Count

`UICoverageTracker.getInteractionCount(State state, String widgetId)` SHALL return the number of times the specific widget has been interacted with in the given state. Returns 0 for unknown state/widget combinations.

#### Scenario: Query interaction count
- **WHEN** widget `"//Button[@text=\"OK\"]|MODEL_CLICK"` in stateA has been interacted with 5 times
- **THEN** `getInteractionCount(stateA, "//Button[@text=\"OK\"]|MODEL_CLICK")` SHALL return 5

#### Scenario: Unknown widget
- **WHEN** widget `"//Button[@text=\"Cancel\"]|MODEL_CLICK"` has never been interacted with
- **THEN** `getInteractionCount(stateA, "//Button[@text=\"Cancel\"]|MODEL_CLICK")` SHALL return 0

### Requirement: Coverage Boost in Action Scoring — Per-Action

`StatefulAgent.adjustActionsByGUITree()` runs the `ScoringPipeline`, in which `CoveragePass` (mounted iff `ape.coverageBoostWeight` is non-zero) follows `WtgPass`. For each valid, target-requiring, resolved action, the boost SHALL be computed per-action based on whether the specific widget has been tested anywhere in the current Activity:

```
widgetId = UICoverageTracker.widgetId(action)
interacted = coverageTracker.hasActivityInteraction(state.getActivity(), widgetId)
stateVisits = state.getVisitedCount()
boost = coverageBoostWeight / (1 + stateVisits / 5)   // integer division
if (!interacted && boost > 0) {
    action.setPriority(action.getPriority() + boost)
    action.setCoverageBoost(boost)   // dec.cov
}
```

This ensures widgets untested anywhere in the Activity receive the boost while widgets already tested — in this abstract state or any sibling fragment of the same Activity — receive zero. The differential therefore survives NamingFactory refinement: a fragment split does not re-arm the boost for already-exercised widgets.

#### Scenario: Untested widget in new state receives full boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=0, `ape.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 0/5) = 100

#### Scenario: Widget tested in a sibling fragment receives no boost
- **WHEN** widget X was interacted with in fragment S1 of `MainActivity`, and the agent now scores actions in fragment S2 of `MainActivity` where X is registered but locally untested
- **THEN** X's boost in S2 SHALL be 0

#### Scenario: Untested widget in visited state receives decayed boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=10, `ape.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 10/5) = 100 / 3 = 33

#### Scenario: Untested widget in heavily visited state receives minimal boost
- **WHEN** widget is not interacted anywhere in the Activity (`hasActivityInteraction`=false), state visitedCount=25, `ape.coverageBoostWeight`=100
- **THEN** boost = 100 / (1 + 25/5) = 100 / 6 = 16

#### Scenario: Tested widget receives no boost regardless of state visits
- **WHEN** widget has been interacted somewhere in the Activity (`hasActivityInteraction`=true)
- **THEN** boost = 0 (regardless of stateVisits)

**Rationale**: Without decay, the coverage boost traps the agent in complex states with many widgets (e.g., SettingsActivity with 27 widgets). E2E testing on 20 APKs showed simplenotes regressing -12.56pp because the agent spent all time in SettingsActivity exploring color pickers instead of visiting other activities. The decay formula `weight / (1 + stateVisits / 5)` provides full boost on first visit and diminishes to ~16% after 25 visits, allowing the agent to explore elsewhere. The decay is deliberately kept state-scoped while the novelty test is activity-scoped: decay protects against dwelling, novelty must survive fragmentation.

#### Scenario: Coverage boost disabled
- **WHEN** the plan's `ape.coverageBoostWeight` is 0 (so `COVERAGE_BOOST` is absent and `ScoringParams.coverageBoostWeight()` is 0)
- **THEN** no coverage boost SHALL be applied to any action

### Requirement: UICoverageTracker — Coverage Dump

`UICoverageTracker` SHALL expose a read-only dump method that emits, at agent teardown, one `[APE-RV] UICOV` line per tracked state. The line SHALL report the per-state widget-level coverage already held in `stateData` and, when `Feature.MOP` is in the plan, a `mopReach` flag indicating whether the state's Activity gates a MOP target. The dump exists so that per-screen exploration completeness — invisible in the trace otherwise — becomes observable at the end of a run. The tracker and the coverage part of the line are not MOP: they are mounted and written identically in every cell (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`).

The dump SHALL be invoked by the `coverageDump` step of the `StatefulAgent.tearDown` chain, which calls the `dumpCoverage()` hook; `StatefulAgent.dumpCoverage` is a no-op and `SataAgent.dumpCoverage` overrides it, calling `getCoverageTracker().dump(...)` with the predicate built from `getMopData()`. `getMopData()` SHALL be null if and only if `Feature.MOP` is not in the plan, even when the static artifact was loaded for `STATIC` (design D-B14.21). The `mopReach` value SHALL be computed at the call site as `getMopData().activityHasMop(state.getActivity())`, and only when `getMopData()` is non-null, because `UICoverageTracker` does not hold a `MopData` reference; the value SHALL be supplied to the dump as a predicate over `State`, and the supplied predicate SHALL be absent (null) when `Feature.MOP` is not in the plan.

For each tracked state the line format SHALL be, when `Feature.MOP` is in the plan:

```
[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=MODEL_CLICK:a/b,MODEL_LONG_CLICK:c/d mopReach=<0|1>
```

and, when `Feature.MOP` is not in the plan, the same line with the ` mopReach=<0|1>` token omitted — not written as `mopReach=0`:

```
[APE-RV] UICOV state=<stateKey> discovered=<W> interacted=<D> gap=<1-D/W> byType=MODEL_CLICK:a/b,MODEL_LONG_CLICK:c/d
```

`discovered` (`W`) is the count of registered widgets for the state, `interacted` (`D`) is the count of distinct registered widgets whose interaction count is greater than 0, `gap` is `1 - D/W` (or `1.0` when `W == 0`), and `mopReach`, when written, is `1` when the supplied predicate returns true for the state and `0` otherwise. `byType` is a per-action-type breakdown of `interacted/discovered`, where the type label is the `TYPE` segment of each element key — the `ActionType.name()` value (`MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_*`, …), parsed as the substring after the last `|` or the whole key when no `|` is present (the `widgetId()` convention). The breakdown distinguishes **action types**, not widget classes: there is no `Edit` or `Button` action type — a clicked `EditText` and a clicked `Button` are both `MODEL_CLICK`. Only types actually registered for the state appear; the order follows `ActionType` declaration order.

`mopReach` is a MOP signal for the clean-control gate (`event-sink` INV-SNK-18, `guidance-groups` INV-GRP-07): in a run whose plan does not contain `Feature.MOP` (cells `base`, `as`, `as_llm`), no `[APE-RV] UICOV` line SHALL carry a `mopReach` token.

The dump SHALL be read-only: it SHALL NOT register widgets, record interactions, evict entries, or mutate `stateData`, `activityRollup`, or any interaction count. It MAY additionally emit one line for a state at the moment that state is evicted from the bounded `stateData` (LRU eviction), so that states evicted before teardown are still reported once; such a line follows the same `mopReach` rule.

#### Scenario: State with partial coverage emits one line
- **WHEN** at teardown a tracked state of `MainActivity` has 10 registered widgets and 3 distinct interacted widgets
- **THEN** exactly one `[APE-RV] UICOV` line SHALL be emitted for that state
- **AND** it SHALL report `discovered=10 interacted=3 gap=0.7`, in every cell

#### Scenario: Fully unexplored state reports gap 1.0
- **WHEN** a tracked state has 8 registered widgets and 0 interactions
- **THEN** its `[APE-RV] UICOV` line SHALL report `discovered=8 interacted=0 gap=1.0`, in every cell

#### Scenario: byType breakdown reflects per-action-type coverage
- **WHEN** a state registered 3 `MODEL_CLICK` actions (2 interacted) and 1 `MODEL_LONG_CLICK` action (0 interacted)
- **THEN** the line SHALL include `byType=MODEL_CLICK:2/3,MODEL_LONG_CLICK:0/1`, in every cell

#### Scenario: mopReach reflects activityHasMop
- **WHEN** the run's preset is `as_mop` (`Feature.MOP` in the plan, so `getMopData()` is non-null) and `getMopData().activityHasMop("com.example.CipherActivity")` returns true for a tracked state of that Activity
- **THEN** that state's line SHALL end with `mopReach=1`
- **AND** a tracked state of an Activity that gates no MOP target SHALL end with `mopReach=0`

#### Scenario: mopReach omitted in a cell without MOP
- **WHEN** the run's preset is `as` (`STATIC` mounted, the artifact loaded, `Feature.MOP` not in the plan, so `getMopData()` is null) and `stateData` holds a state of `com.example.CipherActivity` with 10 registered `MODEL_CLICK` widgets, 4 of them interacted
- **THEN** its line SHALL be `[APE-RV] UICOV state=<stateKey> discovered=10 interacted=4 gap=0.6 byType=MODEL_CLICK:4/10`, with no `mopReach` token
- **AND** no `[APE-RV] UICOV` line of the run SHALL contain the substring `mopReach`
- **AND** the same holds in the `base` and `as_llm` cells

#### Scenario: One line per tracked state at teardown
- **WHEN** `stateData` holds 5 tracked states at teardown
- **THEN** the dump SHALL emit exactly 5 `[APE-RV] UICOV` lines, one per tracked state, in every cell

#### Scenario: Dump does not alter counts
- **WHEN** the dump runs at teardown
- **THEN** `getTotalElements()` and `getTotalInteractions()` SHALL return the same values immediately before and immediately after the dump
