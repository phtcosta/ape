# Delta: aperv-tool Plugin (isolate-guidance-groups)

## Purpose

`isolate-guidance-groups` replaces the jar's four campaign presets (`aperv`, `mop`, `llm`, `llm_mop`) with six cell presets, one per experimental cell of the guidance groups — `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm` (design D-B1, § API Design "Group blocks and cells") — renames the data key `ape.mopDataPath` to `ape.staticDataPath` because the `as` cell reads the artifact with MOP off (D-B2), adds the resolver key `ape.variant`, echoed as `RUN_START.variant` outside the plan digest, and retires `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` and `ape.mopTargetPickCap` (a properties file that sets one aborts with `retired_key`). The tool side is implemented by rv-android's sister change `isolate-guidance-groups` (contract v3.1; its `aperv` spec, "ApeRVTool Cell Variants", "ApeRVTool Configuration" and "ApeRVTool Execution Flow"), which is the owner of the roster. This delta brings the jar repository's mirror of the plugin into line with that contract and does not contradict it: six cell variants plus `default` bound to `base`, the ten old variants deleted without alias, the fixed lines `ape.preset`/`ape.variant`/`ape.staticDataPath`, the artifact pushed for every cell but `base`, and `system-broadcast.json` pushed only for the four `as*` cells, where the STATIC member `COMPONENT_TRIGGER` that is its only reader runs (D-B13).

## MODIFIED Requirements

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return a mapping of variant names to variant definitions. The variants are the six experimental cells, and each variant's name SHALL equal the name of the jar-resident cell preset it selects:

| Variant | `preset` | Groups the preset mounts | `static_data` | `broadcast_catalog` | `overrides` |
|---|---|---|---|---|---|
| `base` | `base` | — | — | — | _(empty)_ |
| `mop` | `mop` | MOP | `"static_analysis"` | — | _(empty)_ |
| `as` | `as` | ADDONS, STATIC | `"static_analysis"` | `True` | _(empty)_ |
| `as_mop` | `as_mop` | ADDONS, STATIC, MOP | `"static_analysis"` | `True` | _(empty)_ |
| `as_llm` | `as_llm` | ADDONS, STATIC, LLM | `"static_analysis"` | `True` | `llm_url` |
| `as_mop_llm` | `as_mop_llm` | ADDONS, STATIC, MOP, LLM | `"static_analysis"` | `True` | `llm_url` |

`get_variants()` SHALL also return `default`, bound to the same object as `base`, because every registered tool carries a `default` variant. It SHALL return no other name. The ten variants defined before this change (`sata`, `sata_mop`, `sata_llm`, `sata_mop_llm`, `mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70`, `mopd_on_llm_off`, `mopd_on_llm_90`, and the former binding of `default`) are deleted with no alias: the jar they configured is not the jar this change deploys, and a name that resolved would claim a configuration that no longer runs.

Each variant definition SHALL consist of:

- `preset: str` — the name of a **jar-resident** cell preset, written to `ape.properties` as the `ape.preset` line. A cell preset is the union of the jar's group blocks (ADDONS, STATIC, MOP, LLM); the jar, not Python, defines what a preset means, and Python SHALL NOT mirror preset contents or branch on a preset name.
- `variant: str` — the variant's own cell name (`"base"` for `default`), written as the `ape.variant` line.
- `overrides: Dict[str, Any]` — only what the jar cannot know: the LLM server URL `llm_url` on `as_llm` and `as_mop_llm`, empty on the other four. A dose (`llm_percentage`, `mop_coin_percentage`, `component_percentage`) or any other mapped key is an ablation, expressed through the tool DSL (`aperv:as_mop@mop_coin_percentage=0.5`), never as a new variant and never as a new preset.
- Python-only orchestration keys at top level — `strategy` (the `--ape` CLI flag, `"sata"` on every variant), `static_data` (set to `"static_analysis"` on every cell that mounts STATIC or MOP, which is every cell but `base`), `broadcast_catalog` (`True` on the four `as*` cells) and `seed` — which SHALL NOT be written to `ape.properties`.

