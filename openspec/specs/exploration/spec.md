# Specification: Exploration Engine

## Purpose

APE-RV's exploration engine is responsible for systematically exercising an Android application under test by navigating its GUI state space. The core motivation for model-based exploration — rather than pure random input generation as in AOSP Monkey — is that random exploration wastes most of its budget re-visiting already-seen states and rarely penetrates deeply into an app's feature set. APE-RV builds an explicit, labeled graph of abstract GUI states and state transitions as it runs, enabling the agent to reason about which parts of the application have already been covered and which have not. This directed exploration dramatically increases code and feature coverage within a fixed time budget.

The central abstraction mechanism, inherited from the original APE tool (ICSE 2019), is CEGAR-style (Counterexample-Guided Abstraction Refinement). The `NamingFactory` component maps concrete `GUITree` snapshots to abstract `State` nodes via a configurable `Naming` (a level in a widget-attribute lattice). When the abstraction is too coarse — detected by observing non-deterministic transitions from a single abstract state — the naming is automatically refined to a finer level, splitting the offending state. This adaptive refinement ensures the model stays sound (no false merging of distinct screens) without over-specifying upfront.

APE-RV supports selectable exploration strategies, each suited to a different use case. `SataAgent` (`--ape sata`) is the primary strategy and implements an epsilon-greedy heuristic that aggressively prioritises unvisited actions while using graph-guided navigation to reach under-explored parts of the app. `RandomAgent` (`--ape random`) provides an experimental baseline using priority-weighted random selection over model actions — it still uses the `StatefulAgent` priority infrastructure but without SATA's directed navigation. `ReplayAgent` (activated via the `ape.replayLog` configuration key) replays a recorded action sequence for deterministic regression testing. Phase 2 will add `ApeAgent` (`--ape ape`, full CEGAR naming-refinement), BFS (`--ape bfs`), and DFS (`--ape dfs`) as additional selectable strategies. The strategy is selected once at startup and does not change during a session.

The exploration loop, implemented inside `MonkeySourceApe.nextEventImpl()`, is the heartbeat of the system. Each iteration captures the current screen as a `GUITree`, maps it to an abstract `State`, asks the active `Agent` to choose a `ModelAction`, translates that action into one or more `ApeEvent` objects (click, drag, key press, scroll gesture), enqueues those events in Monkey's event queue for execution on the device, then captures the resulting screen and updates the model. The loop terminates when a wall-clock time limit (`--running-minutes N`) or an event-count limit (`-v N`) is reached; on termination the serialised exploration graph is saved to disk. The priority system on `ModelAction` objects allows extensions such as the APE-RV Phase 3 MOP guidance layer to influence action selection without rewriting agent logic: `StatefulAgent.adjustActionsByGUITree()` is the designated hook where a higher-level component may call `action.setPriority(int)` to promote preferred actions before the agent's selection step.

## Data Contracts

### Input

- `--ape <strategy>: String` — selects the exploration strategy; currently accepted values are `sata` (default) and `random`; Phase 2 will add `ape`, `bfs`, `dfs`; passed as a Monkey command-line argument (source: `Monkey.main()`)
- `--running-minutes <N>: int` — wall-clock time limit in minutes after which exploration stops (source: Monkey command-line)
- `-v <N>: int` — maximum number of Monkey events; used as an alternative stop condition (source: Monkey command-line)
- `/data/local/tmp/ape.properties` or `/sdcard/ape.properties: Properties file` — optional key-value configuration overrides loaded at startup by `Config` (source: device filesystem)
- `AccessibilityNodeInfo: Android API` — live accessibility tree of the current screen, fetched by `AndroidDevice` via `UiAutomation` at each step (source: Android AccessibilityService)
- `ComponentName: Android API` — identity of the foreground activity, used to scope states per activity (source: `ActivityManager`)

### Output

- `*.png screenshots: files` — per-step or per-new-state PNG screenshots written to the output directory (when `ape.takeScreenshot=true`, default `true`)
- `*.xml GUITree files: files` — XML serialisation of the accessibility tree at each step (when `ape.saveGUITreeToXmlEveryStep=true`, default `true`)
- `ape_log: logcat entries` — structured log lines emitted via `Logger` for every action selected, strategy event type, and state transition

### Side-Effects

- **Android device**: GUI events (touches, key presses, drags) are injected into the device via `UiAutomation.injectInputEvent()`, causing the app under test to change state.
- **App lifecycle**: The exploration engine may force-kill and restart the app under test (via `EVENT_RESTART` or `EVENT_CLEAN_RESTART` action types) when stability thresholds are exceeded.
- **Model graph**: The in-memory `Graph` object is mutated on every step as new `State` nodes and `StateTransition` edges are discovered.
- **NamingFactory lattice**: If `ape.evolveModel=true` (default `true`), the naming abstraction may be refined mid-session, causing existing `State` objects to be split and the model to be rebuilt.
- **Stability counters**: `StatefulAgent` maintains `graphStableCounter`, `stateStableCounter`, and `activityStableCounter` that are incremented each step and reset on graph/state/activity changes.

### Error

- `StopTestingException` — thrown by the agent to signal a clean stop condition (time limit or step limit reached); caught by `MonkeySourceApe` to terminate the loop gracefully.
- `BadStateException` — thrown by `StatefulAgent.selectNewActionNonnull()` when no valid action is available on the current state; triggers a recovery path (restart or back navigation).
- `NoValidActionException` — thrown when action validation fails after all candidates are exhausted; causes the loop to attempt a forced restart.
- `OutOfMemoryError` — possible but unhandled; caused by retaining all `GUITree` objects in `treeHistory` lists throughout the session.

## Invariants

