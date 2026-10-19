# Specification: Static Analysis Entry Points

## Purpose

The RVSEC static analysis pipeline (rvsec-gator) produces a JSON file per APK containing reachability data (with, since GATOR gh120, each method's call-graph distance to every monitored target), window/widget structure, and WTG transitions. That file is the **host-side** input from which the rv-android generator derives the compact artifact the jar loads, **format 2** ("Derived compact MOP artifact — projection contents": the format-1 projection plus the target count, per-widget and per-activity distance pairs, and the handler-class table); the device never reads it directly (see `mop-guidance`).

`RvsecAnalysisClient.getEntryPoints()` enumerates Activities, Services (`onStartCommand`, `onBind`, `onDestroy`) and BroadcastReceivers (`onReceive`) as entry points, so methods reachable only through a non-GUI component are traversed rather than dropped from the call graph — which is what makes their MOP reachability visible at all. Receivers reach the enumeration through `getReceivers()` on the `XMLParser` interface, alongside the `getServices()` that was already there; both read the `<service>` and `<receiver>` tags, with their IntentFilters, that `DefaultXMLParser` parses from AndroidManifest.xml.

This specification defines which components are entry points, how they appear in the JSON output, and what the runtime needs from them — the intent surface `component-triggering` dispatches against, and the activity reachability the A′ union draws on as one of its three sources.

Component triggering is a STATIC member whose pool reads no MOP data (design D-B13), so the compact artifact projects every receiver, service and provider whatever its `reachesTarget`, and each receiver and service intent filter carries, besides `actions` and `categories`, an optional reduced `data` member (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`; design D-B14.11) so that a filter matching on a scheme, host, path or MIME type can be targeted. The member is additive: the artifact stays `formatVersion: 2`, and the generator string and the `source.digest` chain are unchanged. The derive is implemented in rv-android (its `aperv` spec); this specification mirrors the same contract on the jar side.

---

## Data Contracts

### Input
- `AndroidManifest.xml` — parsed by GATOR's `DefaultXMLParser`, provides `<service>` and `<receiver>` declarations with IntentFilters and `android:exported` attribute
- `xml.getServices()` — `Iterator<String>` of Service class names declared in the manifest (existing)
- `xml.getReceivers()` — `Iterator<String>` of BroadcastReceiver class names declared in the manifest (requires new getter)

### Output
- Extended `reachability[]` in JSON — classes reachable from Service/Receiver lifecycle methods included with `reachable`, `reachesMop`, `directlyReachesMop`, `isService`, `isReceiver` flags
- New `components{}` section in JSON — structured data for receivers and services with intent-filters, exported status, MOP reachability, and MOP method signatures

### Side-Effects
- **[Static Analysis JSON]**: JSON files produced by rvsec-gator will contain additional entries in `reachability[]` and a new `components{}` section

### Error
- If a Service/Receiver class declared in the manifest cannot be resolved by Soot (e.g., class not found in the APK), it SHALL be skipped with a WARNING log. No exception SHALL propagate.

---

## Invariants

- **INV-EP-01**: Every Service class returned by `xml.getServices()` SHALL have its lifecycle methods (`onCreate`, `onStartCommand`, `onBind`, `onUnbind`, `onDestroy`, `onHandleIntent`) added as entry points if they exist in the `SootClass`.
- **INV-EP-02**: Every BroadcastReceiver class returned by `xml.getReceivers()` SHALL have its `onReceive` method added as an entry point if it exists in the `SootClass`.
- **INV-EP-03**: The `components{}` JSON section SHALL contain one entry per Activity, Service, BroadcastReceiver, and ContentProvider declared in the manifest, regardless of whether their lifecycle methods reach MOP specs.
- **INV-EP-04**: Existing `windows[]` and `transitions[]` data SHALL remain unchanged. `reachability[]` entries use `componentType`/`isMain` instead of `isActivity`/`isMainActivity` (breaking change from rvsec#45).
- **INV-EP-05**: Each component entry SHALL include its intent-filters (actions + categories) or authorities (for providers) as parsed from the manifest, enabling runtime intent construction.
- **INV-DRV-06** (the defining text lives in rv-android's `aperv` spec): The artifact SHALL contain no call-graph data — no `reachability` section, no method signature and no method name, no call edge, no raw `windows`/`transitions`/`listeners` — and no `*Target` key other than `hasTargetMethods` on receivers and services (the check is a case-sensitive substring test for `Target`). Format 2 MAY carry integer target indices and integer distances (`targets`, `dist`, `activityDist`, the `dist` of a `handlers` record) and binary class names (the keys of `handlers`). A target index is joined to its signature only offline, through `source.digest` and the full JSON it names. The full JSON SHALL remain unmodified on the host.

---

## Requirements

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

### Requirement: MOP flag propagation for Service/Receiver callbacks

Service and BroadcastReceiver lifecycle methods SHALL receive MOP flag propagation through the call graph, following the same mechanism used for Activity lifecycle handlers in `complementWithCallbacks()`.

#### Scenario: Service lifecycle method reaches MOP
- **WHEN** a Service's `onStartCommand` method calls a method that directly reaches a MOP specification
- **THEN** `onStartCommand` SHALL be marked with `reachesMop=true` in `reachability[]`

---

### Requirement: Reachability entries — component type classification

Each entry in `reachability[]` SHALL include a `componentType` field (String, nullable) and an `isMain` field (boolean), replacing the former `isActivity`/`isMainActivity` booleans.

Valid `componentType` values: `"activity"`, `"service"`, `"receiver"`, `"provider"`, or `null` (for non-component classes).

#### Scenario: Service class in reachability
- **WHEN** a Service class appears in `reachability[]`
- **THEN** its entry SHALL have `"componentType": "service"`, `"isMain": false`

#### Scenario: Main Activity in reachability
- **WHEN** the main launcher Activity appears in `reachability[]`
- **THEN** its entry SHALL have `"componentType": "activity"`, `"isMain": true`

#### Scenario: Non-component class in reachability
- **WHEN** a class that is not an Activity, Service, Receiver, or Provider appears in `reachability[]`
- **THEN** its entry SHALL have `"componentType": null`, `"isMain": false`

---

### Requirement: JSON output — components section

The static analysis JSON SHALL include a new top-level `components{}` object with four arrays: `activities[]`, `receivers[]`, `services[]`, and `providers[]`.

Each activity/receiver/service entry SHALL contain:
- `className` (String): fully qualified class name
- `isMain` (boolean): true only for the main launcher activity
- `intentFilters` (Array): list of `{actions: [...], categories: [...]}` objects from the manifest
- `exported` (boolean): value of `android:exported` attribute
- `reachesMop` (boolean): true if any lifecycle method reaches a MOP specification
- `mopMethods` (Array of String): Soot signatures of lifecycle methods that reach MOP

Each provider entry SHALL contain the same fields except `intentFilters` is replaced by:
- `authorities` (String): value of `android:authorities` attribute

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
      "reachesMop": false,
      "mopMethods": []
    }],
    "receivers": [{
      "className": "com.example.app.BootReceiver",
      "isMain": false,
      "intentFilters": [{"actions": ["android.intent.action.BOOT_COMPLETED"], "categories": []}],
      "exported": true,
      "reachesMop": true,
      "mopMethods": ["<com.example.app.BootReceiver: void onReceive(android.content.Context,android.content.Intent)>"]
    }],
    "services": [{
      "className": "com.example.app.CryptoService",
      "isMain": false,
      "intentFilters": [{"actions": ["com.example.START_CRYPTO"], "categories": []}],
      "exported": false,
      "reachesMop": true,
      "mopMethods": ["<com.example.app.CryptoService: int onStartCommand(android.content.Intent,int,int)>"]
    }],
    "providers": [{
      "className": "com.example.app.DataProvider",
      "isMain": false,
      "authorities": "com.example.app.data",
      "exported": false,
      "reachesMop": false,
      "mopMethods": []
    }]
  }
  ```

#### Scenario: App with no non-Activity components
- **WHEN** an APK has only Activities
- **THEN** the JSON SHALL contain `"components": {"activities": [...], "receivers": [], "services": [], "providers": []}`

#### Scenario: Component without intent-filters
- **WHEN** a Service is declared without any `<intent-filter>` in the manifest
- **THEN** its entry SHALL have `"intentFilters": []`

### Requirement: Derived compact MOP artifact — projection contents

`derive_mop_artifact.derive(document)` SHALL produce a `formatVersion: 2` artifact containing exactly the projection the explorer consumes:

1. **Scalars**: `package`, `mainActivity` copied verbatim (they feed the T1.7 strict-match check and trigger `ComponentName` construction, INV-CT-04).
2. **Provenance**: `source.digest` (`sha256:<hex>` of the full-JSON bytes), `source.file` (basename), `source.generator` = `"aperv-derive/2"`.
3. **Widgets** (`widgets.<baseActivity>.<shortId>`): per-normalized-eventType MOP map `mop` with values `none|direct|transitive|both` (INV-DRV-01; keys pre-normalized — lowercase, `_`/`-` stripped), plus the consumed metadata fields `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, each emitted only when non-empty, plus the distance map `dist` (item 9). A widget SHALL be emitted only when it is MOP-flagged OR carries at least one metadata field (an unflagged, metadata-less widget is unreachable through every production query). `id`, `type`, `text`, and raw `listeners` SHALL NOT be emitted.
4. **Activity sets**: `mopActivities` — derived from the **MOP-flagged widgets**, not from the widgets that survive into the emitted map: a flagged widget contributes its base activity even when an empty short id keeps the widget itself off the wire (INV-DRV-02), and the dialog merge promotes a flagged dialog's host (INV-DRV-03) — and `mopActivitiesAugmented` (A′ 3-source union: widget-derived ∪ `components.activities[].reachesTarget==true` ∪ `reachability[]` classes with `componentType=="activity"` and ≥ 1 reaching method other than the class's own `<init>` and `<clinit>`) — both always emitted, so the on-device `mopActivitySourceComponents` flag keeps selecting at run time. The constructor exclusion of source 3 has the reason of the `activityDist` one (item 9): every activity constructor is a gh120 boundary target (D12), so without it every activity would reach a target and enter the treatment census (author's decision, 2026-10-07). Distances do not change either set.
5. **OPTIONSMENU records**: `optionsMenus: [{activity, hasFlaggedWidget}]`, one per **distinct base activity** owning an `OPTIONSMENU`-type window, `activity` = base activity of the window name, `hasFlaggedWidget` = the OR, across that activity's menu windows, of "any widget parsed from the window is MOP-flagged" — tested before the empty-short-id drop, since an id-less menu item still makes the menu a gateway. (The gateway *set* is recomputed on-device from these records + WTG + the selected activity set, keeping INV-MOP-13 flag-sensitive.)
6. **WTG**: `wtg.<sourceBaseActivity> = [{widget, target}]` per INV-DRV-03; `widget` is the transition event's `widgetName`; `widgetClass` SHALL NOT be emitted (zero production readers).
7. **Components**: `activities[]` (`className`, `isMain`, `permission`, `reachesMop`, `deepLinkUri` per INV-DRV-07), `receivers[]`/`services[]` (adds `intentFilters` and `hasTargetMethods`), `providers[]` (adds `authorities`). `reachesMop` is the D7 rename of the wire `reachesTarget`. Every receiver, service and provider of the document SHALL be projected, whatever its `reachesTarget`: the jar's component trigger is a STATIC member whose pool reads no MOP data (`isolate-guidance-groups` design D-B13), so a component left off the wire is one the trigger can never fire. Each `intentFilters[]` entry of a receiver or service SHALL carry `actions` and `categories` and, additively, an optional reduced `data` member `{"schemes": […], "hosts": […], "paths": […], "pathPrefixes": […], "mimeTypes": […]}` taken from that filter's producer `data` block: each of the five is a list of strings, a list that is empty SHALL be omitted, and `data` SHALL be omitted when all five are empty. `ports` and `pathPatterns` SHALL NOT be projected: a port is not part of the matching data the jar sets on the intent, and a pattern is not a literal path (design D-B14.11; the jar uses `paths[0]`, falling back to `pathPrefixes[0]`). The member is additive: the artifact stays `formatVersion: 2`, `source.generator` stays `"aperv-derive/2"`, the `source.digest` chain that joins a run to its analysis input is unchanged, and the jar reads an absent `data` as "no data". For activities the gh60 D15 `data` block itself SHALL NOT be emitted — its one production use is preserved as the derived `deepLinkUri` string. `readPermission`/`writePermission`, `targetMethods` signature lists, and `exported` SHALL NOT be emitted; the rest remains available host-side in the full JSON. The derive side of this item is implemented in rv-android's sister change `isolate-guidance-groups` (its `aperv` spec, "MOP Artifact Projection Contents"); this mirror states the same contract.
8. **Stats**: `windows`, `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `wtgEdges`, `dedupedTransitions` (INV-DRV-04). `widgetsTotal` and `flagged` count the widget map after the dialog merge and before the emission filter of item 3.
9. **Targets and distances** (format 2, design D15):
   - **`targets`**: the number of entries of the document's `distanceTargets` (direct targets C, followed by boundary targets B \ C; gh120 `analysis` spec `:326`). Every pair `[i, d]` of the artifact SHALL satisfy `0 ≤ i < targets`. No signature and no `kind` is emitted: boundary targets weigh as direct ones (author's decision).
   - **The distance of a handler.** A handler method's distance to target `i` is its `targetDistances` entry for `i`: 0 when the method is the target, 1–10 otherwise (gh120 `DIST_MAX = 10`); a target absent from its entries is unreachable. A handler SHALL resolve to its method by the same exact signature join that gives its flags (INV-DRV-01). A D8 synthetic-lambda wrapper absent from the exact-join index SHALL resolve by the same class recovery (INV-DRV-01, INV-DRV-09), and its distance to `i` is the minimum over the recovered `lambda$…` methods of the enclosing class.
   - **Per widget, `dist: {<event>: [[i, d], …]}`**, beside `mop`, for the same `(base activity, shortId, event)` key and the same normalized event keys. For each target, `d` is the minimum over the widget's handlers for that event. Collisions (INV-DRV-02) and the dialog merge (INV-DRV-03) SHALL merge `dist` lists by the minimum distance per target, while the flags keep the strongest-flag rule.
   - **Per activity, `activityDist: {<base activity>: [[i, d], …]}`**: for each target, the minimum over the pairs of the activity's widgets (after the dialog merge, including widgets the empty-short-id rule keeps off the wire) and the `targetDistances` of the reaching methods of the activity's own class (the classes the A′ source 3 indexes, `activity_classes`), except that class's `<init>` and `<clinit>`. Launching any activity runs its own constructor, so a target reached from it, typically the boundary target that is the constructor itself (gh120 D12), says nothing about which activity to launch first; without the exclusion every such activity sits at `d = 0` (author's decision, 2026-10-07). It feeds the launcher (`component-triggering`, design D20).
   - **`handlers: {<binary class name>: {mop: {<event>: flag}, dist: {<event>: [[i, d], …]}}}`**: one record per class of `reachability[]` that declares a handler method. `onClick(android.view.View)` fills `click`, `onLongClick(android.view.View)` fills `longclick`, and a Compose lambda's `java.lang.Object invoke()` fills both, because the node's stamp key, not the method, tells the event (gh121). `java.lang.Object invoke(java.lang.Object)` fills both in the same way, because gh121 also stamps a `ToggleableNode`'s `onValueChange`, which is a `Function1` (INV-INS-178). `<init>` and `<clinit>` are not handler methods. The flag of an event is derived from the handler method's own `directlyReachesTarget`/`reachesTarget` by INV-DRV-01's two axes, and its pairs are the method's distances. When two methods of one class fill the same event, their flags are OR-ed and their distances merged by the minimum per target. A class whose handler methods reach nothing SHALL still be listed, with `mop` `none` for each of its events and no `dist`: a listed class states that this handler reaches no target (`mop-guidance` "Handler Stamp Lookup"). The key is the binary name as `Class.getName()` returns it, the form gh121 writes and `reachability[].className` uses.
   - **Cut, order, omission.** A widget's and a handler's pair list SHALL keep every pair at `d ≤ 3`; an `activityDist` entry SHALL keep the K = 3 pairs of smallest `d` (design D15 as amended 2026-10-09: a pair at `d ≥ 4` gives an action no weight, and the K = 3 count cut hid 18.7 % of the targets at `d ≤ 3` on the lists that weigh actions in the round-A corpus). Every list SHALL be sorted by `d` ascending, then `i` ascending, so its order is total and the canonical bytes of INV-DRV-05 are fixed. A merge SHALL be applied before the cut, including the merge of a widget's or an activity's handlers and the widget minima `activityDist` starts from. An event with no pair SHALL have no key in a `dist` map; a `dist` map with no key, and an `activityDist` entry with no pair, SHALL be omitted. `targets` and `handlers` SHALL always be emitted, `0` and `{}` when empty.

