## ADDED Requirements

### Requirement: Diagnostic Action History Holds Snapshots, Not Trees

`Model.actionHistory` is a diagnostic record of every executed action, appended once per action (`MonkeySourceApe.generateEventsForAction`) and once per crash (`ApeAgent.appCrashed`). At HEAD each `ActionRecord` held an `Action` plus a `GUITreeAction`, and through it a `GUITree` and its entire `GUITreeNode` subtree — one full GUI tree pinned per step for the whole run, the retainer the code itself marks `TODO: may be the cause of OOM` (V11, report Sec. 3.1).

After this change, `ActionRecord` SHALL hold only identifiers and a minimal snapshot — primitives and strings captured at append time, when the action's resolved objects are still valid: clock timestamp, agent timestamp, action type name, state identifier (null for non-model actions), target `Name` as XPath string (null for targetless actions), GUI tree id and tree timestamp, and throttle. An `ActionRecord` SHALL NOT hold a reference to any `Action`, `GUITreeAction`, `GUITree`, or `GUITreeNode`.

This shape is conditional on the caller audit recorded in this change's `design.md`: at stage 6 (after `rearch-02-runspec` removed model serialization and `rearch-04-step-ndjson-telemetry` removed `action-history.log`), the deep history has zero rich-object consumers — the single semantic consumer, `StatefulAgent.recoverCurrentState`, needs only the most recent model-action record and is served by the recovery point below. The teardown re-resolution (`ActionRecord.resolveModelAction`) is deleted with its last consumer. The post-rebuild remap loop over the history (`StatefulAgent.updateModel`) and `Model.updateActionHistory` are deleted (P3): snapshot records hold no model references, so nothing in them can go stale.

The host-side crash minimizer `reducer/ape/Reducer.java` also read rich records, but it is outside the Maven build (never part of `target/ape-rv.jar`) and its input artifact `sataModel.obj` is removed by `rearch-02-runspec`; it is dead tooling at this stage and is not a consumer this requirement must serve.

#### Scenario: appending a model action retains no tree

- **WHEN** a resolved model action is executed and appended to the action history
- **THEN** the appended record SHALL contain the snapshot fields (timestamps, action type, state id, target XPath, tree id/timestamp, throttle)
- **AND** the record SHALL hold no reference to the action object, the `GUITreeAction`, the `GUITree`, or any `GUITreeNode`

#### Scenario: appending a non-model action

- **WHEN** a non-model action (fuzz event, crash record, lifecycle event) is appended
- **THEN** the record SHALL carry its timestamps and action type with null state/target identifiers, exactly as the rich record carried a null `guiAction` at HEAD

#### Scenario: model rebuild leaves the history untouched

- **WHEN** a naming refinement rebuilds the model
- **THEN** no history record SHALL require remapping (there is no per-record remap loop)
- **AND** no history record SHALL keep any pre-rebuild model object reachable

### Requirement: Current-State Recovery Point

`StatefulAgent.recoverCurrentState` restores `currentState`/`currentAction`/`currentGUITree`/`currentGUITreeAction` when the current state was lost. At HEAD it scanned the rich history backwards: return without recovery if a `canStartApp()` record is found first; otherwise recover from the most recent model-action record. Snapshot records cannot serve this path, so the `Model` SHALL maintain a dedicated **recovery point** of depth 1: the rich `(ModelAction, GUITreeAction)` pair of the most recent model action, plus a blocked flag, updated on every append by three rules evaluated in the scan's precedence (`canStartApp` checked before `isModelAction`):

1. appended action `canStartApp()` → blocked;
2. else if model action → recovery point set to the rich pair, unblocked;
3. else → no change.

Recovery SHALL occur iff the point exists and is not blocked, and SHALL restore exactly what the HEAD scan restored. After a model rebuild, the recovery point's action reference SHALL be remapped through `Model.update(ModelAction, GUITreeAction)` in `StatefulAgent.updateModel`, the same discipline as the agent's own `currentAction`/`currentGUITreeAction` pairs. The recovery point is the **only** rich retention in the history subsystem, and it retains at most one tree — one that the owning state's `treeHistory` retains anyway.

#### Scenario: recovery from the most recent model action

- **WHEN** the append sequence ends `…, model action M, fuzz` and the current state is lost
- **THEN** `recoverCurrentState` SHALL recover state, action, tree, and tree-action from M's rich pair — the same outcome as the HEAD backward scan

#### Scenario: a start action blocks recovery

- **WHEN** the append sequence ends `…, model action M, start action, fuzz` and the current state is lost
- **THEN** `recoverCurrentState` SHALL NOT recover (the start action is more recent than M), matching the HEAD scan's early return

#### Scenario: recovery point survives a rebuild

- **WHEN** a naming refinement rebuilds the model while a recovery point is set
- **THEN** the recovery point's model action SHALL be remapped to the rebuilt model's corresponding action
- **AND** a subsequent recovery SHALL restore non-stale objects

### Requirement: ModelAction Resolved-Object Lifetime

