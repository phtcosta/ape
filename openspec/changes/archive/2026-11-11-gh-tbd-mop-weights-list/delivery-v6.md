# Delivery note: `gh-tbd-mop-weights-list` (calibration v6, task P.1)

This note is the evidence that the calibration's task P.3 reads. The acceptance contract is the seven jar
requirements of `rvsec-calibracao/openspec/changes/2026-11-aperv-calibracao-v6/specs/aperv-dependency/spec.md`.
All paths below are relative to the `ape` repository root unless they are absolute.

## 1. Commit

| | |
|---|---|
| Repository | `ape` (APE-RV) |
| Branch | `gh-tbd-mop-weights-list`, branched from `master` `bc4bc1b8` (the E7 jar) |
| Change commit | `e6d1be2b97d92526fedf044c47c30a562c1022ee` |
| `master` merge | none. `master` stays at `bc4bc1b8`; a merge needs rv-android's `tool.py` aligned first (Pedro decides) |

The jar is built from `e6d1be2b`. The commit that adds this note changes only files under
`openspec/changes/gh-tbd-mop-weights-list/` (this note, `tasks.md`, `evidence/repro_check.txt`), none of which reach the
jar.

## 2. Jar

| | |
|---|---|
| File | `/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/worktrees-aperv/gh-tbd-mop-weights-list/target/ape-rv.jar` |
| sha256 | `f4e9687d7f18d51091acf916146a041d017cf8fcbf50e58005090a4e2609f001` |
| Size | 322563 bytes |
| Build command | `nice -n 19 ionice -c3 mvn -o clean package`, run in the worktree at `e6d1be2b` on 2026-11-03 |
| Build stamp | `BuildInfo.GIT_SHA = "e6d1be2"`, `BuildInfo.JAR_BUILT = "2026-11-03T13:55:17Z"` |

The jar is not rebuilt after this hash was recorded. A rebuild of the same commit gives a different sha256, because
`JAR_BUILT` changes (section 8).

## 3. Full test suite

`mvn -o clean test` at the committed state, and again inside the `package` build of section 2:

```
[WARNING] Tests run: 1537, Failures: 0, Errors: 0, Skipped: 28
[INFO] BUILD SUCCESS
```

| Tests run | Failures | Errors | Skipped |
|---|---|---|---|
| 1537 | 0 | 0 | 28 |

`bc4bc1b8` gives 1495 / 0 / 0 / 28, measured when it was rebuilt for section 8. The difference, 42, is the number of new
`@Test` methods. The 28 skipped tests are the same as before: 18 `@Ignore` needing an Android runtime, 6 `Assume` in
`SglangLiveTest`, 4 `Assume` in `ApeFuzzerPinchGateTest`.

## 4. OpenSpec validation

```
$ openspec validate gh-tbd-mop-weights-list --strict      (openspec 1.14.3)
Change 'gh-tbd-mop-weights-list' is valid
```

Exit code 0, also recorded in `evidence/validate_strict.txt`.

## 5. Acceptance scenarios

One line per scenario of the seven jar requirements. Test paths are under
`src/test/java/com/android/commands/monkey/ape/`. Every test passed in the run of section 3.

