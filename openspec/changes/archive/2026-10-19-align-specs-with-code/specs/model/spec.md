## Data Contracts

### Side-Effects

- **[Graph mutation]**: `Model.getState(GUITree)` creates a new `State` in `Graph.states` if no state with the computed `StateKey` exists, then appends the `GUITree` to the state's `treeHistory`.
- **[Graph mutation]**: `Graph.addTransition(source, action, target, ...)` inserts a new `StateTransition` or updates the `visitedCount` of an existing one.
- **[Graph mutation]**: `Model.rebuild()` removes stale `State`s and `StateTransition`s from the graph and re-inserts them under the updated `Naming`, incrementing `Model.version`.
- **[Graph listener]**: `Graph` fires `GraphListener` callbacks when states or transitions are added or removed; `StatefulAgent` implements `GraphListener` and registers itself with `graph.addListener(this)`, so `SataAgent` (a `StatefulAgent`) receives them to maintain its own priority queues.
- **[Filesystem]**: none. `StatefulAgent.tearDown()` writes no model artifact: the serialization protocol (`sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js`) is deleted, no `action-history.log` is written, and the model exists only for the lifetime of the process (R1/R3 — clean runs, no read-back).

### Error

- `IllegalStateException` — thrown by `StateTransition` constructor when `source` does not equal `action.getState()`, ensuring action-state consistency.
- `IllegalStateException` — thrown by `State.getAction(Name, ActionType)` when the requested widget is not present in the state's `StateKey`, preventing stale action references.
- `RuntimeException("Cannot be appended twice.")` — thrown by `State.append(GUITree)` when the same `GUITree` is appended to a `State` twice, enforcing bijection between `GUITree` and `State`.
- `NullPointerException` — thrown by `Model.rebuild()` when a rebuilt `GUITree` has no assigned `State`, detecting incomplete rebuild logic.
- `IllegalStateException("Fail to resolve a node for this action: …")` — thrown by `ModelAction.resolveAt(...)` when `requireTarget() = true` and the resolved node array is empty, indicating that the action target has disappeared from the current screen.

## Invariants

