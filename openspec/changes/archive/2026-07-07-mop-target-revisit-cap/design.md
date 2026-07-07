# Design: mop-target-revisit-cap

## Context

The deterministic MOP sites live in `src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java`: `selectUnvisitedMopTarget` (called from the epsilon-greedy chain, ~line 496) and `pickBestMopTarget` (called from `findGreedyActionForward` before the EARLY_STAGE roulette). Both restrict to valid/enabled/unvisited actions with `mopBoost > 0` (INV-SEL-MOP-01/03). "Unvisited" is per abstract state, so NamingFactory refinement re-arms the preference for the same physical widget in every near-duplicate state. cmpft evidence (dnsfilter, 3/3 reps): 111–130 MOP picks concentrated on ~2 widgets, 103–110-step single-activity streaks, −6.6pp cov_method, zero new violations vs the no-MOP baseline.

## Architecture

The static picker `pickBestMopTarget` stays pure. The cap runs at the two INSTANCE call sites — `selectUnvisitedMopTarget` (a `protected` instance method that reads the `newState` field, epsilon path) and `findGreedyActionForward` (EARLY_STAGE) — which own the counter and filter each candidate iterable through a static, JVM-testable seam before invoking the picker:

```
SataAgent (instance)
├── Map<String, Integer> mopTargetPicks             // owned here; key = target.toXPath()+"|"+actionType+"|"+activity
├── selectUnvisitedMopTarget (instance, epsilon path)  // filters candidates via the seam, then calls the static picker
└── findGreedyActionForward (instance, EARLY_STAGE)    // filters candidates via the seam, then calls the static pickBestMopTarget directly

SataAgent (static seam — pure, testable)
├── static String  mopPickKey(action, activity)        // target.toXPath()+"|"+actionType+"|"+activity; null if target or XPath null
└── static boolean eligibleForMopPick(picks, key, cap) // cap <= 0 → true; key == null → true; else picks.getOrDefault(key,0) < cap

Config.mopTargetPickCap                                 // "ape.mopTargetPickCap", default 3, <= 0 = unlimited
```

