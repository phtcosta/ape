# Delta: exploration — refinement-crash-recovery

## Purpose

This delta hardens the exploration engine's failure paths so that (a) the exception that actually
terminates the exploration loop is always the one reported, and (b) a naming refinement can no
longer kill the run. Today `Monkey.run`'s `finally` calls `MonkeySourceApe.tearDown()` unguarded;
when teardown itself throws — which it deterministically does after a refinement leaves a stale
`ActionRecord` (`GUITree.pickNodes` → `IllegalStateException: Cannot find widget`) — the teardown
exception replaces the in-flight loop exception and the run's primary diagnostic evidence is
destroyed. The same masking class exists in `Naming.naming`, whose `finally` dereferences a
possibly-null result. Teardown is also all-or-nothing: `MonkeySourceApe.tearDown` runs
`disconnect()` before the agent teardown (a throw there loses the entire model), and
`StatefulAgent.tearDown` runs eight persistence/diagnostic steps sequentially (a throw at any step
skips all later steps).

Separately, the post-refinement revalidation pass (`validateAllNewActions`) resolves every action of
`newState` against the latest `GUITree`; when the refined naming no longer binds an action's
pre-refinement `Name`, the resulting `IllegalStateException` propagates out of the exploration loop
uncaught. This delta makes that rebind failure a logged, counted, non-fatal event that invalidates
the failing action.

## Invariants

- **INV-EXPL-16** (restated, extended): `tearDown()` SHALL run on every termination path of the
  exploration loop, normal or abnormal, **and SHALL NOT replace the in-flight exception**: a
  `Throwable` thrown by any teardown step SHALL be caught and logged with its full stack trace
  inside the teardown chain, never propagated out of the `finally` block in `Monkey.run`.
- **INV-EXPL-29**: Teardown SHALL be step-isolated: a `Throwable` thrown by one teardown step
  (rotation restore, `disconnect()`, LLM summary, graph save, action-history save, counters,
  activity nodes, naming dump) SHALL NOT prevent any subsequent teardown step from executing.
- **INV-EXPL-30**: A post-refinement rebind failure (an action of `newState` whose `Name`
  no longer binds in the latest `GUITree`) SHALL NOT terminate the exploration loop; the action
  is marked invalid (`setValid(false)`), the failure is logged once with the action descriptor,
  and exploration proceeds to the next step.

## MODIFIED Requirements

### Requirement: Output Persistence on Termination

On termination — normal (when `StopTestingException` is caught) or abnormal (any other `Throwable`
escaping the exploration loop) — the exploration engine SHALL save graph artefacts to the output
directory. The `tearDown()` call chain (agent teardown, model serialisation, coverage dump,
timeline) SHALL execute inside a `finally` block in `Monkey`, so an uncaught `RuntimeException`
from the event loop still produces the run's outputs before the process exits. The serialised graph
(`sataModel.obj`) is written by `ObjectOutputStream` and contains the full in-memory `Graph`
object. The Graphviz file (`sataGraph.dot`) is a DOT representation of every state and transition.
The visualisation file (`sataGraph.vis.js`) is a vis.js JSON representation. All writes MUST
complete before the process exits. If `ape.saveObjModel=false`, the `sataModel.obj` file SHALL NOT
be written (default `true`). If `ape.saveDotGraph=false`, the `sataGraph.dot` file SHALL NOT be
written (default `false`). If `ape.saveVisGraph=false`, the `sataGraph.vis.js` file SHALL NOT be
written (default `true`).

The `finally` block in `Monkey.run` SHALL guard each of its throw-capable statements individually:
the rotation restore (`MonkeyRotationEvent.injectEvent`) and the `MonkeySourceApe.tearDown()` call
each run inside their own catch that logs the failure with a full stack trace. A failure in the
rotation restore SHALL NOT skip teardown, and a failure anywhere in teardown SHALL NOT replace the
exception that terminated the loop (INV-EXPL-16): after the `finally` completes, the original loop
exception SHALL propagate to `Monkey.main`'s outermost handler and be the stack trace reported for
the run.

Teardown SHALL be step-isolated (INV-EXPL-29). In `MonkeySourceApe.tearDown`, a `Throwable` from
`disconnect()` SHALL NOT prevent `mAgent.tearDown()` (which persists the model and action history)
nor the writer/logger shutdown and timeline export that follow. In `StatefulAgent.tearDown`, a
`Throwable` from any step of the sequence (LLM summary, `super.tearDown()`, `saveGraph()`,
`saveActionHistory()`, action counters, activity nodes, naming dump, model counters) SHALL be
caught and logged with its stack trace, and all remaining steps SHALL still execute.

#### Scenario: Normal termination with defaults

