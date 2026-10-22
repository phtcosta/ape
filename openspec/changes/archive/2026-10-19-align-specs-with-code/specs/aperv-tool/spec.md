## Purpose

`aperv-tool` integrates `ape-rv.jar` into the rv-android tool registry as the `aperv` plugin. It follows the same integration pattern as `rvsmart-tool` (external module, lazy-import registration) and mirrors the execution model of the builtin `ape` tool (ADB `app_process` invocation, `com.android.commands.monkey.Monkey` main class). The plugin ID `aperv` is distinct from the existing `ape` builtin, allowing both tools to coexist and be compared within the same experiment.

The plugin's variants are the six experimental cells of the guidance groups — `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm` — each bound to the jar-resident cell preset of the same name, plus `default`, bound to `base` (design D-B1). A variant writes the fixed lines `ape.preset`, `ape.variant` (echoed by the jar as `RUN_START.variant`, outside the plan digest) and, on every cell but `base`, `ape.staticDataPath`, the artifact's device path (D-B2); `system-broadcast.json` is pushed only for the four `as*` cells, where its only reader, the STATIC member `COMPONENT_TRIGGER`, runs (D-B13). The retired keys `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak`, `ape.mopTargetPickCap` and `ape.llmPercentageNoSubstrate` are never emitted; a properties file that sets one aborts with `retired_key`. The roster's owner is rv-android's `aperv` capability (contract v3.1: "ApeRVTool Cell Variants", "ApeRVTool Configuration", "ApeRVTool Execution Flow"); this spec is the jar repository's mirror of that contract and does not contradict it.

---

## Invariants

- **INV-APERV-05**: A variant whose `static_data` is `"static_analysis"` (every cell but `base`) SHALL either push the freshly derived compact artifact to `/data/local/tmp/mop-artifact.json` and write the `ape.staticDataPath` line, or fail the task with `RVToolExecutionError` before launching the jar (static-analysis JSON absent, or a `DerivationError`). There SHALL be no execution path in which such a cell launches the jar without `ape.staticDataPath` set.
- **INV-APERV-06**: The device SHALL only ever receive the derived compact artifact; no code path pushes the full static-analysis JSON.
- **INV-APERV-07**: Cache freshness is digest-based: a cached `<apk_name>.mop.json` SHALL be reused only when its recorded `source.digest` equals the SHA-256 of the current full JSON; otherwise it SHALL be regenerated. Cache state SHALL never change what the device receives for a given full JSON (determinism, rv-android INV-DRV-05).

## Data Contracts

### Output

- `task.result.trace_file: file` — APE stdout+stderr captured to this path: the jar's NDJSON sink records (every line that begins with `{`: `RUN_START`, dictionary entries, step records, `MOP_DATA`, `PIPELINE`, `LLM_ACK`, `RUN_END`) interleaved with its `[APE] ` Logger lines and the AOSP Monkey output (`## Network stats` included); gzipped at collection to `<trace>.ndjson.gz`
- No structured metrics file (APE does not emit a `APERV_METRICS:` line); metrics are extracted from the trace file by the rv-android analysis pipeline, through the NDJSON reader, if needed

