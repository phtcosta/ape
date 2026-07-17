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

- **INV-MODEL-02**: `ActionType.requireTarget()` MUST return `false` for the targetless model actions `MODEL_BACK`, `MODEL_MENU` and `MODEL_LLM_TAP`, and MUST return `true` for `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, and `MODEL_SCROLL_RIGHT_LEFT`. This contract is enforced by ordinal comparison: `requireTarget()` returns `true` if and only if `ordinal() >= MODEL_CLICK.ordinal() && ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`.

- **INV-MODEL-03**: `ActionType.isModelAction()` MUST return `true` for all **eight** `MODEL_*` values (`MODEL_BACK` through `MODEL_SCROLL_RIGHT_LEFT`, including `MODEL_LLM_TAP`) and MUST return `false` for all non-model values (`PHANTOM_CRASH`, `FUZZ`, `EVENT_*`).

- **INV-MODEL-04**: The `Graph` MUST NOT contain two distinct `State` objects with equal `StateKey`s. `Graph.getOrCreateState(StateKey)` MUST be the sole factory for `State` objects, and it MUST return the same object on repeated calls with equal keys.

- **INV-MODEL-05**: Every `StateTransition` MUST satisfy `transition.source == transition.action.getState()`. The `StateTransition` constructor enforces this with an `IllegalStateException`.

- **INV-MODEL-06**: Each `GUITree` MUST be appended to exactly one `State`. Once `GUITree.currentState` is set to a non-null `State`, it MUST NOT be changed except during `Model.rebuild()` when the state is removed and re-assigned under the updated `Naming`. During `Model.rebuild()`, the `GUITree.currentState` pointer is cleared to `null` before re-appending the tree to its new `State`, which allows `State.append()` to accept the tree again without triggering the duplicate-append guard (`RuntimeException("Cannot be appended twice.")`).

- **INV-MODEL-07**: `State.visitedCount` (inherited from `GraphElement`) MUST be incremented by exactly 1 each time `GraphElement.visitedAt(timestamp)` is called. It MUST equal the total number of calls to `visitedAt` on that element.

- **INV-MODEL-08**: `StateTransition.treeTransitions` MUST be non-empty after the transition is first created; `Graph.addTransition(...)` MUST append the `GUITreeTransition` to the list on every call, both for new and existing transitions.

- **INV-MODEL-09**: `Model.version` MUST be incremented by exactly 1 each time `Model.rebuild()` produces a structural change to the graph. If `rebuild()` is called but no states are affected, `version` MUST remain unchanged.

- **INV-MODEL-10**: `StateKey` equality MUST imply widget-set equality: two `StateKey`s are equal if and only if their `activity` strings are equal, their `naming` references are equal (same `Naming` object or equal by `Naming.equals()`), and their `widgets` arrays are equal by `Arrays.equals()`.

- **INV-MODEL-11**: `Model.rebuild()` SHALL be count-preserving for edge and activity counters: after any number of rebuilds, each edge's `visitedCount` equals the count implied by the replayed transition history, and each `ActivityNode`'s visit count equals its pre-rebuild (live-exploration) total. (Per-state source counters are excluded — see the deferred note above.)

- **INV-MODEL-12**: `ActionType.MODEL_LLM_TAP` MUST satisfy `requireTarget() == false` and `isModelAction() == true`. This is enforced by its ordinal position (`MODEL_BACK.ordinal() <= MODEL_MENU.ordinal() < MODEL_LLM_TAP.ordinal() < MODEL_CLICK.ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`), with no change to the bodies of `requireTarget()` or `isModelAction()`. An `LlmTapAction` MUST be constructable with `target = null` without error.

- **INV-MODEL-13**: `LlmTapAction` identity MUST include its `(pixelX, pixelY, longClick)` payload in addition to the inherited `(state, target = null, type)`. Two taps with identical payloads in the same state MUST be equal with equal `hashCode`; two taps whose coordinate or `longClick` flag differ MUST NOT be equal. Consequently each distinct off-tree tap owns its own `StateTransition` edges, and a differing outcome between two *different* coordinates MUST NOT be reported as non-determinism of a single action.

- **INV-MODEL-14**: An **ephemeral** action — one synthesized per decision and never a member of any `State.actions` (`isEphemeral()`, true exactly for `MODEL_LLM_TAP`) — MUST NOT be registered in the graph's unvisited/visited action inventory, and the graph's visit bookkeeping MUST accept it without error rather than treating it as a violated registration. Any **non**-ephemeral model action reaching that bookkeeping unregistered MUST still raise the existing sanity-check error. An ephemeral action MUST NOT trigger naming refinement: its outcome varies with its payload or with a non-deterministic surface, neither of which any abstraction can resolve.

- **INV-MODEL-15**: `Model.saveActionHistory` SHALL complete and produce `action-history.log` for every teardown, regardless of how many individual `ActionRecord`s fail to resolve. A resolution failure affects only its own record (the record is skipped); it MUST NOT abort the iteration, suppress other records, or propagate out of the method.

- **INV-MODEL-16**: The ephemeral-action quarantine SHALL survive a model rebuild. An ephemeral edge (a `StateTransition` whose action `isEphemeral()`) SHALL NOT be replayed by `Model.rebuild`: it is observational and does not survive the refinement that removed its states — its `GUITreeTransition`s are dropped from the replay set **and** from the graph's tree-transition history (so `rebuildHistory` cannot resurrect a dangling edge). A post-refinement re-anchor of an agent action reference (`Model.update(ModelAction, GUITreeAction)`) SHALL return an ephemeral action unchanged — its identity is its payload (INV-MODEL-13), not `State.getActions()` membership, so a membership lookup is a category error, not a recoverable miss. Neither path SHALL throw for an ephemeral action; non-ephemeral behavior is unchanged.

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

### Requirement: Tolerant Action-History Persistence

`Model.saveActionHistory` SHALL resolve and write each `ActionRecord` inside a per-record guard.
When `ActionRecord.resolveModelAction()` throws (stale pre-refinement descriptor failing
`GUITree.pickNodes`, or a record whose `guiAction` is null), the record SHALL be skipped: the
failure is logged as a warning containing the action's descriptor and the exception message (no
process abort, no partial file), and a skipped-record counter increments. After the iteration the
method SHALL log a summary line under an `[APE-RV]` tag reporting `total=<records>
skipped=<failures>`, so experiment tooling can quantify how much history was lost to refinements.
Records that resolve normally SHALL be written exactly as before (`ApeRRFormatter.startLogAction` /
`endLogAction`), preserving the replay-log format consumed by `ReplayAgent`.

I/O failures keep their existing contract: an `IOException` opening or writing the file is caught,
logged, and never propagated.

#### Scenario: stale record is skipped, file still written

- **WHEN** teardown runs `saveActionHistory` over a history of 60 records
- **AND** record 60 was taken under a naming that was later refined, so its descriptor no longer resolves in its `GUITree` (`Cannot find widget`)
- **THEN** records 1–59 SHALL be written to `action-history.log` in order
- **AND** record 60 SHALL be skipped with a warning naming its action descriptor
- **AND** the summary line SHALL report `total=60 skipped=1`
- **AND** no exception SHALL propagate out of `saveActionHistory`

#### Scenario: fully resolvable history is written unchanged

- **WHEN** teardown runs `saveActionHistory` and every record resolves
- **THEN** the produced `action-history.log` SHALL be byte-identical to the pre-change format
- **AND** the summary line SHALL report `skipped=0`

#### Scenario: null guiAction is a skipped record, not an abort

- **WHEN** a model-action record carries `guiAction == null`
- **THEN** that record SHALL be skipped with a warning
- **AND** all remaining records SHALL still be processed

---

### Requirement: requireTarget Contract for All Action Types

`ActionType.requireTarget()` MUST return a value consistent with whether the action operates on a specific widget, and this contract MUST be enforced uniformly across all call sites that dispatch actions. `requireTarget()` returns `true` if and only if `ordinal() >= MODEL_CLICK.ordinal() && ordinal() <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`. The targetless model actions `MODEL_BACK`, `MODEL_MENU` and `MODEL_LLM_TAP` sit below `MODEL_CLICK` in ordinal order and therefore return `false`.

#### Scenario: widget actions require a target
- **WHEN** `actionType.requireTarget()` is called for any of: `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`
- **THEN** the method MUST return `true`

#### Scenario: global actions do not require a target
- **WHEN** `actionType.requireTarget()` is called for `MODEL_BACK`
- **THEN** the method MUST return `false`
- **AND** a `ModelAction` of type `MODEL_BACK` MUST be constructable with `target = null` without any error

#### Scenario: MODEL_LLM_TAP is a targetless model action
- **WHEN** `actionType.requireTarget()` is called for `MODEL_LLM_TAP`
- **THEN** it MUST return `false`
- **AND** `ActionType.MODEL_LLM_TAP.isModelAction()` MUST return `true`
- **AND** an `LlmTapAction` MUST be constructable with `target = null` and a pixel coordinate without any error

#### Scenario: non-model action types do not require a target
- **WHEN** `actionType.requireTarget()` is called for any of: `PHANTOM_CRASH`, `FUZZ`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `EVENT_NOP`, `EVENT_ACTIVATE`, `EVENT_TRIGGER_ACTIVITY`
- **THEN** the method MUST return `false`

---

### Requirement: LLM Coordinate Tap Action

The model MUST provide `LlmTapAction`, a subclass of `ModelAction` with `actionType = MODEL_LLM_TAP` and `target = null`, carrying an off-tree tap payload: an integer pixel coordinate `(pixelX, pixelY)` and a `longClick` flag. It is constructed only by `LlmRouter` for the off-tree case and returned directly to the agent; it is NOT a member of any `State.actions` array.

Because `MODEL_LLM_TAP.isModelAction()` is `true`, the agent-side action resolution path reads the action's resolved `GUITreeAction` and asserts it is non-null. A synthesized `LlmTapAction`, being absent from `State.actions`, is not resolved by the normal per-state resolution pass. Therefore the agent MUST resolve the synthesized tap against the current state before it is dispatched — populating its `GUITreeAction` via the targetless resolution branch (`target = null`), exactly as `MODEL_MENU` and `MODEL_BACK` are resolved. A widget action returned on the same path is already resolved and MUST NOT be re-resolved.

When executed, the tap dispatches a raw tap at `(pixelX, pixelY)` (long press when `longClick`), and the model records a `StateTransition` from the source state to the observed destination state, keyed on `(source, action, targetState)` per the existing transition machinery.

`LlmTapAction` identity extends the inherited `(state, target = null, type)` with its `(pixelX, pixelY, longClick)` payload (INV-MODEL-13), and `ModelAction.equals` guards `getClass() != obj.getClass()`, so an `LlmTapAction` is never equal to any `MODEL_MENU`, `MODEL_BACK`, or widget action. Two taps at different coordinates are different actions and own their edges separately; repeating the identical tap in the same state reuses that action's edge.

The synthesized tap is **ephemeral** (INV-MODEL-14): it is dispatched once and recorded as an observational edge, and never re-enters exploration. Because it is absent from `State.actions` it is never validated, so it remains invalid and no action filter admits it — it can be neither selected nor traversed as part of a path.

#### Scenario: LlmTapAction is a targetless model action

- **WHEN** `new LlmTapAction(state, 600, 900, false)` is constructed
- **THEN** `action.getType()` MUST equal `ActionType.MODEL_LLM_TAP`
- **AND** `action.getTarget()` MUST be `null`
- **AND** `action.requireTarget()` MUST return `false`
- **AND** `action.getPixelX()` MUST equal `600` AND `action.getPixelY()` MUST equal `900` AND `action.isLongClick()` MUST be `false`

#### Scenario: Synthesized tap is resolved before dispatch

- **WHEN** the agent receives a synthesized `LlmTapAction` from the LLM routing path
- **THEN** the agent MUST resolve it against the current state before dispatch, so its resolved `GUITreeAction` is non-null
- **AND** the action MUST pass through agent-side action resolution (which asserts a non-null `GUITreeAction` for `isModelAction() == true` actions) without error
- **AND** a widget action received on the same path MUST NOT be re-resolved

#### Scenario: Taps at different coordinates are different actions

- **WHEN** two `LlmTapAction`s in the same source state carry different coordinates, or the same coordinate with a differing `longClick` flag
- **THEN** they MUST NOT be equal
- **AND** each MUST key its own `StateTransition` edges, so a differing destination between them MUST NOT be recorded as one action with two outcomes

#### Scenario: Repeating the identical tap reuses its edge

- **WHEN** an `LlmTapAction` with the same `(state, pixelX, pixelY, longClick)` is dispatched twice and both times reaches the same destination state
- **THEN** the two actions MUST be equal with equal `hashCode`
- **AND** the model MUST record a single `StateTransition` for that `(source, action, targetState)` triple, with `hittingCount` incremented on the second traversal

#### Scenario: The ephemeral tap is accepted by graph visit bookkeeping

- **WHEN** the graph marks a synthesized `LlmTapAction` visited, at selection and again when its edge is recorded
- **THEN** it MUST NOT raise the unregistered-action sanity-check error
- **AND** the graph's unvisited and visited action inventories MUST remain unchanged
- **AND** a **non**-ephemeral model action that is not registered MUST still raise that error

#### Scenario: The ephemeral tap does not trigger naming refinement

- **WHEN** a `StateTransition` whose action is ephemeral is reported to non-determinism resolution
- **THEN** the model MUST be left unrefined and unrebuilt

---

### Requirement: Model Rebuild After Naming Refinement

When the `NamingFactory` updates the `Naming` for one or more `GUITree`s (because non-determinism was detected or action refinement was requested), the `Model` MUST rebuild all affected `State`s and `StateTransition`s so that the graph remains consistent with the updated abstraction.

Rebuild MUST be count-preserving for edge and activity counters (INV-MODEL-11): replaying the transition history re-establishes edge visit counts exclusively through the same `markVisited` path used during live exploration. `Graph.rebuildHistory()` SHALL NOT apply any additional `visitedCount` increment of its own. `ActivityNode` visit counters SHALL be preserved across rebuild — the replay SHALL NOT re-mark activities. An activity's visit count is invariant under state re-abstraction (a `GUITree`'s activity name does not change when its abstract `State` does) and survives `ActivityNode.removeState` (which detaches states but leaves the counter untouched), so it is already complete before replay; re-marking the partial set of replayed sources would double-count activities that also have non-replayed transitions. Two consecutive rebuilds of the same history therefore yield identical edge and activity visit counts. (Previously each rebuild double-incremented every edge and re-added activity visits on top of the preserved totals, so every naming refinement inflated the counters that `greedyPickLeastVisited`, saturation, and trivial-activity detection consume — refined screens looked artificially "hot" and were deprioritized.)

Note (deferred): the replay still re-marks *surviving source `State`s* of removed transitions via `markVisited(State)`, so per-state visit counts are not yet rebuild-idempotent. This is a separate, lower-impact concern than the activity/edge inflation fixed here and is tracked independently; INV-MODEL-11 is scoped to edge and activity counters accordingly.

#### Scenario: state removed and re-inserted after naming change
- **WHEN** `Model.rebuild()` is called and for at least one `State S`, at least one `GUITree` in `S.treeHistory` now maps to a different `Naming` than `S.stateKey.naming`
- **THEN** `S` MUST be removed from `Graph.states` and `Graph.transitions` before re-insertion
- **AND** every `GUITree` previously in `S.treeHistory` MUST be re-processed via `GUITreeBuilder` to obtain a new `StateKey` under the updated `Naming`
- **AND** all `GUITreeTransition`s that referenced the removed `StateTransition`s MUST be re-inserted in timestamp order
- **AND** `Model.version` MUST be incremented by 1

#### Scenario: unaffected states survive rebuild unchanged
- **WHEN** `Model.rebuild()` is called and a `State S` has no `GUITree` whose `Naming` has changed
- **THEN** `S` MUST remain in `Graph.states` with its `StateKey`, `actions`, and `treeHistory` unchanged

#### Scenario: rebuild preserves edge visit counts
- **WHEN** an edge was traversed 3 times during live exploration and `Model.rebuild()` runs twice
- **THEN** after both rebuilds the rebuilt edge's `visitedCount` SHALL equal 3

#### Scenario: rebuild preserves activity visit counts
- **WHEN** an `ActivityNode` accumulated 10 visits during live exploration and `Model.rebuild()` runs
- **THEN** after the rebuild the `ActivityNode`'s visit count SHALL equal 10 (preserved across rebuild; the replay does not re-mark activities)

---

### Requirement: Ephemeral Quarantine Through Rebuild

`Model.rebuild` SHALL exclude ephemeral-action edges when it collects the removed states'
`GUITreeTransition`s for replay (INV-MODEL-16). The excluded transitions SHALL also be removed
from the graph's tree-transition history so the post-replay `rebuildHistory` pass — which
reconstructs the state-transition history from the tree-transition history — cannot re-insert a
`StateTransition` that no longer exists in the graph (a dangling edge would poison
history-based path reconstruction, e.g. `fillTransitionsByHistory`). The number of dropped
ephemeral transitions SHALL be logged under an `[APE-RV]` tag. `StatefulAgent.updateModel`'s
re-anchoring of `currentAction`/`lastAction`/`newAction` (via
`Model.update(ModelAction, GUITreeAction)`) SHALL leave an ephemeral action reference unchanged
instead of re-anchoring it by state membership.

#### Scenario: refinement removes a state carrying an ephemeral tap edge

- **WHEN** a naming refinement removes a state whose collected in/out edges include an ephemeral
  `MODEL_LLM_TAP` edge alongside non-ephemeral edges
- **THEN** `Model.rebuild` SHALL replay every non-ephemeral `GUITreeTransition` exactly as before
- **AND** the ephemeral edge's `GUITreeTransition`s SHALL NOT be replayed and SHALL be removed
  from the tree-transition history
- **AND** the rebuild SHALL complete (`Rebuilding model finished`) without throwing
  `No such action [MODEL_LLM_TAP]`

#### Scenario: post-rebuild history contains no dangling ephemeral edge

- **WHEN** a rebuild dropped an ephemeral edge
- **THEN** the rebuilt state-transition history SHALL NOT contain the removed ephemeral
  `StateTransition`

#### Scenario: agent reference to the ephemeral tap survives a rebuild

- **WHEN** the agent's `lastAction` (or `currentAction`/`newAction`) is an ephemeral tap whose
  state was removed by a rebuild
- **THEN** `updateModel` SHALL NOT throw and SHALL keep the ephemeral reference as-is
  (payload-bound), re-anchoring only the non-ephemeral references

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
