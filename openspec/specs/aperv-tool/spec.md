# Specification: aperv-tool Plugin

## Purpose

`aperv-tool` integrates `ape-rv.jar` into the rv-android tool registry as the `aperv` plugin. It follows the same integration pattern as `rvsmart-tool` (external module, lazy-import registration) and mirrors the execution model of the builtin `ape` tool (ADB `app_process` invocation, `com.android.commands.monkey.Monkey` main class). The plugin ID `aperv` is distinct from the existing `ape` builtin, allowing both tools to coexist and be compared within the same experiment.

---
## Requirements
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

`ApeRVTool.get_variants()` SHALL return a mapping of frozen variant names to variant definitions, each of which SHALL consist of:

- `preset: str` — the name of a **jar-resident** preset (`rearch-02-runspec`: `aperv`, `mop`, `llm`, `llm_mop`), written to `ape.properties` as the `ape.preset` line. The jar, not Python, defines what a preset means; Python SHALL NOT mirror preset contents.
- `overrides: Dict[str, Any]` — only the deltas that distinguish this variant from its preset. A variant identical to its preset SHALL carry an empty dict. Ablations SHALL be expressed as named override sets, never as new presets.
- Python-only orchestration keys at top level — `strategy` (the `--ape` CLI flag), `mop_data`, `seed`, and the B3 pairing keys `expected_jar_git_sha`/`expected_jar_sha256` — which SHALL NOT be written to `ape.properties`.

No variant SHALL carry a full property expansion.

**The roster is not held in this repository.** Which variants exist, their frozen names, their preset assignments and their override deltas are owned by rv-android's `aperv` capability (`rv-android/openspec/specs/aperv/spec.md`) and maintained through that repository's own OpenSpec workflow. This requirement SHALL NOT enumerate variant names, and a reader needing the current roster SHALL consult that spec rather than this one.

This is a deliberate constraint on where the roster may be written, not an oversight. A variant name is the resume-identity key and the consolidation column key of the frozen corpus; it is retired and consolidated by campaign decisions that happen in rv-android. An enumeration maintained here can only be a copy, and a copy that drifts silently is worse than a pointer that is occasionally inconvenient — this requirement previously held such a copy, and it was wrong in two different eras before anyone noticed.

#### Scenario: Variant is preset plus deltas

- **WHEN** any variant returned by `get_variants()` is read
- **THEN** it SHALL carry a `preset` key naming a jar-resident preset and an `overrides` dict
- **AND** it SHALL NOT carry a full expansion of `ape.*` property keys

#### Scenario: Variant identical to its preset

- **WHEN** a variant whose configuration matches its preset exactly is read
- **THEN** its `overrides` dict SHALL be empty rather than restating the preset's keys

#### Scenario: Orchestration keys stay out of the properties file

- **WHEN** `ape.properties` is generated for any variant
- **THEN** `strategy`, `mop_data`, `seed`, `expected_jar_git_sha` and `expected_jar_sha256` SHALL NOT appear in it

#### Scenario: Default variant resolved

- **WHEN** this requirement is read for the list of available variants — including which name is the
  default and what it resolves to
- **THEN** it SHALL NOT contain one, and SHALL direct the reader to rv-android's `aperv` capability

#### Scenario: sata_mop variant is wired (replaces Phase 4 placeholder)

- **WHEN** a variant that needs the compacted static-analysis artifact is read
- **THEN** it SHALL carry `mop_data` as a top-level Python-only orchestration key, alongside its
  `preset` and `overrides`
- **AND** `mop_data` SHALL NOT appear in the generated `ape.properties`, which names the artifact
  through `ape.mopDataPath` instead
- **AND** which variants set it SHALL NOT be asserted here (see the preceding scenario)

---

### Requirement: configure() Method

`ApeRVTool.configure(config)` SHALL store the resolved variant configuration in `self._tool_config`. It SHALL validate that `config["strategy"]` is one of `["sata", "random"]`, that `config["preset"]` is present and non-empty, and that `config.get("overrides", {})` is a dict. If any check fails, it SHALL raise `ConfigurationError` before any device interaction.

The whitelist SHALL shrink from `["sata", "random", "bfs", "dfs"]` — the deletion `rearch-02-runspec` delegates to this stage. `bfs` and `dfs` were never agent types: `ApeAgent.createAgent` (`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:68-96`) recognizes exactly `sata`, `random` and `replay`, with every other value previously falling through silently to `new SataAgent` (verified V9). Accepting them Python-side would let a run pass local validation and abort on the device, reintroducing the silent-degradation class stage 2 exists to remove. `replay` is legal in the jar but is NOT accepted here: it requires `--ape-replay <log>`, which this tool never passes.

#### Scenario: Valid strategy configured

- **WHEN** `configure({"strategy": "sata", "preset": "mop", "overrides": {}})` is called
- **THEN** `self._tool_config["preset"]` SHALL equal `"mop"`
- **AND** no exception SHALL be raised

#### Scenario: Missing preset raises ConfigurationError

- **WHEN** `configure({"strategy": "sata"})` is called
- **THEN** `ConfigurationError` SHALL be raised naming the missing `preset` key

#### Scenario: Invalid strategy raises ConfigurationError

- **WHEN** `configure({"strategy": "bfs", "preset": "aperv"})` or `configure({"strategy": "dfs", "preset": "aperv"})` is called
- **THEN** `ConfigurationError` SHALL be raised before any device interaction
- **AND** the run SHALL NOT reach the jar, where it would abort as an unknown `--ape` value

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

## Invariants

- **INV-APERV-01**: `ApeRVTool.name` SHALL always equal `"aperv"`. This string is the registry key used by rv-platform, rv-experiment, and the CLI `--tools` flag. It MUST NOT be changed after registration.
- **INV-APERV-02**: The device JAR path SHALL always be `/data/local/tmp/ape-rv.jar`. The host JAR name is `ape-rv.jar`. These paths MUST match the `mvn install` copy target in `ape/pom.xml`.
- **INV-APERV-03**: `configure()` MUST be called before `execute_tool_specific_logic()`. The latter SHALL raise `RVToolExecutionError` if `self._tool_config` is empty at execution time.
- **INV-APERV-04**: Timeout is ALWAYS controlled by `task.config.timeout` (set by rv-platform). The `running_minutes` passed to APE is derived from `task.config.timeout / 60`. Variants MUST NOT hardcode a timeout.

---

## Data Contracts

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
