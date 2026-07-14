## Purpose

Emit the per-Activity coverage aggregation in the trace. The base spec's "Per-Activity coverage aggregation for reporting" requirement defines how reported coverage collapses naming-level fragments (union of fragments' widgets with per-widget max interaction count — `getActivityCoverageGap`, `activityRollup`, INV-COV-05), but the teardown dump currently emits only per-state `UICOV` lines. Per-state lines systematically overstate the true per-screen gap under refinement: in the cmpft corpus, runs with more abstract states show proportionally lower per-state interaction rates (a measured negative correlation, ≈−0.4 to −0.7 depending on the unit/formula, between per-run abstract-state count and interaction rate) because the same physical widget re-registers as untested in every sibling fragment. The per-Activity line makes the honest number — distinct widget-actions of a screen never exercised anywhere in the run — readable from the trace, which is the metric the experiment analysis needs.

## ADDED Requirements

### Requirement: UICoverageTracker — Per-Activity Coverage Dump

The teardown dump SHALL additionally emit one `[APE-RV] UICOV-ACT` line per Activity that has at least one tracked fragment (live `stateData` entry or eviction-rollup entry). The `UICOV-ACT` lines SHALL be emitted in lexicographic activity-name order (the grouped activity keys sorted before emission) so the output is deterministic across runs. The aggregation SHALL be the one "Per-Activity coverage aggregation for reporting" defines: the union of the Activity's fragments' widget keys, with each widget's interaction count taken as the maximum across fragments and rollup (`mergeMax`). The line format SHALL be:

```
[APE-RV] UICOV-ACT activity=<activity> discovered=<W> interacted=<D> gap=<1-D/W> byType=<TYPE:d/w,...> liveStates=<liveFragmentCount>
```

where `discovered`/`interacted`/`gap`/`byType` follow the per-state `UICOV` line conventions (same key/type-segment parsing, `Locale.ROOT` gap formatting) computed over the aggregated map, and `liveStates` is the number of live (non-evicted) fragments of that Activity grouped into the line at teardown. Activities present only in the eviction rollup report `liveStates=0`; the historical fold count is not tracked (by design, `activityRollup` stores only per-widget maxima). The per-state `UICOV` lines SHALL remain unchanged and SHALL be emitted alongside.

The aggregated dump SHALL be read-only: no registration, recording, eviction, or mutation of `stateData`, `activityRollup`, or interaction counts (same constraint as the per-state dump, INV-COV-07).

- **INV-COV-08**: For every Activity line, `discovered` SHALL equal the size of the union of widget keys across that Activity's live fragments and its rollup entry, and `interacted` SHALL equal the number of keys in that union whose merged (`mergeMax`) count is greater than 0.

#### Scenario: fragments collapse into one activity line
- **WHEN** `MainActivity` fragmented into 3 states registering the same 10 widget keys, with widget X interacted only in fragment 2
- **THEN** exactly one `UICOV-ACT activity=MainActivity` line SHALL be emitted
- **AND** it SHALL report `discovered=10` (union, not 30) with X counted as interacted

#### Scenario: evicted fragments still counted
- **WHEN** a fragment of `SettingsActivity` was LRU-evicted mid-run (its counts folded into `activityRollup`) and one live fragment remains
- **THEN** the `UICOV-ACT` line for `SettingsActivity` SHALL aggregate the rollup and the live fragment

#### Scenario: per-state lines unchanged
- **WHEN** the teardown dump runs
- **THEN** every per-state `[APE-RV] UICOV` line SHALL be emitted exactly as specified by the coverage-dump requirement, in addition to the `UICOV-ACT` lines

#### Scenario: rollup-only activity reports liveStates=0
- **WHEN** every fragment of `LoginActivity` was LRU-evicted mid-run (all its counts folded into `activityRollup`) and no live fragment remains at teardown
- **THEN** exactly one `UICOV-ACT activity=LoginActivity` line SHALL be emitted, sourced purely from the rollup
- **AND** it SHALL report `liveStates=0`

#### Scenario: activity lines emitted in deterministic order
- **WHEN** the teardown dump aggregates multiple Activities
- **THEN** the `UICOV-ACT` lines SHALL be emitted in lexicographic activity-name order, identical across runs with the same tracked data
