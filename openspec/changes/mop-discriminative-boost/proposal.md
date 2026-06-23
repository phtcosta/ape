## Why

The MOP boost does not steer exploration even where it fires. Measured on the cmpmop run (`docs/20260622_investigacao_mop.md` §1, Camada 2): ~73% of non-zero boosts are the uniform `+100` activity-level fallback, applied to EVERY target widget on a MOP-bearing activity — a constant shift that cannot re-rank candidates. Only the discriminative `+500`/`+300` boosts (one widget lifted above its neighbours) can steer, and they account for ~1% of decisions. Worse, the `+100` collides at equal magnitude with the coverage boost, which rewards the opposite (untested widgets), so the MOP term is rarely the distinguishing one. Removing the uniform fallback and giving the discriminative boost a deterministic selection path is the minimum needed to give MOP guidance a fair test on the substrate that does exist.

## What Changes

- **Remove the `+100` uniform activity-level fallback** from `MopScorer.score`. The MOP boost becomes discriminative-only: `+500` (direct), `+300` (transitive), `0` otherwise. A resolved-but-unflagged widget, or a null widget, on a MOP-bearing activity now scores `0` (previously `+100`). **BREAKING** to the scoring contract.
- **Delete `Config.mopWeightActivity`** and its declarations/scenarios (P3 — it becomes dead with the fallback removed). `activityHasMop(...)` the predicate stays (still used by WTG scoring and `stateMopDensity`); only its use as a `+100` scoring fallback is removed.
- **Remove `INV-MOP-07`** (it mandated the activity-level fallback that this change deletes).
- **Add a MOP-target greedy short-circuit** in SATA epsilon-greedy selection: when a valid, unvisited action carries a discriminative MOP boost (`mopBoost > 0`), select it before the roulette/least-visited step — mirroring the existing Back/Menu-unvisited short-circuit (`SataAgent.selectNewActionEpsilonGreedyRandomly`, `:414-435`). This gives the discriminative boost a deterministic path to the monitored widget without altering SATA's least-visited character elsewhere.
- No new configuration flags. `llmMaxCalls` is not introduced (it does not exist and must not).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mop-guidance`: "MopScorer — Priority Boost" loses the `+100` activity-level row and its two fallback scenarios (unflagged/null now score `0`); "Config.mopDataPath Flag" loses the `mopWeightActivity` weight and its scenarios; `INV-MOP-07` is removed.
- `action-selection`: gains a new requirement for the MOP-target greedy short-circuit in epsilon-greedy selection.

## Impact

- **Components:** `MopScorer.score` (`MopScorer.java:35-55`, delete the `activityHasMop → +mopWeightActivity` branch); `Config.mopWeightActivity` field and the `mop-guidance` Javadoc that references it; `SataAgent.selectNewActionEpsilonGreedyRandomly` (`:414-435`, add the short-circuit). The `StatefulAgent` MOP pass and its `[APE-RV] MOP boost` counts change (fewer boosted widgets — only discriminative ones).
- **Dependency:** depends on change `mop-parser-fidelity` (#0) — #0 restores the flagged widgets that this change needs in order to discriminate. Apply #0 first.
- **Consumers unaffected:** `MopScorer.scoreWtg`, `scoreOpenMenu`, `stateMopDensity` keep using `activityHasMop` as a predicate; the WTG/menu boosts are unchanged.
- **Validation:** `MopScorerTest` (resolved-but-unflagged and null → `0`; direct/transitive unchanged); `SataAgent` selection unit coverage for the short-circuit; end-to-end in the 19-APK fair-test re-run (`docs/20260622_investigacao_mop.md` §7.5), the experiment this change exists to enable.
