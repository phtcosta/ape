## Purpose

APE-RV's `StatefulAgent` has three stagnation counters (`graphStableCounter`, `stateStableCounter`, `activityStableCounter`) but the activity-level counter threshold is set to `Integer.MAX_VALUE`, effectively disabling it. This means the agent can spend its entire 600-second budget in a single activity (e.g., Preferences screens) without being forced to explore others. The `ActivityBudgetTracker` addresses this by allocating a per-activity iteration budget proportional to the number of interactable widgets in that activity. When the budget is exhausted, the SATA heuristic forces navigation to another activity.

The budget formula is: `budget = baseBudget + (widgetCount × budgetPerWidget)`. An activity with 20 widgets gets a larger budget than one with 3 widgets, reflecting the expected exploration effort. The tracker is instantiated once in `StatefulAgent`, activities are registered as they are discovered, and iteration counts are incremented in `moveForward()`. The budget check is performed at the top of `SataAgent.selectNewActionNonnull()` — if the current activity's budget is exhausted, the agent delegates to the trivial activity selection path to navigate elsewhere.

## Data Contracts

### Input
- `activityName: String` — fully qualified activity class name (source: `State.getActivity()`)
- `widgetCount: int` — number of target-requiring actions across all states belonging to this activity (source: `ActivityNode.states` → sum of actions where `action.requireTarget() == true`). This excludes non-widget actions like BACK and MENU.

### Output
- `isBudgetExhausted: boolean` — whether the activity has consumed its allocated iterations (consumer: `SataAgent.selectNewActionNonnull()`)
- `remainingBudget: int` — iterations remaining before exhaustion (consumer: logging)

### Side-Effects
- **[Agent behavior]**: When budget is exhausted, the SATA agent skips its normal priority chain and forces navigation to another activity.

### Error
- None. Unregistered activities return `false` for exhaustion and `Integer.MAX_VALUE` for remaining budget.

## Invariants

- **INV-BUD-01**: `isBudgetExhausted(activityName)` SHALL return `false` for any activity that has not been registered.
- **INV-BUD-02**: Budget SHALL be computed exactly once per activity at registration time and SHALL NOT be recalculated.
- **INV-BUD-03**: `remainingBudget(activityName)` SHALL be non-negative (clamped to 0 minimum).

## ADDED Requirements

### Requirement: ActivityBudgetTracker — Budget Allocation

`ActivityBudgetTracker(int baseBudget, int budgetPerWidget)` SHALL accept two configuration parameters. `registerActivity(String activityName, int widgetCount)` SHALL allocate `baseBudget + (widgetCount × budgetPerWidget)` iterations to the activity. Registration is idempotent — if the activity is already registered, the call is ignored (budget is not recalculated).

#### Scenario: Register activity with widgets
- **WHEN** `registerActivity("com.example.MainActivity", 15)` is called with baseBudget=50 and budgetPerWidget=5
- **THEN** the budget for "com.example.MainActivity" SHALL be 125 (50 + 15×5)

#### Scenario: Re-register same activity
- **WHEN** `registerActivity("com.example.MainActivity", 20)` is called after a previous registration with widgetCount=15
- **THEN** the budget SHALL remain 125 (original allocation preserved)

### Requirement: ActivityBudgetTracker — Iteration Counting

`recordIteration(String activityName)` SHALL increment the iteration counter for the specified activity by 1.

#### Scenario: Count iterations
- **WHEN** `recordIteration("com.example.MainActivity")` is called 50 times
- **THEN** the iteration count for "com.example.MainActivity" SHALL be 50
- **AND** `isBudgetExhausted()` SHALL return `false` if budget is 125

### Requirement: ActivityBudgetTracker — Budget Exhaustion

`isBudgetExhausted(String activityName)` SHALL return `true` when the iteration count equals or exceeds the allocated budget.

#### Scenario: Budget exhausted
- **WHEN** budget is 125 and 125 iterations have been recorded
- **THEN** `isBudgetExhausted("com.example.MainActivity")` SHALL return `true`

#### Scenario: Unknown activity
- **WHEN** `isBudgetExhausted("com.example.UnknownActivity")` is called for an unregistered activity
- **THEN** the result SHALL be `false`

### Requirement: Budget Check in SATA Action Selection

