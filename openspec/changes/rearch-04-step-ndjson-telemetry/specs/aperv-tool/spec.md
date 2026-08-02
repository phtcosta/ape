# aperv-tool Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the Python side gains exactly two post-run, write-only steps — a **temporary** NDJSON→legacy-format converter that keeps every current rv-platform/analysis parser working during the migration, and **gzip at collection** for storage at rest. Per owner decision D5 this is the *entire* Python scope: no sentinel or exit-code validation, no `RUN_END` reading, no task-status changes, no retry logic. Truncated-run detection remains post-hoc analysis over trace/logcat timestamps, exactly as today. Both new steps are non-fatal: their failure logs a warning and leaves the raw trace in place.

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
8. On `RVCommandTimeoutError`: log as expected behaviour, run the collection steps 10–11 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
9. Run the empty-trace check (`_check_empty_trace`, unchanged — a 0-byte NDJSON trace is still 0 bytes)
10. **Gzip at collection**: compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue
11. **Temporary legacy conversion**: run the NDJSON→legacy converter over the raw capture and write the reconstructed legacy line family (`[APE-STEP]`, `[APE-OUTCOME]`, `[APE-LLM-TEL]`, `[APE-LLM-ERROR]`, `[APE-LLM-CONFIG]`, `[APE-LLM-CONFIG-ACK]`, `[APE-MOP-DATA]`, `[APE-RV] LLM Summary` / `Decision ratio`) to `task.result.trace_file` itself, so existing parsers keep reading exactly the file and format they read today. On failure, log a WARNING and leave the raw NDJSON trace in place

Steps 10–11 SHALL NOT inspect, validate, or act on the trace's content beyond mechanical transformation: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). The converter SHALL reconstruct legacy semantics mechanically: expand `act`/`st` dictionary IDs to activity/state strings, re-emit omitted defaults (`mop=0 mop_frontier=0 wtg=0 coverage=0 menu=0 form=0`, `new_state=false`, `activity_changed=false`), re-derive `activity_has_mop` from the `ACT` entries, re-expand `t` to epoch `clock=` via `RUN_START.t0`, split `llm[]` sub-events back into per-call lines keyed by the record's `s`, and expand `RUN_END.counters` into the summary lines. The converter is temporary: it is deleted when the analysis pipeline consumes NDJSON natively (a later change; not triggered here).

No health check step is required (APE has no `--health-check` flag).

#### Scenario: Post-run conversion keeps current parsers working

- **WHEN** a run completes and the captured `task.result.trace_file` contains NDJSON records
- **THEN** after step 11, `task.result.trace_file` SHALL contain the legacy `key=value` line family reconstructing every field current parsers consume
- **AND** `<trace>.ndjson.gz` SHALL contain the compressed raw NDJSON capture

#### Scenario: Converter failure is non-fatal and write-only

- **WHEN** the converter raises on a malformed line
- **THEN** a WARNING SHALL be logged, the raw NDJSON trace SHALL remain at `task.result.trace_file`, and the task SHALL complete with the same status it would have had otherwise (D5: no validation, no status logic)

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
- **THEN** steps 10–11 SHALL run on the trace captured up to the kill (gzip + legacy conversion), and only then SHALL `RVToolTimeoutError` be re-raised
- **AND** the converted trace SHALL contain the partial legacy output for the steps completed before the timeout

#### Scenario: JAR push fails

- **WHEN** `adb push` exits with non-zero code
- **THEN** `RVToolExecutionError` SHALL be raised with the exit code and any stderr output
