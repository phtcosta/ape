## Context

APE-RV's MOP-guided scoring relies on static analysis JSON produced by rvsec-gator (`RvsecAnalysisClient`). The JSON contains `reachability[]`, `windows[]`, and `transitions[]` sections. Currently, only Activity classes serve as entry points for the call graph traversal (`getEntryPoints()` at L252-266 of `RvsecAnalysisClient.java`). Services and BroadcastReceivers declared in AndroidManifest.xml are ignored, causing methods reachable only through these components to be absent from the reachability analysis.

This was documented as a Known Limitation in the gh9 design (L233-253) and marked as a prerequisite that was never executed. Refs proposal.md and #11.

### Current state in GATOR

- `XMLParser` interface defines `getServices()` (L154) returning `Iterator<String>`. No `getReceivers()` method exists.
- `AbstractXMLParser` (inner class of `XMLParser`) has fields: `services` (ArrayList\<String\>) and `receivers` (ArrayList\<String\>), both populated by `DefaultXMLParser`.
- `DefaultXMLParser.getServices()` (L169) returns `services.iterator()`. No equivalent for receivers.
- `GUIAnalysisOutput.getActivities()` returns `Set<SootClass>` from the flowgraph — a different type than `XMLParser.getActivities()` which returns `Iterator<String>`.
- `RvsecAnalysisClient.getEntryPoints()` uses `output.getActivities()` (SootClass) and `output.getLifecycleHandlers(activity)`.
- For Services/Receivers, there is no `output.getServices()` returning SootClass. We must resolve class names from `XMLParser` to `SootClass` via `Scene.v().getSootClassUnsafe()`.
- `RvsecAnalysisClient.complementWithCallbacks()` (L351-393) adds lifecycle handlers and event handlers as reachable methods with MOP flag propagation — but only for Activities (L362-373). Must also include Service/Receiver lifecycle methods so they receive MOP flags via the call graph.

### Current JSON structure

```json
{
  "package": "com.example.app",
  "mainActivity": "com.example.app.MainActivity",
  "reachability": [
    {
      "className": "com.example.SomeClass",
      "isActivity": true|false,
      "isMainActivity": true|false,
      "methods": [
        {"name": "m", "signature": "<...>", "reachable": bool, "reachesMop": bool, "directlyReachesMop": bool}
      ]
    }
  ],
  "windows": [
    {"id": N, "name": "activity.class.name", "type": "ACTIVITY"|"DIALOG", "isMain": bool, "widgets": [...]}
  ],
  "transitions": [...]
}
```

## Architecture

The change spans two repositories (rvsec-gator and ape) but touches minimal files in each.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `XMLParser.getReceivers()` | Expose parsed receiver class names | AndroidManifest.xml | `Iterator<String>` |
| `RvsecAnalysisClient.getEntryPoints()` | Add Service/Receiver lifecycle methods as entry points | `XMLParser` services/receivers | `Set<SootMethod>` |
| `RvsecAnalysisClient.writeJson()` | Write `components[]` section to JSON | Services/Receivers sets | JSON file |
| `MopData.load()` (APE-RV) | Parse `components[]` from JSON | JSON file | Component MOP data |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-EP-01: Service lifecycle entry points | `RvsecAnalysisClient.getEntryPoints()` | Integration: JSON output for APK with Service |
| INV-EP-02: Receiver onReceive entry point | `RvsecAnalysisClient.getEntryPoints()` | Integration: JSON output for APK with Receiver |
| INV-EP-03: components[] in JSON | `RvsecAnalysisClient.writeComponents()` | Integration: verify JSON structure |
| INV-EP-04: Existing data unchanged | No code change needed | Integration: diff JSON before/after |
| MOP flag propagation for callbacks | `RvsecAnalysisClient.complementWithCallbacks()` | Integration: verify reachesMop for Service lifecycle |
| Reachability isService/isReceiver flags | `RvsecAnalysisClient.writeReachability()` | Integration: verify JSON fields |
| MopData components parsing | `MopData.parseComponents()` | Unit: `MopDataTest` |

## Goals / Non-Goals

**Goals:**
- Service and BroadcastReceiver lifecycle methods included as entry points in the call graph traversal
- New `components[]` section in the static analysis JSON listing non-Activity components
- `MopData` in APE-RV parses the new section for component-level MOP awareness
- Backward compatible: old JSON files (without `components[]`) work unchanged

**Non-Goals:**
- Adding ActionTypes in APE-RV for `am broadcast`/`am startservice` (direct component triggering during exploration)
- ContentProvider entry points (no lifecycle method equivalent)
- Modifying `windows[]` structure (Services/Receivers have no GUI windows)
- Changing MopScorer priority values for component-level MOP

## Decisions

**D1: Resolve class names via Scene.v().getSootClassUnsafe(), not GUIAnalysisOutput**

