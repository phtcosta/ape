## Context

The proposal adopts calibration v6's finalist F1 as the jar defaults of four MOP keys. The evidence and its framing
(equivalent to the E7 values, best point estimate, not an improvement) are in `proposal.md` "Why" and in the
calibration's report, `rvsec-calibracao/results/v6/report.md`.

Current state at `a0fb5c16` (paths under `src/main/java/com/android/commands/monkey/ape/`):

| Key | Default (`runtime/KeyOwnership.java`) | Neutral (`runtime/Feature.java`) | Other copies of the value |
|---|---|---|---|
| `ape.mopWeights` | `:210` `"500,400,300"` | `:194` `"0"` | retired-key reason of `ape.mopWeightD1..3`, `:332` "(default 500,400,300)"; `utils/MopScorer.java:177` javadoc |
| `ape.mopRetireAfter` | `:211` `"3"` | `:195` `"3"` | — |
| `ape.mopCoinLedgerMaxInteractions` | `:224` `"1"` | `:199` `"1"` | retired-key reason of `ape.mopTargetPickCap`, `:325` "(default 1)"; `agent/MopInteractionLedger.java:41`, `runtime/Feature.java:198`, `runtime/RunSpec.java:1101` javadoc |
| `ape.activityTriggerStagnationStep` | `:219` `"50"` | `:201` `"50"` | `runtime/RunSpec.java:270-272`, the clamp `value <= 0 ? "50" : raw` |

Three mechanisms decide what the change touches:

- **A preset key counts as explicit.** `RunSpec.resolve` puts `Presets.resolve(preset)` into `explicit`
  (`RunSpec.java:164`), and `applyInertRule` aborts on an explicit key of an absent feature at a non-neutral value
  (`RunSpec.java:345-372`). This rules out putting the values in `Presets.mopBlock` (D1).
- **A sub-parameter's neutral is its jar default** (`Feature.java:46-49`). Changing a default without its neutral
  would make "state the default for an absent feature" abort (D2).
- **`RUN_START.params` lists only non-default values plus active activation keys** (`RunSpecEcho.params`,
  `:128-139`). The cell fixtures of the MOP cells carry none of the four keys today, and keep carrying none. Only the
  plan `digest`, a hash over the effective values, moves.

Tests: 25 test files name one of the four keys, and 27 build a plan through `TestRunSpecs.mopSpec()` or a preset.
Most scoring tests build their `MopContext` with an explicit `new int[] {500, 400, 300}`, so the ladder default does
not reach them. The tests that read a default through the plan are the ones this change moves. Which of them fail is
measured, not guessed (D6).

## Architecture

No component is added or rewired. The change is data in two registries plus one clamp:

