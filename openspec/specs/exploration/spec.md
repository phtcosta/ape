# Specification: Exploration Engine

## Purpose

APE-RV's exploration engine is responsible for systematically exercising an Android application under test by navigating its GUI state space. The core motivation for model-based exploration — rather than pure random input generation as in AOSP Monkey — is that random exploration wastes most of its budget re-visiting already-seen states and rarely penetrates deeply into an app's feature set. APE-RV builds an explicit, labeled graph of abstract GUI states and state transitions as it runs, enabling the agent to reason about which parts of the application have already been covered and which have not. This directed exploration dramatically increases code and feature coverage within a fixed time budget.

The central abstraction mechanism, inherited from the original APE tool (ICSE 2019), is CEGAR-style (Counterexample-Guided Abstraction Refinement). The `NamingFactory` component maps concrete `GUITree` snapshots to abstract `State` nodes via a configurable `Naming` (a level in a widget-attribute lattice). When the abstraction is too coarse — detected by observing non-deterministic transitions from a single abstract state — the naming is automatically refined to a finer level, splitting the offending state. This adaptive refinement ensures the model stays sound (no false merging of distinct screens) without over-specifying upfront.

APE-RV supports three selectable exploration strategies, created by `ApeAgent.createAgent` from the plan's agent type. `SataAgent` (`--ape sata`, the default) is the primary strategy and implements an epsilon-greedy heuristic that aggressively prioritises unvisited actions while using graph-guided navigation to reach under-explored parts of the app. `RandomAgent` (`--ape random`) provides an experimental baseline: it takes the next buffered action when one is planned, and otherwise a priority-weighted roulette over the state's actions — it uses the `StatefulAgent` priority infrastructure but none of SATA's directed navigation. `ReplayAgent` (activated on the command line via `--ape replay --ape-replay <log>`; `ape.replayLog` is retired as a file key) replays a recorded action sequence for deterministic regression testing. Any other value, including `ape`, `bfs` and `dfs`, aborts the run before step 1. The strategy is selected once at startup and does not change during a session.

The exploration loop, implemented inside `MonkeySourceApe.nextEventImpl()`, is the heartbeat of the system. Each iteration captures the current screen as a `GUITree`, maps it to an abstract `State`, asks the active `Agent` to choose a `ModelAction`, translates that action into one or more `ApeEvent` objects (click, drag, key press, scroll gesture), enqueues those events in Monkey's event queue for execution on the device, then captures the resulting screen and updates the model. The loop terminates when a wall-clock time limit (`--running-minutes N`) or an event-count limit (`-v N`) is reached; teardown then writes the run's closing telemetry (no exploration-graph file is saved). The priority system on `ModelAction` objects allows extensions such as the MOP guidance layer to influence action selection without rewriting agent logic: `StatefulAgent.adjustActionsByGUITree()` is the designated hook where the mounted scoring passes call `action.setPriority(int)` to promote preferred actions before the agent's selection step.

For `SataAgent` the step is a fixed sequence of owners (`decision-pipeline`), mounted by group (`guidance-groups`). With the ADDONS member `APE_FIXES` and `ape.checkRestart`, the restart is a guard stage that runs before any selection, so a restart never replaces an action already chosen; without `APE_FIXES` (`base`, `mop`) the pipeline does not mount that stage and `ApeAgent.checkRestart` is applied after selection, as APE applies it, and may replace the selected action of any owner. Either way the step records `dec.src="restart"` with its kind. `RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart`. The LLM hooks consult declared gates rather than the SATA buffer: a new state reached mid-path may go to the LLM, and the buffer is then abandoned without weakening. With `APE_FIXES` the fuzz toss is drawn from its own `StepRng` stream once per step, after the step's action events are generated; without it the toss is APE's `RandomHelper.toss(ape.fuzzingRate)`, drawn only when fuzzing is on. The ADDONS mechanisms of the engine — `MODEL_MENU`, the dynamic epsilon, the two event-generation guards and the `APE_FIXES` corrections (restart timing, the bounded foreground wait, the off-screen drop, pinch emission and the fuzz-toss stream) — are read from the plan, so a cell without ADDONS behaves as upstream APE at those points. Every key the engine reads through the plan is resolved once, at startup, into the run plan (`RunContext.current().spec()`), with its default in `KeyOwnership`.

## Data Contracts

### Input

- `--ape <strategy>: String` — selects the exploration strategy; the accepted values are `sata` (default), `random` and `replay` (with `--ape-replay <log>`); any other value aborts before step 1; passed as a Monkey command-line argument (source: `Monkey.main()`)
- `--running-minutes <N>: int` — wall-clock time limit in minutes after which exploration stops (source: Monkey command-line)
- `-v <N>: int` — maximum number of Monkey events; used as an alternative stop condition (source: Monkey command-line)
- `/data/local/tmp/ape.properties` or `/sdcard/ape.properties: Properties file` — optional key-value configuration overrides, resolved at startup with the command line into the run plan (`RunSpec`); an unknown, retired or ill-typed key aborts the run before the first event (source: device filesystem)
- `AccessibilityNodeInfo: Android API` — live accessibility tree of the current screen, fetched by `AndroidDevice` via `UiAutomation` at each step (source: Android AccessibilityService)
- `ComponentName: Android API` — identity of the foreground activity, used to scope states per activity (source: `ActivityManager`)

### Output

- `*.png screenshots: files` — per-step PNG screenshots (when `ape.takeScreenshot` (default `true`) and `ape.takeScreenshotForEveryStep` (default `false`) are both true) or per-new-state PNG screenshots (when `ape.takeScreenshot` and `ape.takeScreenshotForNewState` (default `true`) are both true), written to the output directory; by default one PNG per new abstract state is written and no per-step PNG (INV-EXPL-17)
- `*.xml GUITree files: files` — XML serialisation of the accessibility tree at each step (when `ape.saveGUITreeToXmlEveryStep=true`, default `false`)
- `ape_log: logcat entries` — structured log lines emitted via `Logger` for every action selected, strategy event type, and state transition

### Side-Effects

- **Android device**: GUI events (touches, key presses, drags) are injected into the device via `UiAutomation.injectInputEvent()`, causing the app under test to change state.
- **App lifecycle**: The exploration engine may force-kill and restart the app under test (via `EVENT_RESTART` or `EVENT_CLEAN_RESTART` action types) when stability thresholds are exceeded.
- **Model graph**: The in-memory `Graph` object is mutated on every step as new `State` nodes and `StateTransition` edges are discovered.
- **NamingFactory lattice**: If `ape.evolveModel=true` (default `true`), the naming abstraction may be refined mid-session, causing existing `State` objects to be split and the model to be rebuilt.
- **Stability counters**: `StatefulAgent` maintains `graphStableCounter`, `stateStableCounter`, and `activityStableCounter` that are incremented each step and reset on graph/state/activity changes.

### Error

