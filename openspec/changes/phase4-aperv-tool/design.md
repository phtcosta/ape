# Design: phase4-aperv-tool

## Architecture

`aperv-tool` is a thin wrapper module. It adds one new file tree to the rv-android workspace and one registration block to rv-platform. The Java runtime (`ape-rv.jar`) is unchanged; this change is purely Python glue.

```
rv-android/
├── modules/
│   ├── aperv-tool/                         ← NEW MODULE
│   │   ├── pyproject.toml
│   │   └── src/aperv_tool/
│   │       ├── __init__.py
│   │       └── tools/aperv/
│   │           ├── __init__.py
│   │           ├── tool.py                 ← ApeRVTool(AbstractTool)
│   │           ├── ape-rv.jar              ← gitignored; placed by mvn install
│   │           └── .gitignore
│   └── rv-platform/
│       └── src/rv_platform/__init__.py     ← MODIFIED: +aperv block
└── (root pyproject.toml — no change needed; auto-discovers modules/*)
```

## Component Map

| Component | File | Role |
|-----------|------|------|
| `ApeRVTool` | `tool.py` | Implements `AbstractTool`; push JAR + run APE via ADB |
| `pyproject.toml` | module root | uv workspace member declaration |
| `_register_external_tools()` | `rv-platform/__init__.py` | Lazy-import registration of `ApeRVTool` |
| `ape-rv.jar` | `tools/aperv/` | Dalvik JAR; runtime artefact, not source-controlled |

## Decisions

### D1 — Follow rvsmart-tool structure exactly, not builtin ape structure

The builtin `ape` tool (`rv-tools/builtin/ape/tool.py`) has several quirks: `device_serial` stored in `self.config` rather than resolved from `task.config.device_id`, timeout logic that uses `running_minutes` as a config key, and no separation between configuration and execution concerns. `rvsmart-tool` is cleaner: all runtime values come from `task`, not from variant config. `ApeRVTool` follows `rvsmart-tool`'s pattern.

Consequence: variants declare `strategy` only; `device_serial` and `timeout` always come from the platform task.

### D2 — Device JAR path `/data/local/tmp/ape-rv.jar`, not `/data/local/tmp/ape.jar`

The builtin `ape` uses `/data/local/tmp/ape.jar`. `ApeRVTool` uses `/data/local/tmp/ape-rv.jar` to avoid filename collision when both tools run on the same device. The JAR name matches the output of `mvn package` in the ape repo.

Consequence: the `mvn install` copy target in `ape/pom.xml` must write to `aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`.

### D3 — No health check step

The builtin `rvsmart` has a `--health-check` flag. APE does not. Omitting the health check step simplifies the execution flow without loss of correctness.

### D4 — `sata_mop` variant is a placeholder

Phase 3 (MOP/RV Awareness) does not exist yet. `sata_mop` is declared in `get_variants()` with `"strategy": "sata"` and `"mop_data": None`. When Phase 3 is implemented, this variant will push the static analysis JSON to `/data/local/tmp/ape.properties` with `ape.mopDataPath=/data/local/tmp/static_analysis.json`. Until then, `sata_mop` behaves identically to `sata`.

### D5 — Entry-point auto-discovery NOT used

`pyproject.toml` does NOT declare `[project.entry-points."rv_tools.plugins"]`. Registration is done explicitly in `rv-platform/__init__.py`. This matches the rvsmart-tool pattern and avoids entry-point scan issues when `aperv-tool` is not installed.

## API Design

### ApeRVTool (tool.py)

