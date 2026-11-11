<!-- Subagent dispatch hints (maximum parallelism, by file ownership):
     - Work only in /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/gh-tbd-mop-defaults-f1.
       Never edit, build or commit in WS/ape. Never run `mvn install`. Commit only when Pedro asks; no Co-Authored-By line.
     - Brief every subagent: "Do not run find/grep/rg recursively outside the exact directories listed here; never from
       /pedro, ~ or /. If a path does not exist, report it instead of searching."
     - mvn is single-writer: target/ is shared by the worktree, so subagents NEVER run mvn. They edit their own files
       and report; the coordinator runs every mvn command, one at a time, as
       `nice -n 19 ionice -c3 mvn -o ...` after checking `docker ps` and `uptime`.
     - File ownership decides parallelism: two tasks in the same wave never touch the same file.

     Wave 1 (parallel, 3 dispatches, no mvn): Group 1 (runtime tests) | Group 2 (scoring tests) | Group 4 (CLAUDE.md).
       Then the coordinator runs gate 2.4 (RED).
     Wave 2 (serial, 1 dispatch or the coordinator): Group 3 (src/main + GREEN + the census 3.8). Owns all of src/main.
     Wave 3 (parallel, up to 4 dispatches, no mvn): Group 5 tracks 5.1 runtime | 5.2 utils+scoring | 5.3 oracle |
       5.4 agent/pipeline/llm/telemetry/model. Each track takes only the census rows of its files. Then the coordinator
       runs 5.6 (focused runs, one track after another).
     Wave 4 (serial, coordinator): Group 6 (fixtures, greps, full suite, validate, verify, review).
     Wave 5 (serial, Pedro's OK at each step): Group 7 (commit, handoffs, archive).
     Critical path: 0 -> 1 + 2 (write) -> 2.4 RED -> 3 -> 5 -> 6 -> 7. Group 4 runs off the critical path.
     The change touches ~25 files: 2 to 4 main files, ~20 test files, 3 fixtures, CLAUDE.md. -->

## 0. Baseline

- [x] 0.1 Check `docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'` and `uptime`; ask Pedro before any mvn run if
      a heavy load runs — 2026-11-11 12:17: no container, load 0.43 (64 cores, 119 GB free); Pedro OKed mvn for the plan
- [x] 0.3 Artifact fixes found at apply (Pedro, 2026-11-11): run-spec scenario "the six MOP scoring keys resolve to
      their defaults" → `[194, 146, 97]`, 10; mop-guidance `W_next` at the default (146/97/0, E7 400/300/0), "(default
      10)", "at k = 1 (at the default k = 4 …)", E7 ladder stated in three examples; new delta `exploration`
      "Configuration Loading" drops "(default 1)" (design D4); `validate --strict` re-run
- [x] 0.2 Baseline at `a0fb5c16`, the worktree's base: `mvn -o clean test` ran 1537 / 0 failures / 0 errors / 28
      skipped on 2026-11-11 (log `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/d6798427-a19c-4449-84e2-fbb0150717bd/scratchpad/mvn_test_a0fb5c16.log`)

## 1. RED tests, runtime (Wave 1; files: `src/test/java/com/android/commands/monkey/ape/runtime/RunSpecResolveTest.java`, `RunSpecEchoTest.java`)

- [x] 1.1 `RunSpecResolveTest`, run-spec "Calibrated MOP Defaults": a `mop` cell with only `ape.staticDataPath` and
      `ape.variant` resolves to `weights()` `[194, 146, 97]`, retirement count 10, `coinLedgerMaxInteractions()` 4,
      `activityTriggerStagnationStep()` 85 — `aMopCellWithoutStatedKeysRunsF1` (RED at 2.4)
- [x] 1.2 `RunSpecResolveTest`, "Calibrated Defaults Are Not Preset Keys" and "Neutral Values Follow the Calibrated
      Defaults": `mop` + `ape.activityTriggerEnabled=false` resolves without `MOP_LAUNCHER`; adding
      `ape.activityTriggerStagnationStep=85` lists it inert; `=50` aborts `missing_dependency`; no `ape.mop` +
      `ape.mopRetireAfter=10` inert, `=3` aborts; `mop` + `ape.mopCoinPercentage=0` + `ape.mopCoinLedgerMaxInteractions=1`
      aborts — `noPresetStatesTheCalibratedKeys`, `aVariantWithoutTheLauncherResolves`, `theCadenceWithoutTheLauncherIsInertAt85AndAbortsAt50`, `theRetirementCountWithoutMopIsInertAt10AndAbortsAt3`, `theCoinBudgetWithoutTheCoinIsInertAt4AndAbortsAt1`
- [x] 1.3 `RunSpecResolveTest`, "E7 Plan by Explicit Keys" and "E7 Recipe Omits Keys of Absent Features": the four E7
      values stated give `[500, 400, 300]`, 3, 1, 50; `mop_nolaunch` + E7 ladder, N 3 and k 2 without the cadence
      resolves with the three values effective — `theFourE7ValuesStatedGiveTheE7Plan`, `theE7RecipeWithoutTheLauncherOmitsTheCadence` (GREEN before and after)
- [x] 1.4 `RunSpecResolveTest`, component-triggering "invalid values clamped at load": `ape.activityTriggerStagnationStep=0`
      clamps to `"85"` (new assertion beside `:345-348`; the old `"50"` assertion is changed in 5.1, not here) — `aCadenceAtOrBelowZeroClampsToTheCalibratedDefault` (RED at 2.4)
- [x] 1.5 `RunSpecEchoTest`: `RUN_START.params` of a default `mop` cell carries none of the four keys; with the four
      E7 values stated it carries all four, `ape.mopWeights` as `[500,400,300]` — `aDefaultMopCellCarriesNoneOfTheCalibratedKeys`, `theF1ValuesStatedAreDefaultsTheRecordOmits`, `theE7ValuesStatedAreCarriedWithTheLadderAsAJsonArray`

## 2. RED tests, scoring (Wave 1; files: `src/test/java/com/android/commands/monkey/ape/utils/MopScorerDistanceTest.java`, `MopLadderE7EquivalenceTest.java`)

- [x] 2.1 `MopScorerDistanceTest`, mop-guidance "Distance weights at the default ladder": a context from
      `TestRunSpecs.mopSpec("ape.mopScoring", "distance").mop()` scores `d = 0..4` as 194, 194, 146, 97, 0; two ties at
      level 1 get 146 and keep `mopWeight` 194; four ties at level 3 get 24 — `theDefaultLadderWeighsEachDistanceLevel`, `theDefaultLadderSplitsTiesWithTheNextLevelFloor`
- [x] 2.2 `MopLadderE7EquivalenceTest:90-91`: build the context from
      `mopSpec("ape.mopScoring", "distance", "ape.mopWeights", "500,400,300")` (design D5, D6: it relied on the
      default); update its javadoc to say the E7 ladder is stated — helper `e7Ladder()`, test renamed `theStatedE7LadderEqualsTheE7Functions`
- [x] 2.3 Report the new `@Test` count of groups 1 and 2 — 14 new `@Test`: RunSpecResolveTest +9, RunSpecEchoTest +3, MopScorerDistanceTest +2 (+1 at review 6.7: 15 in all)
- [x] 2.4 Gate (coordinator, after groups 1 and 2): `mvn -o test -Dtest='RunSpecResolveTest,RunSpecEchoTest,MopScorerDistanceTest,MopLadderE7EquivalenceTest'`.
      Expected RED: the new F1, neutral, clamp and `params` assertions fail; the E7-stated tests pass. Record the
      failing test names in `evidence/red.md` — 2026-11-11: 91 run, 6 failures + 3 errors, exactly the 9 new F1/neutral/clamp/params tests; E7-stated tests pass (`evidence/red.md`)

## 3. Production change, GREEN and the census (Wave 2; owns `src/main/java/com/android/commands/monkey/ape/runtime/` and the javadoc files listed)

- [x] 3.1 `runtime/KeyOwnership.java:210,211,219,224`: defaults `"194,146,97"`, `"10"`, `"85"`, `"4"`; rewrite the
      comment above `:205` to name calibration v6's F1, the 900 s interval [−0.55, +1.64], "equivalent to the E7
      values, not an improvement" and the four searched keys (run-spec "Calibrated Defaults Described as Equivalent") — done; comment names F1, D22, [−0.55, +1.64], "equivalent … not an improvement", why not preset keys
- [x] 3.2 `runtime/Feature.java:195,199,201`: neutrals `"10"`, `"4"`, `"85"`; keep `MOP.own("ape.mopWeights", "0")`; update
      the comment at `:198` (k = 1 is the first-interaction rule; the default is 4) — done; comment: "neutral at its default; k = 1 would be the first-interaction rule"
- [x] 3.3 `runtime/RunSpec.java:270-272`: the cadence clamp returns `KeyOwnership.defaultOf(key)` (design D3); leave the
      coin floor and the `MOP_COIN`-absent fallback `1` (`:1103-1106`) as they are, and update the latter's javadoc only
      if it quotes the default — done; `KeyOwnership.defaultOf(key)`; clamp javadoc says so; `:1103-1106` javadoc quotes no default, unchanged
- [x] 3.4 `runtime/KeyOwnership.java:323-333`: drop "(default 1)" and "(default 500,400,300)" from the two retired-key
      reasons (design D4) — done
- [x] 3.5 Javadoc that quotes an old default, current-state wording (P4): `utils/MopScorer.java:177`,
      `agent/MopInteractionLedger.java:41` — `MopScorer` javadoc → 194/146/97 (F1); `MopInteractionLedger:41` quotes no default ("1 is the first-interaction rule"), left unchanged
- [x] 3.6 Gate: rerun the 2.4 command; every test GREEN. Record in `evidence/red.md` — 2026-11-11: 91 run, 5 failures; the 14 new tests GREEN, E7-EQUIV mismatches=0; the 5 failures are the old-default assertions of `RunSpecResolveTest` that 5.1 moves (`evidence/red.md`)
- [x] 3.7 Run `/sdd-test-run` on `runtime` (`mvn -o test -Dtest='RunSpec*Test,Feature*Test,KeyOwnership*Test,Presets*Test'`) — 2026-11-11: 148 run, 8 failures, all census rows of class S (track 5.1); `RunSpecAbortTest` 33/33, `PresetsTest` 16/16
- [x] 3.8 Census: `mvn -o clean test`, full suite. Write `evidence/test-census.md`: one row per failing test with its
      file, the key it reads, the class of design D6 (subject is the default / relied on it incidentally / other)
      and the owning track of group 5 (5.1 runtime, 5.2 utils+scoring, 5.3 oracle, 5.4 agent+pipeline+llm+telemetry+model).
      A row of class "other" stops the wave until Pedro has seen it — 2026-11-11: 1551 run, 23 failures + 1 error, 0 class O by mechanism; D7 lists 2 of the 5 seeded oracle scripts (`ParityOracleAsMopLlmTest` fails, `SinkNeutralityTest`/`CleanControlTest` pass on a changed path): stopped for Pedro (`evidence/test-census.md`)

## 4. Documentation (Wave 1, off the critical path; file: `CLAUDE.md`)

- [x] 4.1 `CLAUDE.md` Central Configuration: the `ape.mopWeights` bullet (default `194,146,97`), the Scoring-modes bullet
      ("weighs an action ... through the ladder `ape.mopWeights` (default ...)"), Retirement (`ape.mopRetireAfter`
      10), the k bullet (default 4), the cadence (85, clamp to the default). Name calibration v6's F1 and the interval;
      say "equivalent to the E7 values, not an improvement"; name the four searched keys; add one sentence that the
      MOP cells run these defaults, and that rv-android's `tool.py` states k = 2 until it is changed — done (Wave 1):
      k/cadence/ladder/N bullets updated, new bullet "Calibrated MOP defaults" (F1, [−0.55, +1.64], D22, k = 2 in tool.py)
- [x] 4.2 `CLAUDE.md`: the E7 recipe (the four keys; omit the cadence without the launcher and k without the coin) next
      to "Behavior groups and cells", where it says the blocks reproduce E6 — done (Wave 1)
- [x] 4.3 Leave the test count line for 7.2 (it changes with the new tests) — left at 1537

## 5. Test adaptation from the census (Wave 3; parallel tracks, each limited to its own files)

- [x] 5.1 Track runtime (`runtime/*Test.java`): the census rows of class "subject is the default" move to F1 values:
      `RunSpecResolveTest:94-95,104,275,290` (stated default ≡ unset now uses `194,146,97`), `:345-348` (`"50"` →
      the 1.4 assertion), `:352-356` stays (floor 1); `KeyOwnershipTotalityTest:192-194,236`; `FeatureDerivationTest:301`;
      `RunSpecAbortTest` if the reason text is asserted; any other row of this track — done: 8 rows moved to F1 (`evidence/test-census.md`); `RunSpecAbortTest` asserts no "(default …)", unchanged
- [x] 5.2 Track utils+scoring (`utils/*Test.java`, `agent/scoring/*Test.java`): census rows; `MopContextTest:30,72`
      (`fromSpec` default ladder → `[194, 146, 97]` if its subject is the default, else state `500,400,300`);
      `MopRetirementTest`; any other row — done: `MopContextTest:30` → F1 (subject default); `:72` explicit, unaffected; `MopRetirementTest` and the rest unaffected; `TestMopContexts.flag` javadoc
- [x] 5.3 Track oracle (`oracle/*.java`, design D7): `LadderConfigGuard:87` → 85; `ParityOracleMopTest` and
      `ParityOracleAsMopTest` `SEEDED_CADENCE` 48 → 83 and their javadoc (`:18`, the seed comment);
      `PreemptionGoldenTest.DUE_CADENCE` 49 → 84 and its javadoc (`:32`, `:88`); `ScenarioScript:204` and
      `OracleScaffold:240` javadoc. Do not touch `src/test/resources/goldens/`. Amendment (census 3.8, Pedro
      2026-11-11, design D7 option B): `ParityOracleAsMopLlmTest:40` `SEEDED_CADENCE` 48 → 83 and its javadoc (`:25`);
      `SinkNeutralityTest:69` and `CleanControlTest:72` derive the seed as
      `Integer.parseInt(KeyOwnership.defaultOf("ape.activityTriggerStagnationStep")) - 2`, comment updated — done: guard 85; seeds 83/84 (literal) in the four golden tests; derived seed in `SinkNeutralityTest`, `CleanControlTest`; javadoc
- [x] 5.4 Track agent+pipeline+llm+telemetry+model (`agent/**`, `llm/*`, `telemetry/*`, `model/*`): the census rows
      (class "relied incidentally": state the value the test was written for, keep its expectations). Then review the
      tests that use `TestRunSpecs.mopSpec()` or a MOP preset and exercise the coin, retirement or the launcher
      without stating k, N or the cadence (`PostSelectionRestartTest`, `StatefulAgentMopRetirementTest`,
      `StatefulAgentMopTelemetryTest`, `SataExtensionPointTest`, `MopCoinStageTest`, `StageProvenanceLabelTest`,
      `ScorerCounterfactualTest`, `ModelActionTest`, `StepRecordTest`, `NdjsonSinkTest`): list in
      `evidence/test-census.md` each one that still passes but now runs a different path, and state the value it
      was written for (design D6) — done: rows 10–13 state N = 3; `StatefulAgentMopTelemetryTest.distance()` also states the E7 ladder (hand-set 500/400 boosts); review: no other test on a changed path (`evidence/test-census.md`)
- [x] 5.5 Each track reports its files and edits; no track runs mvn — done: four tracks reported; no track ran mvn
- [x] 5.6 Gate (coordinator): one focused run per track, in sequence, with the `-Dtest` list of its census rows; then
      run `/sdd-test-run` on `oracle` (`mvn -o test -Dtest='ParityOracle*Test,PreemptionGoldenTest,LadderConfig*,CleanControlTest,SinkNeutralityTest'`)
      and confirm `git diff --exit-code -- src/test/resources/goldens` is empty — 2026-11-11: runtime 155/0/0, utils 92/0/0, agent 129/0/0, oracle 30/0/0; goldens byte-identical

## 6. Verification (Wave 4, coordinator)

- [x] 6.1 Re-capture the cell fixtures: `mvn -o test -Dtest=CellRunStartFixtureTest -Dape.oracle.regenerate=true` (that
      test only: the property also re-captures goldens). `git diff src/test/resources/cells` must show only the
      `digest` of `mop`, `as_mop` and `as_mop_llm` (design D8); anything else is investigated, not committed — 2026-11-11: only the `digest` of `mop`, `as_mop`, `as_mop_llm` moved; goldens untouched (`evidence/suite.md`)
- [x] 6.2 Grep for leftover copies of an old default in `src/main`, `src/test` javadoc, `CLAUDE.md` and
      `openspec/specs` (`500,400,300` and `{500, 400, 300}` described as a default, `default 1`, `(default 50)`,
      `default \`3\``, "first interaction ... default"); list each hit in `evidence/greps.md` as fixed, kept (it states
      E7 explicitly) or moved to the archive hand-sync (design D10) — 2026-11-11: no leftover in src/main, test javadoc or CLAUDE.md; hand-sync list = mop-guidance Purpose `:11` and INV-MOP-38 `:1173` (`evidence/greps.md`)
- [x] 6.3 Full suite `mvn -o clean test`: expected 1537 + the new `@Test` count of 2.3, 0 failures, 0 errors, 28 skipped.
      Record in `evidence/suite.md` — 2026-11-11: 1551 run, 0 failures, 0 errors, 28 skipped (1537 + 14); goldens unchanged (`evidence/suite.md`)
- [x] 6.4 `openspec validate gh-tbd-mop-defaults-f1 --strict` and `openspec validate --specs --strict` (the latter must
      show no warning that `a0fb5c16` did not already show) — change valid; `--specs --strict` output byte-identical to master `a0fb5c16` (pre-existing long-text warnings only)
- [x] 6.5 Run `/sdd-qa-lint-fix` on the changed module — 2026-11-11: Checkstyle 8.39 (google_checks, no project config) has no auto-fix; 0 errors; changed-line warnings are style-default mismatches already present at `a0fb5c16` (4-space indent, names); the two new >100-char comment lines in src/main re-wrapped
- [x] 6.6 Run `/sdd-verify` on the changed module — 2026-11-11: overall pass (1551/0/0/28; lint 0 errors; `evidence/suite.md`)
- [x] 6.7 Invoke `/sdd-code-reviewer` via the Skill tool on the branch diff; report findings to Pedro and fix only what
      he approves — 2026-11-11: COMMENT, 0 critical / 1 warning / 6 notes; Pedro approved WR-01, NT-06 (spec + test), NT-02, NT-03, NT-05; NT-01, NT-04 left. Applied; suite 1552/0/0/28 (`evidence/review.md`)

## 7. Delivery (Wave 5; each step needs Pedro's OK)

- [x] 7.1 Commit on branch `gh-tbd-mop-defaults-f1` (no Co-Authored-By line) — 2026-11-11: `a867e471` (42 files: the 29 tracked files + the change directory; the `docs/*_prompt_*.md` left out; no attribution line)
- [x] 7.2 `CLAUDE.md` Notes: the test count from 6.3 — 2026-11-11: `CLAUDE.md:342` 1537 → 1552 (skips 28 unchanged), the count of the full suite after the review fixes (`evidence/suite.md`, `evidence/review.md`); no source file changed since that run, so not re-measured
- [x] 7.3 Handoff for rv-android (untracked prompt in `docs/`, never committed): drop or change
      `MOP_COIN_LEDGER_MAX_INTERACTIONS = 2` (`tool.py:117`, Pedro decides which); do not write `ape.mopWeights` unless
      the DSL sets it; the E7 recipe, with the cadence omitted on `mop_nolaunch`; no `mvn install -Drvsec_home` until
      `tool.py` is aligned. Coordinate with the `gator` session's change `gh-tbd-gator-full-minified-horizon`, which
      also edits `tool.py` — 2026-11-11: delivered as a message to the `gator` session (Pedro's choice, instead of a prompt
      file): the four defaults and neutrals, k = 2 at `tool.py:117` (Pedro decides drop or 4), the stale "jar's default
      is 1" comments (`:113-116`, `:231-233`), `mop_weights` only on a DSL override (already in gator's uncommitted
      diff, no default ladder), the E7 recipe, no `mvn install -Drvsec_home`, push ordering
