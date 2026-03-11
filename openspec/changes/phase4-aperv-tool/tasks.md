<!-- Sequential execution — 8 files across 2 repos (ape + rv-android).
     Groups 1 (ape pom.xml) and 2 (aperv-tool module) are independent and
     could run in parallel, but file count is below the 20-file threshold
     so sequential is correct.
     Critical path: Group 1 (pom.xml) → Group 2 (aperv-tool module) → Group 3 (rv-platform) → Group 4 (Verification).
     Note: no automated test suite (CLAUDE.md). Validation is via uv sync + rv-platform import smoke test + ADB device run. -->

## 1. ape/pom.xml — Install JAR Copy

- [ ] 1.1 In `pom.xml`, add a `maven-resources-plugin` execution bound to the `install` phase that copies `target/ape-rv.jar` to `${rvsec_home}/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`. Define `rvsec_home` property defaulting to `${user.home}/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec`. Use the `copy-resources` goal.
- [ ] 1.2 Run `mvn install -Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec` — must succeed and produce `ape-rv.jar` at `modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar` in the rv-android repo.

## 2. aperv-tool Module (rv-android)

- [ ] 2.1 Create `modules/aperv-tool/pyproject.toml` with content per design.md API section. Include: `name = "aperv-tool"`, `requires-python = ">=3.12"`, `dependencies = ["rv-android-core", "rv-tools"]`, `[tool.uv.sources]` workspace entries, hatchling build backend, `packages = ["src/aperv_tool"]`.
- [ ] 2.2 Create `modules/aperv-tool/src/aperv_tool/__init__.py` (empty).
- [ ] 2.3 Create `modules/aperv-tool/src/aperv_tool/tools/__init__.py` (empty).
- [ ] 2.4 Create `modules/aperv-tool/src/aperv_tool/tools/aperv/__init__.py` (empty).
- [ ] 2.5 Create `modules/aperv-tool/src/aperv_tool/tools/aperv/.gitignore` containing `/ape-rv.jar`.
- [ ] 2.6 Create `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` implementing `ApeRVTool(AbstractTool)` per design.md API section:
  - Constants: `APERV_TOOL_NAME`, `APERV_JAR_NAME`, `APERV_DEVICE_JAR_PATH`, `APERV_DEVICE_PROPERTIES_PATH`, `APERV_MAIN_CLASS`, `APERV_AVAILABLE_STRATEGIES`
  - `TOOL_SPEC` with `name="aperv"`, `process_pattern="com.android.commands.monkey"`
  - `get_variants()`: `default`, `sata`, `random`, `bfs`, `sata_mop` (placeholder) — all with `throttle_ms=200`
  - `configure()`: validate strategy in `APERV_AVAILABLE_STRATEGIES`; raise `ConfigurationError` if invalid
  - `execute_tool_specific_logic()`: resolve device_serial from task, timeout from task, resolve JAR, push JAR, optionally push properties, build and execute main command, re-raise `RVToolTimeoutError`
  - `_resolve_jar_path()`: search `[dirname(__file__), $RVSEC_HOME/ape/target, $TOOLS_DIR/aperv]`
  - `_push_file_to_device()`: `adb -s <serial> push -a -p <local> <device>`; raise `RVToolExecutionError` on failure
  - `_push_properties()`: generate `ape.properties` from `_tool_config` (throttle_ms → `ape.defaultGUIThrottle`), push via `_push_file_to_device`
  - `_build_main_command()`: `adb -s <serial> shell CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin com.android.commands.monkey.Monkey -p <pkg> --running-minutes <N> --ape <strategy>`, `Command` timeout = `timeout_seconds + 15`
  - `_check_empty_trace()`: warn if trace is 0 bytes

## 3. rv-platform Registration (rv-android)

- [ ] 3.1 In `modules/rv-platform/src/rv_platform/__init__.py`, add the `aperv` registration block to `_register_external_tools()` immediately after the `rvsmart` block, per design.md API section. Guard with `is_tool_registered("aperv")`, `ImportError` → WARNING, other exceptions → ERROR.

## 4. Verification

- [ ] 4.1 Run `cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android && uv sync` — must complete without errors; `aperv-tool` must appear in workspace packages.
- [ ] 4.2 Smoke test import: `python3 -c "import rv_platform; from rv_tools import ToolRegistry; t = ToolRegistry.get_instance().list_tools(); print(t); assert 'aperv' in t, 'aperv not registered'"` — must succeed and print a list containing `"aperv"`.
- [ ] 4.3 Verify variants: `python3 -c "from aperv_tool.tools.aperv.tool import ApeRVTool; v = ApeRVTool.get_variants(); print(list(v)); assert 'sata' in v and 'sata_mop' in v"` — must pass.
- [ ] 4.4 Confirm `mvn install` copies JAR: verify `modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar` exists and is a valid Dalvik DEX: `unzip -p .../ape-rv.jar classes.dex | file -` → `Dalvik dex file version 035` (or similar).
- [ ] 4.5 ADB device run: with emulator running (`scripts/run_emulator.sh` in ape repo), run:
  ```
  cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android
  uv run rv-platform run --tools aperv:sata --apk test-apks/cryptoapp.apk --timeout 60
  ```
  Confirm run completes (timeout exit), trace file is non-empty, and `"aperv"` appears in the run log.
- [ ] 4.6 Run `/sdd-qa-lint-fix modules/aperv-tool/src` in rv-android repo — fix any ruff issues.
- [ ] 4.7 Run `/sdd-verify modules/aperv-tool/src` — PASS (0 tests, lint clean).
- [ ] 4.8 Invoke `/sdd-code-reviewer` via Skill tool.
- [ ] 4.9 Run `/opsx:sync phase4-aperv-tool` — sync delta specs to main specs.
