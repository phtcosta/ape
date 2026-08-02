# Delta: aperv-tool Plugin (rearch-07-compact-static-artifact)

## Purpose

This delta switches the static-analysis push path of `aperv-tool` from the full JSON to the **derived compact MOP artifact**. For MOP arms, `execute_tool_specific_logic()` now derives (or reuses from cache) `<apk_name>.mop.json` from the full JSON, pushes it to `/data/local/tmp/mop-artifact.json`, and points `ape.mopDataPath` at that path. The full static-analysis JSON is never pushed to the device again.

Two degradation classes die here. The **warn-and-continue** path (full JSON absent → warning → MOP arm silently runs without MOP guidance, report V21) is replaced by a raised `RVToolExecutionError`: a MOP arm that cannot arm is a failed task, loud on the host, symmetrical with the jar-side INV-MOP-22 abort. The **too-large** class (oversized JSON rejected by the jar's parse-footprint guard, arms aborting with 0 steps on call-graph-heavy apps) loses its trigger: the pushed artifact is the explorer-shaped projection, bounded by construction, and the jar-side guard is deleted (mop-guidance delta). Consequently `_compact_static_analysis_json` — the lossless minify/dedup/enrich shim that existed only to clear that guard — is deleted and replaced by the real derivation (`derive_mop_artifact.py`, specified in the `static-analysis-entrypoints` delta).

This change lands after `rearch-05-thin-python-arms` and edits the same `tool.py` execute path; non-MOP arms are untouched.

## Data Contracts

### Input
- `<task.results_dir>/<apk_name>.json` — full static-analysis JSON (located by `_find_static_analysis_file`, unchanged).
- `<task.results_dir>/<apk_name>.mop.json` — cached derived artifact (may not exist yet; regenerated when stale).

### Output
- `/data/local/tmp/mop-artifact.json` on the device (MOP arms only).
- `ape.properties` line `ape.mopDataPath=/data/local/tmp/mop-artifact.json` (MOP arms with a successful push only).

### Error
- `RVToolExecutionError` — MOP arm with a missing full JSON, a failed derivation, or a failed push. Non-MOP arms are unaffected by this section entirely.

## Invariants

- **INV-APERV-05**: An arm configured with `mop_data == "static_analysis"` SHALL either push a freshly-validated derived artifact and set `ape.mopDataPath`, or fail the task before launching the jar. There SHALL be no execution path in which such an arm launches the jar without `ape.mopDataPath` set.
- **INV-APERV-06**: The device SHALL only ever receive the derived compact artifact; no code path pushes the full static-analysis JSON.
- **INV-APERV-07**: Cache freshness is digest-based: a cached `<apk_name>.mop.json` SHALL be reused only when its recorded `source.digest` equals the SHA-256 of the current full JSON; otherwise it SHALL be regenerated. Cache state SHALL never change what the device receives for a given full JSON (determinism, INV-DRV-05).

## MODIFIED Requirements

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute)
3. Resolve `ape-rv.jar` via `_resolve_jar_path()`
4. Push `ape-rv.jar` to `/data/local/tmp/ape-rv.jar` via `adb push`
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`. **If not found, raise `RVToolExecutionError`** naming the expected path — a MOP arm without its static-analysis input is a failed task, never a silently degraded run. If found, obtain the derived artifact via `_derive_mop_artifact(task)` (cache-or-generate, see the added requirement below; a `DerivationError` also raises `RVToolExecutionError`), push it to `/data/local/tmp/mop-artifact.json`, and set `mop_json_pushed = True`
6. Push `ape.properties` to `/data/local/tmp/ape.properties`; when `mop_json_pushed`, include `ape.mopDataPath=/data/local/tmp/mop-artifact.json` in the content
7. Build and execute main command via `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, capturing stdout+stderr to `task.result.trace_file`
8. On `RVToolTimeoutError`: log as expected behaviour and re-raise

No health check step is required (APE has no `--health-check` flag).

#### Scenario: MOP arm — artifact derived and pushed
- **WHEN** `execute_tool_specific_logic` is called with a `mop_data == "static_analysis"` arm AND `_find_static_analysis_file(task)` returns a valid path
- **THEN** `_derive_mop_artifact(task)` SHALL return the cached-or-generated `<apk_name>.mop.json`
- **AND** it SHALL be pushed to `/data/local/tmp/mop-artifact.json`
- **AND** `ape.properties` SHALL contain `ape.mopDataPath=/data/local/tmp/mop-artifact.json`

#### Scenario: MOP arm — full JSON absent fails the task
- **WHEN** `execute_tool_specific_logic` is called with a `mop_data == "static_analysis"` arm AND no full JSON is found in `task.results_dir`
- **THEN** `RVToolExecutionError` SHALL be raised naming the expected path
- **AND** the jar SHALL NOT be launched
- **AND** no warning-and-continue path SHALL exist (the former "running without MOP data" behavior is deleted)

#### Scenario: MOP arm — derivation failure fails the task
- **WHEN** the full JSON exists but `derive` raises `DerivationError` (e.g., `complete != true`)
- **THEN** `RVToolExecutionError` SHALL be raised carrying the derivation error
- **AND** no partial artifact SHALL be pushed

#### Scenario: non-MOP arm — no static-analysis interaction
- **WHEN** `execute_tool_specific_logic` is called with an arm whose config lacks `mop_data`
- **THEN** no derivation SHALL run, no artifact SHALL be pushed
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`

#### Scenario: Timeout during exploration
- **WHEN** the exploration runs past `task.config.timeout` seconds
- **THEN** `RVToolTimeoutError` SHALL be re-raised after logging
- **AND** the trace file SHALL contain partial APE output written before the timeout

#### Scenario: JAR push fails
- **WHEN** `adb push` exits with non-zero code
- **THEN** `RVToolExecutionError` SHALL be raised with the exit code and any stderr output

---

## ADDED Requirements

### Requirement: Derived artifact generation and caching

`ApeRVTool._derive_mop_artifact(task)` SHALL return the host path of the compact MOP artifact for the task's APK, generating it when needed:

1. Compute the SHA-256 of the current full JSON (`<apk_name>.json`).
2. If `<apk_name>.mop.json` exists next to it AND its `source.digest` matches, reuse it (cache hit).
3. Otherwise call `derive_mop_artifact.derive` + `serialize_canonical` and write `<apk_name>.mop.json` atomically (write-temp-then-rename); a failed derivation writes nothing.

The artifact is cached in `task.results_dir` — inspectable and diffable next to its source — and is a pure function of the full JSON (INV-APERV-07, INV-DRV-05). This method replaces `_compact_static_analysis_json`, which is **deleted** together with its fallback-to-source-push behavior: there is no longer any condition under which the full JSON is pushed (INV-APERV-06).

#### Scenario: cache hit skips derivation
- **WHEN** `<apk_name>.mop.json` exists with a `source.digest` matching the current full JSON
- **THEN** `_derive_mop_artifact` SHALL return it without regenerating

#### Scenario: stale cache regenerates
- **WHEN** the full JSON changed since the cached artifact was generated (digest mismatch)
- **THEN** `_derive_mop_artifact` SHALL regenerate and overwrite the cache
- **AND** the pushed bytes SHALL equal a fresh derivation of the current full JSON

#### Scenario: the full JSON is never pushed
- **WHEN** any arm executes, under any cache or error state
- **THEN** no `adb push` of `<apk_name>.json` (or any file containing `reachability[]`) SHALL occur (INV-APERV-06)
