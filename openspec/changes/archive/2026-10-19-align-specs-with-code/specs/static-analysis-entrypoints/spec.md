# static-analysis-entrypoints — delta: align-specs-with-code

## Purpose

The RVSEC static analysis pipeline (rvsec-gator) produces a JSON file per APK containing reachability data (with, since GATOR gh120, each method's call-graph distance to every monitored target), window/widget structure, and WTG transitions. That file is the **host-side** input from which the rv-android generator derives the compact artifact the jar loads, **format 2** ("Derived compact MOP artifact — projection contents": the format-1 projection plus the target count, per-widget and per-activity distance pairs, and the handler-class table); the device never reads it directly (see `mop-guidance`).

`RvsecAnalysisClient.getEntryPoints()` enumerates Activities, Services (`onStartCommand`, `onBind`, `onDestroy`) and BroadcastReceivers (`onReceive`) as entry points, so methods reachable only through a non-GUI component are traversed rather than dropped from the call graph — which is what makes their MOP reachability visible at all. Receivers reach the enumeration through `getReceivers()` on the `XMLParser` interface, alongside the `getServices()` that was already there; both read the `<service>` and `<receiver>` tags, with their IntentFilters, that `DefaultXMLParser` parses from AndroidManifest.xml.

This specification defines which components are entry points, how they appear in the JSON output, and what the runtime needs from them — the intent surface `component-triggering` dispatches against, and the activity reachability the A′ union draws on as one of its three sources.

The producer's document speaks the analyser's neutral vocabulary: a method or component that reaches a monitored operation carries `reachesTarget` / `directlyReachesTarget`, and a component lists its reaching lifecycle methods in `targetMethods`. The generator is the one component that reads that document, and it renames to the MOP vocabulary on the wire (`mop`, `mopActivities`, `reachesMop`); `hasTargetMethods` is the one `Target` name the wire keeps (design D7).

Component triggering is a STATIC member whose pool reads no MOP data (design D-B13), so the compact artifact projects every receiver, service and provider whatever its `reachesTarget`, and each receiver and service intent filter carries, besides `actions` and `categories`, an optional reduced `data` member (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`; design D-B14.11) so that a filter matching on a scheme, host, path or MIME type can be targeted. The member is additive: the artifact stays `formatVersion: 2`, and the generator string and the `source.digest` chain are unchanged. The derive is implemented in rv-android (its `aperv` spec); this specification mirrors the same contract on the jar side. The INV-DRV-* invariants cited here are rv-android's, defined in its `aperv` spec (INV-DRV-06 is mirrored below); INV-ANA-* and INV-INS-* are rv-android's `analysis` and `instrumentation` invariants.

## Data Contracts

### Output
- Extended `reachability[]` in JSON — classes reachable from Service/Receiver lifecycle methods included, each with `componentType` and `isMain` (INV-EP-04) and per-method `signature`, `reachable`, `reachesTarget` and `directlyReachesTarget` flags (and, since gh120, `targetDistances`)
- New `components{}` section in JSON — structured data for activities, receivers, services and providers with intent-filters (or authorities), exported status, target reachability (`reachesTarget`) and the signatures of the reaching lifecycle methods (`targetMethods`)

## MODIFIED Requirements

### Requirement: MOP flag propagation for Service/Receiver callbacks

Service and BroadcastReceiver lifecycle methods SHALL receive MOP flag propagation through the call graph, following the same mechanism used for Activity lifecycle handlers in `complementWithCallbacks()`. In the producer's document the flags are `reachesTarget` and `directlyReachesTarget`; the generator renames them to the MOP vocabulary on the wire.

#### Scenario: Service lifecycle method reaches MOP
- **WHEN** a Service's `onStartCommand` method calls a method that directly reaches a MOP specification
- **THEN** `onStartCommand` SHALL be marked with `reachesTarget=true` in `reachability[]`

### Requirement: JSON output — components section

The static analysis JSON SHALL include a new top-level `components{}` object with four arrays: `activities[]`, `receivers[]`, `services[]`, and `providers[]`.

Each activity/receiver/service entry SHALL contain:
- `className` (String): fully qualified class name
- `isMain` (boolean): true only for the main launcher activity
- `intentFilters` (Array): list of `{actions: [...], categories: [...]}` objects from the manifest
- `exported` (boolean): value of `android:exported` attribute
- `reachesTarget` (boolean): true if any lifecycle method reaches a MOP specification
- `targetMethods` (Array of String): Soot signatures of lifecycle methods that reach MOP

Each provider entry SHALL contain the same fields except `intentFilters` is replaced by:
- `authorities` (String): value of `android:authorities` attribute

The generator reads `reachesTarget` and `targetMethods` and emits them on the wire as `reachesMop` and the boolean `hasTargetMethods` ("Derived compact MOP artifact — projection contents" item 7); `exported` stays in the full JSON.

#### Scenario: App with all component types
- **WHEN** an APK declares an Activity, Receiver, Service, and ContentProvider
- **THEN** the JSON SHALL contain:
  ```json
  "components": {
    "activities": [{
      "className": "com.example.app.MainActivity",
      "isMain": true,
      "intentFilters": [{"actions": ["android.intent.action.MAIN"], "categories": ["android.intent.category.LAUNCHER"]}],
      "exported": true,
      "reachesTarget": false,
      "targetMethods": []
    }],
    "receivers": [{
      "className": "com.example.app.BootReceiver",
      "isMain": false,
      "intentFilters": [{"actions": ["android.intent.action.BOOT_COMPLETED"], "categories": []}],
      "exported": true,
      "reachesTarget": true,
      "targetMethods": ["<com.example.app.BootReceiver: void onReceive(android.content.Context,android.content.Intent)>"]
    }],
    "services": [{
      "className": "com.example.app.CryptoService",
      "isMain": false,
      "intentFilters": [{"actions": ["com.example.START_CRYPTO"], "categories": []}],
      "exported": false,
      "reachesTarget": true,
      "targetMethods": ["<com.example.app.CryptoService: int onStartCommand(android.content.Intent,int,int)>"]
    }],
    "providers": [{
      "className": "com.example.app.DataProvider",
      "isMain": false,
      "authorities": "com.example.app.data",
      "exported": false,
      "reachesTarget": false,
      "targetMethods": []
    }]
  }
  ```

#### Scenario: App with no non-Activity components
- **WHEN** an APK has only Activities
- **THEN** the JSON SHALL contain `"components": {"activities": [...], "receivers": [], "services": [], "providers": []}`

#### Scenario: Component without intent-filters
- **WHEN** a Service is declared without any `<intent-filter>` in the manifest
- **THEN** its entry SHALL have `"intentFilters": []`

## REMOVED Requirements

### Requirement: Equivalence gate for the parser cutover
**Reason**: A one-shot cutover artifact, satisfied when the jar's full-JSON parser was deleted (`rearch-07-compact-static-artifact`). Its own scenario required the gate test and the old parser to be deleted after it passed, and neither exists in `src/main` or `src/test`, so it no longer states a requirement on the code.
**Migration**: None. The permanent protection is the generator's per-rule unit tests in rv-android (INV-DRV-01..04, INV-DRV-06, INV-DRV-07) and the jar's fixture tests over the derived cryptoapp artifact (`mop-guidance` "MopData — Static Analysis JSON Loader").