No variant SHALL carry a full property expansion. The authoritative definition of the roster remains rv-android's `aperv` capability; the table above is the cell contract of this change's design (§ API Design), which that capability implements, and the two SHALL name the same six cells.

#### Scenario: Variant is preset plus deltas

- **WHEN** any variant returned by `get_variants()` is read
- **THEN** it SHALL carry a `preset` key naming one of `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`, a `variant` key equal to its cell name, and an `overrides` dict
- **AND** it SHALL NOT carry a full expansion of `ape.*` property keys

#### Scenario: Variant identical to its preset

- **WHEN** the `base`, `mop`, `as` or `as_mop` variant is read
- **THEN** its `overrides` dict SHALL be empty rather than restating the preset's keys
- **AND** the `as_llm` and `as_mop_llm` variants SHALL carry `llm_url` as their only override

#### Scenario: Orchestration keys stay out of the properties file

- **WHEN** `ape.properties` is generated for any variant
- **THEN** `strategy`, `static_data`, `broadcast_catalog` and `seed` SHALL NOT appear in it
- **AND** no line SHALL name `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` or `ape.mopTargetPickCap`

#### Scenario: Default variant resolved

- **WHEN** `get_variants()` is called
- **THEN** its keys SHALL be exactly `default`, `base`, `mop`, `as`, `as_mop`, `as_llm` and `as_mop_llm`
- **AND** `get_variants()["default"]` SHALL be the same object as `get_variants()["base"]`, so `aperv` without a variant runs `ape.preset=base` with `ape.variant=base`
- **AND** none of the deleted names (`sata`, `sata_mop`, `mop_on_llm_70`, `mopd_on_llm_90`, …) SHALL resolve

#### Scenario: sata_mop variant is wired (replaces Phase 4 placeholder)

- **WHEN** a variant that needs the compacted static-analysis artifact is read — any of `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`
- **THEN** it SHALL carry `static_data="static_analysis"` as a top-level Python-only orchestration key, alongside its `preset`, `variant` and `overrides`
- **AND** `static_data` SHALL NOT appear in the generated `ape.properties`, which names the artifact through `ape.staticDataPath=/data/local/tmp/mop-artifact.json` instead
- **AND** the `base` variant SHALL carry no `static_data`, and no variant SHALL carry the retired key `mop_data`

---

### Requirement: configure() Method

`ApeRVTool.configure(config)` SHALL store the resolved variant configuration in `self._tool_config`. It SHALL validate that `config["strategy"]` is one of `["sata", "random"]`, that `config["preset"]` and `config["variant"]` are present and non-empty, and that `config.get("overrides", {})` is a dict. If any check fails, it SHALL raise `ConfigurationError` before any device interaction.

Top-level keys that the tool DSL folds into the configuration and that have an `APERV_PROPERTY_MAPPING` entry SHALL be moved into `overrides`, where a DSL value takes precedence over the variant's own value. Any remaining top-level key that is neither mapped nor one of `preset`, `variant`, `overrides`, `strategy`, `static_data`, `broadcast_catalog`, `seed`, `device_port`, `device_serial`, `device_id` SHALL raise `ConfigurationError` naming it. In particular the renamed data key `mop_data` SHALL be rejected under its old name, so an experiment YAML that still names it fails loudly rather than running a cell without its data. The three device keys are the platform's own injection; they SHALL be accepted, ignored and never written to `ape.properties`. The full rule is rv-android's `aperv` "ApeRVTool Configuration"; this mirror does not restate its other clauses.

The strategy whitelist is `["sata", "random"]`. `bfs` and `dfs` were never agent types: `ApeAgent.createAgent` recognizes exactly `sata`, `random` and `replay`. Accepting them Python-side would let a run pass local validation and abort on the device. `replay` is legal in the jar but is NOT accepted here: it requires `--ape-replay <log>`, which this tool never passes.

