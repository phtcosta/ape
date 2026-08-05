# ui-coverage Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`. The coverage-dump ordering requirement (INV-COV-10) is
**preserved in substance and re-anchored in form, for the second time**. Its boundary has always been
stated against the first teardown step that writes: originally the model serialization (`saveGraph`),
which `rearch-02-runspec` deleted; then the action-history save, which **this** change deletes
(task 7.2). After this stage no teardown step writes a file at all, so the boundary moves onto the
first step that produces output of any kind — the `actionCounters` free-text dump.

This capability had no delta in this change's original set, and that is the gap this delta closes.
The change's group 10 found the same failure class on the *re-rendering* side: a requirement living
in a capability nobody thought to disposition, which `openspec archive` would then sync into the main
specs demanding a mechanism the code no longer has. INV-COV-10 is its deletion-side twin — the
requirement names `saveActionHistory` as the live boundary in three places (the invariant, the
requirement narrative, and a scenario), and task 7.2 deletes it.

The property is not weakened. What INV-COV-10 buys is that the run's most fragile output is not
queued behind its most expensive ones — measured at 333 of 338 lost dumps recovered. The expensive
writes are now gone at the source, which is a stronger position than the ordering ever was; the
ordering is kept because the cheap dumps that remain still cost more than the coverage dump, and a
requirement that survives its own justification's removal is what keeps the property verifiable at
stage 5 and beyond.

## Invariants

- **INV-COV-10 (re-anchored a second time, not dissolved)**: the coverage dump MUST precede every
  other teardown step that produces output. The pre-`rearch-02` phrasing bound it to `saveGraph`;
  `rearch-02-runspec` re-bound it to chain position among writers, naming the action-history save as
  the writer of the day. This change deletes that step, leaving **no file-writing step in the chain**
  — so the binding moves to the first step that produces output at all, the `actionCounters` dump.
  Two steps precede the dump and neither is part of the boundary: `super.tearDown()` writes nothing,
  and **`flushPendingStep`** writes one already-serialized `StepRecord` line whose whole purpose is
  the same loss-bounding the dump's position buys (`event-sink` INV-SNK-08). The `runEnd` step is
  after the dump and is unaffected. The read-only clause (INV-COV-07 applies) and the single-call-site
  clause are untouched.

## MODIFIED Requirements

### Requirement: Coverage Dump Emitted First Among Teardown Writers

The coverage dump (the per-state `[APE-RV] UICOV` and per-Activity `[APE-RV] UICOV-ACT` emissions specified by "UICoverageTracker — Coverage Dump" and "UICoverageTracker — Per-Activity Coverage Dump") SHALL be emitted **before any other teardown step that produces output**.

