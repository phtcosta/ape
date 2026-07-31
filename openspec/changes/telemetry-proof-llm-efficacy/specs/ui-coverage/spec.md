## ADDED Requirements

### Requirement: Coverage Dump Emitted Before Model Serialization

The coverage dump (the per-state `[APE-RV] UICOV` and per-Activity `[APE-RV] UICOV-ACT` emissions specified by "UICoverageTracker — Coverage Dump" and "UICoverageTracker — Per-Activity Coverage Dump") SHALL be emitted as the **first** step of the agent teardown chain, before the model serialization step (`saveGraph`) and before every other teardown step that writes to the device filesystem.

Today it is the **last** step: `StatefulAgent.tearDown()` runs `llmSummary → superTearDown → saveGraph → saveActionHistory → actionCounters → activityNodes → namingDump → modelCounters`, and only then does `SataAgent.tearDown()` reach `getCoverageTracker().dump(...)`. The most fragile item is emitted after the most expensive write.

The consequence is measured: **338 of the 800 `aperv` calibration runs (42.3%) carry no dump**, and **330 of those 338 end on the line `Save graph data to /sdcard/sata-…`** — cut *during* the `/sdcard` serialization, three steps before the dump would have run. A further 3 are cut after `saveGraph` but before the dump. Emitting the dump first recovers **333 of the 338**.

**Why not a shutdown hook.** An idempotent JVM shutdown hook was the original design for this requirement and is explicitly **not** adopted, because on the measured failure path it recovers nothing. The constraint is not which signal reaches the JVM — it is that the destination is already gone. `Logger` writes only to `System.out`; the trace file is the host-side `adb` stdout opened by the harness; on timeout the harness sends `SIGKILL` to the `adb` process and closes that file. Anything the device process emits afterwards — from a hook or otherwise, and regardless of whether the device itself received `SIGTERM` — cannot reach the trace. There is no second sink: the `.logcat` sibling of a lossy run contains zero `APE` lines. A requirement written on the signal axis ("orderly termination yes, SIGKILL no") would describe a boundary that is not the operative one.

**Scope of the guarantee, stated honestly.** This requirement recovers runs whose teardown *began*. It does not recover a run killed before teardown — in the calibration corpus, 5 of the 338. No teardown-side mechanism can reach those, and this specification does not claim otherwise.

**Partial dumps are valid output.** Hoisting the emission does not make it atomic: 3 of the 462 runs that do dump today are truncated mid-`UICOV-ACT`. A consumer SHALL treat a truncated final line as a partial dump of an otherwise valid run, not as a corrupt run, and SHALL NOT discard the complete lines that precede it.

The dump remains read-only with respect to tracker state (INV-COV-07). Because there is exactly one call site, no idempotence flag is required.

#### Scenario: dump precedes model serialization

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` and `[APE-RV] UICOV-ACT` lines SHALL appear in the trace **before** the `Save graph data to …` line
- **AND** the dump SHALL be read-only (tracker counts unchanged by the emission)

#### Scenario: run cut during model serialization still has its dump

- **WHEN** the harness terminates the process while `saveGraph` is writing to `/sdcard`, so the trace's last line is `Save graph data to /sdcard/sata-…`
- **THEN** the trace SHALL nonetheless contain the complete coverage dump, emitted earlier in the chain

#### Scenario: run killed before teardown has no dump

- **WHEN** the process is terminated before the teardown chain begins
- **THEN** no dump is emitted (documented limitation — the emission point was never reached; this is not a defect of the mechanism)

#### Scenario: truncated dump is a partial dump, not a corrupt run

- **WHEN** the capture ends mid-way through the `UICOV-ACT` block, leaving an incomplete final line
- **THEN** the run SHALL be treated as carrying a partial dump
- **AND** every complete line preceding the truncation SHALL remain usable

## Invariants

- **INV-COV-10**: The coverage dump SHALL be emitted exactly once per run, from the first step of the teardown chain, strictly before the model-serialization step; the emission SHALL be read-only (INV-COV-07 applies). No second emission path exists, so no cross-path idempotence guard is required.