#### Scenario: Valid strategy configured

- **WHEN** `configure({"strategy": "sata", "preset": "mop", "variant": "mop", "static_data": "static_analysis", "overrides": {}})` is called
- **THEN** `self._tool_config["preset"]` SHALL equal `"mop"` and `self._tool_config["variant"]` SHALL equal `"mop"`
- **AND** no exception SHALL be raised

#### Scenario: Missing preset raises ConfigurationError

- **WHEN** `configure({"strategy": "sata", "variant": "base"})` is called
- **THEN** `ConfigurationError` SHALL be raised naming the missing `preset` key
- **AND WHEN** `configure({"strategy": "sata", "preset": "base", "overrides": {}})` is called
- **THEN** `ConfigurationError` SHALL be raised naming the missing `variant` key, before any `adb push`
- **AND WHEN** `configure({"strategy": "sata", "preset": "as", "variant": "as", "mop_data": "static_analysis", "overrides": {}})` is called
- **THEN** `ConfigurationError` SHALL be raised naming `mop_data`

#### Scenario: Invalid strategy raises ConfigurationError

- **WHEN** `configure({"strategy": "bfs", "preset": "base", "variant": "base"})` or `configure({"strategy": "dfs", "preset": "base", "variant": "base"})` is called
- **THEN** `ConfigurationError` SHALL be raised before any device interaction
- **AND** the run SHALL NOT reach the jar, where it would abort as an unknown `--ape` value

