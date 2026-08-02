## Why

The meaning of experimental modes lives in Python: `tool.py` hardcodes 29 arms over a 52-key property mapping (`APERV_PROPERTY_MAPPING`), with pytest guards that validate Python constants against Python constants (INV-APV-14) and a kill-switch list duplicated and divergent from the jar's (18 Python keys vs 27 Java keys) — the split-brain verified as V20. Dead keys survive (`mop_weight_activity` maps to a jar key with 0 hits in `src/main`, finding 3.3-7), and a jar↔Python drift is silent (V21). With presets resident in the jar since `rearch-02-runspec`, the Python side can finally become thin: an arm is a preset name plus explicit overrides.

This change is **stage 5 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 5.1, Sec. 10). It is the first stage that touches rv-android.

## What Changes

- **BREAKING (cross-repo)**: the 29 arm definitions in `tool.py` are re-expressed as `preset + explicit overrides`; the hardcoded per-arm property dicts shrink to deltas.
- Dead keys removed from `APERV_PROPERTY_MAPPING` (starting with `mop_weight_activity`); the mapping shrinks to the keys the jar actually validates (unknown keys now abort the run fail-fast, per stage 2).
- The Python-side kill-switch duplication is deleted — purity is structural in the jar (plan without feature ⇒ feature absent), and the effective plan is echoed in `RUN_START`.
- INV-APV-14 (constant-vs-constant pytest guards) is retired. **Owner decision D1 stands: no automatic echo-vs-intent validation is added** — drift auditing remains post-hoc analysis of the `RUN_START` line.
- Migration check (report Sec. 11): deterministic regeneration of the 29 current arms' effective property sets, diffed against the pre-change values, to prove the calibrated grid is preserved.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `aperv-tool`: arm-definition requirements restated as preset + overrides; property-mapping and kill-switch requirements reduced; INV-APV-14 replaced by the regeneration-diff migration check (one-time) and level-0 echo provenance.

## Impact

- **Python/rv-android**: `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` (arm dicts, `APERV_PROPERTY_MAPPING`, `ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`), its pytest guards (`test_aperv_tool.py`).
- **Java**: none (presets already in the jar from stage 2).
- **Depends on**: `rearch-02-runspec` (presets + fail-fast + echo must exist and be deployed).
- **Comparability**: arms' effective configurations must be diff-identical before/after regeneration; any intentional divergence is a declared new arm, never a silent edit.
- Grounding: report Sec. 5.1, 6.6, Sec. 12 D1, verified V20/V21, finding 3.3-7.