```python
APERV_TOOL_NAME = "aperv"
APERV_JAR_NAME = "ape-rv.jar"
APERV_DEVICE_JAR_PATH = "/data/local/tmp/ape-rv.jar"
APERV_DEVICE_PROPERTIES_PATH = "/data/local/tmp/ape.properties"
APERV_MAIN_CLASS = "com.android.commands.monkey.Monkey"
APERV_AVAILABLE_STRATEGIES = ["sata", "random", "bfs", "dfs"]

class ApeRVTool(AbstractTool):
    TOOL_SPEC = ToolSpec.create_builtin_spec(
        name=APERV_TOOL_NAME,
        description="APE-RV enhanced exploration agent (ape-rv.jar) via app_process",
        url="https://github.com/phtcosta/ape",
        version="1.0.0",
        process_pattern="com.android.commands.monkey",
    )

    def __init__(self): ...
    def get_tool_spec(cls) -> ToolSpec: ...
    def get_variants(cls) -> Dict[str, Dict[str, Any]]: ...
    def configure(self, config: Dict[str, Any]) -> None: ...  # validates strategy
    def execute_tool_specific_logic(self, task: Task, app: App) -> None: ...

    # Private helpers
    def _resolve_jar_path(self) -> str: ...          # JarResolver with 3-path priority
    def _push_file_to_device(self, local, device, serial, trace) -> None: ...
    def _push_properties(self, serial, trace) -> None: ...  # optional; throttle etc.
    def _build_main_command(self, app, serial, timeout_s) -> Command: ...
    def _check_empty_trace(self, trace_path) -> None: ...
```

### Main command format

```
adb -s <serial> shell
  CLASSPATH=/data/local/tmp/ape-rv.jar
  /system/bin/app_process /system/bin
  com.android.commands.monkey.Monkey
  -p <package>
  --running-minutes <N>
  --ape <strategy>
```

Differences from builtin `ape`:
- JAR path: `/data/local/tmp/ape-rv.jar` (not `ape.jar`)
- `app_process` working dir: `/system/bin` (matches the canonical invocation; builtin uses `/data/local/tmp/`)
- `Command` timeout: `timeout_seconds + 15` (grace period for APE to flush output after `StopTestingException`)

### pyproject.toml (aperv-tool)

```toml
[project]
name = "aperv-tool"
version = "0.1.0"
description = "APE-RV tool wrapper for rv-platform integration"
requires-python = ">=3.12"
dependencies = ["rv-android-core", "rv-tools"]

[tool.uv.sources]
rv-android-core = { workspace = true }
rv-tools = { workspace = true }

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/aperv_tool"]
```

### rv-platform/__init__.py addition

```python
# Register ApeRV tool if available and not already registered
if not registry.is_tool_registered("aperv"):
    try:
        from aperv_tool.tools.aperv.tool import ApeRVTool
        registry.register_tool_class(ApeRVTool)
    except ImportError as e:
        logging.getLogger(__name__).warning(f"aperv tool not available: {e}")
    except Exception as e:
        logging.getLogger(__name__).error(f"Failed to register aperv tool: {e}")
```

## pom.xml install hook

The ape repo `pom.xml` must copy `target/ape-rv.jar` to the aperv-tool module on `mvn install`. If the path is not already configured, add a `maven-resources-plugin` execution:

```xml
<execution>
    <id>copy-jar-to-aperv-tool</id>
    <phase>install</phase>
    <goals><goal>copy-resources</goal></goals>
    <configuration>
        <outputDirectory>
            ${rvsec_home}/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv
        </outputDirectory>
        <resources>
            <resource>
                <directory>${project.build.directory}</directory>
                <includes><include>ape-rv.jar</include></includes>
                <filtering>false</filtering>
            </resource>
        </resources>
    </configuration>
</execution>
```

`rvsec_home` property defaults to `${user.home}/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec` and can be overridden via `-Drvsec_home=...`.

## File List

**New files (rv-android repo)**:
- `modules/aperv-tool/pyproject.toml`
- `modules/aperv-tool/src/aperv_tool/__init__.py`
- `modules/aperv-tool/src/aperv_tool/tools/__init__.py`
- `modules/aperv-tool/src/aperv_tool/tools/aperv/__init__.py`
- `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`
- `modules/aperv-tool/src/aperv_tool/tools/aperv/.gitignore`  (contains `/ape-rv.jar`)

**Modified files (rv-android repo)**:
- `modules/rv-platform/src/rv_platform/__init__.py`  (+aperv registration block)

**Modified files (ape repo)**:
- `pom.xml`  (+install phase JAR copy to aperv-tool module)