| Requirement | Scenario | Test (`file:line`, method) | Result |
|---|---|---|---|
| Explicit ladder key | Default | `runtime/RunSpecResolveTest.java:90` `theFourMopScoringKeysResolveToTheirDefaults` (effective `500,400,300`); `runtime/RunSpecResolveTest.java:101` `mopParamsReadsTheFourKeysThroughTypedAccessors` (`weights()` = `[500,400,300]`, length = cut 3) | pass (class 37/0) |
| Explicit ladder key | Malformed value | `runtime/RunSpecAbortTest.java:277` `aMalformedLadderAbortsAsATypeErrorOnTheKey` (empty, 11 elements, negative, non-integer, empty element → `reason=invalid_type key=ape.mopWeights` at resolution, before step 1); `runtime/ValueTypeIntListTest.java` (11 tests of the type) | pass (33/0; 11/0) |
| Per-level keys retired | Retired key passed | `runtime/RunSpecAbortTest.java:298` `thePerLevelDistanceKeysAreRetiredInFavourOfTheLadder` (`ape.mopWeightD1..D3` → `retired_key`, detail names `ape.mopWeights`) | pass (33/0) |
| Level of a distance | Distance zero | `utils/MopScorerDistanceTest.java:339` `distanceZeroIsLevelOneAndTheFourthLevelIsBeyondTheDefaultCut` (d = 0 → 500) | pass (20/0) |
| Level of a distance | Beyond the cut | `utils/MopScorerDistanceTest.java:339` `distanceZeroIsLevelOneAndTheFourthLevelIsBeyondTheDefaultCut` (d = 4 → 0) | pass (20/0) |
| Level of a distance | Above the old cut | `utils/MopScorerDistanceTest.java:347` `aLongerLadderWeighsAboveTheOldCut` (`500,400,300,300,300,300`: d = 5 → 300, d = 7 → 0) | pass (20/0) |
| Cut applied at artifact load | Activity pair beyond the cut | `utils/MopDataCutTest.java:146` `anActivityPairBeyondTheCutIsKept` (c = 3, `activityDist` `[[0,5]]` kept, launcher nearest = 5 under dmax 6) | pass (11/0) |
| Cut applied at artifact load | Deeper artifact under the default cut | `utils/MopDataCutTest.java:204` `aDeeperArtifactUnderTheCutEqualsThePreCutArtifact` (pairs to d = 10 at c = 3 ≡ the same artifact cut at 3: every widget, event, flag, click aggregate, handler, activity; records differ only in `cutPairs`) | pass (11/0) |
| Tie-split floor | Flat ladder does not split | `utils/MopScorerDistanceTest.java:360` `aFlatLadderDoesNotSplit` (`300,300,300`, 4 ties → 300 each) | pass (20/0) |
| Tie-split floor | Single-level ladder splits fully | `utils/MopScorerDistanceTest.java:372` `aSingleLevelLadderSplitsFully` (`500`, 4 ties → 125 each) | pass (20/0) |
| Default equivalence with the E7 jar | Differential test | `utils/MopLadderE7EquivalenceTest.java:133` `theDefaultLadderEqualsTheE7Functions` (section 6) | pass (1/0) |
| Default equivalence with the E7 jar | Cell fixtures | `oracle/CellRunStartFixtureTest.java:97` `eachCellsRunStartMatchesItsFixture`, plus `evidence/cells_diff.txt` (section 7) | pass (1/0) |
| Coin second-key coverage | Window boundary | `agent/pipeline/MopCoinStageTest.java:320` `theSecondKeyCountsTheTargetsWithinDPlusOne` (scored at d = 1: counts the targets at d and d + 1, not d + 2; tie on both keys → `getActions()` order) | pass (16/0) |
| Coin second-key coverage | Retired target excluded | `agent/pipeline/MopCoinStageTest.java:339` `aRetiredTargetInTheWindowIsNotCounted` (a retired target in the window is not counted; when the only target is retired the second key is 0 and the action is not eligible) | pass (16/0) |
| Coin second-key coverage | Window never passes the cut | `agent/pipeline/MopCoinStageTest.java:360` `theWindowNeverPassesTheCut` (`[[0,3],[1,3],[2,4],[3,4]]` loaded at c = 3: `cutPairs` 2, scored at d = 3, second key 2) | pass (16/0) |

## 6. Differential evidence

`MopLadderE7EquivalenceTest` holds verbatim copies of the `bc4bc1b8` bodies of `distanceWeight`, `splitTies` and
`nextLevelWeight`, written against the literals 500/400/300, and compares them with the new functions at the default
ladder. Its output in the run of section 3:

```
[E7-EQUIV] distanceWeight=12 splitTies=440 nextLevelWeight=5 mismatches=0
```