---

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute); command timeout keeps the +45 s teardown grace
3. Resolve `ape-rv.jar` via `_resolve_jar_path()` and push to `/data/local/tmp/ape-rv.jar`
4. **Push the broadcast catalog** only when the variant carries `broadcast_catalog=True` — the four `as*` cells — and `system-broadcast.json` exists in the module directory: push it to `/data/local/tmp/system-broadcast.json`. Its only reader in the jar is the STATIC member `COMPONENT_TRIGGER`, which the `as*` presets mount at `componentPercentage=0.03` (design D-B13). `base` and `mop` do not mount STATIC and SHALL push no catalog. If the file is absent, skip (the jar degrades gracefully)
5. If `_tool_config.get("static_data") == "static_analysis"` (every cell but `base`): locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`. **If not found, raise `RVToolExecutionError`** naming the expected path — a cell without its static-analysis input is a failed task, never a silently degraded run. If found, obtain the derived artifact via `_derive_mop_artifact(task)` (cache-or-generate; a `DerivationError` also raises `RVToolExecutionError`), push **only that artifact** to `/data/local/tmp/mop-artifact.json`, and set `artifact_pushed = True`. The full JSON is never modified and never pushed
6. Push `ape.properties` generated as: `ape.preset=<preset>` first; `ape.variant=<variant>` second; `ape.staticDataPath=/data/local/tmp/mop-artifact.json` third if and only if `artifact_pushed` (that is, iff the cell mounts STATIC or MOP); then one `ape.<key>=<value>` line per entry of `overrides`, translated through `APERV_PROPERTY_MAPPING`, with Python bools serialized lowercase. `ape.llmUrl` therefore appears only for `as_llm` and `as_mop_llm`, whose `overrides` carry `llm_url`; the jar aborts a plan that sets `ape.llmUrl` without the LLM group (D-B14.4). The retired keys `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` and `ape.mopTargetPickCap` SHALL never be emitted. The full property expansion SHALL NOT be performed
7. Capture LLM provenance to the sidecar for the LLM cells (`as_llm`, `as_mop_llm`; unchanged, INV-APV-33)
8. Build and execute the main command (`--ape <strategy>`, `-s <seed>` when configured), capturing stdout+stderr to `task.result.trace_file` (the captured stream is the NDJSON trace)
9. On `RVCommandTimeoutError`: log as expected behaviour, run the collection step 11 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
10. Run the empty-trace check (`_check_empty_trace`, unchanged)
11. **Gzip at collection**: compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue

Step 11 SHALL NOT inspect, validate, or act on the trace's content: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). `task.result.trace_file` SHALL remain the raw NDJSON capture, byte-for-byte — no NDJSON→legacy conversion step exists.

The tool SHALL NOT read back, parse, or validate any jar output (`RUN_START` included, its `variant` member too) — provenance is write-only in the trace (INV-APV-43, owner decision D1).

No health check step is required (APE has no `--health-check` flag).

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so a scenario whose claim survives under new vocabulary keeps the main spec's header. Four headers here read in retired vocabulary: `sata_mop` and `sata` are deleted variant names, "MOP arm" is now any cell that mounts STATIC or MOP, and "JSON present" / "no JSON push" describe the artifact push. Read the bodies, which are this change's.

#### Scenario: Properties file carries preset plus deltas only

- **WHEN** `_push_properties()` runs for `as_mop` with the derived artifact pushed
- **THEN** the generated file SHALL be exactly the three lines `ape.preset=as_mop`, `ape.variant=as_mop`, `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, in that order
- **AND WHEN** it runs for `base`
- **THEN** the file SHALL be exactly the two lines `ape.preset=base` and `ape.variant=base`
- **AND WHEN** it runs for `as_mop_llm`
- **THEN** the file SHALL be `ape.preset=as_mop_llm`, `ape.variant=as_mop_llm`, `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and one `ape.llmUrl=http://10.0.2.2:30000/v1` line
- **AND** no generated file SHALL contain an `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak` or `ape.mopTargetPickCap` line

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
- **WHEN** `execute_tool_specific_logic` is called for `aperv:as` (a cell with `static_data == "static_analysis"` and MOP off) AND `_find_static_analysis_file(task)` returns a valid path
- **THEN** `_derive_mop_artifact(task)` SHALL return the cached-or-generated `<apk_name>.mop.json`
- **AND** it SHALL be pushed to `/data/local/tmp/mop-artifact.json`
- **AND** `ape.properties` SHALL contain `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **AND** `system-broadcast.json`, when present in the module directory, SHALL be pushed to `/data/local/tmp/system-broadcast.json`

#### Scenario: sata_mop — JSON absent
- **WHEN** `execute_tool_specific_logic` is called for `aperv:mop` or any other cell with `static_data == "static_analysis"` AND no full JSON is found in `task.results_dir`
- **THEN** `RVToolExecutionError` SHALL be raised naming the expected path
- **AND** the jar SHALL NOT be launched
- **AND** no warning-and-continue path SHALL exist

#### Scenario: MOP arm — derivation failure fails the task
- **WHEN** the cell is one of `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`, the full JSON exists, and `derive` raises `DerivationError` (e.g., a null `package`)
- **THEN** `RVToolExecutionError` SHALL be raised carrying the derivation error
- **AND** no partial artifact SHALL be pushed

#### Scenario: sata variant — no JSON push
- **WHEN** `execute_tool_specific_logic` is called for `aperv:base` (or `aperv:default`), whose config carries no `static_data`
- **THEN** no derivation SHALL run and no artifact SHALL be pushed
- **AND** `ape.properties` SHALL contain neither `ape.staticDataPath` nor `ape.mopDataPath`
- **AND** no file SHALL be pushed to `/data/local/tmp/system-broadcast.json`, even when it exists in the module directory; the same holds for `aperv:mop`, which pushes the artifact but no catalog

#### Scenario: Timeout during exploration
- **WHEN** the exploration runs past `task.config.timeout + 45` seconds
- **THEN** step 11 SHALL run on the trace captured up to the kill (gzip), and only then SHALL `RVToolTimeoutError` be re-raised
- **AND** the trace file SHALL contain the partial APE output written before the timeout

#### Scenario: JAR push fails
- **WHEN** `adb push` of `ape-rv.jar` exits with non-zero code
- **THEN** `RVToolExecutionError` SHALL be raised with the exit code and any stderr output
- **AND** no `ape.properties` SHALL be pushed for the run
