# Tasks — back-menu-pick-cap

## 1. Config and static seams

- [x] 1.1 Add `Config.backMenuPickCap` (`ape.backMenuPickCap`, default 3; <= 0 = unlimited) next to `mopTargetPickCap`, with a current-state comment (P4)
- [x] 1.2 Add pure static `SataAgent.backMenuPickKey(ActionType type, String activity)`: `activity + "|" + type.name()` for MODEL_BACK/MODEL_MENU, null otherwise (incl. null activity); add the instance-owned `backMenuPicks` map next to `mopTargetPicks`
- [x] 1.3 Reuse the existing generic `eligibleForMopPick`/`recordMopPick` seams unchanged (no new counting logic)

## 2. Discretionary-channel wiring (SataAgent)

- [x] 2.1 Gate the BACK unvisited short-circuit and the MENU unvisited short-circuit on key eligibility; on return, record the pick and emit `[APE-RV] BACK/MENU capped: activity=<a> type=<t> picks=<n>` when the record reaches the cap
- [x] 2.2 Build the stable capped `ActionFilter` wrapper (excludes MODEL_BACK/MODEL_MENU whose key is capped) and pass it to both `greedyPickLeastVisited` and `randomlyPickAction` in `selectNewActionEpsilonGreedyRandomly`; keep the wrapped filter identical across the roulette's counting and picking passes (INV-SEL-NAV-04)
- [x] 2.3 Record least-visited/roulette picks of BACK/MENU against the same keys (+ cap log on reach)
- [x] 2.4 Confirm by inspection that `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` are untouched (INV-SEL-NAV-03); update the epsilon-greedy method comment to the current contract (P4)
- [x] 2.5 EARLY_STAGE backward (`findGreedyActionBackward`, `SataAgent.java:1278-1283`): gate the direct unvisited-BACK pick on BACK-key eligibility — when capped, skip it and fall through to the backtrack path; on pick, record + cap log on reach (INV-SEL-NAV-05)
- [x] 2.6 EARLY_STAGE forward (`findGreedyActionForward`, `SataAgent.java:1218-1236`): after the existing INV-FORM-06 submit exclusion narrows `candidates`, drop capped target-less BACK/MENU before `randomPickWithPriority`; when the roulette winner is BACK/MENU, record its key + cap log on reach (INV-SEL-NAV-05). Leave the `pickCappedMopTarget` probe untouched

## 3. Menu-boost gate (StatefulAgent)

- [x] 3.1 Add `protected boolean menuPickEligible(String activity)` to `StatefulAgent` (returns true); consult it in the menu-boost pass: boost applied only when eligible, no `setPriority`/`setMenuBoost` otherwise
- [x] 3.2 Override `menuPickEligible` in `SataAgent` with the cap check over `backMenuPicks`

## 4. Unit tests (JVM, static seams — SataAgent not instantiable)

- [x] 4.1 `SataAgentBackMenuCapTest`: key contract (BACK/MENU vs other types, null activity), eligibility/record round-trip at the cap boundary, once-only reach-the-cap signal (INV-SEL-NAV-01/-04)
- [x] 4.2 Cap-disabled behavior: cap 0 and negative → always eligible, no counter updates (INV-SEL-NAV-02)
- [x] 4.3 Wrapped-filter test with stub actions: excludes exactly the capped types, include decisions stable across two passes
- [x] 4.4 Boost-gate test: `menuPickEligible == false` → MENU priority and `menuBoost` unchanged (extend the existing StatefulAgent boost-pass test)
- [x] 4.5 EARLY_STAGE backward seam: at cap, the direct unvisited-BACK pick is skipped (BACK-key ineligible); below cap it is taken and recorded (INV-SEL-NAV-05)
- [x] 4.6 EARLY_STAGE forward seam: capped target-less BACK/MENU are filtered from the `randomPickWithPriority` candidate list; a winning BACK/MENU below cap is recorded (INV-SEL-NAV-05); the exclusion composes with the INV-FORM-06 submit exclusion (submit still dropped, BACK/MENU never the submit candidate)
- [x] 4.7 Run `mvn test -Dtest=SataAgentBackMenuCapTest`

## 5. Verification

- [x] 5.1 Full suite: `mvn test` (0 failures/errors)
- [x] 5.2 `openspec validate back-menu-pick-cap --strict`
- [x] 5.3 Device-smoke characterization via cmpft3 (`rvsec/rv-android/docs/20260708_analise_efeito_changes_cmpft3.md` §5, §10). Verdict **PASS**. Evidence: `[APE-RV] BACK/MENU capped` fired **1272×** (0 in cmpft2), diffuse across 205 APKs; BACK+MENU share of `[APE-STEP]` went **25.29% → 10.70% (−14.60pp, −58%)**, below the <15% target; churn migrated ~1:1 to CLICK (55.87%→67.54%), total steps +6.9%; no concentrated coverage regression (cov_act 66.47→66.69, cov_mop 34.49→34.76; symmetric win/loss on the 205 capped apps). The per-arm "cov_mop does not regress vs the uncapped MOP arm" clause holds against the temporal cmpft2 baseline (which IS the uncapped MOP jar): no regression. Caveat: **attribution to the cap alone is partial/confounded** (1272 caps = 0.634% of steps — the −14.6pp is a bundle effect: the embedded menu-boost gate + the other 5 changes); isolating the cap requires a single-flag arm. Controlled per-arm isolation deferred to the gh74 fair-test (`PAMunb/rvsec#74`).
