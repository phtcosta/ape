# Test census after the production change (task 3.8, design D6/D7/D8)

Run 2026-11-11 12:45, worktree with groups 1–4 applied (src/main at F1), base `a0fb5c16`:

```
nice -n 19 ionice -c3 mvn -o clean test
Tests run: 1551, Failures: 23, Errors: 1, Skipped: 28   (BUILD FAILURE)
```

1551 = 1537 (baseline `a0fb5c16`) + 14 new `@Test` of groups 1–2. `git diff -- src/test/resources/goldens
src/test/resources/cells` is empty: nothing was re-captured.

Log: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/census_3_8.log`

Classes (design D6): **S** = its subject is the default (expected value moves to F1); **I** = relied on a
default incidentally (state the old value it was written for, keep its expectations); **O** = other.
D7 (oracle seeds move with the cadence) and D8 (fixture digest) are the oracle/fixture forms of S.

## Failing tests (24)

| # | Test | Line | Message (abridged) | Key | Class | Track |
|---|---|---|---|---|---|---|
| 1 | `runtime.RunSpecResolveTest.theFourMopScoringKeysResolveToTheirDefaults` | 94 | expected `500,400,300` was `194,146,97` | `mopWeights`, `mopRetireAfter` | S | 5.1 |
| 2 | `runtime.RunSpecResolveTest.mopParamsReadsTheFourKeysThroughTypedAccessors` | 104 | expected 500 was 194 | `mopWeights`, `mopRetireAfter` | S | 5.1 |
| 3 | `runtime.RunSpecResolveTest.weightsReturnsAFreshCopy` | 275 | expected 500 was 194 | `mopWeights` | S | 5.1 |
| 4 | `runtime.RunSpecResolveTest.theLadderDigestIsCanonical` | 291 | "a stated default equals the unset key in the digest" (states `500,400,300`) | `mopWeights` | S | 5.1 |
| 5 | `runtime.RunSpecResolveTest.theLoadTimeClampsArePreservedAsClamps` | 516 | expected `50` was `85` | cadence clamp | S | 5.1 |
| 6 | `runtime.KeyOwnershipTotalityTest.theFourMopScoringKeysAreOwnedByMopWithTheSpecsTypesAndDefaults` | 200 | jar default expected `500,400,300` was `194,146,97` | `mopWeights`, `mopRetireAfter` | S | 5.1 |
| 7 | `runtime.KeyOwnershipTotalityTest.theGroupContractKeysHaveTheirOwnersTypesAndDefaults` | 254 | jar default expected `1` was `4` | `mopCoinLedgerMaxInteractions` | S | 5.1 |
| 8 | `runtime.FeatureDerivationTest.theMopFeatureOwnsTheFourScoringKeysWithTheirNeutralValues` | 306 | neutral expected `3` was `10` | `mopRetireAfter` (neutral) | S | 5.1 |
| 9 | `utils.MopContextTest.fromSpecCarriesThePlanDefaults` | 30 | expected 500 was 194 | `mopWeights`, `mopRetireAfter` | S | 5.2 |
| 10 | `agent.StatefulAgentMopRetirementTest.aRetiringStepReportsItsTargetsToTheSink` | 258 | expected 1 was 0 (target 22 not retired at the 3rd count) | `mopRetireAfter` via `mopSpec("ape.mopScoring","distance")` | I | 5.4 |
| 11 | `agent.StatefulAgentMopRetirementTest.nothingIsCountedWithoutAnUnretiredPair` | 280 | target 9 not retired after 3 counts | `mopRetireAfter` (same) | I | 5.4 |
| 12 | `agent.StatefulAgentMopTelemetryTest.theThirdFirstInteractionRetiresOnItsOwnRecord` | 304 | error: `JSONObject["ret"] not found` | `mopRetireAfter` via `mopSpec("ape.mopScoring","distance")` | I | 5.4 |
| 13 | `agent.StatefulAgentMopTelemetryTest.runEndCarriesTheAgentsStampAndRetirementCounts` | 327 | expected 2 was 0 (`RUN_END.retired`) | `mopRetireAfter` (same) | I | 5.4 |
| 14 | `oracle.ParityOracleMopTest.ladderConfigDefaultsAreUnchanged` | 100 | cadence expected 50 was 85 (`LadderConfigGuard`) | cadence | S (D7 guard) | 5.3 |
| 15 | `oracle.ParityOracleMopTest.baselineGoldenIsReproduced` | 113 | step 1 expected `EVENT_TRIGGER_ACTIVITY` was `MODEL_CLICK` (seed 48) | cadence | S (D7 seed) | 5.3 |
| 16 | `oracle.ParityOracleAsMopTest.ladderConfigDefaultsAreUnchanged` | 100 | cadence expected 50 was 85 | cadence | S (D7 guard) | 5.3 |
| 17 | `oracle.ParityOracleAsMopTest.baselineGoldenIsReproduced` | 113 | step 1 expected `EVENT_TRIGGER_ACTIVITY` was `MODEL_CLICK` (seed 48) | cadence | S (D7 seed) | 5.3 |
| 18 | `oracle.ParityOracleAsMopLlmTest.ladderConfigDefaultsAreUnchanged` | 74 | cadence expected 50 was 85 | cadence | S (D7 guard) — **not listed in D7 / 5.3** | 5.3 |
| 19 | `oracle.ParityOracleAsMopLlmTest.baselineGoldenIsReproduced` | 94 | step 2 expected `EVENT_TRIGGER_ACTIVITY` was `MODEL_CLICK` (`SEEDED_CADENCE = 48`, `:40`) | cadence | S (D7 seed) — **not listed in D7 / 5.3** | 5.3 |
| 20 | `oracle.PreemptionGoldenTest.ladderConfigDefaultsAreUnchanged` | 157 | cadence expected 50 was 85 | cadence | S (D7 guard) | 5.3 |
| 21 | `oracle.PreemptionGoldenTest.theSeededCadenceIsOneBelowTheConfiguredFiringPoint` | 165 | cadence expected 50 was 85 (`DUE_CADENCE = 49`) | cadence | S (D7 seed) | 5.3 |
| 22 | `oracle.PreemptionGoldenTest.preemptionGoldenIsReproduced` | 193 | expected `EVENT_TRIGGER_ACTIVITY` was `MODEL_CLICK` | cadence | S (D7 seed) | 5.3 |
| 23 | `oracle.PreemptionGoldenTest.anAcceptedStepAdvancesTheLauncherCadence` | 274 | expected `EVENT_TRIGGER_ACTIVITY` was `MODEL_CLICK` | cadence | S (D7 seed) | 5.3 |
| 24 | `oracle.CellRunStartFixtureTest.eachCellsRunStartMatchesItsFixture` | 115 | `mop`: `digest` `5e0068112e42a1bc` → `356216783902d09d` (first mismatch only) | all four (digest) | S (D8) | 6.1 |

No row of class O by mechanism: every failure is the old default read as the subject (S, D7, D8) or the old
retirement count reached through `TestRunSpecs.mopSpec` (I).

## Artifact gap found by the census (stops the wave until Pedro has seen it)

Design D7 and task 5.3 name the seeds of `ParityOracleMopTest`, `ParityOracleAsMopTest` (48 → 83) and
`PreemptionGoldenTest.DUE_CADENCE` (49 → 84). The census and a grep of `oracle/` for
`stepsSinceLauncherFiring(` find three more scripts seeded two below the old cadence:

| File | Seed | Status at 3.8 | Effect of the move to 85 |
|---|---|---|---|
| `oracle/ParityOracleAsMopLlmTest.java:40` | `SEEDED_CADENCE = 48` | FAILS (rows 18–19) | its golden expects the launcher on step 2 |
| `oracle/SinkNeutralityTest.java:69` | `SEEDED_CADENCE = 48` ("Two below the cadence, so the launcher fires inside a four-step scenario (design D2)") | passes | the launcher no longer fires in the MOP cells' four-step replay, so the neutrality gate stops covering the launcher path |
| `oracle/CleanControlTest.java:72` | `.stepsSinceLauncherFiring(48)` (same four-step script) | passes | the non-vacuity replay in `AS_MOP_LLM` no longer contains a launch; the clean cells have no launcher, so their assertions are unaffected |

`PreemptionGoldenTest:251-266` and `:286,308` pass `DUE_CADENCE` or `0` to `oneStep(...)`, so they follow the
`DUE_CADENCE` move already in 5.3. The three `agent/pipeline` tests that name the cadence
(`HardPreemptionTest:49`, `PipelineFixture:249`, `StageProvenanceLabelTest:62`) state it explicitly and pass.

Resolved (Pedro, 2026-11-11, option B; design D7 amended): `ParityOracleAsMopLlmTest` seed 48 → 83 (literal,
guarded); `SinkNeutralityTest` and `CleanControlTest` derive the seed from `KeyOwnership.defaultOf` − 2.

## Edits of Wave 3 (tracks 5.1–5.4)

| Track | File | Edit | Rows |
|---|---|---|---|
| 5.1 | `runtime/RunSpecResolveTest` | defaults → `194,146,97` / 10 in four tests; stated default ≡ unset uses `194,146,97`; cadence clamp `"50"` → `"85"` | 1–5 |
| 5.1 | `runtime/KeyOwnershipTotalityTest` | ladder/N defaults → F1; k default `1` → `4`; javadoc notes the moved default | 6–7 |
| 5.1 | `runtime/FeatureDerivationTest` | N neutral `3` → `10` | 8 |
| 5.2 | `utils/MopContextTest` | `fromSpec` defaults → `{194,146,97}`, 10 | 9 |
| 5.2 | `utils/TestMopContexts` | javadoc only: `flag(...)` holds fixed E7 values for the distance keys, inert under flag | — |
| 5.3 | `oracle/LadderConfigGuard` | cadence 50 → 85 | 14, 16, 18, 20 |
| 5.3 | `oracle/ParityOracle{Mop,AsMop,AsMopLlm}Test` | `SEEDED_CADENCE` 48 → 83, javadoc | 15, 17, 19 |
| 5.3 | `oracle/PreemptionGoldenTest` | `DUE_CADENCE` 49 → 84, javadoc | 21–23 |
| 5.3 | `oracle/SinkNeutralityTest`, `oracle/CleanControlTest` | seed derived `defaultOf(cadence) − 2` | (passing, path restored) |
| 5.3 | `oracle/ScenarioScript`, `oracle/OracleScaffold` | javadoc: cadence 85; firing at or past the cadence, counter advanced at every step (`MopLauncherStage.shouldFire` `>=`, `:243-244`) | — |
| 5.4 | `agent/StatefulAgentMopRetirementTest` | plan states `ape.mopRetireAfter=3` (class I) | 10–11 |
| 5.4 | `agent/StatefulAgentMopTelemetryTest` | `distance()` states `ape.mopRetireAfter=3` (class I) and `ape.mopWeights=500,400,300` (hand-set boosts 500/400 are the E7 ladder's) | 12–13 |
| 5.4 | `agent/pipeline/PipelineFixture`, `agent/ActivityFrontierTest` | comments no longer call k = 1 / cadence 50 the default | — |

Row 24 (`CellRunStartFixtureTest`) is re-captured in 6.1.

## Passing tests reviewed for a changed path (task 5.4, and the review of track 5.2)

No reviewed test runs a different path at F1 after the edits above; none is "unsure".

| Test | Verdict | Key | Reason |
|---|---|---|---|
| `agent/StatefulAgentMopRetirementTest` (other methods) | unaffected | N, ladder, k | highest count reached is 2; d ≥ 4 is beyond c = 3 for both ladders; ledgers built with explicit k (`new MopInteractionLedger(1)`, `(3)`) |
| `agent/StatefulAgentMopTelemetryTest` (other methods) | unaffected (ladder now stated) | ladder | `dec.mop` is the hand-set `getMopBoost()`; `mopd` needs only weight > 0 at d = 0/2 |
| `agent/PostSelectionRestartTest` | unaffected | k, cadence | `ScriptedAgent` overrides `selectNewActionNonnull`, the only caller of `decisionPipeline.decide`; ledger k = 1 explicit; flag plan |
| `agent/SataExtensionPointTest` | unaffected | — | `installMop()` checks mounting; `mopSpec()` flag; no pipeline, ledger or retirement |
| `agent/pipeline/MopCoinStageTest` | unaffected | k, ladder, N | every ledger states k; distance contexts built with ladder and N = 3; retirements explicit |
| `agent/pipeline/StageProvenanceLabelTest` | unaffected | cadence, k, N | cadence stated `"1"`; the fixture's ledger is never written; flag context |
| `agent/pipeline/HardPreemptionTest` | unaffected | cadence, k | cadence stated (`fullPlan(1|2)`, `llmWithLauncherPlan(2)`) |
| `agent/pipeline/PipelineParityTest` | unaffected | — | assembly only; the one `decide` is in the base cell |
| `agent/pipeline/MopLauncherStageTest`, `agent/MopInteractionLedgerTest`, `LlmStructuralFallbackTest` | unaffected | cadence, k | values explicit |
| `model/ScorerCounterfactualTest`, `model/ModelActionTest`, `telemetry/StepRecordTest`, `telemetry/NdjsonSinkTest` | unaffected | — | no plan / no MOP context, or literal sink inputs |
| `llm/ApePromptBuilderMopMarkerTest`, `llm/PromptContributionTest` | unaffected | ladder, N | contexts built with ladder and N = 3 stated |
| `utils/MopRetirementTest` | unaffected | N | every `MopRetirement` has an explicit count; distance contexts state the E7 ladder |
| `utils/MopDataCutTest` | unaffected | ladder | `theE7FixtureAtTheDefaultLadderIsNotCut` reads only the ladder's length, 3 for E7 and F1 |
| `utils/MopDataTest`, `utils/StaticGuiModelTest`, `utils/MopScorerTest`, `utils/MopScorerContainmentTest`, `utils/MopDataLookupTest` | unaffected | — | flag mode or non-MOP plan |
| `agent/scoring/*` (`MopWidgetPassTest`, `ScoringPass*Test`, `ScoringPipelineTest`, `NavFrontierPassTest`, `MopFrontierPassTest`, `ScoringParamsDefaultsTest`) | unaffected | — | E7 ladder stated, or flag contexts, or keys outside the four |

## Gate 5.6 (2026-11-11 13:48–13:50)

| Track | `-Dtest` | Result |
|---|---|---|
| 5.1 runtime | `RunSpec*Test,Feature*Test,KeyOwnership*Test,Presets*Test,CellPresetTest` | 155 run, 0 failures, 0 errors |
| 5.2 utils+scoring | `MopContextTest,MopRetirementTest,MopDataCutTest,MopDataTest,MopScorerDistanceTest,MopLadderE7EquivalenceTest,TestMopContexts*,MopWidgetPassTest` | 92 run, 0 failures, 0 errors |
| 5.4 agent | `StatefulAgentMopRetirementTest,StatefulAgentMopTelemetryTest,HardPreemptionTest,StageProvenanceLabelTest,MopCoinStageTest,MopLauncherStageTest,ActivityFrontierTest,PostSelectionRestartTest,SataExtensionPointTest` | 129 run, 0 failures, 0 errors |
| 5.3 oracle (`/sdd-test-run`) | `ParityOracle*Test,PreemptionGoldenTest,LadderConfig*,CleanControlTest,SinkNeutralityTest` | 30 run, 0 failures, 0 errors |

`git diff --exit-code -- src/test/resources/goldens` is empty: the goldens are byte-identical. Logs:
`/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/gate_5_6_*.log`