- **INV-EXPL-01**: The exploration loop SHALL continue dispatching actions until either `StopTestingException` is thrown (time/step limit reached) or the process is killed externally. No other condition MAY cause silent loop exit.
- **INV-EXPL-02**: `ActionType.MODEL_BACK.requireTarget()` and `ActionType.MODEL_MENU.requireTarget()` SHALL both return `false`. Neither `MODEL_BACK` nor `MODEL_MENU` requires a widget target; both map directly to Android key events.
- **INV-EXPL-04**: `ActionType.MODEL_CLICK.requireTarget()`, `MODEL_LONG_CLICK.requireTarget()`, `MODEL_SCROLL_BOTTOM_UP.requireTarget()`, `MODEL_SCROLL_TOP_DOWN.requireTarget()`, `MODEL_SCROLL_LEFT_RIGHT.requireTarget()`, and `MODEL_SCROLL_RIGHT_LEFT.requireTarget()` SHALL each return `true`.
- **INV-EXPL-05**: `ActionType.isModelAction()` SHALL return `true` for all `MODEL_*` enum constants (`MODEL_BACK`, `MODEL_MENU`, `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`) and `false` for all other constants (`PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`).
- **INV-EXPL-06**: Every `State` object SHALL have non-null `backAction` and `menuAction` fields, each holding a `ModelAction` of their respective types (`MODEL_BACK` and `MODEL_MENU`). Both fields are initialised in the `State` constructor and MUST NOT be set to null at any point. (Unchanged by `rv-scoring-pipeline`: `Config.modelMenuEnabled` gates only whether the `menuAction` is added to the State's action *set* — the field itself stays non-null in every arm, including `ape_pure`, so this invariant holds verbatim.)
- **INV-EXPL-13**: `MODEL_MENU` SHALL be positioned in the `ActionType` enum after `MODEL_BACK` and before `MODEL_CLICK`. This placement ensures that `requireTarget()`'s ordinal range check (`MODEL_CLICK` through `MODEL_SCROLL_RIGHT_LEFT`) continues to correctly identify target-requiring actions without any change to the range boundaries.
- **INV-EXPL-09**: When `SataAgent` triggers a forced app restart due to graph stability (i.e., `graphStableCounter` reaching `ape.graphStableRestartThreshold`), the `graphStableCounter` MUST be reset to zero immediately after the restart is initiated.
- **INV-EXPL-10**: `RandomAgent` extends `StatefulAgent` and uses the same priority-weighted selection infrastructure. It does NOT implement a separate pure-random algorithm. The distinction from `SataAgent` is that `RandomAgent` uses `StatefulAgent`'s base priority assignment without SATA's directed graph navigation heuristics.
- **INV-EXPL-11**: `StatefulAgent.adjustActionsByGUITree()` SHALL be called after base priority assignment and before the agent's selection step. Any priority modifications made by external components (e.g., MOP guidance in Phase 3) MUST be applied inside or after this method, not before it.
- **INV-EXPL-12**: A `ModelAction` with a higher `priority` value SHALL be preferred over one with a lower `priority` value when `RandomHelper.randomPickWithPriority()` is used for selection within `StatefulAgent`.
- **INV-EXPL-14**: Given identical `-s` seed, APK, and configuration, the sequence of `RandomHelper` draws SHALL be identical across runs.
- **INV-EXPL-15**: No run SHALL spend more than 101 consecutive `checkAppActivity` iterations waiting for a foreground package without triggering a relaunch.
- **INV-EXPL-16**: `tearDown()` SHALL run on every termination path of the exploration loop, normal or abnormal, **and SHALL NOT replace the in-flight exception**: a `Throwable` thrown by any teardown step SHALL be caught and logged with its full stack trace inside the teardown chain, never propagated out of the `finally` block in `Monkey.run`.
- **INV-EXPL-29**: Teardown SHALL be step-isolated: a `Throwable` thrown by one teardown step (rotation restore, `disconnect()`, LLM summary, `super.tearDown()`, coverage dump, action-history save, action counters, activity nodes, naming dump, model counters — the graph-save step no longer exists) SHALL NOT prevent any subsequent teardown step from executing.
- **INV-EXPL-17**: Per-step debug artifacts (PNG/XML) SHALL be written only when explicitly enabled via `ape.properties`.
- **INV-EXPL-18**: Every fuzz gesture branch SHALL emit exactly one event per invocation.
- **INV-EXPL-19**: No touch event SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own bounds — or, for coordinate-carrying actions without a node (`MODEL_LLM_TAP`), other than the action's own decided coordinate.
- **INV-EXPL-30**: `MODEL_LLM_TAP` dispatch SHALL construct a non-degenerate rect `(x, y, x+1, y+1)` for the decided pixel `(x, y)`, and its validity domain SHALL be the physical display bounds (`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds. A tap whose pixel lies inside the display SHALL enqueue a touch down/up pair; a tap outside it SHALL be dropped with the `[APE-RV] off-screen action dropped` log line and no event. Node actions keep the root-node visible-bounds domain.
## Requirements
### Requirement: Strategy Selection

The exploration strategy MUST be selected at process startup via the `--ape <strategy>` command-line argument passed to `Monkey`, and the agent type is **validated plan data**: it is carried by the resolved `RunSpec` (`run-spec` capability), not by a mutable property. The argument value is a case-sensitive string. The three legal values are `sata` (creates `SataAgent`), `random` (creates `RandomAgent`), and `replay` (creates `ReplayAgent`; requires `--ape-replay <log>`). If the argument is absent, the strategy SHALL default to `sata` (a documented default; the aperv deployment always passes the flag). If the argument does not match a legal value — including `bfs`, `dfs`, and `ape`, which previously fell through silently to `SataAgent` — the process SHALL abort with a diagnostic naming the valid set, before step 1. `ApeAgent.createAgent` SHALL contain no fallback arm.

The agent type SHALL NOT be settable from a properties file: `ape.agentType` (and `ape.replayLog`) appearing in `/data/local/tmp/ape.properties` or `/sdcard/ape.properties` is a retired-key abort — a stray device file can no longer swap the agent of a run. The strategy object is constructed once and shared for the entire session; it MUST NOT be replaced or re-instantiated during a running session.

#### Scenario: Valid strategy argument provided

- **WHEN** the process is launched with `app_process ... com.android.commands.monkey.Monkey -p com.example.app --ape sata`
- **THEN** a `SataAgent` instance SHALL be created and used for all `Agent.updateState()` calls for the duration of the session
- **AND** no other agent type SHALL be instantiated

#### Scenario: Strategy argument is `random`

- **WHEN** the process is launched with `--ape random`
- **THEN** a `RandomAgent` instance SHALL be created
- **AND** `RandomAgent.selectNewActionNonnull()` SHALL be called at each exploration step instead of any `SataAgent` method

#### Scenario: Strategy argument is absent

- **WHEN** the process is launched without an `--ape` argument
- **THEN** the strategy SHALL default to `sata` and a `SataAgent` SHALL be created

#### Scenario: Unknown strategy aborts instead of falling back

- **WHEN** the process is launched with `--ape bfs`
- **THEN** the process SHALL exit nonzero with a diagnostic naming the valid set `{sata, random, replay}`
- **AND** no agent SHALL be constructed

#### Scenario: Properties file cannot swap the agent

- **WHEN** `/sdcard/ape.properties` contains `ape.agentType=random` and the process is launched with `--ape sata`
- **THEN** the process SHALL abort with a retired-key diagnostic (`ape.agentType` is CLI-only)

---

### Requirement: Exploration Loop Termination

The exploration loop inside `MonkeySourceApe.nextEventImpl()` SHALL run continuously until a stop condition is reached. Two stop conditions are supported: (1) elapsed wall-clock time exceeds the value specified by `--running-minutes N`, and (2) the total Monkey event count reaches the value specified by `-v N`. When either condition is detected, the agent SHALL throw `StopTestingException`, which `MonkeySourceApe` catches to exit the loop. The loop MUST NOT exit silently on any other condition. Crashes and ANRs in the app under test MUST be logged and counted but MUST NOT terminate the loop unless a stop condition is also triggered.

#### Scenario: Time limit reached

- **WHEN** `--running-minutes 30` is specified and 30 minutes of wall-clock time have elapsed since the session started
- **THEN** the agent SHALL throw `StopTestingException`
- **AND** `MonkeySourceApe` SHALL catch the exception and proceed to the teardown phase (the teardown chain; **no** `sataModel.obj` or `sataGraph.vis.js` is written — the persistence protocol and the graph dumps are deleted by this change)

#### Scenario: App crash does not stop exploration

- **WHEN** the app under test crashes (process dies) during a step
- **AND** the time limit has not been reached
- **THEN** `Agent.appCrashed()` SHALL be called and the crash SHALL be logged
- **AND** the agent SHALL initiate an app restart via an `EVENT_RESTART` action
- **AND** the exploration loop SHALL continue from the restarted app state

### Requirement: GUITree Capture and State Abstraction

At each exploration step, `MonkeySourceApe` MUST capture the current screen as a `GUITree` by fetching the root `AccessibilityNodeInfo` from Android's accessibility service via `AndroidDevice`. The raw `GUITree` MUST then be passed to the `NamingFactory`/`Model` abstraction layer, which maps it to an abstract `State` using the current `Naming` level. If `ape.evolveModel=true` (default `true`), the naming layer MAY refine the abstraction after the step completes if non-determinism is detected. The resulting abstract `State` is the input to `Agent.selectAction()`.

#### Scenario: First visit to a new screen

- **WHEN** the accessibility tree differs from all previously seen trees such that no existing `State` matches
- **THEN** a new `State` SHALL be created and added to the `Graph`
- **AND** the new `State` SHALL be initialised with a `backAction` (`MODEL_BACK`), a `menuAction` (`MODEL_MENU`), plus one `ModelAction` per actionable widget identified by the current `Naming`

#### Scenario: Revisit of a known screen

- **WHEN** the accessibility tree maps to an existing abstract `State` under the current `Naming`
- **THEN** no new `State` SHALL be created
- **AND** the visit counter on the existing `State` SHALL be incremented

---

### Requirement: ActionType Classification

`ActionType` is an enum in `com.android.commands.monkey.ape.model.ActionType` that classifies every action the exploration engine can perform. The `requireTarget()` predicate SHALL return `true` if and only if the action type requires a specific widget node as its target (i.e., the action is a gesture or input directed at a UI element). The `isModelAction()` predicate SHALL return `true` for all `MODEL_*` constants and `false` for all event and phantom constants.

The full set of `MODEL_*` action types and their `requireTarget()` values are:

| ActionType | requireTarget() | Description |
|---|---|---|
| `MODEL_BACK` | `false` | BACK key press; no widget target |
| `MODEL_MENU` | `false` | MENU key press; no widget target |
| `MODEL_CLICK` | `true` | Tap on a widget node |
| `MODEL_LONG_CLICK` | `true` | Long-press on a widget node |
| `MODEL_SCROLL_BOTTOM_UP` | `true` | Scroll-up gesture on a widget |
| `MODEL_SCROLL_TOP_DOWN` | `true` | Scroll-down gesture on a widget |
| `MODEL_SCROLL_LEFT_RIGHT` | `true` | Swipe-right gesture (ViewPager tabs) |
| `MODEL_SCROLL_RIGHT_LEFT` | `true` | Swipe-left gesture (ViewPager tabs) |

Non-model types (`PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`) SHALL have `isModelAction()` return `false` and are not used as graph edge labels. `EVENT_TRIGGER_ACTIVITY` is the stagnation-triggered activity launch step (component-triggering spec): `requireTarget()` SHALL return `false` for it, and its event generation dispatches an activity-launch intent instead of a GUI gesture.

#### Scenario: requireTarget() on BACK

- **WHEN** `ActionType.MODEL_BACK.requireTarget()` is called
- **THEN** the return value SHALL be `false`

#### Scenario: requireTarget() on MENU

- **WHEN** `ActionType.MODEL_MENU.requireTarget()` is called
- **THEN** the return value SHALL be `false`

#### Scenario: requireTarget() on CLICK

- **WHEN** `ActionType.MODEL_CLICK.requireTarget()` is called
- **THEN** the return value SHALL be `true`

#### Scenario: isModelAction() on MODEL_MENU

- **WHEN** `ActionType.MODEL_MENU.isModelAction()` is called
- **THEN** the return value SHALL be `true`

#### Scenario: isModelAction() on EVENT_RESTART

- **WHEN** `ActionType.EVENT_RESTART.isModelAction()` is called
- **THEN** the return value SHALL be `false`

#### Scenario: predicates on EVENT_TRIGGER_ACTIVITY

- **WHEN** `ActionType.EVENT_TRIGGER_ACTIVITY.requireTarget()` and `ActionType.EVENT_TRIGGER_ACTIVITY.isModelAction()` are called
- **THEN** both SHALL return `false`

### Requirement: OptionsMenu Systematic Exploration (MODEL_MENU)

Every `State` object SHALL hold a `menuAction` field of type `ModelAction(this, ActionType.MODEL_MENU)`, initialised in the `State` constructor immediately after `backAction`. The field SHALL be exposed via `State.getMenuAction()` and SHALL be non-null for the life of the state. This mirrors the `backAction` / `getBackAction()` pattern exactly.

Inclusion of the `menuAction` in the state's **selectable** action set is gated by `Config.modelMenuEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `modelMenuEnabled` is `true` (default), the `menuAction` SHALL be included in the array returned by `State.getActions()`, exactly as before this change. When `modelMenuEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), the `menuAction` SHALL NOT be included in `State.getActions()` and the agent SHALL never select `MODEL_MENU`; the field SHALL still be constructed and returned by `State.getMenuAction()` (so `INV-EXPL-06` holds). This reproduces upstream APE, which has no model-level options-menu action.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** when `Config.modelMenuEnabled` is `true` (default) the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU excluded from selection when modelMenuEnabled is false
- **WHEN** `Config.modelMenuEnabled` is `false` and a new `State` is constructed
- **THEN** `state.getMenuAction()` MUST still return a non-null `ModelAction` of type `MODEL_MENU`
- **AND** `state.getActions()` MUST NOT contain the `menuAction`
- **AND** the agent MUST never select `MODEL_MENU` for that state

#### Scenario: MODEL_MENU event generation
- **WHEN** `MonkeySourceApe.generateEventsForActionInternal()` is called with a `ModelAction` whose type is `MODEL_MENU`
- **THEN** `generateKeyMenuEvent()` SHALL be called
- **AND** no target `GUITreeNode` SHALL be required or consulted

#### Scenario: MODEL_MENU validation always passes
- **WHEN** `MonkeySourceApe.validateResolvedAction()` is called with a `ModelAction` of type `MODEL_MENU`
- **THEN** the method SHALL return `true`
- **AND** no widget validator (`validateClickAction`, `validateScrollAction`) SHALL be invoked

### Requirement: SataAgent — Unvisited Action Priority

`SataAgent` is the default and primary exploration strategy. Its core heuristic is to exhaustively visit all unvisited actions before re-visiting known actions. An action is considered unvisited when `ModelAction.isUnvisited()` returns `true` (i.e., its execution count is zero for that named widget). Among unvisited actions in the current state, `SataAgent` MUST check `backAction` first, then `menuAction`, then widget-targeted actions ordered by their natural priority. Only when all actions in the current state have been visited does the agent fall back to the epsilon-greedy path.

The `menuAction` check is added to `SataAgent.selectNewActionEpsilonGreedyRandomly()` immediately after the existing `backAction` unvisited check and before the epsilon-greedy fallback.

#### Scenario: State has unvisited BACK action

- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` passes `ActionFilter.ENABLED_VALID` and `ModelAction.isUnvisited()` returns `true`
- **THEN** the BACK action SHALL be returned immediately, before any MENU or widget action is considered

#### Scenario: State has visited BACK but unvisited MENU action

- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` has already been visited
- **AND** the current `State`'s `menuAction` passes `ActionFilter.ENABLED_VALID` and `ModelAction.isUnvisited()` returns `true`
- **THEN** the MENU action SHALL be returned immediately, before any widget action or epsilon-greedy selection

#### Scenario: State has visited BACK but unvisited widget actions

- **WHEN** `SataAgent.selectNewActionEpsilonGreedyRandomly()` is called
- **AND** the current `State`'s `backAction` has already been visited
- **AND** the current `State`'s `menuAction` has already been visited
- **AND** at least one widget-targeted action in the current state is unvisited and passes `ActionFilter.ENABLED_VALID`
- **THEN** the agent SHALL apply the epsilon-greedy decision: with probability `1 - ape.defaultEpsilon` (default: `1 - 0.05 = 0.95`) the least-visited valid action SHALL be returned; with probability `ape.defaultEpsilon` (default: `0.05`) a random valid action SHALL be returned

#### Scenario: All actions in current state are visited

- **WHEN** every `ModelAction` in the current `State` (including `backAction` and `menuAction`) has been visited at least once
- **AND** no buffer path is available
- **THEN** `SataAgent` SHALL fall through to `selectNewActionEpsilonGreedyRandomly()` and apply the epsilon-greedy rule over all valid actions in the current state

---

### Requirement: SataAgent — Forced App Restart on Graph Stability

`SataAgent` (via its superclass `StatefulAgent`) monitors whether the exploration graph has stopped growing. The counter `graphStableCounter` is incremented each step in which no new `State` or `StateTransition` is added to the `Graph`. When `graphStableCounter` reaches `ape.graphStableRestartThreshold` (default: `100`) consecutive stable steps, the agent SHALL trigger a forced app restart via an `EVENT_RESTART` action and MUST reset `graphStableCounter` to zero immediately. This prevents the exploration from getting stuck in a saturated region of the state space.

#### Scenario: Graph stable for threshold steps

- **WHEN** `graphStableCounter` reaches `100` (the value of `ape.graphStableRestartThreshold`) consecutive steps without any change to the `Graph`
- **THEN** `StatefulAgent.onGraphStable(100)` SHALL be called on the active agent
- **AND** `SataAgent.onGraphStable()` SHALL return `true`, indicating a restart should be performed
- **AND** an `EVENT_RESTART` action SHALL be enqueued
- **AND** `graphStableCounter` SHALL be reset to `0`

#### Scenario: Graph grows; counter resets

- **WHEN** a new `State` is discovered on step N (making `graphStableCounter = 0` at step N-1 become irrelevant)
- **THEN** `graphStableCounter` SHALL be reset to `0`
- **AND** the restart SHALL NOT be triggered regardless of the previous counter value

#### Scenario: RandomAgent ignores graph stability

- **WHEN** `RandomAgent` is the active strategy
- **AND** `graphStableCounter` reaches `100`
- **THEN** `RandomAgent.onGraphStable(100)` SHALL return `false`
- **AND** no forced restart SHALL be initiated by the graph-stability path

---

### Requirement: SataAgent — ABA Graph Navigation

Beyond simple greedy unvisited-action selection, `SataAgent` uses multi-step graph navigation to reach under-explored regions of the app. The ABA pattern (A → B → A) describes moving from the current state A to a "greedy state" B (a state with unvisited actions), executing actions at B, then returning to A. `SataAgent.selectNewActionEarlyStageForABA()` searches for such paths using `Graph.moveToState()`. A state B is considered a greedy state if `getGreedyActions(null, B)` returns a non-empty list. ABA navigation MUST NOT navigate to a saturated dialog state (a state with many in-edges and no forward unsaturated actions).

#### Scenario: Greedy state reachable in graph

- **WHEN** `SataAgent.selectNewActionEarlyStageForABAInternal()` is called
- **AND** there exists a state B reachable from the current state A via strong transitions (deterministic edges)
- **AND** B has at least one unvisited widget-targeted action (`getGreedyActions(A, B)` is non-empty)
- **AND** B is not a saturated dialog state
- **THEN** the first action on the shortest path from A to B SHALL be returned
- **AND** the remaining path steps SHALL be stored in the `actionBuffer` for subsequent steps

#### Scenario: No greedy state reachable

- **WHEN** `selectNewActionEarlyStageForABAInternal()` finds no state B satisfying the greedy condition
- **THEN** `null` SHALL be returned and the caller SHALL fall through to the next selection strategy

---

### Requirement: SataAgent — Trivial Activity Detection

`SataAgent` SHALL identify activities that are difficult to explore further as "trivial" and SHALL avoid spending excessive time in them. An activity is trivial when it has fewer states than `ape.trivialActivityRankThreshold` (default: `3`) OR when its visited rate is below a threshold relative to the median/mean visit count across all activities. When the current activity is non-trivial and a trivial activity has unvisited actions reachable via strong transitions, `SataAgent` SHOULD navigate to that trivial activity to exploit unexplored actions there.

#### Scenario: Current activity is trivial

- **WHEN** `SataAgent.selectNewActionForTrivialActivity()` is called
- **AND** the current state's activity is itself in the `trivialActivities` set
- **THEN** the method SHALL return `null` (no navigation needed; caller handles action selection locally)

#### Scenario: Trivial activity reachable

- **WHEN** the current state's activity is not trivial
- **AND** at least one trivial activity has a state with unvisited actions reachable by a forward (non-BACK) path
- **THEN** the first action of the shortest path to that trivial activity SHALL be returned

---

### Requirement: RandomAgent — Stateless Uniform Random Selection

`RandomAgent` MUST select actions without any reference to visit counts, state history, or graph structure. On each step, it MUST call `selectNewActionRandomly()`, which picks uniformly at random over all currently enabled, valid actions on the current state (as determined by `ActionFilter.ENABLED_VALID`). `RandomAgent` MUST override `onGraphStable()` and `onStateStable()` to return `false`, ensuring no restart is triggered by stability counters. `RandomAgent` does not maintain any per-state or per-action counters of its own.

#### Scenario: Action selection with multiple candidates

- **WHEN** `RandomAgent.selectNewActionNonnull()` is called
- **AND** the current state has 5 enabled valid actions
- **THEN** each of the 5 actions SHALL have equal probability (`0.2`) of being selected
- **AND** no preference SHALL be given to unvisited or high-priority actions

#### Scenario: Graph stable — no restart

- **WHEN** `RandomAgent.onGraphStable(100)` is called
- **THEN** the return value SHALL be `false`
- **AND** no restart event SHALL be enqueued as a result

---

### Requirement: StatefulAgent — Priority-Based Action Selection

`StatefulAgent` and its subclasses SHALL use a `priority` integer field on each `ModelAction` to break ties and express preferences. Higher numeric priority means higher preference. The method `StatefulAgent.adjustActionsByGUITree()` is called after the base priority has been assigned and before the agent selects an action. This is the designated extension point where external components MAY call `ModelAction.setPriority(int)` to boost specific actions.

When `Config.mopDataPath` is non-null, `StatefulAgent` SHALL load `MopData` at construction time and apply MOP scoring in `adjustActionsByGUITree()` after the base priority loop. The MOP scoring pass SHALL only apply to actions where `action.requireTarget() == true` AND `action.isValid() == true`. The boost is additive: `action.setPriority(action.getPriority() + MopScorer.score(...))`. When `Config.mopDataPath` is null, the MOP scoring pass is skipped entirely and the method behaves identically to its pre-Phase-3 form.

The selection method `RandomHelper.randomPickWithPriority(List<ModelAction>)` MUST prefer actions with higher priority; actions with equal priority are selected uniformly at random among that priority tier.

#### Scenario: Higher priority action preferred
- **WHEN** `StatefulAgent` has two candidate actions `actionA` (priority=10) and `actionB` (priority=1)
- **THEN** `actionA` SHALL be selected with probability proportional to its priority weight under `randomPickWithPriority`

#### Scenario: MOP boost applied to direct-reachable widget
- **WHEN** `Config.mopDataPath` points to a valid JSON AND `actionA` targets widget `btn_encrypt` which has `directMop=true`
- **THEN** `actionA.getPriority()` after `adjustActionsByGUITree()` SHALL equal `basePriority + 500`

#### Scenario: MOP scoring skipped when mopDataPath is null
- **WHEN** `Config.mopDataPath` is `null`
- **THEN** `adjustActionsByGUITree()` SHALL complete without any calls to `MopScorer`
- **AND** all action priorities SHALL equal their base SATA-assigned values

#### Scenario: Non-target actions not boosted
- **WHEN** MOP data is loaded AND `actionB` is `MODEL_BACK` (`requireTarget() == false`)
- **THEN** `actionB.getPriority()` SHALL NOT be modified by the MOP scoring pass

---

### Requirement: StatefulAgent — BFS and DFS Traversal Modes

When the strategy is `bfs` or `dfs`, `MonkeySourceApe` SHALL create a `StatefulAgent` configured with a BFS queue or DFS stack respectively. These modes do not use the ABA heuristic or epsilon-greedy selection of `SataAgent`; instead they traverse the state graph using classical breadth-first or depth-first order over unvisited states. Both modes still use the `Model` for state tracking and both apply `adjustActionsByGUITree()` for priority adjustments.

#### Scenario: BFS strategy visits states level by level

- **WHEN** the strategy is `bfs`
- **AND** the model graph has states at depths 1, 2, and 3 from the initial state
- **THEN** all depth-1 states SHALL be fully explored before any depth-2 state is targeted
- **AND** all depth-2 states SHALL be explored before any depth-3 state is targeted

#### Scenario: DFS strategy goes deep before backtracking

- **WHEN** the strategy is `dfs`
- **AND** the model graph has a linear chain of states A → B → C
- **THEN** the agent SHALL navigate to C before backtracking to explore siblings of B or A

---

### Requirement: Output Persistence on Termination

On termination — normal (when `StopTestingException` is caught) or abnormal (any other `Throwable` escaping the exploration loop) — the exploration engine SHALL flush its observational stream, never file-based graph artefacts. The `tearDown()` call chain SHALL execute inside a `finally` block in `Monkey`, so an uncaught `RuntimeException` from the event loop still produces the run's outputs before the process exits. The run's outputs are: the NDJSON trace on stdout (event-sink capability) — flushed by `flushPendingStep` and terminated by `RUN_END` — and the UI-coverage dump lines (ui-coverage capability, format unchanged). No graph serialization, DOT/vis export, per-state dump, action-history log, event produce/consume log, or timeline export SHALL be written: the writers for `sataGraph.dot`, `sataGraph.vis.js`, `step-*.txt`, `action-history.log`, `produce.log`, `consume.log`, and `sataTimeline.vis.js` are deleted, along with the `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` configuration flags. Post-run visualizations, if ever needed, are host-side post-processing over the trace.

The `finally` block in `Monkey.run` SHALL guard each of its throw-capable statements individually: the rotation restore (`MonkeyRotationEvent.injectEvent`) and the `MonkeySourceApe.tearDown()` call each run inside their own catch that logs the failure with a full stack trace. A failure in the rotation restore SHALL NOT skip teardown, and a failure anywhere in teardown SHALL NOT replace the exception that terminated the loop (INV-EXPL-16): after the `finally` completes, the original loop exception SHALL propagate to `Monkey.main`'s outermost handler and be the stack trace reported for the run.

Teardown SHALL be step-isolated (INV-EXPL-29). In `MonkeySourceApe.tearDown`, a `Throwable` from `disconnect()` SHALL NOT prevent `mAgent.tearDown()` nor the image-writer shutdown that follows. In `StatefulAgent.tearDown`, a `Throwable` from any step of the sequence — `flushPendingStep` (writes the in-flight `StepRecord` with `out:{"resolved":false}`), `super.tearDown()`, coverage dump, action counters, activity nodes, naming dump, model counters, `runEnd` (emits `RUN_END` with reason and counters, last) — SHALL be caught and logged with its stack trace, and all remaining steps SHALL still execute. The replay reader (`ApeRRFormatter.readActions`, used by `ReplayAgent` on externally supplied logs) SHALL be preserved; only the tool's own writers of that format are deleted.

**Where the deleted writers' assertions went.** Three scenarios below keep the header the pre-change
requirement gave them and carry a body this change contradicts, because the artifact they were
written around is one this change deletes:

| Scenario header | What it asserted | Where the claim is now |
|---|---|---|
| `Normal termination with defaults` | the coverage dump **and `action-history.log`** are produced as before | the dump, unchanged; the log, nowhere — its writer is deleted by this change (`model`'s REMOVED `Tolerant Action-History Persistence`), and what replaces it as the run's record of what happened is the step-record stream, terminated by `RUN_END` |
| `coverage dump precedes the first artifact write` | the dump precedes the action-history save | `ui-coverage :: Coverage Dump Emitted First Among Teardown Writers`, whose boundary moves to the first *surviving* producer of output, the `actionCounters` dump. After this change no teardown step writes a file at all, so "artifact write" no longer names anything — the property is stated against output, not files |
| `disconnect failure does not lose the model` | a throwing `disconnect()` must not cost the run its persisted model | the same isolation, over what teardown now produces: the trace's flush and terminator. The subject narrowed once already when the model serialization went (rearch-02); it narrows again here, and the guarantee — one failing outer step must not skip `mAgent.tearDown()` — is untouched |

#### Scenario: Normal termination with defaults

- **WHEN** `StopTestingException` is caught after the time limit expires
- **AND** every key is at its jar default (the `save*` keys no longer exist to be set)
- **THEN** any pending `StepRecord` SHALL be flushed (`out:{"resolved":false}` if unresolved) and `RUN_END` SHALL be the last sink record
- **AND** the coverage dump SHALL be produced as before
- **AND** no `sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js`, `step-*.txt`, `action-history.log`, `produce.log`, `consume.log`, or `sataTimeline.vis.js` file SHALL exist in the output directory

#### Scenario: saveObjModel disabled

- **WHEN** `ape.properties` contains `ape.saveObjModel=false`
- **THEN** resolution SHALL abort with a retired-key diagnostic before step 1, and no run SHALL start — the flag no longer selects between writing and not writing a file, because the file and its writer no longer exist
- **AND** the same SHALL hold for `ape.saveDotGraph`, `ape.saveVisGraph` and `ape.saveStates`

#### Scenario: coverage dump precedes the first artifact write

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` / `UICOV-ACT` lines SHALL appear in the trace before the output of any later step of the chain — the action-history save this scenario named is deleted, so the boundary is the first surviving producer of output, `actionCounters`
- **AND** `flushPendingStep`, which precedes the dump, SHALL NOT count against the boundary: it is loss-bounding rather than an artifact write, and `ui-coverage` owns the full statement

#### Scenario: disconnect failure does not lose the model

- **WHEN** `MonkeySourceApe.tearDown()` runs and `disconnect()` throws `IllegalStateException`
- **THEN** the failure SHALL be logged
- **AND** `mAgent.tearDown()` SHALL still execute, flushing the pending step and emitting `RUN_END`. *(The scenario's subject narrows for the second time rather than disappearing: the serialized model it originally named went with the persistence protocol and the action history goes here, but the isolation it pins — a throwing `disconnect()` must not cost the run its outputs — is untouched by this change.)*

#### Scenario: abnormal termination still persists outputs

- **WHEN** a `RuntimeException` escapes the exploration loop
- **THEN** `tearDown()` SHALL still run (via `finally`), flush the pending step, and emit `RUN_END` with `reason:"crash"` before the process exits
- **AND** the exception SHALL still propagate (the run is reported as failed)

#### Scenario: teardown failure does not mask the loop exception

- **WHEN** an `IllegalStateException` escapes the exploration loop
- **AND** a teardown step (e.g. the coverage dump) throws its own `RuntimeException`
- **THEN** the teardown failure SHALL be logged with its full stack trace
- **AND** the exception reported by `Monkey.main`'s outermost handler SHALL be the original loop `IllegalStateException`, not the teardown failure

#### Scenario: rotation-restore failure does not skip teardown

- **WHEN** the exploration loop terminates and `MonkeyRotationEvent.injectEvent` throws (e.g. a binder failure)
- **THEN** the failure SHALL be logged
- **AND** `MonkeySourceApe.tearDown()` SHALL still execute and flush the run's trace

#### Scenario: one failing agent-teardown step does not skip the rest

- **WHEN** `StatefulAgent.tearDown()` runs and the naming dump throws a `RuntimeException`
- **THEN** the failure SHALL be logged with its stack trace
- **AND** the model counters SHALL still be printed and `RUN_END` SHALL still be emitted

#### Scenario: flushPendingStep runs before the expensive steps

- **WHEN** `StatefulAgent.tearDown()` begins with a step in flight
- **THEN** `flushPendingStep` SHALL execute as the first step of the agent teardown chain, so a mid-teardown cut costs at most the later free-text dumps, never the in-flight record

### Requirement: Fuzzing Integration

When `ape.doFuzzing=false`, the fuzzing path is disabled and the agent never injects random `FUZZ` actions outside the structured exploration loop. When `ape.doFuzzing=true`, at each step the agent MAY inject a random `FuzzAction` with probability `ape.fuzzingRate` (default: `0.02`, i.e., 2% of steps). The fuzzing decision is made in `Agent.canFuzzing()` and checked in `MonkeySourceApe` before the normal action-selection path. Fuzzing MUST only activate after the app's activity has been visited at least `ape.fuzzingActivityVisitThreshold` (default: `10`) times, preventing fuzzing from disrupting early exploration.

#### Scenario: Fuzzing disabled

- **WHEN** `ape.doFuzzing=false`
- **THEN** `Agent.canFuzzing()` SHALL return `false` at every step
- **AND** no `FuzzAction` SHALL be injected into the event queue

#### Scenario: Fuzzing fires at expected rate

- **WHEN** `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **AND** the current activity's visit count exceeds `ape.fuzzingActivityVisitThreshold` (default: `10`)
- **THEN** across a large number of steps, approximately 2% of steps SHALL inject a `FuzzAction`
- **AND** the remaining 98% of steps SHALL proceed through the normal action-selection path

---

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a configuration channel for `ape.*` behavior. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; the five formerly non-final test knobs no longer exist (tests construct `RunSpec` values instead). `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority.

The table below lists the configuration keys relevant to the exploration engine with their types and defaults (unchanged values):

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart |
| `ape.activityStableRestartThreshold` | int | 200 | Steps in same activity before forced restart |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

The keys `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` are retired (persistence removal) and abort resolution if present.

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** `Config.graphStableRestartThreshold` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields SHALL take their hardcoded default values
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

### Requirement: StatefulAgent — LLM Router Integration

`StatefulAgent` SHALL integrate the LLM through the `RunContext`-owned LLM units and the decision pipeline's LLM stages — not through a router field of its own. The fields:

| Field | Owner after this change | Description |
|-------|------------------------|-------------|
| LLM units (`LlmClient`, `LlmEngine`, `ScreenshotStep`, `CoordinateMapper`, `LlmTelemetry`, `ApePromptBuilder`, `ToolCallParser`) | `RunContext` (constructed once at bootstrap when the plan carries the LLM feature) | replace the former `_llmRouter` reference; see `llm-routing` "LLM Unit Lifecycle and Ownership" |
| `_isNewState` | `StatefulAgent` (unchanged) | captured in `updateStateInternal()` **before** `markVisited()` (unchanged capture requirement) |
| `_actionHistory` (ring buffer, max 5) | `StatefulAgent` (unchanged) | last executed actions with results, fed to the prompt builder |
| `_lastState` / `_stateBeforeLast` | `StatefulAgent` (unchanged) | history for action-result determination |
| Stagnation single-shot flag | **`LlmStagnation` stage** (moved out of `StatefulAgent`) | see "SataAgent — LLM Stagnation Hook" |

When the plan does not carry the LLM feature, no LLM unit SHALL be constructed and no LLM stage SHALL be assembled (feature absent = stage absent — replaces the null-`_llmRouter` convention). The agent SHALL forward each visited `StateTransition` once to every assembled stage's `onStateTransition` hook, immediately after its own stability-counter bookkeeping in `onVisitStateTransition` — this is the sole reset channel for stage-owned episode state.

#### Scenario: LLM enabled via Config

- **WHEN** the resolved plan carries the LLM feature
- **THEN** `RunContext` SHALL hold the LLM units for the entire session
- **AND** the pipeline SHALL contain the enabled LLM stages

#### Scenario: LLM disabled (default)

- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM infrastructure object SHALL be instantiated
- **AND** no LLM stage SHALL exist in the pipeline

#### Scenario: transition events reach the stages

- **WHEN** `onVisitStateTransition` records an edge
- **THEN** every assembled stage's `onStateTransition(edge)` SHALL be invoked exactly once, after the agent's own counter bookkeeping

---

### Requirement: StatefulAgent — isNewState Capture Before markVisited

In `StatefulAgent.updateStateInternal()`, the `_isNewState` flag SHALL be captured as `(newState.getVisitedCount() == 0)` **before** the call to `getGraph().markVisited(newState, timestamp)`. This is a bug fix: previously, `markVisited()` increments the visit count, making `getVisitedCount() == 0` always false at any point after the call.

The corrected flow in `updateStateInternal()`:

```
1. State newState = model.getState(naming, guiTree)
2. _stateBeforeLast = _lastState                          ← [NEW] shift history
3. _lastState = currentState                               ← [NEW] save outgoing state
4. _isNewState = (newState.getVisitedCount() == 0)         ← [NEW] capture BEFORE markVisited
5. getGraph().markVisited(newState, getTimestamp())         ← existing (increments count)
6. ... (existing logic: transition creation, stability counters, etc.)
```

#### Scenario: First visit detection is accurate

- **WHEN** `updateStateInternal()` is called with a `newState` that has `visitedCount == 0`
- **THEN** `_isNewState` SHALL be `true`
- **AND** after `markVisited()`, `newState.getVisitedCount()` SHALL be `1`
- **AND** `_isNewState` remains `true` (captured before increment)

#### Scenario: Revisit detection is accurate

- **WHEN** `updateStateInternal()` is called with a `newState` that has `visitedCount == 3`
- **THEN** `_isNewState` SHALL be `false`

---

### Requirement: StatefulAgent — Action History Ring Buffer

`StatefulAgent` SHALL maintain a ring buffer of the last 5 executed actions with their results. After each action is executed and the new state is determined, an `ActionHistoryEntry` SHALL be appended to the buffer.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `normX` | `int` | Center X in [0,1000) normalized space |
| `normY` | `int` | Center Y in [0,1000) normalized space |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen" |

**Result determination**: After `updateStateInternal()` resolves the new state:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The buffer is passed to `ApePromptBuilder.build()` via `LlmRouter.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is executed
- **AND** the resulting state is different from the previous state and from the state before that
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"`, `result="new screen"`

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new action is executed
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is executed
- **AND** the resulting state equals `_stateBeforeLast`
- **THEN** the `ActionHistoryEntry.result` SHALL be `"previous screen"`

---

### Requirement: SataAgent — LLM New-State Hook

LLM new-state routing SHALL be the `LlmNewState` decision stage — the second stage of the pipeline (after `Budget`), assembled only when the plan enables the LLM new-state mode. `SataAgent.selectNewActionNonnull()` SHALL contain no inline LLM block: it delegates to `DecisionPipeline.decide()`, and the stage occupies the ladder position the inline block occupied (before stagnation/random/launcher/trigger/SATA — behavior parity gated by the per-preset goldens).

The stage's guards are unchanged in content: the shared LLM precondition — action buffer empty (to not interrupt multi-step navigation) AND the state has more than 2 actions (to skip trivial states like permission dialogs) — evaluated through the single `LlmGate` helper shared by the three LLM stages (the former verbatim triplication of this precondition is deleted); then the new-state trigger (`_isNewState`) and the breaker gate. On a non-null engine result the stage returns `Select` (stamping `DecisionSource.LLM`/`PickChannel.LLM`, and resolving a synthesized `MODEL_LLM_TAP` against the state — unchanged accept semantics); on null it returns `Continue` and the remaining pipeline decides (structural fallback).

When the plan has no LLM feature, the stage does not exist and selection cost is zero.

#### Scenario: LLM provides action on new state

- **WHEN** the pipeline reaches `LlmNewState` with the buffer empty, `actions.size() > 2`, `_isNewState` true, breaker allowing
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step (hard preemption — no later stage evaluated)

#### Scenario: LLM returns null, SATA takes over

- **WHEN** the engine returns `null`
- **THEN** the stage SHALL return `Continue`
- **AND** the launcher/trigger/SATA stages SHALL evaluate normally

#### Scenario: LLM skipped — buffer has pending navigation

- **WHEN** the action buffer is non-empty (multi-step navigation in progress)
- **THEN** the shared precondition SHALL fail and all three LLM stages SHALL return `Continue`
- **AND** the buffered action SHALL be returned by the `SataChain` buffer rung (existing behavior)

#### Scenario: LLM skipped — trivial state

- **WHEN** `actions.size() <= 2` (e.g., permission dialog)
- **THEN** the LLM stages SHALL return `Continue` and the pipeline SHALL handle the state directly

#### Scenario: LLM disabled, zero overhead

- **WHEN** the plan carries no LLM feature
- **THEN** no LLM stage SHALL exist and the pipeline SHALL be structurally identical to the non-LLM preset's
- **AND** the decision sequence SHALL equal the non-LLM golden under the same seed

---

### Requirement: SataAgent — LLM Stagnation Hook

LLM stagnation routing SHALL be the `LlmStagnation` decision stage, assembled after `LlmNewState` and before `LlmRandom` when the plan enables the stagnation mode. The trigger is at-or-past the midpoint with the episode's single shot unspent: `graphStableCounter >= graphStableRestartThreshold / 2` AND the stage's per-episode fired flag is armed. The same shared precondition (`LlmGate`) applies.

**Episode-state ownership (moved out of `StatefulAgent`):** the fired flag SHALL be a private field of the `LlmStagnation` stage. It is burned inside `decide()` whenever the trigger fires — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold is what follows if the stagnation persists) — and re-armed by the stage's `onStateTransition` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode). The stage's own reset of `graphStableCounter` after a successful escape does not re-arm the flag: no new edge was observed, so it is the same episode.

**One scenario below is a replacement, not a carry-forward.** `"Stagnation hook fires only once per phase"` asserted in the main spec that the hook fires *only* at `graphStableCounter == threshold/2` exactly — 49 and 51 both declined by the equality check. This change replaces that trigger with at-or-past the midpoint guarded by the episode flag, so the old body is contradicted rather than forgotten: 51 with the flag armed now fires. What the scenario's name asserts — one firing per episode — is what survives, and it survives structurally (the flag is burned in `decide()`, not re-derived from the counter), so the scenario is restated under its own header with the new mechanism as its body. The counter-jump case the old equality check would have missed is covered by `llm-routing :: Stagnation LLM Mode :: "midpoint skipped by a counter jump still fires"`.

`graphStableCounter` remains a `StatefulAgent`/`RunContext` field — it is shared exploration state consumed by the forced-restart mechanism (`onGraphStable` at `counter > threshold`), which this stage does not modify. On an accepted escape the stage SHALL reset the counter to 0 through the `StepContext`'s single declared write method (unblocking exploration, unchanged semantics).

#### Scenario: LLM breaks stagnation (single-shot at midpoint)

- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2` with the episode flag armed, the shared precondition holds, and the breaker allows
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step, the flag SHALL be burned, `graphStableCounter` SHALL be reset to `0`
- **AND** `requestRestart()` SHALL NOT be called

#### Scenario: Stagnation hook fires only once per phase

- **WHEN** `graphStableCounter` is `49` with `graphStableRestartThreshold=100` (below the midpoint) and the episode flag is armed
- **THEN** the stage SHALL NOT fire
- **WHEN** the counter then reaches `50` and the stage fires, burning the flag
- **THEN** every later step of the same episode — `51`, `52`, and every counter value up to the restart threshold — SHALL NOT fire again, whatever the counter reads
- **AND** the single shot SHALL be spent by the firing itself, not by the counter leaving the midpoint (the pre-change equality check declined `51` because `51 != 50`; the flag declines it because the episode is over)

#### Scenario: LLM fails at midpoint, stagnation continues to restart

- **WHEN** the trigger fires and the engine returns `null`
- **THEN** the flag SHALL be burned and the stage SHALL return `Continue`
- **AND** the counter SHALL continue incrementing and the stage SHALL NOT fire again this episode
- **AND** if `graphStableCounter` eventually exceeds `graphStableRestartThreshold`, `requestRestart()` SHALL be called (existing behavior per INV-EXPL-09)

#### Scenario: new edge re-arms the episode through the stage hook

- **WHEN** the flag is burned and a later transition records a `NEW_ACTION` edge
- **THEN** `onStateTransition` SHALL re-arm the flag inside the stage
- **AND** a later stagnation reaching the midpoint SHALL fire again

#### Scenario: no stagnation state remains in StatefulAgent

- **WHEN** the change is complete
- **THEN** `StatefulAgent` SHALL declare no LLM-stagnation fired flag
- **AND** the only stagnation-episode state SHALL live in the `LlmStagnation` stage

### Requirement: StatefulAgent — LLM Telemetry at tearDown

`StatefulAgent.tearDown()` SHALL fold the aggregate LLM counters into the `RUN_END` record (event-sink capability) via the counters accessor on the LLM infrastructure (when present). The `[APE-RV] LLM Summary:` and `[APE-RV] LLM Decision ratio:` log lines and `printSummary()` are retired; the decision ratio is derived from the counters by consumers, not stored.

#### Scenario: Telemetry printed on normal termination

- **WHEN** `StatefulAgent.tearDown()` is called after `StopTestingException` in an LLM-enabled run
- **THEN** the `RUN_END` record SHALL carry the aggregate LLM counters (`calls`, token totals, outcome and failure-cause counters, `breaker_trips`)
- **AND** no `[APE-RV] LLM Summary` or `Decision ratio` line SHALL be emitted, and `printSummary()` SHALL NOT exist — the aggregate is a member of the run's last record instead of two free-text lines, and the ratio is derived by consumers from the counters rather than stored

#### Scenario: Non-LLM arm

- **WHEN** teardown runs in an arm whose plan has no LLM feature
- **THEN** `RUN_END` SHALL simply omit the LLM counter block (absent = feature absent)

### Requirement: Dynamic Epsilon Decay

`SataAgent.egreedy()` SHALL compute a coverage-adaptive epsilon when `Config.dynamicEpsilon` is `true`. The epsilon value SHALL be interpolated between `Config.minEpsilon` and `Config.maxEpsilon` based on the current state's UI coverage gap:

```
epsilon = minEpsilon + (maxEpsilon - minEpsilon) * coverageGap
```

Where `coverageGap` is obtained from `UICoverageTracker.getCoverageGap(currentState)`. The SataAgent accesses the tracker via the `getCoverageTracker()` accessor inherited from `StatefulAgent`.

**Scope note**: `egreedy()` is called from `selectNewActionEpsilonGreedyRandomly()`, which is one of the last steps in the SATA selection chain. Dynamic epsilon has incremental impact — it improves decisions that reach epsilon-greedy, but does not affect decisions resolved earlier (LLM hooks, buffer, early-stage, trivial activity).

When `Config.dynamicEpsilon` is `false`, the existing behavior SHALL be preserved: epsilon is the fixed value `Config.defaultEpsilon`.

#### Scenario: New state (high gap)
- **WHEN** `egreedy()` is called in a state with coverageGap=1.0 and maxEpsilon=0.15, minEpsilon=0.02
- **THEN** the effective epsilon SHALL be 0.15

#### Scenario: Fully explored state
- **WHEN** `egreedy()` is called in a state with coverageGap=0.0
- **THEN** the effective epsilon SHALL be 0.02 (minEpsilon)

#### Scenario: Dynamic epsilon disabled
- **WHEN** `Config.dynamicEpsilon` is `false`
- **THEN** `egreedy()` SHALL use the fixed `Config.defaultEpsilon` value

### Requirement: Config Flags for Dynamic Epsilon

`Config` SHALL declare the following flags controlling coverage-adaptive epsilon, loaded from `ape.properties` at class-loading time:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `dynamicEpsilon` | `ape.dynamicEpsilon` | boolean | `true` | Enable coverage-adaptive epsilon |
| `maxEpsilon` | `ape.maxEpsilon` | double | `0.15` | Epsilon when coverage gap is 1.0 |
| `minEpsilon` | `ape.minEpsilon` | double | `0.02` | Epsilon when coverage gap is 0.0 |

#### Scenario: Dynamic epsilon flags default correctly
- **WHEN** `ape.properties` sets none of `ape.dynamicEpsilon`, `ape.maxEpsilon`, or `ape.minEpsilon`
- **THEN** `Config.dynamicEpsilon` SHALL default to `true`, `Config.maxEpsilon` to `0.15`, and `Config.minEpsilon` to `0.02`

---

### Requirement: Seeded Agent Decision Reproducibility

All agent-decision randomness routed through `RandomHelper` (priority roulettes such as `randomPickWithPriority`, uniform picks, `toss`, and `ApeFuzzer` gesture generation) SHALL be driven by a `java.util.Random` seeded from the Monkey `-s` seed. The seeded `java.util.Random` is owned by the `RunContext` (`run-spec` capability), constructed once at bootstrap from the plan's seed — the same value that initializes `Monkey.mRandom` — and reached through the context, never through static state. Input-string generation SHALL draw from this same stream: the `StringCache` `ThreadLocalRandom` path (V23) and the `/sdcard/ape.strings` reader are deleted by this change (owner decision D6), so no agent-decision or input randomness remains outside the seeded stream. Two runs launched with the same `-s`, the same APK, and the same configuration SHALL produce the same sequence of `RandomHelper` draws. (Historically `RandomHelper` used `ThreadLocalRandom.current()`, which cannot be seeded, and a residual `ThreadLocalRandom` survived in `StringCache.nextString()` — verified V23 — leaving input generation unseeded even after `RandomHelper` was fixed. Both are gone at this stage.)

#### Scenario: identical seeds produce identical draw sequences
- **WHEN** `RandomHelper.seed(42)` is called and a sequence of `randomPick`/`toss`/`nextInt` draws is recorded, then `RandomHelper.seed(42)` is called again
- **THEN** repeating the same sequence of calls SHALL yield the same values in the same order

#### Scenario: seed comes from the Monkey -s flag
- **WHEN** the Monkey is launched with `-s 12345`
- **THEN** `RandomHelper` SHALL be seeded with `12345` before the first agent decision

### Requirement: Bounded Foreground Wait

When `waitForActivity` is active and the foreground package is not in the allowed set, `MonkeySourceApe.checkAppActivity()` throttles 100 ms and re-checks on the next `getNextEvent()`. This wait SHALL be bounded: a consecutive-iteration counter SHALL be maintained, and when it exceeds 100 iterations (~10 s) the engine SHALL log `[APE-RV] waitForActivity exceeded 100 cycles, relaunching`, clear the wait state, and relaunch the app under test via the existing restart path (`startRandomMainApp`). The counter SHALL reset whenever the allowed package reaches the foreground. (Previously there was no counter, timeout, or relaunch: an app that never returned to the foreground consumed the entire `--running-minutes` budget in 100 ms throttles, producing a run with zero actions.)

#### Scenario: wedged app is relaunched
- **WHEN** the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL be cleared
- **AND** the app under test SHALL be relaunched
- **AND** one `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

#### Scenario: normal wait resets the counter
- **WHEN** the allowed package reaches the foreground after 5 wait iterations
- **THEN** the counter SHALL reset to 0
- **AND** no relaunch SHALL occur

---

### Requirement: Per-Step Debug Artifact Defaults

`ape.takeScreenshotForEveryStep` and `ape.saveGUITreeToXmlEveryStep` SHALL default to `false`. Both are debug aids: the experiment pipeline consumes neither (coverage comes from logcat, the trace from stdout; the aperv-tool pulls no per-step PNG/XML), and the LLM path captures its own screenshots on demand via `ScreenshotCapture`, independent of these flags. Per-step PNG + XML writes are pure I/O overhead on the exploration loop (estimated 20-40% of step throughput on an emulator). Debug runs re-enable either flag via `ape.properties`.

#### Scenario: defaults are off
- **WHEN** `ape.properties` contains neither key
- **THEN** `Config.takeScreenshotForEveryStep` SHALL be `false`
- **AND** `Config.saveGUITreeToXmlEveryStep` SHALL be `false`
- **AND** no per-step PNG or XML SHALL be written

#### Scenario: debug re-enable
- **WHEN** `ape.properties` contains `ape.saveGUITreeToXmlEveryStep=true`
- **THEN** a `step-N.xml` SHALL be written each step, as before

#### Scenario: LLM screenshots unaffected
- **WHEN** `ape.takeScreenshotForEveryStep=false` and the LLM routes a step
- **THEN** `ScreenshotCapture` SHALL still capture its on-demand screenshot for the prompt

---

### Requirement: Fuzz Gesture Emission

Every branch of the fuzzer's default gesture switch (drag, pinch/zoom, click) SHALL append exactly one event to the output list. `generatePinchOrZoomEvent` SHALL end with `events.add(new ApePinchOrZoomEvent(points))` (previously the gesture was built and discarded — the `events` parameter was never used, so ~1/3 of default-branch fuzz iterations emitted nothing).

Before appending, `generatePinchOrZoomEvent` SHALL size the `points` array to exactly the number of entries it writes and emit no `null` entry. The current allocation `new PointF[4 + count << 1]` evaluates by Java precedence to `(4 + count) << 1` = `8 + 2·count`, while only `6 + 2·count` entries are written — leaving two trailing `null` slots that the constructor would dereference. The `ApePinchOrZoomEvent` constructor SHALL validate the array length (reject arrays shorter than 6 entries — 1 count slot + 1 duration slot + 2 coordinates × (count+1) pointers with count ≥ 0 gives the minimum valid payload of 6) BEFORE dereferencing any element, so a malformed array is rejected rather than throwing `NullPointerException`.

#### Scenario: pinch/zoom branch emits
- **WHEN** the fuzzer's gesture switch selects the pinch/zoom branch
- **THEN** exactly one `ApePinchOrZoomEvent` SHALL be appended to the event list
- **AND** its points array SHALL have at least 6 entries

#### Scenario: emitted points array has no null entries
- **WHEN** the pinch/zoom branch builds its `points` array for any `count ≥ 0`
- **THEN** the array passed to `ApePinchOrZoomEvent` SHALL have length equal to the number of written entries
- **AND** it SHALL contain no `null` element and construction SHALL NOT throw `NullPointerException`

#### Scenario: short payload rejected
- **WHEN** an `ApePinchOrZoomEvent` is constructed with 5 points
- **THEN** the constructor SHALL reject it before dereferencing any element

---

### Requirement: Off-Screen Action Handling

When `generateClickEventAt` finds that the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <action>` line. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs). The invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and gains the same log line. Visit/coverage crediting of the dropped action is unchanged in this change (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

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
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted
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

### Requirement: LLM Tap Injectable Rect

`LlmTapAction` SHALL expose its dispatch geometry as the minimal non-degenerate rect anchored at the
decided pixel: `toInjectableRect()` returns `new Rect(pixelX, pixelY, pixelX + 1, pixelY + 1)`. The
`MODEL_LLM_TAP` case in `MonkeySourceApe` SHALL obtain the rect exclusively through this method (a
single construction site, unit-testable on the JVM), and the resulting click point (rect center,
truncated to int) SHALL equal the decided pixel `(pixelX, pixelY)`.

`LlmTapAction` SHALL also own the guard-bounds decision: `clipToDisplay(Rect displayBounds)`
returns the intersection of the display bounds with `toInjectableRect()` when the pixel lies inside
the display, and `null` when the pixel is outside it or `displayBounds` is `null`. The
`MODEL_LLM_TAP` dispatch SHALL pass this result as the explicit guard bounds of
`generateClickEventAt` (never the root-node `getVisibleBounds()` result), so that the guard body
and the drop log line remain single-sourced for node and coordinate actions alike.

#### Scenario: injectable rect is non-empty and centered on the pixel
- **WHEN** `LlmTapAction` carries `pixelX=853, pixelY=1657` and `toInjectableRect()` is called
- **THEN** the returned rect SHALL be `(853, 1657, 854, 1658)`
- **AND** `rect.isEmpty()` SHALL be `false`
- **AND** `(int) rect.exactCenterX()` SHALL equal `853` and `(int) rect.exactCenterY()` SHALL
  equal `1657`

#### Scenario: injectable rect passes the visibility guard for an interior pixel
- **WHEN** the visible bounds are `(0, 0, 1080, 1920)` and the rect is `(853, 1657, 854, 1658)`
- **THEN** `visibleBounds.intersect(rect)` SHALL yield the non-empty rect `(853, 1657, 854, 1658)`
- **AND** `contains(853, 1657)` on the intersection SHALL be `true`

#### Scenario: clipToDisplay accepts an in-display pixel outside the app window
- **WHEN** `LlmTapAction` carries `pixelX=424, pixelY=1618` and `clipToDisplay(new Rect(0, 0,
  1080, 1920))` is called
- **THEN** the returned rect SHALL be the non-empty `(424, 1618, 425, 1619)`
- **AND** `contains(424, 1618)` on it SHALL be `true`

#### Scenario: clipToDisplay rejects an out-of-display pixel
- **WHEN** `LlmTapAction` carries `pixelX=1080, pixelY=500` and `clipToDisplay(new Rect(0, 0,
  1080, 1920))` is called
- **THEN** the result SHALL be `null`

#### Scenario: clipToDisplay tolerates a null display
- **WHEN** `clipToDisplay(null)` is called
- **THEN** the result SHALL be `null` (the tap is dropped, never NPEs)

### Requirement: Foreign-Activity Guard in Event Generation

When `ape.foreignActivityGuard` is true and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldModel(pkg, filterAccepts, systemWhitelist)` before calling `mAgent.updateState`, where `pkg` is `topComp.getPackageName()`, `filterAccepts` is `MonkeyUtils.getPackageFilter().isPackageValid(pkg)` (the same predicate the active backstop `checkAppActivity` gates on), and `systemWhitelist` is the fixed set {`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`} (the Google-image package is required because the RVSec AVD runs a Google emulator image whose runtime-permission UI is `com.google.android.permissioncontroller`; the AOSP entry alone would never match). `com.android.systemui` is NOT whitelisted — `checkAppActivity` already treats it as invalid and restarts on it, so the guard SHALL BACK out of it. When the decision is negative, the source SHALL enqueue exactly one BACK key event plus the standard throttle and return without invoking `updateState` — the foreign screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT contribute actions.

The decision SHALL be based exclusively on the component's package name (the task's applicationId), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing START/ACTIVATE handling proceeds). A null package SHALL be treated as modelable (uncheckable — defer to the existing paths). With `ape.foreignActivityGuard` false, event generation SHALL be identical to the pre-guard specification.

