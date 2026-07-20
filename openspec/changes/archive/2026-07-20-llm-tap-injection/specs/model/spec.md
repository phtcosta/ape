# Delta: model — llm-tap-injection

## Purpose

INV-MODEL-16 (refinement-crash-recovery) quarantined ephemeral actions through a model rebuild on
two paths: the rebuild replay (skip + purge ephemeral edges) and the post-rebuild re-anchor
(`Model.update` returns ephemeral references unchanged). The cmpm forensics
(`docs/20260720_analise_forense_cmpm_traces.md`, finding A2) exposed a third path the quarantine
missed: the agent still holds the ephemeral action as `currentAction`, and — precisely because the
re-anchor leaves it unchanged — its anchor state is a state the rebuild removed. When
`StatefulAgent.updateGraph` records the step's transition, `Graph.addTransition` reaches
`StateTransition.<init>`, whose `source.equals(action.getState())` invariant throws
`IllegalStateException` and terminates the run (cmpm's only truncation: floflacards v2 rep1, 82s,
3 steps).

This delta closes the loop: the transition-recording path detects the stale ephemeral pair and skips
the edge — an ephemeral edge is observational (INV-MODEL-16 purges it from replay anyway), so losing
it is strictly cheaper than losing the run. Callers already tolerate a null transition
(`StatefulAgent.checkNonDeterministicTransitions` early-returns on null `currentStateTransition`).
Non-ephemeral actions are unaffected: for them a source/anchor mismatch remains a programming error
that must keep throwing.

## Invariants

- **INV-MODEL-17** (new): `Graph.addTransition` SHALL NOT construct a `StateTransition` for an
  ephemeral action whose `getState()` differs from the transition source (a stale anchor left by a
  rebuild). It SHALL log one `[APE-RV] stale ephemeral edge dropped` line and return `null`; it
  SHALL NOT throw. Behavior for non-ephemeral actions is unchanged (a mismatch still throws
  `IllegalStateException`).

## MODIFIED Requirements

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

The quarantine SHALL also cover transition recording (INV-MODEL-17): when `Graph.addTransition`
receives an ephemeral action whose anchor state (`action.getState()`) is not equal to the given
source state — the stale pair a rebuild leaves behind when the ephemeral action was the agent's
in-flight `currentAction` — it SHALL skip the edge, log one
`[APE-RV] stale ephemeral edge dropped: <action> (source <state>)` line, and return `null` instead
of letting `StateTransition.<init>` throw `IllegalStateException`. A non-ephemeral action with a
mismatched source keeps the existing throwing behavior.

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

#### Scenario: stale ephemeral action reaches transition recording after a rebuild

- **WHEN** `Graph.addTransition(source, action, target, ...)` is called with an ephemeral
  `LlmTapAction` whose `getState()` is a state removed by a rebuild (not equal to `source`)
- **THEN** no `StateTransition` SHALL be constructed and the method SHALL return `null`
- **AND** one `[APE-RV] stale ephemeral edge dropped` line SHALL be emitted
- **AND** no `IllegalStateException` SHALL propagate

#### Scenario: non-ephemeral source mismatch still throws

- **WHEN** `Graph.addTransition(source, action, target, ...)` is called with a non-ephemeral
  `ModelAction` whose `getState()` is not equal to `source`
- **THEN** `StateTransition.<init>` SHALL throw `IllegalStateException` exactly as before
