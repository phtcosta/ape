# Delta: model — refinement-crash-recovery

## Purpose

This delta documents and hardens the action-history persistence contract. `Model` keeps an
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

## ADDED Requirements

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
