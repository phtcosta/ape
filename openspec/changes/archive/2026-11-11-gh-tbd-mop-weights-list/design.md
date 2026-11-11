## Context

Calibration v6 searches the cut c and the ladder shape of the `distance` scoring mode (proposal). Today, at
`bc4bc1b8`, the E7 jar:

- reads three per-level keys, `ape.mopWeightD1..D3` (500/400/300). They are declared in
  `src/main/java/com/android/commands/monkey/ape/runtime/KeyOwnership.java:209-211`, neutral `0` in
  `runtime/Feature.java:194-196`, listed in `MopScoring.DISTANCE`'s key set (`runtime/MopScoring.java:46`), read by
  `RunSpec.MopParams.weightD1..3` (`runtime/RunSpec.java:1113-1125`) and carried by `utils/MopContext`
  (`weightD1..3`).
- maps a distance to a weight in `utils/MopScorer.distanceWeight` (`:179-190`): `d ≤ 1` → D1, `2` → D2, `3` → D3, else
  0.
- splits ties in `MopScorer.splitTies` (`:206-234`). Ties are by weight value, and the floor comes from
  `nextLevelWeight` (`:241-248`), which loops over the literal levels `1..3`.
- loads widget, handler and activity pairs through one `StaticArtifactLoader.PairReader`
  (`utils/StaticArtifactLoader.java:396-429`). It drops only malformed pairs and counts them in
  `MOP_DATA.droppedPairs`.
- relies on the generator's cut: widget and handler lists carry targets at `d ≤ 3` (gh122's `DIST_WEIGHED_MAX`).
  `activityDist` keeps the three nearest pairs.

