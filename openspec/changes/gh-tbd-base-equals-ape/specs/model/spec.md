## MODIFIED Requirements

### Requirement: Model Rebuild After Naming Refinement

When the `NamingFactory` updates the `Naming` for one or more `GUITree`s (because non-determinism was detected or action refinement was requested), the `Model` MUST rebuild all affected `State`s and `StateTransition`s so that the graph remains consistent with the updated abstraction.

Rebuild MUST be count-preserving for edge counters in every cell (INV-MODEL-11): replaying the transition history re-establishes edge visit counts exclusively through the same `markVisited` path used during live exploration. `Graph.rebuildHistory()` SHALL NOT apply any additional `visitedCount` increment of its own, with or without `Feature.APE_FIXES`. Two consecutive rebuilds of the same history therefore yield identical edge visit counts.

Activity visit counters during the replay depend on `Feature.APE_FIXES`, read through `RunContext.current().spec().has(Feature.APE_FIXES)` in `Graph.markVisited(State, int)`, the single decision point. `Graph.markVisited(State, int)` always calls `State.visitedAt(timestamp)`; it counts the state's activity (`markVisited(activity, timestamp)`) unless the graph is rebuilding (the `rebuilding` flag set through `Graph.setRebuilding`) and the member is in the plan:

- With the member (the four ADDONS cells), `ActivityNode` visit counters SHALL be preserved across rebuild — the replay SHALL NOT re-mark activities. An activity's visit count is invariant under state re-abstraction (a `GUITree`'s activity name does not change when its abstract `State` does) and survives `ActivityNode.removeState` (which detaches states but leaves the counter untouched), so it is already complete before replay; re-marking the partial set of replayed sources would double-count activities that also have non-replayed transitions. Two consecutive rebuilds of the same history yield identical activity visit counts.
- Without the member (`base`, `mop`), each `markVisited(State)` the replay makes SHALL add one visit to the state's `ActivityNode`, as upstream APE does, so every rebuild raises the activity counters that `greedyPickLeastVisited`, saturation and trivial-activity detection read.

Note (deferred): the replay re-marks *surviving source `State`s* of removed transitions via `markVisited(State)` in every cell, so per-state visit counts are not rebuild-idempotent. INV-MODEL-11 is scoped to edge and activity counters accordingly, and its activity part to the member: in every cell each edge's `visitedCount` after any number of rebuilds SHALL equal the count implied by the replayed transition history; with `APE_FIXES` in the plan each `ActivityNode`'s visit count SHALL equal its pre-rebuild (live-exploration) total; without it each replayed `markVisited(State)` SHALL add one visit to the state's `ActivityNode`.

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
- **WHEN** an edge was traversed 3 times during live exploration and `Model.rebuild()` runs twice, in any of the six cells
- **THEN** after both rebuilds the rebuilt edge's `visitedCount` SHALL equal 3

#### Scenario: rebuild preserves activity visit counts
- **WHEN** an `ActivityNode` accumulated 10 visits during live exploration and `Model.rebuild()` runs, with `APE_FIXES` in the plan (the `as` cell)
- **THEN** after the rebuild the `ActivityNode`'s visit count SHALL equal 10 (preserved across rebuild; the replay does not re-mark activities)

#### Scenario: rebuild counts activity visits again without the member
- **WHEN** an `ActivityNode` accumulated 10 visits during live exploration, `Model.rebuild()` runs and its replay calls `markVisited(State)` 4 times for states of that activity, with `APE_FIXES` absent (the `base` or `mop` cell)
- **THEN** after the rebuild the `ActivityNode`'s visit count SHALL equal 14
- **AND** each rebuilt edge's `visitedCount` SHALL still equal its live-exploration count
