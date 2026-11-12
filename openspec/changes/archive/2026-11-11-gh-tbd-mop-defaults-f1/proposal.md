## Why

Calibration v6 (`rvsec-calibracao`, change `2026-11-aperv-calibracao-v6`) searched the four constants of the MOP
`distance` guidance that Study 3's paper states without a reason: the ladder and its cut (`ape.mopWeights`), the
retirement count N (`ape.mopRetireAfter`), the coin ledger k (`ape.mopCoinLedgerMaxInteractions`) and the launcher
cadence (`ape.activityTriggerStagnationStep`). Its report (`results/v6/report.md`, phase D closed 2026-11-08) measured
three finalists against the E7 values at 900 s, 40 apps × 5 reps. Pedro decided on 2026-11-11 (the calibration's task
X.1) to adopt finalist **F1** as the jar's defaults:

| Key | E7 value | F1 |
|---|---|---|
| `ape.mopWeights` | `500,400,300` | `194,146,97` |
| `ape.mopRetireAfter` | `3` | `10` |
| `ape.mopCoinLedgerMaxInteractions` | `1` (jar), `2` (E7 harness) | `4` |
| `ape.activityTriggerStagnationStep` | `50` | `85` |

The evidence supports F1 as **equivalent to the E7 values, with the best point estimate**, not as a demonstrated
improvement. F1 − default: S +0.53, 95% CI [−0.55, +1.64]; S_w +0.63 [−0.14, +1.55]; no term has a negative point
estimate. About 92% of the S difference is the misuse term M (+4.91), and most of that comes from one app
(`com.ivanovsky.passnotes`, 0% → 60%); without it M is +1.85. The search found the between-configuration spread of S
compatible with zero. These numbers are what the documentation may claim about the new defaults.

The calibration covered only these four keys, in the `mop` cell (its decision D22). Every other MOP constant stays at
the provisional E7 value and is not calibrated: the WTG, frontier and menu weights (200/200/250), `p_M = 1.0`,
`ape.mopLauncherDmax` and the launch cap, and every ADDONS, STATIC, BASE and LLM key.

## What Changes

- **New jar defaults** for the four keys, in `KeyOwnership`: `ape.mopWeights=194,146,97`, `ape.mopRetireAfter=10`,
  `ape.mopCoinLedgerMaxInteractions=4`, `ape.activityTriggerStagnationStep=85`.
- **Neutral values follow the defaults.** For a sub-parameter the neutral value is the jar default (`Feature`), so the
  neutrals of `ape.mopRetireAfter`, `ape.mopCoinLedgerMaxInteractions` and `ape.activityTriggerStagnationStep` become
  10, 4 and 85. The neutral of `ape.mopWeights` stays the one-level ladder `0`.
- **The cut is unchanged.** F1 has three levels, so c stays 3: an E7 artifact loses no pair at load and `cutPairs`
  stays 0, as before. Only the level weights, N, k and the cadence change.
- **No preset changes.** The values are defaults, not preset keys: a preset key counts as explicit, and an explicit
  launcher or coin key at a non-neutral value aborts a variant that unmounts that mechanism (the harness's
  `mop_nolaunch`, or `ape.mopCoinPercentage=0`) with `missing_dependency`, the reason the MOP block already leaves
  out `ape.mopWeightOpenMenu` (D-B14.1). A jar default is not explicit, so no variant is affected.
- **BREAKING (behaviour at default):** every MOP cell (`mop`, `as_mop`, `as_mop_llm`) now runs with F1. A run with
  no explicit key no longer reproduces Study 3's E7 `mop` arm. Reproducing E7 requires stating the four E7 values
  explicitly; the specs state that recipe and the tests that pin E7 equivalence state the old values instead of
  relying on the defaults.
- **Documentation** (`CLAUDE.md`, javadoc of the owning classes) gives the new defaults, their source (calibration v6,
  F1) and the equivalence framing above.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `run-spec`:
  - "Feature Model with Declared Dependencies": defaults and neutrals of `ape.mopWeights`, `ape.mopRetireAfter` and
    `ape.mopCoinLedgerMaxInteractions`.
  - "Distance Ladder Key": the default ladder and its scenario.
  - "Integer-List Rendering": the scenario whose stated ladder equals the default.
  - "Default Plan Equivalence With the E7 Jar": E7 equivalence holds with the four E7 values stated, not at the
    defaults.
- `mop-guidance`:
  - Purpose: the coin's default k.
  - "MopScorer — Priority Boost": the scenario "Default ladder equals the E7 jar" states the E7 ladder.
  - "MOP Coin Decider" and "MopInteractionLedger — Executions per Activity and Key": the default k and the scenarios
    written "at the default `ape.mopCoinLedgerMaxInteractions=1`".
- `component-triggering`: "Cadence-Based MOP Activity Launch": cadence default 85, and a value `<= 0` clamped to 85.
- `exploration`: "Configuration Loading": the description of the retired `ape.mopTargetPickCap` drops "(default 1)",
  as its abort reason does (design D4).

## Impact

- **Code** (`src/main/java/com/android/commands/monkey/ape/runtime/`): `KeyOwnership` (four defaults), `Feature`
  (three neutrals), and `RunSpec.clamp`, which maps a cadence `<= 0` to the literal `"50"` (`RunSpec.java:270-272`).
  That clamp means "fall back to the default", so it follows the new default and maps to 85 (Pedro, 2026-11-11). The
  coin's `< 1 → 1` clamp is a floor, not the default, and stays.
- **Tests:** 25 test files read at least one of the four keys today. Those that rely on the defaults rather than
  stating a value are identified in the design. They include the E7-equivalence check (`MopScorerDistanceTest`
  `[E7-EQUIV]`), the plan tests (`RunSpecResolveTest`, `RunSpecEchoTest`, `FeatureDerivationTest`,
  `KeyOwnershipTotalityTest`), the parity oracle's per-cell guard (`LadderConfigGuard`, which asserts the cadence 50),
  and the MOP-cell goldens (`ParityOracleMopTest`, `ParityOracleAsMopTest`, `ParityOracleAsMopLlmTest`). Each golden
  seeds the launcher counter at 48, two below the cadence, so the launcher fires inside its scenario; at 85 the seed
  moves to 83, and the recorded decisions are expected to stay the same. `SinkNeutralityTest` and `CleanControlTest`
  seed their replays the same way and derive the seed from the default instead (design D7). The oracle's driver writes no coin ledger, so k does not reach these goldens. A golden
  divergence is explained before any re-capture. The cell fixtures
  `src/test/resources/cells/{mop,as_mop,as_mop_llm}.run_start.json` are expected to change only in `digest`, because
  `RUN_START.params` lists only non-default keys.
- **Telemetry:** none new. `RUN_START.params` now lists the four keys when a run states the E7 values.
- **Cross-repository (not edited here):**
  - rv-android `aperv-tool/tool.py:117` sets `MOP_COIN_LEDGER_MAX_INTERACTIONS = 2` on every MOP cell. That explicit
    key overrides the new default, so campaigns keep k = 2 until rv-android drops it. The same file still maps the
    retired `mop_weight_d1..d3`. Both must be aligned before the next `mvn install -Drvsec_home`.
  - `rvsec-calibracao`: task X.1 records this change as the adoption decision.
  - Study 3's replication package runs its own pinned jar image, so its results are unaffected.
