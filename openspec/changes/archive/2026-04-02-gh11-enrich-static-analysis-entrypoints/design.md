## Context

APE-RV's MOP-guided scoring relies on static analysis JSON produced by rvsec-gator (`RvsecAnalysisClient`). The JSON contains `reachability[]`, `windows[]`, and `transitions[]` sections. Currently, only Activity classes serve as entry points for the call graph traversal (`getEntryPoints()` at L252-266 of `RvsecAnalysisClient.java`). Services and BroadcastReceivers declared in AndroidManifest.xml are ignored, causing methods reachable only through these components to be absent from the reachability analysis.

Beyond the static analysis gap, APE-RV has no mechanism to exercise non-GUI components at runtime. Services and BroadcastReceivers frequently execute security-relevant code (crypto, TLS) invisible to UI-only exploration.

This was documented as a Known Limitation in the gh9 design (L233-253) and analyzed in detail in `rv-android/docs/analise_broadcast_service_integration.md` (2026-03-18). Refs proposal.md and #11.

### Current state in GATOR

- `XMLParser` interface defines `getServices()` (L154) returning `Iterator<String>`. No `getReceivers()` method exists.
- `AbstractXMLParser` has fields `services` and `receivers` (ArrayList\<String\>), both populated by `DefaultXMLParser`.
- `DefaultXMLParser` parses `<service>` (L415-426) and `<receiver>` (L428-436) including IntentFilters via `readIntentFilters()` (L252-337), stored in `IntentFilterManager`.
- `RvsecAnalysisClient.getEntryPoints()` uses `output.getActivities()` only.
- `RvsecAnalysisClient.complementWithCallbacks()` (L351-393) propagates MOP flags only for Activity lifecycle handlers.
- For Services/Receivers, resolve class names via `Scene.v().getSootClassUnsafe()`.

### Current state in APE-RV

- `AndroidDevice.broadcastIntent(Intent)` (L434-442) exists — used for IME. Reusable for broadcast triggering.
- No `startService()` in `AndroidDevice`.
- `SataAgent` has stagnation detection via `graphStableCounter` with restart as only escape.
- `MopData` parses 3 passes (reachability, windows, transitions). No components parsing.

### JSON structure (after rvsec#45)

```json
{
  "package": "...", "mainActivity": "...",
  "reachability": [{"className": "...", "componentType": "activity"|"service"|"receiver"|"provider"|null, "isMain": bool, "methods": [...]}],
  "windows": [{"id": N, "name": "...", "type": "ACTIVITY"|"DIALOG", "widgets": [...]}],
  "transitions": [...],
  "components": {
    "activities": [{"className": "...", "isMain": bool, "intentFilters": [...], "exported": bool, "reachesMop": bool, "mopMethods": [...]}],
    "receivers": [same fields],
    "services": [same fields],
    "providers": [{"className": "...", "isMain": false, "authorities": "...", "exported": bool, "reachesMop": bool, "mopMethods": [...]}]
  }
}
```

**Breaking change from rvsec#45**: `isActivity`/`isMainActivity` replaced by `componentType`/`isMain`. MopData Pass 1 must handle this.

## Architecture

4 layers across two repos (rvsec-gator and ape).

### Key Components

| Component | Responsibility | Repo |
|-----------|---------------|------|
| `XMLParser.getReceivers()` | Expose parsed receiver class names | rvsec-gator |
| `RvsecAnalysisClient.getEntryPoints()` | Add Service/Receiver lifecycle as entry points | rvsec-gator |
| `RvsecAnalysisClient.writeComponents()` | Write rich `components{}` JSON with intent-filters | rvsec-gator |
| `MopData` Pass 4 | Parse `components{}` → ReceiverInfo/ServiceInfo | ape |
| `SystemBroadcastCatalog` | Lookup typed extras for system broadcast actions | ape |
| `AndroidDevice.startService()` | Start service via IActivityManager reflection | ape |
| `SataAgent` stagnation escape | Trigger broadcasts/services before restart | ape |
| `Config` flags | `testBroadcasts`, `testServices` (default false) | ape |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-EP-01..02: Entry points | `getEntryPoints()` | Integration: JSON with Service/Receiver methods |
| INV-EP-03: components{} | `writeComponents()` | Integration: verify JSON structure |
| INV-EP-04: Unchanged data | No change | Integration: diff before/after |
| INV-EP-05: Intent-filters | `writeComponents()` reads IntentFilterManager | Integration: verify intentFilters |
| MOP propagation | `complementWithCallbacks()` | Integration: verify reachesMop |
| isService/isReceiver | `writeReachability()` | Integration: verify fields |
| MopData components | `MopData` Pass 4 | Unit: MopDataTest |
| INV-CT-01: Opt-in | `Config.testBroadcasts/testServices` | Unit: default false |
| INV-CT-02: Stagnation | `SataAgent.selectNewActionNonnull()` | Integration |
| EVENT_BROADCAST | `MonkeySourceApe` + `AndroidDevice` | Integration |
| EVENT_START_SERVICE | `MonkeySourceApe` + `AndroidDevice.startService()` | Integration |

## Goals / Non-Goals

**Goals:**
- Service/BroadcastReceiver lifecycle methods as entry points in call graph
- Rich `components{}` JSON with intent-filters, exported, reachesMop, mopMethods
- MopData parses components, provides ReceiverInfo/ServiceInfo
- EVENT_BROADCAST and EVENT_START_SERVICE action types
- Stagnation escape: trigger MOP components before restart
- Backward compatible: old JSON works, testBroadcasts=false default