The first deflection of each distinct foreign package SHALL log `[APE-RV] Foreign activity: pkg=<pkg> -> BACK`; subsequent deflections of the same package SHALL NOT log (throttle — a persistent foreign screen must not spam the trace). The dead `checkPackage(ComponentName, AccessibilityNodeInfo)` helper SHALL be deleted (P3 — it has no callers and the guard supersedes its intent).

- **INV-EXPL-20**: No `State` or UI-coverage registration SHALL ever originate from a screen whose package fails the guard decision.
- **INV-EXPL-21**: Screens of the system-interaction whitelist packages SHALL NOT be backed out by the guard (the guard SHALL be a no-op for them). This is a guard-local guarantee only: it does not assert durable in-package modeling — `checkAppActivity`, which this change does not touch, may still restart over these packages on a later cycle.
- **INV-EXPL-22**: The `ape.foreignActivityGuard` flag SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no BACK deflections, no guard log lines). This is a reasoning invariant over the flag placement, not a pure-seam matrix case — `shouldModel` has no flag parameter.

#### Scenario: launcher screen deflected
- **WHEN** the app under test is `com.example.app`, a click lands the device on `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, and `generateEvents` runs with the guard enabled
- **THEN** one BACK key event SHALL be enqueued, `updateState` SHALL NOT be called, and (on first occurrence) the trace SHALL contain `[APE-RV] Foreign activity: pkg=com.google.android.apps.nexuslauncher -> BACK`

#### Scenario: permission dialog not deflected by the guard
- **WHEN** the top component's package is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (runtime permission dialog; the Google package is the one seen on the RVSec Google-image AVD)
- **THEN** the guard SHALL be a no-op for that cycle and `updateState` SHALL proceed normally (a guard-local no-op — this does not guarantee the screen survives `checkAppActivity` on a later cycle)

#### Scenario: divergent class namespace is not misflagged
- **WHEN** the app under test has applicationId `info.metadude.android.fosdem.schedule` and the top component is `info.metadude.android.fosdem.schedule/nerd.tuxmobil.fahrplan.congress.MainActivity`
- **THEN** the caller SHALL derive `pkg` from `topComp.getPackageName()` (the applicationId `info.metadude.android.fosdem.schedule`), never from the activity class prefix `nerd.tuxmobil.*`, so `filterAccepts` is true and the screen is modeled normally (a caller-level `getPackageName()` assertion, not a `shouldModel`-seam matrix case)

#### Scenario: guard disabled
- **WHEN** `ape.foreignActivityGuard=false` and a foreign screen reaches `generateEvents`
- **THEN** the screen SHALL be modeled exactly as before this change (leak-and-restart behavior), with no guard log line

### Requirement: Tree/Package Guard in Event Generation

When `ape.treePackageGuard` is true and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldRefetch(topPkg, treePkg, systemWhitelist)` before calling `mAgent.updateState`, where `topPkg` is `topComp.getPackageName()`, `treePkg` is `info.getPackageName()` (the accessibility root's owning package, converted null-safely to a String), and `systemWhitelist` is the `SYSTEM_INTERACTION_PACKAGES` set defined by `foreign-activity-guard` ({`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`}). `shouldRefetch` SHALL return true (mismatch) only when `treePkg` is non-null, differs from `topPkg`, and is not in the whitelist.

When `shouldRefetch` is true and at least one refetch iteration remains (`repeat > 0` inside the existing `while (repeat-- > 0)` loop), the source SHALL `continue` — re-fetching both `topComp` and `info` on the next loop iteration — and SHALL NOT invoke `updateState` on the current pair: the mismatched screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT be built into a `GUITree`. When `shouldRefetch` is true but the refetch iterations are exhausted (`repeat == 0`), the source SHALL fall through and model the pair exactly as today (fail-open) — the guard SHALL NOT deadlock capture on a persistent mismatch.

The decision SHALL be based exclusively on package names (`topComp.getPackageName()` and `info.getPackageName()`), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing null / START handling proceeds). A null tree package SHALL be treated as a match (uncheckable — defer to the existing paths, model normally). A tree owned by a whitelist package SHALL NOT be treated as a mismatch (a permission dialog legitimately owning the tree over the app is modeled, not re-fetched). With `ape.treePackageGuard` false, event generation SHALL be identical to the pre-guard specification.

