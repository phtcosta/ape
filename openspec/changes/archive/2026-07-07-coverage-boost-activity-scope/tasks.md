# Tasks: coverage-boost-activity-scope

## 1. Tracker

- [x] 1.1 Add cumulative `Map<String, Set<String>> activityInteracted` to `UICoverageTracker`; in `recordInteraction`, add `widgetId` to `activityInteracted[state.getActivity()]` (skip null activity). The update MUST be placed BEFORE the unregistered-state early return at `UICoverageTracker.java:155` (or performed in both branches) so first-touch interactions on unregistered states are captured — the per-state increment exists in both branches, so "mirror the per-state increment" alone is ambiguous
- [x] 1.2 Add `boolean hasActivityInteraction(activity, widgetId)`: `Set` membership test (no rollup read, no `stateData` scan), O(1), null-safe (null activity → false)

## 2. Boost pass

- [x] 2.1 Switch the novelty test in `StatefulAgent.adjustActionsByGUITree()` coverage pass from `getInteractionCount(newState, widgetId) == 0` to `!hasActivityInteraction(newState.getActivity(), widgetId)`; keep the decay formula and the `coverageBoostWeight==0` guard untouched; update the pass comment to the current contract (P4)

## 3. Tests

- [x] 3.1 `activityInteractionCoversFragments` (in `UICoverageTrackerTest`): interact X in fragment S1 → `hasActivityInteraction(activity, X) == true` while `getInteractionCount(S2, X) == 0` (INV-COV-09)
- [x] 3.2 `activityInteractionSurvivesEviction`: evict S1 (fold) → `hasActivityInteraction(activity, X)` still true
- [x] 3.3 `siblingFragmentInteractedButLocallyZero` (in `UICoverageTrackerTest`): register fragments S1/S2 of the same activity, `recordInteraction(S1, w)` → assert `hasActivityInteraction(activity, w) == true` while `getInteractionCount(S2, w) == 0` — this is exactly the differential the one-line call-site change consumes. No StatefulAgent JVM seam exists (`new StatefulAgent` appears nowhere in `src/test`), so the call-site itself is device-verified in 4.3, not unit-tested
- [x] 3.4 Existing decay/disabled scenarios stay green (`mvn test` nos testes de boost existentes)

## 4. Verification

- [x] 4.1 Full suite: `mvn test` (0 failures/errors)
- [x] 4.2 `openspec validate coverage-boost-activity-scope --strict`
- [x] 4.3 Device smoke (rebuilt jar): one standalone run — `Coverage boost` lines still present; boosted counts per state drop on refined screens (spot-check vs a cmpft trace of the same APK)
