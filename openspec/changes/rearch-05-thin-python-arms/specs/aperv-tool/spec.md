## Purpose

Delta spec for the `aperv-tool` capability — stage 5 of the re-architecture ("thin Python arms"), the first cross-repo stage (rv-android, `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`).

Today an experiment arm's meaning lives in Python: 29 hardcoded variant dicts expanded through a 52-pair `APERV_PROPERTY_MAPPING` into 19–34 `ape.properties` lines per arm, guarded by pytest suites that validate Python constants against other Python constants (the INV-APV-13/14/15/17/26/27 family), with a kill-switch key list duplicated from — and divergent with — the jar's (18 Python keys vs 27 Java keys, verified V20), and at least one dead key (`mop_weight_activity`, finding 3.3-7). With presets resident in the jar since `rearch-02-runspec` (fail-fast validation + level-0 `RUN_START` echo), the Python side becomes thin: **an arm is a preset name plus an explicit dict of override deltas**. The jar is the sole authority on what a preset means; the Python side remains the sole authority on the experimental matrix (which arms exist, their frozen names, their deltas).

This delta modifies the arm-definition and properties-generation requirements, adds the override pass-through contract and the one-time regeneration migration check, and records the retirement of the constant-vs-constant guard machinery. Per owner decision D1 (final): **no automatic echo-vs-intent validation is added anywhere** — `tool.py` never reads `RUN_START`; drift auditing stays post-hoc analysis of the trace. Everything else the plugin does — the strategy validation mechanism (whose accepted set shrinks to `["sata", "random"]`, the one orchestration change this stage makes), JAR resolution and push, static-analysis discovery/compaction/enrichment, seed propagation, the +45 s command grace, timeout-as-normal-exit, empty-trace detection, LLM provenance capture and the B3 snap-tolerance pairing — is intentionally untouched by this change.

The cross-repo counterpart of this delta targets rv-android's own spec (`rv-android/openspec/specs/aperv/spec.md`: the variant/properties tables and the requirement "Arm-Defining Flag Completeness (FR20)"). That spec is **not** edited by hand — rv-android's CLAUDE.md forbids writing OpenSpec artifacts directly — so the counterpart is carried by its own change in that repository, opened at implementation time (task 8.5a) and applied by that repo's archive/sync. Nothing in this delta's operations targets it; see the disposition note at the end of this file.

## Data Contracts

### Input

- `variant dict: Dict[str, Any]` — per arm: `preset: str` (jar preset name), `overrides: Dict[str, Any]` (deltas over the preset), plus Python-only orchestration keys (`strategy`, `mop_data`, `seed`, `expected_jar_git_sha`, `expected_jar_sha256`) (source: `ApeRVTool.get_variants()` merged with experiment parameters)
- `APERV_PROPERTY_MAPPING: Dict[str, str]` — Python override key → `ape.*` property name; reduced to keys the deployed jar validates (source: `tool.py` module constant)
- `arm_effective_baseline.json` — pre-change canonical effective configuration of each of the 29 arms (source: one-time capture script, committed before any arm edit)

### Output

