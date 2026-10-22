## Purpose

APE-RV's exploration engine is responsible for systematically exercising an Android application under test by navigating its GUI state space. The core motivation for model-based exploration — rather than pure random input generation as in AOSP Monkey — is that random exploration wastes most of its budget re-visiting already-seen states and rarely penetrates deeply into an app's feature set. APE-RV builds an explicit, labeled graph of abstract GUI states and state transitions as it runs, enabling the agent to reason about which parts of the application have already been covered and which have not. This directed exploration dramatically increases code and feature coverage within a fixed time budget.

The central abstraction mechanism, inherited from the original APE tool (ICSE 2019), is CEGAR-style (Counterexample-Guided Abstraction Refinement). The `NamingFactory` component maps concrete `GUITree` snapshots to abstract `State` nodes via a configurable `Naming` (a level in a widget-attribute lattice). When the abstraction is too coarse — detected by observing non-deterministic transitions from a single abstract state — the naming is automatically refined to a finer level, splitting the offending state. This adaptive refinement ensures the model stays sound (no false merging of distinct screens) without over-specifying upfront.

APE-RV supports three selectable exploration strategies, created by `ApeAgent.createAgent` from the plan's agent type. `SataAgent` (`--ape sata`, the default) is the primary strategy and implements an epsilon-greedy heuristic that aggressively prioritises unvisited actions while using graph-guided navigation to reach under-explored parts of the app. `RandomAgent` (`--ape random`) provides an experimental baseline: it takes the next buffered action when one is planned, and otherwise a priority-weighted roulette over the state's actions — it uses the `StatefulAgent` priority infrastructure but none of SATA's directed navigation. `ReplayAgent` (activated on the command line via `--ape replay --ape-replay <log>`; `ape.replayLog` is retired as a file key) replays a recorded action sequence for deterministic regression testing. Any other value, including `ape`, `bfs` and `dfs`, aborts the run before step 1. The strategy is selected once at startup and does not change during a session.

The exploration loop, implemented inside `MonkeySourceApe.nextEventImpl()`, is the heartbeat of the system. Each iteration captures the current screen as a `GUITree`, maps it to an abstract `State`, asks the active `Agent` to choose a `ModelAction`, translates that action into one or more `ApeEvent` objects (click, drag, key press, scroll gesture), enqueues those events in Monkey's event queue for execution on the device, then captures the resulting screen and updates the model. The loop terminates when a wall-clock time limit (`--running-minutes N`) or an event-count limit (`-v N`) is reached; teardown then writes the run's closing telemetry (no exploration-graph file is saved). The priority system on `ModelAction` objects allows extensions such as the MOP guidance layer to influence action selection without rewriting agent logic: `StatefulAgent.adjustActionsByGUITree()` is the designated hook where the mounted scoring passes call `action.setPriority(int)` to promote preferred actions before the agent's selection step.

For `SataAgent` the step is a fixed sequence of owners (`decision-pipeline`), mounted by group (`guidance-groups`). The restart is a guard stage that runs before any selection, so a restart never replaces an action already chosen and the step records `dec.src="restart"` with its kind; `RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart`. The LLM hooks consult declared gates rather than the SATA buffer: a new state reached mid-path may go to the LLM, and the buffer is then abandoned without weakening. The fuzz toss is drawn from its own stream once per step, after the step's action events are generated. The ADDONS mechanisms of the engine — `MODEL_MENU`, the dynamic epsilon and the two event-generation guards — are read from the plan, so a cell without ADDONS behaves as upstream APE. Every key the engine reads through the plan is resolved once, at startup, into the run plan (`RunContext.current().spec()`), with its default in `KeyOwnership`.

## Data Contracts

### Input

