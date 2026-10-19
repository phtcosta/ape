# Delta: UI Coverage (isolate-guidance-groups)

## Purpose

The teardown coverage dump writes one `[APE-RV] UICOV` line per tracked state, and its last token, `mopReach`, is computed from the agent's `MopData`. Before this change `_mopData` was non-null whenever an artifact was loaded, and `mopReach=0` stood both for "this activity gates no MOP target" and for "this run has no MOP data". `isolate-guidance-groups` loads the artifact iff `STATIC` or `MOP` is mounted (design D-B14.20), and `getMopData()` is null iff `Feature.MOP` is not mounted, even when the artifact was loaded for STATIC (D-B14.21). A `mopReach=0` in the `as` cell would then be a MOP-derived value written by a cell without MOP, which the clean-control gate (`event-sink` INV-SNK-18) forbids. After this change the token is written only when `MOP` is mounted, consistent with `ACT.mop` (D-B14.9), and the gate counts it as a MOP signal. The tracker itself, the visit and coverage counts and the rest of the line are not MOP and are unchanged in every cell (author decision A8, `verification-artifacts.md`).

## MODIFIED Requirements

### Requirement: UICoverageTracker — Coverage Dump

`UICoverageTracker` SHALL expose a read-only dump method that emits, at agent teardown, one `[APE-RV] UICOV` line per tracked state. The line SHALL report the per-state widget-level coverage already held in `stateData` and, when `Feature.MOP` is in the plan, a `mopReach` flag indicating whether the state's Activity gates a MOP target. The dump exists so that per-screen exploration completeness — invisible in the trace otherwise — becomes observable at the end of a run. The tracker and the coverage part of the line are not MOP: they are mounted and written identically in every cell (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`).

The dump SHALL be invoked from the agent teardown path (`SataAgent.tearDown()`, which holds `_coverageTracker` and, through `StatefulAgent`, `getMopData()`). `getMopData()` SHALL be null if and only if `Feature.MOP` is not in the plan, even when the static artifact was loaded for `STATIC` (design D-B14.21). The `mopReach` value SHALL be computed at the call site as `getMopData().activityHasMop(state.getActivity())`, and only when `getMopData()` is non-null, because `UICoverageTracker` does not hold a `MopData` reference; the value SHALL be supplied to the dump as a predicate over `State`, and the supplied predicate SHALL be absent (null) when `Feature.MOP` is not in the plan.

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

---

### Requirement: Coverage Boost in Action Scoring — Per-Action

`StatefulAgent.adjustActionsByGUITree()` SHALL include a coverage boost pass after the WTG scoring pass. For each valid, target-requiring, resolved action, the boost SHALL be computed per-action based on whether the specific widget has been tested anywhere in the current Activity:

```
widgetId = UICoverageTracker.widgetId(action)
interacted = _coverageTracker.hasActivityInteraction(newState.getActivity(), widgetId)
stateVisits = newState.getVisitedCount()
if (!interacted) {
    boost = coverageBoostWeight / (1 + stateVisits / 5)
} else {
    boost = 0
}
action.setPriority(action.getPriority() + boost)
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

### Requirement: Config Flag for Coverage Boost

The coverage boost SHALL be the plan key `ape.coverageBoostWeight`, the activation key of the ADDONS member `COVERAGE_BOOST` (POSITIVE, neutral `0`), read through the plan (`spec().exploration().coverageBoostWeight()`, injected into `CoveragePass` through `ScoringParams`). `Config` has no field for it (design D-B24).

| Key | Owner | Type | Jar default | Description |
|-----|-------|------|-------------|-------------|
| `ape.coverageBoostWeight` | `COVERAGE_BOOST` (ADDONS) | int | 100 | Priority boost for untested widgets (`<= 0` = feature absent) |

#### Scenario: Config flag loaded
- **WHEN** `ape.properties` contains `ape.coverageBoostWeight=150`
- **THEN** on a plan with `ADDONS` the resolved `coverageBoostWeight()` SHALL be 150

#### Scenario: Default value
- **WHEN** `ape.coverageBoostWeight` is not set in properties
- **THEN** `KeyOwnership.defaultOf("ape.coverageBoostWeight")` SHALL be `100`, and the ADDONS block states `100`