The boundary is "first among writers", not a fixed index. Before this change the boundary was the model serialization step (`saveGraph`), which this change deletes together with `sataModel.obj`, `readGraph` and `--ape-model` (V14, finding 3.3-6; R1/R3 — clean runs, no read-back). The chain after this change is `flushPendingStep → superTearDown → coverage dump → action counters → activity nodes → naming dump → model counters → runEnd` (this change's `exploration` delta, INV-EXPL-29): the dump still lands third, and the first writer it must precede is now the `actionCounters` free-text dump. **`flushPendingStep` precedes the dump and is deliberately outside the boundary.** It writes one already-serialized `StepRecord` line to stdout — not an artifact, and not an expensive one — and it runs first for the same reason the dump runs early: it is loss-bounding, closing the record that a SIGKILL would otherwise take (`event-sink` INV-SNK-08). Ordering two loss-bounding emissions against each other would protect nothing; the boundary this requirement states is against the steps that *cost* something to produce. Stating the requirement on "first among writers" keeps it aligned with what recovers the lost dumps and with what the tests assert, and keeps it verifiable as later stages remove further steps from the chain — a requirement written on a chain index would break at stage 4.

Before the fix that introduced this requirement, the dump was the **last** step: `StatefulAgent.tearDown()` ran its full chain and only then did `SataAgent.tearDown()` reach `getCoverageTracker().dump(...)`. The most fragile item was emitted after the most expensive write.

The consequence is measured: **338 of the 800 `aperv` calibration runs (42.3%) carry no dump**, and **330 of those 338 end on the line `Save graph data to /sdcard/sata-…`** — cut *during* the `/sdcard` serialization, three steps before the dump would have run. A further 3 are cut after that write but before the dump. Emitting the dump first recovers **333 of the 338**. This measurement is the historical justification for the ordering and is retained as the record of why the requirement exists. **Both writes it names are now gone**: `rearch-02-runspec` deleted the model serialization and this change deletes the action-history save, so after this stage **no teardown step writes a file at all** — every surviving step emits free text or a sink record to stdout. The ordering is kept rather than dissolved because what it protects is not specific to `/sdcard`: the dump is the run's most fragile output, it is worth more than the counters and dumps that follow it, and a mid-teardown cut should cost those and not it. The requirement therefore keeps its subject and moves its boundary for the second time — from the serialization step, to the action-history save, to the first free-text dump — which is exactly the portability the "first among writers" phrasing was chosen for.

**Why not a shutdown hook.** An idempotent JVM shutdown hook was the original design for this requirement and is explicitly **not** adopted, because on the measured failure path it recovers nothing. The constraint is not which signal reaches the JVM — it is that the destination is already gone. `Logger` writes only to `System.out`; the trace file is the host-side `adb` stdout opened by the harness; on timeout the harness sends `SIGKILL` to the `adb` process and closes that file. Anything the device process emits afterwards — from a hook or otherwise, and regardless of whether the device itself received `SIGTERM` — cannot reach the trace. There is no second sink: the `.logcat` sibling of a lossy run contains zero `APE` lines. A requirement written on the signal axis ("orderly termination yes, SIGKILL no") would describe a boundary that is not the operative one.

**Scope of the guarantee, stated honestly.** This requirement recovers runs whose teardown *began*. It does not recover a run killed before teardown — in the calibration corpus, 5 of the 338. No teardown-side mechanism can reach those, and this specification does not claim otherwise.

**Partial dumps are valid output.** Emitting first does not make the dump atomic: 3 of the 462 runs that do dump today are truncated mid-`UICOV-ACT`. A consumer SHALL treat a truncated final line as a partial dump of an otherwise valid run, not as a corrupt run, and SHALL NOT discard the complete lines that precede it.

The dump remains read-only with respect to tracker state (INV-COV-07). Because the teardown dump has exactly one call site, no idempotence flag is required. This says nothing about the optional per-state emission at LRU eviction that "UICoverageTracker — Coverage Dump" permits (`MAY additionally emit one line for a state at the moment that state is evicted`): that emission is a distinct, mid-run, per-state path, it is not part of the teardown dump this requirement orders, and it is not currently implemented.

#### Scenario: dump precedes the first teardown step that produces output

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` and `[APE-RV] UICOV-ACT` lines SHALL appear in the trace before any output produced by a later step of the teardown chain — both writers this scenario has ordered against in turn (the model serialization, then the action-history save) are deleted, so the boundary is now the first *surviving* producer of output, the `actionCounters` dump
- **AND** the `flushPendingStep` record written before the dump SHALL NOT count against the boundary (it is loss-bounding, not an artifact write)
- **AND** the dump SHALL be read-only (tracker counts unchanged by the emission)

#### Scenario: run cut during model serialization still has its dump

- **WHEN** a run is cut short after teardown has begun
- **THEN** the dump SHALL already be in the trace, because it precedes every writer of the chain
- **AND** the specific window this scenario was written for SHALL no longer exist: no `Save graph data to …` line, `sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js` or `action-history.log` is produced, so there is no file write left in the chain to be cut during. The protection strengthens rather than lapses — every write the dump used to race is gone, and the ordering still holds against whatever produces output first

#### Scenario: run killed before teardown has no dump

- **WHEN** the process is terminated before the teardown chain begins
- **THEN** no dump is emitted (documented limitation — the emission point was never reached; this is not a defect of the mechanism)

#### Scenario: truncated dump is a partial dump, not a corrupt run

- **WHEN** the capture ends mid-way through the `UICOV-ACT` block, leaving an incomplete final line
- **THEN** the run SHALL be treated as carrying a partial dump
- **AND** every complete line preceding the truncation SHALL remain usable
