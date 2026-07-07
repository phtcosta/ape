# Proposal: uicov-activity-rollup

## Why

The cmpft UICOV analysis (10,959 per-state dump lines, 657 runs) measured a global widget-action interaction rate of 38.3% — but the per-state dump is known to overstate the true per-screen gap: NamingFactory refinement splits one Activity into many abstract states (a measured negative correlation, ≈−0.4 to −0.7 depending on the unit/formula, between per-run abstract-state count and interaction rate in the cmpft traces), and the same physical widget is counted as "discovered, untested" once per fragment. The tracker already maintains the honest per-Activity aggregation (`activityRollup` + `getActivityCoverageGap`, base spec "Per-Activity coverage aggregation for reporting" at `openspec/specs/ui-coverage/spec.md:90-92`: union of fragments with per-widget max) — but nothing emits it, so the research-relevant number ("how many items of this screen were never touched?") cannot be read from any trace.

## What Changes

- The teardown dump additionally emits one `[APE-RV] UICOV-ACT` line per Activity, aggregating all its fragments (live `stateData` entries plus the eviction rollup) with per-widget `mergeMax` — the same aggregation `getActivityCoverageGap` already defines: `[APE-RV] UICOV-ACT activity=<name> discovered=<W> interacted=<D> gap=<1-D/W> byType=... liveStates=<liveFragmentCount>`, where `liveStates` measures live (non-evicted) fragmentation only — the number of live fragments grouped into the line; Activities present only in the eviction rollup report `liveStates=0` (the historical fold count is not tracked, by design).
- Read-only, teardown-only, additive: the per-state `UICOV` lines are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ui-coverage`: the coverage dump gains a per-Activity aggregated line (new requirement; realizes the reporting side of "Per-Activity coverage aggregation for reporting" in the trace).

## Impact

- **Components**: `UICoverageTracker` (aggregation + format helper, mirroring the per-state dump), `SataAgent.tearDown()` call site.
- **Experiments**: enables the true per-screen coverage metric in every future run's trace; no behavior change (observability only, arm-neutral).
- **Risk**: none beyond a few extra trace lines per run (one per visited Activity).
- **Archive ordering**: this change ADDs a requirement whose read-only parity cites INV-COV-07 and which builds alongside the `UICoverageTracker — Coverage Dump` requirement — both defined only in the unarchived `exploration-observability` delta (the main `ui-coverage` spec stops at INV-COV-06 with no dump requirement). This change MUST therefore be archived AFTER `exploration-observability`.
