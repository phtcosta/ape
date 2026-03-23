## Purpose

APE-RV's `StatefulAgent` has three stagnation counters (`graphStableCounter`, `stateStableCounter`, `activityStableCounter`) but the activity-level counter threshold is set to `Integer.MAX_VALUE`, effectively disabling it. This means the agent can spend its entire 600-second budget in a single activity (e.g., Preferences screens) without being forced to explore others. The `ActivityBudgetTracker` addresses this by allocating a per-activity iteration budget proportional to the number of interactable widgets in that activity. When the budget is exhausted, the SATA heuristic forces navigation to another activity.

The budget formula is: `budget = baseBudget + (widgetCount × budgetPerWidget)`. An activity with 20 widgets gets a larger budget than one with 3 widgets, reflecting the expected exploration effort. The tracker is instantiated once in `StatefulAgent`, activities are registered as they are discovered, and iteration counts are incremented in `moveForward()`. The budget check is performed at the top of `SataAgent.selectNewActionNonnull()` — if the current activity's budget is exhausted, the agent delegates to the trivial activity selection path to navigate elsewhere.

## Data Contracts

### Input
- `activityName: String` — fully qualified activity class name (source: `State.getActivity()`)
- `widgetCount: int` — number of interactable widgets across all states belonging to this activity (source: `ActivityNode.states` → sum of `State.getActions().size()`)

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

#### Scenario: Budget exhausted but no alternative
- **WHEN** the current activity's budget is exhausted but no path to another activity is available
- **THEN** the agent SHALL fall through to the normal SATA priority chain

### Requirement: Config Flags for Activity Budget

`Config.java` SHALL declare the following flags:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `activityBaseBudget` | `ape.activityBaseBudget` | int | 50 | Base iteration budget per activity |
| `activityBudgetPerWidget` | `ape.activityBudgetPerWidget` | int | 5 | Additional budget per interactable widget |

#### Scenario: Config flags loaded
- **WHEN** `ape.properties` contains `ape.activityBaseBudget=100` and `ape.activityBudgetPerWidget=10`
- **THEN** `Config.activityBaseBudget` SHALL be 100 and `Config.activityBudgetPerWidget` SHALL be 10

#### Scenario: Default values
- **WHEN** activity budget flags are not set in properties
- **THEN** `Config.activityBaseBudget` SHALL be 50 and `Config.activityBudgetPerWidget` SHALL be 5
