# Delta: model — refinement-crash-recovery

## Purpose

This delta (a) extends the ephemeral-action quarantine of INV-MODEL-13/14 to the two model-rebuild
paths it did not cover, and (b) documents and hardens the action-history persistence contract.

The F-D gate (2026-07-17, design.md D7 outcome 1) proved the cmpv2 in-loop terminator: a naming
refinement removes a state whose in/out edges include an ephemeral `MODEL_LLM_TAP` edge —
`Graph.remove` collects **all** edges of the removed state with no `isEphemeral` filter — and
`Model.rebuild`'s transition replay then re-anchors each collected `GUITreeTransition` via
`State.getAction(type)` for targetless actions. An ephemeral action is never a member of
`State.getActions()` (INV-MODEL-14), so the re-anchor throws
`IllegalStateException: No such action [MODEL_LLM_TAP]` and kills the run. A second, latent hole
of the same shape: after a rebuild, `StatefulAgent.updateModel` re-anchors the agent's action
references (`Model.update(ModelAction, GUITreeAction)` → `state.getAction(type)`), which throws
identically when the reference is the ephemeral tap and its state was removed.

This delta also documents and hardens the action-history persistence contract. `Model` keeps an
append-only `List<ActionRecord>` of every dispatched action; at teardown,
`Model.saveActionHistory(File, List<ActionRecord>)` writes it to `action-history.log`, re-resolving
each record's `ModelAction` against the `GUITree` captured when the action was taken
(`ActionRecord.resolveModelAction` → `GUITree.pickNodes` for target-requiring actions). A naming
refinement between the record's creation and teardown can make the recorded `Name` ambiguous or
absent in that tree's re-abstracted name array, in which case `pickNodes` throws
`IllegalStateException: Cannot find widget`. Today that exception escapes `saveActionHistory`
unhandled — the method's existing `catch (IOException)` does not intercept an
`IllegalStateException` — aborting the whole file write, propagating out of teardown, exiting the
process with code 1 and masking the exception that ended the run. After this delta, resolution
failures are per-record, counted, and reported — the file is always written.

## Invariants

- **INV-MODEL-15**: `Model.saveActionHistory` SHALL complete and produce `action-history.log` for
  every teardown, regardless of how many individual `ActionRecord`s fail to resolve. A resolution
  failure affects only its own record (the record is skipped); it MUST NOT abort the iteration,
  suppress other records, or propagate out of the method.
- **INV-MODEL-16**: The ephemeral-action quarantine SHALL survive a model rebuild. An ephemeral
  edge (a `StateTransition` whose action `isEphemeral()`) SHALL NOT be replayed by
  `Model.rebuild`: it is observational and does not survive the refinement that removed its
  states — its `GUITreeTransition`s are dropped from the replay set **and** from the graph's
  tree-transition history (so `rebuildHistory` cannot resurrect a dangling edge). A
  post-refinement re-anchor of an agent action reference
  (`Model.update(ModelAction, GUITreeAction)`) SHALL return an ephemeral action unchanged —
  its identity is its payload (INV-MODEL-13), not `State.getActions()` membership, so a
  membership lookup is a category error, not a recoverable miss. Neither path SHALL throw for
  an ephemeral action; non-ephemeral behavior is unchanged.

## ADDED Requirements

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