`GUIAnalysisOutput.getActivities()` returns `Set<SootClass>` from the flowgraph's `allNActivityNodes`, but there is no equivalent for Services/Receivers (they are not GUI nodes). `XMLParser.getServices()/getReceivers()` return `Iterator<String>` (class names). We resolve to `SootClass` using `Scene.v().getSootClassUnsafe(className)`, which returns `null` for unresolvable classes instead of throwing. This follows GATOR's existing pattern in `ServiceTestingClient` (L54).

**D2: Lifecycle methods hardcoded, not discovered via class hierarchy**

Instead of walking the class hierarchy to find all overridden lifecycle methods, we check for specific method names: `onStartCommand`, `onBind`, `onUnbind`, `onCreate`, `onDestroy`, `onHandleIntent` (Services), `onReceive` (Receivers). This is simpler (P1) and covers all practical cases. If a method doesn't exist in the class, `SootClass.getMethodByNameUnsafe()` returns null — skip it.

**D3: components[] is a new top-level JSON section, not part of windows[]**

Services and Receivers have no GUI representation. Adding them to `windows[]` (which contains widgets, listeners, layout info) would be semantically wrong and break APE-RV's `MopData` parser which expects window entries to have widgets. A separate `components[]` section is cleaner and backward compatible.

**D4: MopData parses components[] as optional section**

If `components[]` is absent (old JSON files), `MopData` ignores it and behaves identically to the current version. No migration or re-analysis needed for existing experiment data.

## API Design

### `XMLParser.getReceivers() -> Iterator<String>`
- **Pre**: Manifest has been parsed
- **Post**: Returns iterator over fully qualified receiver class names
- **Error**: Never throws; returns empty iterator if no receivers

### `RvsecAnalysisClient.getEntryPoints(GUIAnalysisOutput output) -> Set<SootMethod>`
- **Pre**: output non-null, Soot Scene initialized
- **Post**: Set contains lifecycle methods + public/protected methods of Activities, Services, and Receivers
- **Error**: Unresolvable classes logged as WARNING and skipped

### `RvsecAnalysisClient.writeComponents(JsonWriter w, XMLParser xml) -> void`
- **Pre**: JsonWriter in object context
- **Post**: Writes `"components": [...]` array
- **Error**: IOException propagated to caller (same as other write methods)

### `MopData.hasComponentMop(String className) -> boolean`
- **Pre**: MopData loaded
- **Post**: Returns true if the component class has lifecycle methods with MOP reachability
- **Error**: Returns false for unknown class names

### `MopData.getComponentCount() -> int`
- **Pre**: MopData loaded
- **Post**: Returns number of components parsed from `components[]`
- **Error**: Returns 0 if section absent

## Data Flow

```
AndroidManifest.xml
    │
    ▼
DefaultXMLParser.parse()
    │
    ├── activities[] ─── (existing) ──→ GUIAnalysisOutput.getActivities()
    ├── services[]   ─── getServices() ──→ RvsecAnalysisClient.getEntryPoints()
    └── receivers[]  ─── getReceivers() ──→ RvsecAnalysisClient.getEntryPoints()
                                                │
                                                ▼
                                     Call graph traversal (Soot)
                                                │
                                                ▼
                                     reachability[] + components[] in JSON
                                                │
                                                ▼
                                     MopData.load() in APE-RV
                                                │
                                     ┌──────────┴──────────┐
                                     ▼                      ▼
                              Widget MOP data         Component MOP data
                              (existing)              (new: hasComponentMop)
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Class not found in Soot Scene | Manifest declares class not in APK | Log WARNING, skip class | Continue with remaining classes |
| Method not found in SootClass | Lifecycle method not overridden | Skip silently (expected) | No recovery needed |
| components[] absent from JSON | Old JSON format | MopData ignores, returns 0/false | Graceful degradation |

## Risks / Trade-offs

- **[Risk: Soot resolution failures]** Some manifest entries reference classes that Soot cannot resolve (e.g., library classes not in the analysis scope). Mitigation: `getSootClassUnsafe()` returns null, logged and skipped.
- **[Risk: Increased analysis time]** More entry points mean larger call graphs. Mitigation: Services and Receivers are typically few (1-5 per app) compared to activities (5-20). Marginal impact expected.
- **[Trade-off: No MopScorer changes]** Component MOP data is parsed but not used for priority scoring in this change. Services/Receivers have no widgets to boost. The data is available for future use (e.g., `am broadcast` action type).

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | MopData.parseComponents() | JUnit with test JSON containing components[] | ~3 tests |
| Unit | MopData backward compat (no components[]) | JUnit with existing test JSON | ~1 test |
| Integration | getReceivers() returns parsed receivers | RvsecAnalysisClientIT with test APK | ~1 test |
| Integration | JSON output contains components[] | RvsecAnalysisClientIT, verify JSON structure | ~1 test |
| Integration | Full pipeline: APK with Service → JSON → MopData | End-to-end with cryptoapp or similar | Manual |

## Resolved Questions

- **Should `reachability[]` include `isService`/`isReceiver`?** YES. Low cost, avoids cross-referencing `components[]`. Added as task 2.4.
