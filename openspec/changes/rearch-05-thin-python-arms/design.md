# Design: rearch-05-thin-python-arms

## Context

Stage 5 of 7 of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3) — the first stage that touches rv-android. Depends on `rearch-02-runspec` being implemented and the resulting jar **deployed** to the aperv-tool module: presets resident in the jar (`Presets.resolve`), total fail-fast validation (unknown key ⇒ abort before step 1), and the level-0 `RUN_START` echo (owner decision D1).

Current state, verified in this design against the live tree (rv-android `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`):

- **29 arms** in `ApeRVTool.get_variants()` (11 base + 6 frozen gh43 prompt arms + 9 cal_a1…cal_a9 + 3 gh90 decisive-run arms). Verified by executing the module: 29 exactly.
- **`APERV_PROPERTY_MAPPING` = 52 pairs** (`tool.py:77-164`). Verified: 52 exactly.
- **`ARM_DEFINING_KEYS` = 18**, **`LLM_ARM_KEYS` = 11**, `_ARM_DEFINING_EXEMPT` = 6 — Python constants guarded by pytest against other Python constants (INV-APV-13/14/15/17/26/27, `tests/test_aperv_tool.py`, e.g. `test_non_exempt_variants_set_all_arm_defining_keys` at `:629-638`).
- **Kill-switch duplicated and divergent** (verified V20): `_APE_PURE_ARM_FLAGS` = 18 Python keys (`tool.py:266-285`) vs the jar's `Config.rvForcedOffValues()` = 27 keys (`Config.java:343-364`). The Java side forces `llmOnNewState`, `llmOnStagnation`, `llmPercentage`, all `mopWeight*`, `coverageBoostWeight`, `componentPercentage`, `mopTargetPickCap`, `activityStableRestartThreshold` — none of which the Python dict sets.
- **Dead key** (finding 3.3-7): `mop_weight_activity → ape.mopWeightActivity` (`tool.py:97`) — swept in this design against `src/main`: **0 hits**; the only dead key among the 52.
- **`llm_snap_tolerance_px` is a live key outside all guards** (finding 3.3-5): `Config.java` reads `ape.llmSnapTolerancePx`, the arm `mop_on_llm_70` sets it (`tool.py:769`), and it belongs to no guard set. It is NOT dead — it must stay, routed through the normal override path.
- Per-arm expansion sizes today (computed from the live module): `sata` writes 19 property lines, `sata_mop_act_frontier` 23, `sata_mop_llm` 31, `cal_a1` 33, `mop_on_llm_70` 34.

Owner decisions that constrain this design (report Sec. 12 — final, do not reopen):

- **D1**: the echo is level 0, definitive. **No automatic echo-vs-intent validation, ever.** `tool.py` never parses `RUN_START`; drift auditing stays post-hoc analysis.
- The comparability rule (proposal): arms' effective configurations must be diff-identical before/after re-expression; any intentional divergence is a declared new arm, never a silent edit.

## Architecture

```text
BEFORE (per arm)                          AFTER (per arm)
──────────────────                        ─────────────────
{ **_BASELINE_ARM_FLAGS,   (18 keys)      { "preset": "mop",          (1 key)
  **_MOP_SUBSTRATE,        (5 keys)         "strategy": "sata",       (Python-only, CLI)
  "strategy": "sata",                       "mop_data": "static_analysis",  (Python-only)
  "throttle_ms": 200,                       "overrides": { …deltas only… } }
  …per-arm deltas… }                              │
        │                                         ▼
        ▼                                 _push_properties writes:
_push_properties expands 19–34            ape.preset=mop
ape.* lines from the 52-pair map          ape.mopDataPath=…          (when pushed)
                                          ape.<override>=<value>     (deltas only)
        │                                         │
        ▼                                         ▼
jar: Config static, silent on             jar (stage 2): Presets.resolve("mop")
unknown keys, meaning of the arm          + overrides, fail-fast on unknown key,
reconstructed only via tool.py            RUN_START echoes the effective plan
```

