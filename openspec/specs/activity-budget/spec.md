## Purpose

APE-RV's `StatefulAgent` has three stagnation counters (`graphStableCounter`, `stateStableCounter`, `activityStableCounter`). The activity-level threshold is `ape.activityStableRestartThreshold`, the parameter of the ADDONS member `Feature.ACTIVITY_STABLE_RESTART` (POSITIVE, neutral `0`, jar default `200`; the ADDONS block of every `as*` preset states `200`), read through the plan (`spec().exploration().activityStableRestartThreshold()`). When the feature is absent — the `base` and `mop` cells — the threshold is upstream's `Integer.MAX_VALUE`, so no activity-stable restart occurs, and the agent can spend its entire budget in a single activity (e.g., Preferences screens) without being forced to explore others. The `ActivityBudgetTracker` addresses this by allocating a per-activity iteration budget proportional to the number of interactable widgets in that activity. When the budget is exhausted, the agent is forced to navigate to another activity.

The budget formula is: `budget = baseBudget + (widgetCount × budgetPerWidget)`. An activity with 20 widgets gets a larger budget than one with 3 widgets, reflecting the expected exploration effort. The tracker exists iff the plan mounts the ADDONS member `Feature.ACTIVITY_BUDGET` (`ape.activityBudgetEnabled`; parameters `ape.activityBaseBudget`, default 50, and `ape.activityBudgetPerWidget`, default 5), tested by `RunContext.current().spec().has(Feature.ACTIVITY_BUDGET)`. It is instantiated once in `StatefulAgent`, activities are registered as they are discovered, and iteration counts are incremented in `moveForward()`. The budget check is the `Budget` guard stage of the decision pipeline (`decision-pipeline`, design D-B7), which runs first, before `Restart` and every LLM, MOP and SATA stage: if the current activity's budget is exhausted, it takes the step with the trivial-activity navigation. A step it takes carries `dec.src:"budget"` and `dec.ch:"budget"`, and a non-empty SATA buffer it preempts is abandoned without weakening (`dec.buf:"budget"`, D-B14.13, D-B20). No site of either mechanism reads `Config`.

## Data Contracts

### Input
- `activityName: String` — fully qualified activity class name (source: `State.getActivity()`)
- `widgetCount: int` — number of target-requiring actions (`action.requireTarget() == true`) of the state being registered, counted in `StatefulAgent` on each new state (`newState.getActions()`). This excludes non-widget actions like BACK and MENU. Registration is idempotent, so the budget of an activity is set by the first state of that activity the agent reaches.

### Output
- `isBudgetExhausted: boolean` — whether the activity has consumed its allocated iterations (consumer: the `Budget` stage, `BudgetStage.decide()`)
- `getRemainingBudget(activityName): int` — iterations remaining before exhaustion (no production caller; read by the unit tests)

### Side-Effects
- **[Agent behavior]**: When budget is exhausted, the `Budget` stage takes the step before any other owner and forces navigation to another activity (`dec.src:"budget"`, `dec.ch:"budget"`).

### Error
- None. Unregistered activities return `false` for exhaustion and `Integer.MAX_VALUE` for remaining budget.

## Invariants

- **INV-BUD-01**: `isBudgetExhausted(activityName)` SHALL return `false` for any activity that has not been registered.
- **INV-BUD-02**: Budget SHALL be computed exactly once per activity at registration time and SHALL NOT be recalculated.
- **INV-BUD-03**: `getRemainingBudget(activityName)` SHALL be non-negative (clamped to 0 minimum), and SHALL return `Integer.MAX_VALUE` for an unregistered activity.

## Requirements

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

The activity-budget mechanism SHALL be governed by `Feature.ACTIVITY_BUDGET`, the ADDONS member activated by `ape.activityBudgetEnabled` and read through `RunContext.current().spec().has(Feature.ACTIVITY_BUDGET)`. No site SHALL read `Config.activityBudgetEnabled`. The ADDONS block of the `as`, `as_mop`, `as_llm` and `as_mop_llm` presets states `ape.activityBudgetEnabled=true`; the `base` and `mop` cells do not mount `ADDONS`, so the feature is absent there.

When `Feature.ACTIVITY_BUDGET` is in the plan, `StatefulAgent` SHALL instantiate the `ActivityBudgetTracker` with `ape.activityBaseBudget` and `ape.activityBudgetPerWidget` read from the plan, and the decision pipeline SHALL mount the `Budget` guard stage as its first stage (`decision-pipeline` "Pipeline Assembly from the RunSpec"). On each step the `Budget` stage SHALL check `ActivityBudgetTracker.isBudgetExhausted()` for the current activity before every other stage. The budget is a **soft constraint** — it influences navigation but does not block exploration:

