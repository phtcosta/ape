# Specification: Exploration Model

## Purpose

APE explores Android applications by building an explicit directed graph of abstract UI states and the transitions between them. Without such a graph, a random testing tool like Monkey has no memory of what it has already seen: it cannot prefer unexplored widgets, cannot detect when the same screen is reached by two different paths, and cannot recover from dead-end states in a principled way. The exploration model exists to give the testing agent a persistent, queryable map of the app under test.

The model encodes three levels of abstraction. At the finest level, a `GUITree` is one concrete screen captured at a specific moment from the Android Accessibility API. One level up, a `State` is the equivalence class of all `GUITree`s that are considered identical under the current `Naming` function; the `Naming` maps a `GUITree` to a sorted array of abstract widget descriptors (`Name[]`) together with the foreground activity class name, producing a `StateKey`. Two trees that produce the same `StateKey` are merged into the same `State`. At the coarsest level, an `ActivityNode` groups all `State`s whose `StateKey.activity` is the same Android activity class name; it is used for activity-level coverage statistics.

A `StateTransition` is a labelled directed edge `(source: State, action: ModelAction, target: State)` recording that executing `action` while in `source` produced `target`. Each edge carries a visit count so the agent can quantify how reliably a transition has been observed. Non-determinism — the same action from the same source reaching different targets on different executions — is detected by the model and triggers the `NamingFactory` refinement algorithm, which splits over-merged states.

The model enables three agent capabilities: (1) exhaustive coverage — the agent selects actions from the set of still-unvisited `ModelAction`s in the current state; (2) directed navigation — `Graph` BFS/DFS utilities find shortest paths through the known state space to reach under-explored states; (3) abstraction refinement — the model rebuilds itself after the `NamingFactory` raises the abstraction level, re-mapping previously observed `GUITree`s to their new `State` assignments.

## Data Contracts

### Input

- `activity: ComponentName` — Android foreground activity, supplied by `AndroidDevice` via `AccessibilityService`
- `rootInfo: AccessibilityNodeInfo` — root of the current view hierarchy, captured by `GUITreeBuilder` from the Accessibility API
- `bitmap: Bitmap` — screenshot at the time of capture (may be `null` when screenshot is disabled); stored in `GUITree` for replay and debugging
- `naming: Naming` — current abstraction function, managed by `NamingManager`; determines how `GUITree` nodes are grouped into `Name` descriptors

### Output

- `State` — the abstract state assigned to the current `GUITree`; returned by `Model.getState(GUITree)`
- `StateTransition` — the recorded edge; returned by `Model.addTransition(source, action, target, ...)`
- `sataModel.obj` — Java-serialized `Model` object written to `graphOutputDir` on normal termination when `Config.saveObjModel = true`
- `sataGraph.dot` — Graphviz DOT representation of the exploration graph written on termination when `Config.saveDotGraph = true`
- `sataGraph.vis.js` — vis.js JSON representation for browser visualization written on termination when `Config.saveVisGraph = true`

### Side-Effects

- **[Graph mutation]**: `Model.getState(GUITree)` creates a new `State` in `Graph.states` if no state with the computed `StateKey` exists, then appends the `GUITree` to the state's `treeHistory`.
- **[Graph mutation]**: `Graph.addTransition(source, action, target, ...)` inserts a new `StateTransition` or updates the `visitedCount` of an existing one.
- **[Graph mutation]**: `Model.rebuild()` removes stale `State`s and `StateTransition`s from the graph and re-inserts them under the updated `Naming`, incrementing `Model.version`.
- **[Graph listener]**: `Graph` fires `GraphListener` callbacks when states or transitions are added or removed; `SataAgent` subscribes to maintain its own priority queues.
- **[Filesystem]**: `StatefulAgent.tearDown()` writes serialized model artifacts (`sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js`) under `graphOutputDir` using `ObjectOutputStream` and `PrintWriter`.

### Error

- `IllegalStateException` — thrown by `StateTransition` constructor when `source` does not equal `action.getState()`, ensuring action-state consistency.
- `IllegalStateException` — thrown by `State.getAction(Name, ActionType)` when the requested widget is not present in the state's `StateKey`, preventing stale action references.
- `RuntimeException("Cannot be appended twice.")` — thrown by `State.append(GUITree)` when the same `GUITree` is appended to a `State` twice, enforcing bijection between `GUITree` and `State`.
- `NullPointerException` — thrown by `Model.rebuild()` when a rebuilt `GUITree` has no assigned `State`, detecting incomplete rebuild logic.
- `RuntimeException` — thrown by `ModelAction.resolveAt(...)` when `requireTarget() = true` and the resolved node array is empty, indicating that the action target has disappeared from the current screen.

