## Purpose

This delta replaces the placeholder `openspec/specs/aperv-tool/spec.md` ("Not yet implemented") with a complete specification for the `aperv-tool` Python module.

`aperv-tool` integrates `ape-rv.jar` into the rv-android tool registry as the `aperv` plugin. It follows the same integration pattern as `rvsmart-tool` (external module, lazy-import registration) and mirrors the execution model of the builtin `ape` tool (ADB `app_process` invocation, `com.android.commands.monkey.Monkey` main class). The plugin ID `aperv` is distinct from the existing `ape` builtin, allowing both tools to coexist and be compared within the same experiment.

---

## ADDED Requirements

### Requirement: ApeRVTool Class Structure

`ApeRVTool` SHALL extend `AbstractTool` from `rv_android_core.tools.abstract_tool`. It SHALL declare a class-level `TOOL_SPEC` of type `ToolSpec` with:
- `name`: `"aperv"`
- `description`: human-readable string identifying it as the APE-RV exploration agent
- `url`: the ape-rv GitHub repository URL
- `version`: `"1.0.0"`
- `process_pattern`: `"com.android.commands.monkey"` (same as builtin `ape` — identifies the running process on device)

`ApeRVTool.__init__()` SHALL initialise a `JarResolver` instance and an empty `_tool_config` dict. It SHALL NOT perform JAR resolution or device communication at init time.

#### Scenario: Tool is instantiated
- **WHEN** `ApeRVTool()` is constructed
- **THEN** `self.name` SHALL equal `"aperv"`
- **AND** `self.jar_resolver` SHALL be a `JarResolver` instance
- **AND** no ADB command SHALL be issued during construction

---

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return a dict with the following variant names and their configuration:

| Variant | `strategy` | Notes |
|---------|-----------|-------|
| `default` | `sata` | Default variant; equivalent to `sata` |
| `sata` | `sata` | SATA heuristic — primary strategy |
| `random` | `random` | Priority-weighted random baseline |
| `bfs` | `bfs` | Breadth-first traversal |
| `sata_mop` | `sata` | SATA + MOP guidance (Phase 3); placeholder until Phase 3 is implemented |

All variants SHALL include a `throttle_ms` key (default `200`) matching `ape.defaultGUIThrottle`.

#### Scenario: Default variant resolved
- **WHEN** `ApeRVTool.get_variants()["default"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`

#### Scenario: sata_mop variant is present but Phase 3 not implemented
- **WHEN** `ApeRVTool.get_variants()["sata_mop"]` is accessed
- **THEN** the `strategy` value SHALL be `"sata"`
- **AND** a `mop_data` key SHALL be present with value `None` (placeholder until Phase 3 wires `ape.mopDataPath`)

---

### Requirement: configure() Method

`ApeRVTool.configure(config)` SHALL store the resolved variant configuration in `self._tool_config`. It SHALL validate that `config["strategy"]` is one of `["sata", "random", "bfs", "dfs"]`. If absent or invalid, it SHALL raise `ConfigurationError`.

#### Scenario: Valid strategy configured
- **WHEN** `configure({"strategy": "sata", "throttle_ms": 200})` is called
- **THEN** `self._tool_config["strategy"]` SHALL equal `"sata"`
- **AND** no exception SHALL be raised

#### Scenario: Invalid strategy raises ConfigurationError
- **WHEN** `configure({"strategy": "unknown"})` is called
- **THEN** `ConfigurationError` SHALL be raised with a message listing valid strategies

---

### Requirement: JAR Resolution

`ApeRVTool._resolve_jar_path()` SHALL locate `ape-rv.jar` using `JarResolver.resolve_jar_path("ape-rv.jar", search_paths)`. The search priority SHALL be:

1. `os.path.dirname(__file__)` — tool module directory (JAR placed here by `mvn install`)
2. `$RVSEC_HOME/ape/target/ape-rv.jar` — build output when `RVSEC_HOME` env var is set
3. `$TOOLS_DIR/aperv/ape-rv.jar` — explicit tools directory override

If `ape-rv.jar` is not found in any path, `RVToolExecutionError` SHALL be raised with a message listing the searched paths.

#### Scenario: JAR found in module directory
- **WHEN** `ape-rv.jar` exists at `os.path.dirname(__file__)/ape-rv.jar`
- **THEN** `_resolve_jar_path()` SHALL return that path without consulting other locations

#### Scenario: JAR not found anywhere
- **WHEN** `ape-rv.jar` is absent from all three search paths
- **THEN** `RVToolExecutionError` SHALL be raised
- **AND** the error message SHALL list the searched paths

