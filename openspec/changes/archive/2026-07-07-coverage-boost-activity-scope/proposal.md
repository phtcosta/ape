# Proposal: coverage-boost-activity-scope

## Why

The coverage boost steers toward untested widgets, but its novelty test is per abstract state: `getInteractionCount(newState, widgetId)`. When NamingFactory refinement splits one screen into several abstract states, a widget already exercised in one fragment counts as untested in every sibling fragment, so the +100 boost re-fires on already-tested widgets — wasting the differential that is supposed to point at genuinely untouched items. The cmpft UICOV corpus quantifies the damage: ~74% of all steps re-hit already-tested widget-action pairs; the per-run interaction rate falls with the number of abstract states (a measured negative correlation, ≈−0.4 to −0.7 depending on formula); overall only 38.3% of discovered widget-action pairs are ever exercised, dropping to 19% on screens with 21–50 pairs.

## What Changes

- The boost pass's novelty test becomes activity-scoped: a widget counts as tested if it has been interacted with in ANY fragment of the current Activity, not just the current abstract state. The boost consumes only zero-vs-nonzero (it fires iff the count is 0), so membership is the exact primitive: `UICoverageTracker` gains a cumulative `Map<String, Set<String>> activityInteracted` (activity → widgetIds interacted at least once), updated on EVERY `recordInteraction` (including the unregistered-state early-return branch), and an O(1) `boolean hasActivityInteraction(activity, widgetId)`. The set is never consulted against the rollup — it is a superset of anything later folded into `activityRollup`, so no rollup read is needed on the boost path, and it never evicts, so refinement and state eviction cannot re-arm the boost.
- The decay formula (`weight / (1 + stateVisits/5)`) is unchanged — its documented purpose (escaping widget-heavy screens, simplenotes −12.56pp evidence) is orthogonal and stays intact.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ui-coverage`: "Coverage Boost in Action Scoring — Per-Action" novelty test becomes activity-scoped (MODIFIED); tracker gains the activity-scoped interaction lookup (ADDED requirement, new INV-COV-09).

## Impact

- **Components**: `UICoverageTracker` (cumulative activity interaction set + membership lookup), `StatefulAgent.adjustActionsByGUITree()` boost pass (one call-site change).
- **Experiments**: arm-neutral (all arms run the same jar); expected to redirect the boost differential toward genuinely untouched widgets and reduce the 74% revisit share. Measurable via the `UICOV-ACT` lines (`uicov-activity-rollup`) in the next validation run.
- **Risk**: widgets shared across genuinely different screens of the same Activity (e.g., a tab bar) lose the boost after first use anywhere in the Activity — acceptable: such widgets are navigation chrome, precisely the low-value revisits the change targets.