## MODIFIED Requirements

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute); command timeout keeps the +45 s teardown grace
3. Resolve `ape-rv.jar` via `_resolve_jar_path()` and push to `/data/local/tmp/ape-rv.jar`
4. **Push the broadcast catalog** only when the variant carries `broadcast_catalog=True` — the four `as*` cells — and `system-broadcast.json` exists in the module directory: push it to `/data/local/tmp/system-broadcast.json`. Its only reader in the jar is the STATIC member `COMPONENT_TRIGGER`, which the `as*` presets mount at `componentPercentage=0.03` (design D-B13). `base` and `mop` do not mount STATIC and SHALL push no catalog. If the file is absent, skip (the jar degrades gracefully)
5. If `_tool_config.get("static_data") == "static_analysis"` (every cell but `base`): locate `<task.results_dir>/<apk_name>.json` via `_find_static_analysis_file(task)`. **If not found, raise `RVToolExecutionError`** naming the expected path — a cell without its static-analysis input is a failed task, never a silently degraded run. If found, obtain the derived artifact via `_derive_mop_artifact(task)` (cache-or-generate; a `DerivationError` also raises `RVToolExecutionError`), push **only that artifact** to `/data/local/tmp/mop-artifact.json`, and set `artifact_pushed = True`. The full JSON is never modified and never pushed
6. Push `ape.properties` generated as: `ape.preset=<preset>` first; `ape.variant=<variant>` second; `ape.staticDataPath=/data/local/tmp/mop-artifact.json` third if and only if `artifact_pushed` (that is, iff the cell mounts STATIC or MOP); then one `ape.<key>=<value>` line per entry of `overrides`, translated through `APERV_PROPERTY_MAPPING`, with Python bools serialized lowercase. `ape.llmUrl` therefore appears only for `as_llm` and `as_mop_llm`, whose `overrides` carry `llm_url`; the jar aborts a plan that sets `ape.llmUrl` without the LLM group (D-B14.4). The retired keys `ape.mopDataPath`, `ape.leastVisitedPriorityTiebreak`, `ape.mopTargetPickCap` and `ape.llmPercentageNoSubstrate` SHALL never be emitted. The full property expansion SHALL NOT be performed
7. Capture LLM provenance to the sidecar for the LLM cells (`as_llm`, `as_mop_llm`; rv-android INV-APV-33)
8. Build and execute the main command (`--ape <strategy>`, `-s <seed>` when configured), capturing stdout+stderr to `task.result.trace_file` (the captured stream is the NDJSON trace)
9. On `RVCommandTimeoutError`: log as expected behaviour, run the collection step 11 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
10. Run the empty-trace check (`_check_empty_trace`, unchanged)
11. **Gzip at collection**: compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue

Step 11 SHALL NOT inspect, validate, or act on the trace's content: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). `task.result.trace_file` SHALL remain the raw NDJSON capture, byte-for-byte — no NDJSON→legacy conversion step exists.

The tool SHALL NOT read back, parse, or validate any jar output (`RUN_START` included, its `variant` member too) — provenance is write-only in the trace (rv-android INV-APV-43, owner decision D1).

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

### Requirement: Native NDJSON Trace Reader

The module SHALL provide `aperv_tool/analysis/trace_ndjson.py`, a read-only reader of the NDJSON trace, and it SHALL be the sole mechanism by which analysis code consumes a post-change trace. The reader SHALL stream the file and yield one typed row per step, having already:

- resolved `act` / `st` integer references against the `ACT` / `STATE` dictionary records;
- materialized the fields the sink omits at their defaults (`mop`, `mop_frontier`, `wtg`, `nav_frontier` (from `navf`), `coverage`, `menu`, `form` at `0`; `new_state` and `activity_changed` at `false`);
- re-derived `activity_has_mop` on the step side from the record's `ACT` entry and on the outcome side via `out.target` → `STATE.act` → `ACT.mop`;
- expanded the run-relative `t` to epoch milliseconds via `RUN_START.t0`, where an absolute clock is wanted;
- attached the step's `llm[]` sub-events and its `out` section to the same row, so the three-way join by `step=` no longer exists for any consumer;
- carried every field of the sub-event through, **including the `sys`/`user`/`resp`/`tool_calls` prompt and response dumps**.

The reader SHALL NOT write to the trace, SHALL NOT emit legacy `[APE-*]` lines, and SHALL NOT run on the collection path — it is an analysis-time component. A malformed record SHALL be skipped and counted in the reader's own diagnostics rather than aborting the read.

**The dumps are named explicitly because dropping them is the easy mistake, and because this reader is the only sanctioned way to read a new trace.** They are on by default, they are the largest single item in the trace, and the throughput gate (`event-sink` INV-SNK-13) prices their per-character escaping — so a reader that discards them leaves the run paying the whole cost of data no analysis can reach. The failure is not hypothetical in the other direction either: the analysis that consumed them, `calibracao/decompose_nomatch.py`, pairs each `[APE-LLM-TEL]` with the `[APE-LLM-RESPONSE]` that produced it to decompose `no_match` causes, and that pairing was the gate of a change decision. Its successor over new traces needs `resp`. The new schema makes the pairing free — call and response share one record — so losing the field at the reader would give back exactly what the re-encoding bought. If the dumps are ever judged too expensive, the decision to make is the flag (`TelemetryParams.llmPromptDump`, default on), not a silent drop one layer down: written-and-unreadable is the one state that costs everything and returns nothing.