The first mismatch of each distinct `top→tree` package pair SHALL log `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch`; subsequent mismatches of the same pair SHALL NOT log (throttle — a persistent mismatch across the refetch iterations must not spam the trace). The guard SHALL be inserted after `foreign-activity-guard`'s top-component foreign check and before `updateState`, and SHALL reuse that change's whitelist set rather than redefining it.

- **INV-EXPL-26**: While at least one refetch iteration remains, no `State`, `GUITree`, or UI-coverage registration SHALL originate from a `(topComp, tree)` pair whose tree package fails the guard decision (mismatched and not whitelisted).
- **INV-EXPL-27**: On refetch-iteration exhaustion the guard SHALL fail open — a still-mismatched pair SHALL be modeled exactly as the pre-guard path; the guard SHALL NOT prevent capture indefinitely. (Reasoning invariant over the loop-exhaustion branch, not a pure-seam matrix case — `shouldRefetch` does not see `repeat`.)
- **INV-EXPL-28**: The `ape.treePackageGuard` flag SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no refetch `continue`, no guard log lines). (Reasoning invariant over the flag placement — `shouldRefetch` has no flag parameter.)

#### Scenario: launcher tree under an in-package MainActivity is re-fetched
- **WHEN** the app under test is `com.example.app`, `topComp` reports `com.example.app/.MainActivity` but the accessibility root's `getPackageName()` is `com.google.android.apps.nexuslauncher` (a relaunch frame not yet painted), the guard is enabled, and at least one refetch iteration remains
- **THEN** `updateState` SHALL NOT be called on this pair, the loop SHALL `continue` to re-fetch both `topComp` and `info`, and (on first occurrence) the trace SHALL contain `[APE-RV] Tree/package mismatch: top=com.example.app tree=com.google.android.apps.nexuslauncher -> refetch`

