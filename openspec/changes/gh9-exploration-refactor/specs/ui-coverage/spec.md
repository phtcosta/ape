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
- **[Memory]**: Widget sets and interaction counts grow monotonically during the session. No pruning is performed.

### Error
- None. All methods are null-safe and return default values for unknown states.

## Invariants

- **INV-COV-01**: `getCoverageGap(state)` SHALL return a value in the closed interval [0.0, 1.0].
- **INV-COV-02**: For a given state, the coverage gap SHALL monotonically decrease (or remain equal) as interactions are recorded. It SHALL NOT increase unless new widgets are registered for the same state.
- **INV-COV-03**: `getCoverageGap(unknownState)` SHALL return 1.0 for any state that has not been registered.
- **INV-COV-04**: `widgetId(action)` SHALL return a non-null, non-empty string for any non-null `ModelAction`.

## ADDED Requirements

### Requirement: UICoverageTracker — Widget Registration

`UICoverageTracker.registerScreenElements(State state, List<ModelAction> actions)` SHALL register all actions of the state as trackable widgets. The widget ID for each action is computed as:
- For actions with a target (`requireTarget() == true`): `action.getTarget().toXPath()`
- For actions without a target (BACK, MENU): `action.getType().name()`

Registration is idempotent — re-registering the same state replaces the widget set.

#### Scenario: Register widgets for a new state
- **WHEN** `registerScreenElements(stateA, actions)` is called for a state with actions targeting `//Button[@text="OK"]`, `//EditText[@resource-id="email"]`, and `MODEL_BACK`
- **THEN** the state shall have 3 registered elements
- **AND** element IDs SHALL be `"//Button[@text=\"OK\"]"`, `"//EditText[@resource-id=\"email\"]"`, and `"MODEL_BACK"`

#### Scenario: Re-register same state with different widgets
- **WHEN** `registerScreenElements(stateA, newActions)` is called after a previous registration
- **THEN** the state SHALL have the new widget set (replacing the old one)

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

`StatefulAgent.adjustActionsByGUITree()` SHALL include a coverage boost pass after the WTG scoring pass. For each valid, target-requiring, resolved action, the boost SHALL be computed per-action based on whether the specific widget has been tested:

```
widgetId = action.getTarget().toXPath()
interactionCount = _coverageTracker.getInteractionCount(currentState, widgetId)
if (interactionCount == 0) {
    boost = Config.coverageBoostWeight
} else {
    boost = 0
}
action.setPriority(action.getPriority() + boost)
```

This ensures untested widgets receive the full boost while already-tested widgets receive zero, creating a differential that steers the agent toward unexplored interactions.

#### Scenario: Untested widget receives full boost
- **WHEN** widget "//Button[@text=\"Settings\"]" has interactionCount=0 and `Config.coverageBoostWeight` is 100
- **THEN** the action targeting that widget SHALL receive a priority boost of 100

#### Scenario: Tested widget receives no boost
- **WHEN** widget "//Button[@text=\"OK\"]" has interactionCount=3
- **THEN** the action targeting that widget SHALL receive a priority boost of 0

#### Scenario: Mixed state — differential steering
- **WHEN** state has 3 actions: widgetA (interactionCount=0), widgetB (interactionCount=5), widgetC (interactionCount=0)
- **AND** `Config.coverageBoostWeight` is 100
- **THEN** widgetA and widgetC receive +100 boost; widgetB receives +0
- **AND** the priority distribution now favors untested widgets

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
