## Why

`RvsecAnalysisClient.getEntryPoints()` in rvsec-gator iterates only over `output.getActivities()`, ignoring Services and BroadcastReceivers as entry points. Methods reachable only through these non-GUI components are excluded from the `reachableSet` and MOP reachability analysis. This was identified as a prerequisite for gh9 (exploration refactor) but was never implemented, leaving MOP-guided scoring incomplete for apps that use Services and BroadcastReceivers. Refs #11.

## What Changes

- Add `getReceivers()` accessor to GATOR's `XMLParser` interface (field already exists in `DefaultXMLParser`, getter is missing)
- Extend `RvsecAnalysisClient.getEntryPoints()` to iterate over Services (`xml.getServices()`) and Receivers (`xml.getReceivers()`), adding their lifecycle methods (`onStartCommand`, `onBind`, `onReceive`, etc.) as entry points
- Extend the static analysis JSON output to include Services and BroadcastReceivers so that APE-RV's `MopData` can parse and use them for MOP-guided scoring
- Extend APE-RV's `MopData` parser to consume the new component data from the JSON

## Capabilities

### New Capabilities

- `static-analysis-entrypoints`: Entry point enrichment in rvsec-gator to include Service and BroadcastReceiver lifecycle methods in the reachability analysis and JSON output

### Modified Capabilities

- `mop-guidance`: `MopData` parser must handle the new JSON sections for Services/BroadcastReceivers, extending the reachability map beyond activity-bound widgets

## Impact

- **rvsec-gator** (`XMLParser`, `RvsecAnalysisClient`): Interface change + entry point enumeration logic. Affects the static analysis pipeline that produces the JSON consumed by APE-RV.
- **APE-RV** (`MopData`): Must parse new JSON sections. Existing activity-based scoring is unchanged; `MopScorer` is NOT modified in this change — component MOP data is parsed and available for future use.
- **rv-android pipeline**: Re-running static analysis on APKs will produce enriched JSON files. Existing JSON files remain valid (backward compatible — new sections are additive).
- **Experiment reproducibility**: Experiments using old JSON files will behave identically. New JSON files unlock broader MOP coverage.