A `ModelAction`'s resolved references (`resolvedTree`, `resolvedGUITreeAction`, `resolvedNode`, `resolvedNodes`) are written at each resolve (`State.resolveAction`; every action of the current state is re-resolved each step via `validateAllNewActions`) and consumed only within the resolving step — scoring, selection, refinement, and event generation all read them before the step completes, and the step-N+1 outcome path uses the agent's own field snapshots. Overwrite-on-re-resolve already bounds the fields to the **last** resolve; what was unbounded at HEAD (V24, report Sec. 3.1) is the referenced tree's lifetime: an action resolved against a tree the model later releases kept that tree reachable after it left `State.treeHistory`.

After this change: when a `GUITree` is released, `Model.release(removed)` SHALL invoke `releaseResolved(removed)` on every action of the tree's owning state, in the same release cycle as the `GUITreeBuilder` cache sweep. `ModelAction.releaseResolved(released)` SHALL, iff `resolvedTree == released` (reference identity): null the four resolved reference fields and invalidate the resolve timestamp so `isResolvedAt` returns false for every timestamp; and SHALL NOT modify `resolvedSaturation` (the one cross-step semantic scalar — `isSaturated()` feeds SATA decisions and action filters across steps), nor priority, boost, or provenance fields. For an action resolved against a different (live) tree, the call SHALL be a no-op.

The resulting lifetime rule: resolved references live exactly as long as both (a) no newer resolve has replaced them and (b) their tree is still owned by its state. While (b) holds, the references retain nothing that `treeHistory` does not already retain — no per-step clearing is performed.

#### Scenario: tree release clears the resolved references that point into it

- **WHEN** a tree is released via the recheck path (`removeLastLastGUITree` → `GUITreeBuilder.release` → `Model.release`) and an action of the owning state was last resolved against that tree
- **THEN** that action's `resolvedTree`, `resolvedGUITreeAction`, `resolvedNode`, and `resolvedNodes` SHALL be null
- **AND** `isResolvedAt(t)` SHALL be false for every timestamp `t`
- **AND** the released tree SHALL NOT be reachable from any action of the state

#### Scenario: saturation survives the release

- **WHEN** an action with `resolvedSaturation = 1.0` has its resolved references released
- **THEN** `isSaturated()` SHALL still report the saturation computed at the last resolve — release changes retention, never a decision input

#### Scenario: actions resolved against a live tree are untouched

- **WHEN** a tree is released and another action of the same state was last resolved against the state's surviving latest tree
- **THEN** that action's resolved references SHALL be unchanged

#### Scenario: a guarded reader after release behaves as with any stale resolve

- **WHEN** a scoring pass or `adjustActionsByGUITree` encounters an action whose references were released
- **THEN** the `isResolvedAt(timestamp)` guard SHALL skip it, exactly as it skips any action not resolved at the current step at HEAD

### Requirement: Retention Fixes Are Decision-Neutral

The stage-6 memory fixes (cache release, snapshot history, resolved-object release) are retention changes, not behavior changes. Each SHALL be observationally neutral: under the same seed and the same inputs, the explored action sequence — every selected action, in order — SHALL be identical before and after the fix. Neutrality is evidenced by the `rearch-01-parity-oracle` golden suite: the per-preset golden decision sequences SHALL pass unchanged after each fix lands (report Sec. 9 test 10: memory-semantics changes pass action-sequence parity plus refinement invariants). The fixes are unconditional defect repairs: no configuration flag guards them and no kill-switch registry entry is added for them.

Anything beyond these fixes — bounds or eviction on `Graph` collections, `State.treeHistory`, or naming/refinement structures — is explicitly out of scope until a heap profile by retention root on 600 s runs proves it necessary (report Sec. 6.7, Sec. 7). `OutOfMemoryError` handling is unchanged: process death, task FAILED in the supervisor, retry at the task level — no catch, no serialization on a dying heap.

#### Scenario: parity after each fix

- **WHEN** a fix group (V12, V11, or V24) is applied and the parity oracle's golden suite is re-run for each target preset under the captured seeds
- **THEN** every golden decision sequence SHALL compare identical

#### Scenario: no new configuration surface

- **WHEN** the stage-6 fixes are applied
- **THEN** no new `ape.properties` key SHALL exist for enabling or disabling them
- **AND** the kill-switch registries SHALL be unchanged

## Invariants

- **INV-MODEL-18**: No `ActionRecord` in `Model.actionHistory` SHALL hold a reference to an `Action`, `GUITreeAction`, `GUITree`, or `GUITreeNode`; the history's only rich retention is the depth-1 recovery point, which retains at most one `(ModelAction, GUITreeAction)` pair whose tree is owned by a live state.
- **INV-MODEL-19**: After `Model.release(tree)` returns, no `ModelAction` of the tree's owning state SHALL hold a resolved reference into the released tree, and every such cleared action SHALL report `isResolvedAt(t) == false` for all `t` while keeping its `resolvedSaturation` unchanged.
- **INV-MODEL-20**: The stage-6 retention fixes MUST be decision-neutral: same seed ⇒ identical action sequence before and after each fix, verified by the rearch-01 parity goldens; no retention fix may alter a decision input (`resolvedSaturation`, priorities, boosts, provenance) or add a configuration flag.
