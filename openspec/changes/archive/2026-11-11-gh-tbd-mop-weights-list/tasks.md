<!-- Subagent dispatch hints:
     - Work only in /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/gh-tbd-mop-weights-list.
       Never edit, build or commit in WS/ape (master stays at bc4bc1b8). Never run `mvn install`.
     - Every mvn run: `nice -n 19 ionice -c3 mvn ...`, after checking `docker ps` / `uptime`.
     - Group 1 (plan key) must complete first. Groups 2 (scorer) and 3 (loader) depend on it, and are independent of
       each other. Group 4 (coin tests) needs 3. Group 5 integrates; group 6 delivers.
     - Critical path: 1 -> 2 -> 5 -> 6 (3 -> 4 in parallel with 2).
     - ~20 files; one implementer in sequence is enough. If dispatched, brief each subagent with:
       "Do not run find/grep/rg recursively outside the exact directories listed here; never from /pedro or /. If a
       path does not exist, report it instead of searching." -->

## 0. Baseline

- [x] 0.1 Check `docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'` and `uptime`; ask Pedro if a heavy load runs
- [x] 0.2 In the worktree at `bc4bc1b8`, run `nice -n 19 ionice -c3 mvn clean test` and record the baseline
      (expected 1495 run / 0 failures / 0 errors / 28 skipped) — not run, to save resources (Pedro, 2026-11-03): the
      baseline is the documented `bc4bc1b8` count 1495 / 0 / 0 / 28 (`CLAUDE.md`), and 5.6's 1536 equals it plus the
      41 new `@Test` methods. Measured as a by-product of 6.3: `bc4bc1b8` rebuilt with `mvn -o clean package` ran
      1495 / 0 / 0 / 28

## 1. Plan key `ape.mopWeights` (run-spec deltas)

- [x] 1.1 Write the failing tests first: a new `ValueTypeIntListTest` (parse, the five failure classes, the boundaries
      of 10 elements and `0`, trimming, `canonical`, `sameValue`); `RunSpecResolveTest` (default `[500,400,300]`,
      inert under `flag`, neutral `0` without MOP, `missing_dependency` for `500` without MOP, increasing accepted,
      digest equality `500, 400` ≡ `500,400` and stated default ≡ unset); `RunSpecAbortTest` (malformed →
      `invalid_type key=ape.mopWeights`; `ape.mopWeightD2=400` → `retired_key`, detail names `ape.mopWeights`);
      `RunSpecEchoTest` (`"ape.mopWeights":[500,400]` in `params`, absent at the default)
- [x] 1.2 Add `ValueType.INT_LIST` (`runtime/ValueType.java`): parse to an unmodifiable `List<Integer>`, the
      `invalid_type` detail "expected 1 to 10 comma-separated non-negative integers", canonical join without spaces
      (design D1)
- [x] 1.3 `runtime/KeyOwnership.java`: replace the three `ape.mopWeightD*` declarations with
      `feature(Feature.MOP, "ape.mopWeights", ValueType.INT_LIST, "500,400,300")`; add the three `RETIRED` entries
      (design D9)
- [x] 1.4 `runtime/Feature.java`: replace the three neutrals with `MOP.own("ape.mopWeights", "0")` (D8);
      `runtime/MopScoring.java`: the `DISTANCE` key set becomes `ape.mopWeights`, `ape.mopRetireAfter`,
      `ape.mopLauncherDmax`
- [x] 1.5 `runtime/RunSpec.java`: delete `MopParams.weightD1..3`; add `weights()` returning a fresh `int[]` (D2); check
      that `RunSpecEcho` writes the parsed list as a JSON array through `runtime/Json`
- [x] 1.6 Update the existing tests that name the retired keys: `RunSpecResolveTest`, `RunSpecEchoTest`,
      `RunSpecAbortTest:268`, `FeatureDerivationTest:300-302`, `KeyOwnershipTotalityTest:192-194`
