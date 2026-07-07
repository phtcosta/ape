## MODIFIED Requirements

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

## Invariants

- **INV-MODEL-11**: `Model.rebuild()` SHALL be count-preserving for edge and activity counters: after any number of rebuilds, each edge's `visitedCount` equals the count implied by the replayed transition history, and each `ActivityNode`'s visit count equals its pre-rebuild (live-exploration) total. (Per-state source counters are excluded — see the deferred note above.)