- `--ape <strategy>: String` — selects the exploration strategy; the accepted values are `sata` (default), `random` and `replay` (with `--ape-replay <log>`); any other value aborts before step 1; passed as a Monkey command-line argument (source: `Monkey.main()`)
- `--running-minutes <N>: int` — wall-clock time limit in minutes after which exploration stops (source: Monkey command-line)
- `-v <N>: int` — maximum number of Monkey events; used as an alternative stop condition (source: Monkey command-line)
- `/data/local/tmp/ape.properties` or `/sdcard/ape.properties: Properties file` — optional key-value configuration overrides, resolved at startup with the command line into the run plan (`RunSpec`); an unknown, retired or ill-typed key aborts the run before the first event (source: device filesystem)
- `AccessibilityNodeInfo: Android API` — live accessibility tree of the current screen, fetched by `AndroidDevice` via `UiAutomation` at each step (source: Android AccessibilityService)
- `ComponentName: Android API` — identity of the foreground activity, used to scope states per activity (source: `ActivityManager`)

### Error

- `StopTestingException` — thrown by the agent to signal a clean stop condition (time limit or step limit reached); caught by `MonkeySourceApe` to terminate the loop gracefully.
- `BadStateException` — thrown with `"No available action on the current state"` by `StatefulAgent.handleNullAction()` (the last rung of the `SataChain` stage, and `RandomAgent`'s fallback) when its roulette yields no valid action, and by the `SataChain` stage itself when every rung returns null; caught in `ApeAgent.updateStateWrapper`, which triggers a recovery path (restart or back navigation).
- `NoValidActionException` — thrown when action validation fails after all candidates are exhausted; causes the loop to attempt a forced restart.
- `OutOfMemoryError` — possible but unhandled; caused by retaining all `GUITree` objects in `treeHistory` lists throughout the session.

## Invariants

- **INV-EXPL-29**: Teardown SHALL be step-isolated: a `Throwable` thrown by one teardown step SHALL NOT prevent any subsequent teardown step from executing. The steps are the rotation restore and `MonkeySourceApe.tearDown()` in `Monkey.run`'s `finally`; `disconnect`, `agentTearDown` and `imageWriters` in `MonkeySourceApe.tearDown`; and `flushPendingStep`, `superTearDown`, `coverageDump`, `actionCounters`, `activityNodes`, `namingDump`, `modelCounters` and `runEnd` in `StatefulAgent.tearDown` (each a `safeStep`). No graph-save, LLM-summary or action-history step exists.
- **INV-EXPL-31**: Each of `State` (menu action, `spec().has(Feature.MODEL_MENU)` in its constructor), `SataAgent` (dynamic epsilon, `exploration().dynamicEpsilon()` in `computeDynamicEpsilon`, true exactly when `DYNAMIC_EPSILON` is in the plan) and `MonkeySourceApe.generateEvents` (the foreign-activity and tree/package guards, `spec().has(Feature.FOREIGN_ACTIVITY_GUARD)` and `spec().has(Feature.TREE_PACKAGE_GUARD)`) SHALL decide through the plan (`RunContext.current().spec()`). None of them SHALL read a `Config` field for it. On a plan without the `ADDONS` group, each of these mechanisms SHALL behave as upstream APE.

## RENAMED Requirements

- FROM: `### Requirement: RandomAgent — Stateless Uniform Random Selection`
- TO: `### Requirement: RandomAgent — Buffer, Then Priority-Weighted Roulette`

## REMOVED Requirements

### Requirement: StatefulAgent — BFS and DFS Traversal Modes

**Reason**: No BFS or DFS agent exists. `ApeAgent.createAgent` accepts only `sata`, `random` and `replay`, and the plan aborts on any other agent type, `bfs` and `dfs` included ("Strategy Selection").

**Migration**: None. `--ape bfs` and `--ape dfs` abort before step 1 with a diagnostic naming `{sata, random, replay}`.

## MODIFIED Requirements

### Requirement: RandomAgent — Buffer, Then Priority-Weighted Roulette

`RandomAgent.selectNewActionNonnull()` SHALL first take the next buffered action (`selectNewActionFromBuffer()`,
validated); when there is none, it SHALL call `selectNewActionRandomly()`, which draws a **priority-weighted**
roulette over all of the state's actions (`State.randomlyPickAction(getRandom())`, `ActionFilter.ALL`: an action is
picked with probability proportional to its priority, via `random.nextInt(total priority)`), and validate the
pick; when that yields no valid action, it SHALL fall back to `handleNullAction()` (a priority roulette over the
validated actions, or `BadStateException`). The priorities are those `StatefulAgent.adjustActionsByGUITree()`
assigned: the upstream base priority plus the mounted scoring passes. `RandomAgent` uses none of SATA's directed
navigation (no ABA, no early-stage, no trivial-activity or epsilon-greedy rungs) and has no decision pipeline.
`RandomAgent` MUST override `onGraphStable()` and `onStateStable()` to return `false`, so no restart is triggered by
graph or state stability. `RandomAgent` does not maintain any per-state or per-action counters of its own.

