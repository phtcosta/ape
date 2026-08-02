# ui-coverage Delta Specification

## Purpose

Delta for `rearch-02-runspec`. The coverage-dump ordering requirement (INV-COV-10) is **preserved in substance and re-anchored in form**. Its pre-change normative boundary was the model-serialization step — "the dump SHALL be emitted before `saveGraph`" — and this change deletes `saveGraph`, `readGraph`, `sataModel.obj` and the `--ape-model` protocol entirely (report Sec. 6.6, R1/R3). A requirement stated on a boundary that no longer exists is unverifiable, so the boundary moves from *the serialization step* to *the first writer of the teardown chain*, which is what the property actually protects: the most fragile artifact must not queue behind the expensive ones.

Nothing about the guarantee changes: the dump is still emitted before anything else in teardown writes, it is still read-only with respect to tracker state, its measured recovery is still the reason it exists, and its honest limits (runs killed before teardown; partial dumps) are restated verbatim. The chain this delta references is the post-change one recorded by this change's `exploration` delta (INV-EXPL-29 with the graph-save step removed).

## Invariants

- **INV-COV-10 (re-anchored, not dissolved)**: the coverage dump MUST precede every other teardown step that writes output. The pre-change phrasing bound it to `saveGraph`; with the persistence protocol deleted, the binding is to chain position among writers. `rearch-04-step-ndjson-telemetry` removes `saveActionHistory` from the same chain and adds `flushPendingStep`/`RUN_END`; the dump SHALL continue to precede both.

## RENAMED Requirements

- FROM: `### Requirement: Coverage Dump Emitted Before Model Serialization`
- TO: `### Requirement: Coverage Dump Emitted First Among Teardown Writers`

## MODIFIED Requirements

### Requirement: Coverage Dump Emitted First Among Teardown Writers

The coverage dump (the per-state `[APE-RV] UICOV` and per-Activity `[APE-RV] UICOV-ACT` emissions specified by "UICoverageTracker — Coverage Dump" and "UICoverageTracker — Per-Activity Coverage Dump") SHALL be emitted **before any other teardown step that produces output**.

The boundary is "first among writers", not a fixed index. Before this change the boundary was the model serialization step (`saveGraph`), which this change deletes together with `sataModel.obj`, `readGraph` and `--ape-model` (V14, finding 3.3-6; R1/R3 — clean runs, no read-back). The post-change chain is `llmSummary → superTearDown → coverage dump → action-history save → action counters → activity nodes → naming dump → model counters` (this change's `exploration` delta, INV-EXPL-29): the dump lands third, and the two steps preceding it write nothing. Stating the requirement on "first among writers" keeps it aligned with what recovers the lost dumps and with what the tests assert, and keeps it verifiable as later stages remove further steps from the chain — a requirement written on a chain index would break at stage 4.

Before the fix that introduced this requirement, the dump was the **last** step: `StatefulAgent.tearDown()` ran its full chain and only then did `SataAgent.tearDown()` reach `getCoverageTracker().dump(...)`. The most fragile item was emitted after the most expensive write.

The consequence is measured: **338 of the 800 `aperv` calibration runs (42.3%) carry no dump**, and **330 of those 338 end on the line `Save graph data to /sdcard/sata-…`** — cut *during* the `/sdcard` serialization, three steps before the dump would have run. A further 3 are cut after that write but before the dump. Emitting the dump first recovers **333 of the 338**. This measurement is the historical justification for the ordering and is retained as the record of why the requirement exists; the specific write it names is deleted by this change, which removes that failure mode at its source rather than weakening the ordering.

**Why not a shutdown hook.** An idempotent JVM shutdown hook was the original design for this requirement and is explicitly **not** adopted, because on the measured failure path it recovers nothing. The constraint is not which signal reaches the JVM — it is that the destination is already gone. `Logger` writes only to `System.out`; the trace file is the host-side `adb` stdout opened by the harness; on timeout the harness sends `SIGKILL` to the `adb` process and closes that file. Anything the device process emits afterwards — from a hook or otherwise, and regardless of whether the device itself received `SIGTERM` — cannot reach the trace. There is no second sink: the `.logcat` sibling of a lossy run contains zero `APE` lines. A requirement written on the signal axis ("orderly termination yes, SIGKILL no") would describe a boundary that is not the operative one.

**Scope of the guarantee, stated honestly.** This requirement recovers runs whose teardown *began*. It does not recover a run killed before teardown — in the calibration corpus, 5 of the 338. No teardown-side mechanism can reach those, and this specification does not claim otherwise.

**Partial dumps are valid output.** Emitting first does not make the dump atomic: 3 of the 462 runs that do dump today are truncated mid-`UICOV-ACT`. A consumer SHALL treat a truncated final line as a partial dump of an otherwise valid run, not as a corrupt run, and SHALL NOT discard the complete lines that precede it.

The dump remains read-only with respect to tracker state (INV-COV-07). Because the teardown dump has exactly one call site, no idempotence flag is required. This says nothing about the optional per-state emission at LRU eviction that "UICoverageTracker — Coverage Dump" permits (`MAY additionally emit one line for a state at the moment that state is evicted`): that emission is a distinct, mid-run, per-state path, it is not part of the teardown dump this requirement orders, and it is not currently implemented.

#### Scenario: dump precedes every other teardown writer

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` and `[APE-RV] UICOV-ACT` lines SHALL appear in the trace before any output produced by a later step of the teardown chain
- **AND** the dump SHALL be read-only (tracker counts unchanged by the emission)

#### Scenario: no graph artifact exists to order against

- **WHEN** a run completes normally after this change
- **THEN** no `Save graph data to …` line, `sataModel.obj`, `sataGraph.dot` or `sataGraph.vis.js` SHALL exist
- **AND** the ordering requirement SHALL still be verifiable against the first surviving writer of the chain

#### Scenario: run killed before teardown has no dump

- **WHEN** the process is terminated before the teardown chain begins
- **THEN** no dump is emitted (documented limitation — the emission point was never reached; this is not a defect of the mechanism)

#### Scenario: truncated dump is a partial dump, not a corrupt run

- **WHEN** the capture ends mid-way through the `UICOV-ACT` block, leaving an incomplete final line
- **THEN** the run SHALL be treated as carrying a partial dump
- **AND** every complete line preceding the truncation SHALL remain usable