The reader SHALL additionally expose the run-level records it encounters — `MOP_DATA`, `PIPELINE`, `LLM_ACK` and `RUN_END` — as attributes alongside the `RUN_START` header it surfaces, since the step-row iteration has no natural place for them. Without this they are unreachable: this reader is the sole mechanism for consuming a post-change trace, so a record it skips is a record no conformant analysis can read, and `MOP_DATA.wtgEdges` and `PIPELINE.candidates` are precisely the two quantities this change added — one because `transitions` had been misread as the frontier gate for months, the other because "the arm turned it off" and "this application's data could not support it" were otherwise indistinguishable across 25 of 40 applications. Writing that census and leaving it unread would reproduce, one layer up, the defect the census was added to end.

**`RUN_END` is exposed verbatim and interpreted by nothing in the reader** (`run_end`, the record minus `type`, or `None`). Its `counters` are the per-run census of the mechanisms the cell mounted, which analysis reads; what owner decision D5 refuses is an exit contract, so the reader SHALL derive no completeness flag or status from the record, and the collection path SHALL NOT read it (rv-android INV-APV-53). A trace cut by a `SIGKILL` legitimately has no `RUN_END`, and `None` reports that fact rather than repairing it. Truncated-run detection remains post-hoc over timestamps.

`clock_logcat_join.py` SHALL read the trace through this reader and join steps to violations on the D4 logcat heartbeat, which places step and violation on the same clock. It SHALL carry no `[APE-STEP]` regex and no UTC-offset reconstruction — no year-candidate search, no quarter-hour rounding, no anchor selection, no `alignment_residual_ms`.

That join rests on one precondition (`event-sink` INV-SNK-14): the platform captures logcat as a live stream under a strict tag allowlist, so the heartbeat reaches the joined file only because its tag `ApeRvHb` is in the baseline tag set `LogcatManager.default_tags` (rv-android INV-PLT-21).

#### Scenario: Reader yields a joined step row

- **WHEN** the reader is run over a trace whose step 42 carries a `dec` with no boosts, two `llm[]` sub-events, and an `out` closed at step 43
- **THEN** it SHALL yield one row for step 42 carrying the resolved activity and state strings, `mop=0 mop_frontier=0 wtg=0 nav_frontier=0 coverage=0 menu=0 form=0`, both LLM sub-events in occurrence order, and the outcome fields
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
- **THEN** the reader SHALL expose all four as attributes, so `wtgEdges`, the candidate census and the run counters can be read without a second parser
- **AND** it SHALL expose `RUN_END` verbatim and derive no completeness flag or status from it, and no collection-path code SHALL read it (D5, rv-android INV-APV-53)
- **AND** on a trace without `RUN_END` the accessor SHALL return `None`

### Requirement: Derived artifact generation and caching

`ApeRVTool._derive_mop_artifact(task)` SHALL return the host path of the compact MOP artifact for the task's APK, generating it when needed:

1. Compute the SHA-256 of the current full JSON (`<apk_name>.json`).
2. If `<apk_name>.mop.json` exists next to it AND its `source.digest` matches, reuse it (cache hit).
3. Otherwise call `derive_mop_artifact.derive` + `serialize_canonical` and write `<apk_name>.mop.json` atomically (write-temp-then-rename); a failed derivation writes nothing.

The artifact is cached in `task.results_dir` — inspectable and diffable next to its source — and is a pure function of the full JSON (INV-APERV-07; rv-android INV-DRV-05). This method replaces `_compact_static_analysis_json`, which is **deleted** together with its fallback-to-source-push behavior: there is no longer any condition under which the full JSON is pushed (INV-APERV-06).

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