#### Scenario: Action selection with multiple candidates

- **WHEN** `RandomAgent.selectNewActionNonnull()` is called with an empty buffer
- **AND** the current state has 5 actions with priorities 10, 10, 20, 20, 40
- **THEN** the selected action SHALL be drawn from the agent stream by the priority roulette, with probabilities 0.1, 0.1, 0.2, 0.2 and 0.4
- **AND WHEN** the buffer holds a planned action that is still valid
- **THEN** that action SHALL be returned and no roulette SHALL be drawn

#### Scenario: Graph stable — no restart

- **WHEN** `RandomAgent.onGraphStable(100)` is called
- **THEN** the return value SHALL be `false`
- **AND** no restart event SHALL be enqueued as a result

### Requirement: Fuzzing Integration

`ape.doFuzzing` and `ape.fuzzingRate` are BASE keys of the feature `FUZZING`, and `MonkeySourceApe` SHALL read them from the plan (`spec`), never from `Config` (design D-B6, extended by author decision A10). When `ape.doFuzzing=false`, `FUZZING` is absent from the plan and the agent never injects random `FUZZ` actions outside the structured exploration loop. When `ape.doFuzzing=true` (the jar default), at each step the agent MAY inject a random `FuzzAction` with probability `ape.fuzzingRate` (default: `0.02`, i.e., 2% of steps). The toss SHALL come from `StepRng`'s own `fuzz` stream (`StepRng.fuzzToss`, `decision-pipeline` INV-DP-13), not from `RandomHelper`. It is drawn once per step in `MonkeySourceApe.generateEvents`, after the step's action events are generated, whether or not fuzzing is enabled and whatever the step's owner, so that enabling fuzzing or changing its rate never shifts a helper-stream draw. The injection happens when `FUZZING` is in the plan, the toss holds, and `Agent.canFuzzing()` admits. `canFuzzing()` is `false` on a step whose selected action's activity has been visited fewer than `ape.fuzzingActivityVisitThreshold` (default: `10`) times (`StatefulAgent.checkFuzzing` sets `disableFuzzing`), preventing fuzzing from disrupting early exploration, and always `false` under `ReplayAgent`.

#### Scenario: Fuzzing disabled

- **WHEN** `ape.doFuzzing=false`
- **THEN** `FUZZING` SHALL be absent from the plan, so `Agent.canFuzzing()` is never consulted and no `FuzzAction` is generated at any step
- **AND** no `FuzzAction` SHALL be injected into the event queue
- **AND** the fuzz toss SHALL still be drawn from the `fuzz` stream once on every step, and no `RandomHelper` draw SHALL be made for fuzzing

#### Scenario: Fuzzing fires at expected rate

- **WHEN** `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **AND** the current activity's visit count reaches `ape.fuzzingActivityVisitThreshold` (default: `10`)
- **THEN** across a large number of steps, approximately 2% of steps SHALL inject a `FuzzAction`
- **AND** the remaining 98% of steps SHALL proceed through the normal action-selection path

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a supported configuration channel; note that `Config` builds its `Properties` with `System.getProperties()` as defaults, so a system property could reach a key that `Config` reads without plan validation or echo. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; tests construct `RunSpec` values instead of mutating `Config`. `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority. A key that the plan owns SHALL be read from the plan (`RunContext.current().spec()`) and has no `Config` field: in particular `ape.doFuzzing`, `ape.fuzzingRate` and `ape.checkRestart` are BASE keys read from the plan (design D-B6, extended by author decision A10).