Derivation preconditions: the document is an object, carries a non-null `package`, and every section it does carry is of the expected type; otherwise `DerivationError`, and no artifact is written. The producer's `"complete": true` sentinel is NOT read (rv-android INV-DRV-08): a well-typed document whose WTG stage did not run yields an artifact with an empty `wtg` and `stats.wtgEdges == 0`. A document without `distanceTargets` yields `targets: 0` and no pair. Nothing here refuses such a document: gh120 decided against a compatibility guard in the derive (gh120 design D9, `:110`), and the corpus hand-off is what keeps a September `.apk.json` from being derived. A gh120 document always has targets, because every activity constructor is a boundary target, so `targets: 0` marks a pre-gh120 input or an empty one; the jar loads it and the run continues without distance data (`mop-guidance`, "MopData — Static Analysis JSON Loader"; author, 2026-10-08).

#### Scenario: cryptoapp derivation matches the known ground truth
- **WHEN** `derive` runs on the gh120 test fixture `rv-android/modules/rv-static-analysis/tests/resources/cryptoapp.apk.json` (`br.unb.cic.cryptoapp`; 27 `distanceTargets`, 23 direct then 4 boundary, the four activity constructors)
- **THEN** the artifact SHALL carry `formatVersion: 2`, `source.generator == "aperv-derive/2"` and `targets: 27`
- **AND** it SHALL flag exactly `buttonGenerateHash`, `btn_cipher_encrypt` and `executeButton` (all `transitive` on `click`; no `direct` flag anywhere)
- **AND** `mopActivities` SHALL equal `{MessageDigestActivity, CipherActivity, CryptographyActivity}` (base names), and `mopActivitiesAugmented` SHALL equal it: no activity has `reachesTarget == true` (source 2), and `MainActivity`'s only reaching method is its constructor, which reaches boundary target 23 and which source 3 leaves out
- **AND** `executeButton` SHALL be flagged through the **exact join**: its handler `CryptographyActivity$$ExternalSyntheticLambda0.onClick` is listed in `reachability[]` with `reachesTarget: true`, because gh120 links the wrapper to its own `lambda$setupExecuteButton$0$CryptographyActivity(android.view.View)` body (`analysis` INV-ANA-77), so the class recovery is not consulted (INV-DRV-09) and `stats.recovered == 0`. The wrapper's own `targetDistances` has ten entries, targets 16, 17 and 18 at `d = 3` and targets 12–15 and 19–21 at `d = 4`
- **AND** the widget pairs SHALL be `buttonGenerateHash` `{click: [[22,2]]}` and `executeButton` `{click: [[16,3],[17,3],[18,3]]}`, the latter every pair at `d ≤ 3` of the wrapper's ten; `btn_cipher_encrypt`, whose nearest targets 0 and 1 lie at `d = 4`, SHALL carry no `dist`
- **AND** `activityDist` SHALL be `CipherActivity: [[0,2],[1,2]]`, `CryptographyActivity: [[12,0],[13,0],[14,0]]` and `MessageDigestActivity: [[22,2]]`, with no entry for `MainActivity`. Each activity's own constructor is a boundary target (indices 23–26) at `d = 0`; the exclusion of the class's `<init>`/`<clinit>` keeps those pairs out, which would otherwise be every activity's nearest (`MainActivity: [[23,0]]`, `CipherActivity: [[24,0],…]`, `MessageDigestActivity: [[26,0],…]`). `CryptographyActivity` keeps `d = 0` from direct targets in its own non-constructor methods
- **AND** `handlers` SHALL hold exactly two records, the two classes that declare `onClick(android.view.View)`: `br.unb.cic.cryptoapp.cipher.CipherActivity$1` with `{mop: {click: transitive}}` and no `dist` (its targets lie at `d = 4`) and `br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0` with `{mop: {click: transitive}, dist: {click: [[16,3],[17,3],[18,3]]}}`. `MessageDigestActivity.generateHash(android.view.View)`, an XML `android:onClick` method, and `MainActivity$1.onMenuItemClick` are not handler methods and have no record
- **AND** `optionsMenus` SHALL contain the `MainActivity` record, and the `wtg` map SHALL carry the click edges from `MainActivity` to all three MOP sub-activities
- **AND** `components.activities` SHALL have 4 entries, `providers` 1 entry with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`, every component `reachesMop==false`, and every declared component SHALL be projected although none reaches a target
- **AND** `stats.windows==5`, `stats.widgetsTotal==30`, `stats.flagged==3`, `stats.handlersUnmatched==4`, `stats.syntheticLambda==0`, `stats.recovered==0`, `stats.wtgEdges==16`, `stats.dedupedTransitions==1`. No production consumer reads WTG edge multiplicity, so the deduplicated edge changes no on-device result

#### Scenario: incomplete full JSON refuses to derive
- **WHEN** `derive` runs on a document that is not an object, has a null `package`, or carries a section of the wrong type
- **THEN** `DerivationError` SHALL be raised
- **AND** no artifact file SHALL be written
- **AND WHEN** `derive` runs on a well-typed document whose `complete` is absent or `false` and whose `transitions` is empty
- **THEN** an artifact SHALL be returned, with `wtg == {}` and `stats.wtgEdges == 0`, and with `widgets`, `targets`, `dist`, `activityDist` and `handlers` derived from `reachability`, `windows` and `distanceTargets` exactly as with the sentinel present (rv-android INV-DRV-08)

#### Scenario: no Target vocabulary and no call graph on the wire
- **WHEN** any artifact is generated
- **THEN** the only key containing `Target` SHALL be `hasTargetMethods` on a receiver or service entry (`targets` does not match: the rule's pattern is case-sensitive and names the producer's `Target` vocabulary), and it SHALL contain no `reachability`/`windows`/`transitions`/`listeners`/`distanceTargets` section (INV-DRV-06 as amended)
- **AND** no key and no string value SHALL be a method signature (`<class: type name(args)>`) or a method name; the keys of `handlers` are class names only
- **AND** the assertion SHALL run against a document declaring receivers, services and at least one handler class — over one whose component lists and handler table are empty it is vacuous

#### Scenario: unflagged metadata-less widgets are projected away
- **WHEN** the full JSON contains a widget with no MOP-reaching listener and no `inputType`/`hint`/`prompt`/`spinnerMode`/`contentDescription`/`tooltipText`/`entries`
- **THEN** the artifact SHALL NOT contain that widget
- **AND** an unflagged widget with a non-empty `hint` SHALL be emitted (typed input reads it), without a `dist` map, because a handler that reaches no target has no pair

#### Scenario: a flagged widget with an empty short id still marks its activity
- **WHEN** the only MOP-flagged widget of base activity `C` carries `idName == ""` and its click handler has distance 2 to target 5
- **THEN** the artifact SHALL NOT contain a widget entry for it, and `stats.droppedFlaggedNoId` SHALL count it
- **AND** `mopActivities` SHALL nevertheless contain `C` (INV-DRV-02)
- **AND** `activityDist.C` SHALL include `[5,2]` unless a nearer pair for target 5 or three nearer pairs displace it: the widget is unscorable by id, the activity is not
- **AND** consequently `activityHasMop("C")` SHALL be true on-device, so `C` keeps its WTG score, its frontier weight, its `stateMopDensity` contribution and its place in the launcher census

#### Scenario: Activity sets — the A′ union draws on three sources, distinctly
- **WHEN** a document declares a flagged widget on base activity `A`, a `components.activities[]` entry `B` with `reachesTarget == true`, a `reachability[]` class `C` with `componentType == "activity"` and ≥1 reaching method, and a `components.activities[]` entry `D` with `reachesTarget == false`
- **THEN** `mopActivities` SHALL be `["A"]` and `mopActivitiesAugmented` SHALL be `["A", "B", "C"]`
- **AND** `D` SHALL appear in neither set: the union adds activities that reach a monitored operation, not every declared activity
- **AND WHEN** a `reachability[]` class `E` with `componentType == "activity"` has `<init>` as its only reaching method
- **THEN** `E` SHALL NOT enter `mopActivitiesAugmented` through source 3
- **AND** the three sources SHALL be exercised **distinctly** — one member per source — because they are not redundant: source 2 is a false negative on an activity whose reach passes through a lambda the producer's call graph did not traverse before gh120, and source 3 does not depend on the component-level flag
- **AND** the union SHALL be additive: `mopActivities ⊆ mopActivitiesAugmented` on every document (`mop-guidance` INV-MOP-27)

#### Scenario: deep link derived from the first ACTION_VIEW filter
- **WHEN** an activity declares an intent-filter with `android.intent.action.VIEW` and `data.schemes == ["myapp"]`, `data.hosts == ["detail"]`, `data.paths == ["/x"]`
- **THEN** its emitted `deepLinkUri` SHALL be `"myapp://detail/x"`
- **AND WHEN** the filter declares `ACTION_VIEW` with an empty scheme list, or declares schemes without `ACTION_VIEW`, or the activity declares no intent-filter at all
- **THEN** `deepLinkUri` SHALL be absent, and the activity trigger SHALL fall back to the explicit-component intent
- **AND** the activity's entry SHALL carry no `data` block, no scheme list, and no host or path list (INV-DRV-07): a reduced `data` member appears only on a receiver's or service's intent filter (item 7)

#### Scenario: widget distance is the minimum over its handlers, cut to three
- **WHEN** a widget has two `click` listeners whose handler methods carry `targetDistances` `[[2,3],[4,1],[6,5]]` and `[[2,1],[7,2],[8,2],[9,4]]`
- **THEN** its `dist.click` SHALL be `[[2,1],[4,1],[7,2],[8,2]]`: per target the minimum (`2→1`, `4→1`, `6→5`, `7→2`, `8→2`, `9→4`), then every pair at `d ≤ 3` (the cut is a distance of three calls, not a count), ordered by `d`, ties by `i`
- **AND** the widget's `mop.click` SHALL be the OR of the two handlers' flags, as in format 1

#### Scenario: an absent D8 wrapper takes the recovered lambdas' minimum
- **WHEN** a widget's `click` handler is `X$$ExternalSyntheticLambda1.onClick`, a signature absent from the exact-join index, and class `X` has two reaching methods `lambda$a$0` with `[[3,2]]` and `lambda$b$1` with `[[3,4],[5,3]]`
- **THEN** the widget SHALL be flagged by the class recovery (INV-DRV-01) and its `dist.click` SHALL be `[[3,2],[5,3]]`

#### Scenario: a listed handler class that reaches nothing
- **WHEN** class `com.x.Foo$1` declares `onClick(android.view.View)` with `reachesTarget: false` and no `targetDistances`
- **THEN** `handlers["com.x.Foo$1"]` SHALL be `{mop: {click: none}}`, with no `dist`

#### Scenario: a Compose invoke fills both events
- **WHEN** class `com.x.ui.ScreenKt$Body$1$1` declares `java.lang.Object invoke()` with `reachesTarget: true`, `directlyReachesTarget: false` and `targetDistances` `[[3,2]]`
- **THEN** `handlers["com.x.ui.ScreenKt$Body$1$1"]` SHALL be `{mop: {click: transitive, longclick: transitive}, dist: {click: [[3,2]], longclick: [[3,2]]}}`
- **AND** a class whose only method named `invoke` has another signature (e.g. `invoke(java.lang.Object,java.lang.Object)`), or whose only methods are `<init>`/`<clinit>`, SHALL have no record

#### Scenario: a toggleable's invoke(Object) fills both events
- **WHEN** class `com.x.ui.SettingsKt$Row$1$1` declares `java.lang.Object invoke(java.lang.Object)` (a `ToggleableNode`'s `onValueChange`, which gh121 stamps) with `reachesTarget: true`, `directlyReachesTarget: false` and `targetDistances` `[[5,3]]`
- **THEN** `handlers["com.x.ui.SettingsKt$Row$1$1"]` SHALL be `{mop: {click: transitive, longclick: transitive}, dist: {click: [[5,3]], longclick: [[5,3]]}}`

#### Scenario: a receiver's intent filter keeps its reduced data
- **WHEN** a document declares a receiver `com.x.DeepReceiver` with `reachesTarget: false` and the single intent filter `{"actions": ["android.intent.action.VIEW"], "categories": [], "data": {"schemes": ["myapp"], "hosts": ["detail"], "ports": [], "paths": [], "pathPrefixes": [], "pathPatterns": [], "mimeTypes": []}}`
- **THEN** `components.receivers` SHALL contain `com.x.DeepReceiver` with `reachesMop == false`
- **AND** its projected filter SHALL be `{"actions": ["android.intent.action.VIEW"], "categories": [], "data": {"schemes": ["myapp"], "hosts": ["detail"]}}`, with no `paths`, `pathPrefixes`, `mimeTypes`, `ports` or `pathPatterns` key
- **AND WHEN** a service's filter declares `ports: ["80"]` and `pathPatterns: ["/a.*"]` and every other `data` list empty
- **THEN** that filter SHALL carry no `data` member
- **AND** the artifact SHALL still carry `formatVersion: 2` and `source.generator == "aperv-derive/2"`, and `source.digest` SHALL be the `sha256` of the full-JSON bytes as before

### Requirement: Derived artifact generator — determinism and provenance

`serialize_canonical(artifact)` SHALL emit canonical bytes per INV-DRV-05. Running the generator twice on the same full-JSON bytes — on any host, in any process — SHALL produce byte-identical files, so the artifact's own digest is stable and the `source.digest` chain in the `MOP_DATA` trace record identifies the exact static-analysis input of every run.

#### Scenario: byte-identical regeneration
- **WHEN** `derive` + `serialize_canonical` run twice on the same full JSON
- **THEN** the two outputs SHALL be byte-identical

#### Scenario: provenance digest matches the input
- **WHEN** an artifact is generated from a full JSON whose SHA-256 is `d`
- **THEN** `source.digest` SHALL equal `"sha256:" + d`

---

### Requirement: Full JSON remains the host-side source of truth (R9)

The full static-analysis JSON SHALL remain in `<results_dir>`, byte-identical, as the sole static-analysis input of every metric and analysis pipeline. The frozen metric definitions — *MOP coverage* over `directly_reaches_mop`, *unique misuse* `(app, class, method, specification)`, app-vs-library via the `Mneut` prefix test — SHALL keep reading the full JSON and logcat exclusively; no metric computation SHALL read a `*.mop.json` artifact (INV-DRV-06). The derived artifact is device-input only.

#### Scenario: metrics unaffected by derivation
- **WHEN** the derivation step runs for an app
- **THEN** the full JSON SHALL be unmodified (same bytes, same path)
- **AND** the analysis pipeline's `directly_reaches_mop` set for that app SHALL be computed from the full JSON, identical to the pre-change value

---

### Requirement: Equivalence gate for the parser cutover

Before the jar's full-JSON parser is deleted, a one-shot equivalence gate SHALL demonstrate, over a **designed input set** — the cryptoapp fixture pair plus one synthetic full-JSON fragment per relocated rule the fixture cannot exercise — that for every member the projections served by the **old** parser on the full JSON and by the **new** parser on the derived artifact are identical: widget flag maps (per-event entries and aggregates), widget metadata fields, both MOP-activity sets (flag off and on), OPTIONSMENU-gateway sets (flag off and on), WTG views, component trigger tuples and provider tuples, **the per-activity deep-link URI including its null cases** (INV-DRV-07 — activities are otherwise absent from the tuple comparison, so nothing else would catch a deep-link divergence), and `package`/`mainActivity`. Every synthetic SHALL be derived through the production generator rather than hand-written, so the artifact side of each comparison is never an author's idea of what the generator emits. The gate SHALL run in the repository's ordinary test invocation and SHALL NOT be conditioned on a system property, an environment variable or an external directory: a gate that can be skipped by an unset input is indistinguishable, in a green build, from a gate that ran. The gate is the merge condition for the cutover commit; the gate code, its synthetics, and the old parser it needs as oracle are deleted immediately after it passes (P3 — the permanent protection is the per-rule generator unit tests plus the jar fixture tests).

The gate SHALL cover each relocated rule with at least one input-set member that **fires** it — flagged-and-dropped empty-id widgets (INV-DRV-02), recovered synthetic-lambda handlers including the negative case (INV-DRV-01), re-keyed dialogs with host promotion (INV-DRV-03), an A′ union differing from the widget-derived set (INV-DRV-06), and the deep-link URI with its null cases (INV-DRV-07) — and SHALL fail when any rule has no member that fires it. Equality over inputs that never trigger a rule is not evidence for that rule. A synthetic authored for a rule but deriving to an artifact where the rule did not fire is a defect in the synthetic, not coverage of the rule.

**Scope, and what it deliberately does not establish** (owner decision 2026-08-05): this gate was specified over the pinned 345-application corpus via `-Dmop.corpusDir`, and that run does not occur — the APE-RV side executes once, in `gh97-rearch-ab-gate`, which is a merge gate rather than a cutover gate. The generator's exposure to real producer documents is established separately and already: `gh96` derived all 345 with no crash and no refusal, and its recorded totals were independently reproduced. What no longer has evidence is the *jar-side* comparison over unanticipated real-application shapes; that residual is carried by the permanent Python suite and, indirectly, by `gh97`'s campaign. The corpus is not a precondition of this requirement and MUST NOT be reinstated as one without a decision that reverses the above.

#### Scenario: equivalence over the designed input set
- **WHEN** the equivalence gate runs as part of the ordinary test invocation, with no property or directory supplied
- **THEN** every member of the input set SHALL compare equal on every projection listed above
- **AND** any inequality SHALL fail the gate naming the member and the first differing projection
- **AND** each relocated rule SHALL have at least one member that fires it, the gate failing when one does not

#### Scenario: gate is a cutover artifact, not a permanent test
- **WHEN** the cutover commit lands
- **THEN** the repository SHALL contain neither the full-JSON parser, nor the gate test, nor its synthetic full-JSON fragments
- **AND** the permanent suites (generator unit tests, jar fixture tests) SHALL cover every relocated invariant (INV-DRV-01..04, INV-DRV-06, INV-DRV-07)
