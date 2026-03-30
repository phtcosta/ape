## Why

`RvsecAnalysisClient.getEntryPoints()` in rvsec-gator iterates only over `output.getActivities()`, ignoring Services and BroadcastReceivers as entry points. Methods reachable only through these non-GUI components are excluded from the `reachableSet` and MOP reachability analysis. This was identified as a prerequisite for gh9 (exploration refactor) but was never implemented, leaving MOP-guided scoring incomplete for apps that use Services and BroadcastReceivers.

Beyond the static analysis gap, APE-RV has no mechanism to exercise non-GUI components at runtime. Services and BroadcastReceivers frequently execute security-relevant code (crypto, TLS) invisible to UI-only exploration. This change adds both the static analysis enrichment AND runtime triggering of these components via `am broadcast` / `am startservice`.

Refs #11. Prior analysis: `rv-android/docs/analise_broadcast_service_integration.md` (2026-03-18).

## What Changes

- Add `getReceivers()` accessor to GATOR's `XMLParser` interface (field already exists in `DefaultXMLParser`, getter is missing)
- Extend `RvsecAnalysisClient.getEntryPoints()` to iterate over Services and Receivers, adding their lifecycle methods as entry points
- Extend `RvsecAnalysisClient.complementWithCallbacks()` to propagate MOP flags for Service/Receiver lifecycle methods
- Add rich `components{}` section to the static analysis JSON with intent-filters, exported status, and MOP reachability per component
- Add `isService`/`isReceiver` flags to `reachability[]` entries
- Extend APE-RV's `MopData` parser to consume the new `components{}` section
- Add `EVENT_BROADCAST` and `EVENT_START_SERVICE` action types to APE-RV
- Add `AndroidDevice.startService()` method (broadcastIntent already exists)
- Integrate broadcast/service triggering as stagnation escape hatch in SataAgent
- Add Config flags: `ape.testBroadcasts`, `ape.testServices`

## Capabilities

### New Capabilities

- `static-analysis-entrypoints`: Entry point enrichment in rvsec-gator to include Service and BroadcastReceiver lifecycle methods in the reachability analysis and JSON output, with intent-filters and exported status
- `component-triggering`: Runtime triggering of Services and BroadcastReceivers from APE-RV during exploration, using intent data from the enriched static analysis JSON

### Modified Capabilities

- `mop-guidance`: `MopData` parser handles the new `components{}` JSON section; `MopScorer` is NOT modified — component MOP data feeds the stagnation escape hatch, not the priority scorer
- `action-selection`: SataAgent gains broadcast/service triggering as escape hatch when exploration stagnates, tried before restart

## Impact

- **rvsec-gator** (`XMLParser`, `RvsecAnalysisClient`): Interface change + entry point enumeration + new `components{}` JSON section with intent-filters
- **APE-RV** (`MopData`, `SataAgent`, `AndroidDevice`, `Config`): Parse components, new action types, stagnation escape hatch, new Config flags
- **rv-android pipeline**: Re-running static analysis produces enriched JSON. Old JSON files remain valid (backward compatible — new sections are additive). New aperv-tool property mapping needed.
- **Experiment reproducibility**: `testBroadcasts=false` (default) preserves existing behavior. Opt-in via `ape.properties`.