- `ape.properties` on device — `ape.preset=<name>` first, `ape.mopDataPath=…` when the static-analysis push succeeded, then one `ape.<key>=<value>` line per override (destination: `/data/local/tmp/ape.properties`, consumed by the stage-2 jar's `RunSpec` resolution)
- migration record — final regeneration diff output + baseline JSON, archived in the module's `docs/` after owner sign-off (destination: post-hoc audit trail)

### Side-Effects

- **[Device]**: unchanged push flow (jar, broadcast catalog, compacted static-analysis JSON, properties file)
- **[Jar]**: unknown key, invalid type, or invalid combination in the pushed properties aborts the run before step 1 (stage-2 fail-fast); the effective plan is echoed write-only in `RUN_START`

### Error

- `ConfigurationError` — arm configured without a `preset` key, or an `overrides` key with no mapping entry (caught before any device interaction, same policy as strategy validation)
- Jar-side abort — any key or combination the jar rejects; visible in the trace, never silent

## Invariants

- **INV-APV-40**: Every variant returned by `get_variants()` MUST consist of a `preset` name, an `overrides` dict (possibly empty), and Python-only orchestration keys. No variant may carry a full property expansion; the substrate spread dicts are deleted.
- **INV-APV-41**: `APERV_PROPERTY_MAPPING` MUST contain only keys the deployed jar validates. Dead keys are removed, not commented out. `llm_snap_tolerance_px` and `llm_max_tokens` are live jar keys and MUST remain mapped.
- **INV-APV-42**: The 27 surviving variant names are frozen. The variant string is the resume-identity key and the consolidation column key; re-expression MUST NOT rename any arm. An owner-approved intentional divergence in effective configuration MUST be introduced as a new declared arm name, never as a silent edit. The two retired names (`ape_pure`, `bfs` — design D2) are the only removals, and MUST be recorded as documented retirements in the migration arm report rather than appearing as regeneration diffs.
- **INV-APV-43**: `tool.py` MUST NOT parse, validate, or branch on `RUN_START` or any other jar echo output (owner decision D1 — level 0 is definitive). Drift auditing is post-hoc analysis.
- **INV-APV-44**: During the migration (until owner sign-off), every **surviving** arm's regenerated effective configuration MUST diff empty against `arm_effective_baseline.json`. The baseline is captured over all 29 pre-change arms; the two retired names are excluded from the diff by an explicit retirement list, never by silent absence. The check is one-time: after sign-off the test is deleted and the record archived — it MUST NOT survive as a standing constant-vs-constant guard.

## MODIFIED Requirements

### Requirement: Tool Variants

`ApeRVTool.get_variants()` SHALL return exactly the 27 surviving frozen variant names: `default`, `sata`, `random`, `sata_mop_widget`, `sata_mop`, `sata_mop_activity`, `sata_mop_act_frontier`, `sata_llm`, `sata_mop_llm`, the six frozen gh43 prompt arms (`sata_mop_llm_ape_current`, `sata_mop_llm_ape_reasoning`, `sata_mop_llm_compact_v1`, `sata_mop_llm_v13`, `sata_mop_llm_v17`, `sata_mop_llm_visual_only`), the nine calibration arms (`cal_a1`…`cal_a9`), and the three gh90 decisive-run arms (`mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70`).

The two retired variants SHALL NOT be returned (design D2, owner ratified at artifact approval):

- `ape_pure` — no structural-purity preset exists. `ape.apePureMode` is a retired key that aborts resolution (stage 2), and purity is structural: a plan without a feature has no feature, and the effective plan is echoed in `RUN_START`. Consistent with owner decision D3 — the campaign control is the minimal `aperv` preset, and comparison with original APE stays anchored on the frozen phase-2 data.
- `bfs` — `bfs` was never an agent type. `ApeAgent.createAgent` (`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:68-96`) recognizes exactly `sata`, `random`, and `replay`, with every other value falling through silently to `new SataAgent` (verified V9). The `bfs` variant therefore carried the same effective configuration as `sata`, differing only in a strategy string the jar ignores. Retiring it removes a duplicate, not an experimental arm; after stage 2 an unknown `--ape` value aborts, so the variant could not run at all.

Both retirements SHALL be recorded as documented removals in the migration arm report (INV-APV-42).

Each surviving variant SHALL be expressed as **preset + explicit overrides** (INV-APV-40):

- `preset: str` — one of the jar-resident preset names (stage 2: `aperv`, `mop`, `llm`, `llm_mop`). The jar, not Python, defines what the preset means.
- `overrides: Dict[str, Any]` — only the deltas that distinguish this arm from its preset. An arm identical to its preset carries an empty dict.
- Python-only orchestration keys at top level: `strategy` (CLI `--ape` flag), `mop_data` (`"static_analysis"` triggers the static-analysis JSON push, unchanged), and for `mop_on_llm_70` the B3 pairing keys `expected_jar_git_sha`/`expected_jar_sha256` (never written to `ape.properties`).

Preset assignment: `default`/`sata`/`random` → `aperv`; `sata_mop_widget`/`sata_mop`/`sata_mop_activity`/`sata_mop_act_frontier`/`mop_on_llm_off`/`mop_off_llm_off` → `mop`; `sata_llm` → `llm`; all remaining LLM arms → `llm_mop`. Ablations (e.g. the frontier reach package, the gh90 MOP-off contrast) SHALL be expressed as named override sets, never as new presets.

`sata_mop` SHALL remain the back-compat alias of `sata_mop_widget`, bound to the same object. `default` SHALL remain equivalent to `sata`. The six gh43 arms remain frozen in the sense that matters — their **effective configuration** is preserved (verified by the regeneration migration check) — while their source shape becomes preset + overrides like every other arm; the `_ARM_DEFINING_EXEMPT` machinery is deleted with the guard it exempted from.

Every arm's effective configuration after re-expression SHALL be identical to its pre-change effective configuration (INV-APV-44); any intentional divergence requires owner approval and a new arm name (INV-APV-42).

#### Scenario: MOP arm as preset plus deltas

- **WHEN** `get_variants()["sata_mop_act_frontier"]` is read
- **THEN** `preset` SHALL be `"mop"` and `mop_data` SHALL be `"static_analysis"`
- **AND** `overrides` SHALL contain exactly the frontier reach deltas (`mop_activity_source_components=True`, `frontier_boost_weight=200`, `mop_frontier_weight=200`, `activity_trigger_enabled=True`)
- **AND** no key of the former `_BASELINE_ARM_FLAGS`/`_MOP_SUBSTRATE` expansion SHALL appear unless it is a genuine delta over the preset

#### Scenario: Arm identical to its preset

- **WHEN** `get_variants()["sata_mop_llm"]` is read
- **THEN** `preset` SHALL be `"llm_mop"` and `overrides` SHALL be empty

#### Scenario: gh90 single-factor contrast visible at the definition site

- **WHEN** `get_variants()["mop_off_llm_off"]` and `get_variants()["mop_on_llm_off"]` are compared
- **THEN** both SHALL carry `preset="mop"` and the same frontier override base
- **AND** the difference SHALL be exactly the MOP-off deltas (the five zeroed weights and `activity_trigger_enabled=False`), readable from the override dicts without any expansion machinery
- **AND** `mop_off_llm_off` SHALL keep `mop_data="static_analysis"` (the control removes MOP guidance, not the substrate document or navigation)

#### Scenario: Alias preserved

- **WHEN** `get_variants()` is read
- **THEN** `variants["sata_mop"]` SHALL be the same object as `variants["sata_mop_widget"]`

#### Scenario: Retired variants are absent

- **WHEN** `get_variants()` is read after this change
- **THEN** `"ape_pure"` and `"bfs"` SHALL NOT be keys of the returned mapping
- **AND** the module SHALL contain no `_APE_PURE_ARM_FLAGS` constant (purity is structural in the jar: a plan without a feature has no feature, and the effective plan is echoed in `RUN_START`)
- **AND** the two names SHALL appear in the migration arm report as documented removals, not as diffs

---

### Requirement: configure() Method

`ApeRVTool.configure(config)` SHALL store the resolved variant configuration in `self._tool_config`. It SHALL validate that `config["strategy"]` is one of `["sata", "random"]`, that `config["preset"]` is present and non-empty, and that `config.get("overrides", {})` is a dict. If any check fails, it SHALL raise `ConfigurationError` before any device interaction.

The whitelist SHALL shrink from the pre-change `["sata", "random", "bfs", "dfs"]` — the deletion `rearch-02-runspec` delegates to this stage (its design records the consequence: `bfs`/`dfs` silently ran `SataAgent` before stage 2 and abort after it). `bfs` and `dfs` are not agent types (V9), so accepting them Python-side would let a run pass local validation and abort on the device — reintroducing the silent-degradation class stage 2 exists to remove. `replay` is legal in the jar but is NOT accepted here: it requires `--ape-replay <log>`, which this tool never passes.

#### Scenario: Valid preset arm configured

- **WHEN** `configure({"strategy": "sata", "preset": "mop", "overrides": {}})` is called
- **THEN** `self._tool_config["preset"]` SHALL equal `"mop"`
- **AND** no exception SHALL be raised

#### Scenario: Missing preset raises ConfigurationError

- **WHEN** `configure({"strategy": "sata"})` is called
- **THEN** `ConfigurationError` SHALL be raised naming the missing `preset` key

#### Scenario: Invalid strategy raises ConfigurationError

- **WHEN** `configure({"strategy": "unknown", "preset": "aperv"})` is called
- **THEN** `ConfigurationError` SHALL be raised with a message listing valid strategies

#### Scenario: Retired strategy rejected Python-side, not on the device

- **WHEN** `configure({"strategy": "bfs", "preset": "aperv"})` or `configure({"strategy": "dfs", "preset": "aperv"})` is called
- **THEN** `ConfigurationError` SHALL be raised before any device interaction
- **AND** the run SHALL NOT reach the jar, where it would abort as an unknown `--ape` value (stage 2)

---

### Requirement: execute_tool_specific_logic() Flow

`ApeRVTool.execute_tool_specific_logic(task, app)` SHALL execute the following steps in order:

1. Resolve device serial from `task.config.device_id` (default `"emulator-5554"`)
2. Resolve timeout from `task.config.timeout` (default `300` seconds); convert to minutes for APE's `--running-minutes` flag (minimum 1 minute); command timeout keeps the +45 s teardown grace
3. Resolve `ape-rv.jar` via `_resolve_jar_path()` and push to `/data/local/tmp/ape-rv.jar`
4. Push `system-broadcast.json` when present (component triggering, unchanged)
5. If `_tool_config.get("mop_data") == "static_analysis"`: locate, compact, enrich, and push the static-analysis JSON exactly as today (INV-APV-20..25/31/32 unchanged); on success set `mop_json_pushed = True`, otherwise log the WARNING and continue
6. Push `ape.properties` generated as: `ape.preset=<preset>` first; `ape.mopDataPath=/data/local/tmp/static_analysis.json` when `mop_json_pushed`; then one `ape.<key>=<value>` line per entry of `overrides`, translated through `APERV_PROPERTY_MAPPING`, with Python bools serialized lowercase. The full property expansion of the pre-change mapping loop SHALL NOT be performed
7. Capture LLM provenance to the sidecar for arms with `llm_url` in effect (unchanged, INV-APV-33)
8. Build and execute the main command (`--ape <strategy>`, `-s <seed>` when configured), capturing stdout+stderr to `task.result.trace_file` (the captured stream is the NDJSON trace, per `rearch-04-step-ndjson-telemetry`)
9. On `RVCommandTimeoutError`: log as expected behaviour, run the collection steps 11–12 below, then re-raise as `RVToolTimeoutError` — timeout is the normal exit for exploration runs, so collection MUST NOT be skipped on it
10. Run the empty-trace check (`_check_empty_trace`, unchanged — a 0-byte NDJSON trace is still 0 bytes)
11. **Gzip at collection** (unchanged from `rearch-04-step-ndjson-telemetry`): compress the raw NDJSON capture to `<trace>.ndjson.gz` next to the trace file. On failure, log a WARNING and continue
12. **Temporary legacy conversion** (unchanged from `rearch-04-step-ndjson-telemetry`): run the NDJSON→legacy converter over the raw capture and write the reconstructed legacy line family (`[APE-STEP]`, `[APE-OUTCOME]`, `[APE-LLM-TEL]`, `[APE-LLM-ERROR]`, `[APE-LLM-CONFIG]`, `[APE-LLM-CONFIG-ACK]`, `[APE-MOP-DATA]`, `[APE-RV] LLM Summary` / `Decision ratio`) to `task.result.trace_file` itself, so existing parsers keep reading exactly the file and format they read today. On failure, log a WARNING and leave the raw NDJSON trace in place

Steps 11–12 SHALL NOT inspect, validate, or act on the trace's content beyond mechanical transformation: no `RUN_START`/`RUN_END` presence check, no exit-code interpretation beyond the existing debug log, no task-status change (owner decision D5). The converter SHALL reconstruct legacy semantics mechanically: expand `act`/`st` dictionary IDs to activity/state strings, re-emit omitted defaults (`mop=0 mop_frontier=0 wtg=0 coverage=0 menu=0 form=0`, `new_state=false`, `activity_changed=false`), re-derive `activity_has_mop` from the `ACT` entries, re-expand `t` to epoch `clock=` via `RUN_START.t0`, split `llm[]` sub-events back into per-call lines keyed by the record's `s`, and expand `RUN_END.counters` into the summary lines. The converter is temporary: it is deleted when the analysis pipeline consumes NDJSON natively (a later change; not triggered here).

The tool SHALL NOT read back, parse, or validate any jar output (`RUN_START` included) — provenance is write-only in the trace (INV-APV-43, owner decision D1).

#### Scenario: Post-run conversion keeps current parsers working

- **WHEN** a run completes and the captured `task.result.trace_file` contains NDJSON records
- **THEN** after step 12, `task.result.trace_file` SHALL contain the legacy `key=value` line family reconstructing every field current parsers consume
- **AND** `<trace>.ndjson.gz` SHALL contain the compressed raw NDJSON capture

#### Scenario: Converter failure is non-fatal and write-only

- **WHEN** the converter raises on a malformed line
- **THEN** a WARNING SHALL be logged, the raw NDJSON trace SHALL remain at `task.result.trace_file`, and the task SHALL complete with the same status it would have had otherwise (D5: no validation, no status logic)

#### Scenario: No exit contract

- **WHEN** a trace ends without a `RUN_END` record (e.g. SIGKILL on timeout before teardown)
- **THEN** the tool SHALL NOT detect, log, or act on its absence
- **AND** truncated-run identification remains a post-hoc analysis over trace/logcat timestamps

#### Scenario: Properties file carries preset plus deltas only

- **WHEN** `_push_properties()` runs for `sata_mop_act_frontier` with the static-analysis JSON pushed
- **THEN** the generated file SHALL begin with `ape.preset=mop`
- **AND** SHALL contain `ape.mopDataPath=/data/local/tmp/static_analysis.json`
- **AND** SHALL contain exactly the four override lines (`ape.mopActivitySourceComponents=true`, `ape.frontierBoostWeight=200`, `ape.mopFrontierWeight=200`, `ape.activityTriggerEnabled=true`)
- **AND** SHALL NOT contain any other `ape.*` line

#### Scenario: Empty-override arm

- **WHEN** `_push_properties()` runs for `sata_mop_llm` with the static-analysis JSON pushed
- **THEN** the generated file SHALL contain exactly `ape.preset=llm_mop` and the `ape.mopDataPath` line

#### Scenario: Unmapped override key aborts before push

- **WHEN** an arm's `overrides` contains a key absent from `APERV_PROPERTY_MAPPING`
- **THEN** `ConfigurationError` SHALL be raised before any `adb push`

#### Scenario: No echo read-back

- **WHEN** the run completes and the trace contains the jar's `RUN_START` line
- **THEN** the tool SHALL NOT have parsed or acted on it (write-only provenance; drift auditing is post-hoc)

## ADDED Requirements

### Requirement: Arm Property Overrides Pass-Through

`APERV_PROPERTY_MAPPING` SHALL be reduced to an override pass-through table: it exists only to translate Python override keys to `ape.*` names, and it SHALL contain only keys the deployed jar validates (INV-APV-41). Behavioral validation of values, types, dependencies, and combinations is the jar's responsibility (stage-2 fail-fast); the Python side performs no semantic validation of overrides beyond the mapping-membership check.

Deletions at this change: `mop_weight_activity → ape.mopWeightActivity` (dead in the jar, 0 hits in `src/main`, finding 3.3-7) and `ape_pure_mode → ape.apePureMode` (dead by construction once stage 2 deletes the kill-switch mechanism). At implementation time the remaining entries SHALL be re-swept against the deployed jar's accepted-key vocabulary and any further dead entry removed. `llm_snap_tolerance_px` (live jar key, finding 3.3-5) SHALL remain mapped and reach the jar only as an explicit override of the arms that set it (`mop_on_llm_70`), subject to the jar's own feature-dependency validation; its Python-side B3 pairing with the declared jar digests (INV-APV-34) is retained unchanged.

#### Scenario: Dead key removed

- **WHEN** `APERV_PROPERTY_MAPPING` is inspected after this change
- **THEN** it SHALL NOT contain `mop_weight_activity` nor `ape_pure_mode`

#### Scenario: Live ungoverned key brought into the normal path

- **WHEN** `get_variants()["mop_on_llm_70"]` is read
- **THEN** `llm_snap_tolerance_px: 150` SHALL be an entry of its `overrides` dict
- **AND** `_push_properties()` SHALL write `ape.llmSnapTolerancePx=150` for that arm
- **AND** the `expected_jar_git_sha`/`expected_jar_sha256` pairing keys SHALL remain Python-only and never reach `ape.properties`

---

### Requirement: One-Time Arm Regeneration Migration Check

Before any arm is edited, a capture script SHALL record each of the 29 arms' effective configuration — the canonical `{ape.key: value}` map obtained by expanding the pre-change generated `ape.properties` over the jar's behavioral defaults — to a committed baseline file (`tests/migration/arm_effective_baseline.json`). During the migration, a pytest (`tests/migration/test_arm_regeneration_diff.py`) SHALL recompute each re-expressed arm's effective configuration (preset table + overrides) and assert an empty diff against the baseline, per arm (INV-APV-44). The two retired names (`ape_pure`, `bfs`) SHALL be carried in an explicit retirement list that the test reads: they are reported as documented retirements and excluded from the diff, so a retirement can never be confused with a regeneration failure — nor a silent deletion with a retirement. Preset tables and defaults are read from the ape repo source checkout for this purpose only — the migration tooling is not a runtime dependency and creates no shared manifest.

The check SHALL be re-run after every task group that edits arms, and gates the change (roadmap stage-5 gate). A non-empty diff is either a re-expression bug (fix) or an intentional divergence (owner approval + new arm name — INV-APV-42). After the final full diff and owner sign-off, the test SHALL be deleted and the baseline plus final diff output archived in the module's `docs/` as the migration record: the check is one-time by design and MUST NOT become a standing constant-vs-constant guard (owner decision D1).

#### Scenario: Baseline captured before edits

- **WHEN** the migration starts
- **THEN** `arm_effective_baseline.json` SHALL exist and cover all 29 arm names before any variant dict is modified

#### Scenario: Re-expression gated per group

- **WHEN** a group of arms is re-expressed as preset + overrides
- **THEN** `test_arm_regeneration_diff.py` SHALL pass with an empty diff for every arm (migrated and not-yet-migrated alike)

#### Scenario: Check retired after sign-off

- **WHEN** the owner signs off the final full diff
- **THEN** the migration test SHALL be deleted and the baseline + diff archived
- **AND** no standing test SHALL compare arm definitions against a frozen copy of themselves

## Notes

### Disposition of `Arm-Defining Flag Completeness (FR20)` — cross-repo, not a delta operation here

This requirement does **not** live in this repository's `aperv-tool` capability; it is held in rv-android's own spec (`rv-android/openspec/specs/aperv/spec.md`, requirement "Arm-Defining Flag Completeness (FR20)"). A `REMOVED` block here would name a requirement absent from `openspec/specs/aperv-tool/spec.md` and would therefore sync to nothing — `openspec validate --strict` cannot detect that, so the disposition is recorded as a note instead, and the removal is executed in rv-android's own OpenSpec workflow (tasks group 8). The reasoning and the recorded substitute follow.

**Reason**: This requirement (its executable form is the INV-APV-13/14/15/17/26/27 guard family in `tests/test_aperv_tool.py` and the `ARM_DEFINING_KEYS`/`LLM_ARM_KEYS`/`_ARM_DEFINING_EXEMPT` constants in `tool.py`) enforced arm explicitness by validating Python constants against other Python constants — a self-referential check that never touched the binary that runs (verified V20). With arms expressed as preset + overrides, the enforced property dissolves: an arm's identity is its preset (jar-resolved, fail-fast validated) plus its override deltas; there is no expansion left to keep complete, and a missing or misspelled key aborts the run in the jar instead of passing silently. Complete deletion — the constants, the guard tests, the frozen name-table pins, and the cal/gh90 expansion-diff tests are removed with no compatibility shim.

**Substitute recorded**: (a) the one-time regeneration migration check (INV-APV-44) proves the re-expression preserved the calibrated grid; (b) level-0 echo provenance — every trace begins with `RUN_START` carrying the effective plan, digest, and jar version, so "which arm ran this task" is answerable from the trace alone, post-hoc (owner decision D1: no runtime validation replaces the guards). The kill-switch defense-in-depth rows of this requirement are substituted by structural purity in the jar (stage 2): a plan without a feature contains no feature, and the echo proves it.
