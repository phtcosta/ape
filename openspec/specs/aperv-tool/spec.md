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

Written against the post-`rearch-05-thin-python-arms` text (which already carries the stage-4 collection steps). Only steps 5 and 6 change at this stage; every other step, the trailing paragraphs, and the stage-4/5 scenarios are preserved.

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute); command timeout keeps the +45 s teardown grace
3. Resolve `ape-rv.jar` via `_resolve_jar_path()` and push to `/data/local/tmp/ape-rv.jar`
4. Push `system-broadcast.json` when present (component triggering, unchanged)
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`. **If not found, raise `RVToolExecutionError`** naming the expected path — a MOP arm without its static-analysis input is a failed task, never a silently degraded run (this replaces the warn-and-continue of the previous stages). If found, obtain the derived artifact via `_derive_mop_artifact(task)` (cache-or-generate, see the added requirement below; a `DerivationError` also raises `RVToolExecutionError`), push it to `/data/local/tmp/mop-artifact.json`, and set `mop_json_pushed = True`. The compaction/enrichment shim of the previous stages (INV-APV-20..25/31/32) is **deleted**: the derivation subsumes the dedup and the minify, whose whole purpose was to clear the jar's parse-footprint guard, and retires the enrichment of INV-APV-32 as a behaviour change (see this delta's Purpose and design D10)
6. Push `ape.properties` generated as: `ape.preset=<preset>` first; `ape.mopDataPath=/data/local/tmp/mop-artifact.json` when `mop_json_pushed`; then one `ape.<key>=<value>` line per entry of `overrides`, translated through `APERV_PROPERTY_MAPPING`, with Python bools serialized lowercase. The full property expansion of the pre-stage-5 mapping loop SHALL NOT be performed
7. Capture LLM provenance to the sidecar for arms with `llm_url` in effect (unchanged, INV-APV-33)
8. Build and execute the main command (`--ape <strategy>`, `-s <seed>` when configured), capturing stdout+stderr to `task.result.trace_file` (the captured stream is the NDJSON trace, per `rearch-04-step-ndjson-telemetry`)
9. On `RVCommandTimeoutError`: log as expected behaviour, run the collection step 11 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
10. Run the empty-trace check (`_check_empty_trace`, unchanged)
11. **Gzip at collection** (unchanged from `rearch-04-step-ndjson-telemetry`): compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue

Step 11 SHALL NOT inspect, validate, or act on the trace's content: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). `task.result.trace_file` SHALL remain the raw NDJSON capture, byte-for-byte — no NDJSON→legacy conversion step exists (`rearch-04-step-ndjson-telemetry` design D-8).

The tool SHALL NOT read back, parse, or validate any jar output (`RUN_START` included) — provenance is write-only in the trace (INV-APV-43, owner decision D1).

No health check step is required (APE has no `--health-check` flag).

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so a scenario whose claim survives under new vocabulary must keep the *main spec's* header or the archive drops it. Three headers here consequently read in vocabulary this change retires: `sata_mop` and `sata` are arm names `gh95` retired, and "JSON present" / "no JSON push" describe a push path that no longer exists. Read the bodies, which are this change's; the headers are the tool's cost, and this note is the only place it is worth mentioning.

#### Scenario: Properties file carries preset plus deltas only

- **WHEN** `_push_properties()` runs for `sata_mop_act_frontier` with the derived artifact pushed
- **THEN** the generated file SHALL begin with `ape.preset=mop`
- **AND** SHALL contain `ape.mopDataPath=/data/local/tmp/mop-artifact.json`
- **AND** SHALL contain exactly the four override lines (`ape.mopActivitySourceComponents=true`, `ape.frontierBoostWeight=200`, `ape.mopFrontierWeight=200`, `ape.activityTriggerEnabled=true`)
- **AND** SHALL NOT contain any other `ape.*` line

#### Scenario: Collection leaves the NDJSON trace intact

- **WHEN** a run completes and the captured `task.result.trace_file` contains NDJSON records
- **THEN** after step 11, `task.result.trace_file` SHALL still hold exactly the captured NDJSON records, unmodified
- **AND** `<trace>.ndjson.gz` SHALL contain the compressed copy

#### Scenario: Gzip failure is non-fatal and write-only

- **WHEN** compression raises
- **THEN** a WARNING SHALL be logged, the uncompressed NDJSON trace SHALL remain at `task.result.trace_file`, and the task SHALL complete with the same status it would have had otherwise (D5: no validation, no status logic)

#### Scenario: No exit contract

- **WHEN** a trace ends without a `RUN_END` record (e.g. SIGKILL on timeout before teardown)
- **THEN** the tool SHALL NOT detect, log, or act on its absence
- **AND** truncated-run identification remains a post-hoc analysis over trace/logcat timestamps

#### Scenario: sata_mop — JSON present
- **WHEN** `execute_tool_specific_logic` is called with a `mop_data == "static_analysis"` arm AND `_find_static_analysis_file(task)` returns a valid path
- **THEN** `_derive_mop_artifact(task)` SHALL return the cached-or-generated `<apk_name>.mop.json`
- **AND** it SHALL be pushed to `/data/local/tmp/mop-artifact.json`
- **AND** `ape.properties` SHALL contain `ape.mopDataPath=/data/local/tmp/mop-artifact.json`

#### Scenario: sata_mop — JSON absent
- **WHEN** `execute_tool_specific_logic` is called with a `mop_data == "static_analysis"` arm AND no full JSON is found in `task.results_dir`
- **THEN** `RVToolExecutionError` SHALL be raised naming the expected path
- **AND** the jar SHALL NOT be launched
- **AND** no warning-and-continue path SHALL exist (the former "running without MOP data" behavior is deleted)

#### Scenario: MOP arm — derivation failure fails the task
- **WHEN** the full JSON exists but `derive` raises `DerivationError` (e.g., `complete != true`)
- **THEN** `RVToolExecutionError` SHALL be raised carrying the derivation error
- **AND** no partial artifact SHALL be pushed

#### Scenario: sata variant — no JSON push
- **WHEN** `execute_tool_specific_logic` is called with an arm whose config lacks `mop_data`
- **THEN** no derivation SHALL run, no artifact SHALL be pushed
- **AND** `ape.properties` SHALL NOT contain `ape.mopDataPath`

#### Scenario: Timeout during exploration
- **WHEN** the exploration runs past `task.config.timeout` seconds
- **THEN** step 11 SHALL run on the trace captured up to the kill (gzip), and only then SHALL `RVToolTimeoutError` be re-raised
- **AND** the trace file SHALL contain the partial APE output written before the timeout

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

### Requirement: Native NDJSON Trace Reader

The module SHALL provide `aperv_tool/analysis/trace_ndjson.py`, a read-only reader of the NDJSON trace, and it SHALL be the sole mechanism by which analysis code consumes a post-change trace. The reader SHALL stream the file and yield one typed row per step, having already:

- resolved `act` / `st` integer references against the `ACT` / `STATE` dictionary records;
- materialized the fields the sink omits at their defaults (`mop`, `mop_frontier`, `wtg`, `coverage`, `menu`, `form` at `0`; `new_state` and `activity_changed` at `false`);
- re-derived `activity_has_mop` on the step side from the record's `ACT` entry and on the outcome side via `out.target` → `STATE.act` → `ACT.mop`;
- expanded the run-relative `t` to epoch milliseconds via `RUN_START.t0`, where an absolute clock is wanted;
- attached the step's `llm[]` sub-events and its `out` section to the same row, so the three-way join by `step=` no longer exists for any consumer;
- carried every field of the sub-event through, **including the `sys`/`user`/`resp`/`tool_calls` prompt and response dumps**.

The reader SHALL NOT write to the trace, SHALL NOT emit legacy `[APE-*]` lines, and SHALL NOT run on the collection path — it is an analysis-time component. A malformed record SHALL be skipped and counted in the reader's own diagnostics rather than aborting the read.

**The dumps are named explicitly because dropping them is the easy mistake, and because this reader is the only sanctioned way to read a new trace.** They are on by default, they are the largest single item in the trace, and the throughput gate (`event-sink` INV-SNK-13) prices their per-character escaping — so a reader that discards them leaves the run paying the whole cost of data no analysis can reach. The failure is not hypothetical in the other direction either: the analysis that consumed them, `calibracao/decompose_nomatch.py`, pairs each `[APE-LLM-TEL]` with the `[APE-LLM-RESPONSE]` that produced it to decompose `no_match` causes, and that pairing was the gate of a change decision. Its successor over new traces needs `resp`. The new schema makes the pairing free — call and response share one record — so losing the field at the reader would give back exactly what the re-encoding bought. If the dumps are ever judged too expensive, the decision to make is the flag (`TelemetryParams.llmPromptDump`, default on), not a silent drop one layer down: written-and-unreadable is the one state that costs everything and returns nothing.

The reader SHALL additionally expose the run-level records it encounters — `MOP_DATA`, `PIPELINE` and `LLM_ACK` — as attributes alongside the `RUN_START` header it already surfaces, since the step-row iteration has no natural place for them. Without this they are unreachable: this reader is the sole mechanism for consuming a post-change trace, so a record it skips is a record no conformant analysis can read, and `MOP_DATA.wtgEdges` and `PIPELINE.candidates` are precisely the two quantities this change added — one because `transitions` had been misread as the frontier gate for months, the other because "the arm turned it off" and "this application's data could not support it" were otherwise indistinguishable across 25 of 40 applications. Writing that census and leaving it unread would reproduce, one layer up, the defect the census was added to end.

**`RUN_END` SHALL NOT be exposed**, and this asymmetry is deliberate rather than an oversight in the list above. Owner decision D5 is that no consumer reads it; an attribute is the first step toward `if not run_end: ...`, which is the exit contract D5 refuses. The other three are load and assembly census, not a termination signal, so exposing them creates no such gradient. Truncated-run detection remains post-hoc over timestamps.

`clock_logcat_join.py` SHALL be migrated onto this reader in this change. Its `[APE-STEP]` regex and its entire UTC-offset reconstruction — the year-candidate search, the quarter-hour rounding, the anchor selection, and `alignment_residual_ms` — SHALL be deleted, not disabled: the D4 logcat heartbeat places step and violation on the same clock, so the reconstruction has nothing left to reconstruct.

The deletion has one precondition, and it is not a formality (`event-sink` INV-SNK-14). The platform captures logcat as a live stream under a strict tag allowlist (`adb logcat … -s RVSEC:V RVSEC-COV:V`), so the heartbeat reaches the joined file only once its tag is in that allowlist — a change in the `rvsec` repo, touching the byte-identical-command clause that pins the capture. Until a captured run is shown to contain heartbeat lines, the reconstruction SHALL stay. Deleting a working fallback in favour of a mechanism never observed end-to-end would reproduce, on the analysis path, exactly the silent-inertness class this change exists to remove.

#### Scenario: Reader yields a joined step row

- **WHEN** the reader is run over a trace whose step 42 carries a `dec` with no boosts, two `llm[]` sub-events, and an `out` closed at step 43
- **THEN** it SHALL yield one row for step 42 carrying the resolved activity and state strings, `mop=0 wtg=0 coverage=0 menu=0 form=0`, both LLM sub-events in occurrence order, and the outcome fields
- **AND** no second pass over the trace SHALL be required to join them

#### Scenario: Join runs without offset reconstruction

- **WHEN** `clock_logcat_join.py` joins a new-format trace against a logcat containing the D4 heartbeat lines
- **THEN** the step↔violation join SHALL be computed directly from the shared logcat clock
- **AND** the module SHALL contain no UTC-offset reconstruction, no year-candidate search, and no `alignment_residual_ms`

#### Scenario: the prompt dumps survive the reader

- **WHEN** a step's `llm[]` entry carries `sys`, `user` and `resp` because the prompt-dump flag is at its default
- **THEN** the row the reader yields SHALL carry all three, with the newlines the widget list contains restored exactly as the jar escaped them
- **AND** no analysis SHALL need a second parser over the same trace to reach them

#### Scenario: the load census is reachable and the termination record is not

- **WHEN** a trace carries `MOP_DATA`, `PIPELINE`, `LLM_ACK` and `RUN_END`
- **THEN** the reader SHALL expose the first three as attributes, so `wtgEdges` and the candidate census can be read without a second parser
- **AND** it SHALL expose no `RUN_END` accessor, so no consumer can come to depend on its presence (D5)

### Requirement: Frozen Legacy-Corpus Readers Are Not Migrated

The archived legacy corpus — the traces behind the 2026-07-24 calibration report and the decisive run — will not be regenerated, so the scripts that read it SHALL keep parsing the legacy `[APE-*]` `key=value` format and SHALL NOT be migrated, adapted, or deleted by this change: `scripts/cmpm_stratify.py`, `scripts/analyze_cmpv2_llm.py`, `experimento-cal/scripts/*`, `experimento-20260721/scripts/*`, and `calibracao/*`.

This carve-out is normative and stated here so that it is not mistaken for a P3 violation. Those scripts are not compatibility shims keeping a superseded implementation alive for new data; they are the readers of a dataset that is finished. P3 governs superseded implementation, not analysis code over frozen data. The distinction is operational: `clock_logcat_join.py` migrates because it must read *new* traces; these do not, because they never will.

`coverage_dump.py` is likewise untouched — it reads only the `[APE-RV] UICOV` / `UICOV-ACT` lines, which this change does not modify.

#### Scenario: A frozen-corpus script keeps its legacy parser

- **WHEN** the change is applied and every jar-side emitter of the legacy line family is gone
- **THEN** `scripts/cmpm_stratify.py` and the `experimento-cal` / `experimento-20260721` / `calibracao` scripts SHALL be unchanged
- **AND** they SHALL still parse the archived legacy traces they were written for

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
