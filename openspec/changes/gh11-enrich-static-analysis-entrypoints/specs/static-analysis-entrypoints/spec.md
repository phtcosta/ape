# Specification: Static Analysis Entry Points

## Purpose

The RVSEC static analysis pipeline (rvsec-gator) produces a JSON file per APK containing reachability data, window/widget structure, and WTG transitions. This JSON is consumed by APE-RV's `MopData` to steer GUI exploration toward MOP-reachable code paths.

Currently, `RvsecAnalysisClient.getEntryPoints()` enumerates only Activity classes from GATOR's `output.getActivities()`. Android apps also define Services (`onStartCommand`, `onBind`, `onDestroy`) and BroadcastReceivers (`onReceive`) in their AndroidManifest.xml. Methods reachable only through these components are excluded from the call graph traversal, causing their MOP reachability to be missed entirely.

GATOR's `DefaultXMLParser` already parses `<service>` and `<receiver>` tags from AndroidManifest.xml. Services are accessible via `xml.getServices()`, but Receivers lack a public getter (`getReceivers()` is missing from the `XMLParser` interface despite the field existing in `DefaultXMLParser`).

This specification defines how Services and BroadcastReceivers SHALL be incorporated as entry points in the static analysis, and how the resulting data SHALL appear in the JSON output.

---

## Data Contracts

### Input
- `AndroidManifest.xml` — parsed by GATOR's `DefaultXMLParser`, provides `<service>` and `<receiver>` declarations with IntentFilters
- `xml.getServices()` — `Iterator<String>` of Service class names declared in the manifest (existing)
- `xml.getReceivers()` — `Iterator<String>` of BroadcastReceiver class names declared in the manifest (requires new getter)

### Output
- Extended `reachability[]` in JSON — classes reachable from Service/Receiver lifecycle methods included with `reachable`, `reachesMop`, `directlyReachesMop` flags
- New `components[]` section in JSON — Service and BroadcastReceiver declarations with their class names and types

### Side-Effects
- **[Static Analysis JSON]**: JSON files produced by rvsec-gator will contain additional entries in `reachability[]` and a new `components[]` section

### Error
- If a Service/Receiver class declared in the manifest cannot be resolved by Soot (e.g., class not found in the APK), it SHALL be skipped with a WARNING log. No exception SHALL propagate.

---

## Invariants

- **INV-EP-01**: Every Service class returned by `xml.getServices()` SHALL have its lifecycle methods (`onCreate`, `onStartCommand`, `onBind`, `onUnbind`, `onDestroy`, `onHandleIntent`) added as entry points if they exist in the `SootClass`.
- **INV-EP-02**: Every BroadcastReceiver class returned by `xml.getReceivers()` SHALL have its `onReceive` method added as an entry point if it exists in the `SootClass`.
- **INV-EP-03**: The `components[]` JSON section SHALL contain one entry per Service and one per BroadcastReceiver declared in the manifest, regardless of whether their lifecycle methods reach MOP specs.
- **INV-EP-04**: Existing `reachability[]` and `windows[]` data for Activities SHALL remain unchanged. The change is strictly additive.

---

## ADDED Requirements

### Requirement: XMLParser — getReceivers() accessor

The `XMLParser` interface SHALL expose a `getReceivers()` method returning `Iterator<String>` of BroadcastReceiver class names parsed from AndroidManifest.xml, following the same pattern as the existing `getServices()`. `DefaultXMLParser` already stores receivers in an internal `receivers` ArrayList — the accessor SHALL expose this existing data.

#### Scenario: getReceivers() returns parsed receivers
- **WHEN** an APK declares `<receiver android:name=".MyReceiver"/>` in its AndroidManifest.xml
- **THEN** `xml.getReceivers()` SHALL return an iterator containing the fully qualified class name `"com.example.app.MyReceiver"`

#### Scenario: No receivers declared
- **WHEN** an APK declares no `<receiver>` tags in its AndroidManifest.xml
- **THEN** `xml.getReceivers()` SHALL return an empty iterator

---

### Requirement: RvsecAnalysisClient — Service and Receiver entry points