- [x] 1.7 Run `/sdd-test-run` on `runtime` (`mvn test -Dtest='RunSpec*Test,ValueType*Test,Feature*Test,KeyOwnership*Test'`)

## 2. Level rule and tie split (mop-guidance "MopScorer — Priority Boost")

- [x] 2.1 Write the failing tests first in `MopScorerDistanceTest`: Distance zero, Beyond the cut, Above the old cut
      (`500,400,300,300,300,300`, d = 5 → 300, d = 7 → 0), Flat ladder does not split (`300,300,300`, 4 ties → 300),
      Single-level ladder splits fully (`500`, 4 ties → 125, d = 2 → 0), increasing ladder `300,500` split
      (`W_next` = 0); `MopContextTest`: `weights()`, `levelWeight`, `cut()`
- [x] 2.2 Write `MopLadderE7EquivalenceTest` (new, `src/test/java/com/android/commands/monkey/ape/utils/`) with
      verbatim frozen copies of the `bc4bc1b8` `distanceWeight`, `splitTies` and `nextLevelWeight` over the literals
      500/400/300; the inputs of design D10; it asserts 0 mismatches and prints one `[E7-EQUIV]` line with the input
      counts
- [x] 2.3 `utils/MopContext.java`: replace `weightD1..3` with `weights` (copied), `levelWeight(int)`, `cut()`; update
      `fromSpec` and the constructor (D2)
- [x] 2.4 `utils/MopScorer.java`: `distanceWeight` = `levelWeight(max(d, 1))` (D3); `nextLevelWeight` loops over
      `1..mop.cut()` (D4); update the Javadoc (current-state wording, P4), including the class header at `:23`
- [x] 2.5 Fix every other test or main reference to `weightD1..3` that the compiler reports (`MopContextTest`, test
      helpers that build a `MopContext`)
- [x] 2.6 Run `/sdd-test-run` on `utils` (`mvn test -Dtest='Mop*Test'`)

## 3. Cut at artifact load (mop-guidance "Distance-Pair Cut at Load" and the three requirements after it, "Load Status Line")

- [x] 3.1 Write the failing tests first in `MopDataCutTest` (new, `src/test/java/com/android/commands/monkey/ape/utils/`)
      on synthetic format-2 artifacts written to the JUnit temp folder: activity pair at d = 5 kept; deeper artifact
      (pairs up to 10) at c = 3 ≡ the same artifact cut at 3 (equal `MopData` for every widget, event, handler,
      activity and click aggregate; records differ only in `cutPairs`); c = 6 over a depth-3 artifact cuts nothing;
      c = 2 keeps `[[1,1],[2,2]]` and counts 1; malformed and cut counted apart (`droppedPairs` 2, `cutPairs` 1);
      click aggregate over cut lists; a stamped handler emptied by the cut still wins (`STAMP`, weight 0); no cut
      under `flag`; the cryptoapp fixture at the default ladder loads unchanged with `cutPairs` 0
- [x] 3.2 `utils/StaticArtifactLoader.java`: `PairReader(int targets, int cut)` with a `cut` counter and
      `read(JSONArray, boolean applyCut)` (malformed check first, then the cut) (D5); widget `dist` and handler `dist`
      read with the cut, `activityDist` without it
- [x] 3.3 Same file: the public `load` computes `cut` from the plan (`MOP ∧ distance` → `weights().length`, else
      `NO_CUT`); the package-visible seam takes `int cut` (D6); update the class Javadoc at `:82` and the `PairReader`
      doc
- [x] 3.4 `telemetry/EventSink.java` (`mopData` gains `int cutPairs`), `NdjsonSink.java` (write `cutPairs` beside
      `droppedPairs`), `NoopSink` and every test sink that implements `mopData` (D7); update the sink tests that pin
      the `MOP_DATA` member list
- [x] 3.5 Run `/sdd-test-run` on the loader and the sink (`mvn test -Dtest='MopData*Test,*Sink*Test'`)

