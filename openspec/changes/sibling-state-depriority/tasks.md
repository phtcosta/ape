# Tasks — sibling-state-depriority

## 1. Config

- [ ] 1.1 Add `Config.siblingStatePenalty` (`ape.siblingStatePenalty`, default 24; 0 = disabled) next to `coverageBoostWeight`, with a current-state comment (P4) noting the threshold is the existing `maxStatesPerActivity`

## 2. Scoring pass (StatefulAgent)

- [ ] 2.1 Implement the sibling-depriority pass in `adjustActionsByGUITree`, immediately after the coverage-boost pass: state-count guard (`> maxStatesPerActivity`), exemptions (target-less, `mopBoost > 0`, `wtgBoost > 0`, activity-novel), fixed subtraction floored at 1
  - Ordering is a hard requirement: the pass MUST sit after the WTG pass (`StatefulAgent.java:1443-1467`) so `mopBoost`/`wtgBoost` are populated before the exemptions read them — placing it earlier would silently penalize frontier/WTG widgets
- [ ] 2.2 Emit `[APE-RV] Sibling depriority: state=<activity>#<stateKey>, penalized=<n>/<total>, siblings=<s>` once per pass when `n > 0`
- [ ] 2.3 Update the `adjustActionsByGUITree` pass-ordering comment to the current contract (P4)

## 3. Unit tests

- [ ] 3.1 Threshold boundary: siblings == `maxStatesPerActivity` → no penalty; +1 → penalty applied (INV-COV-10)
- [ ] 3.2 Exemption matrix: activity-novel, `mopBoost > 0`, `wtgBoost > 0`, target-less actions each left untouched (INV-COV-11)
- [ ] 3.3 Floor: priority 8 − penalty 24 → 1; disabled: `siblingStatePenalty = 0` → scoring identical to pass-absent (INV-COV-12)
- [ ] 3.4 Log emitted exactly once per pass, only when `n > 0`
- [ ] 3.5 Run the StatefulAgent scoring-pass test class

## 4. Verification

- [ ] 4.1 Full suite: `mvn test` (0 failures/errors)
- [ ] 4.2 `openspec validate sibling-state-depriority --strict`
- [ ] 4.3 Device smoke (rebuilt jar): heavy-refinement app (e.g. `com.faltenreich.diaguard_68.apk`) — `Sibling depriority` lines present, redundant-pick share drops vs the cmpft2 trace of the same APK; a simple app emits no lines
  - The cmpft2 baseline reports `liveStates` (the tracker proxy) while this pass triggers on the Model state count — but the comparison here is on redundant-*pick* share, which is metric-independent, so the population mismatch does not affect the smoke gate