The jar becomes the sole authority on what a preset *means*; the Python side remains the sole authority on the *experimental matrix* (which arms exist, their names, their overrides). The two meet only through `ape.properties` (unchanged transport) and the write-only `RUN_START` line (unchanged collection).

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `ApeRVTool.get_variants()` | 29 arm definitions as `preset + overrides` | — | `Dict[str, Dict]` (names frozen) |
| `APERV_PROPERTY_MAPPING` | Python key → `ape.*` translation for override pass-through only | override keys | `ape.*` names (reduced set) |
| `ApeRVTool._push_properties()` | Write `ape.preset` + `ape.mopDataPath` + override lines | `_tool_config` | `ape.properties` on device |
| `tests/migration/capture_arm_baseline.py` | One-time pre-change capture of the 29 arms' effective configs | live `tool.py` + jar defaults | `arm_effective_baseline.json` |
| `tests/migration/test_arm_regeneration_diff.py` | One-time migration gate: regenerated effective configs == baseline | baseline JSON + re-expressed arms + preset tables | pass/fail per arm |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| Tool Variants (MODIFIED: preset + overrides) | `ApeRVTool.get_variants()` | restated structural tests in `test_aperv_tool.py` (names, the `sata_mop`/`sata_mop_widget` binding, override dicts) |
| execute flow (MODIFIED: `ape.preset` line) | `_push_properties()` | restated `TestPushProperties`/`TestArmProperties` |
| Arm Property Overrides Pass-Through | `APERV_PROPERTY_MAPPING` (reduced) | properties-writer tests; jar-side fail-fast is the runtime enforcement |
| One-Time Arm Regeneration Migration Check | `tests/migration/` | `test_arm_regeneration_diff.py` (deleted after owner sign-off) |
| INV-APV-43 (no runtime echo validation, D1) | absence of any `RUN_START` parser in `tool.py` | review gate; grep in the final diff task |

## Goals / Non-Goals

**Goals:**

- Re-express all 29 arms as `preset + explicit override deltas`, preserving every arm's **name** (the variant string is the resume-identity and consolidation column key — a rename silently splits a campaign) and every arm's **effective configuration** (regeneration diff empty).
- Shrink `APERV_PROPERTY_MAPPING` to the keys the deployed jar validates; delete dead keys.
- Delete the Python kill-switch duplication (`_APE_PURE_ARM_FLAGS`) — purity is structural in the jar since stage 2.
- Retire the constant-vs-constant pytest guards (INV-APV-13/14/15/17/26/27 family) and the constants they guard (`ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`, `_ARM_DEFINING_EXEMPT`).
- Replace them with: (a) the one-time regeneration diff (migration), and (b) level-0 echo provenance (`RUN_START` reconstructs the arm from the trace alone) — with **no runtime validation** (D1).

**Non-Goals:**

- No jar changes. Presets, fail-fast, and the echo are stage-2 deliverables; if a preset is found missing or wrong during this change, that is a stage-2 defect to report, not something to patch here.
- No change to task orchestration, with one exception: `configure()`'s strategy **whitelist** shrinks to `["sata", "random"]` (the `bfs`/`dfs` deletion `rearch-02-runspec` delegates to this stage; see D2). The validation mechanism itself, JAR resolution/push, static-analysis discovery + compaction + enrichment (INV-APV-20..25/31/32), seed propagation (INV-APV-18), the `+45 s` command grace, timeout-as-normal-exit, empty-trace detection, LLM provenance capture (INV-APV-33), and the B3 snap-tolerance/jar-sha pairing (INV-APV-34) all stay as they are.
- No new communication channel, no `RUN_START` parser, no echo-vs-intent check (D1).
- No new arms, no re-tuning: byte-identical effective configs or a documented, owner-approved divergence.

## Decisions

### D1: Arm shape — `preset` key + `overrides` sub-dict, Python-only orchestration keys stay top-level

An arm becomes:

