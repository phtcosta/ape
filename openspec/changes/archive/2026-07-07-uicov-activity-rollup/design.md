# Design: uicov-activity-rollup

## Context

`UICoverageTracker` (src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java) already computes the per-Activity aggregation for steering-side queries: `getActivityCoverageGap` (lines 264-291) merges `activityRollup` with live sibling states via `mergeMax`. The teardown dump (`dump`, lines 308-316, called from `SataAgent.tearDown()`, SataAgent.java:285-286) iterates only `stateData` and emits per-state lines. This change reuses the existing aggregation to emit per-Activity lines in the same dump pass.

## Architecture

```
SataAgent.tearDown()
└── _coverageTracker.dump(mopReachPredicate)      // existing per-state lines, unchanged
    └── [NEW] dumpActivities()                     // after the state loop
        ├── group stateData by state.getActivity() // same traversal, read-only
        ├── seed each group with activityRollup entries (mergeMax)
        └── one formatActivityLine(...) per activity → Logger
```

Aggregation helper is shared with `getActivityCoverageGap` (extract the merge into a private `aggregateActivity(activity)` used by both, or iterate once building all activities' maps — single pass over `stateData`, cheaper than per-activity scans).

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `UICoverageTracker.dumpActivities` | Aggregate fragments+rollup per activity; emit `UICOV-ACT` lines | stateData, activityRollup | trace lines |
| `formatActivityLine` (package-visible, pure) | Format one line (host-testable like `formatCoverageLine`) | activity, merged map, fragment count | String |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| fragments collapse (union + mergeMax) | single-pass grouping with `mergeMax` | `UICoverageTrackerTest.activityLineCollapsesFragments` (new) |
| evicted fragments counted | seed groups from `activityRollup` | `UICoverageTrackerTest.rollupIncludedInActivityLine` (new) |
| INV-COV-08 | property of mergeMax union | assertions inside the two tests above |
| read-only (INV-COV-07 parity) | no get/put on access-ordered map (entrySet iteration only) | `dumpIsReadOnly` (extend existing if present) |
| per-state lines unchanged | `dumpActivities` appended after existing loop | existing dump tests stay green |

## Goals / Non-Goals

**Goals:**
- True per-screen (Activity) coverage readable from every trace at teardown.
- Zero behavior change to steering, registration, recording, eviction.

**Non-Goals:**
- Changing the per-state dump, the boost pass, or any steering logic (that is `coverage-boost-activity-scope`).
- Periodic (mid-run) activity dumps.
- `mopReach` on the activity line (derivable from the per-state lines; keep the line lean).

## Decisions

1. **Single-pass grouping in the dump instead of calling `getActivityCoverageGap` per activity** — the per-activity query scans all of `stateData` each call (O(A×S)); the dump builds all groups in one traversal (O(S)). The merge semantics are identical (`mergeMax`).
2. **`liveStates=<liveFragmentCount>`** on the line: makes the live (non-evicted) fragmentation factor — the number of live fragments grouped into the line — measurable per screen directly, which the cmpft analysis had to infer statistically. Rollup-only Activities report `liveStates=0` (the historical fold count is not tracked). It measures live fragmentation only.
3. **Pure package-visible formatter**, mirroring `formatCoverageLine` — keeps the format host-testable without an Android runtime (established pattern).

## API Design

### `dumpActivities() -> void` (private, called from `dump`)
- Pre: none. Post: one `UICOV-ACT` line per activity with ≥1 fragment or rollup entry, emitted in lexicographic activity-name order (sort the grouped activity keys before emitting) for deterministic output; no state mutation.

### `formatActivityLine(activity: String, merged: Map<String,Integer>, liveFragments: int) -> String`
- Pure; `Locale.ROOT`; byType via the existing `typeSegment`/`formatByType` helpers.

## Data Flow

`stateData` + `activityRollup` → grouped merge (mergeMax) → formatted lines → `Logger` (stdout trace). No writes.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| null activity on a state | `state.getActivity()` | skip the state in grouping (same as `foldIntoRollup`) | n/a |

## Risks / Trade-offs

- [Extra trace volume] → one line per visited Activity (typically < 20 per run); negligible next to the per-state lines.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | fragment collapse, rollup inclusion, read-only, format, deterministic order | new `UICoverageTrackerTest` cases against the pure formatter + tracker fixture | ~4 new |
| Device (E2E) | `UICOV-ACT` lines present and parseable in traces | next cmpft-protocol validation run | corpus |

## Open Questions

None.