```
ape.properties ──► RunSpec.resolve ──► effective values ──► MopParams / RUN_START
                        │                     ▲
     Presets (unchanged)┘                     │ default when the key is not stated
                                   KeyOwnership.feature(..., default)   ◄── 4 values change
                                   Feature.own(key, neutral)            ◄── 3 neutrals change
                                   RunSpec.clamp(cadence <= 0)          ◄── falls back to the default
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `runtime/KeyOwnership.feature(...)` | declares each key's owner, type and jar default | — | the four F1 defaults |
| `runtime/Feature.own(key, neutral)` | declares the neutral value INV-RUN-05 accepts for an absent feature | — | neutrals 10, 4, 85 |
| `runtime/RunSpec.clamp(key, raw)` | load-time clamps | stated raw value | cadence `<= 0` → the key's default |
| `runtime/KeyOwnership.RETIRED` | retired keys and their reasons | — | reasons without a default value (D4) |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| run-spec "Calibrated MOP Defaults" | `KeyOwnership.java:210,211,219,224` | `RunSpecResolveTest` (new `f1Defaults*`), `KeyOwnershipTotalityTest:192-194,236` |
| run-spec "Calibrated Defaults Are Not Preset Keys" | `Presets.mopBlock` unchanged | `RunSpecResolveTest` (new: `mop` + `activityTriggerEnabled=false` resolves; stated 85 inert), `PresetsTest` |
| run-spec "Neutral Values Follow the Calibrated Defaults" | `Feature.java:195,199,201` | `FeatureDerivationTest:301`, `RunSpecResolveTest` (new: abort on 3 / 50 / 1) |
| run-spec "Calibrated Defaults Described as Equivalent" | `CLAUDE.md`, `KeyOwnership` comment | grep (task 6.2) and review (task 6.7) |
| run-spec "E7 Plan by Explicit Keys" | none | `RunSpecResolveTest` / `RunSpecEchoTest` (new: four keys stated → E7 values, `params` carries them) |
| run-spec "E7 Recipe Omits Keys of Absent Features" | none | `RunSpecResolveTest` (new: `mop_nolaunch` + E7 keys without the cadence resolves) |
| run-spec "Distance Ladder Key" (modified) | `KeyOwnership.java:210` | `RunSpecResolveTest:94,104,275` |
| run-spec "Integer-List Rendering" (modified) | none | `RunSpecResolveTest:290` (stated default ≡ unset, with `194,146,97`) |
| run-spec "Total Fail-Fast Validation" (modified) | `KeyOwnership.java:323-333` | `RunSpecAbortTest:361` |
| exploration "Configuration Loading" (modified) | none (spec text, D4) | none |
| run-spec "Feature Model with Declared Dependencies" (modified) | `KeyOwnership`, `Feature` | `KeyOwnershipTotalityTest`, `FeatureDerivationTest` |
| mop-guidance "MopScorer — Priority Boost" (modified) | none (`MopScorer` reads the ladder) | `MopScorerDistanceTest` (new: F1 arithmetic), `MopLadderE7EquivalenceTest:91` (states `500,400,300`) |
| mop-guidance "Target Retirement", "MOP Coin Decider", "MopInteractionLedger" (modified) | none | the census of group 3 (D6) |
| component-triggering "Cadence-Based MOP Activity Launch" (modified) | `RunSpec.clamp` | `RunSpecResolveTest:345-348` (clamp → 85), `MopLauncherStageTest` |
| parity-oracle "Config default drift is caught by the guard" (unchanged spec) | none | `LadderConfigGuard:87`, `ParityOracleMopTest`, `ParityOracleAsMopTest`, `ParityOracleAsMopLlmTest`, `PreemptionGoldenTest`; derived seeds in `SinkNeutralityTest`, `CleanControlTest` (D7) |
| guidance-groups "The committed RUN_START fixture matches" (unchanged spec) | none | `CellRunStartFixtureTest`: three MOP fixtures re-captured, `digest` only (D8) |

## Goals / Non-Goals

**Goals:**

- The four keys default to F1 in every plan that mounts their feature, with no preset change and no new abort for an
  existing harness variant.
- E7 stays reproducible by stating four keys, and the specs say how.
- Every copy of an old default in code, javadoc, abort messages, specs and `CLAUDE.md` is removed or updated.
- The oracle goldens stay byte-identical; the cell fixtures change in `digest` only.

**Non-Goals:**

- No change to rv-android (`tool.py:117` k = 2, the `mop_weight_d1..d3` mapping) or to the calibration repository.
- No change to any other key, including the uncalibrated MOP constants (WTG 200, frontier 200, menu 250, `p_M`,
  `ape.mopLauncherDmax`).
- No jar delivery to rv-android in this change. `mvn install -Drvsec_home` waits for rv-android's `tool.py`.
- No new mechanism, no new key, no change to scoring, the cut, the coin or the launcher logic.

## Decisions

**D1. Jar defaults, not preset keys** (Pedro, 2026-11-11). The first proposal put the values in `Presets.mopBlock`.
That makes them explicit, and an explicit `ape.activityTriggerStagnationStep=85` or
`ape.mopCoinLedgerMaxInteractions=4` aborts any plan that unmounts the launcher or the coin, such as rv-android's
`mop_nolaunch` (`tool.py:484-493`) or a `p_M = 0` dose. The block already leaves out `ape.mopWeightOpenMenu` for the
same reason (D-B14.1). *Alternatives:* a mixed split (ladder and N in the block, k and cadence as defaults) works but
puts one decision in two places; changing the resolver so that preset keys of absent features are inert changes
INV-RUN-05 for every key, which this change does not need.

**D2. Neutrals follow the defaults.** `Feature`'s rule is uniform: a sub-parameter's neutral is its jar default. The
neutrals of `ape.mopRetireAfter`, `ape.mopCoinLedgerMaxInteractions` and `ape.activityTriggerStagnationStep` become 10,
4 and 85. `ape.mopWeights` keeps neutral `0`, the one-level ladder, which is not its default and acts like the zero of
a weight. Consequence, stated in the run-spec delta: stating an E7 value for an absent feature now aborts. The
rule compares the clamped value, so without the coin a k below 1 (floored to 1) aborts too, while a cadence `<= 0`
without the launcher clamps to 85 and stays inert (review NT-06, accepted by Pedro 2026-11-11 and pinned in
`RunSpecResolveTest`).

**D3. The cadence clamp falls back to the default, read from `KeyOwnership`** (Pedro, 2026-11-11: the clamp follows the
new default). `RunSpec.clamp` returns `KeyOwnership.defaultOf("ape.activityTriggerStagnationStep")` instead of the
literal `"50"`, so the default lives in one place. *Alternative:* the literal `"85"`, a second copy that the next
default change would miss. The coin's `< 1 → "1"` clamp is a floor, not the default, and stays. So does the `1` that
`MopParams.coinLedgerMaxInteractions()` returns when `MOP_COIN` is absent (`RunSpec.java:1103-1106`): no coin reads it
then, and retirement reads only `tried`.

**D4. Retired-key reasons stop quoting defaults.** The reasons of `ape.mopTargetPickCap` and `ape.mopWeightD1..3`
end with "(default 1)" and "(default 500,400,300)". Updating them makes a third copy of each default; dropping the
parenthesis makes none. The run-spec delta modifies "Total Fail-Fast Validation" to match, and the exploration delta
modifies "Configuration Loading", whose description of `ape.mopTargetPickCap` ends with the same "(default 1)" (found
at apply, approved by Pedro 2026-11-11). `RunSpecAbortTest:361` checks only that the detail names the replacing key.

**D5. E7 equivalence: REMOVED + ADDED, and the scorer's examples keep the E7 arithmetic.** "Default Plan Equivalence
With the E7 Jar" is false after this change, and a MODIFIED block would keep a false header, so it is removed and
"E7 Plan by Explicit Keys" plus "E7 Recipe Omits Keys of Absent Features" are added. In mop-guidance, the scenarios
written "with default weights" now state `ape.mopWeights=500,400,300`. Their numbers, and the tests behind them, which
already build the context from `{500, 400, 300}`, do not move. One new scenario, "Distance weights at the default
ladder", pins F1's arithmetic (194/194/146/97/0; two ties at level 1 → 146; four at level 3 → 24). The scenario
"Default ladder equals the E7 jar" keeps its header (strict validation and `openspec archive` match scenarios by name)
and its body states the ladder.

**D6. Test adaptation rule, from a measured census.** After the production change, one full suite run lists the
failures (group 3). Each failing test is classified:

- *its subject is the default*, so its expected value moves to F1 (for example `RunSpecResolveTest` default
  assertions, `KeyOwnershipTotalityTest`, `FeatureDerivationTest`);
- *it relied on a default incidentally*, so it states the old value it was written against, and its expected
  values stay (for example a coin test written for k = 1, `MopLadderE7EquivalenceTest`);
- *anything else* is investigated before any edit and reported to Pedro.

A test that still passes may now exercise a different path, for example a coin test that is eligible more often at
k = 4. Review task 5.4 reads the coin, retirement and launcher tests that use `mopSpec()` and no explicit value for
the key they exercise.

**D7. Oracle: move the seeds with the cadence, never re-capture.** `LadderConfigGuard:87` asserts the cadence and
fails first, naming the key (parity-oracle "Config default drift is caught by the guard"). The guard moves to 85. The
scenario seeds move with it: `ParityOracleMopTest`, `ParityOracleAsMopTest` and `ParityOracleAsMopLlmTest` from
48 to 83 (two below), `PreemptionGoldenTest.DUE_CADENCE` from 49 to 84 (one below). These four carry a golden and
run the guard, so the seed stays a literal the guard protects. The golden files under `src/test/resources/goldens/`
SHALL stay byte-identical (`git diff --exit-code`). The oracle driver writes no coin ledger, so k does not reach
these goldens. A golden that still diverges is investigated, not re-captured.

*Amendment (census 3.8, Pedro 2026-11-11, option B).* Two more scripts seed the counter two below the old cadence:
`SinkNeutralityTest` (`SEEDED_CADENCE = 48`, "so the launcher fires inside a four-step scenario") and
`CleanControlTest` (`stepsSinceLauncherFiring(48)`). Both still pass at 85, but the launcher no longer fires in
their MOP-cell replays, so the neutrality gate and the non-vacuity replay lose the launch step without failing. They
run no guard and pin no launch step, so a literal would lose it again at the next cadence change. Their seed is
derived: `Integer.parseInt(KeyOwnership.defaultOf("ape.activityTriggerStagnationStep")) - 2`.
`ParityOracleAsMopLlmTest`, which D7 first omitted, takes the literal 83 like its siblings.

**D8. Cell fixtures re-captured, `digest` only.** `CellRunStartFixtureTest` compares the masked `RUN_START` of every
cell. The three MOP fixtures change in `digest`. They are re-captured with
`-Dape.oracle.regenerate=true -Dtest=CellRunStartFixtureTest` (only that test: the property also re-captures
goldens), and the diff must show the `digest` line of `mop`, `as_mop` and `as_mop_llm` and nothing else.

**D9. Documentation states the evidence.** `CLAUDE.md` (Central Configuration, the `ape.mopWeights` bullet, Retirement,
the k bullet, the cadence) and the `KeyOwnership` comment name calibration v6's F1, the interval [−0.55, +1.64] and
the four searched keys, and do not call the defaults an improvement (run-spec "Calibrated Defaults Described as
Equivalent"). Javadoc that quotes an old default (`MopScorer:177`, `MopInteractionLedger:41`, `Feature:198`, oracle
test javadoc `ScenarioScript:204`, `OracleScaffold:240`, `ParityOracle*Test:18`, `PreemptionGoldenTest:88`) is
updated in current-state wording (P4).

**D10. Hand-sync at archive.** `openspec archive` syncs requirement blocks only. Three texts outside them quote
an old default and are edited by hand at archive: the mop-guidance Purpose ("`ape.mopCoinLedgerMaxInteractions`
times (default 1)"), INV-MOP-38 ("(default 1: no entry; …)"), and any Purpose or invariant that task 6.2's grep finds.

## API Design

### `RunSpec.clamp(String key, String raw) -> String`

Unchanged signature. For `ape.activityTriggerStagnationStep` with `raw` parsing to `<= 0`, the result is
`KeyOwnership.defaultOf(key)` (`"85"`); otherwise `raw`. The other clamps are unchanged. Silent, as before.

No other signature changes. `KeyOwnership.defaultOf(String)` already exists (`RunSpecEcho.isDefault` uses it).

## Data Flow

A properties file that does not state a key gets the `KeyOwnership` default into the effective values. A file that
states it gets the stated value, clamped where a clamp applies. `MopParams`, `MopContext.fromSpec`, the stages and
`RUN_START` read the effective values, as before. `RUN_START.params` omits a key at its default, so F1 runs list none of
the four, and runs that reproduce E7 list all four. The plan `digest` changes for every plan that mounts a feature
owning one of the keys.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `missing_dependency` on an E7 value stated for an absent feature | `RunSpec.applyInertRule` | abort before step 1 (existing rule, new neutral) | omit the key, or state the F1 value ("E7 Recipe Omits Keys of Absent Features") |
| golden divergence after the seed move | `GoldenFile.compare` | test fails | investigate (D7); never re-capture to pass |
| fixture diff beyond `digest` | `CellRunStartFixtureTest` | test fails after re-capture review | investigate before committing the fixture |

## Risks / Trade-offs

- [rv-android `tool.py:117` states k = 2 on every MOP cell, so campaigns run F1's ladder, N and cadence with k = 2,
  a combination the calibration never measured] → this change does not deliver the jar to rv-android. Task 7.3
  writes the handoff, and Pedro decides when rv-android drops the constant. The rv-android image build compiles the ape
  of `origin/master`, so the push (task 7.6) is ordered against that decision.
- [F1 was measured only in the `mop` cell; `as_mop` and `as_mop_llm` get it through the same defaults] → stated in
  the proposal and in `CLAUDE.md`. The block is shared by design, so a per-cell value would need a new preset key,
  which D1 rules out.
- [N = 10 sits at the edge of the searched range 1–10, and the 900 s interval contains zero] → the documentation
  carries the interval and the equivalence framing (D9). No claim of improvement is made.
- [A test may keep passing while it exercises a different path under k = 4 or N = 10] → review task 5.4 (D6).
- [The plan `digest` of every MOP plan changes] → nothing reads `RUN_START` (`RunSpecEcho` javadoc, owner decision
  D1). Traces are joined to their jar by `build`.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (runtime) | the four defaults, the three neutrals, the clamp to 85, no preset key, the E7 recipe, the `mop_nolaunch` plan | `RunSpecResolveTest`, `RunSpecEchoTest`, `KeyOwnershipTotalityTest`, `FeatureDerivationTest`, `RunSpecAbortTest` | ~8 new `@Test`, ~10 edited assertions |
| Unit (scoring) | F1 arithmetic of the level rule and tie split; E7 equivalence with the ladder stated | `MopScorerDistanceTest`, `MopLadderE7EquivalenceTest` | 1–2 new `@Test` |
| Integration (oracle) | goldens byte-identical after the seed move; fixtures differ only in `digest` | `ParityOracleMopTest`, `ParityOracleAsMopTest`, `PreemptionGoldenTest`, `CellRunStartFixtureTest` | 0 new |
| Regression | full suite | `mvn -o clean test` | expected 1537 + new, 0 failures, 0 errors, 28 skipped |

## Open Questions

- Whether rv-android drops `MOP_COIN_LEDGER_MAX_INTERACTIONS = 2` or sets it to 4, and when: Pedro decides
  (handoff, task 7.3). The `gator` session's change `gh-tbd-gator-full-minified-horizon` also edits `tool.py` and
  leaves k = 2 alone.
- Whether `CLAUDE.md`'s description of the presets ("The blocks reproduce Study 03 E6's configuration") needs a
  sentence saying that the MOP cells now run the F1 defaults: proposed yes, in task 4.1.
