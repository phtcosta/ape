## Why

The enhanced `ape-rv.jar` produced by Phases 1 and 2 has no integration point in the rv-android ecosystem. Without a plugin, it cannot be selected via `rv-platform run --tools aperv:sata`, cannot be included in rv-experiment configurations, and cannot be compared against the existing `ape` builtin in controlled experiments. This change creates the `aperv-tool` Python module that wraps `ape-rv.jar` as a first-class rv-android tool, closing the gap from #1 and #2. See [issue #3](https://github.com/phtcosta/ape/issues/3).

## What Changes

- **New Python module** `rvsec/rv-android/modules/aperv-tool/` — uv workspace member, auto-discovered by `members = ["modules/*"]` in the rv-android root `pyproject.toml`
- **New class** `ApeRVTool(AbstractTool)` in `src/aperv_tool/tools/aperv/tool.py` — wraps `ape-rv.jar` execution via `adb shell app_process`, following the exact pattern of `APETool` (builtin) and `RVSmartTool` (external)
- **New variants**: `default`/`sata`, `random`, `bfs`, `sata_mop` (MOP variant placeholder for Phase 3)
- **Registration**: add `aperv` block to `rv-platform/src/rv_platform/__init__.py` `_register_external_tools()` — lazy import, warning on missing, error on failure
- **JAR placeholder** `src/aperv_tool/tools/aperv/ape-rv.jar` — gitignored; copied by `mvn install` in the ape repo (Phase 1 pom.xml already has the install hook target path)

## Capabilities

### New Capabilities

- `aperv-tool`: rv-android plugin wrapping `ape-rv.jar` as the `aperv` tool; defines variants, JAR resolution, ADB execution, and metrics extraction

### Modified Capabilities

- `aperv-tool`: replace the placeholder spec (`openspec/specs/aperv-tool/spec.md` currently says "Not yet implemented") with a complete specification covering `ApeRVTool`, its variants, JAR resolution, device execution flow, and registration

## Impact

- **rv-android modules**: new `aperv-tool` module added under `modules/`; no changes to existing modules
- **rv-platform**: one-line addition per block in `_register_external_tools()` (same pattern as rvsmart)
- **ape repo `pom.xml`**: the `mvn install` JAR-copy target path (`aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`) must be confirmed and added if not present
- **No breaking changes** to any existing tool, builtin `ape`, or experiment configuration
- **FR02** from `docs/PRD.md` ("aperv plugin for rv-android") is satisfied by this change