#### Scenario: whitelisted permission dialog tree is not a mismatch
- **WHEN** `topComp` reports the app under test but the accessibility root's `getPackageName()` is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (a runtime-permission dialog legitimately owning the tree over the app)
- **THEN** `shouldRefetch` SHALL return false, the guard SHALL NOT re-fetch, and `updateState` SHALL model the pair normally

#### Scenario: persistent mismatch fails open on exhaustion
- **WHEN** the tree package differs from `topComp`'s package on every refetch iteration and the last iteration is reached (`repeat == 0`)
- **THEN** the guard SHALL NOT `continue` past loop exhaustion, and the pair SHALL be modeled via `updateState` exactly as the pre-guard behavior (fail-open — capture is not deadlocked)

#### Scenario: guard disabled
- **WHEN** `ape.treePackageGuard=false` and a `(topComp, tree)` pair with a foreign tree reaches `generateEvents`
- **THEN** the pair SHALL be modeled exactly as before this change (unchecked pairing), with no refetch `continue` and no guard log line

### Requirement: Parametrized Idle-Wait Ceiling in Slow Tree Capture

The global timeout of the UiAutomation idle wait in `getRootInActiveWindowSlow` SHALL be `ape.maxIdleTimeoutMs` (default `10000` ms). The idle wait SHALL remain best-effort: on timeout, or with any flag value, the source SHALL still call `getRootInActiveWindow()` unconditionally and return the resulting tree — the wait SHALL NOT gate whether a tree is captured. The quiet-period argument of the idle wait (the first `waitForIdle` parameter, 1000 ms) SHALL NOT be changed by this flag.

