## Why

Calibration v6 (`rvsec-calibracao`, change `2026-11-aperv-calibracao-v6`, task P.1, decision D13) searches the MOP
guidance constants of the `distance` scoring mode. Two of its six dimensions are the **cut** c ∈ {1..6} and the
**ladder shape**. The jar fixes both. It has three per-level keys (`ape.mopWeightD1..D3` = 500/400/300). The cut d ≤ 3
appears as a literal in `MopScorer.distanceWeight` and `MopScorer.nextLevelWeight`, and it also depends on the
artifact, because the generator writes widget and handler pairs only up to d = 3. The calibration needs a ladder of any
length from 1 to 10, given as one key, and a cut the jar applies itself, once, when it loads the artifact. With the key
at its default, the jar must behave exactly like the E7 jar `bc4bc1b8`, so that the calibration's default reproduces
Study 3's E7 `mop` arm.

The acceptance contract is the seven jar requirements of
`rvsec-calibracao/openspec/changes/2026-11-aperv-calibracao-v6/specs/aperv-dependency/spec.md`. The jar built from this
change is delivered as one file plus its sha256. The calibration bind-mounts it read-only over the jar of the E7
campaign image (D34). There is no new image, and the jar is not copied into rv-android.

## What Changes

- **New plan key `ape.mopWeights`** (`MOP` feature, read only under `ape.mopScoring=distance`). It takes a
  comma-separated list of 1 to 10 non-negative integers, with default `500,400,300` and neutral value `0`. Element ℓ is
  the weight of level ℓ, and the list length is the cut c. It needs a new value type, `INT_LIST`. A value that is empty,
  has more than 10 elements, or has a negative or non-integer element is a type error. It aborts resolution with
  `reason=invalid_type key=ape.mopWeights` before step 1 (Pedro, 2026-11-02: the spec's aborts are the key's type).
  An increasing list is accepted, and its effect is documented.
- **BREAKING: `ape.mopWeightD1`, `ape.mopWeightD2` and `ape.mopWeightD3` are retired.** A properties file that sets one
  of them aborts with `reason=retired_key`, and the reason names `ape.mopWeights`. The plan getters
  `MopParams.weightD1..3` and the accessors `MopContext.weightD1..3` are deleted.
- **Level of a distance.** A distance d has level ℓ = max(d, 1). Its weight is element ℓ when ℓ ≤ c, and 0 otherwise.
  `MopScorer.distanceWeight` reads the ladder instead of three literals.
- **Tie split.** The rule `max(w(ℓ)/n, w(ℓ+1))` is unchanged. The next level comes from the list, and is 0 beyond c.
  `MopScorer.nextLevelWeight` loops over levels 1..c instead of 1..3. Ties stay ties by weight value, as in
  `bc4bc1b8`.
- **Cut at artifact load.** Under `distance`, `StaticArtifactLoader` drops every widget and handler pair with d > c
  while it reads them. It never drops `activityDist` pairs. A new `MOP_DATA` counter, `cutPairs`, counts the dropped
  pairs, and `droppedPairs` still counts only malformed ones (Pedro, 2026-11-02). Under `flag`, and in a cell without
  `MOP`, nothing is cut.
- **Tests.** New tests cover the coin's second key (the d+1 window). They pin its `bc4bc1b8` behaviour, which no test
  covers today. A differential test compares the new functions with frozen copies of the `bc4bc1b8` level-weight,
  tie-split and next-level functions.
- **Delivery note.** `delivery-v6.md` in this change directory records the commit, the jar sha256, the suite result,
  `openspec validate --strict`, one line per acceptance scenario, the differential evidence and the fixture diff.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mop-guidance`:
  - "MopScorer — Priority Boost": the weight and the tie split read the ladder.
  - ADDED "Distance-Pair Cut at Load".
  - "MopData — Load Status Line and Fail-Fast": adds `cutPairs`.
  - "MOP Coin Decider": the second-key scenarios.
  - "Target Retirement", "Handler Stamp Lookup" and "MopScorer — MOP-Flagged State Density": the d ≤ 3 literal and the
    retired key names are replaced by the ladder.
- `run-spec`:
  - "Feature Model with Declared Dependencies": the table of the MOP scoring keys changes.
  - ADDED "Distance Ladder Key": the `INT_LIST` type, its default, its neutral value, its aborts and the retirement of
    D1..D3.
- `event-sink`: "Run-Level Records" adds `MOP_DATA.cutPairs`.
- `llm-prompt`: "MOP Marker Annotation" takes `[DM]` from the ladder's first element and drops the d ≥ 4 literal.
- `scoring-pipeline`: "ScoringPass Plugin Interface" names the ladder instead of `mopWeightD1..3`.

## Impact

- **Code** (`src/main/java/com/android/commands/monkey/ape/`):
  - `runtime/ValueType` (`INT_LIST`), `runtime/KeyOwnership` (key, retired keys), `runtime/Feature` (neutral value),
    `runtime/MopScoring` (the `distance` key set), `runtime/RunSpec.MopParams` (`weights()`);
  - `utils/MopContext` (`weights()`, `cut()`), `utils/MopScorer` (`distanceWeight`, `nextLevelWeight`);
  - `utils/StaticArtifactLoader` (`PairReader` cut, `cutPairs`), `telemetry/EventSink` / `NdjsonSink` (`mopData`
    gains `cutPairs`).
- **Tests:** `RunSpecResolveTest`, `RunSpecEchoTest`, `RunSpecAbortTest`, `FeatureDerivationTest`,
  `KeyOwnershipTotalityTest`, `MopContextTest`, `MopScorerDistanceTest`, `MopDataTest`, `MopCoinStageTest`, the sink
  tests that pin `MOP_DATA`, plus new differential and coin-window tests. The cell fixtures
  `src/test/resources/cells/{mop,as_mop,as_mop_llm}.run_start.json` change only in `digest`.
- **Telemetry:** `MOP_DATA` gains `cutPairs`, which is 0 at the default with E7 artifacts. `RUN_START.params` shows
  `ape.mopWeights` only when it is not the default, as a JSON array of integers.
- **Cross-repository:** none edited. The calibration's copy of rv-android's `tool.py` already writes `ape.mopWeights`
  (D35, D36). rv-android's own `tool.py` still maps `mop_weight_d1..d3` → `ape.mopWeightD1..D3`, so this jar refuses an
  rv-android task that sets those settings. That is acceptable, because the jar is never copied into rv-android
  (D34). A later merge into `master` would need rv-android aligned first. That is Pedro's decision, and it is out of
  scope here.
- **E7 reproduction:** at the default, with E7 artifacts (pairs up to d = 3), nothing is cut and every weight, split,
  coin key and retirement equals `bc4bc1b8`.
