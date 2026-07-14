# Design: coverage-boost-activity-scope

## Context

The boost pass (src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1469-1491) computes per-action novelty via `_coverageTracker.getInteractionCount(newState, widgetId)` — per abstract state. `UICoverageTracker` (src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java) records interactions per state (`stateData`) and folds evicted states into `activityRollup`; the only activity-level read is `getActivityCoverageGap` (lines 264-291), which scans all of `stateData` per call — too costly for a per-action hot path. cmpft evidence for the defect: 74% of steps re-hit tested pairs; rate × state-count show a measured negative correlation (≈−0.4 to −0.7 depending on formula); 21–50-pair screens at 19% rate.

## Architecture

```
UICoverageTracker
├── [NEW] Map<String, Set<String>> activityInteracted   // cumulative: activity → widgetIds interacted ≥ once
│         ├── recordInteraction(state, action): add widgetId to activityInteracted[state.getActivity()]
│         │     — placed BEFORE the unregistered-state early return (or in both branches)
│         └── hasActivityInteraction(activity, widgetId): membership test — O(1), no rollup read, no stateData scan
└── foldIntoRollup: unchanged (rollup still feeds reporting; activityInteracted is a superset of it)

StatefulAgent.adjustActionsByGUITree (boost pass)
└── getInteractionCount(newState, widgetId) == 0  →  !hasActivityInteraction(newState.getActivity(), widgetId)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `UICoverageTracker.activityInteracted` | Cumulative activity-scoped interacted-widget sets | recordInteraction | map state |
| `UICoverageTracker.hasActivityInteraction` | O(1) activity-scoped novelty read | activity, widgetId | boolean |
| boost pass call-site | Use activity-scoped membership | newState | per-action boost |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-COV-09 (activity membership ⊇ fragment) | `activityInteracted` add mirrors recordInteraction, before the early return | `UICoverageTrackerTest.activityInteractionCoversFragments` (new) |
| survives eviction + refinement | cumulative set is independent of stateData eviction and never evicts | `UICoverageTrackerTest.activityInteractionSurvivesEviction` (new) |
| sibling-fragment boost = 0 | call-site switch in boost pass; verified at the tracker via the differential the call site consumes | `UICoverageTrackerTest.siblingFragmentInteractedButLocallyZero` (new); call-site itself device-gated (no StatefulAgent JVM seam) |
| decay unchanged | formula untouched | existing decay scenarios stay green |

## Goals / Non-Goals

**Goals:**
- Boost differential points only at widgets untouched anywhere in the current Activity.
- O(1) lookup on the per-action hot path.
- Decay behavior and all disabled/zero-weight paths byte-identical.

**Non-Goals:**
- Changing the decay formula or its state-scoped denominator (documented simplenotes evidence; explicitly kept).
- Changing per-state tracking, the dump, `getCoverageGap`, or `getActivityCoverageGap` consumers.
- MENU/scroll action-mix tuning (separate, future).

## Decisions

1. **Cumulative `activityInteracted` set instead of scanning `stateData` per action** — the boost pass runs per action per step; `getActivityCoverageGap`-style scans are O(states×widgets) per call. The set is updated on the same single write path (`recordInteraction`), so consistency is by construction.
2. **Membership set, not a count map merged with the rollup** — the boost consumes only zero-vs-nonzero (StatefulAgent.java:1481), so the exact primitive is "has this widget ever been interacted in this Activity?", a `Set` membership test. Because the set is updated on every `recordInteraction` (before the unregistered-state early return), it is a superset of whatever `foldIntoRollup` later folds into `activityRollup`; the read therefore needs no rollup consultation and stays correct even for activities whose every fragment was evicted. This also dissolves the sum-vs-max semantics muddle a count map would inherit from `mergeMax` rollup folding.
3. **Decay stays state-scoped.** Fragmentation resets decay upward (fresh state → full weight), which mildly favors large refined screens — the direction the UICOV data says needs help. Making decay activity-scoped would strengthen decay and re-penalize exactly those screens.
4. **Key remains `UICoverageTracker.widgetId(action)`** (XPath|TYPE, INV-COV-06) — same identity as registration/recording, so counts line up 1:1.
5. **Neutralizes the latent post-eviction re-arm.** When a state is LRU-evicted and later revisited, `registerScreenElements` resets its per-state counts to 0, so the per-state novelty test would re-fire the boost for widgets already exercised before eviction. The cumulative set never evicts, so it keeps the boost suppressed across the evict → re-register → revisit cycle — a re-arm the per-state test could not close.

**Note:** the priority log's `gap` value remains state-scoped after this change (it is derived from per-state coverage, not the activity set); renaming the log key to reflect that is optional and out of scope here.

## API Design

### `hasActivityInteraction(activity: String, widgetId: String) -> boolean`
- Pre: none (null activity → false). Post: `true` whenever `getInteractionCount(s, widgetId) > 0` for any state `s` of the activity (INV-COV-09); monotonic within a run (never resets, never evicted). No side effects.

## Data Flow

`recordInteraction` writes: stateData (existing) + `activityInteracted` (new, before the early return). Boost pass reads `activityInteracted` membership only. Reporting paths unchanged.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| null activity on state | `state.getActivity()` | skip `activityInteracted` update; lookup returns false | degrades to per-state behavior for that state |

## Risks / Trade-offs

- [Cross-screen chrome (tab bars) in one Activity loses boost after first use anywhere] → intended: those are the low-value revisits; per-state registration still lists them, and roulette can still pick them at base priority.
- [Memory: one more map] → grows with the distinct (activity, widgetId) pairs interacted over the run and is never evicted (unlike LRU-bounded `stateData`, which sheds). Small in practice: bounded by the app's activity × widget universe.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | INV-COV-09; eviction survival; sibling-fragment differential (interacted in S1, count 0 in S2); decay scenarios unchanged | tracker fixture with 2 fragments of one activity in `UICoverageTrackerTest`; no StatefulAgent JVM seam exists, so the call-site is device-gated | ~3 new + existing green |
| Device (E2E) | revisit share and `UICOV-ACT` interacted rate improve vs cmpft | next cmpft-protocol validation run | corpus |

## Open Questions

None.
