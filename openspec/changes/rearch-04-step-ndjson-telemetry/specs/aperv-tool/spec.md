# aperv-tool Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`. On the **collection** path the Python side gains exactly one post-run, write-only step: **gzip at collection** for storage at rest. The captured `.trace` is the NDJSON stream and is never rewritten — there is no NDJSON→legacy converter, because reconstructing the unescaped `key=value` family over the primary artifact would make the file everyone opens a derived reconstruction, hide the real records in a sidecar nobody reads, and reintroduce the line-breaking defect class the sink's escaping guarantee (INV-SNK-01/02) exists to eliminate.

On the **analysis** path the module gains a native NDJSON reader, and its one production consumer of the legacy `[APE-STEP]` family — `clock_logcat_join.py` — migrates onto it here. That migration is a net deletion: the D4 logcat heartbeat retires the module's UTC-offset reconstruction.

Per owner decision D5 the collection scope stops there: no sentinel or exit-code validation, no `RUN_END` reading, no task-status changes, no retry logic. Truncated-run detection remains post-hoc analysis over trace/logcat timestamps, exactly as today. The gzip step is non-fatal: its failure logs a warning and leaves the uncompressed trace in place.

## MODIFIED Requirements

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute)
3. Resolve `ape-rv.jar` via `_resolve_jar_path()`
4. Push `ape-rv.jar` to `/data/local/tmp/ape-rv.jar` via `adb push`
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`; if found, push to `/data/local/tmp/static_analysis.json` and set `mop_json_pushed = True`; if not found, log WARNING and set `mop_json_pushed = False`
6. Push `ape.properties` to `/data/local/tmp/ape.properties`; when `mop_json_pushed`, include `ape.mopDataPath=/data/local/tmp/static_analysis.json` in the content
7. Build and execute main command via `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, capturing stdout+stderr to `task.result.trace_file` (the captured stream is now the NDJSON trace)
8. On `RVCommandTimeoutError`: log as expected behaviour, run the collection step 10 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
9. Run the empty-trace check (`_check_empty_trace`, unchanged — a 0-byte NDJSON trace is still 0 bytes)
10. **Gzip at collection**: compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue

Step 10 SHALL NOT inspect, validate, or act on the trace's content: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). `task.result.trace_file` SHALL remain the raw NDJSON capture, byte-for-byte, after collection completes — no step of this flow rewrites, reformats, or truncates it, and no NDJSON→legacy conversion step exists.

No health check step is required (APE has no `--health-check` flag).

#### Scenario: Collection leaves the NDJSON trace intact

- **WHEN** a run completes and the captured `task.result.trace_file` contains NDJSON records
- **THEN** after step 10, `task.result.trace_file` SHALL still hold exactly the captured NDJSON records, unmodified
- **AND** `<trace>.ndjson.gz` SHALL contain the compressed copy
- **AND** no legacy `[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-TEL]` line SHALL have been written anywhere by the tool

#### Scenario: Gzip failure is non-fatal and write-only

- **WHEN** compression raises
- **THEN** a WARNING SHALL be logged, the uncompressed NDJSON trace SHALL remain at `task.result.trace_file`, and the task SHALL complete with the same status it would have had otherwise (D5: no validation, no status logic)

#### Scenario: No exit contract

- **WHEN** a trace ends without a `RUN_END` record (e.g. SIGKILL on timeout before teardown)
- **THEN** the tool SHALL NOT detect, log, or act on its absence
- **AND** truncated-run identification remains a post-hoc analysis over trace/logcat timestamps

#### Scenario: sata_mop — JSON present

- **WHEN** `execute_tool_specific_logic` is called with `sata_mop` variant AND `_find_static_analysis_file(task)` returns a valid path
- **THEN** `_push_file_to_device(static_json, "/data/local/tmp/static_analysis.json", device_serial, task.result.trace_file)` SHALL be called
- **AND** `ape.properties` SHALL contain `ape.mopDataPath=/data/local/tmp/static_analysis.json`

#### Scenario: sata_mop — JSON absent

- **WHEN** `execute_tool_specific_logic` is called with `sata_mop` variant AND no JSON file is found
- **THEN** a WARNING SHALL be logged: `"sata_mop: static analysis file not found in results_dir, running without MOP data"`
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`
- **AND** execution SHALL continue (APE runs as plain `sata`)

#### Scenario: sata variant — no JSON push

- **WHEN** `execute_tool_specific_logic` is called with `sata` variant
- **THEN** no JSON file SHALL be pushed to the device
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`