The "window stuck animating" break threshold of **both** slow-capture retry loops (`refreshNewState` and `checkAndRefreshNewState`, which compare the same `getRootInActiveWindowSlow` duration) SHALL be derived from the same flag as `maxIdleTimeoutMs / 1000` seconds, so that lowering the ceiling keeps the breaks firing. With `ape.maxIdleTimeoutMs` at its default `10000`, the idle-wait global timeout SHALL be `10000` ms and each break threshold SHALL be `10` seconds — byte-identical to the pre-change literals (`1000 * 10` and `>= 10`).

- **INV-EXPL-23**: With `ape.maxIdleTimeoutMs` at its default `10000`, slow tree capture and both retry-loop breaks SHALL behave identically to the pre-change implementation (the global timeout is `10000` ms and the break threshold is `10` s).
- **INV-EXPL-24**: The idle wait SHALL remain best-effort at every flag value — `getRootInActiveWindow()` SHALL be invoked unconditionally after the wait, so a tree SHALL always be captured regardless of the timeout.
- **INV-EXPL-25**: Every "window stuck animating" retry-loop break threshold (in `refreshNewState` and `checkAndRefreshNewState`) SHALL be derived from `ape.maxIdleTimeoutMs` (as `maxIdleTimeoutMs / 1000` seconds), never from an independent literal, so the ceiling and the breaks cannot diverge.

