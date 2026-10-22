# activity-budget — delta: align-specs-with-code

## Data Contracts

### Input
- `activityName: String` — fully qualified activity class name (source: `State.getActivity()`)
- `widgetCount: int` — number of target-requiring actions (`action.requireTarget() == true`) of the state being registered, counted in `StatefulAgent` on each new state (`newState.getActions()`). This excludes non-widget actions like BACK and MENU. Registration is idempotent, so the budget of an activity is set by the first state of that activity the agent reaches.

### Output
- `isBudgetExhausted: boolean` — whether the activity has consumed its allocated iterations (consumer: the `Budget` stage, `BudgetStage.decide()`)
- `getRemainingBudget(activityName): int` — iterations remaining before exhaustion (no production caller; read by the unit tests)

## Invariants

- **INV-BUD-03**: `getRemainingBudget(activityName)` SHALL be non-negative (clamped to 0 minimum), and SHALL return `Integer.MAX_VALUE` for an unregistered activity.

## MODIFIED Requirements

### Requirement: ActivityBudgetTracker — Budget Allocation

`ActivityBudgetTracker(int baseBudget, int budgetPerWidget)` SHALL accept two configuration parameters. `registerActivity(String activityName, int widgetCount)` SHALL allocate `baseBudget + (widgetCount × budgetPerWidget)` iterations to the activity. Registration is idempotent — if the activity is already registered, the call is ignored (budget is not recalculated).

`StatefulAgent` SHALL call `registerActivity` for every new state, before resolving its action, with the state's activity and the number of its target-requiring actions (`requireTarget()`). Because registration is idempotent, an activity's budget is computed from the first state of that activity the agent reaches, not from the sum over its states.

#### Scenario: Register activity with widgets
- **WHEN** `registerActivity("com.example.MainActivity", 15)` is called with baseBudget=50 and budgetPerWidget=5
- **THEN** the budget for "com.example.MainActivity" SHALL be 125 (50 + 15×5)

#### Scenario: Re-register same activity
- **WHEN** `registerActivity("com.example.MainActivity", 20)` is called after a previous registration with widgetCount=15
- **THEN** the budget SHALL remain 125 (original allocation preserved)

#### Scenario: a later, larger state of the activity does not raise the budget
- **WHEN** the agent first reaches a state of `com.example.MainActivity` with 15 target-requiring actions, then a state of the same activity with 30
- **THEN** the budget of `com.example.MainActivity` SHALL be 125 (50 + 15×5) for the rest of the run