1. When the budget is exhausted, try `selectNewActionForTrivialActivity()` — navigate to a different activity. A non-null result SHALL be returned as `Select`: the step is owned by `budget`, its record carries `dec.src:"budget"`, `dec.own.i:"budget"` and `dec.ch:"budget"`, and no `dec.strat`; when the search tied two or more paths at the X4 extension point, the record also carries that choice's `dec.nav` (`action-selection` "SATA Navigation Extension Points (X1, X3, X4)", design D-B21). The search runs through the agent's `budgetNavigation()`, which detaches the SATA buffer without weakening any edge before it searches: when the search finds nothing the buffer is restored, and when it takes the step a non-empty detached buffer is abandoned, the record carries `dec.buf:"budget"` (design D-B14.13), and the newly planned path stays in the buffer (D-B20). A step the `Budget` stage takes never reaches `Restart`, so a pending restart latch is left for the next step (D-B14.16).
2. If the budget is not exhausted, or `selectNewActionForTrivialActivity()` returns null, the stage SHALL return `Continue`, and the step falls through to the later stages and finally to the normal SATA chain.

**Rationale**: Both hard fallbacks were tested and found harmful:
- EVENT_RESTART caused destructive restart loops (40+ restarts/run, -3.39pp method coverage)
- MODEL_BACK caused stuck loops (78 useless BACKs/run burning 67% of time, -1.10pp on 20 APKs)
- Fallthrough (no special action) was validated as correct: +3.67pp method, +6.01pp MOP on cryptoapp

The budget's value is in the trivial activity navigation — when available, it forces diversification. When not available, the later stages and the normal SATA chain handle exploration without forced navigation.

When `Feature.ACTIVITY_BUDGET` is not in the plan (the `base` and `mop` cells, or an `as*` cell with `ape.activityBudgetEnabled=false`), `StatefulAgent` SHALL NOT instantiate the `ActivityBudgetTracker`, and the pipeline SHALL NOT mount the `Budget` stage — selection proceeds without any budget check, reproducing upstream APE (which has no activity budget). No `recordIteration`/`isBudgetExhausted` call SHALL occur, and no record SHALL carry `dec.src:"budget"`.

#### Scenario: Budget exhausted forces navigation via trivial activity (flag on)
- **WHEN** the run's preset is `as` (so `Feature.ACTIVITY_BUDGET` is in the plan), `com.example.MainActivity` has a budget of 125 and 125 recorded iterations, and `selectNewActionForTrivialActivity()` returns an action toward `com.example.SettingsActivity`
- **THEN** the `Budget` stage SHALL return `Select` with that action and the agent SHALL navigate to `com.example.SettingsActivity`
- **AND** the step record SHALL carry `dec.src:"budget"`, `dec.ch:"budget"` and no `dec.strat`
- **AND** if the search found two shortest paths to `com.example.SettingsActivity`, the record SHALL also carry `dec.nav` with `x:"X4"` (D-B21); with a single path it SHALL carry no `dec.nav`
- **AND** if the SATA buffer held two planned transitions, none of them SHALL remain in the buffer after the step, the record SHALL carry `dec.buf:"budget"`, and no `weakenStateTransition` call SHALL have been made

#### Scenario: the budget keeps the path it planned (D-B20)
- **WHEN** the budget is exhausted and `selectNewActionForTrivialActivity()` finds a three-edge path A→B→C→D to a trivial activity, with the SATA buffer empty or not before the step
- **THEN** the step's action SHALL be the path's first action, and after the step the buffer SHALL hold the path's edges as `refillBuffer` left them, so that `SataChain`'s buffer rung follows the path on the next steps
- **AND** the record SHALL carry `dec.buf:"budget"` and `RUN_END.counters.bufAbandoned` SHALL be one larger exactly when the buffer was non-empty before the step

#### Scenario: Budget exhausted, no trivial activity — fallthrough (flag on)
- **WHEN** `Feature.ACTIVITY_BUDGET` is in the plan, the current activity's budget is exhausted, and `selectNewActionForTrivialActivity()` returns null
- **THEN** the `Budget` stage SHALL return `Continue`
- **AND** the SATA buffer SHALL be as it was before the step, with no `weakenStateTransition` call and no `dec.buf` (D-B20)
- **AND** the step SHALL be decided by a later stage or by the normal SATA chain with all priority boosts active, and its record SHALL NOT carry `dec.src:"budget"`

#### Scenario: No budget tracker or check when the flag is off
- **WHEN** the run's preset is `base` or `mop` (no `ADDONS`), or is `as` with `ape.activityBudgetEnabled=false`
- **THEN** `StatefulAgent` SHALL NOT instantiate an `ActivityBudgetTracker`
- **AND** the pipeline SHALL NOT contain the `Budget` stage (for `base`: `[Restart, SataChain]`)
- **AND** no `recordIteration` or `isBudgetExhausted` call SHALL occur during the run, and no step record SHALL carry `dec.src:"budget"`

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

