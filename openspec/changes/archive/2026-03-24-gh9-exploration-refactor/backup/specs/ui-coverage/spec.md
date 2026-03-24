## Purpose

APE-RV's exploration engine tracks action visits (per `ModelAction.visitedCount`) but lacks a per-state widget-level coverage metric. When the agent enters a state with 20 interactable widgets and tests only 5 before navigating away, there is no signal that 75% of the state remains unexplored. The `UICoverageTracker` fills this gap by maintaining a registry of all interactable widgets per state and recording which have been interacted with. The resulting coverage gap metric (fraction of untested widgets, 0.0–1.0) feeds into `StatefulAgent.adjustActionsByGUITree()` as an additive priority boost, steering the agent back toward underexplored states.

The design follows the same pattern as rvsmart's `UICoverageTracker` but adapts to APE-RV's data model: `GUITreeNode` replaces `ScreenItem`, and the state's structural hash (from `GUITree`) serves as the screen identifier. Widget identification uses a hybrid scheme — `res:{resourceId}` when a resource ID is available, `coords:{centerX},{centerY}` from bounds otherwise — ensuring stable tracking across visits to the same state.

The tracker is instantiated once in `StatefulAgent` and persists for the session. Registration occurs in `StatefulAgent.updateStateInternal()` after the `GUITree` is built. Interaction recording occurs in `StatefulAgent.moveForward()` after action execution. The coverage boost is applied in `adjustActionsByGUITree()` alongside the existing MOP scoring pass.

## Data Contracts

### Input
- `structuralHash: String` — structural hash of the current State (source: `GUITree.getStructuralHash()` via State)
- `widgets: List<GUITreeNode>` — interactable widgets in the current GUITree (source: `GUITree` nodes where `clickable || longClickable || scrollable`)
- `interactedWidgetId: String` — hybrid ID of the widget just interacted with (source: resolved `GUITreeNode` of the executed `ModelAction`)

### Output
- `coverageGap: float` — fraction of registered widgets NOT yet interacted with, range [0.0, 1.0] (consumer: `adjustActionsByGUITree()` in `StatefulAgent`)
- `totalElements: int` — count of unique widgets registered across all states (consumer: logging/telemetry)
- `totalInteractions: int` — sum of all interaction counts (consumer: logging/telemetry)

### Side-Effects
- **[Memory]**: Widget sets and interaction counts grow monotonically during the session. No pruning is performed.

### Error
- None. All methods are null-safe and return default values for unknown states.

## Invariants

- **INV-COV-01**: `getCoverageGap(structuralHash)` SHALL return a value in the closed interval [0.0, 1.0].
- **INV-COV-02**: For a given state, the coverage gap SHALL monotonically decrease (or remain equal) as interactions are recorded. It SHALL NOT increase unless new widgets are registered for the same state.
- **INV-COV-03**: `getCoverageGap(unknownHash)` SHALL return 1.0 for any state hash that has not been registered.
- **INV-COV-04**: `elementId(node)` SHALL return a non-null, non-empty string for any non-null `GUITreeNode`.

## ADDED Requirements

### Requirement: UICoverageTracker — Widget Registration

`UICoverageTracker.registerScreenElements(String structuralHash, List<GUITreeNode> widgets)` SHALL register all interactable widgets for a state. Widget IDs SHALL be computed using a hybrid scheme: `"res:" + resourceId` when `GUITreeNode.getResourceID()` is non-null and non-empty, otherwise `"coords:" + centerX + "," + centerY` from `GUITreeNode.getBoundsInScreen()`. Registration is idempotent — re-registering the same state replaces the widget set.

#### Scenario: Register widgets for a new state
- **WHEN** `registerScreenElements("state_abc", [Button(id="btn_ok"), EditText(id=null, bounds=[100,200,300,400])])` is called
- **THEN** the state "state_abc" SHALL have 2 registered elements
- **AND** element IDs SHALL be `"res:btn_ok"` and `"coords:200,300"`

#### Scenario: Re-register same state with different widgets
- **WHEN** `registerScreenElements("state_abc", [Button(id="btn_cancel")])` is called after a previous registration
- **THEN** the state "state_abc" SHALL have the new widget set (replacing the old one)

### Requirement: UICoverageTracker — Interaction Recording

`UICoverageTracker.recordInteraction(String structuralHash, String elementId)` SHALL increment the interaction count for the given widget in the given state. The composite key `structuralHash + "|" + elementId` ensures screen-scoped tracking.

#### Scenario: Record first interaction
- **WHEN** `recordInteraction("state_abc", "res:btn_ok")` is called for the first time
- **THEN** the interaction count for `"state_abc|res:btn_ok"` SHALL be 1

#### Scenario: Record repeated interaction
- **WHEN** `recordInteraction("state_abc", "res:btn_ok")` is called 3 times
- **THEN** the interaction count SHALL be 3

### Requirement: UICoverageTracker — Coverage Gap Computation

`UICoverageTracker.getCoverageGap(String structuralHash)` SHALL return the fraction of registered widgets that have NOT been interacted with at least once. The formula is: `1.0 - (interactedCount / totalRegistered)`.

#### Scenario: No interactions yet
- **WHEN** state "state_abc" has 10 registered widgets and 0 interactions
- **THEN** `getCoverageGap("state_abc")` SHALL return 1.0

#### Scenario: Partial coverage
- **WHEN** state "state_abc" has 10 registered widgets and 3 distinct widgets interacted with
- **THEN** `getCoverageGap("state_abc")` SHALL return 0.7

#### Scenario: Full coverage
- **WHEN** all 10 registered widgets have been interacted with at least once
- **THEN** `getCoverageGap("state_abc")` SHALL return 0.0

#### Scenario: Unknown state
- **WHEN** `getCoverageGap("unknown_state")` is called for an unregistered state
- **THEN** the result SHALL be 1.0

### Requirement: Coverage Boost in Action Scoring

`StatefulAgent.adjustActionsByGUITree()` SHALL include a coverage boost pass after the existing MOP scoring pass. For each valid, target-requiring action, the boost SHALL be `(int)(coverageGap * Config.coverageBoostWeight)` where `coverageGap` is the current state's gap from `UICoverageTracker`. This boost is additive to the action's existing priority.

#### Scenario: Coverage boost applied
- **WHEN** the current state has a coverage gap of 0.8 and `Config.coverageBoostWeight` is 100
- **THEN** each valid target action in the state SHALL receive a priority boost of 80

#### Scenario: Coverage boost disabled
- **WHEN** `Config.coverageBoostWeight` is 0
- **THEN** no coverage boost SHALL be applied to any action

### Requirement: Config Flag for Coverage Boost

`Config.java` SHALL declare the following flag:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `coverageBoostWeight` | `ape.coverageBoostWeight` | int | 100 | Weight multiplied by coverage gap for priority boost (0 = disabled) |

#### Scenario: Config flag loaded
- **WHEN** `ape.properties` contains `ape.coverageBoostWeight=150`
- **THEN** `Config.coverageBoostWeight` SHALL be 150

#### Scenario: Default value
- **WHEN** `ape.coverageBoostWeight` is not set in properties
- **THEN** `Config.coverageBoostWeight` SHALL be 100
