# Specification: aperv-tool Plugin

## Status

**Not yet implemented.** This capability is planned for Phase 4 of APE-RV development.

## Planned Purpose

This specification will describe the `aperv-tool` Python module that integrates APE-RV into rv-android's tool registry as the `aperv` plugin (distinct from the existing `ape` builtin). It will follow the same pattern as `rvsmart-tool`.

When Phase 4 is implemented, this document will specify:
- `ApeRVTool(AbstractTool)` implementation
- Four variants: `sata`, `sata_mop`, `bfs`, `random`
- JAR resolution via `JarResolver` (module dir → `$RVSEC_HOME` → `$TOOLS_DIR`)
- Registration in `rv-platform`'s `_register_external_tools()` with lazy import fallback
- uv workspace declaration in rv-android root `pyproject.toml`

Implementation will be tracked via the corresponding OpenSpec change artifact under `openspec/changes/`.
