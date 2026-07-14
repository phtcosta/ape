## Purpose

Scope the coverage-boost novelty test to the Activity. The boost differential exists to steer selection toward widgets never exercised; testing novelty per abstract state defeats it under refinement, because every naming-level fragment of a screen re-arms the boost for widgets already tested in sibling fragments. The cmpft corpus measured the effect: ~74% of exploration steps re-hit already-tested widget-action pairs, the interaction rate decays with fragmentation (a measured negative correlation, ≈−0.4 to −0.7 depending on formula, between a run's state count and its rate), and screens with 21–50 widget-action pairs are exercised at only 19%. The tracker already knows the activity-level truth for reporting (`activityRollup`, `getActivityCoverageGap`); this delta makes the same knowledge drive the boost, through an O(1) membership lookup.

## ADDED Requirements

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

## MODIFIED Requirements

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
