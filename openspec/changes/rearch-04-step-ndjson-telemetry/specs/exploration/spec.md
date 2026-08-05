# exploration Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the exploration engine stops writing the legacy per-run output files (`sataGraph.dot`, `sataGraph.vis.js`, per-state `step-*.txt`, `action-history.log`, `produce.log`, `consume.log`, `sataTimeline.vis.js`) and its termination path is restated around the event sink — teardown gains `flushPendingStep` (first agent step) and the `RUN_END` emission (last agent step), both as isolated safeSteps. The teardown isolation invariants INV-EXPL-16/29 are preserved untouched in mechanism; only the step roster changes. INV-EXPL-17 (per-step PNG/XML debug artifacts default off) is unaffected by this change.

## Invariants

- **INV-EXPL-29 — step roster re-anchored, isolation semantics unchanged.** The invariant enumerates the teardown steps its isolation guarantee ranges over — *"rotation restore, `disconnect()`, LLM summary, `super.tearDown()`, coverage dump, action-history save, action counters, activity nodes, naming dump, model counters"* — and it lives in this capability's top-level `## Invariants` block, outside every requirement this delta modifies. Two members of that list stop existing here: the **LLM summary** step (retired with `printSummary()` by this change's "StatefulAgent — LLM Telemetry at tearDown") and the **action-history save** (deleted with `Model.saveActionHistory`, task 7.2). Two are added: **`flushPendingStep`** first and **`runEnd`** last. The roster therefore becomes `flushPendingStep`, `super.tearDown()`, coverage dump, action counters, activity nodes, naming dump, model counters, `runEnd` — inside `MonkeySourceApe.tearDown`, the rotation restore and `disconnect()` are unchanged. **The guarantee itself is untouched**: a `Throwable` from any one step SHALL NOT prevent any subsequent step from executing, which is the property the "Output Persistence on Termination" requirement below restates over the new roster. Only the enumeration moves; nothing about isolation is weakened, and the two new steps are ordinary `safeStep`s precisely so the invariant continues to range over them.

## MODIFIED Requirements

### Requirement: Output Persistence on Termination

On termination — normal (when `StopTestingException` is caught) or abnormal (any other `Throwable` escaping the exploration loop) — the exploration engine SHALL flush its observational stream, never file-based graph artefacts. The `tearDown()` call chain SHALL execute inside a `finally` block in `Monkey`, so an uncaught `RuntimeException` from the event loop still produces the run's outputs before the process exits. The run's outputs are: the NDJSON trace on stdout (event-sink capability) — flushed by `flushPendingStep` and terminated by `RUN_END` — and the UI-coverage dump lines (ui-coverage capability, format unchanged). No graph serialization, DOT/vis export, per-state dump, action-history log, event produce/consume log, or timeline export SHALL be written: the writers for `sataGraph.dot`, `sataGraph.vis.js`, `step-*.txt`, `action-history.log`, `produce.log`, `consume.log`, and `sataTimeline.vis.js` are deleted, along with the `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` configuration flags. Post-run visualizations, if ever needed, are host-side post-processing over the trace.

The `finally` block in `Monkey.run` SHALL guard each of its throw-capable statements individually: the rotation restore (`MonkeyRotationEvent.injectEvent`) and the `MonkeySourceApe.tearDown()` call each run inside their own catch that logs the failure with a full stack trace. A failure in the rotation restore SHALL NOT skip teardown, and a failure anywhere in teardown SHALL NOT replace the exception that terminated the loop (INV-EXPL-16): after the `finally` completes, the original loop exception SHALL propagate to `Monkey.main`'s outermost handler and be the stack trace reported for the run.

Teardown SHALL be step-isolated (INV-EXPL-29). In `MonkeySourceApe.tearDown`, a `Throwable` from `disconnect()` SHALL NOT prevent `mAgent.tearDown()` nor the image-writer shutdown that follows. In `StatefulAgent.tearDown`, a `Throwable` from any step of the sequence — `flushPendingStep` (writes the in-flight `StepRecord` with `out:{"resolved":false}`), `super.tearDown()`, coverage dump, action counters, activity nodes, naming dump, model counters, `runEnd` (emits `RUN_END` with reason and counters, last) — SHALL be caught and logged with its stack trace, and all remaining steps SHALL still execute. The replay reader (`ApeRRFormatter.readActions`, used by `ReplayAgent` on externally supplied logs) SHALL be preserved; only the tool's own writers of that format are deleted.

#### Scenario: Normal termination flushes the sink, writes no legacy files

- **WHEN** `StopTestingException` is caught after the time limit expires
- **THEN** any pending `StepRecord` SHALL be flushed (`out:{"resolved":false}` if unresolved) and `RUN_END` SHALL be the last sink record
- **AND** no `sataGraph.dot`, `sataGraph.vis.js`, `step-*.txt`, `action-history.log`, `produce.log`, `consume.log`, or `sataTimeline.vis.js` file SHALL exist in the output directory

#### Scenario: abnormal termination still flushes the trace

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

#### Scenario: one failing agent-teardown step does not skip RUN_END

- **WHEN** `StatefulAgent.tearDown()` runs and the naming dump throws a `RuntimeException`
- **THEN** the failure SHALL be logged with its stack trace
- **AND** the model counters SHALL still be printed and `RUN_END` SHALL still be emitted

#### Scenario: flushPendingStep runs before the expensive steps

- **WHEN** `StatefulAgent.tearDown()` begins with a step in flight
- **THEN** `flushPendingStep` SHALL execute as the first step of the agent teardown chain, so a mid-teardown cut costs at most the later free-text dumps, never the in-flight record

### Requirement: StatefulAgent — LLM Telemetry at tearDown

`StatefulAgent.tearDown()` SHALL fold the aggregate LLM counters into the `RUN_END` record (event-sink capability) via the counters accessor on the LLM infrastructure (when present). The `[APE-RV] LLM Summary:` and `[APE-RV] LLM Decision ratio:` log lines and `printSummary()` are retired; the decision ratio is derived from the counters by consumers, not stored.

#### Scenario: LLM counters ride RUN_END on normal termination

- **WHEN** `StatefulAgent.tearDown()` is called after `StopTestingException` in an LLM-enabled run
- **THEN** the `RUN_END` record SHALL carry the aggregate LLM counters (`calls`, token totals, outcome and failure-cause counters, `breaker_trips`)
- **AND** no `[APE-RV] LLM Summary` line SHALL be emitted

#### Scenario: Non-LLM arm

- **WHEN** teardown runs in an arm whose plan has no LLM feature
- **THEN** `RUN_END` SHALL simply omit the LLM counter block (absent = feature absent)