## 4. Coin second-key coverage (mop-guidance "MOP Coin Decider")

- [x] 4.1 Make `MopCoinStage.targetCount` package-private without changing its body (D11)
- [x] 4.2 Add to `MopCoinStageTest`: Window boundary (counts `d + 1`, not `d + 2`; ranking tie broken by order);
      Retired target excluded (retired target in the window not counted; every target retired → 0, not eligible);
      Window never passes the cut (a widget list `[[0,3],[1,3],[2,4],[3,4]]` loaded at c = 3, action at d = 3 →
      second key 2, `cutPairs` 2)
- [x] 4.3 Run `/sdd-test-run` (`mvn test -Dtest='MopCoinStage*Test'`)

## 5. Integration and verification

- [x] 5.1 Regenerate the cell fixtures once with `-Dape.oracle.regenerate=true -Dtest=CellRunStartFixtureTest`, then run
      the test in compare mode; `git diff bc4bc1b8 -- src/test/resources/cells/` must touch only `digest` in `mop`,
      `as_mop` and `as_mop_llm`, and nothing in `base`, `as`, `as_llm`; save the diff as `evidence/cells_diff.txt`
- [x] 5.2 Confirm the goldens (`src/test/resources/goldens/`) are unchanged (`git diff --stat bc4bc1b8 --
      src/test/resources/goldens` empty) and their tests pass
- [x] 5.3 Search `src/` once more for the strings `mopWeightD`, `weightD1`, `weightD2` and `weightD3`
      (`git grep -n 'mopWeightD\|weightD[123]' -- src`); the only remaining hits are the retired-key entries and their
      tests
- [x] 5.4 Update `CLAUDE.md` (worktree copy): the `distance` scoring bullet (`ape.mopWeights` replaces
      `mopWeightD1..3`; c, cut at load, `cutPairs`), the "Besides the activation keys" list, the inert-keys sentence,
      the telemetry `MOP_DATA` mention, the test count after 5.6
- [x] 5.5 Run `/sdd-qa-lint-fix` on the changed files (no linter configured: skip with a note if none applies) — skipped: `pom.xml` configures no linter (no checkstyle, spotless, PMD or Error Prone), 2026-11-03
- [x] 5.6 Full suite: `nice -n 19 ionice -c3 mvn clean test`; record tests run / failures / errors / skipped —
      2026-11-03: `mvn -o clean test` BUILD SUCCESS, Tests run: 1536, Failures: 0, Errors: 0, Skipped: 28
      (1495 at `bc4bc1b8` + 41 new `@Test` methods); `[E7-EQUIV] distanceWeight=12 splitTies=440 nextLevelWeight=5 mismatches=0`.
      Re-run after 5.10 (one more `@Test`), the delivered state: `mvn -o clean test` BUILD SUCCESS, Tests run: 1537,
      Failures: 0, Errors: 0, Skipped: 28; `[E7-EQUIV]` unchanged
- [x] 5.7 `openspec validate gh-tbd-mop-weights-list --strict`; record the output — 2026-11-03, openspec 1.14.3, exit 0:
      "Change 'gh-tbd-mop-weights-list' is valid" (`evidence/validate_strict.txt`)
- [x] 5.8 Invoke `/sdd-code-reviewer` via the Skill tool on the branch diff against `bc4bc1b8`; report findings to Pedro
      before fixing any — 2026-11-03: 0 critical, 2 warnings, 7 notes; Pedro's decisions in 5.10
- [x] 5.9 Run `/sdd-verify` (or `/opsx:verify`) on the change — 2026-11-03 `/opsx:verify`: every requirement of the five
      deltas maps to code and a passing test; no design decision contradicted; only open items are 0.2, 5.8 and group 6

