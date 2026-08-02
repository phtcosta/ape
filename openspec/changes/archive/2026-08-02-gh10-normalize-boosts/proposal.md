> **SUPERSEDED (2026-08-02, owner decision — never implemented, 0/9 tasks).**
> Closed without spec sync. The proposal is stale against HEAD `5dcf225`: it references
> `mopWeightActivity`, a key removed by `mop-discriminative-boost`, and its motivating
> experiment (49 APKs, March 2026) predates the #16 efficacy-telemetry work built to
> answer the same question with better data. The underlying concern (boost magnitudes
> vs. base priority space) remains valid and is re-expressible as a preset override in
> the `RunSpec` architecture selected by `docs/analise_fable-selecao.md` (rev. 3),
> informed by #16 data rather than these March defaults.

## Why

The gh9 exploration enhancements (MOP guidance, WTG navigation, UI coverage boost) add priority boosts of 100–500 to an action selection system whose base priorities range 1–60. This 13× inflation creates "gravity wells" where a single MOP+WTG action absorbs 88%+ of selection probability in `randomlyPickAction()`, locking exploration into narrow paths. A 49-APK experiment (2 reps × 300s) shows no statistically significant improvement over baseline APE (method coverage -0.42pp, p=0.078) with high variance (-13pp to +30pp per APK). Refs #10.

## What Changes

- **BREAKING**: Default MOP weight values reduced by 10× (mopWeightDirect 500→50, mopWeightTransitive 300→30, mopWeightActivity 100→10, mopWeightWtg 200→20, coverageBoostWeight 100→20) to fit the base priority space
- Add configurable `maxBoostCap` parameter (default 80) that limits total per-action boost from all guidance passes (MOP + WTG + coverage)
- Disable `dynamicEpsilon` by default (set to `false`), reverting to static epsilon to eliminate path-dependent feedback loop
- All scoring logic (MopScorer, WTG matching, UICoverageTracker) remains unchanged — only magnitudes and capping change

## Capabilities

### New Capabilities

_None — this change modifies existing capabilities._

### Modified Capabilities

- `mop-guidance`: MOP weight defaults reduced by 10×, total boost capped
- `wtg-navigation`: WTG weight default reduced from 200 to 20
- `ui-coverage`: Coverage boost weight reduced from 100 to 20
- `action-selection`: Add per-action boost cap after all guidance passes; disable dynamic epsilon by default

## Impact

- **StatefulAgent.adjustActionsByGUITree()**: Add capping pass after MOP, WTG, and coverage boost passes
- **Config.java**: New parameter `maxBoostCap`, changed defaults for 6 weight parameters and `dynamicEpsilon`
- **aperv-tool**: The `sata_mop` variant passes MOP weights via `ape.properties` — downstream tools using explicit weight overrides will need to update their values to match the new scale
- **Existing experiments**: Results from previous runs with old defaults are not comparable; baseline must be re-established
