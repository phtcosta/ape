# Code review (task 6.7)

`/sdd-code-reviewer` on the uncommitted diff against `a0fb5c16` (29 files: 4 main, 21 tests, `CLAUDE.md`, 3 cell
fixtures), 2026-11-11. Verdict **COMMENT**: 0 critical, 1 warning, 6 notes. Pedro accepted the coordinator's
recommendations on 2026-11-11: fix WR-01, NT-06, NT-02, NT-03, NT-05; leave NT-01 and NT-04.

| # | Finding | Checked | Decision | What was done |
|---|---|---|---|---|
| WR-01 | `KeyOwnership` comment and `CLAUDE.md` said F1 is equivalent to "the E7 values (500,400,300, 3, **1**, 50)"; the calibration's comparator arm ran with **k = 2** (`rvsec-calibracao/results/v6/report.md:87`, row 15 `default | 500,400,300 | 3 | 2 | 50`), so the interval [−0.55, +1.64] is F1 − (k = 2) | yes | fix | both now say "the E7 `mop` arm (500,400,300, N 3, k 2 as Study 3's harness stated it, cadence 50)" |
| NT-06 | the inert rule compares the clamped value: without the coin a k `< 1` is floored to `1`, which is no longer neutral, so it now aborts (it was inert at `a0fb5c16`); a cadence `<= 0` without the launcher clamps to 85 and stays inert | yes (`RunSpec.java:175-197,348-375`) | document + pin | run-spec delta "Neutral Values Follow the Calibrated Defaults": one sentence + two scenario clauses; design D2 consequence; `CLAUDE.md` "Calibrated MOP defaults"; `RunSpecResolveTest.theCoinBudgetWithoutTheCoinIsInertAt4AndAbortsAt1` asserts `k=0` aborts; new `aCadenceAtOrBelowZeroWithoutTheLauncherIsInert` |
| NT-02 | nothing asserted the premise of the derived seed (a launch in the MOP replay); comment cited "(design D2)" | — | fix | `CleanControlTest.theSameReplayInAMopCellCarriesTheSignals` asserts a launch; comments cite gh-tbd-mop-defaults-f1 D7 |
| NT-03 | `aCadenceAtOrBelowZeroClampsToTheCalibratedDefault` tested only `0`; the clamp is also asserted in `theLoadTimeClampsArePreservedAsClamps` | — | partial | negative cadence `-7` → `"85"` added; the duplication requested by task 5.1 kept |
| NT-05 | `KeyOwnershipTotalityTest` javadoc read as history | — | fix | rewritten in current-state wording |
| NT-01 | no generic test that a sub-parameter's neutral equals its default | — | not now | needs an exception list (weights, activation keys, sentinels); a candidate for its own change |
| NT-04 | comments restate F1's values (`MopScorer`, `ScenarioScript`, `OracleScaffold`, …) | — | leave | design D9 asked for the values; grep 6.2 catches drift at the next recalibration |

## Finding while applying NT-02 (reported to Pedro)

The reviewer's suggested assertion, `dec.src == "launcher"`, failed on the first run (`Tests run: 70, Failures: 1`).
Root cause: the oracle driver writes a non-model step through `sink.decisionNonModel(selected.toString(),
selected.getType().name(), null)` (`oracle/OracleDriver.java:278-285`), so a launch in the replay carries
`dec.src = "EVENT_TRIGGER_ACTIVITY"`, not the production owner `launcher` (written by `StatefulAgent.resolveNewAction`,
which the replay bypasses; the driver's javadoc says its records "are not a production trace"). The assertion now
reads `EVENT_TRIGGER_ACTIVITY`, with a comment. Same consequence, pre-existing and **not changed**:
`CleanControlTest.assertStepClean`'s `"launcher".equals(dec.optString("src"))` check can never fire in a replay;
the clean cells mount no launcher, which is what keeps them launch-free.

## Verification after the fixes (2026-11-11 14:2x)

- `openspec validate gh-tbd-mop-defaults-f1 --strict` → valid.
- `mvn -o test -Dtest='RunSpecResolveTest,KeyOwnershipTotalityTest,CleanControlTest,SinkNeutralityTest'` → 70 run,
  0 failures, 0 errors.
- `mvn -o clean test` → **1552 run, 0 failures, 0 errors, 28 skipped** (1537 + 15 new `@Test`: the 14 of groups
  1–2 plus `aCadenceAtOrBelowZeroWithoutTheLauncherIsInert`); `[E7-EQUIV] … mismatches=0`; goldens unchanged;
  `git diff --check` clean.

Logs: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/{review_fix_focused,suite_after_review}.log`