- [x] 5.10 Review follow-ups (Pedro, 2026-11-03): WR-01 documented (mop-guidance scenario "Increasing ladder raises
      the share", design D4) and pinned in `MopScorerDistanceTest`; WR-02 documented (run-spec scenario "Inert under
      flag, neutral without MOP", design D8, `CLAUDE.md`) and pinned in `RunSpecResolveTest`; notes 1–3 fixed
      (`MopCoinStageTest` through `ModelAction.resolveAt`, `MopRetirement` Javadoc reflowed, `MopDataCutTest` imports
      and `URL.toURI()`); notes 4–7 left as they are. Run the touched test classes — 2026-11-03: `MopScorerDistanceTest` 20/0,
      `RunSpecResolveTest` 37/0, `MopCoinStageTest` 16/0, `MopDataCutTest` 11/0 (84 run, 0 failures); one new `@Test`
      (`anIncreasingLadderRaisesTheShareAboveTheWeight`)

## 6. Delivery (only after Pedro's OK to commit)

- [x] 6.1 Commit the change on branch `gh-tbd-mop-weights-list` (by path, no push, no co-author line) — 2026-11-03: `e6d1be2b`
- [x] 6.2 Build the delivery jar from that commit: `nice -n 19 ionice -c3 mvn clean package` (never `install`); record
      `sha256sum target/ape-rv.jar`; do not rebuild afterwards — 2026-11-03, `nice -n 19 ionice -c3 mvn -o clean package` at
      `e6d1be2b` (1537 / 0 / 0 / 28): `f4e9687d7f18d51091acf916146a041d017cf8fcbf50e58005090a4e2609f001`
- [x] 6.3 Reproducibility check (report only): `git -C WS/ape worktree add --detach <scratch path> bc4bc1b8`, build it
      the same way, compare its sha256 with E7's `28f218be…` (`campaign.env:32`) and, when they differ, compare the
      jar entries other than `BuildInfo` (`unzip -l` + per-entry CRC); remove that worktree — 2026-11-03: `bc4bc1b8`
      rebuilt = `2383abe4…` ≠ E7 `28f218be…`, as expected; the jar's one entry is `classes.dex`, and `dexdump` of two
      `bc4bc1b8` builds differs only in `BuildInfo.JAR_BUILT`; the E7 jar, extracted from its image without running
      a container (Pedro's OK), differs from the rebuilt `bc4bc1b8` dex only in `BuildInfo.JAR_BUILT`
      (`evidence/repro_check.txt`); worktree removed
- [x] 6.4 Write `openspec/changes/gh-tbd-mop-weights-list/delivery-v6.md`, with: (1) commit hash and branch; (2) jar
      path and sha256, plus the exact build command; (3) the full `mvn test` counts; (4) the `openspec validate --strict`
      output; (5) one line per scenario of the seven acceptance requirements (test `file:line`, method, result);
      (6) the `[E7-EQUIV]` line (inputs, 0 mismatches); (7) `evidence/cells_diff.txt` (digest only); and the
      reproducibility result of 6.3 — 2026-11-03, `delivery-v6.md` written
- [x] 6.5 Commit the delivery note (Pedro's OK); then send session `calibracao` one message with the commit, the jar
      path and the sha256 — 2026-11-03: note committed `f5e47dd3`; message sent to `calibracao`
- [x] 6.6 At archive (only when Pedro decides): hand-sync what deltas cannot carry. In `run-spec` `## Invariants`,
      INV-RUN-09's key set (`ape.mopWeights` replaces `ape.mopWeightD1..D3`; "six Part B keys" → four). In
      `mop-guidance` `## Purpose`, `every target at d ≤ 3` → `every target up to the generator's depth; the jar cuts at
      c`. In `## Invariants`, INV-MOP-24's `d ≤ 3` — 2026-11-11, archived as `2026-11-11-gh-tbd-mop-weights-list` (+10, ~10 across five main specs); INV-RUN-09, the `mop-guidance` Purpose (the cut stated only under `distance`, per D6) and INV-MOP-24 edited by hand