- `StopTestingException` — thrown by the agent to signal a clean stop condition (time limit or step limit reached); caught by `MonkeySourceApe` to terminate the loop gracefully.
- `BadStateException` — thrown with `"No available action on the current state"` by `StatefulAgent.handleNullAction()` (the last rung of the `SataChain` stage, and `RandomAgent`'s fallback) when its roulette yields no valid action, and by the `SataChain` stage itself when every rung returns null; caught in `ApeAgent.updateStateWrapper`, which triggers a recovery path (restart or back navigation).
- `NoValidActionException` — thrown when action validation fails after all candidates are exhausted; causes the loop to attempt a forced restart.
- `OutOfMemoryError` — possible but unhandled; caused by retaining all `GUITree` objects in `treeHistory` lists throughout the session.

## Invariants

- **INV-EXPL-01**: The exploration loop SHALL continue dispatching actions until either `StopTestingException` is thrown (time/step limit reached) or the process is killed externally. No other condition MAY cause silent loop exit.
- **INV-EXPL-02**: `ActionType.MODEL_BACK.requireTarget()` and `ActionType.MODEL_MENU.requireTarget()` SHALL both return `false`. Neither `MODEL_BACK` nor `MODEL_MENU` requires a widget target; both map directly to Android key events.
- **INV-EXPL-04**: `ActionType.MODEL_CLICK.requireTarget()`, `MODEL_LONG_CLICK.requireTarget()`, `MODEL_SCROLL_BOTTOM_UP.requireTarget()`, `MODEL_SCROLL_TOP_DOWN.requireTarget()`, `MODEL_SCROLL_LEFT_RIGHT.requireTarget()`, and `MODEL_SCROLL_RIGHT_LEFT.requireTarget()` SHALL each return `true`.
- **INV-EXPL-05**: `ActionType.isModelAction()` SHALL return `true` for all `MODEL_*` enum constants (`MODEL_BACK`, `MODEL_MENU`, `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`) and `false` for all other constants (`PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`).
- **INV-EXPL-06**: Every `State` object SHALL have non-null `backAction` and `menuAction` fields, each holding a `ModelAction` of their respective types (`MODEL_BACK` and `MODEL_MENU`). Both fields are initialised in the `State` constructor and MUST NOT be set to null at any point. (The plan feature `MODEL_MENU` (`ape.modelMenuEnabled`, ADDONS) gates only whether the `menuAction` is added to the State's selectable action *set* — the field itself stays non-null in every cell, including `base`, so this invariant holds verbatim.)
- **INV-EXPL-13**: `MODEL_MENU` SHALL be positioned in the `ActionType` enum after `MODEL_BACK` and before `MODEL_CLICK`. This placement ensures that `requireTarget()`'s ordinal range check (`MODEL_CLICK` through `MODEL_SCROLL_RIGHT_LEFT`) continues to correctly identify target-requiring actions without any change to the range boundaries.
- **INV-EXPL-09**: When `SataAgent` triggers a forced app restart due to graph stability (i.e., `graphStableCounter` reaching `ape.graphStableRestartThreshold`), the `graphStableCounter` MUST be reset to zero immediately after the restart is initiated.
- **INV-EXPL-10**: `RandomAgent` extends `StatefulAgent` and uses the same priority-weighted selection infrastructure. It does NOT implement a separate pure-random algorithm. The distinction from `SataAgent` is that `RandomAgent` uses `StatefulAgent`'s base priority assignment without SATA's directed graph navigation heuristics.
- **INV-EXPL-11**: `StatefulAgent.adjustActionsByGUITree()` SHALL be called after base priority assignment and before the agent's selection step. Any priority modifications made by external components (e.g., MOP guidance in Phase 3) MUST be applied inside or after this method, not before it.
- **INV-EXPL-12**: A `ModelAction` with a higher `priority` value SHALL be preferred over one with a lower `priority` value when `RandomHelper.randomPickWithPriority()` is used for selection within `StatefulAgent`.
- **INV-EXPL-14**: Given identical `-s` seed, APK, and configuration, the sequence of `RandomHelper` draws SHALL be identical across runs. `RandomHelper` is seeded with `seed ^ RANDOM_HELPER` and `Monkey.mRandom` with `new Random(seed)` (`run-spec` "Run Identity and Seed").
- **INV-EXPL-15**: When the plan carries `APE_FIXES`, no run SHALL spend more than 101 consecutive `checkAppActivity` iterations waiting for a foreground package without triggering a relaunch; without `APE_FIXES` the wait SHALL be unbounded, as upstream APE.
- **INV-EXPL-33**: When the plan carries `APE_FIXES`, the component of every relaunch of the app under test SHALL be taken from a launcher query made at that relaunch, unless that query returns nothing, in which case it SHALL be taken from the startup list. Without `APE_FIXES`, every relaunch SHALL take its component from the startup list and no relaunch SHALL query the package manager.
- **INV-EXPL-16**: `tearDown()` SHALL run on every termination path of the exploration loop, normal or abnormal, **and SHALL NOT replace the in-flight exception**: a `Throwable` thrown by any teardown step SHALL be caught and logged with its full stack trace inside the teardown chain, never propagated out of the `finally` block in `Monkey.run`.
- **INV-EXPL-29**: Teardown SHALL be step-isolated: a `Throwable` thrown by one teardown step SHALL NOT prevent any subsequent teardown step from executing. The steps are the rotation restore and `MonkeySourceApe.tearDown()` in `Monkey.run`'s `finally`; `disconnect`, `agentTearDown` and `imageWriters` in `MonkeySourceApe.tearDown`; and `flushPendingStep`, `superTearDown`, `coverageDump`, `actionCounters`, `activityNodes`, `namingDump`, `modelCounters` and `runEnd` in `StatefulAgent.tearDown` (each a `safeStep`). No graph-save, LLM-summary or action-history step exists.
- **INV-EXPL-17**: Per-step debug artifacts (PNG/XML) SHALL be written only when explicitly enabled via `ape.properties`.
- **INV-EXPL-18**: Every fuzz gesture branch SHALL build exactly one gesture per invocation; with `APE_FIXES` every branch SHALL append that gesture's event, and without it only the pinch/zoom branch SHALL NOT append its event.
- **INV-EXPL-19**: When the plan carries `APE_FIXES`, no touch event SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own bounds — or, for coordinate-carrying actions without a node (`MODEL_LLM_TAP`), other than the action's own decided coordinate. Without `APE_FIXES`, a node action whose bounds miss the visible screen SHALL be clicked on the display bounds, as upstream APE; a `MODEL_LLM_TAP` keeps INV-EXPL-30 in every cell.
- **INV-EXPL-30**: `MODEL_LLM_TAP` dispatch SHALL construct a non-degenerate rect `(x, y, x+1, y+1)` for the decided pixel `(x, y)`, and its validity domain SHALL be the physical display bounds (`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds. A tap whose pixel lies inside the display SHALL enqueue a touch down/up pair; a tap outside it SHALL be dropped with the `[APE-RV] off-screen action dropped` log line and no event. Node actions keep the root-node visible-bounds domain.
- **INV-EXPL-31**: Each of `State` (menu action, `spec().has(Feature.MODEL_MENU)` in its constructor), `SataAgent` (dynamic epsilon, `exploration().dynamicEpsilon()` in `computeDynamicEpsilon`, true exactly when `DYNAMIC_EPSILON` is in the plan) and `MonkeySourceApe.generateEvents` (the foreign-activity and tree/package guards, `spec().has(Feature.FOREIGN_ACTIVITY_GUARD)` and `spec().has(Feature.TREE_PACKAGE_GUARD)`) SHALL decide through the plan (`RunContext.current().spec()`). None of them SHALL read a `Config` field for it. On a plan without the `ADDONS` group, each of these mechanisms SHALL behave as upstream APE.
- **INV-EXPL-32**: For an agent with a decision pipeline (`SataAgent`), the `Restart` stage is mounted if and only if `ape.checkRestart` is `true` and the plan carries `APE_FIXES`. With the stage mounted, no restart SHALL replace a selected action after selection, and every restart SHALL be a step whose owner is `restart` (`decision-pipeline` INV-DP-14). Without the stage and with `ape.checkRestart` `true`, `ApeAgent.checkRestart` SHALL be applied once per step after selection, never on the step's `BadStateException` retry, and MAY replace the selected action of any owner; a replaced action SHALL be marked visited and SHALL NOT be counted by the MOP interaction ledger, target retirement or the LLM action history, and the step SHALL be recorded with owner `restart`. `RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart` wrapping unchanged (design D-B14.12).

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

Inclusion of the `menuAction` in the state's **selectable** action set is gated by the plan feature `MODEL_MENU` (key `ape.modelMenuEnabled`, default `true`), a member of the ADDONS group (design D-B6, INV-GRP-06). `State` SHALL read `RunContext.current().spec().has(Feature.MODEL_MENU)` and SHALL NOT read `Config.modelMenuEnabled` (`model/State.java:78` before this change). Otherwise a cell without ADDONS would still carry the menu action. When the plan carries `MODEL_MENU` (the ADDONS block sets `ape.modelMenuEnabled=true`), the `menuAction` SHALL be included in the array returned by `State.getActions()`, exactly as before this change. When the plan does not carry it (ADDONS off, as in the `base` and `mop` cells, or `ape.modelMenuEnabled=false`), the `menuAction` SHALL NOT be included in `State.getActions()` and the agent SHALL never select `MODEL_MENU`; the field SHALL still be constructed and returned by `State.getMenuAction()` (so `INV-EXPL-06` holds). This reproduces upstream APE, which has no model-level options-menu action.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** when the plan carries `MODEL_MENU` (e.g. the `as` cell) the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU excluded from selection when modelMenuEnabled is false
- **WHEN** the plan does not carry `MODEL_MENU` (the `base` cell, with ADDONS off, or `ape.modelMenuEnabled=false`) and a new `State` is constructed
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

---

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

`SataAgent` (via its superclass `StatefulAgent`) monitors whether the exploration graph has stopped growing. The counter `graphStableCounter` is incremented each step in which no new `State` or `StateTransition` is added to the `Graph`. When `graphStableCounter` exceeds `ape.graphStableRestartThreshold` (default: `100`) in step 2's `checkStable`, `onGraphStable` SHALL raise the restart flag with kind `graph` (`requestRestart()`) and the counter MUST be reset to zero immediately. This prevents the exploration from getting stuck in a saturated region of the state space. The state and activity stability checks raise the same flag with kinds `state` and `activity`. The activity threshold is `ape.activityStableRestartThreshold` when the plan carries the ADDONS member `ACTIVITY_STABLE_RESTART`, and otherwise the upstream `Integer.MAX_VALUE` (design D-B5).

Where the restart is applied depends on whether the pipeline mounted the `Restart` stage, which it does if and only if `ape.checkRestart` is `true` (BASE, jar default `true`; design D-B14.12) and the plan carries the ADDONS member `APE_FIXES` (design D5). Both are read from the plan (`spec`), never from `Config.checkRestart` (design D-B6, extended by author decision A10). `ApeAgent.restartStageMounted()` returns `false`, and `SataAgent` overrides it with whether its assembled pipeline contains `RESTART`, computed once when the pipeline is assembled; that is the single decision point.

**Stage mounted (the ADDONS cells).** The restart is selected by the `Restart` guard stage of the decision pipeline (`decision-pipeline` "Restart Guard Stage"). That stage runs after `Budget` and before any LLM, MOP or SATA stage, and it selects an `EVENT_RESTART` or `EVENT_CLEAN_RESTART` start action through `StatefulAgent.restartAction()`, which drops the action buffer without weakening and then calls `ApeAgent.startRestart()`. The step's record carries `dec.src="restart"` and `dec.restart` set to the kind, and `dec.a` names the start action that ran. A restart preempts the step. It never replaces a selected action, so no selected model action is lost to it, and `ApeAgent.checkRestart` returns its argument unchanged.

**Stage not mounted (`APE_FIXES` absent, as in the `base` and `mop` cells, with `ape.checkRestart=true`).** The restart is applied after selection, as upstream APE applies it (design D6). The latch that blocks a second threshold restart is cleared at step start. Inside `StatefulAgent.updateStateInternal`, after `resolveNewAction()` and after `getGraph().markVisited(action)` has marked a selected model action visited, `ApeAgent.checkRestart(action)` is called once. On the step's `BadStateException` retry, the second `updateStateInternal` that `ApeAgent.updateStateWrapper` runs after retrieving the window root again, `checkRestart` SHALL return the action unchanged, as APE's retry runs without `checkRestart` (`decision-pipeline` "Post-Selection Restart"). Otherwise it SHALL return the action unchanged when no restart is due (`restartDue()`: the restart flag is raised, or a threshold has passed and the latch is not set), and otherwise SHALL return the start action of `ApeAgent.startRestart()`, which records the kind and `lastRestartStep`, redraws the threshold for `periodic`, starts a new episode, writes `dec.restart`, increments the `restarts` counter and tosses the `EVENT_CLEAN_RESTART` kind. When the selected action is kept, `recordActionHistory` and `moveForward` run as on every step, and the action is forwarded to the pipeline's stages (`onSelectionKept`), where the `MopLauncher` stage commits a launch. When it is replaced, neither runs: `resetTrace` runs on the action buffer and nulls its pointers, and the new episode's buffer clear weakens the planned edge, so the graph, the visit marks and the weakening equal APE's. The fork-only bookkeeping reached through `moveForward` and `recordActionHistory` — the MOP interaction ledger and target retirement (`dec.ret`), the UI-coverage interaction count, the budget iteration and the LLM action history — SHALL NOT count the replaced action, and a replaced launch SHALL NOT enter the `MopLauncher` stage's launched set, consume its budget or count `launches`. `StatefulAgent` then rewrites the step's decision as a restart decision (`decisionReplaced` with owner `restart` and channel `restart`, replacing the earlier decision of the same step) and clears `lastDecisionAction`, so the record carries `dec.src="restart"`, `dec.ch="restart"`, `dec.restart` and `dec.a` naming the start action, as a stage-selected restart step does. A post-selection restart replaces the action of any owner (SATA, MOP coin, launcher, LLM); LLM attempts already made in the step stay in its `llm[]`.

With `ape.checkRestart=false` no restart is applied on either path. `RandomAgent` and `ReplayAgent` have no pipeline and always restart after selection, unchanged.

INV-EXPL-32: for an agent with a decision pipeline (`SataAgent`), the `Restart` stage is mounted if and only if `ape.checkRestart` is `true` and the plan carries `APE_FIXES`. With the stage mounted, no restart SHALL replace a selected action after selection, and every restart SHALL be a step whose owner is `restart` (`decision-pipeline` INV-DP-14). Without the stage and with `ape.checkRestart` `true`, `ApeAgent.checkRestart` SHALL be applied once per step after selection, never on the step's `BadStateException` retry, and MAY replace the selected action of any owner; a replaced action SHALL be marked visited and SHALL NOT be counted by the MOP interaction ledger, target retirement or the LLM action history, and the step SHALL be recorded with owner `restart`. `RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart` wrapping unchanged (design D-B14.12).

#### Scenario: Graph stable for threshold steps

- **WHEN** `graphStableCounter` reaches `101` consecutive steps without any change to the `Graph`, exceeding `ape.graphStableRestartThreshold=100`
- **AND** the plan carries `APE_FIXES` and `ape.checkRestart=true`, so the `Restart` stage is mounted
- **THEN** `StatefulAgent.onGraphStable(101)` SHALL be called on the active agent in step 2's `checkStable`
- **AND** `SataAgent.onGraphStable()` SHALL return `true` and raise the restart flag with kind `graph`, indicating a restart should be performed
- **AND** at `graphStableCounter=100` it SHALL have returned `false`, because the threshold must be exceeded, not reached
- **AND** the `Restart` stage of the same step SHALL select a start action (`EVENT_RESTART`, or `EVENT_CLEAN_RESTART` with probability 0.2) before any LLM or SATA stage is evaluated, with `dec.src="restart"`, `dec.ch="restart"` and `dec.restart="graph"`
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

#### Scenario: post-selection restart without APE_FIXES replaces the selected action

- **WHEN** the `base` cell runs `SataAgent` with `ape.checkRestart=true`, so `APE_FIXES` is absent and the `Restart` stage is not mounted
- **AND** `onGraphStable` raises the restart flag with kind `graph` in step 2's `checkStable` and SATA then selects click A on state S
- **THEN** A SHALL be marked visited in the graph before `ApeAgent.checkRestart` runs
- **AND** `ApeAgent.checkRestart` SHALL replace A with the start action (`EVENT_RESTART`, or `EVENT_CLEAN_RESTART` with probability 0.2)
- **AND** `moveForward` and `recordActionHistory` SHALL NOT run for A, so no MOP interaction, target retirement, UI-coverage interaction, budget iteration or action-history entry SHALL be recorded for A
- **AND** the new episode SHALL weaken the planned edge of the action buffer
- **AND** the step's record SHALL carry `dec.src="restart"`, `dec.ch="restart"`, `dec.restart="graph"` and `dec.a` naming the start action, and the `restarts` counter SHALL increase by `1`

#### Scenario: post-selection restart keeps an action when none is due

- **WHEN** the `Restart` stage is not mounted, `ape.checkRestart=true`, the restart flag is not raised and no threshold has passed
- **AND** SATA selects click A
- **THEN** `ApeAgent.checkRestart` SHALL return A unchanged
- **AND** `recordActionHistory` and `moveForward` SHALL run for A, and the step's record SHALL keep the decision that selected A

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

### Requirement: StatefulAgent — Priority-Based Action Selection

`StatefulAgent` and its subclasses SHALL use a `priority` integer field on each `ModelAction` to break ties and express preferences. Higher numeric priority means higher preference. The method `StatefulAgent.adjustActionsByGUITree()` is called after the base priority has been assigned and before the agent selects an action. This is the designated extension point where the mounted scoring passes (`scoring-pipeline`) MAY call `ModelAction.setPriority(int)` to boost specific actions.

`StatefulAgent` SHALL load the static-analysis artifact at construction time if and only if `STATIC` or `MOP` is in the resolved plan, from the path `ape.staticDataPath` (`spec.staticData().dataPath()`; `STATIC_DATA`), which replaces the retired `ape.mopDataPath` (design D-B2, D-B14.20). `Config.mopDataPath` no longer exists. The loader splits the artifact into `StaticGuiModel` and `MopData` (`mop-guidance`). `getMopData()` SHALL be null if and only if `MOP` is not in the plan: in the `as` and `as_llm` cells the artifact is loaded for `STATIC`, yet no consumer receives a `MopData` (design D-B14.21). With `MOP` mounted, the MOP scoring passes apply in `adjustActionsByGUITree()` after the base priority loop. A MOP pass SHALL only apply to actions where `action.requireTarget() == true` AND `action.isValid() == true`. The boost is additive: `action.setPriority(action.getPriority() + term)`, with the term written to the pass's own field. When `MOP` is not in the plan, no MOP pass is constructed and no `MopScorer` call is made.

The selection method `RandomHelper.randomPickWithPriority(List<ModelAction>)` MUST prefer actions with higher priority; actions with equal priority are selected uniformly at random among that priority tier.

#### Scenario: Higher priority action preferred
- **WHEN** `StatefulAgent` has two candidate actions `actionA` (priority=10) and `actionB` (priority=1)
- **THEN** `actionA` SHALL be selected with probability proportional to its priority weight under `randomPickWithPriority`

#### Scenario: MOP boost applied to direct-reachable widget
- **WHEN** the plan is the `mop` cell with `ape.staticDataPath` naming a valid format-2 artifact under `ape.mopScoring=flag`, AND `actionA` targets widget `btn_encrypt`, which the artifact marks `direct` for `click`
- **THEN** `actionA.getPriority()` after `adjustActionsByGUITree()` SHALL equal `basePriority + 500`, and its `mopBoost` SHALL be `500`

#### Scenario: MOP scoring skipped when mopDataPath is null
- **WHEN** `MOP` is not in the plan — the `base` cell with no `ape.staticDataPath`, or the `as` cell, which loads the artifact for `STATIC`
- **THEN** `getMopData()` SHALL return `null` and `adjustActionsByGUITree()` SHALL complete without any call to `MopScorer`
- **AND** no action SHALL carry a non-zero `mopBoost`, `mopFrontierBoost`, `wtgBoost` or `menuBoost`; in the `base` cell every priority SHALL equal its base SATA-assigned value
- **AND** the header names the retired `ape.mopDataPath` because `openspec archive` matches scenarios by name

#### Scenario: Non-target actions not boosted
- **WHEN** `MOP` is in the plan AND `actionB` is `MODEL_BACK` (`requireTarget() == false`)
- **THEN** `actionB.getPriority()` SHALL NOT be modified by any MOP scoring pass

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

`ape.doFuzzing` and `ape.fuzzingRate` are BASE keys of the feature `FUZZING`, and `MonkeySourceApe` SHALL read them from the plan (`spec`), never from `Config` (design D-B6, extended by author decision A10). When `ape.doFuzzing=false`, `FUZZING` is absent from the plan and the agent never injects random `FUZZ` actions outside the structured exploration loop. When `ape.doFuzzing=true` (the jar default), at each step the agent MAY inject a random `FuzzAction` with probability `ape.fuzzingRate` (default: `0.02`, i.e., 2% of steps). The fuzz toss is evaluated in `MonkeySourceApe.generateEvents`, after the step's action events are generated, as one expression whose source is decided by the ADDONS member `APE_FIXES` (design D9):

- **With `APE_FIXES` in the plan** (the ADDONS cells), the toss SHALL come from `StepRng`'s own `fuzz` stream (`StepRng.fuzzToss`, `decision-pipeline` INV-DP-13), not from `RandomHelper`. It is drawn once per step, whether or not fuzzing is enabled and whatever the step's owner, so that enabling fuzzing or changing its rate never shifts a helper-stream draw.
- **Without `APE_FIXES`** (the `base` and `mop` cells), the toss SHALL be `RandomHelper.toss(ape.fuzzingRate)`, drawn only when `FUZZING` is in the plan, as upstream APE. With fuzzing off no draw is made for fuzzing from either stream.

The injection happens when `FUZZING` is in the plan, the toss holds, and `Agent.canFuzzing()` admits. `canFuzzing()` is `false` on a step whose selected action's activity has been visited fewer than `ape.fuzzingActivityVisitThreshold` (default: `10`) times (`StatefulAgent.checkFuzzing` sets `disableFuzzing`), preventing fuzzing from disrupting early exploration, and always `false` under `ReplayAgent`.

A step that injects a `FuzzAction` SHALL record the kinds of the gesture events it queued as `dec.fz` (`event-sink` "StepRecord Schema", design D14), with no RNG draw and no change to the events.

#### Scenario: Fuzzing disabled

- **WHEN** `ape.doFuzzing=false`
- **THEN** `FUZZING` SHALL be absent from the plan, so `Agent.canFuzzing()` is never consulted and no `FuzzAction` is generated at any step
- **AND** no `FuzzAction` SHALL be injected into the event queue
- **AND** with `APE_FIXES` in the plan, the fuzz toss SHALL still be drawn from the `fuzz` stream once on every step, and no `RandomHelper` draw SHALL be made for fuzzing
- **AND** without `APE_FIXES`, no draw SHALL be made for fuzzing from either the `fuzz` stream or `RandomHelper`

#### Scenario: Fuzzing fires at expected rate

- **WHEN** `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **AND** the current activity's visit count reaches `ape.fuzzingActivityVisitThreshold` (default: `10`)
- **THEN** across a large number of steps, approximately 2% of steps SHALL inject a `FuzzAction`
- **AND** the remaining 98% of steps SHALL proceed through the normal action-selection path

#### Scenario: an injected FuzzAction is recorded

- **WHEN** a step's fuzz toss fires, `canFuzzing()` holds, and the `FuzzAction` queues a trackball event and a pinch, with `APE_FIXES` in the plan
- **THEN** the step's record SHALL carry `dec.fz:["trackball","pinch"]`
- **AND WHEN** the same events are generated without `APE_FIXES`, so the pinch is not queued
- **THEN** the record SHALL carry `dec.fz:["trackball"]`

#### Scenario: fuzz toss without APE_FIXES draws from RandomHelper

- **WHEN** the `base` cell runs with `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **THEN** each step SHALL draw the fuzz toss as `RandomHelper.toss(0.02)` after the step's action events are generated
- **AND** no draw SHALL be made from `StepRng`'s `fuzz` stream

---

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a supported configuration channel; note that `Config` builds its `Properties` with `System.getProperties()` as defaults, so a system property could reach a key that `Config` reads without plan validation or echo. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; tests construct `RunSpec` values instead of mutating `Config`. `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority. A key that the plan owns SHALL be read from the plan (`RunContext.current().spec()`) and has no `Config` field: in particular `ape.doFuzzing`, `ape.fuzzingRate` and `ape.checkRestart` are BASE keys read from the plan (design D-B6, extended by author decision A10), and `ape.apeFixes` is the activation key of the ADDONS member `APE_FIXES`, read through `spec().has(Feature.APE_FIXES)`.

The table below lists the configuration keys relevant to the exploration engine with their types and defaults:

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart (BASE) |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart (BASE) |
| `ape.activityStableRestartThreshold` | int | 200 in the ADDONS block | Steps in same activity before forced restart. Activation key of the ADDONS member `ACTIVITY_STABLE_RESTART` (POSITIVE, neutral 0), no longer a BASE key; when the feature is absent (ADDONS off, or the key `0`) the threshold is the upstream `Integer.MAX_VALUE` (design D-B5) |
| `ape.apeFixes` | boolean | true (requires `ape.addons`; `false` when ADDONS is off) | Activation key of the ADDONS member `APE_FIXES`: with it the restart guard stage, the bounded foreground wait, the off-screen drop, the pinch/zoom emission and the `fuzz`-stream toss; without it APE's code at each of those points |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection (BASE, read from the plan) |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step (BASE, read from the plan) |
| `ape.checkRestart` | boolean | true | Enable restarts (BASE, read from the plan): the `Restart` guard stage when `APE_FIXES` is in the plan, the post-selection `checkRestart` otherwise |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.takeScreenshotForNewState` | boolean | true | Save one PNG per new abstract state (with `ape.takeScreenshot`) |
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
- **THEN** all `Config` fields and all BASE plan keys SHALL take their hardcoded default values, and no group member SHALL be in the plan, so `ACTIVITY_STABLE_RESTART` and `APE_FIXES` are absent and the activity threshold is `Integer.MAX_VALUE`
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

#### Scenario: the activity-stable threshold follows ADDONS
- **WHEN** the `as` cell resolves, whose ADDONS block sets `ape.activityStableRestartThreshold=200`
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be in the plan and the activity-stability check SHALL raise the restart flag once the counter exceeds `200`
- **AND WHEN** the `base` cell resolves
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be absent and the threshold SHALL be `Integer.MAX_VALUE`, so the activity check never raises the flag

#### Scenario: APE_FIXES follows ADDONS
- **WHEN** the `as`, `as_mop`, `as_llm` or `as_mop_llm` cell resolves, whose ADDONS block states `ape.apeFixes=true`
- **THEN** `APE_FIXES` SHALL be in the plan
- **AND WHEN** the `base` or `mop` cell resolves
- **THEN** `APE_FIXES` SHALL be absent, so every point it decides runs upstream APE's code

---

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

`StatefulAgent` SHALL maintain a ring buffer of the last 5 selected model actions with their results. When a model action is selected, before it is dispatched, an `ActionHistoryEntry` SHALL be appended to the buffer with an empty result. The entry's result SHALL be the outcome of that entry's own action, filled by the next state update before any LLM stage of that step runs, so that no prompt pairs an action with another action's outcome.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back", "menu" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back, menu and an off-tree LLM tap |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `bounds` | `Rect` | The executed widget's bounds in screen **pixels**: a copy of the resolved node's `getBoundsInScreen()`; for an off-tree `MODEL_LLM_TAP`, the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()`/`getPixelY()`; null for back and menu |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen"; empty until the next state update fills it |

`StatefulAgent` SHALL NOT normalize and SHALL NOT read `AndroidDevice.getDisplayBounds()` for the history. `ApePromptBuilder` derives the displayed coordinate at build time (the centre of the bounds' part inside the display) with the dimensions the decision was captured at (`llm-prompt` "Action History"), so the list and the history of one prompt share one base.

An action with no resolved node SHALL record what ran: a `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with the one-pixel rectangle at the tap's own pixel, and no class or text; a `MODEL_MENU` press records `menu` with no bounds. A node action whose bounds cannot be read records null bounds, never a `(0,0)` placeholder.

For a node action, `widgetClass` SHALL be produced by the helper the element list uses for the class simple name, and `widgetText` by the text-then-content-description steps of the list's identifier fallback (`llm-prompt` "Widget List Generation", INV-PRM-05), not by reading `getClassName()` or `getText()` directly. The list's `id=<shortId>` step is not applied (`pending.md` P2).

**Result determination**: In `updateStateInternal()`, after the new state is resolved and `_lastState`/`_stateBeforeLast` have moved, and before `resolveNewAction()` runs, the last entry of the buffer, if its result is still empty, receives the outcome of its action. At that point `newState` is the screen that action produced, `_lastState` the state it was taken from, and `_stateBeforeLast` the state before that:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The entry is immutable: the agent replaces it with a copy carrying the result. A filled result is never overwritten. When the step after an entry's action selects no model action, nothing is appended, and the next update finds the last entry already filled, so an outcome is never assigned to an action it does not belong to.

**Restart**: A restart step appends no entry and removes none, on either restart path ("SataAgent — Forced App Restart on Graph Stability"). When the `Restart` guard stage is mounted, it selects the restart before any model action is selected (`decision-pipeline` INV-DP-14), so no model action is recorded for that step. When the stage is not mounted, the post-selection `ApeAgent.checkRestart` runs before `recordActionHistory`, and a selected model action it replaces never reaches `recordActionHistory`, so no entry is appended for it. The start action is not a model action. No path removes an entry after it was appended, and `startNewEpisode` removes nothing on any path.

The buffer is passed to `ApePromptBuilder.build()` via `LlmEngine.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is selected
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"` and an empty result
- **AND WHEN** the next state update resolves a state different from the state the click was taken from and from the state before that
- **THEN** that entry's result SHALL be `"new screen"` before any LLM stage of that step runs

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new model action is selected
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is selected from state S2, which was reached from state S1
- **AND** the next state update resolves S1
- **THEN** the back action's `ActionHistoryEntry.result` SHALL be `"previous screen"`

#### Scenario: each entry carries its own action's outcome
- **WHEN** click A1 is selected from state S1 and the next update resolves a new state S2
- **AND** click A2 is selected from S2 and the next update resolves S2 again
- **THEN** the prompt built at that update SHALL show `A1 → new screen` and `A2 → same`
- **AND** A2's entry SHALL NOT read `new screen`, the outcome of A1

#### Scenario: a step without a model action does not shift an outcome
- **WHEN** click A1 is selected and the next update fills its result
- **AND** that step selects an action that is not a model action, so no entry is appended
- **AND** the update after it resolves a different state
- **THEN** A1's result SHALL keep the value filled first

#### Scenario: a model action replaced by a restart leaves no entry
- **WHEN** click A1 is selected and the next update fills its result
- **AND** on the next step either the `Restart` guard selects a start action before any model action is selected, or, with the stage not mounted, the post-selection `checkRestart` replaces the selected click A2 with a start action
- **THEN** no entry SHALL be appended for that step, so no entry for a replaced action can exist
- **AND** A1's entry SHALL remain the last entry, with the result filled first

#### Scenario: a restart that replaces no model action removes nothing
- **WHEN** click A1 is selected and the next update fills its result
- **AND** a restart takes the following step with a start action, through the `Restart` guard or the post-selection `checkRestart`
- **THEN** A1's entry SHALL remain, with its result, and the buffer SHALL be unchanged

#### Scenario: entry carries the pixel bounds
- **WHEN** a click on a widget with bounds `[0,105][1080,252]` is selected
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`, a copy rather than the node's own `Rect`
- **AND** no display dimension SHALL have been read to produce it

#### Scenario: off-tree LLM tap recorded at its pixel
- **WHEN** a `MODEL_LLM_TAP` at pixel `(600, 900)` with `isLongClick()` false is selected
- **THEN** the entry SHALL carry `actionType="click"`, the bounds `[600,900][601,901]`, and no widget class

#### Scenario: menu press recorded as menu
- **WHEN** a `MODEL_MENU` action is selected
- **THEN** the entry SHALL carry `actionType="menu"`, no bounds and no widget class

#### Scenario: icon with only a content-description
- **WHEN** a click is selected on a node of class `android.widget.ImageButton` with empty text and content-description `"Settings"`
- **THEN** the entry SHALL carry `widgetClass="ImageButton"` and `widgetText="Settings"`

---

### Requirement: SataAgent — LLM New-State Hook

LLM new-state routing SHALL be the `LlmNewState` decision stage, the first stage after the `Budget` and `Restart` guards. It is assembled only when the plan carries `LLM_NEW_STATE`. `SataAgent.selectNewActionNonnull()` SHALL contain no inline LLM block: it delegates to `DecisionPipeline.decide()`, and the stage occupies the ladder position the inline block occupied (before stagnation, coin, launcher, trigger, MOP coin and SATA; the per-cell goldens gate the behaviour).

The stage checks the new-state trigger (`_isNewState`) first, with no coin (D-A17). It then evaluates the gate chain that all three LLM stages share (`llm-routing` "Declared LLM Gates"): `MinActionsGate(ape.llmGateMinActions)`, default 3, which skips trivial states such as permission dialogs with two actions or fewer; then `BreakerGate`. The action buffer is **not** a gate (D-A6, P-4). A new state reached while a SATA path is buffered goes to the LLM, and when the LLM takes the step the buffer is abandoned without `weakenStateTransition`, with `dec.buf="llm"` (`decision-pipeline` INV-DP-15). On a non-null engine result the stage returns `Select` (stamping `DecisionSource.LLM`/`PickChannel.LLM`, and resolving a synthesized `MODEL_LLM_TAP` against the state — unchanged accept semantics); on null it returns `Continue` and the remaining pipeline decides (structural fallback). Once this stage has called, the later LLM stages of the step do not call again: a step makes at most one LLM call.

When the plan has no LLM feature, the stage does not exist and selection cost is zero.

#### Scenario: LLM provides action on new state

- **WHEN** the pipeline reaches `LlmNewState` with `actions.size() >= 3`, `_isNewState` true, breaker allowing
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step (hard preemption — no later stage evaluated)

#### Scenario: LLM returns null, SATA takes over

- **WHEN** the engine returns `null`
- **THEN** the stage SHALL return `Continue`, with `dec.own.fb` naming the reason
- **AND** `LlmStagnation` and `LlmCoin` SHALL return `Continue` without calling the LLM again on that step, because a step makes at most one LLM call (`decision-pipeline` "Hard Preemption Order")
- **AND** the launcher, trigger, MOP-coin and SATA stages SHALL evaluate normally

#### Scenario: LLM skipped — buffer has pending navigation

- **WHEN** the action buffer holds two planned transitions (multi-step navigation in progress), the state is new with 5 actions, the breaker allows, and the LLM returns an accepted action
- **THEN** the LLM SHALL be called and its action SHALL decide the step: the buffer is no longer a gate
- **AND** the buffer SHALL be abandoned without `weakenStateTransition`, and the step record SHALL carry `dec.buf="llm"`
- **AND** the header is kept because `openspec archive` matches scenarios by name, although the LLM is no longer skipped

#### Scenario: LLM skipped — trivial state

- **WHEN** `actions.size() <= 2` (e.g., permission dialog), with `ape.llmGateMinActions` at its default `3`
- **THEN** `MinActionsGate` SHALL refuse, the LLM stages SHALL return `Continue` with `dec.own.g="min_actions"`, and the pipeline SHALL handle the state directly

#### Scenario: LLM disabled, zero overhead

- **WHEN** the plan carries no LLM feature
- **THEN** no LLM stage SHALL exist, no `u_L` SHALL be drawn, and the pipeline SHALL be structurally identical to the corresponding non-LLM cell's (`as_mop_llm` without LLM is `as_mop`)
- **AND** the decision sequence SHALL equal that cell's golden under the same seed

---

### Requirement: SataAgent — LLM Stagnation Hook

LLM stagnation routing SHALL be the `LlmStagnation` decision stage, assembled after `LlmNewState` and before `LlmCoin` when the plan carries `LLM_STAGNATION`. The trigger is at-or-past the midpoint with the episode's single shot unspent: `graphStableCounter >= graphStableRestartThreshold / 2` AND the stage's per-episode fired flag is armed. The trigger consumes no coin (D-A17). When it holds, the same shared gate chain applies (`MinActionsGate`, then `BreakerGate`; `llm-routing` "Declared LLM Gates"). The action buffer is not a gate, and an accepted answer abandons a non-empty buffer without weakening (`decision-pipeline` INV-DP-15). A gate refusal leaves the flag armed, because no call was made.

**Episode-state ownership (moved out of `StatefulAgent`):** the fired flag SHALL be a private field of the `LlmStagnation` stage. It is burned inside `decide()` whenever the trigger fires and the gates admit the call — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold is what follows if the stagnation persists) — and re-armed by the stage's `onStateTransition` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode). The stage's own reset of `graphStableCounter` after a successful escape does not re-arm the flag: no new edge was observed, so it is the same episode.

**One scenario below is a replacement, not a carry-forward.** `"Stagnation hook fires only once per phase"` asserted in the main spec that the hook fires *only* at `graphStableCounter == threshold/2` exactly — 49 and 51 both declined by the equality check. This change replaces that trigger with at-or-past the midpoint guarded by the episode flag, so the old body is contradicted rather than forgotten: 51 with the flag armed now fires. What the scenario's name asserts — one firing per episode — is what survives, and it survives structurally (the flag is burned in `decide()`, not re-derived from the counter), so the scenario is restated under its own header with the new mechanism as its body. The counter-jump case the old equality check would have missed is covered by `llm-routing :: Stagnation LLM Mode :: "midpoint skipped by a counter jump still fires"`.

`graphStableCounter` remains a `StatefulAgent`/`RunContext` field — it is shared exploration state consumed by the stability restart (`onGraphStable` raises the flag at `counter > threshold`, and the restart is applied by the `Restart` guard when the pipeline mounted it, or by the post-selection `checkRestart` otherwise; "SataAgent — Forced App Restart on Graph Stability"), which this stage does not modify. On an accepted escape the stage SHALL reset the counter to 0 through the `StepContext`'s single declared write method (unblocking exploration, unchanged semantics).

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
- **AND** if `graphStableCounter` eventually exceeds `graphStableRestartThreshold`, `requestRestart()` SHALL be called in step 2's `checkStable`; with the `Restart` stage mounted, the `Restart` guard SHALL select the restart in that same step, before any LLM stage is evaluated (INV-EXPL-09, `decision-pipeline` "Restart Guard Stage"), and without it the post-selection `checkRestart` SHALL replace that step's selected action with the start action (INV-EXPL-32)

#### Scenario: new edge re-arms the episode through the stage hook

- **WHEN** the flag is burned and a later transition records a `NEW_ACTION` edge
- **THEN** `onStateTransition` SHALL re-arm the flag inside the stage
- **AND** a later stagnation reaching the midpoint SHALL fire again

#### Scenario: no stagnation state remains in StatefulAgent

- **WHEN** the change is complete
- **THEN** `StatefulAgent` SHALL declare no LLM-stagnation fired flag
- **AND** the only stagnation-episode state SHALL live in the `LlmStagnation` stage

---

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

### Requirement: Seeded Agent Decision Reproducibility

All agent-decision randomness routed through `RandomHelper` (priority roulettes such as `randomPickWithPriority`, uniform picks, `toss`, and `ApeFuzzer` gesture generation) SHALL be driven by a `java.util.Random` seeded from the Monkey `-s` seed. The seeded `java.util.Random` is owned by the `RunContext` (`run-spec` capability), constructed once at bootstrap as `RandomHelper.seed(seed ^ RANDOM_HELPER)`, where `seed` is the plan's seed — the same value that initializes `Monkey.mRandom` — and `RANDOM_HELPER` is a fixed odd 64-bit constant distinct from `StepRng`'s four stream constants (design D11). It is reached through the context, never through static state. `Monkey.mRandom` keeps `new Random(seed)`, so the two generators do not produce one sequence at two offsets, and `RUN_START.seed` keeps the run seed. INV-EXPL-14: given identical `-s` seed, APK, and configuration, the sequence of `RandomHelper` draws SHALL be identical across runs. Input-string generation SHALL draw from this same stream: the `StringCache` `ThreadLocalRandom` path (V23) and the `/sdcard/ape.strings` reader are deleted (owner decision D6), so no agent-decision or input randomness remains outside the seeded stream. Two runs launched with the same `-s`, the same APK, and the same configuration SHALL produce the same sequence of `RandomHelper` draws. (Historically `RandomHelper` used `ThreadLocalRandom.current()`, which cannot be seeded, and a residual `ThreadLocalRandom` survived in `StringCache.nextString()` — verified V23 — leaving input generation unseeded even after `RandomHelper` was fixed. Both are gone.)

#### Scenario: identical seeds produce identical draw sequences
- **WHEN** `RandomHelper.seed(42)` is called and a sequence of `randomPick`/`toss`/`nextInt` draws is recorded, then `RandomHelper.seed(42)` is called again
- **THEN** repeating the same sequence of calls SHALL yield the same values in the same order

#### Scenario: seed comes from the Monkey -s flag
- **WHEN** the Monkey is launched with `-s 12345`
- **THEN** `RandomHelper` SHALL be seeded with `12345 ^ RANDOM_HELPER` before the first agent decision
- **AND** `Monkey.mRandom` SHALL be `new Random(12345)`
- **AND** `RUN_START.seed` SHALL be `12345`

#### Scenario: helper seed differs from the Monkey seed
- **WHEN** the run seed is any value `s`
- **THEN** the seed handed to `RandomHelper` SHALL be `s ^ RANDOM_HELPER`, which differs from `s` because `RANDOM_HELPER` is non-zero
- **AND** `RANDOM_HELPER` SHALL differ from each of `StepRng`'s stream constants

---

### Requirement: Bounded Foreground Wait

When `waitForActivity` is active and the foreground package is not in the allowed set, `MonkeySourceApe.checkAppActivity()` throttles 100 ms and re-checks on the next `getNextEvent()`. When the plan carries the ADDONS member `APE_FIXES`, this wait SHALL be bounded: a consecutive-iteration counter SHALL be maintained, and when it exceeds 100 iterations (~10 s) the engine SHALL log `[APE-RV] waitForActivity exceeded 100 cycles, relaunching`, clear the wait state, and relaunch the app under test via the existing restart path (`startRandomMainApp`). The counter SHALL reset whenever the allowed package reaches the foreground. The bound is one condition, `has(APE_FIXES) && ++waitForActivityCycles > 100` (design D8). Without `APE_FIXES` (the `base` and `mop` cells) the wait SHALL be unbounded, as upstream APE: no relaunch is triggered by the wait and the log line is not emitted, so an app that never returns to the foreground consumes the rest of the `--running-minutes` budget in 100 ms throttles. INV-EXPL-15: when the plan carries `APE_FIXES`, no run SHALL spend more than 101 consecutive `checkAppActivity` iterations waiting for a foreground package without triggering a relaunch; without `APE_FIXES` the wait SHALL be unbounded.

#### Scenario: wedged app is relaunched
- **WHEN** the plan carries `APE_FIXES`
- **AND** the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL be cleared
- **AND** the app under test SHALL be relaunched
- **AND** one `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

#### Scenario: normal wait resets the counter
- **WHEN** the allowed package reaches the foreground after 5 wait iterations
- **THEN** the counter SHALL reset to 0
- **AND** no relaunch SHALL occur

#### Scenario: wait is unbounded without APE_FIXES
- **WHEN** the `base` cell runs and the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL NOT be cleared and the app under test SHALL NOT be relaunched by the wait
- **AND** no `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

---

### Requirement: Per-Step Debug Artifact Defaults

`ape.takeScreenshotForEveryStep` and `ape.saveGUITreeToXmlEveryStep` SHALL default to `false`. Both are plan keys read through `RunContext.current().spec().exploration()` (`takeScreenshotForEveryStep()`, `saveGUITreeToXmlEveryStep()`), with their defaults in `KeyOwnership`; `Config` has no field for either (design D-B24). Both are debug aids: the experiment pipeline consumes neither (coverage comes from logcat, the trace from stdout; the aperv-tool pulls no per-step PNG/XML), and the LLM path captures its own screenshots on demand via `ScreenshotCapture`, independent of these flags. Per-step PNG + XML writes are pure I/O overhead on the exploration loop (estimated 20-40% of step throughput on an emulator). Debug runs re-enable either flag via `ape.properties`.

`ape.takeScreenshotForNewState` is not a per-step artifact and SHALL default to `true` (a jar default in `KeyOwnership`, not a cell key; design D12). `StatefulAgent.onAddNode` saves one PNG per new abstract state when `spec().exploration().takeScreenshot()` and `takeScreenshotForNewState()` are both true. The capture's `MonkeySourceApe.nextImageWriter()` draws `Monkey.mRandom` once per new state, as upstream APE does with the same setting. The output contract is therefore: per-step PNG screenshots when `ape.takeScreenshot` (default `true`) and `ape.takeScreenshotForEveryStep` (default `false`) are both true, per-new-state PNG screenshots when `ape.takeScreenshot` and `ape.takeScreenshotForNewState` (default `true`) are both true, and per-step GUITree XML when `ape.saveGUITreeToXmlEveryStep` (default `false`) is true; by default one PNG per new abstract state is written and no per-step artifact (INV-EXPL-17).

#### Scenario: defaults are off
- **WHEN** `ape.properties` contains neither key
- **THEN** the resolved plan's `takeScreenshotForEveryStep()` SHALL be `false`
- **AND** its `saveGUITreeToXmlEveryStep()` SHALL be `false`
- **AND** no per-step PNG or XML SHALL be written

#### Scenario: debug re-enable
- **WHEN** `ape.properties` contains `ape.saveGUITreeToXmlEveryStep=true`
- **THEN** a `step-N.xml` SHALL be written each step, as before

#### Scenario: LLM screenshots unaffected
- **WHEN** `ape.takeScreenshotForEveryStep=false` and the LLM routes a step
- **THEN** `ScreenshotCapture` SHALL still capture its on-demand screenshot for the prompt

#### Scenario: new-state screenshot on by default
- **WHEN** `ape.properties` sets neither `ape.takeScreenshot` nor `ape.takeScreenshotForNewState`
- **THEN** the resolved plan's `takeScreenshotForNewState()` SHALL be `true`
- **AND** a new abstract state added to the model SHALL produce one PNG named after the state in the output directory
- **AND** a revisited state SHALL produce no PNG

---

### Requirement: Fuzz Gesture Emission

Every branch of the fuzzer's default gesture switch (drag, pinch/zoom, click) SHALL build exactly one gesture per invocation. Whether `generatePinchOrZoomEvent` appends its event is decided by the ADDONS member `APE_FIXES` (design D9): with `APE_FIXES` in the plan it SHALL end with `events.add(new ApePinchOrZoomEvent(points))`, so every branch appends exactly one event; without `APE_FIXES` (the `base` and `mop` cells) it SHALL build the points with the same draws and SHALL NOT append the event, as upstream APE, whose pinch/zoom gesture was built and discarded. The drag and click branches append their event in every cell. INV-EXPL-18: every fuzz gesture branch SHALL build exactly one gesture per invocation; with `APE_FIXES` every branch SHALL append that gesture's event, and without it only the pinch/zoom branch SHALL NOT append its event.

Before appending or discarding, `generatePinchOrZoomEvent` SHALL size the `points` array to exactly the number of entries it writes, `6 + (count << 1)`, and emit no `null` entry, in every cell: the size consumes no draw. The former allocation `new PointF[4 + count << 1]` evaluated by Java precedence to `(4 + count) << 1` = `8 + 2·count`, while only `6 + 2·count` entries are written — leaving two trailing `null` slots that the constructor would dereference. The `ApePinchOrZoomEvent` constructor SHALL validate the array length (reject arrays shorter than 6 entries — 1 count slot + 1 duration slot + 2 coordinates × (count+1) pointers with count ≥ 0 gives the minimum valid payload of 6) BEFORE dereferencing any element, so a malformed array is rejected rather than throwing `NullPointerException`.

#### Scenario: pinch/zoom branch emits
- **WHEN** the plan carries `APE_FIXES`
- **AND** the fuzzer's gesture switch selects the pinch/zoom branch
- **THEN** exactly one `ApePinchOrZoomEvent` SHALL be appended to the event list
- **AND** its points array SHALL have at least 6 entries

#### Scenario: emitted points array has no null entries
- **WHEN** the pinch/zoom branch builds its `points` array for any `count ≥ 0`, with or without `APE_FIXES`
- **THEN** the array SHALL have length equal to the number of written entries, `6 + (count << 1)`
- **AND** it SHALL contain no `null` element and, when passed to `ApePinchOrZoomEvent`, construction SHALL NOT throw `NullPointerException`

#### Scenario: short payload rejected
- **WHEN** an `ApePinchOrZoomEvent` is constructed with 5 points
- **THEN** the constructor SHALL reject it before dereferencing any element

#### Scenario: pinch/zoom branch without APE_FIXES appends nothing
- **WHEN** the `base` cell's fuzzer selects the pinch/zoom branch with a given `RandomHelper` state
- **THEN** the branch SHALL make the same `RandomHelper` draws it makes with `APE_FIXES`
- **AND** no event SHALL be appended to the event list

---

### Requirement: Off-Screen Action Handling

When the node overload `generateClickEventAt(Rect nodeRect, long waitTime, ClickPoint clickPoint)` obtains null visible bounds for a node action — the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null) — the outcome is decided by the ADDONS member `APE_FIXES` (design D7), at that overload only:

- **With `APE_FIXES` in the plan** (the ADDONS cells), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <rect>` line, where `<rect>` is the node rect handed to `generateClickEventAt`. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs).
- **Without `APE_FIXES`** (the `base` and `mop` cells), it SHALL log APE's `Error to fetch bounds.` warning, substitute `AndroidDevice.getDisplayBounds()` for the missing bounds, and compute the click point on the display bounds, so the default `ClickPoint.CENTER` click lands at the display centre, as upstream APE. No `[APE-RV] off-screen action dropped` line is emitted for that substitution.

In every cell the invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and logs `Invalid bounds: <bounds>` followed by the same `[APE-RV] off-screen action dropped: <rect>` line. Visit/coverage crediting of the action is unchanged on both paths (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

For coordinate-carrying actions without a resolved node (`MODEL_LLM_TAP`), the rect handed to
`generateClickEventAt` MUST be the minimal non-degenerate rect anchored at the decided pixel —
`(x, y, x+1, y+1)` — never the zero-area `(x, y, x, y)`: an empty rect is unconditionally dropped by
both guard branches, which silently disables the action (cmpm finding A1: 16,625/16,625 taps
dropped). The validity domain of a coordinate tap is the **physical display bounds**
(`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds: the coordinate is
decided from a full-display screenshot, and its legitimate targets include cross-window elements
(IME keyboards, dialogs) that lie outside the app root node (measured: 6/7 thumbkey gate taps and
~50% of cmpm v2 taps dropped on the root-node domain). A coordinate outside the display produces
the log line and no event in every cell (INV-EXPL-30): the display-bounds substitution of the path
without `APE_FIXES` lives in the 3-arg node overload, and the explicit-bounds overload
`generateClickEventAt(Rect, long, ClickPoint, Rect bounds)`, through which `MODEL_LLM_TAP` passes
`clipToDisplay(...)`, drops null bounds in every cell. Node actions keep the root-node visible-bounds
domain unchanged.

INV-EXPL-19: when the plan carries `APE_FIXES`, no touch event SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own bounds — or, for `MODEL_LLM_TAP`, other than the action's own decided coordinate. Without `APE_FIXES`, a node action whose bounds miss the visible screen SHALL be clicked on the display bounds, as upstream APE; a `MODEL_LLM_TAP` keeps INV-EXPL-30 in every cell.

#### Scenario: off-screen node produces no event
- **WHEN** the plan carries `APE_FIXES`
- **AND** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
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
  `(0, 0, 1080, 1920)` (x equals the exclusive right edge), with or without `APE_FIXES`
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: off-screen node without APE_FIXES clicks the display centre
- **WHEN** the `base` cell runs, the display bounds are `(0, 0, 1080, 1920)`, and a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** one `Error to fetch bounds.` warning SHALL be logged
- **AND** a touch down/up pair SHALL be enqueued at the display centre `(540, 960)`
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

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

When the plan carries `FOREIGN_ACTIVITY_GUARD` (key `ape.foreignActivityGuard`, a member of the ADDONS group) and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldModel(pkg, filterAccepts, systemWhitelist)` before calling `mAgent.updateState`, where `pkg` is `topComp.getPackageName()`, `filterAccepts` is `MonkeyUtils.getPackageFilter().isPackageValid(pkg)` (the same predicate the active backstop `checkAppActivity` gates on), and `systemWhitelist` is the fixed set {`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`} (the Google-image package is required because the RVSec AVD runs a Google emulator image whose runtime-permission UI is `com.google.android.permissioncontroller`; the AOSP entry alone would never match). `com.android.systemui` is NOT whitelisted — `checkAppActivity` already treats it as invalid and restarts on it, so the guard SHALL BACK out of it. When the decision is negative, the source SHALL enqueue exactly one BACK key event plus the standard throttle and return without invoking `updateState` — the foreign screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT contribute actions.

The decision SHALL be based exclusively on the component's package name (the task's applicationId), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing START/ACTIVATE handling proceeds). A null package SHALL be treated as modelable (uncheckable — defer to the existing paths). `MonkeySourceApe` SHALL read the feature from the plan (`spec().has(Feature.FOREIGN_ACTIVITY_GUARD)`) and SHALL NOT read `Config.foreignActivityGuard` (design D-B6, INV-GRP-06). When the plan does not carry it (ADDONS off, as in the `base` and `mop` cells, or the key false), event generation SHALL be identical to the pre-guard specification. Each deflection adds one to `RUN_END.counters.guardBacks`.

The first deflection of each distinct foreign package SHALL log `[APE-RV] Foreign activity: pkg=<pkg> -> BACK`; subsequent deflections of the same package SHALL NOT log (throttle — a persistent foreign screen must not spam the trace). The dead `checkPackage(ComponentName, AccessibilityNodeInfo)` helper SHALL be deleted (P3 — it has no callers and the guard supersedes its intent).

- **INV-EXPL-20**: No `State` or UI-coverage registration SHALL ever originate from a screen whose package fails the guard decision.
- **INV-EXPL-21**: Screens of the system-interaction whitelist packages SHALL NOT be backed out by the guard (the guard SHALL be a no-op for them). This is a guard-local guarantee only: it does not assert durable in-package modeling — `checkAppActivity`, which this change does not touch, may still restart over these packages on a later cycle.
- **INV-EXPL-22**: The plan feature `FOREIGN_ACTIVITY_GUARD` SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no BACK deflections, no guard log lines). This is a reasoning invariant over the flag placement, not a pure-seam matrix case — `shouldModel` has no flag parameter.

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
- **WHEN** the plan does not carry `FOREIGN_ACTIVITY_GUARD` (the `base` cell, or `ape.foreignActivityGuard=false` with ADDONS on) and a foreign screen reaches `generateEvents`
- **THEN** the screen SHALL be modeled exactly as before this change (leak-and-restart behavior), with no guard log line

---

### Requirement: Tree/Package Guard in Event Generation

When the plan carries `TREE_PACKAGE_GUARD` (key `ape.treePackageGuard`, a member of the ADDONS group) and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldRefetch(topPkg, treePkg, systemWhitelist)` before calling `mAgent.updateState`, where `topPkg` is `topComp.getPackageName()`, `treePkg` is `info.getPackageName()` (the accessibility root's owning package, converted null-safely to a String), and `systemWhitelist` is the `SYSTEM_INTERACTION_PACKAGES` set defined by `foreign-activity-guard` ({`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`}). `shouldRefetch` SHALL return true (mismatch) only when `treePkg` is non-null, differs from `topPkg`, and is not in the whitelist.

When `shouldRefetch` is true and at least one refetch iteration remains (`repeat > 0` inside the existing `while (repeat-- > 0)` loop), the source SHALL `continue` — re-fetching both `topComp` and `info` on the next loop iteration — and SHALL NOT invoke `updateState` on the current pair: the mismatched screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT be built into a `GUITree`. When `shouldRefetch` is true but the refetch iterations are exhausted (`repeat == 0`), the source SHALL fall through and model the pair exactly as today (fail-open) — the guard SHALL NOT deadlock capture on a persistent mismatch.

The decision SHALL be based exclusively on package names (`topComp.getPackageName()` and `info.getPackageName()`), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing null / START handling proceeds). A null tree package SHALL be treated as a match (uncheckable — defer to the existing paths, model normally). A tree owned by a whitelist package SHALL NOT be treated as a mismatch (a permission dialog legitimately owning the tree over the app is modeled, not re-fetched). `MonkeySourceApe` SHALL read the feature from the plan (`spec().has(Feature.TREE_PACKAGE_GUARD)`) and SHALL NOT read `Config.treePackageGuard` (design D-B6, INV-GRP-06). When the plan does not carry it (ADDONS off, or the key false), event generation SHALL be identical to the pre-guard specification.

The first mismatch of each distinct `top→tree` package pair SHALL log `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch`; subsequent mismatches of the same pair SHALL NOT log (throttle — a persistent mismatch across the refetch iterations must not spam the trace). The guard SHALL be inserted after `foreign-activity-guard`'s top-component foreign check and before `updateState`, and SHALL reuse that change's whitelist set rather than redefining it.

- **INV-EXPL-26**: While at least one refetch iteration remains, no `State`, `GUITree`, or UI-coverage registration SHALL originate from a `(topComp, tree)` pair whose tree package fails the guard decision (mismatched and not whitelisted).
- **INV-EXPL-27**: On refetch-iteration exhaustion the guard SHALL fail open — a still-mismatched pair SHALL be modeled exactly as the pre-guard path; the guard SHALL NOT prevent capture indefinitely. (Reasoning invariant over the loop-exhaustion branch, not a pure-seam matrix case — `shouldRefetch` does not see `repeat`.)
- **INV-EXPL-28**: The plan feature `TREE_PACKAGE_GUARD` SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no refetch `continue`, no guard log lines). (Reasoning invariant over the flag placement — `shouldRefetch` has no flag parameter.)

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
- **WHEN** the plan does not carry `TREE_PACKAGE_GUARD` (the `base` cell, or `ape.treePackageGuard=false` with ADDONS on) and a `(topComp, tree)` pair with a foreign tree reaches `generateEvents`
- **THEN** the pair SHALL be modeled exactly as before this change (unchecked pairing), with no refetch `continue` and no guard log line

---

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

### Requirement: Launcher Resolved at Relaunch

The app's launcher components are the activities that `LauncherQuery.query(pm, categories)` returns: the activities
the package manager resolves for `Intent.ACTION_MAIN` with each of the run's main categories (`LAUNCHER` and `MONKEY`
unless `-c` names others), restricted to the packages the package filter admits. Startup (`Monkey.getMainApps`) calls
this function once and keeps the result as the startup list, which is never overwritten. The run aborts with
`** No activities found to run, monkey aborted.` when the startup list is empty.

An app can change its own launcher during the run. `PackageManager.setComponentEnabledSetting` can enable one
`activity-alias` and disable another, and a component disabled this way no longer starts. A relaunch is any launch
of the app under test through `MonkeySourceApe.randomlyPickMainApp()` or the null-tree relaunch. The first covers
`startRandomMainApp` (null top activity, the bound of INV-EXPL-15, the blocked-package path) and the agent's
`EVENT_START`, `EVENT_RESTART` and `EVENT_CLEAN_RESTART` actions (`ApeAgent.getStartAction`). The second is
`restartPackage(…, "null tree")` (D-B16).

When the plan carries the ADDONS member `APE_FIXES`, each relaunch SHALL call `LauncherQuery.query` again and SHALL
pick its component from that result. The random pick draws `mRandom.nextInt(n)` once over the result, and the
null-tree relaunch takes its first element. If the query returns an empty list, the relaunch SHALL use the startup
list. Without `APE_FIXES` (the `base` and `mop` cells), a relaunch SHALL pick from the startup list and SHALL NOT
query the package manager, as in upstream APE. When the list a relaunch picks from differs from the startup list,
the engine SHALL log one line `[APE-RV] relaunch launcher <components>`. `MonkeySourceApe.getMainApp()` returns the
first element of the startup list in every cell. The `StatefulAgent` constructor uses it for the static-artifact
package and main-activity check.

One pure function makes the choice, `MonkeySourceApe.RelaunchTargets.of(spec, startup, query)`. With `APE_FIXES` it
returns `query.get()` when that list is non-empty and `startup` otherwise. Without `APE_FIXES` it returns `startup`
and does not call `query`. INV-EXPL-33: when the plan carries `APE_FIXES`, the component of every relaunch of the app
under test SHALL be taken from a launcher query made at that relaunch, unless that query returns nothing, in which
case it SHALL be taken from the startup list. Without `APE_FIXES`, every relaunch SHALL take its component from the
startup list and no relaunch SHALL query the package manager.

#### Scenario: the app switched its launcher alias

- **WHEN** the plan carries `APE_FIXES`
- **AND** the startup list is `[org.fossify.keyboard/.SplashActivity.Green]`
- **AND** at the relaunch the query returns `[org.fossify.keyboard/.SplashActivity.Red]`
- **THEN** the relaunch SHALL start `org.fossify.keyboard/.SplashActivity.Red`
- **AND** the startup list SHALL still be `[org.fossify.keyboard/.SplashActivity.Green]`
- **AND** one `[APE-RV] relaunch launcher` line naming `SplashActivity.Red` SHALL be emitted

#### Scenario: the query returns nothing

- **WHEN** the plan carries `APE_FIXES`
- **AND** the query at the relaunch returns an empty list
- **THEN** the relaunch SHALL pick from the startup list

#### Scenario: base keeps the startup launcher

- **WHEN** the `base` cell runs and the app has disabled the component of its startup list
- **THEN** every relaunch SHALL target that startup component
- **AND** the launcher query SHALL NOT be called after startup

#### Scenario: an unchanged launcher leaves the seeded picks unchanged

- **WHEN** the plan carries `APE_FIXES` and the query at each relaunch returns a list equal to the startup list
- **THEN** each `randomlyPickMainApp()` call SHALL draw `mRandom.nextInt(n)` once with the same `n`
- **AND** it SHALL return the same component as a pick from the startup list with the same seed

## Invariants (Dynamic Epsilon)

- **INV-EPS-01**: When `DYNAMIC_EPSILON` is in the plan and a `UICoverageTracker` is present, the effective epsilon SHALL always be in [`ape.minEpsilon`, `ape.maxEpsilon`] (`spec().exploration().minEpsilon()`/`maxEpsilon()`).
- **INV-EPS-02**: When `DYNAMIC_EPSILON` is not in the plan (`ape.addons` false, as in the `base` and `mop` cells, or `ape.dynamicEpsilon=false`), `egreedy()` SHALL use the constant `ape.defaultEpsilon`, as upstream APE.
- **INV-EPS-03**: When `UICoverageTracker` is null, `egreedy()` SHALL fall back to `ape.defaultEpsilon` (`spec().exploration().defaultEpsilon()`, default `0.05`).