- **INV-MODEL-03**: `ActionType.isModelAction()` MUST return `true` for all **nine** `MODEL_*` values (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`, `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT` — the ordinal range `MODEL_BACK..MODEL_SCROLL_RIGHT_LEFT`) and MUST return `false` for all non-model values (`PHANTOM_CRASH`, `FUZZ`, `EVENT_*`).

- **INV-MODEL-15** (retired): `Model.saveActionHistory` and `action-history.log` no longer exist; teardown writes no action history (the NDJSON `StepRecord` carries the per-step record), so there is no history save whose completion could be required.

- **INV-MODEL-18**: No `Model.ActionRecord` in `Model.actionHistory` SHALL hold a reference to an `Action`, `GUITreeAction`, `GUITree` or `GUITreeNode`: a record holds primitives and strings only (`clockTimestamp`, `agentTimestamp`, `actionType`, `stateId`, `targetXPath`, `treeId`, `treeTimestamp`, `throttle`), captured in `Model.appendToActionHistory`. The history's only rich retention is the depth-1 `Model.RecoveryPoint`, which retains at most one `(ModelAction, GUITreeAction)` pair whose tree is owned by a live state.

- **INV-MODEL-19**: After `Model.release(tree)` returns, no `ModelAction` of the tree's owning state SHALL hold a resolved reference into the released tree (`ModelAction.releaseResolved` clears `resolvedTree`, `resolvedGUITreeAction`, `resolvedNode`, `resolvedNodes` and resets the resolve timestamp to `-1`), and every such cleared action SHALL report `isResolvedAt(t) == false` for every step timestamp `t` while keeping its `resolvedSaturation` unchanged. A tree with no owning state is released without touching any action.

- **INV-MODEL-20**: The retention fixes (GUITreeBuilder cache release, snapshot action history, resolved-object release) MUST be decision-neutral: same seed ⇒ identical action sequence with and without them. No retention fix may alter a decision input (`resolvedSaturation`, priorities, boosts, provenance) or add a configuration flag; in particular the recovery point is remapped across a rebuild only when its action satisfies `requireTarget()` (`StatefulAgent.updateModel`). Neutrality is evidenced by unit tests that execute the changed paths (release cycles, history append, recovery, rebuild remap); the parity-oracle goldens are a regression floor on the decision ladder and SHALL NOT be cited as evidence for this invariant, since their harness never runs `GUITreeBuilder.release`, `Model.release`, `Model.appendToActionHistory`, `recoverCurrentState` or `updateModel`.

## MODIFIED Requirements

### Requirement: LLM Coordinate Tap Action

The model MUST provide `LlmTapAction`, a subclass of `ModelAction` with `actionType = MODEL_LLM_TAP` and `target = null`, carrying an off-tree tap payload: an integer pixel coordinate `(pixelX, pixelY)` and a `longClick` flag. It is constructed only by `CoordinateMapper.map` on the LLM path, for the off-tree case, and returned through `LlmEngine` to the LLM pipeline stage that selected it; it is NOT a member of any `State.actions` array.

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

### Requirement: Current-State Recovery Point

`StatefulAgent.recoverCurrentState` restores `currentState`/`currentAction`/`currentGUITree`/`currentGUITreeAction` when the current state was lost. At HEAD it scanned the rich history backwards: return without recovery if a `canStartApp()` record is found first; otherwise recover from the most recent model-action record. Snapshot records cannot serve this path, so the `Model` SHALL maintain a dedicated **recovery point** of depth 1: the rich `(ModelAction, GUITreeAction)` pair of the most recent model action, plus a blocked flag, updated on every append by three rules evaluated in the scan's precedence (`canStartApp` checked before `isModelAction`):

1. appended action `canStartApp()` → blocked;
2. else if model action → recovery point set to the rich pair, unblocked;
3. else → no change.

Recovery SHALL occur iff the point exists and is not blocked, and SHALL restore exactly what the HEAD scan restored. After a model rebuild, the recovery point's action reference SHALL be remapped through `Model.update(ModelAction, GUITreeAction)` in `StatefulAgent.updateModel` — **iff the action satisfies `requireTarget()`**, which is the condition the deleted history remap loop applied. A targetless model action (`MODEL_BACK`, `MODEL_MENU`) held as the recovery point SHALL therefore keep referring to the pre-rebuild object, exactly as at HEAD, and a recovery from it SHALL restore that object. Remapping it unconditionally would be a behavior change — plausibly an improvement, since it would remove a stale-recovery path, but not a neutral one, and this change carries no evidence able to evaluate it (INV-MODEL-20). It is left to a change that measures exploration, not to this one. The recovery point is the **only** rich retention in the history subsystem, and it retains at most one tree — one that the owning state's `treeHistory` retains anyway.

#### Scenario: recovery from the most recent model action

- **WHEN** the append sequence ends `…, model action M, fuzz` and the current state is lost
- **THEN** `recoverCurrentState` SHALL recover state, action, tree, and tree-action from M's rich pair — the same outcome as the HEAD backward scan

#### Scenario: a start action blocks recovery

- **WHEN** the append sequence ends `…, model action M, start action, fuzz` and the current state is lost
- **THEN** `recoverCurrentState` SHALL NOT recover (the start action is more recent than M), matching the HEAD scan's early return

#### Scenario: a targeted recovery point is remapped across a rebuild

- **WHEN** a naming refinement rebuilds the model while the recovery point holds a model action satisfying `requireTarget()` (a widget click, say)
- **THEN** the recovery point's model action SHALL be remapped to the rebuilt model's corresponding action
- **AND** a subsequent recovery SHALL restore non-stale objects

#### Scenario: a targetless recovery point is not remapped, matching HEAD

- **WHEN** a naming refinement rebuilds the model while the recovery point holds `MODEL_BACK` (targetless, so `requireTarget()` is false)
- **THEN** the recovery point SHALL NOT be remapped
- **AND** a subsequent recovery SHALL restore the pre-rebuild object — the same stale object the HEAD implementation restores, since its remap loop skipped targetless records while its recovery scan accepted them
- **AND** this pair of scenarios SHALL exist as a contrast test, so that changing the guard later is a visible, deliberate edit rather than a silent one