```python
"sata_mop_act_frontier": {
    "preset": "mop",                     # jar preset name → ape.preset line
    "strategy": "sata",                  # Python-only → --ape CLI flag (unchanged)
    "mop_data": "static_analysis",       # Python-only → push static_analysis.json (unchanged)
    "overrides": {                       # deltas over the preset, written as ape.* lines
        "mop_activity_source_components": True,
        "frontier_boost_weight": 200,
        "mop_frontier_weight": 200,
        "activity_trigger_enabled": True,
    },
},
```

An explicit `overrides` sub-dict (rather than mixing override keys into the top level) keeps the boundary machine-checkable: everything under `overrides` is translated and written; everything top-level is Python orchestration (`preset`, `strategy`, `mop_data`, `seed`, `expected_jar_git_sha`, `expected_jar_sha256`). The single-factor contrasts of the gh90 arms become visible at the definition site — the override dict *is* the diff the old guard tests computed by expansion.

Alternative considered: flat dicts with `preset` inline and overrides at top level (smaller diff to today's shape). Rejected: the Python-only/pass-through split would again live in a constant (`the mapping`), which is the pattern this change retires.

### D2: Preset vocabulary is the jar's, and only the jar's

The stage-2 presets are `aperv`, `mop`, `llm`, `llm_mop` (rearch-02 proposal). Arm mapping:

- `default`, `sata`, `random` → `preset: "aperv"`, no overrides (strategy differs via CLI).
- `ape_pure` and `bfs` → **retired** (resolved by the rearch-02 design: no structural-purity preset exists — `ape.apePureMode` is a retired key that aborts resolution, and an unknown `--ape` value aborts instead of silently falling back. Consistent with owner decision D3: the campaign control is the minimal `aperv` preset. This change deletes both variants and records the retirement in the arm report; owner ratifies at artifact approval).
- `sata_mop` / `sata_mop_widget` (one object under two names; `sata_mop` is the one the frozen corpus uses) → `preset: "mop"`, no overrides.
- `sata_mop_activity`, `sata_mop_act_frontier`, `mop_on_llm_off`, `mop_off_llm_off` → `preset: "mop"` + override deltas. Ablation = named override set, never a new preset (report Sec. 6.2).
- `sata_llm` → `preset: "llm"`; `sata_mop_llm`, the 6 gh43 arms, the 9 cal arms, `mop_on_llm_70` → `preset: "llm_mop"` + override deltas.

Python never mirrors preset contents. The only place preset contents are read outside the jar is the one-time migration script (D7), which parses them from the ape repo **source** checkout — a migration tool, not a runtime dependency.

### D3: Concrete before/after examples (the shrink, from the real tool.py)

**(a) Plain MOP arm — `sata_mop_act_frontier`** (today: `**sata_mop_widget` + 4 deltas = 25-key dict, 23 property lines):

```python
# BEFORE (effective source shape after spreads)
{**_BASELINE_ARM_FLAGS, **_MOP_SUBSTRATE, "strategy": "sata", "throttle_ms": 200,
 "mop_activity_source_components": True, "frontier_boost_weight": 200,
 "mop_frontier_weight": 200, "activity_trigger_enabled": True}
# AFTER
{"preset": "mop", "strategy": "sata", "mop_data": "static_analysis",
 "overrides": {"mop_activity_source_components": True, "frontier_boost_weight": 200,
               "mop_frontier_weight": 200, "activity_trigger_enabled": True}}
```

**(b) LLM+MOP arm — `sata_mop_llm`** (today: 33-key dict, 31 property lines):

```python
# BEFORE
{**_BASELINE_ARM_FLAGS, **_MOP_SUBSTRATE, **_LLM_FLAGS, "strategy": "sata", "throttle_ms": 200}
# AFTER — the preset IS this arm; nothing to override
{"preset": "llm_mop", "strategy": "sata", "mop_data": "static_analysis", "overrides": {}}
```

**(c) gh90 decisive-run arm — `mop_on_llm_70`** (today: 38-key dict, 34 property lines):

```python
# AFTER — frontier ablation + the cal_a1 LLM dose + B3 pairing, all visible as deltas
{"preset": "llm_mop", "strategy": "sata", "mop_data": "static_analysis",
 "overrides": {"mop_activity_source_components": True, "frontier_boost_weight": 200,
               "mop_frontier_weight": 200, "activity_trigger_enabled": True,
               "llm_prompt_variant": "v13", "llm_percentage": 0.7, "llm_temperature": 0,
               "llm_top_p": 0.6, "llm_top_k": 50,
               "llm_on_new_state": True, "llm_on_stagnation": True,
               "llm_snap_tolerance_px": 150},
 "expected_jar_git_sha": "…", "expected_jar_sha256": "…"}   # Python-only, INV-APV-34 unchanged
```

The exact override contents per arm are **not** authored free-hand: they are derived as `effective(arm) − effective(preset)` from the captured baseline (D7), so a delta that looks redundant but is load-bearing (e.g. a cal arm restating `llm_top_p: 0.6` where the preset already says 0.6) is dropped only if the regeneration diff stays empty.

**(d) Ablation arm — `mop_off_llm_off`** (today: `**_FRONTIER_SUBSTRATE` + `**_MOP_OFF_OVERRIDES`, 25-key dict):

```python
# AFTER — "the reference arm minus MOP guidance", readable at the definition site
{"preset": "mop", "strategy": "sata", "mop_data": "static_analysis",
 "overrides": {"mop_activity_source_components": True, "frontier_boost_weight": 200,
               "activity_trigger_enabled": False,
               "mop_weight_direct": 0, "mop_weight_transitive": 0,
               "mop_weight_open_menu": 0, "mop_weight_wtg": 0, "mop_frontier_weight": 0}}
```

The INV-APV-29/30 rationale (MOP-off must keep `mop_data` present and the frontier navigation alive) moves verbatim into the arm's comment — it is semantics of the experiment, not of the retired guard machinery.

### D4: `_push_properties` — preset line + override pass-through, full expansion dropped

Output contract (order fixed for diffability):

```text
ape.preset=<preset>                                  # always first
ape.mopDataPath=/data/local/tmp/static_analysis.json # only when the JSON push succeeded (unchanged)
ape.<mapped-override-key>=<value>                    # one line per overrides entry, mapping order
```

Bool serialization (`True → true`) unchanged. Keys in `overrides` with no mapping entry are a hard `ConfigurationError` at push time — under fail-fast a typo'd key would abort the run anyway, but catching it before the device push saves emulator minutes (same rationale as INV-APV-02). The 52-pair expansion loop and the substrate spread dicts (`_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS`, `_MOP_SUBSTRATE`, `_LLM_FLAGS`, `_FRONTIER_SUBSTRATE`, `_MOP_OFF_OVERRIDES`, `_CAL_LLM_COMMON`) are deleted.

### D5: Deletion ledger

| Deleted | Where | Substitute |
|---|---|---|
| `mop_weight_activity → ape.mopWeightActivity` | `tool.py:97` | nothing — dead key (0 hits in `src/main`, finding 3.3-7); the sweep confirmed it is the **only** currently-dead key of the 52 |
| `ape_pure_mode → ape.apePureMode` mapping entry | `tool.py:120` | dead **by construction after stage 2** (the jar deletes the `apePureMode` mechanism; the key is retired and aborts resolution); the `ape_pure` variant itself is retired (D2) |
| `_APE_PURE_ARM_FLAGS` (18-key Python kill-switch mirror, divergent from the jar's 27) | `tool.py:266-285` | structural purity in the jar (stage 2): a plan without a feature has no feature; `RUN_START` echoes the effective plan |
| `_BASELINE_ARM_FLAGS`, `_MOP_SUBSTRATE`, `_LLM_FLAGS`, `_FRONTIER_SUBSTRATE`, `_MOP_OFF_OVERRIDES`, `_CAL_LLM_COMMON` | `tool.py:242-369` | jar presets + per-arm override deltas |
| `ARM_DEFINING_KEYS` (18), `LLM_ARM_KEYS` (11), `_ARM_DEFINING_EXEMPT` (6) | `tool.py:173-236` | the guards they fed are retired (D6) |
| INV-APV-13/14/15/17/26/27 guard tests + `_EXPECTED_ARM_DEFINING_MAPPING` + cal-table/decisive-run expansion-diff pins | `tests/test_aperv_tool.py:474-965` (classes `TestArmDefiningGuard`, explicitness/table-pin tests in `TestFrozenArmVariants`/`TestDecisiveRunArms`) | one-time regeneration diff (D7) + level-0 echo provenance; single-factor contrasts restated as trivial assertions on override dicts where still wanted |

At implementation time the mapping is **re-swept** against the deployed stage-2 jar's accepted-key vocabulary (`RunSpec`/`Feature`, not the old `Config`): any additional key the jar no longer accepts must be removed then (fail-fast makes a miss loud, not silent). `llm_max_tokens` and `llm_snap_tolerance_px` are live jar keys (verified in `Config.java`) and **stay**.

`llm_snap_tolerance_px` disposition (finding 3.3-5): it stays in the mapping and becomes an ordinary entry of `mop_on_llm_70.overrides` — validated by the jar like every other LLM sub-key (stage-2 `Feature` dependencies make it inert-by-absence when the LLM feature is off, and an abort if set without the LLM feature). The Python-side B3 pairing (INV-APV-34: tolerance ⇔ declared jar sha256/git sha) is **kept**: it gates against a runtime-computed binary digest, which is provenance, not a constant-vs-constant arm guard.

### D6: Guard retirement — what replaces what, and what stays

Retired: every pytest that validates a Python constant against another Python constant *about arm definitions* — mapping-completeness, explicitness, key-count pins, frozen python→java name tables, cal-arm plan-table pins, and the decisive-run tests that recompute arm diffs by expansion.

Explicitly **not** retired (out of scope, still earning their keep): `configure()` validation (INV-APV-02), command building (INV-APV-04/18), constants (INV-APV-03), compaction/enrichment (INV-APV-20..25/31/32), LLM provenance (INV-APV-33), snap-tolerance pairing (INV-APV-34), empty-trace detection, and the properties-writer serialization tests (restated for the new output contract in D4).

Replacement, per owner decision D1 — and this is the load-bearing point: **there is no runtime replacement.** The substitute is (a) the one-time regeneration diff proving the migration changed nothing, and (b) the standing provenance property that every `.trace` begins with a `RUN_START` line from which the arm's effective plan is reconstructable without consulting `tool.py`. Post-hoc analysis audits drift when there is a reason to suspect it. Any future "level 1" echo-vs-intent check is an owner decision on a real incident, not part of this change.

### D7: The one-time migration check — deterministic regeneration diff

The comparability risk (report Sec. 11: "Mudança nos arms Python quebra o grid calibrado") is closed by construction:

1. **Baseline capture — before any edit.** `tests/migration/capture_arm_baseline.py` computes, for each of the 29 arms, the *effective configuration*: the `ape.properties` lines the current code writes, expanded over the jar's behavioral defaults into a canonical `{ape.key: value}` map (plus `strategy` and `mop_data` as orchestration fields). Defaults come from the stage-2 jar's preset/default tables, parsed from the ape repo source checkout (path via `$RVSEC_HOME` or `$APE_REPO`). Output: `tests/migration/arm_effective_baseline.json`, committed.
2. **Regeneration.** `tests/migration/test_arm_regeneration_diff.py` recomputes the same canonical map from the re-expressed arms (`Presets` table + `overrides`) and asserts, per arm, an **empty diff** against the baseline.
3. **Gate.** The test runs in the module's normal pytest suite (and CI) for the entire life of this change; every task group that edits arms re-runs it. It is the roadmap gate for stage 5.
4. **Divergence protocol.** A non-empty diff is either (a) a bug in the re-expression — fix it; or (b) an intentional divergence — which requires the owner to approve it in writing and the arm to be **renamed** (a new declared arm), never silently edited. No third option.
5. **One-time.** After the final full diff and owner sign-off, the test is deleted and the baseline JSON + final diff output are archived under the module's `docs/` as the migration record. Keeping it alive would recreate INV-APV-14 (a constant validated against a frozen copy of itself), which D1 rejects.

Alternative considered: comparing generated `ape.properties` byte-for-byte. Impossible by design — the file's shape is exactly what changes; only the *resolved plan* is invariant.

### D8: Frozen gh43 arms are re-expressed too

Freezing (INV-APV-17) protects the arms' *effective configuration*, not their source shape. The six arms are re-expressed as `preset: "llm_mop"` + overrides like every other arm, gated by the same empty-diff requirement. Their pre-change reliance on jar defaults (they never carried the arm-defining baseline) is precisely what the effective-config comparison resolves: if the preset value differs from the default a frozen arm silently inherited, the diff catches it and the divergence goes to the owner. The exemption machinery (`_ARM_DEFINING_EXEMPT`) dies with the guard it exempted from.

### D9: Stage 2's transitional surface dies here, and this stage is the only place it can

Stage 2 had a real constraint: deploy the resolver against an **unchanged** `tool.py`. It met that constraint honestly and recorded it in two places — `RunSpecCompatTest` plus per-arm fixtures reproducing `_push_properties`' output for the four campaign arms, and a `run-spec` requirement (`Explicit-Key Resolution When No Preset Is Named`) written entirely in transitional voice: "the case for the entire current Python deployment, which this change does not touch", with a scenario literally named *zero Python changes verified*.

Stage 2 also declared the death: its design says "stage 5 replaces the fixtures with the real contract" and its task 6.3 says the pin holds "until stage 5". Nothing in this change's original artifacts executed that. That is the gap this decision closes.

**Why this stage and not a later one.** Task 2.1 rewrites `_push_properties`; groups 3–6 re-express every arm. From the moment those land, the fixtures freeze a byte-for-byte copy of a deployment that no longer exists, and the "zero Python changes verified" scenario is false by construction. A test that pins a superseded shape is exactly the frozen-copy-of-itself pattern D1 and D6 reject everywhere else in this change — retiring the arm guards while keeping a preset-fixture guard would be incoherent.

**What replaces them, and what does not need replacing.** The fixtures approximated one property: that a preset resolves to the same effective plan the arm used to produce. That property is proved directly by the one-time regeneration diff (D7), which compares *effective configurations* rather than generated bytes, and is itself one-time by design. `PresetsTest` survives as a test of the preset contract — `Presets.resolve(name)` returns its declared base vector, explicit keys override it, the merged result passes the same validation as an explicit plan — asserted against the preset definitions, never against a captured copy of `tool.py`'s output. The retired-key coverage the `ape_pure` fixture carried (`ape.apePureMode`, `ape.mopWeightActivity`) moves to `RunSpecAbortTest`, whose subject is the retired-key list itself and which was never transitional.

**What survives in the requirement.** The no-preset case is not a compatibility affordance and does not disappear: a jar launched with no properties file must still resolve from its own defaults, which is what makes a bare standalone run valid (`rearch-02` design `:166`). The delta keeps that and drops the paragraph describing the Python deployment that used to depend on it, along with the two transitional scenarios.

**Scope consequence, stated rather than discovered.** Deleting these tests edits the ape repo. It does not modify the jar — no `src/main` file is touched, no preset value moves, the deployed binary is byte-identical. The tasks' scope line is widened to say this explicitly (group 10), because "all edits land in rv-android" would otherwise read as a prohibition on the one thing this decision requires.

## Data Flow

1. Experiment YAML selects variant → factory merges `{**variant, **parameters}` → `configure()` validates `strategy` (unchanged) and now also validates `preset` is present and `overrides` is a dict.
2. `execute_tool_specific_logic()` (unchanged flow): jar push → broadcast catalog → static-analysis compaction+push (`mop_data`) → `_push_properties()` writes `ape.preset` + `ape.mopDataPath` + override lines → provenance sidecar (LLM arms) → main command with `--ape <strategy>` and `-s <seed>`.
3. Jar (stage 2): resolves preset, applies overrides, fail-fast validates, echoes `RUN_START` as the first trace line. The Python side reads **nothing** back (D1).

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Override key not in mapping | `_push_properties()` | raise `ConfigurationError` before device push | fix arm/YAML; same policy as INV-APV-02 |
| Missing `preset` key in a configured arm | `configure()` | raise `ConfigurationError` | fix arm definition |
| Unknown/invalid `ape.*` key or combination reaches the jar | jar (stage 2 fail-fast) | run aborts before step 1; abort is visible in the trace | fix arm; nothing silent survives |
| Regeneration diff non-empty | migration test | task group blocked | fix re-expression, or owner-approved declared divergence (renamed arm) |
| Stage-2 jar not deployed (no `RUN_START`, no preset support) | migration smoke | change blocked at phase 1 | deploy stage-2 jar first (hard dependency) |

## Risks / Trade-offs

- [Silent grid drift during re-expression] → the per-group regeneration diff; final full diff + owner sign-off task; arm names frozen throughout.
- [Preset tables parsed from ape source drift from the deployed jar] → the migration script records the ape repo commit + the deployed jar sha256 it read against; the check is one-time and the record is archived.
- [`ape_pure`/`bfs` cannot run against the stage-2 jar] → resolved by retirement (D2): both variants are deleted by this change, never migrated; attempting them against the stage-2 jar aborts loudly, which is the intended behavior until the deletion lands.
- [Losing the guards weakens day-to-day defect detection] → accepted deliberately (owner D1): fail-fast in the jar now catches the errors the guards caught (unknown key, missing dependency) at run time with an abort, which is stronger than a constant self-check; provenance makes any survivor auditable post-hoc.
- [Open rv-android changes (`gh88-cal-llm-control`, `gh90-e3-decisive-run-setup`) touching the same arms] → phase-1 inventory task records their status; this change rebases on whatever is merged, never edits arms mid-flight of another change.
- [Deleting stage 2's fixtures removes a safety net mid-migration] → the net is replaced before it is removed, not after: group 10 runs at the end, when the regeneration diff (D7) has already proved every surviving arm's effective configuration unchanged. The fixtures and the diff test the same property; the diff tests it against the jar's real resolution instead of against a captured copy of the old Python output.

## Testing Strategy

| Layer | Scope |
|---|---|
| Unit (kept, restated) | properties writer (preset line, override pass-through, bool serialization, mop-path line), configure validation incl. missing-preset, structural arm assertions (29 names frozen, `sata_mop is sata_mop_widget`, gh90 single-factor override dicts) |
| Unit (retired) | the D6 list — mapping-completeness/explicitness/count/table-pin guards |
| Migration (one-time) | regeneration diff per arm, run per task group and deleted after sign-off |
| Untouched | `tests/test_clock_logcat_join.py`, `tests/test_coverage_dump.py`, all execution-flow/compaction/enrichment/provenance tests |

## Open Questions

1. **`ape_pure`'s preset name — RESOLVED (2026-08-02, by the rearch-02 design).** No structural-purity preset is created: `ape.apePureMode` becomes a retired key that aborts resolution, and unknown `--ape` values abort (no silent fallback). The `ape_pure` and `bfs` variants are therefore **retired and deleted by this change** (see D2), consistent with owner decision D3 (campaign control = minimal `aperv` preset). The owner ratifies the retirement when approving these artifacts.
2. **Post-stage-4 key set.** Stage 4 (telemetry) may delete `ape.stepTelemetryEnabled` when telemetry becomes universal. The implementation-time mapping re-sweep (D5) absorbs this regardless of ordering.
