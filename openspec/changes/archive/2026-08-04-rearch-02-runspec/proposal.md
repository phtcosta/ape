## Why

The behavioral authority of a run is today a silent global: `Config` has 113 `public static` fields loaded from two device paths with empty `catch (NumberFormatException)` blocks, no unknown-key detection, and `getBoolean` degrading typos to `false` with no error path at all (verified V7, finding 3.3-2). The agent factory falls through silently to `SataAgent` on unknown `--ape` values (V9), and the agent type can be swapped by a stray `/sdcard/ape.properties` (Sec. 3.2, "orthogonality" finding — inverted). What each experimental mode *means* lives in Python dicts with no contract to the jar (V20 split-brain), and the kill-switch is three string-literal registries the compiler cannot see (V8). The persistence protocol is broken by construction — it writes a `Model` and reads a `Graph`, swallowing the `ClassCastException` (V14, finding 3.3-6).

This change is **stage 2 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.2): the run plan becomes an immutable, validated, echoed value object; everything invalid aborts before step 1.

## What Changes

- New `RunSpec`: immutable plan resolved once at bootstrap from `ape.properties` + CLI (preset name, seed, run id, exploration params, feature set, MOP/LLM/telemetry params, canonical digest). Static `Config` demoted to a loading detail; `RunSpec` is the sole behavioral authority.
- Presets (`aperv`, `mop`, `llm`, `llm_mop`) reside **in the jar** (`Presets.resolve`); overrides come explicitly on top. **One ordered Python edit in this stage**: `ape_pure_mode` leaves `tool.py` (mapping, `_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS`, `ARM_DEFINING_KEYS`) and that edit lands *before* the jar — 23 of the 29 arms push `ape.apePureMode=false` today, and this change retires the key. Every other property `tool.py` pushes is unchanged.
- `Feature` enum with metadata as data (key, type, default, declared dependencies). **BREAKING**: the three string registries (`rvForcedOffValues` 27 keys, `rvUnsetKeys`, `rvExemptReasons`) and the `apePureMode` Properties-overwrite mechanism are deleted; purity becomes structural (a feature absent from the plan does not exist in the run).
- **BREAKING** fail-fast total (R5): unknown key, invalid type, missing feature dependency, or invalid combination → abort with message before step 1. Unknown `--ape` value → error instead of silent `SataAgent` fallback. The 5 non-final "for tests" Config fields die; tests construct `RunSpec`s.
- Level-0 configuration echo (owner decision D1): `RUN_START` as the first trace line, carrying the effective plan, the digest of the properties actually read, and the jar build version. Write-only provenance; **no automatic validation anywhere and no Python reader** — the echo itself costs the Python side nothing.
- **BREAKING** removal of the `/sdcard` input readers (owner decision D6): `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, `/sdcard/ape.strings` (including the `StringCache` static initializer that throws `RuntimeException` on read failure, V22). Input-string generation rewritten on the run's seeded RNG, killing the residual `ThreadLocalRandom` (V23).
- **BREAKING** removal of the legacy persistence protocol: `saveGraph`/`readGraph`/`--ape-model`/`sataModel.obj` (V14 — broken by construction; R1/R3: no read-back, retry lives in the Python supervisor). The teardown coverage-dump ordering (INV-COV-10) is preserved.
- `RunContext` introduced as the owner of per-run mutable state, initially holding the seeded RNG, `RunSpec`, and run identity; remaining mutable state migrates into it in stage 3.

## Capabilities

### New Capabilities

- `run-spec`: plan resolution, presets in the jar, `Feature` dependency model, fail-fast validation rules, digest, level-0 `RUN_START` echo, `RunContext` ownership rules.

### Modified Capabilities

- `exploration`: run lifecycle starts with `RUN_START`; agent-type selection is validated plan data (no silent fallback, no `/sdcard` override); model persistence requirements removed.
- `model`: `Model`/`Graph` serialization requirements removed (`sataModel.obj`, `--ape-model`); XPath action injection via `/sdcard/ape.xpath.actions` removed.
- `heuristic-input`: input-string generation sourced from the seeded run RNG; `/sdcard/ape.strings` requirement removed.
- `ui-tree`: `/sdcard/ape.xpath` overlay reader requirement removed.
- `scoring-pipeline`: the "apePureMode Kill-Switch and Parity" requirement removed (INV-ARCH-06 dissolved with its substitute recorded; INV-ARCH-01 removed with its subject per D3); the parity flags and pass roster re-grounded on the `Feature` model.
- `component-triggering`, `llm-infrastructure`, `llm-routing`, `mop-guidance`: their kill-switch-registry registration clauses (which reference the deleted INV-ARCH-06 registry) re-grounded on the `Feature` model; no behavioral change.

## Impact

- **Java**: `Config.java` (authority demoted), `ApeAgent.createAgent`, `Monkey`/`MonkeySourceApe` bootstrap, `StatefulAgent` (saveGraph path), `Graph.readGraph`, `StringCache`, `GUITreeBuilder:91`, `XPathActionController`. New: `RunSpec`, `Presets`, `Feature`, `RunContext`.
- **Python/rv-android**: one edit, in a counterpart change of the `rvsec` repo (that repo forbids hand-written OpenSpec artifacts, so it owns its own change; the roadmap coordinates the two). `ape_pure_mode` is deleted from `APERV_PROPERTY_MAPPING`, `_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS` and `ARM_DEFINING_KEYS`, with the INV-APV-13/14/15 guard tests following the count from 18 keys to 17. Nothing else moves: the echo stays level 0 (D1 — no Python reader; drift auditing is post-hoc analysis), and the `preset + overrides` contract is still stage-5 work.
- **Gate**: validated by the `rearch-01-parity-oracle` goldens — same seeds must produce identical action sequences per preset before/after.
- **Invariants**: INV-ARCH-06 (exempt keys inert) dissolves — a sub-parameter of an absent feature does not exist in the plan (report Sec. 8).
- Grounding: report Sec. 6.2, 6.4, 6.6, Sec. 12 D1/D6, verified V7/V8/V9/V14/V22/V23, findings 3.3-2/3.3-6.