**Non-Goals:**
- ContentProvider entry points
- Modifying `windows[]`
- MopScorer priority changes for components (feeds stagnation escape, not scorer)
- Complex intent extras (only action + ComponentName)
- Dynamically registered receivers (manifest-declared only)

## Decisions

**D1: Resolve via Scene.v().getSootClassUnsafe()** — no GUIAnalysisOutput equivalent for Services/Receivers. Pattern from ServiceTestingClient (L54).

**D2: Lifecycle methods hardcoded** — `onCreate`, `onStartCommand`, `onBind`, `onUnbind`, `onDestroy`, `onHandleIntent` (Services), `onReceive` (Receivers). P1 simplicity.

**D3: Structured components{} with receivers[]/services[]** — type-specific handling, intent-filters per component for runtime use. From `analise_broadcast_service_integration.md`.

**D4: components{} optional in MopData** — absent = empty lists. No migration.

**D5: Stagnation escape, not continuous** — trigger only when `graphStableCounter` exceeds threshold. Round-robin across MOP components.

**D6: Reuse AndroidDevice.broadcastIntent()** — add symmetric `startService()` via `IActivityManager.startService()`.

**D7: Intent construction with optional system broadcast extras**

Intents use action + ComponentName for explicit delivery. For system broadcast actions (e.g., `BOOT_COMPLETED`, `CONNECTIVITY_CHANGE`), a `SystemBroadcastCatalog` provides typed extras from the VLM-Fuzz catalog (187 entries, 120 with extras). The catalog is embedded as a JSON resource and provides lookup by action string. If the receiver's intent-filter action matches a catalog entry, extras are added to the Intent. If no match, the Intent is sent with action + ComponentName only. This covers the common case without app-specific extra construction.

## API Design

### rvsec-gator

`XMLParser.getReceivers() -> Iterator<String>` — receiver class names from manifest.

`RvsecAnalysisClient.writeComponents(JsonWriter w)` — writes `"components": {"receivers": [...], "services": [...]}` with intentFilters, exported, reachesMop, mopMethods per entry.

### APE-RV

`MopData.getMopReceivers() -> List<ReceiverInfo>` — receivers with reachesMop=true + actions.
`MopData.getMopServices() -> List<ServiceInfo>` — services with reachesMop=true + actions.
`MopData.hasComponents() -> boolean` — true if any MOP receivers or services.
`AndroidDevice.startService(Intent) -> boolean` — start service via IActivityManager reflection.
`ReceiverInfo(String className, List<String> actions)` — domain object.
`ServiceInfo(String className, List<String> actions)` — domain object.
`SystemBroadcastCatalog.lookup(String action) -> List<IntentExtra>` — typed extras for system broadcasts (187 entries from VLM-Fuzz catalog). Returns empty list if no match.
`IntentExtra(String key, String type, String value)` — types: `string` (`--es`), `int` (`--ei`), `boolean` (`--ez`), `long` (`--el`), `float` (`--ef`).

## Data Flow

```
AndroidManifest.xml
    │
    ▼
DefaultXMLParser.parse()
    ├── activities[]  → GUIAnalysisOutput.getActivities() (existing)
    ├── services[]    → getServices() → getEntryPoints()
    ├── receivers[]   → getReceivers() → getEntryPoints()
    └── intentFilters → IntentFilterManager → writeComponents()
                                               │
                        Call graph traversal ◄──┘
                                │
                                ▼
                    Static Analysis JSON
                    ├── reachability[] (expanded: isService, isReceiver)
                    ├── windows[], transitions[] (unchanged)
                    └── components{} (NEW: receivers[], services[] with intentFilters)
                                │
                                ▼
                    MopData.load() — Pass 4: components
                    ├── ReceiverInfo[] (className, actions)
                    └── ServiceInfo[] (className, actions)
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
             Widget MOP scoring      Stagnation escape hatch
             (existing, improved)    (NEW: broadcast/service trigger)
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Class not in Soot | Manifest missing class | Log WARNING, skip | Continue |
| Lifecycle not overridden | Method not in SootClass | Skip silently | Expected |
| components{} absent | Old JSON | Empty lists | Graceful |
| Broadcast fails | RemoteException | Log WARNING | Continue |
| Service start fails | RemoteException | Log WARNING | Continue |

## Risks / Trade-offs

- **[Risk: Broadcast side effects]** May crash app or open unexpected Activities. Mitigation: opt-in, stagnation only.
- **[Trade-off: Simple intents]** No extras. Some receivers may not process. Future: VLM-Fuzz extras catalog.
- **[Trade-off: No MopScorer changes]** Components feed escape hatch, not scorer. Services/Receivers have no widgets to boost.

## Testing Strategy

| Layer | What | How | Count |
|-------|------|-----|-------|
| Unit | MopData.parseComponents() rich JSON | JUnit | ~4 |
| Unit | MopData backward compat | JUnit | ~1 |
| Unit | ReceiverInfo/ServiceInfo | JUnit | ~2 |
| Unit | Config defaults | JUnit | ~1 |
| Integration | getReceivers(), writeComponents() | RvsecAnalysisClientIT | ~2 |
| Integration | Full pipeline: APK → JSON → MopData → trigger | adb shell | Manual |

## Resolved Questions

- **isService/isReceiver in reachability[]?** YES.
- **Flat vs structured components?** Structured — `{receivers[], services[]}`.
- **When to trigger?** Stagnation escape only.
- **How to construct intents?** Action + ComponentName, no extras.
