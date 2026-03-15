## Why

The APE-RV comparison experiment (169 APKs x 3 tools x 2 reps, 1014 tasks) confirmed that MOP guidance works (`sata_mop` > `sata`, Wilcoxon p<0.05) but the effect is small (~1pp method coverage). Root cause: MOP scoring operates only within states via `adjustActionsByGUITree()`, boosting action priorities but never influencing which state to navigate to. Additionally, 47% of APKs show deterministic exploration (identical traces between `sata` and `sata_mop`) because the +500 boost magnitude overwhelms the base SATA priority (~32), always selecting the same MOP action regardless of exploration value. GitHub Issue: #5

## What Changes

- Rebalance MOP scoring weights from 500/300/100 to configurable defaults (100/60/20) so MOP boost influences but does not dominate the SATA heuristic
- Add three new `ape.properties` configuration keys for MOP weights: `ape.mopWeightDirect`, `ape.mopWeightTransitive`, `ape.mopWeightActivity`
- Add navigation-level MOP guidance: when `SataAgent` selects a target state to navigate to (ABA, trivial activity), prefer states with higher MOP widget density
- Add telemetry logging: per-state count of MOP-boosted actions for post-experiment analysis

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mop-guidance`: Scoring weights become configurable via `ape.properties` with rebalanced defaults. MOP scoring adds per-state telemetry logging.
- `exploration`: ABA graph navigation and trivial-activity navigation incorporate MOP density as a tiebreaker when selecting target states.

## Impact

- `ape/utils/MopScorer.java`: read weights from Config instead of hardcoded constants
- `ape/utils/Config.java`: three new config keys for MOP weights
- `ape/agent/StatefulAgent.java`: telemetry logging in `adjustActionsByGUITree()` + protected getter for `_mopData`
- `ape/agent/SataAgent.java`: navigation-level MOP density tiebreaker in ABA and trivial-activity selection
- **Cross-repo**: `rv-android/modules/aperv-tool/.../tool.py` needs to pass new weight properties (out of scope for this change; tracked separately)
- No breaking changes: when `ape.mopDataPath` is null, behavior is unchanged