- **WHEN** `StopTestingException` is caught after the time limit expires
- **AND** `ape.saveObjModel` and `ape.saveVisGraph` are both at their defaults (`true`), and `ape.saveDotGraph` is at its default (`false`)
- **THEN** `sataModel.obj` SHALL be written to the output directory via Java object serialisation
- **AND** `sataGraph.vis.js` SHALL be written to the output directory as a vis.js JSON file
- **AND** `sataGraph.dot` SHALL NOT be written (disabled by default)
- **AND** all writes SHALL complete before the process returns

#### Scenario: saveObjModel disabled

- **WHEN** `ape.saveObjModel=false` is set in `ape.properties`
- **AND** the session terminates normally
- **THEN** `sataModel.obj` SHALL NOT be created or overwritten
- **AND** `sataGraph.vis.js` SHALL still be written (independent flag)

#### Scenario: abnormal termination still persists outputs

- **WHEN** a `RuntimeException` escapes the exploration loop
- **THEN** `tearDown()` SHALL still run (via `finally`) and write the enabled artefacts before the process exits
- **AND** the exception SHALL still propagate (the run is reported as failed)

#### Scenario: teardown failure does not mask the loop exception

- **WHEN** an `IllegalStateException` escapes the exploration loop
- **AND** a teardown step (e.g. action-history persistence) throws its own `RuntimeException`
- **THEN** the teardown failure SHALL be logged with its full stack trace
- **AND** the exception reported by `Monkey.main`'s outermost handler SHALL be the original loop `IllegalStateException`, not the teardown failure

#### Scenario: rotation-restore failure does not skip teardown

- **WHEN** the exploration loop terminates and `MonkeyRotationEvent.injectEvent` throws (e.g. a binder failure)
- **THEN** the failure SHALL be logged
- **AND** `MonkeySourceApe.tearDown()` SHALL still execute and persist the run's artefacts

#### Scenario: disconnect failure does not lose the model

- **WHEN** `MonkeySourceApe.tearDown()` runs and `disconnect()` throws `IllegalStateException`
- **THEN** the failure SHALL be logged
- **AND** `mAgent.tearDown()` SHALL still execute, persisting `sataModel.obj` and the action history

#### Scenario: one failing agent-teardown step does not skip the rest

- **WHEN** `StatefulAgent.tearDown()` runs and `saveActionHistory()` throws a `RuntimeException`
- **THEN** the failure SHALL be logged with its stack trace
- **AND** the action counters, activity nodes, naming dump, and model counters SHALL still be printed

## ADDED Requirements

### Requirement: Post-Refinement Action Revalidation Tolerance

After a naming refinement rebuilds the model (`checkNonDeterministicTransitions` →
`Model.resolveNonDeterministicTransitions` → `updateModel`), `StatefulAgent.validateAllNewActions`
re-resolves every action of `newState` against the latest `GUITree` via
`State.resolveAction`. A rebind failure — the action's `Name` no longer binds in the re-abstracted
tree, surfacing as `IllegalStateException: Cannot find widget` from `GUITree.pickNodes` — SHALL NOT
propagate out of the revalidation pass. The failing action SHALL be marked invalid
(`setValid(false)`), which excludes it from every action filter — the `valid` flag is the only
signal all filters honour. Leaving the action merely unresolved would NOT exclude it: `valid` is
sticky across steps, and a failed resolution retains the previous step's `resolvedNode`, so a
previously-valid action would stay selectable and dispatch a tap at stale coordinates. The failure
SHALL be logged once with the action descriptor under an `[APE-RV]` tag, and a per-run counter of
rebind failures SHALL be maintained and printed at teardown. Exploration SHALL proceed to action
selection for the same step (INV-EXPL-30).

This is the loop-side counterpart of the tolerant action-history persistence in the `model`
capability: both consume descriptors minted under a pre-refinement naming, and both treat a failed
re-resolution as data loss to report, not a fatal condition.

#### Scenario: rebind failure after refinement does not kill the run

- **WHEN** a non-deterministic transition triggers a naming refinement that removes the agent's current state and rebuilds the model
- **AND** one surviving action of the re-abstracted `newState` fails to rebind (`Cannot find widget`)
- **THEN** the exploration loop SHALL continue: the step completes and a next action is selected from the remaining resolved actions
- **AND** the rebind failure SHALL be logged once with the action's descriptor
- **AND** the run SHALL reach its configured time budget (no early termination attributable to the refinement)

#### Scenario: invalidated action is not selected

- **WHEN** an action that had previously resolved successfully (`valid == true`) fails to rebind and is marked invalid
- **THEN** the selection step SHALL NOT dispatch that action during the current step
- **AND** no selection path SHALL read its stale resolved node

#### Scenario: rebind-failure counter reported at teardown

- **WHEN** a run had 3 rebind failures across its refinements
- **THEN** the teardown diagnostics SHALL include a line reporting the total count of 3
