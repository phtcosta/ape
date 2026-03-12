## MODIFIED Requirements

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return a dict with the following variant names and their configuration:

| Variant | `strategy` | Notes |
|---------|-----------|-------|
| `default` | `sata` | Default variant; equivalent to `sata` |
| `sata` | `sata` | SATA heuristic — primary strategy |
| `random` | `random` | Priority-weighted random baseline |
| `bfs` | `bfs` | Breadth-first traversal |
| `sata_mop` | `sata` | SATA + MOP guidance; pushes static analysis JSON and sets `ape.mopDataPath` |

All variants SHALL include a `throttle_ms` key (default `200`) matching `ape.defaultGUIThrottle`.

The `sata_mop` variant SHALL declare `"mop_data": "static_analysis"` (replacing the Phase 4 placeholder `None`) to signal that `execute_tool_specific_logic()` must push the static analysis JSON to the device.

#### Scenario: Default variant resolved
- **WHEN** `ApeRVTool.get_variants()["default"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`

#### Scenario: sata_mop variant is wired
- **WHEN** `ApeRVTool.get_variants()["sata_mop"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`
- **AND** the `mop_data` key SHALL be `"static_analysis"` (not `None`)

---

## MODIFIED Requirements

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute)
3. Resolve `ape-rv.jar` via `_resolve_jar_path()`
4. Push `ape-rv.jar` to `/data/local/tmp/ape-rv.jar` via `adb push`
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate static analysis JSON in `task.results_dir/<apk_name>.json` and push to `/data/local/tmp/static_analysis.json`; if file absent, log WARNING and continue without MOP data
6. Push `ape.properties` to `/data/local/tmp/ape.properties`; if `mop_data == "static_analysis"` and JSON was pushed, include `ape.mopDataPath=/data/local/tmp/static_analysis.json` in the properties content
7. Build and execute main command via `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, capturing stdout+stderr to `task.result.trace_file`
8. On `RVToolTimeoutError`: log as expected behaviour and re-raise

No health check step is required (APE has no `--health-check` flag).

#### Scenario: sata_mop — JSON present
- **WHEN** `execute_tool_specific_logic` is called with `sata_mop` variant AND `task.results_dir/<apk_name>.json` exists
- **THEN** `adb push` SHALL be called for the JSON file to `/data/local/tmp/static_analysis.json`
- **AND** `ape.properties` SHALL contain `ape.mopDataPath=/data/local/tmp/static_analysis.json`

#### Scenario: sata_mop — JSON absent
- **WHEN** `execute_tool_specific_logic` is called with `sata_mop` variant AND no JSON file is found in `task.results_dir`
- **THEN** a WARNING SHALL be logged: `"sata_mop: static analysis file not found, running without MOP data"`
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`
- **AND** execution SHALL continue (APE runs as plain `sata`)

#### Scenario: sata variant — no JSON push
- **WHEN** `execute_tool_specific_logic` is called with `sata` variant
- **THEN** no JSON file SHALL be pushed to the device
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`