- [ ] 7.4 Note for `rvsec-calibracao` task X.1: the adoption decision and this change's name and commit
- [x] 7.5 Archive (`openspec archive gh-tbd-mop-defaults-f1 -y`), then the hand-sync of design D10: the mop-guidance
      Purpose "(default 1)" → 4, INV-MOP-38 "(default 1: no entry; ...)", and the hits 6.2 moved here; validate the
      main specs strictly — 2026-11-11: `openspec archive -y` → `2026-11-11-gh-tbd-mop-defaults-f1` (+6, ~10, −1 over
      run-spec, mop-guidance, component-triggering, exploration); hand-sync: mop-guidance Purpose "(default 4)",
      INV-MOP-38 "(default 4; at k = 1, no entry; design D-B17)"; the 6.2 pattern re-run on the main specs finds no
      other Purpose or invariant hit. `validate --specs --strict` first showed one warning master lacks: the MODIFIED
      run-spec "Distance Ladder Key" grew to 528 characters (the change validation checks length only on ADDED);
      with Pedro's OK the clause "calibration v6's finalist F1" was dropped in the main spec and the archived delta
      (498 characters); output then byte-identical to master `a0fb5c16` (`Totals: 1 passed, 23 failed (24 items)`)
- [ ] 7.6 Merge into master (`--no-ff`) and push, when Pedro asks; remove the worktree afterwards. Before the push,
      confirm with Pedro how it is ordered against the rv-android image: the image build compiles the ape of GitHub's
      default branch (`origin/master`), so a push before rv-android drops k = 2 ships F1 with k = 2 (`gator` session,
      2026-11-11)
