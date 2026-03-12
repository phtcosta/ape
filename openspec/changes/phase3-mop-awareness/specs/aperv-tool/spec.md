## MODIFIED Requirements

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return a dict with the following variant names and their configuration:

| Variant | `strategy` | Notes |
|---------|-----------|-------|
| `default` | `sata` | Default variant; equivalent to `sata` |
| `sata` | `sata` | SATA heuristic — primary strategy |
| `random` | `random` | Priority-weighted random baseline |
| `bfs` | `bfs` | Breadth-first traversal |
| `sata_mop` | `sata` | SATA + MOP guidance — pushes static analysis JSON and sets `ape.mopDataPath` |

All variants SHALL include a `throttle_ms` key (default `200`) matching `ape.defaultGUIThrottle`.

The `sata_mop` variant SHALL declare `"mop_data": "static_analysis"`. This replaces the Phase 4 placeholder value of `None`. When `mop_data == "static_analysis"`, `execute_tool_specific_logic()` SHALL push the static analysis JSON to the device before launching APE.

#### Scenario: Default variant resolved
- **WHEN** `ApeRVTool.get_variants()["default"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`

#### Scenario: sata_mop variant is wired (replaces Phase 4 placeholder)
- **WHEN** `ApeRVTool.get_variants()["sata_mop"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`
- **AND** the `mop_data` key SHALL be `"static_analysis"` (not `None`)

---

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute)
3. Resolve `ape-rv.jar` via `_resolve_jar_path()`
4. Push `ape-rv.jar` to `/data/local/tmp/ape-rv.jar` via `adb push`
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`; if found, push to `/data/local/tmp/static_analysis.json` and set `mop_json_pushed = True`; if not found, log WARNING and set `mop_json_pushed = False`
6. Push `ape.properties` to `/data/local/tmp/ape.properties`; when `mop_json_pushed`, include `ape.mopDataPath=/data/local/tmp/static_analysis.json` in the content
7. Build and execute main command via `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, capturing stdout+stderr to `task.result.trace_file`
8. On `RVToolTimeoutError`: log as expected behaviour and re-raise

No health check step is required (APE has no `--health-check` flag).

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
