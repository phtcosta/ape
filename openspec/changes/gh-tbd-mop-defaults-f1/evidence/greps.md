# Leftover copies of an old default (task 6.2)

Run 2026-11-11 after groups 1–5 and 6.1, worktree root. Pattern (extended regex), covering the four old defaults
and the wordings task 6.2 names:

```
500, ?400, ?300|default (`)?1\b|\(default (`)?50|default (`)?3\b|first.interaction.{0,40}default|
default.{0,40}first.interaction|50 by default|cadence (of |is )?50|default (cadence|of) (`)?50|
mopRetireAfter.{0,12}\((`)?3|default k|k = 1|default ladder|StagnationStep.{0,40}\b50\b|\b50\b.{0,30}cadence
```

Verdicts: **fixed** (edited in this change), **kept** (states E7 explicitly, or is another key, or stays true at
F1), **archive** (a delta requirement block replaces it at `openspec archive`), **hand-sync** (outside every
requirement block; design D10, task 7.5).

## `src/main/java`

| Hit | Verdict |
|---|---|
| `runtime/KeyOwnership.java:213` "equivalent to the E7 values (500,400,300, 3, 1, 50)" | kept: names E7 explicitly |
| `KeyOwnership.java` defaults, retired reasons; `Feature.java` neutrals; `RunSpec.clamp`; `MopScorer` javadoc | fixed in group 3 (no hit remains) |
| other hits (`command.get(3)`, `new ArrayList<>(3)`, `nextInt(3)`, `m.group(3)`) | kept: not a default |

## `src/test/java` (comments and javadoc)

| Hit | Verdict |
|---|---|
| `agent/StatefulAgentMopTelemetryTest.java:76`, `utils/MopLadderE7EquivalenceTest.java:17,96`, `utils/MopScorerDistanceTest.java:29,136`, `utils/TestMopContexts.java:38`, `runtime/RunSpecEchoTest.java:478` | kept: state the E7 ladder explicitly |
| `utils/MopScorerDistanceTest.java:33,418,434,455`, `utils/MopLadderE7EquivalenceTest.java:24` | kept: name the F1 default ladder |
| `utils/MopDataCutTest.java:293` "E7 artifact at the default ladder" | kept: scenario name; F1 has 3 levels, so the E7 artifact still loses no pair |
| `agent/ActivityFrontierTest.java:142` "a cadence of 50" | kept: a literal of the test, no longer called the default (fixed in 5.4) |
| `runtime/FeatureDerivationTest.java:294`, `runtime/RunSpecResolveTest.java:365,384,533` | kept: name no old value |
| `oracle/OracleScaffold.java:229` | kept: section header, no value |
| `oracle/*` seeds and guard, `agent/pipeline/PipelineFixture.java:132,244`, `utils/TestMopContexts.java` | fixed in group 5 (no old-default wording remains) |

## `CLAUDE.md`

| Hit | Verdict |
|---|---|
| `:178` E7 recipe (`500,400,300`, 3, 1, 50) | kept: E7 stated explicitly |
| `:323` "Calibrated MOP defaults" (E7 values named for the comparison) | kept |
| `:322` `p_M` "(default 1.0)"; `:324` LLM gates "(3)" | kept: other keys |
| `:190`, `:192`, `:300`, `:301`, `:312`, `:313` (old line numbers) | fixed in group 4 |

## `openspec/specs` (main specs)

| Hit | Verdict |
|---|---|
| `run-spec:153,191,310,530,534,569,584` | archive: requirements "Feature Model with Declared Dependencies", "Total Fail-Fast Validation", "Distance Ladder Key", "Integer-List Rendering" (MODIFIED) and "Default Plan Equivalence With the E7 Jar" (REMOVED) |
| `mop-guidance:221,259,284,288,292,864,932,937,940,1006,1017,1027` | archive: "MopScorer — Priority Boost", "Target Retirement", "MOP Coin Decider", "MopInteractionLedger — Executions per Activity and Key" (MODIFIED) |
| `component-triggering:264,296` | archive: "Cadence-Based MOP Activity Launch" (MODIFIED) |
| `exploration:550` | archive: "Configuration Loading" (MODIFIED, delta added at apply) |
| **`mop-guidance:11`** Purpose, "… fewer than `ape.mopCoinLedgerMaxInteractions` times (default 1) …" | **hand-sync** → "(default 4)" (D10) |
| **`mop-guidance:1173`** INV-MOP-38, "(default 1: no entry; design D-B17)" | **hand-sync** → "(default 4; at k = 1, no entry; design D-B17)" (D10) |
| `mop-guidance:1092,1103` | kept: ladder stated explicitly |
| `mop-guidance:1143` scenario "E7 artifact at the default ladder" ("Readers Unchanged on the Remaining Pairs") | kept: true at F1 (three levels, no pair cut, `cutPairs` 0) |
| `mop-guidance:1163` INV-MOP-24 "at the default ladder, a pair at `d ≤ 3`" | kept: true at F1 (every level of `194,146,97` is positive) |
| `event-sink:293,345` "`cutPairs` … 0 at the default ladder" | kept: true at F1 (c = 3) |
| `run-spec:231`, `action-selection:12,208` (`ape.backMenuPickCap` 3); `llm-infrastructure:356,373`, `llm-routing:13`, `exploration:744,770`, `llm-infrastructure:9` (LLM gates 3); `parity-oracle:48` (`ape.trivialActivityRankThreshold` 3) | kept: other keys |

Hand-sync list for task 7.5: `mop-guidance` Purpose (`:11`) and INV-MOP-38 (`:1173`). No other Purpose or
invariant quotes one of the four old defaults.