---

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute)
3. Resolve `ape-rv.jar` via `_resolve_jar_path()`
4. Push `ape-rv.jar` to `/data/local/tmp/ape-rv.jar` via `adb push`
5. Optionally push `ape.properties` to `/data/local/tmp/ape.properties` if `_tool_config` contains non-empty properties (throttle, etc.)
6. Build and execute main command via `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, capturing stdout+stderr to `task.result.trace_file`
7. On `RVToolTimeoutError`: log as expected behaviour and re-raise (timeout is normal termination for exploration tools)

No health check step is required (APE has no `--health-check` flag unlike rvsmart).

#### Scenario: Successful execution push and run
- **WHEN** `execute_tool_specific_logic(task, app)` is called with valid configuration
- **THEN** `adb push` SHALL be invoked with `ape-rv.jar` as source and `/data/local/tmp/ape-rv.jar` as destination
- **AND** `adb shell CLASSPATH=...` SHALL be invoked with `-p <app.package_name>` and `--ape <strategy>`
- **AND** output SHALL be written to `task.result.trace_file`

#### Scenario: Timeout during exploration
- **WHEN** the exploration runs past `task.config.timeout` seconds
- **THEN** `RVToolTimeoutError` SHALL be re-raised after logging
- **AND** the trace file SHALL contain partial APE output written before the timeout

#### Scenario: JAR push fails
- **WHEN** `adb push` exits with non-zero code
- **THEN** `RVToolExecutionError` SHALL be raised with the exit code and any stderr output

---

### Requirement: rv-platform Registration

`rv-platform/src/rv_platform/__init__.py` SHALL register `ApeRVTool` in `_register_external_tools()` using the same lazy-import pattern as `rvsmart`:

```python
if not registry.is_tool_registered("aperv"):
    try:
        from aperv_tool.tools.aperv.tool import ApeRVTool
        registry.register_tool_class(ApeRVTool)
    except ImportError as e:
        logging.getLogger(__name__).warning(f"aperv tool not available: {e}")
    except Exception as e:
        logging.getLogger(__name__).error(f"Failed to register aperv tool: {e}")
```

Registration SHALL be guarded by `is_tool_registered("aperv")` to prevent double-registration if the module is imported multiple times. An `ImportError` (aperv-tool not installed) SHALL produce a WARNING, not an error — the tool is optional. Any other exception SHALL produce an ERROR.

#### Scenario: aperv-tool installed and importable
- **WHEN** rv-platform is imported and `aperv-tool` is installed in the uv workspace
- **THEN** `"aperv"` SHALL appear in `ToolRegistry.get_instance().list_tools()`
- **AND** `ToolRegistry.get_instance().get_tool_class("aperv")` SHALL return `ApeRVTool`

#### Scenario: aperv-tool not installed
- **WHEN** rv-platform is imported and `aperv-tool` is not installed
- **THEN** a WARNING SHALL be logged: `"aperv tool not available: ..."`
- **AND** no exception SHALL propagate from the import block
- **AND** other tools (ape, rvsmart, rvagent) SHALL be unaffected

---

### Requirement: uv Workspace Declaration

`aperv-tool/pyproject.toml` SHALL declare the package as a uv workspace member compatible with rv-android's `members = ["modules/*"]` discovery. It SHALL declare dependencies on `rv-android-core` and `rv-tools` as workspace sources.

The `[project.entry-points."rv_tools.plugins"]` table SHALL NOT be used for `aperv-tool` — registration is done explicitly in `rv-platform/__init__.py`, not via entry-point auto-discovery, matching the `rvsmart-tool` pattern.

#### Scenario: Module added to workspace
- **WHEN** `aperv-tool/` exists under `modules/` in the rv-android root
- **THEN** `uv sync` SHALL include `aperv-tool` in the workspace without any change to the root `pyproject.toml`

---

## ADDED Invariants

- **INV-APERV-01**: `ApeRVTool.name` SHALL always equal `"aperv"`. This string is the registry key used by rv-platform, rv-experiment, and the CLI `--tools` flag. It MUST NOT be changed after registration.
- **INV-APERV-02**: The device JAR path SHALL always be `/data/local/tmp/ape-rv.jar`. The host JAR name is `ape-rv.jar`. These paths MUST match the `mvn install` copy target in `ape/pom.xml`.
- **INV-APERV-03**: `configure()` MUST be called before `execute_tool_specific_logic()`. The latter SHALL raise `RVToolExecutionError` if `self._tool_config` is empty at execution time.
- **INV-APERV-04**: Timeout is ALWAYS controlled by `task.config.timeout` (set by rv-platform). The `running_minutes` passed to APE is derived from `task.config.timeout / 60`. Variants MUST NOT hardcode a timeout.

---

## ADDED Data Contracts

### Input

- `task.config.device_id: str` — ADB serial for the target device (default `"emulator-5554"`)
- `task.config.timeout: int` — execution timeout in seconds (set by rv-platform; default `300`)
- `app.package_name: str` — Android package name passed to APE's `-p` flag
- `ape-rv.jar` — Dalvik JAR produced by `mvn package` in the ape repo; resolved at execution time

### Output

- `task.result.trace_file: file` — APE stdout+stderr captured to this path; contains APE's structured log including `## Network stats` and strategy counters
- No structured metrics file (APE does not emit a `APERV_METRICS:` line); metrics are extracted from the trace file by the rv-android analysis pipeline if needed

### Error

- `ConfigurationError` — invalid or missing `strategy` in `configure()`
- `RVToolExecutionError` — JAR not found, ADB push failure, or unexpected execution failure
- `RVToolTimeoutError` — expected termination; exploration ran to timeout; re-raised so rv-platform records it correctly