The activity-stable restart SHALL be governed by `Feature.ACTIVITY_STABLE_RESTART`, an ADDONS member of kind POSITIVE (neutral value `0`) whose parameter is `ape.activityStableRestartThreshold` (jar default `200`; the ADDONS block of the `as*` presets states `200`). The threshold SHALL be read through the plan (`RunContext.current().spec().exploration().activityStableRestartThreshold()`), never from `Config`. When the feature is in the plan, the accessor SHALL return the configured value; when the feature is absent — the `base` and `mop` cells, or a properties file setting `ape.activityStableRestartThreshold=0` — it SHALL return upstream APE's `Integer.MAX_VALUE`, so the `activityStableCounter` never exceeds it and no activity-stable restart occurs.

With the feature in the plan, the existing `activityStableCounter` mechanism in `StatefulAgent` raises the restart flag with kind `activity` when the agent stays in the same activity for more than N consecutive transitions; the restart itself is taken by the `Restart` guard stage (`decision-pipeline`). This complements the budget tracker: the budget forces navigation to another activity, while the activity-stable counter forces a restart if navigation fails to escape.

#### Scenario: Activity-stable restart triggers
- **WHEN** the run's preset is `as` (so `Feature.ACTIVITY_STABLE_RESTART` is in the plan with threshold 200) and the agent has stayed in `com.example.MainActivity` for 201 consecutive transitions
- **THEN** `onActivityStable()` SHALL call `requestRestart()` with kind `activity`
- **AND** `startNewEpisode()` SHALL reset all three stability counters to 0
- **AND WHEN** the run's preset is `base` or `mop` and the agent stays in `com.example.MainActivity` for 5,000 consecutive transitions
- **THEN** the threshold SHALL be `Integer.MAX_VALUE` and `onActivityStable()` SHALL NOT be called

#### Scenario: Counter resets on activity change
- **WHEN** the agent transitions from `com.example.MainActivity` to `com.example.SettingsActivity`
- **THEN** `activityStableCounter` SHALL reset to 0, in every cell

### Requirement: Config Flags for Activity Budget

The activity-budget and activity-stable-restart keys SHALL be owned by plan features (`KeyOwnership`) and read through `RunContext.current().spec()`; `Config.java` SHALL NOT be read for any of them:

| Property Key | Owner | Type | Jar default | Neutral | Description |
|-------------|-------|------|---------|---------|-------------|
| `ape.activityBudgetEnabled` | `ACTIVITY_BUDGET` (ADDONS) | bool | `true` | `false` | Mounts the budget tracker and the `Budget` stage; the ADDONS block states `true`; without `ADDONS` the default never activates the feature |
| `ape.activityBaseBudget` | `ACTIVITY_BUDGET` | int | 50 | — | Base iteration budget per activity |
| `ape.activityBudgetPerWidget` | `ACTIVITY_BUDGET` | int | 5 | — | Additional budget per interactable widget |
| `ape.activityStableRestartThreshold` | `ACTIVITY_STABLE_RESTART` (ADDONS, POSITIVE) | int | 200 | `0` | Max consecutive same-activity transitions before restart; feature absent ⇒ `Integer.MAX_VALUE` |

`ape.activityStableRestartThreshold` was a base key before this change and is now a feature parameter (design D-B5). A properties file that sets it, or `ape.activityBudgetEnabled`, in a plan without `ADDONS` is resolved by the run-spec dependency rules (`run-spec`): an explicitly set activation key or non-neutral parameter whose dependencies are unmet aborts, and a default-valued key never activates the feature.

#### Scenario: Config flags loaded
- **WHEN** the run's preset is `as` and `ape.properties` contains `ape.activityBaseBudget=100` and `ape.activityBudgetPerWidget=10`
- **THEN** the `ActivityBudgetTracker` SHALL be constructed with `baseBudget=100` and `budgetPerWidget=10`, read from the plan
- **AND** `RUN_START.params` SHALL carry both values

#### Scenario: Default values
- **WHEN** the run's preset is `as` and the activity budget keys are not set in properties
- **THEN** the tracker SHALL be constructed with `baseBudget=50` and `budgetPerWidget=5`

#### Scenario: Activity stable threshold activated
- **WHEN** the run's preset is `as` and `ape.activityStableRestartThreshold` is not set in properties
- **THEN** `spec().exploration().activityStableRestartThreshold()` SHALL return 200, stated by the ADDONS block
- **AND WHEN** the run's preset is `base` and the key is not set
- **THEN** `Feature.ACTIVITY_STABLE_RESTART` SHALL be absent from `RUN_START.features` and the accessor SHALL return `Integer.MAX_VALUE`