#### Scenario: default preserves current behavior
- **WHEN** `ape.maxIdleTimeoutMs` is at its default `10000` and `getRootInActiveWindowSlow` runs
- **THEN** the UiAutomation idle wait SHALL use a `10000` ms global timeout, both retry-loop breaks (`refreshNewState`, `checkAndRefreshNewState`) SHALL fire at `>= 10` seconds, and the observable behavior SHALL be identical to the pre-change implementation

#### Scenario: lowered ceiling caps the wait and capture still proceeds
- **WHEN** `ape.maxIdleTimeoutMs` is lowered (e.g. to `2000`) and a screen has not gone idle within that window
- **THEN** the idle wait SHALL return after at most `2000` ms, `getRootInActiveWindow()` SHALL still be called and its tree returned (no capture is skipped), and both retry-loop breaks SHALL fire at `>= 2` seconds

### Requirement: Run Lifecycle Opens with RUN_START

The exploration run lifecycle SHALL begin with plan resolution and the `RUN_START` echo (specified by the `run-spec` capability): `RunSpec.resolve` runs in `Monkey.run` before `MonkeySourceApe` is constructed, an invalid plan aborts the process before step 1, and a valid plan is echoed as a single JSON object line before any exploration output. The exploration engine SHALL NOT begin capturing GUI trees, constructing agents, or injecting events until the plan is resolved and echoed.

#### Scenario: no exploration before a valid plan

- **WHEN** the process starts with an invalid `ape.properties` (e.g., an unknown key)
- **THEN** the process SHALL exit nonzero with the `[APE-RUNSPEC-ABORT]` diagnostic
- **AND** zero GUI trees SHALL have been captured and zero events injected

#### Scenario: echo precedes the first step

- **WHEN** the process starts with a valid plan
- **THEN** `RUN_START` SHALL appear in the trace before the first step's output

## Invariants (Dynamic Epsilon)

- **INV-EPS-01**: The effective epsilon SHALL always be in [`Config.minEpsilon`, `Config.maxEpsilon`].
- **INV-EPS-02**: When `Config.dynamicEpsilon` is `false`, behavior SHALL be identical to pre-change.
- **INV-EPS-03**: When `UICoverageTracker` is null, `egreedy()` SHALL fall back to `Config.defaultEpsilon`.