#### Scenario: Timeout during exploration

- **WHEN** the exploration runs past `task.config.timeout` seconds
- **THEN** step 10 SHALL run on the trace captured up to the kill (gzip), and only then SHALL `RVToolTimeoutError` be re-raised
- **AND** the trace SHALL contain the NDJSON records written before the timeout, including the truncated final line if the kill landed mid-write

#### Scenario: JAR push fails

- **WHEN** `adb push` exits with non-zero code
- **THEN** `RVToolExecutionError` SHALL be raised with the exit code and any stderr output

## ADDED Requirements

### Requirement: Native NDJSON Trace Reader

The module SHALL provide `aperv_tool/analysis/trace_ndjson.py`, a read-only reader of the NDJSON trace, and it SHALL be the sole mechanism by which analysis code consumes a post-change trace. The reader SHALL stream the file and yield one typed row per step, having already:

- resolved `act` / `st` integer references against the `ACT` / `STATE` dictionary records;
- materialized the fields the sink omits at their defaults (`mop`, `mop_frontier`, `wtg`, `coverage`, `menu`, `form` at `0`; `new_state` and `activity_changed` at `false`);
- re-derived `activity_has_mop` on the step side from the record's `ACT` entry and on the outcome side via `out.target` → `STATE.act` → `ACT.mop`;
- expanded the run-relative `t` to epoch milliseconds via `RUN_START.t0`, where an absolute clock is wanted;
- attached the step's `llm[]` sub-events and its `out` section to the same row, so the three-way join by `step=` no longer exists for any consumer.

The reader SHALL NOT write to the trace, SHALL NOT emit legacy `[APE-*]` lines, and SHALL NOT run on the collection path — it is an analysis-time component. A malformed record SHALL be skipped and counted in the reader's own diagnostics rather than aborting the read.

`clock_logcat_join.py` SHALL be migrated onto this reader in this change. Its `[APE-STEP]` regex and its entire UTC-offset reconstruction — the year-candidate search, the quarter-hour rounding, the anchor selection, and `alignment_residual_ms` — SHALL be deleted, not disabled: the D4 logcat heartbeat places step and violation on the same clock, so the reconstruction has nothing left to reconstruct.

#### Scenario: Reader yields a joined step row

- **WHEN** the reader is run over a trace whose step 42 carries a `dec` with no boosts, two `llm[]` sub-events, and an `out` closed at step 43
- **THEN** it SHALL yield one row for step 42 carrying the resolved activity and state strings, `mop=0 wtg=0 coverage=0 menu=0 form=0`, both LLM sub-events in occurrence order, and the outcome fields
- **AND** no second pass over the trace SHALL be required to join them

#### Scenario: Join runs without offset reconstruction

- **WHEN** `clock_logcat_join.py` joins a new-format trace against a logcat containing the D4 heartbeat lines
- **THEN** the step↔violation join SHALL be computed directly from the shared logcat clock
- **AND** the module SHALL contain no UTC-offset reconstruction, no year-candidate search, and no `alignment_residual_ms`

### Requirement: Frozen Legacy-Corpus Readers Are Not Migrated

The archived legacy corpus — the traces behind the 2026-07-24 calibration report and the decisive run — will not be regenerated, so the scripts that read it SHALL keep parsing the legacy `[APE-*]` `key=value` format and SHALL NOT be migrated, adapted, or deleted by this change: `scripts/cmpm_stratify.py`, `scripts/analyze_cmpv2_llm.py`, `experimento-cal/scripts/*`, `experimento-20260721/scripts/*`, and `calibracao/*`.

This carve-out is normative and stated here so that it is not mistaken for a P3 violation. Those scripts are not compatibility shims keeping a superseded implementation alive for new data; they are the readers of a dataset that is finished. P3 governs superseded implementation, not analysis code over frozen data. The distinction is operational: `clock_logcat_join.py` migrates because it must read *new* traces; these do not, because they never will.

`coverage_dump.py` is likewise untouched — it reads only the `[APE-RV] UICOV` / `UICOV-ACT` lines, which this change does not modify.

#### Scenario: A frozen-corpus script keeps its legacy parser

- **WHEN** the change is applied and every jar-side emitter of the legacy line family is gone
- **THEN** `scripts/cmpm_stratify.py` and the `experimento-cal` / `experimento-20260721` / `calibracao` scripts SHALL be unchanged
- **AND** they SHALL still parse the archived legacy traces they were written for