`RvsecAnalysisClient.getEntryPoints()` SHALL iterate over Services from `xml.getServices()` and Receivers from `xml.getReceivers()`, adding their lifecycle methods as entry points in addition to the existing Activity entry points.

For Services, the lifecycle methods are: `onCreate`, `onStartCommand`, `onBind`, `onUnbind`, `onDestroy`, `onHandleIntent`. For BroadcastReceivers, the lifecycle method is: `onReceive`. Only methods that exist in the `SootClass` SHALL be added (no crash if a method is not overridden).

Public and protected methods of Service and Receiver classes SHALL also be added as entry points, following the same pattern used for Activity classes.

#### Scenario: Service lifecycle methods as entry points
- **WHEN** an APK declares a Service `com.example.app.MyService` with an overridden `onStartCommand` method
- **THEN** `getEntryPoints()` SHALL include `MyService.onStartCommand` in the returned set
- **AND** the call graph traversal SHALL reach methods called from `onStartCommand`
- **AND** MOP reachability SHALL be computed for these methods

#### Scenario: BroadcastReceiver onReceive as entry point
- **WHEN** an APK declares a BroadcastReceiver `com.example.app.MyReceiver` with an `onReceive` method
- **THEN** `getEntryPoints()` SHALL include `MyReceiver.onReceive` in the returned set
- **AND** methods called from `onReceive` SHALL appear in `reachability[]` with correct MOP flags

#### Scenario: Unresolvable class
- **WHEN** the manifest declares `<service android:name=".MissingService"/>` but the class does not exist in the APK
- **THEN** the class SHALL be skipped
- **AND** a WARNING SHALL be logged
- **AND** no exception SHALL propagate

---

### Requirement: JSON output — components section

The static analysis JSON SHALL include a new top-level `components[]` array listing non-Activity Android components (Services, BroadcastReceivers) declared in the manifest.

Each entry SHALL contain:
- `className` (String): fully qualified class name
- `type` (String): `"SERVICE"` or `"BROADCAST_RECEIVER"`

#### Scenario: App with Service and Receiver
- **WHEN** an APK declares Service `com.example.app.CryptoService` and BroadcastReceiver `com.example.app.BootReceiver`
- **THEN** the JSON output SHALL contain:
  ```json
  "components": [
    {"className": "com.example.app.CryptoService", "type": "SERVICE"},
    {"className": "com.example.app.BootReceiver", "type": "BROADCAST_RECEIVER"}
  ]
  ```

#### Scenario: App with no Services or Receivers
- **WHEN** an APK declares no Services or BroadcastReceivers
- **THEN** the JSON output SHALL contain an empty `components[]` array
- **AND** all other sections (`windows[]`, `reachability[]`, `transitions[]`) SHALL be unchanged

---

### Requirement: Reachability entries — component type flags

Each entry in `reachability[]` SHALL include `isService` (boolean) and `isReceiver` (boolean) fields alongside the existing `isActivity` flag. This allows consumers to identify component type without cross-referencing `components[]`.

#### Scenario: Service class in reachability
- **WHEN** a Service class appears in `reachability[]`
- **THEN** its entry SHALL have `isService=true`, `isActivity=false`, `isReceiver=false`

#### Scenario: Non-component class in reachability
- **WHEN** a class that is not an Activity, Service, or Receiver appears in `reachability[]`
- **THEN** its entry SHALL have `isActivity=false`, `isService=false`, `isReceiver=false`

---

### Requirement: MOP flag propagation for Service/Receiver callbacks

Service and BroadcastReceiver lifecycle methods SHALL receive MOP flag propagation through the call graph, following the same mechanism used for Activity lifecycle handlers. Specifically, `complementWithCallbacks()` SHALL include Service/Receiver lifecycle methods in its callback set so they are marked as reachable and their MOP flags are propagated from downstream methods in the call graph.

#### Scenario: Service lifecycle method reaches MOP
- **WHEN** a Service's `onStartCommand` method calls a method that directly reaches a MOP specification
- **THEN** `onStartCommand` SHALL be marked with `reachesMop=true` in `reachability[]`