`SataAgent.selectNewActionNonnull()` SHALL check `ActivityBudgetTracker.isBudgetExhausted()` for the current activity BEFORE the normal SATA priority chain. When the budget is exhausted, the agent SHALL delegate to `selectNewActionForTrivialActivity()` to navigate to another activity. If no trivial activity path is available, the agent SHALL fall through to the normal SATA chain.

#### Scenario: Budget exhausted forces navigation
- **WHEN** the current activity's budget is exhausted and a path to another activity exists
- **THEN** the agent SHALL navigate to the target activity instead of selecting an action in the current state

#### Scenario: Budget exhausted but no trivial activity available — restart fallback
- **WHEN** the current activity's budget is exhausted and `selectNewActionForTrivialActivity()` returns null (no trivial activity path available)
- **THEN** the agent SHALL select `EVENT_RESTART` to force a fresh start from the launcher activity
- **AND** this ensures budget exhaustion always produces a navigation effect, even in apps with 2-3 activities where the trivial activity heuristic returns empty

#### Scenario: Budget exhausted but no alternative (restart also unavailable)
- **WHEN** the restart fallback cannot be constructed
- **THEN** the agent SHALL fall through to the normal SATA priority chain

### Requirement: Budget is Non-Resettable

The activity budget SHALL NOT reset after EVENT_RESTART or EVENT_CLEAN_RESTART. Re-exploration of previously budget-exhausted activities is handled by existing mechanisms:
- `graphStableCounter` / `stateStableCounter` trigger restart when the agent stagnates
- `activityStableRestartThreshold` (newly activated, see below) forces restart when stuck in one activity
- Dynamic epsilon gives high exploration rate (high coverage gap) when revisiting states after restart

#### Scenario: Budget persists across restart
- **WHEN** activity "com.example.MainActivity" has exhausted its budget of 125 iterations
- **AND** the agent performs EVENT_RESTART
- **THEN** `isBudgetExhausted("com.example.MainActivity")` SHALL still return `true`
- **AND** the agent will be forced to navigate elsewhere when it re-enters that activity

### Requirement: Activate activityStableRestartThreshold

`Config.activityStableRestartThreshold` SHALL be changed from `Integer.MAX_VALUE` (disabled) to a configurable default of `200`. This activates the existing `activityStableCounter` mechanism in `StatefulAgent`, which triggers a restart when the agent stays in the same activity for N consecutive transitions. This complements the budget tracker: the budget forces navigation to another activity, while the activity-stable counter forces a restart if navigation fails to escape.

#### Scenario: Activity-stable restart triggers
- **WHEN** the agent has been in "com.example.MainActivity" for 200 consecutive transitions
- **THEN** `onActivityStable()` SHALL call `requestRestart()`
- **AND** `startNewEpisode()` SHALL reset all three stability counters to 0

#### Scenario: Counter resets on activity change
- **WHEN** the agent transitions from "com.example.MainActivity" to "com.example.SettingsActivity"
- **THEN** `activityStableCounter` SHALL reset to 0

### Requirement: Config Flags for Activity Budget

`Config.java` SHALL declare the following flags:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `activityBaseBudget` | `ape.activityBaseBudget` | int | 50 | Base iteration budget per activity |
| `activityBudgetPerWidget` | `ape.activityBudgetPerWidget` | int | 5 | Additional budget per interactable widget |
| `activityStableRestartThreshold` | `ape.activityStableRestartThreshold` | int | 200 | Max consecutive same-activity transitions before restart (was MAX_VALUE) |

#### Scenario: Config flags loaded
- **WHEN** `ape.properties` contains `ape.activityBaseBudget=100` and `ape.activityBudgetPerWidget=10`
- **THEN** `Config.activityBaseBudget` SHALL be 100 and `Config.activityBudgetPerWidget` SHALL be 10

#### Scenario: Default values
- **WHEN** activity budget flags are not set in properties
- **THEN** `Config.activityBaseBudget` SHALL be 50 and `Config.activityBudgetPerWidget` SHALL be 5

#### Scenario: Activity stable threshold activated
- **WHEN** `ape.activityStableRestartThreshold` is not set in properties
- **THEN** `Config.activityStableRestartThreshold` SHALL be 200 (previously was Integer.MAX_VALUE)
