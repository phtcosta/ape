# Tasks: uicov-activity-rollup

## 1. Core

- [x] 1.1 Add `dumpActivities()` to `UICoverageTracker`: single-pass grouping of `stateData` by activity, seeded with `activityRollup` entries, merged via `mergeMax`; emit lines in lexicographic activity-name order (sort grouped keys); call it at the end of `dump(...)`
- [x] 1.2 Add pure package-visible `formatActivityLine(activity, merged, liveFragments)` emitting `[APE-RV] UICOV-ACT activity=<a> discovered=<W> interacted=<D> gap=<g> byType=<...> liveStates=<n>` (reuse `typeSegment`/`formatByType`, `Locale.ROOT`)

## 2. Tests (new cases in `UICoverageTrackerTest`)

- [x] 2.1 `activityLineCollapsesFragments`: 3 fragments of MainActivity with the same 10 widget keys, X interacted in one fragment → one line, `discovered=10`, X interacted (INV-COV-08)
- [x] 2.2 `rollupIncludedInActivityLine`: fold a fragment into the rollup (evict), keep one live → aggregated line covers both, `liveStates=1`
- [x] 2.3 `rollupOnlyActivityLine`: fold all fragments of an activity into the rollup (evict, none live) → one `UICOV-ACT` line sourced purely from the rollup, `liveStates=0`
- [x] 2.4 Dump remains read-only: counts/maps unchanged across `dump` (INV-COV-07 parity)
- [x] 2.5 `activityLinesDeterministicOrder`: multiple activities → `UICOV-ACT` lines emitted in lexicographic activity-name order
- [x] 2.6 (MUST) Update `testDump_oneLinePerTrackedState` (`UICoverageTrackerTest`): change its count predicate from `contains("[APE-RV] UICOV")` to the exact per-state token `contains("[APE-RV] UICOV state=")`, and add a separate assertion counting `[APE-RV] UICOV-ACT` lines (one per activity with live or rollup data) — without this the suite goes red on implementation
- [x] 2.7 Run `mvn test -Dtest=UICoverageTrackerTest`

## 3. Verification

- [x] 3.1 Full suite: `mvn test` (0 failures/errors)
- [x] 3.2 `openspec validate uicov-activity-rollup --strict`
- [x] 3.3 Device smoke (rebuilt jar): one standalone run — trace contains `UICOV-ACT` lines alongside unchanged per-state `UICOV` lines
