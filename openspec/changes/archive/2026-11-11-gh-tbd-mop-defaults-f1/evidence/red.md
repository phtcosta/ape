# RED / GREEN gates (tasks 2.4, 3.6)

## Gate 2.4 — RED (2026-11-11 12:42, base `a0fb5c16`, src/main unchanged)

Command (worktree root):

```
nice -n 19 ionice -c3 mvn -o test -Dtest='RunSpecResolveTest,RunSpecEchoTest,MopScorerDistanceTest,MopLadderE7EquivalenceTest'
```

Result: `Tests run: 91, Failures: 6, Errors: 3, Skipped: 0` — BUILD FAILURE, as expected.

| Class | Run | Failures | Errors |
|---|---|---|---|
| `RunSpecResolveTest` | 46 | 2 | 3 |
| `RunSpecEchoTest` | 22 | 2 | 0 |
| `MopScorerDistanceTest` | 22 | 2 | 0 |
| `MopLadderE7EquivalenceTest` | 1 | 0 | 0 |

New `@Test` of groups 1 and 2 (task 2.3): **14** — `RunSpecResolveTest` +9, `RunSpecEchoTest` +3,
`MopScorerDistanceTest` +2; `MopLadderE7EquivalenceTest` keeps 1 (renamed
`theDefaultLadderEqualsTheE7Functions` → `theStatedE7LadderEqualsTheE7Functions`, ladder stated).

### Failing (9 = every new F1 / neutral / clamp / `params` assertion)

| Test | Kind | Message (abridged) |
|---|---|---|
| `RunSpecResolveTest.aMopCellWithoutStatedKeysRunsF1` | failure | weights `[0]` expected 194 but was 500 |
| `RunSpecResolveTest.aCadenceAtOrBelowZeroClampsToTheCalibratedDefault` | failure | expected `85` but was `50` |
| `RunSpecResolveTest.theCadenceWithoutTheLauncherIsInertAt85AndAbortsAt50` | error | `missing_dependency key=ape.activityTriggerStagnationStep` … neutral value 50 |
| `RunSpecResolveTest.theRetirementCountWithoutMopIsInertAt10AndAbortsAt3` | error | `missing_dependency key=ape.mopRetireAfter` … neutral value 3 |
| `RunSpecResolveTest.theCoinBudgetWithoutTheCoinIsInertAt4AndAbortsAt1` | error | `missing_dependency key=ape.mopCoinLedgerMaxInteractions` … neutral value 1 |
| `RunSpecEchoTest.theF1ValuesStatedAreDefaultsTheRecordOmits` | failure | `ape.mopWeights` stated at F1 is a jar default, so the record omits it |
| `RunSpecEchoTest.theE7ValuesStatedAreCarriedWithTheLadderAsAJsonArray` | failure | `ape.mopWeights` stated at E7 is not a jar default, so the record carries it |
| `MopScorerDistanceTest.theDefaultLadderWeighsEachDistanceLevel` | failure | d = 0 expected 194 but was 500 |
| `MopScorerDistanceTest.theDefaultLadderSplitsTiesWithTheNextLevelFloor` | failure | d = 1 x2: mopWeight expected 194 but was 500 |

### Passing new or edited tests (E7 stated, or true before and after)

`RunSpecResolveTest`: `noPresetStatesTheCalibratedKeys`, `aVariantWithoutTheLauncherResolves`,
`theFourE7ValuesStatedGiveTheE7Plan`, `theE7RecipeWithoutTheLauncherOmitsTheCadence`;
`RunSpecEchoTest.aDefaultMopCellCarriesNoneOfTheCalibratedKeys`;
`MopLadderE7EquivalenceTest.theStatedE7LadderEqualsTheE7Functions`
(`[E7-EQUIV] distanceWeight=12 splitTies=440 nextLevelWeight=5 mismatches=0`).

Log: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/gate_2_4_red.log`

## Gate 3.6 — after group 3 (2026-11-11 12:44)

Same command. Result: `Tests run: 91, Failures: 5, Errors: 0, Skipped: 0`.

- All 14 new tests of groups 1 and 2 are GREEN, and `MopLadderE7EquivalenceTest` still prints
  `[E7-EQUIV] distanceWeight=12 splitTies=440 nextLevelWeight=5 mismatches=0`.
- `RunSpecEchoTest` 22/22, `MopScorerDistanceTest` 22/22, `MopLadderE7EquivalenceTest` 1/1.
- The 5 failures are the pre-existing assertions on the old defaults in `RunSpecResolveTest`, which task 5.1
  moves (design D6, class "subject is the default"). The tasks.md text "every test GREEN" holds only for the
  tests of groups 1–2 at this point; the five reach GREEN in 5.6:

| Test | Line | Message |
|---|---|---|
| `theFourMopScoringKeysResolveToTheirDefaults` | 94 | expected `[500,400,300]` but was `[194,146,97]` |
| `mopParamsReadsTheFourKeysThroughTypedAccessors` | 104 | expected 500 but was 194 |
| `weightsReturnsAFreshCopy` | 275 | expected 500 but was 194 |
| `theLadderDigestIsCanonical` | 291 | "a stated default equals the unset key in the digest" (states `500,400,300`) |
| `theLoadTimeClampsArePreservedAsClamps` | 516 (was 347) | expected `50` but was `85` |

Log: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/gate_3_6_green.log`
