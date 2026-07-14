# Tasks: mop-target-revisit-cap

## 1. Config

- [x] 1.1 Add `Config.mopTargetPickCap` (`ape.mopTargetPickCap`, default 3; <= 0 = unlimited) next to the `mopWeight*` flags, with a current-state comment (P4)

## 2. Core

- [x] 2.1 Add the instance-owned `mopTargetPicks` map to `SataAgent`; implement the static seam `mopPickKey(action, activity)` (returns `target.toXPath() + "|" + actionType + "|" + activity`, null if target/XPath null) and `static eligibleForMopPick(picks, key, cap)` (cap <= 0 or key == null → true). No separate `mopTargetCapLogged` set — capped keys are filtered before the next pick.
- [x] 2.2 Wire the eligibility filter into the two instance call sites — `selectUnvisitedMopTarget` (epsilon path, :529-535) and `findGreedyActionForward` (EARLY_STAGE, :1121-1134, which calls the static `pickBestMopTarget` at :1126): each filters its candidate iterable through `eligibleForMopPick` before invoking the picker, then increments `mopTargetPicks` for the selected key. Do NOT modify the static `pickBestMopTarget` — it stays pure.
- [x] 2.3 Emit `[APE-RV] MOP target capped: activity=<a> widget=<xpath> picks=<n>` once per key, on the pick whose increment reaches the cap (picks == cap after increment)

## 3. Tests (extend `SataAgentMopShortCircuitTest`, driving the static seam `mopPickKey`/`eligibleForMopPick` — no JVM test can instantiate `SataAgent`)

- [x] 3.1 Key includes action type: 3 clicks on widget X cap the click key (`eligibleForMopPick` false at cap) while a long-click on the same widget X stays eligible (distinct key) (INV-SEL-MOP-04)
- [x] 3.2 Same widget/type key in different activities counts independently (distinct keys, each bounded on its own)
- [x] 3.3 `cap <= 0` (test 0 and a negative) → `eligibleForMopPick` always true, no filtering, no counting side effects (INV-SEL-MOP-05)
- [x] 3.4 Cap log emitted exactly once — at the cap-th pick (increment reaching the cap), not before, not on later attempts
- [x] 3.5 Null-target action → `mopPickKey` returns null → never counted, always eligible
- [x] 3.6 A capped action still carries its `mopBoost` (assert the cap does not zero the boost; the action remains roulette-visible)
- [x] 3.7 Run `mvn test -Dtest=SataAgentMopShortCircuitTest`

## 4. Verification

- [x] 4.1 Full suite: `mvn test` (0 failures/errors)
- [x] 4.2 `openspec validate mop-target-revisit-cap --strict`
- [x] 4.3 **[ACCEPTANCE-CRITICAL]** Device smoke (rebuilt jar): standalone run on `dnsfilter.android_1506007.apk` — trace must show no 100+-step single-activity streak and `MOP target capped` lines present. This is the empirical confirmation gate for the key-stability argument (the cap only bounds re-picks if the key stays stable across refinement), not an optional check.