| Function | Inputs | Count |
|---|---|---|
| `distanceWeight` | every d in 0..10, and `Integer.MAX_VALUE` (no pair) | 12 |
| `splitTies` | for every best distance d in 0..10 and tie size n in 1..20: the n actions alone, and together with one action at each worse distance up to 10 and one with no pair | 440 |
| `nextLevelWeight` | the weights 0, 300, 400, 500, 1 | 5 |

0 mismatches over 457 inputs.

## 7. Cell fixtures against `bc4bc1b8`

`git diff --stat bc4bc1b8 e6d1be2b -- src/test/resources/cells` touches three files, one line each. Field by field
(the full diff is in `evidence/cells_diff.txt`):

| Cell | Fields that differ | `digest` |
|---|---|---|
| `mop` | `digest` only | `82f5e2e91908d19d` → `5e0068112e42a1bc` |
| `as_mop` | `digest` only | `87ddf9dab57af99c` → `54ec26904defdb2e` |
| `as_mop_llm` | `digest` only | `7e56112c7d248520` → `b3f5dafc39331ff8` |
| `base`, `as`, `as_llm` | none | unchanged |

The digest changes because the key set of the MOP feature changed (`ape.mopWeights` replaces `ape.mopWeightD1..D3`).
The goldens (`src/test/resources/goldens/`) are unchanged.

## 8. Reproducibility and notes

**Reproducibility (report only).** Detail in `evidence/repro_check.txt`.
- `bc4bc1b8` rebuilt the same way gives sha256 `2383abe4…`. That differs from E7's
  `28f218be7727f6334dd4d18d440c27503535643e2f4ae590c1d8030b779ff6e5`, as expected.
- `pom.xml:24-26` stamps `BuildInfo.JAR_BUILT` with the Maven build time, so two builds of one commit never have the
  same bytes.
- The E7 jar was copied out of image `phtcosta/rvandroid@sha256:50cf6088…` with `docker create` + `docker cp` +
  `docker rm`. The container never ran. Its sha256 is `28f218be…`, and its only entry is `classes.dex`, 722756 bytes,
  the same length as the rebuilt dex.
- `dexdump -d` of the E7 dex against the rebuilt `bc4bc1b8` dex differs only in `BuildInfo.JAR_BUILT`
  (`2026-10-23T20:51:08Z` against `2026-11-03T13:55:42Z`). Two rebuilds of `bc4bc1b8` also differ only there.
- So the source behind E7 rebuilds to E7's code, and the delivered jar's only expected difference from a rebuild of
  `e6d1be2b` is its timestamp. The jar is pinned by its sha256, not by its build.

**Trace readers.** `RUN_START.params` carries `ape.mopWeights`, when it is not the default, as a JSON array of
integers (`"ape.mopWeights":[500,400]`). A reader that rebuilds `ape.properties` from that record must join the
array with commas. The jar-side test helper does that. Analysis code in rv-android or in the calibration may need the
same.

**`MOP_DATA.cutPairs`.** This is a new field in the load record. It counts the well-formed widget and handler pairs
dropped because d > c. It is 0 under `flag`, without MOP, and at the default ladder with artifacts derived at depth 3.
`droppedPairs` still counts only malformed pairs, and no pair is counted in both.

**Behaviour the contract leaves open, as decided by Pedro on 2026-11-03.**
- Under `ape.mopScoring=flag`, and in a cell without MOP, nothing is cut.
- An increasing ladder is accepted. When the best weight's next level weighs more, a tie share exceeds the undivided
  weight. For example, under `300,500` two actions tied at 300 get 500 each, as `bc4bc1b8` did with D1 = 300,
  D2 = 500. A test pins this.
- Without MOP, only the exact one-element `0` is neutral. `ape.mopWeights=0,0,0` aborts with `missing_dependency`.

**rv-android.** rv-android's own `tool.py` still maps `mop_weight_d1..d3` to `ape.mopWeightD1..D3`. This jar refuses
those keys with `retired_key`. The calibration uses its own harness copy, which writes `ape.mopWeights`. The jar is
bind-mounted over the E7 image's jar and never copied into rv-android.