## Invariants

- **INV-MODEL-01**: Every `State` MUST contain exactly one `ModelAction` with `actionType = MODEL_BACK` and `target = null`; this is the `State.backAction` field. It MUST be present even for states where the system back button has no visible effect.

- **INV-MODEL-02**: `ActionType.requireTarget()` MUST return `false` for `MODEL_BACK` and MUST return `true` for `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, and `MODEL_SCROLL_RIGHT_LEFT`. This contract is enforced by ordinal comparison: `requireTarget()` returns `true` if and only if `ordinal() >= MODEL_CLICK.ordinal() && ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`.

- **INV-MODEL-03**: `ActionType.isModelAction()` MUST return `true` for all seven `MODEL_*` values (`MODEL_BACK` through `MODEL_SCROLL_RIGHT_LEFT`) and MUST return `false` for all non-model values (`PHANTOM_CRASH`, `FUZZ`, `EVENT_*`).

- **INV-MODEL-04**: The `Graph` MUST NOT contain two distinct `State` objects with equal `StateKey`s. `Graph.getOrCreateState(StateKey)` MUST be the sole factory for `State` objects, and it MUST return the same object on repeated calls with equal keys.

- **INV-MODEL-05**: Every `StateTransition` MUST satisfy `transition.source == transition.action.getState()`. The `StateTransition` constructor enforces this with an `IllegalStateException`.

- **INV-MODEL-06**: Each `GUITree` MUST be appended to exactly one `State`. Once `GUITree.currentState` is set to a non-null `State`, it MUST NOT be changed except during `Model.rebuild()` when the state is removed and re-assigned under the updated `Naming`. During `Model.rebuild()`, the `GUITree.currentState` pointer is cleared to `null` before re-appending the tree to its new `State`, which allows `State.append()` to accept the tree again without triggering the duplicate-append guard (`RuntimeException("Cannot be appended twice.")`).

- **INV-MODEL-07**: `State.visitedCount` (inherited from `GraphElement`) MUST be incremented by exactly 1 each time `GraphElement.visitedAt(timestamp)` is called. It MUST equal the total number of calls to `visitedAt` on that element.

- **INV-MODEL-08**: `StateTransition.treeTransitions` MUST be non-empty after the transition is first created; `Graph.addTransition(...)` MUST append the `GUITreeTransition` to the list on every call, both for new and existing transitions.

- **INV-MODEL-09**: `Model.version` MUST be incremented by exactly 1 each time `Model.rebuild()` produces a structural change to the graph. If `rebuild()` is called but no states are affected, `version` MUST remain unchanged.

- **INV-MODEL-10**: `StateKey` equality MUST imply widget-set equality: two `StateKey`s are equal if and only if their `activity` strings are equal, their `naming` references are equal (same `Naming` object or equal by `Naming.equals()`), and their `widgets` arrays are equal by `Arrays.equals()`.

## Requirements

### Requirement: State Creation on Novel Abstract State

When the model encounters a `GUITree` whose `StateKey` has not been seen before, it MUST allocate a new `State`, populate its action set from the `StateKey.widgets`, add the `MODEL_BACK` action unconditionally, and register the state in the `Graph`.

#### Scenario: first visit to a new screen
- **WHEN** `Model.getState(GUITree tree)` is called and `GUITreeBuilder.getStateKey(naming, tree)` returns a `StateKey` `K` for which `Graph.states` contains no entry
- **THEN** `Graph.getOrCreateState(K)` MUST create a new `State` object and insert it into `Graph.states` keyed by `K`
- **AND** the new `State.actions` array MUST contain one `ModelAction` per `(widget, actionType)` pair decoded from `K.widgets` via `NamerFactory.decodeActions(widget)`, plus exactly one `ModelAction` with `actionType = MODEL_BACK` and `target = null`
- **AND** the returned `State` MUST have `visitedCount = 0` before `visitedAt` is first called on it

#### Scenario: state action array is immutable after creation
- **WHEN** a `State` is created from `StateKey K`
- **THEN** the `actions` array MUST NOT be modified after construction; `State.getActions()` MUST return an unmodifiable view via `Arrays.asList(actions)` backed by the same array throughout the state's lifetime

---

### Requirement: State Reuse on Revisit

When the model encounters a `GUITree` whose computed `StateKey` already maps to an existing `State`, it MUST return that existing `State` and append the `GUITree` to the state's history without creating a duplicate.

#### Scenario: second visit to a previously seen screen
- **WHEN** `Model.getState(GUITree tree)` is called and `GUITreeBuilder.getStateKey(naming, tree)` returns a `StateKey` `K` that is already present in `Graph.states`
- **THEN** `Graph.getOrCreateState(K)` MUST return the previously stored `State` object, not a new one
- **AND** `State.append(tree)` MUST add `tree` to `State.treeHistory` and call `tree.setCurrentState(this)`
- **AND** the `State.actions` array MUST remain identical to the one created at the state's first visit

#### Scenario: idempotency of getState
- **WHEN** `Model.getState(tree)` is called twice with `GUITree` objects `t1` and `t2` that both produce the same `StateKey K`
- **THEN** both calls MUST return the same `State` instance (`result1 == result2`)
- **AND** `Graph.states.size()` MUST increase by 1 after the first call and by 0 after the second call

---

### Requirement: Visit Count Increment on State Visit

Each logical visit to a `State` or graph element MUST be recorded by incrementing `visitedCount` via `GraphElement.visitedAt(timestamp)`.

#### Scenario: visiting a state for the first time
- **WHEN** `GraphElement.visitedAt(timestamp)` is called on a `State` for the first time with a valid `timestamp > 0`
- **THEN** `State.visitedCount` MUST become 1
- **AND** `State.firstVisitTimestamp` MUST be set to `timestamp`
- **AND** `State.lastVisitTimestamp` MUST be set to `timestamp`

#### Scenario: visiting a state for the nth time
- **WHEN** `GraphElement.visitedAt(timestamp2)` is called on a `State` whose `visitedCount` is already `n >= 1`
- **THEN** `State.visitedCount` MUST become `n + 1`
- **AND** `State.firstVisitTimestamp` MUST remain unchanged
- **AND** `State.lastVisitTimestamp` MUST be updated to `timestamp2`

---

### Requirement: Transition Creation on First Traversal

When an action taken in a source state leads to a target state and no `StateTransition` for `(source, action, target)` exists yet, the model MUST create one and record the associated `GUITreeTransition`.

#### Scenario: new transition between two states
- **WHEN** `Graph.addTransition(source, action, target, sourceTree, treeAction, targetTree)` is called and no `StateTransition` with equal `(source, action, target)` exists
- **THEN** a new `StateTransition` MUST be created with `transition.source = source`, `transition.action = action`, `transition.target = target`
- **AND** the new `StateTransition` MUST be inserted into `Graph.transitions`
- **AND** `transition.treeTransitions` MUST contain exactly one `GUITreeTransition` referencing `(sourceTree, treeAction, targetTree)`
- **AND** `transition.type` MUST be set to `StateTransitionVisitType.NEW_ACTION` if no prior transition from `source` via `action` existed, or `NEW_ACTION_TARGET` if a prior transition via `action` to a different target existed

#### Scenario: self-loop transition
- **WHEN** `Graph.addTransition(source, action, source, ...)` is called (source equals target)
- **THEN** a `StateTransition` MUST be created with `isCircle() = true`
- **AND** the transition MUST be stored and counted like any other transition

---

### Requirement: Transition Visit Count Increment on Repeat Traversal

When the same `(source, action, target)` triple is observed again, the existing `StateTransition` MUST be reused and its visit record extended.

#### Scenario: repeat traversal of an existing transition
- **WHEN** `Graph.addTransition(source, action, target, sourceTree, treeAction, targetTree)` is called and a `StateTransition` with equal `(source, action, target)` already exists with `treeTransitions.size() = n`
- **THEN** the method MUST return the existing `StateTransition`, not create a new one
- **AND** `transition.treeTransitions.size()` MUST become `n + 1`
- **AND** `transition.type` MUST be set to `StateTransitionVisitType.EXISTING`

---

### Requirement: Back Action Type Contract

Every `State` MUST have a designated back action of type `MODEL_BACK` with no widget target, and `ActionType.requireTarget()` MUST return `false` for `MODEL_BACK`.

#### Scenario: back action present and correctly typed
- **WHEN** a `State` is constructed with any `StateKey`
- **THEN** `State.getBackAction()` MUST return a non-null `ModelAction`
- **AND** `state.getBackAction().getType()` MUST equal `ActionType.MODEL_BACK`
- **AND** `state.getBackAction().getTarget()` MUST be `null`
- **AND** `ActionType.MODEL_BACK.requireTarget()` MUST return `false`

#### Scenario: back action executable without resolution target
- **WHEN** `State.resolveAction(agent, backAction, throttle)` is called with `action = state.getBackAction()`
- **THEN** `action.requireTarget()` MUST return `false`
- **AND** `action.resolveAt(timestamp, throttle, tree, null, null)` MUST complete without throwing an exception

---

### Requirement: Model Serialization on Normal Termination

On normal process termination, the `Model` object MUST be serialized to the filesystem when `Config.saveObjModel = true`, preserving the full exploration graph for offline analysis and replay.

#### Scenario: serialization on graceful shutdown
- **WHEN** `StatefulAgent.tearDown()` is called during normal termination and `Config.saveObjModel = true`
- **THEN** `StatefulAgent` MUST write the `Model` object to a file named `sataModel.obj` under `graphOutputDir` using `java.io.ObjectOutputStream`
- **AND** the file MUST be written before the method returns
- **AND** the serialized bytes MUST be deserializable back into a `Model` instance via `java.io.ObjectInputStream`

#### Scenario: visualization output on graceful shutdown
- **WHEN** `StatefulAgent.tearDown()` is called and `Config.saveDotGraph = true`
- **THEN** `StatefulAgent` MUST write a Graphviz DOT file named `sataGraph.dot` under `graphOutputDir`

#### Scenario: no serialization on disabled flag
- **WHEN** `StatefulAgent.tearDown()` is called and `Config.saveObjModel = false`
- **THEN** no `sataModel.obj` file MUST be written

---

### Requirement: requireTarget Contract for All Action Types

`ActionType.requireTarget()` MUST return a value consistent with whether the action operates on a specific widget, and this contract MUST be enforced uniformly across all call sites that dispatch actions.

#### Scenario: widget actions require a target
- **WHEN** `actionType.requireTarget()` is called for any of: `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`
- **THEN** the method MUST return `true`

#### Scenario: global actions do not require a target
- **WHEN** `actionType.requireTarget()` is called for `MODEL_BACK`
- **THEN** the method MUST return `false`
- **AND** a `ModelAction` of type `MODEL_BACK` MUST be constructable with `target = null` without any error

#### Scenario: non-model action types do not require a target
- **WHEN** `actionType.requireTarget()` is called for any of: `PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`
- **THEN** the method MUST return `false`

---

### Requirement: Model Rebuild After Naming Refinement

When the `NamingFactory` updates the `Naming` for one or more `GUITree`s (because non-determinism was detected or action refinement was requested), the `Model` MUST rebuild all affected `State`s and `StateTransition`s so that the graph remains consistent with the updated abstraction.

#### Scenario: state removed and re-inserted after naming change
- **WHEN** `Model.rebuild()` is called and for at least one `State S`, at least one `GUITree` in `S.treeHistory` now maps to a different `Naming` than `S.stateKey.naming`
- **THEN** `S` MUST be removed from `Graph.states` and `Graph.transitions` before re-insertion
- **AND** every `GUITree` previously in `S.treeHistory` MUST be re-processed via `GUITreeBuilder` to obtain a new `StateKey` under the updated `Naming`
- **AND** all `GUITreeTransition`s that referenced the removed `StateTransition`s MUST be re-inserted in timestamp order
- **AND** `Model.version` MUST be incremented by 1

#### Scenario: unaffected states survive rebuild unchanged
- **WHEN** `Model.rebuild()` is called and a `State S` has no `GUITree` whose `Naming` has changed
- **THEN** `S` MUST remain in `Graph.states` with its `StateKey`, `actions`, and `treeHistory` unchanged

---

### Requirement: Action Saturation Tracking

`ModelAction` MUST track whether it has been sufficiently explored (saturated), so the agent can prioritize unsaturated actions and eventually declare a state fully covered.

#### Scenario: single-target action saturation
- **WHEN** a `ModelAction` with `requireTarget() = true` is resolved and `resolvedNodes.length = 1`, and the action has been visited at least once (`isVisited() = true`)
- **THEN** `action.isSaturated()` MUST return `true`
- **AND** `action.getResolvedSaturation()` MUST return `1.0`

#### Scenario: multi-target action saturation threshold
- **WHEN** a `ModelAction` with `requireTarget() = true` is resolved and `resolvedNodes.length = k` where `k >= 2`, and `action.visitedCount >= min(k, 2)` (the `saturatedVisitedThreshold` constant = 2)
- **THEN** `action.isSaturated()` MUST return `true`
- **AND** `action.getResolvedSaturation()` MUST return a value `>= 1.0`, clamped to `1.0`

#### Scenario: global action saturation
- **WHEN** a `ModelAction` with `requireTarget() = false` (i.e., `MODEL_BACK`) has been visited at least once
- **THEN** `action.isSaturated()` MUST return `true`, because `isSaturated()` delegates to `isVisited()` for non-target actions

#### Scenario: state saturation
- **WHEN** every `ModelAction` in `State.actions` that passes `ActionFilter.ENABLED_VALID` has `action.isSaturated() = true`
- **THEN** `State.isSaturated()` MUST return `true`
