# Tasks: activity-trigger-dose

<!-- Small change (~2 source files + 3 test files). Sequential groups; no subagent dispatch needed.
     TDD per project convention: write failing tests first within each group. -->

## 1. Config flags + kill-switch registry

- [x] 1.1 RED: extend `ConfigTest` (or the existing Config-loading test class) — `activityTriggerStagnationStep` defaults to 50; `activityTriggerMaxPerRun` defaults to 0; step `<= 0` clamps to 50 with a logged clamp; cap `< 0` clamps to 0 with a logged clamp (delta scenarios "invalid values clamped at load")
- [x] 1.2 RED: extend `ApePureModeKillSwitchTest` expected exempt set with `activityTriggerStagnationStep` and `activityTriggerMaxPerRun` (INV-ARCH-06 completeness guard)
- [x] 1.3 GREEN: declare both flags in `Config.java` (loaded via `ape.activityTriggerStagnationStep` / `ape.activityTriggerMaxPerRun`, clamp helpers following the `clampLlmPercentageNoSubstrate` extraction pattern) and add both to `rvExemptReasons()` with reason "launcher sub-param; inert when activityTriggerEnabled is forced false"
- [x] 1.4 Run `/sdd-test-run ape.utils` (Config + kill-switch tests green) — 36 tests, 0 failures (ConfigTest 31, ApePureModeKillSwitchTest 5)

## 2. Firing predicate + launch budget in SataAgent

- [x] 2.1 RED: extend `ActivityFrontierTest` for the new 6-arg `shouldTriggerAtStagnation(enabled, hasMopData, counter, stagnationStep, launchesSoFar, maxPerRun)`: fires at counter==step (step=10 and step=50), not below/above; default step 50 reproduces every existing 4-arg case (INV-CT-11); cap=2 blocks the 3rd fire (INV-CT-12); cap=0 unlimited; disabled/no-MopData still gate
- [x] 2.2 GREEN: change `shouldTriggerAtStagnation` to the 6-arg form (drop the `graphStableRestartThreshold` param and the `/2`); update the sole production call site (`SataAgent.selectNewActionNonnull` trigger block) to pass `Config.activityTriggerStagnationStep`, `_activityTriggerLaunchCount`, `Config.activityTriggerMaxPerRun`
- [x] 2.3 GREEN: add `_activityTriggerLaunchCount` field; increment it exactly where the `ActivityTriggerAction` is returned (candidate != null branch) — empty candidate scan consumes no budget (delta scenario "empty candidate scan does not consume budget"); unit case `testEmptyCandidateScanDoesNotConsumeBudget` composes the gate + null-candidate scan to assert budget preservation at the pure seams (the field increment itself is device-verified per task 3.4)
- [x] 2.4 Confirm the LLM stagnation hook (`SataAgent.java:396`) still uses `graphStableRestartThreshold / 2` unchanged (decouples from the launcher point; comment updated to say so)
- [x] 2.5 Run `/sdd-test-run ape.agent` — ActivityFrontierTest 39 tests, 0 failures

## 3. Verification

- [x] 3.1 Full suite: `mvn test` green (baseline before change: 602 tests; expect +~10) — 615 run, 0 failures, 19 Android-runtime skips (+13: ConfigTest +8, ApePureModeKillSwitchTest +1, ActivityFrontierTest +4)
- [x] 3.2 `openspec validate activity-trigger-dose --strict` passes — "Change 'activity-trigger-dose' is valid"
- [x] 3.3 Run `/sdd-verify ape` (tests + lint checkpoint) — satisfied by the green `mvn test` (615) + clean `mvn test-compile`; the Maven build has no separate lint stage, `mvn test` is the authoritative tests+compile gate
- [ ] 3.4 Device smoke (cmpft5 Gate 0 rerun, experiment session): rebuild `mvn install -Drvsec_home=...`, record new jar md5, bind-mount, run 1 high-activity app with `ape.activityTriggerStagnationStep=10`, `ape.activityTriggerMaxPerRun=8`, launcher ON both arms — dose gate: median ≥3 `[APE-RV] Triggering activity:` lines per run AND zero denylisted launches; `mopActsAugmented>0` only in the treatment arm  <!-- DEFERRED: cmpft5 experiment session (rv-android repo); requires emulator + tool.py arm mapping. Same gate-pattern as the archived mop-activity-consumers 5.1. -->
- [x] 3.5 Document archive ordering: archive `mop-activity-consumers` BEFORE this change (same requirement modified; this delta assumes its amended text) — DONE: archived to `openspec/changes/archive/2026-07-11-mop-activity-consumers`, both deltas (component-triggering, mop-guidance) synced into main specs first (19/19 specs valid), then this change strict-validated against the synced text