The acceptance contract is the seven jar requirements of
`rvsec-calibracao/openspec/changes/2026-11-aperv-calibracao-v6/specs/aperv-dependency/spec.md` (D15–D18). This
change's deltas restate them in this repository's specs: `run-spec` (the key, its type, the retirement, the default
equivalence) and `mop-guidance` (the level rule, the tie split, the cut at load, the coin's second key).

Pedro's decisions (2026-11-02):

1. slug `mop-weights-list`;
2. the spec's aborts are the key's type (`invalid_type`), an accepted exception to the "no new abort" rule;
3. cut pairs get their own `MOP_DATA` counter;
4. an increasing ladder is accepted and documented.

Constraints:

- Work only in this worktree, branched from `bc4bc1b8`. `master` stays the E7 jar.
- Deliver one jar file plus its sha256. The calibration bind-mounts it over the E7 image's jar, so rv-android is not
  touched.
- Never run `mvn install -Drvsec_home=…`: it copies the jar into rv-android.

## Architecture

```
ape.properties ─► RunSpec.resolve ─► ValueType.INT_LIST.parse("ape.mopWeights") ─► List<Integer>
                      │                (invalid_type on blank/empty elem/non-int/negative/>10)
                      ▼
               RunSpec.MopParams.weights() : int[]   (copy)
                      │
     ┌────────────────┴──────────────────────────┐
     ▼                                           ▼
StaticArtifactLoader.load(…)                MopContext.fromSpec(data, model, params)
  cut = MOP ∧ distance ? weights.length          weights : int[]   cut() = weights.length
        : NO_CUT                                 │
  PairReader(targets, cut)                       ▼
    widget dist   ─ read(list, CUT)          MopScorer.distanceWeight(mop, d)
    handler dist  ─ read(list, CUT)              ℓ = max(d,1); ℓ ≤ c ? weights[ℓ-1] : 0
    activityDist  ─ read(list, NO_CUT)       MopScorer.splitTies / nextLevelWeight (levels 1..c)
  MOP_DATA {…, droppedPairs, cutPairs}           │
     │                                           ▼
     ▼                                       MopWidgetPass, MopCoinStage (targetCount), MopRetirement,
  MopData (pairs ≤ c)  ────────────────────► prompt marker, stateMopDensity   (unchanged)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `runtime/ValueType.INT_LIST` | Parse, compare and render the ladder | raw string | unmodifiable `List<Integer>`, or `RunSpecException(INVALID_TYPE)` |
| `runtime/KeyOwnership` | Declare `ape.mopWeights` (MOP, `INT_LIST`, `500,400,300`) and retire D1..D3 | — | registry |
| `runtime/Feature` | Neutral `0` for `ape.mopWeights` on `MOP` | — | neutral table |
| `runtime/MopScoring.DISTANCE` | `distance` key set: `ape.mopWeights`, `ape.mopRetireAfter`, `ape.mopLauncherDmax` | — | INV-RUN-09 sets |
| `runtime/RunSpec.MopParams.weights()` | The ladder as a fresh `int[]` | plan | `int[]` (length 1..10) |
| `utils/MopContext` | Carry the ladder; `levelWeight(ℓ)`, `cut()` | `MopParams` | context |
| `utils/MopScorer.distanceWeight` | d → weight by level | context, d | int |
| `utils/MopScorer.nextLevelWeight` | Floor of the tie split, levels `1..c` | context, weight | int |
| `utils/StaticArtifactLoader.PairReader` | Drop malformed pairs (`dropped`), then pairs beyond the cut (`cut`) | wire list, apply-cut flag | `int[][]` |
| `telemetry/EventSink.mopData` / `NdjsonSink` | Write `cutPairs` in `MOP_DATA` | counters | NDJSON line |

## Mapping: Spec -> Implementation -> Test

| Requirement (delta) | Implementation | Test |
|-------------|---------------|------|
| run-spec "Distance Ladder Key", scenario Default | `KeyOwnership`, `RunSpec.MopParams.weights()` | `RunSpecResolveTest` (defaults), `MopContextTest` |
| run-spec "Distance Ladder Key", scenario Inert/neutral | `Feature.MOP.own("ape.mopWeights","0")`, `MopScoring.DISTANCE` | `RunSpecResolveTest` (inert per mode), `FeatureDerivationTest` (neutrals) |
| run-spec "Distance Ladder Key", scenario Increasing | `INT_LIST` accepts any order | `RunSpecResolveTest` |
| run-spec "Integer-List Value Type", scenario Malformed | `ValueType.INT_LIST.parse` | `ValueTypeIntListTest` (new), `RunSpecAbortTest` |
| run-spec "Integer-List Rendering" | `ValueType.canonical`/`sameValue`, `RunSpecEcho` (List → JSON array via `Json`) | `RunSpecEchoTest`, `RunSpecResolveTest` (digest) |
| run-spec "Per-Level Distance Keys Retired" | `KeyOwnership.RETIRED` | `RunSpecAbortTest.everyRetiredKeyAbortsWithItsOwnReason` + a D2 case |
| run-spec "Default Plan Equivalence With the E7 Jar" | — (consequence) | `CellRunStartFixtureTest` (regenerated; diff = `digest` only) |
| run-spec "Feature Model…" (table, scenarios) | as above | `KeyOwnershipTotalityTest`, `RunSpecResolveTest` |
| mop-guidance "MopScorer — Priority Boost" (level rule, tie split, new scenarios) | `MopScorer.distanceWeight`, `splitTies`, `nextLevelWeight` | `MopScorerDistanceTest` (+ ladder cases), `MopLadderE7EquivalenceTest` (new, differential) |
| mop-guidance "Distance-Pair Cut at Load" | `StaticArtifactLoader.load` (cut from plan), `PairReader.read(list, applyCut)` | `MopDataCutTest` (new) |
| mop-guidance "Cut Pairs Counted Apart From Malformed Pairs" | `PairReader` (`dropped`, `cut`) | `MopDataCutTest` |
| mop-guidance "Cut Applied Before the Merges" | the cut happens inside `read`, before `decodeEntry`'s merges and `clickAggregate` | `MopDataCutTest` |
| mop-guidance "No Cut Outside the Distance Mode" | `cut = NO_CUT` unless `MOP ∧ distance` | `MopDataCutTest` |
| mop-guidance "Readers Unchanged on the Remaining Pairs" | — | full suite; `MopDataTest` (cryptoapp fixture unchanged) |
| mop-guidance "MopData — Load Status Line…" (`cutPairs`) | `EventSink.mopData`, `NdjsonSink`, `NoopSink` | `MopDataTest`/sink tests that pin `MOP_DATA` |
| mop-guidance "MOP Coin Decider" (Window boundary, Retired target excluded, Window never passes the cut) | `MopCoinStage.targetCount` (made package-private, unchanged logic) | `MopCoinStageTest` (new cases) |
| mop-guidance "Target Retirement", "Handler Stamp Lookup", "…State Density"; llm-prompt "MOP Marker Annotation"; scoring-pipeline "ScoringPass Plugin Interface" | text only: the ladder replaces the retired names | existing tests, renamed literals |
| event-sink "Run-Level Records" (`cutPairs`) | `NdjsonSink.mopData` | sink test + `MopDataCutTest` |

## Goals / Non-Goals

**Goals:**
- One list key, `ape.mopWeights`, 1–10 levels, applied only under `distance`.
- A cut the jar applies once, at load, to widget and handler pairs only.
- At the default, behaviour identical to `bc4bc1b8`, shown by a differential test and the fixture diff.
- Tests for the coin's second key, which has none today.
- One jar plus its sha256, and a delivery note with the evidence P.3 reads.

**Non-Goals:**
- No change to rv-android, the derive, the harness, the Docker image or the calibration repository.
- No change to `flag`, `activityDist`, `ape.mopLauncherDmax`, `ape.mopRetireAfter`, k (`ape.mopCoinLedgerMaxInteractions`,
  jar default 1), or the ranking of the MOP coin.
- No rejection of increasing ladders, and no clamp.
- No merge into `master`, no push. Pedro decides the merge, and it would need rv-android's `tool.py` aligned first
  (proposal, Impact).

## Decisions

**D1. A list value type, `INT_LIST`, not three or ten numbered keys.**
- Parsing splits on `,` and trims each element, as `INT` trims. It returns `Collections.unmodifiableList`.
- The failures are: blank value, empty element, non-`int` element, negative element, more than 10 elements. Each
  throws `RunSpecException(INVALID_TYPE, key, "expected 1 to 10 comma-separated non-negative integers")`.
- `canonical` renders the parsed list joined by `,` with no spaces. `sameValue` compares the parsed lists.
- The echo puts the parsed `List<Integer>` into `RUN_START.params`, and `runtime/Json` already writes a `Collection` as
  an array.

Alternative considered: keep `D1..D3` and add `D4..D10` with a length key. Rejected, because the contract names one
key (D15) and numbered keys leave undefined what an unset middle level means.

Pedro decided (2026-11-02) that the bounds are part of the type. So the abort uses the existing `invalid_type` class,
and no new abort class is introduced.

**D2. `MopParams.weights()` returns a fresh `int[]`. `MopContext` keeps its own copy.**
- `MopContext` gains `weights` (`int[]`), `levelWeight(int level)` (element `level − 1`, or 0 when
  `level > cut()`) and `cut()`.
- `weightD1..3` are deleted from both classes (P3).
- `RunSpec` is immutable (INV-RUN-01), and a copy on each call keeps a caller from mutating the plan.

**D3. The level rule.** `MopScorer.distanceWeight(mop, d)` computes `ℓ = max(d, 1)` and returns
`mop.levelWeight(ℓ)`. `d = Integer.MAX_VALUE` (no unretired pair) gives `ℓ > c`, hence 0. This is still the one
mapping from a distance to a weight.

**D4. Tie split, `bc4bc1b8` semantics.**
- `splitTies` is unchanged: ties are by weight value, and the share is `max(best / tied, nextLevelWeight(mop, best))`.
- `nextLevelWeight` loops `for (int l = 1; l <= mop.cut(); l++)`: the first level whose weight equals `best` gives
  `levelWeight(l + 1)`, which is 0 for `l = c`. If no level matches, it returns 0.
- Ties stay by value, not by level, on purpose (handoff §2). On a strictly decreasing ladder the two are the same. On a
  plateau, `W_next = W` and nothing splits. On an increasing ladder the rule stays defined: under `300,500` the best
  weight 500 sits at level 2 = c, so `W_next = 0`.
- A level-based tie would change no scenario of the contract, but it would be a second behaviour change. So it is not
  adopted.
- When the best weight's successor weighs more (`300,500` with best weight 300), `W_next > W` and each tied action
  receives more than its undivided weight: two ties at 300 get 500 each. `bc4bc1b8` does the same with
  `D1 = 300`, `D2 = 500`. This is documented and pinned by a test, not capped (Pedro, 2026-11-03, review WR-01).

**D5. The cut goes in `PairReader`, as each list is read.**
- `PairReader(int targets, int cut)` gains a `cut` counter beside `dropped`.
- `read(JSONArray list, boolean applyCut)` first applies the well-formedness test: a failure counts in `dropped` and
  the read goes on. Then, when `applyCut` is set and `d > cut`, the pair counts in `cut` and is skipped.
- Widget `dist` and handler `dist` pass `true`. `activityDist` passes `false`.
- Cutting at the source means the collision merge (INV-MOP-19), the click aggregate and every reader see only pairs at
  `d ≤ c`. Because both merges keep the minimum distance per target, cutting before the merge gives the same result as
  cutting after it.

Alternative considered: filter inside `MopData` after the load, or inside each reader (scorer, coin, retirement).
Rejected. Filtering after the load would need a second pass and a second copy. Filtering in each reader multiplies
the sites, and a missed site would silently read pairs beyond the cut, which is the failure the contract's "cut
applied once, at load" prevents.

**D6. The cut comes from the plan in the public `load`.**
- The public `load(path, pkg, main, sink)` already reads `MOP_ACTIVITY_SOURCE` from the plan. It also computes
  `cut = spec.has(MOP) && spec.mop().scoring() == DISTANCE ? spec.mop().weights().length : NO_CUT`, where
  `NO_CUT = Integer.MAX_VALUE`.
- The package-visible test seam gains an `int cut` parameter.
- Under `flag`, and in a STATIC-only cell, nothing is cut (Pedro, 2026-11-03; spec "No Cut Outside the Distance
  Mode"). `flag` reads no widget or handler distance, so a cut there would only change the load record.

**D7. `MOP_DATA.cutPairs`, separate from `droppedPairs`** (Pedro, 2026-11-02).
- `EventSink.mopData` gains an `int cutPairs` parameter after `droppedPairs`.
- `NdjsonSink` writes it on every `loaded` record, as it writes `droppedPairs` (`NdjsonSink.java:631`).
- `NoopSink` and the test sinks follow the signature change.
- The record "gains fields across changes and never loses one", so adding a field matches its contract. Its value is 0
  at the default with E7 artifacts.

**D8. Neutral value `0`.** `0` is a well-typed one-level ladder of weight 0, the analogue of D1..D3's neutral `0`.
`Feature.MOP.own("ape.mopWeights", "0")`. `sameValue` compares parsed lists, so only the exact one-element `0` is
neutral: without MOP, `0,0,0` aborts with `missing_dependency`, where `D1=D2=D3=0` was accepted. This is documented,
not changed (Pedro, 2026-11-03, review WR-02): a cell without MOP has no reason to state the key.

**D9. Retired keys.** `RETIRED.put("ape.mopWeightD1"|"D2"|"D3", "replaced by ape.mopWeights, the distance ladder:
element ℓ weighs level ℓ and the list length is the cut (default 500,400,300).")`. `RunSpecAbortTest`'s
retired-key sweep picks them up.

**D10. The differential test freezes `bc4bc1b8` verbatim.** `MopLadderE7EquivalenceTest`
(`src/test/java/com/android/commands/monkey/ape/utils/`) holds private static copies of the `bc4bc1b8` bodies of
`distanceWeight`, `splitTies` and `nextLevelWeight`, written against the literals 500/400/300 and the loop bound 3.
It compares them with the new functions under the default ladder over these inputs:

| Function | Inputs |
|---|---|
| `distanceWeight` | every `d` in `0..10` and `Integer.MAX_VALUE` |
| `splitTies` | for every best distance `d` in `0..10` and every tie size `n` in `1..20`, the arrays of `n` actions at `d`, alone and together with one action at each worse distance up to 10 and one with no pair |
| `nextLevelWeight` | every weight in {0, 300, 400, 500, 1} |

The test counts the inputs and asserts 0 mismatches. It prints the counts as one `[E7-EQUIV]` line, and the delivery
note quotes that line.

`MopScorer.nextLevelWeight` becomes package-private, its body per D4, so that the differential test calls it directly
(Pedro, 2026-11-03).

**D11. Coin second-key tests go through the real function.**
- `MopCoinStage.targetCount` becomes package-private, with its logic unchanged, so `MopCoinStageTest` (same package)
  pins it directly. One ranking case per scenario also runs through `select`.
- The "window never passes the cut" case loads a widget list through the loader with c = 3, so the test covers the cut
  and the window together.

**D12. Build and delivery.**
- The jar is built in the worktree with `nice -n 19 ionice -c3 mvn clean package`, the repo's normal build
  (`CLAUDE.md`) without `install`. The handoff asks for the jar built the same way as the E7 jar.
- After the suite passes and the commit is made, the sha256 of `target/ape-rv.jar` is recorded, and the jar is not
  rebuilt afterwards.
- The reproducibility check builds `bc4bc1b8` the same way in a throwaway worktree and compares its sha256 with E7's
  `28f218be…` (`campaign.env:32`). This is a report only.
- A mismatch is expected. The build stamp embeds `git.build.time` (`pom.xml:22-26`, `BuildInfo.JAR_BUILT`), so two
  builds of one commit differ in bytes. The note will say so, and will record whether the classes other than
  `BuildInfo` are identical.

## API Design

### `ValueType.INT_LIST.parse(String key, String raw) -> List<Integer>`
- Pre: `raw` non-null.
- Post: an unmodifiable list of 1..10 elements, each `>= 0`.
- Error: `RunSpecException(INVALID_TYPE)` naming `key`, raised for a blank value, an empty element, a non-`int`
  element, a negative element or more than 10 elements.

### `RunSpec.MopParams.weights() -> int[]`
- Post: a new array equal to the effective ladder. Its length is the cut c, in 1..10.

### `MopContext.levelWeight(int level) -> int` / `MopContext.cut() -> int`
- `levelWeight`: requires `level >= 1`. Returns `weights[level-1]` when `level <= cut()`, and 0 otherwise.

### `MopScorer.distanceWeight(MopContext mop, int d) -> int`
- `levelWeight(max(d, 1))`. Total over `int`.

### `StaticArtifactLoader.PairReader.read(JSONArray list, boolean applyCut) -> int[][]`
- Post: the well-formed pairs in wire order, without those with `d > cut` when `applyCut` is set.
- Each element is counted at most once: in `dropped` (malformed) or in `cut` (cut).

### `EventSink.mopData(…, int droppedPairs, int cutPairs)`
- The existing parameters are unchanged, and `cutPairs` is added last.

## Data Flow

1. `RunSpec.resolve` parses `ape.mopWeights`, or uses the default `500,400,300`, into the plan. A malformed value
   aborts before step 1.
2. `StatefulAgent` (`agent/StatefulAgent.java:217`) calls `StaticArtifactLoader.load`. Under `MOP ∧ distance` this
   reads c = `weights().length`, cuts widget and handler pairs at c and emits `MOP_DATA` with `cutPairs`.
3. `MopContext.fromSpec` copies the ladder.
4. Each step, `MopWidgetPass` → `MopScorer.weigh` → `distanceWeight` / `splitTies` weigh actions. `MopCoinStage`
   ranks them by `mopWeight`, then `targetCount` (window `d + 1`). `MopRetirement` counts at `d*`. All of them read
   only the remaining pairs.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `invalid_type key=ape.mopWeights` | malformed ladder in `ape.properties` | `[APE-RUNSPEC-ABORT]`, nonzero exit before step 1 | fix the value (the calibration's harness renders it) |
| `retired_key key=ape.mopWeightD{1,2,3}` | old key in a properties file (e.g. rv-android's own `tool.py`) | abort before step 1, reason names `ape.mopWeights` | use `ape.mopWeights` |
| `missing_dependency key=ape.mopWeights` | non-neutral ladder without `MOP` | abort (INV-RUN-05) | add `ape.mop=true` or drop the key |
| malformed pair | artifact | dropped, `droppedPairs`++ (unchanged) | regenerate the artifact |
| pair beyond the cut | artifact deeper than c | dropped, `cutPairs`++ (not an error) | none needed |

## Risks / Trade-offs

- **[rv-android's own `tool.py` still writes `ape.mopWeightD1..D3`]** → this jar aborts such a task with
  `retired_key`. The calibration uses its own harness copy, which writes `ape.mopWeights` (D35, D36). The jar is never
  copied into rv-android (D34). A merge into `master` waits on Pedro, and on rv-android being aligned.
- **[Jar bytes not reproducible across builds]** → `BuildInfo.JAR_BUILT` changes per build. The delivery pins one file
  by sha256, the calibration checks it at copy time and in every container, and the note records the build command.
- **[Plan digests change for every MOP cell]** → this is expected (the key set changed) and confined to `digest`, which
  the fixture diff shows. The harness runtime does not gate on the plan digest. Only rv-android's offline
  `tests/migration/test_cells.py` reads these fixtures, from an `ape` checkout, and that is out of scope.
- **[Cut silently hides targets beyond c]** → this is intended (D17), and it is visible in `MOP_DATA.cutPairs`. The
  calibration's harness derives at `max(3, c)` and refuses a document shallower than c (its requirements).
- **[Flag mode not cut]** → Pedro's decision (2026-11-03, D6). It has no behavioural effect, only on `cutPairs`.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `INT_LIST` parse, canonical, sameValue | `ValueTypeIntListTest` (new) | ~10 |
| Unit | Key resolution, inert, neutral, retired, echo, digest | `RunSpecResolveTest`, `RunSpecAbortTest`, `RunSpecEchoTest`, `FeatureDerivationTest`, `KeyOwnershipTotalityTest` (updated) | ~10 changed/added |
| Unit | Level rule and tie split under several ladders | `MopScorerDistanceTest` (added cases), `MopContextTest` | ~8 |
| Differential | Default ladder ≡ frozen `bc4bc1b8` functions | `MopLadderE7EquivalenceTest` (new) | 3 tests, several thousand inputs |
| Unit/loader | Cut, counters, merges, flag no-cut, activity untouched, E7 fixture unchanged | `MopDataCutTest` (new), `MopDataTest` | ~7 |
| Unit | Coin second key: boundary, retired, cut | `MopCoinStageTest` (added) | ~4 |
| Oracle | Cell fixtures (digest only), goldens unchanged | `CellRunStartFixtureTest` (regenerate once, then compare), golden tests | existing |
| Suite | Everything | `nice -n 19 ionice -c3 mvn clean test` | full (bc4bc1b8 baseline 1495/0/0/28) |

## Open Questions

- **Merge into `master`.** Not part of this change's delivery. It would need rv-android's `tool.py` mapping updated
  first, or `master` would refuse its `mop_weight_d*` settings. Pedro decides.