The instance sites increment `mopTargetPicks` when a candidate is selected; the deterministic pickers themselves are never modified.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.mopTargetPickCap` | Flag (default 3, <= 0 = unlimited) | `ape.properties` | int |
| `SataAgent.mopPickKey` (static) | Build the counter key (null if target/XPath null) | ModelAction, activity | String |
| `SataAgent.eligibleForMopPick` (static) | Cap check per candidate key | picks map, key, cap | boolean |
| instance call sites | Own `mopTargetPicks`; increment on pick; log at reach-the-cap | — | — |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-SEL-MOP-04 (cap enforced, cross-state) | `eligibleForMopPick` filter at both call sites (static pickers unchanged) | `SataAgentMopShortCircuitTest.capExcludesWidgetAcrossStates` (new) |
| INV-SEL-MOP-05 (cap <= 0 no-op) | `eligibleForMopPick` returns true and no counting when `Config.mopTargetPickCap <= 0` | `SataAgentMopShortCircuitTest.capZeroKeepsUncappedBehavior` (new) |
| independent counting per key | map keyed by XPath+actionType+activity | `SataAgentMopShortCircuitTest.distinctWidgetsCountedIndependently` (new) |
| capped log line once | logged at the pick that reaches the cap (capped keys filtered thereafter) | asserted in `capExcludesWidgetAcrossStates` |

## Goals / Non-Goals

**Goals:**
- Bound deterministic MOP re-picking per physical widget, across abstract states.
- Keep the boost's roulette participation intact; this bounds deterministic-override streaks, but the capped action keeps its `mopBoost` in the priority roulette and remains reachable via least-visited tie-breaking, so residual probabilistic concentration remains possible (strictly less than today's unbounded deterministic re-picks).
- Zero behavior change with the cap disabled.

**Non-Goals:**
- Suppressing or decaying `mopBoost` itself (roulette-level concentration was secondary; P1 keeps this minimal).
- Cross-run persistence of counts.
- Any change to `MopScorer`, boost values, or attribution (`decision_source`).

## Decisions

1. **Key = `target.toXPath() + "|" + actionType + "|" + activity`** — the same physical-widget identity convention as `UICoverageTracker.widgetId` (XPath + type), scoped by activity to avoid cross-screen collisions of generic paths. The action type is part of the key because MOP boosts are event-type-specific (`MopScorer.eventTypeOf` gates isDirectMop/isTransitiveMop on click/longClick/scroll): without it, three clicks would consume the allowance and exclude a distinct MOP-positive long-click on the same widget. A widget hammered via multiple types is still bounded (cap × few types). Available at both sites without touching MopScorer. Alternative rejected: static `idName` from MopData — requires plumbing the resolved static widget out of the scorer for no added discrimination.
2. **Count deterministic picks only** (not roulette selections that happen to carry mopBoost). The measured pathology is the deterministic streak; counting roulette picks would make the cap depend on chance and complicate INV-SEL-MOP-05.
3. **Cap default 3.** S2 trace evidence: violation-producing widgets fired within their first interactions in every observed case (vermont 5/5, faircode 2/2 within ≤4s of the pick); 3 deterministic shots per widget preserves that while killing 30×+ hammering. Flag allows 0 (unlimited) for A/B.
4. **Filter at the two instance call sites, not in the candidate collection and not in the pickers.** Keeps `getGreedyActions`/candidate plumbing untouched and the static pickers pure; the call sites already own the candidate iteration around the mopBoost eligibility test.

## API Design

### `static String mopPickKey(action: ModelAction, activity: String) -> String`
- Returns `target.toXPath() + "|" + actionType + "|" + activity`, or null when the action's target or its XPath is null. Pure.

### `static boolean eligibleForMopPick(picks: Map<String,Integer>, key: String, cap: int) -> boolean`
- Returns true when `cap <= 0` (unlimited), when `key == null` (uncountable, always pickable), or when `picks.getOrDefault(key, 0) < cap`. Pure read; no logging, no mutation.

### Instance call sites (`selectUnvisitedMopTarget`, `findGreedyActionForward`)
- Own the `mopTargetPicks` map. For each candidate, compute `key = mopPickKey(action, activity)` and skip it when `!eligibleForMopPick(mopTargetPicks, key, cap)`. After the static picker returns a selection, increment `mopTargetPicks` for its key (skip when key is null). When that increment makes the count equal the cap (`cap > 0` and new value `== cap`), log `[APE-RV] MOP target capped: activity=<a> widget=<xpath> picks=<n>` once — no separate "already logged" set is needed, because the key is filtered out of every subsequent pick.

## Data Flow

The instance call sites iterate candidates → skip `mopBoost<=0` (existing) → skip capped keys via `eligibleForMopPick` (new) → invoke the static picker → increment `mopTargetPicks` for the selected key (log if it reaches the cap) → return. The static pickers and the roulette paths are untouched.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| null target/XPath on a boosted action | malformed action | `mopPickKey` returns null → never counted and never filtered — it remains pickable by the deterministic override | Defensive only: production candidates with `mopBoost>0` always carry non-null targets (`mopBoost` is set only on resolved, target-requiring actions; MODEL_MENU uses `menuBoost`) |

## Risks / Trade-offs

- [A violation needing >3 interactions with the same widget is missed deterministically] → not observed in 657 traces; roulette retains the boost so later picks remain possible; flag restores unlimited.
- [XPath instability across rebuilds inflates keys] → worst case the cap under-triggers (more picks than 3 for one physical widget) — degrades toward current behavior, never below it.
- [Key coarseness collapses distinct widgets] → `TypeNamer` with an empty resource-id (AndroidX/Compose) and `EmptyNamer` (`//*`) yield non-unique XPaths, so distinct widgets can share one counter and be over-capped. The direction is conservative/benign: an over-capped widget simply returns to the baseline SATA distribution.
- [Key stability under refinement] → normally the hammered widget is the stable one the refinement splits *around* (the distinguishing widget is the one separating divergent trees — `NamingFactory.checkStateRefinement`), and the default actiontype base naming (`TypeNamer`) keys it by class + resource-id, so its key stays put. An adversarial per-widget namer upgrade is bounded by the namer-lattice height (worst case cap × a few levels of bounded degradation, not unbounded evasion).
- [EARLY_STAGE candidates are unsaturated, not strictly unvisited] → `StateActionDiffer.getUnsaturated` can include visited-but-unsaturated actions, so the inherited INV-SEL-MOP-01/03 "unvisited" wording is stronger than that site's actual code; the cross-state cap is the effective bound there.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | key includes action type; independence per activity; cap <= 0 no-op; single log at reach-the-cap; null-target key; capped action keeps mopBoost | extend `SataAgentMopShortCircuitTest`, driving the static seam `mopPickKey`/`eligibleForMopPick` (no JVM test can instantiate `SataAgent`) | ~6 new |
| Device (E2E) | dnsfilter streak gone (deterministic-override MOP picks ≤ ~cap×flagged per widget; no 100-step activity streak — `decision_source=MOP` also counts roulette-selected boosted actions, so the total is NOT bounded by cap×flagged) | next cmpft-protocol validation run | corpus |

## Open Questions

None.