The table below lists the configuration keys relevant to the exploration engine with their types and defaults:

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart (BASE) |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart (BASE) |
| `ape.activityStableRestartThreshold` | int | 200 in the ADDONS block | Steps in same activity before forced restart. Activation key of the ADDONS member `ACTIVITY_STABLE_RESTART` (POSITIVE, neutral 0), no longer a BASE key; when the feature is absent (ADDONS off, or the key `0`) the threshold is the upstream `Integer.MAX_VALUE` (design D-B5) |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection (BASE, read from the plan) |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step (BASE, read from the plan) |
| `ape.checkRestart` | boolean | true | Mount the `Restart` guard stage (BASE, read from the plan) |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

The keys `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` are retired (persistence removal) and abort resolution if present. The keys `ape.mopDataPath` (renamed `ape.staticDataPath`), `ape.leastVisitedPriorityTiebreak` (replaced by the X1 scorer-sum tie) and `ape.mopTargetPickCap` (replaced by `ape.mopCoinLedgerMaxInteractions`, k, the MOP coin's limit on executions by any owner per activity and `xpath|ActionType` key (default 1)) are retired and abort resolution with `retired_key` (`run-spec`).

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** the plan's `spec().exploration().graphStableRestartThreshold()` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields and all BASE plan keys SHALL take their hardcoded default values, and no group member SHALL be in the plan, so `ACTIVITY_STABLE_RESTART` is absent and the activity threshold is `Integer.MAX_VALUE`
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

#### Scenario: the activity-stable threshold follows ADDONS
- **WHEN** the `as` cell resolves, whose ADDONS block sets `ape.activityStableRestartThreshold=200`
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be in the plan and the activity-stability check SHALL raise the restart flag once the counter exceeds `200`
- **AND WHEN** the `base` cell resolves
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be absent and the threshold SHALL be `Integer.MAX_VALUE`, so the activity check never raises the flag

### Requirement: Dynamic Epsilon Decay

`SataAgent.egreedy()` SHALL compute a coverage-adaptive epsilon (`SataAgent.computeDynamicEpsilon()`) when the plan carries `DYNAMIC_EPSILON` (key `ape.dynamicEpsilon`). That feature is a member of the ADDONS group, so it is absent from the `base` and `mop` cells (design D-B1). `SataAgent` SHALL read the feature from the plan, through its injected exploration parameters (`exploration().dynamicEpsilon()`, true exactly when `DYNAMIC_EPSILON` is in the plan), never from `Config`. The bounds are the `DYNAMIC_EPSILON` parameter keys `ape.minEpsilon` (`0.02`) and `ape.maxEpsilon` (`0.15`), read through the plan (`exploration().minEpsilon()`/`maxEpsilon()`); `Config` has no field for either. The epsilon value SHALL be interpolated between `ape.minEpsilon` and `ape.maxEpsilon` based on the current state's UI coverage gap:

```
epsilon = minEpsilon + (maxEpsilon - minEpsilon) * coverageGap
```

Where `coverageGap` is obtained from `UICoverageTracker.getCoverageGap(newState)`. The SataAgent accesses the tracker via the `getCoverageTracker()` accessor inherited from `StatefulAgent`.

**Scope note**: `egreedy()` is called from `selectNewActionEpsilonGreedyRandomly()`, which is one of the last steps in the SATA selection chain. Dynamic epsilon has incremental impact — it improves decisions that reach epsilon-greedy, but does not affect decisions resolved earlier (LLM hooks, buffer, early-stage, trivial activity).

When the plan does not carry `DYNAMIC_EPSILON`, or no coverage tracker exists, upstream APE's behavior SHALL be preserved: epsilon is the fixed value `defaultEpsilon`, the BASE plan key `ape.defaultEpsilon` (`spec.exploration().defaultEpsilon()`).

#### Scenario: New state (high gap)
- **WHEN** `egreedy()` is called in a state with coverageGap=1.0 and maxEpsilon=0.15, minEpsilon=0.02
- **THEN** the effective epsilon SHALL be 0.15

#### Scenario: Fully explored state
- **WHEN** `egreedy()` is called in a state with coverageGap=0.0
- **THEN** the effective epsilon SHALL be 0.02 (minEpsilon)

#### Scenario: Dynamic epsilon disabled
- **WHEN** the plan does not carry `DYNAMIC_EPSILON` (the `base` cell, or `ape.dynamicEpsilon=false`)
- **THEN** `egreedy()` SHALL use the fixed `defaultEpsilon` value (`0.05`)

### Requirement: Config Flags for Dynamic Epsilon

Coverage-adaptive epsilon is the ADDONS member feature `DYNAMIC_EPSILON`, activated by `ape.dynamicEpsilon` (boolean, jar default `true`, depends on `ADDONS`; design D-B1). Every read site SHALL decide through the plan — `SataAgent` reads `exploration().dynamicEpsilon()`, which holds exactly when the feature is in the plan — and `Config` has no `dynamicEpsilon` field (design D-B6). With `ape.addons=false` (the jar default and the `base`/`mop` cells) the feature is absent whatever its key says, unless the key is set explicitly, which aborts resolution with `MISSING_DEPENDENCY`.

The interpolation bounds are the parameter keys of `DYNAMIC_EPSILON`, read through the plan (`spec.exploration().minEpsilon()`/`maxEpsilon()`, in `SataAgent.computeDynamicEpsilon`); `Config` has no field for either:

| Key | Type | Default | Description |
|------|------|-------|-------------|
| `ape.maxEpsilon` | double | `0.15` | Epsilon when coverage gap is 1.0 |
| `ape.minEpsilon` | double | `0.02` | Epsilon when coverage gap is 0.0 |

#### Scenario: Dynamic epsilon flags default correctly
- **WHEN** `ape.properties` sets `ape.addons=true` and not `ape.dynamicEpsilon`
- **THEN** `DYNAMIC_EPSILON` SHALL be in the plan's `features`, and `egreedy()` SHALL interpolate between `ape.minEpsilon = 0.02` and `ape.maxEpsilon = 0.15`
- **AND WHEN** it sets neither `ape.addons` nor `ape.dynamicEpsilon`
- **THEN** `DYNAMIC_EPSILON` SHALL be absent and `egreedy()` SHALL use the fixed `defaultEpsilon` (`0.05`)

### Requirement: Off-Screen Action Handling

When `generateClickEventAt` finds that the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <rect>` line, where `<rect>` is the node rect handed to `generateClickEventAt`. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs). The invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and logs `Invalid bounds: <bounds>` followed by the same `[APE-RV] off-screen action dropped: <rect>` line. Visit/coverage crediting of the dropped action is unchanged (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

For coordinate-carrying actions without a resolved node (`MODEL_LLM_TAP`), the rect handed to
`generateClickEventAt` MUST be the minimal non-degenerate rect anchored at the decided pixel —
`(x, y, x+1, y+1)` — never the zero-area `(x, y, x, y)`: an empty rect is unconditionally dropped by
both guard branches, which silently disables the action (cmpm finding A1: 16,625/16,625 taps
dropped). The validity domain of a coordinate tap is the **physical display bounds**
(`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds: the coordinate is
decided from a full-display screenshot, and its legitimate targets include cross-window elements
(IME keyboards, dialogs) that lie outside the app root node (measured: 6/7 thumbkey gate taps and
~50% of cmpm v2 taps dropped on the root-node domain). A coordinate outside the display produces
the log line and no event; node actions keep the root-node visible-bounds domain unchanged.

#### Scenario: off-screen node produces no event
- **WHEN** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped: <rect>` line SHALL be emitted, naming the node rect
- **AND** no click SHALL be delivered to the screen center

#### Scenario: on-screen node unchanged
- **WHEN** the node's bounds intersect the visible screen
- **THEN** click generation SHALL be identical to the previous implementation

#### Scenario: in-bounds coordinate tap is not dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(540, 1158)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)`
- **THEN** the rect handed to the guard SHALL be `(540, 1158, 541, 1159)`
- **AND** a touch down/up pair SHALL be enqueued at that point
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: coordinate tap below the app window but inside the display injects
- **WHEN** the app root-node bounds are `(0, 0, 1080, 1400)`, the display bounds are
  `(0, 0, 1080, 1920)`, and a `MODEL_LLM_TAP` carrying pixel `(424, 1618)` is dispatched (the
  IME band that the llm-tap-injection gate measured as dropped)
- **THEN** a touch down/up pair SHALL be enqueued at `(424, 1618)`
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: out-of-display coordinate tap is dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(1080, 500)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)` (x equals the exclusive right edge)
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted
