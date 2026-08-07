# Specification: Static Analysis Entry Points

## Purpose

The RVSEC static analysis pipeline (rvsec-gator) produces a JSON file per APK containing reachability data, window/widget structure, and WTG transitions. That file is the **host-side** input from which the rv-android generator derives the compact artifact the jar loads; the device never reads it directly (see `mop-guidance`).

`RvsecAnalysisClient.getEntryPoints()` enumerates Activities, Services (`onStartCommand`, `onBind`, `onDestroy`) and BroadcastReceivers (`onReceive`) as entry points, so methods reachable only through a non-GUI component are traversed rather than dropped from the call graph — which is what makes their MOP reachability visible at all. Receivers reach the enumeration through `getReceivers()` on the `XMLParser` interface, alongside the `getServices()` that was already there; both read the `<service>` and `<receiver>` tags, with their IntentFilters, that `DefaultXMLParser` parses from AndroidManifest.xml.

This specification defines which components are entry points, how they appear in the JSON output, and what the runtime needs from them — the intent surface `component-triggering` dispatches against, and the activity reachability the A′ union draws on as one of its three sources.

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

`derive_mop_artifact.derive(document)` SHALL produce a `formatVersion: 1` artifact containing exactly the projection the explorer consumes, per the consumption inventory in this change's `design.md`:

1. **Scalars**: `package`, `mainActivity` copied verbatim (they feed the T1.7 strict-match check and trigger `ComponentName` construction, INV-CT-04).
2. **Provenance**: `source.digest` (`sha256:<hex>` of the full-JSON bytes), `source.file` (basename), `source.generator` (generator id/version).
3. **Widgets** (`widgets.<baseActivity>.<shortId>`): per-normalized-eventType MOP map with values `none|direct|transitive|both` (INV-DRV-01; keys pre-normalized — lowercase, `_`/`-` stripped), plus the consumed metadata fields `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, each emitted only when non-empty. A widget SHALL be emitted only when it is MOP-flagged OR carries at least one metadata field (an unflagged, metadata-less widget is unreachable through every production query). `id`, `type`, `text`, and raw `listeners` SHALL NOT be emitted.
4. **Activity sets**: `mopActivities` — derived from the **MOP-flagged widgets**, not from the widgets that survive into the emitted map: a flagged widget contributes its base activity even when an empty short id keeps the widget itself off the wire (INV-DRV-02), and the dialog merge promotes a flagged dialog's host (INV-DRV-03) — and `mopActivitiesAugmented` (A′ 3-source union: widget-derived ∪ `components.activities[].reachesTarget==true` ∪ `reachability[]` classes with `componentType=="activity"` and ≥ 1 reaching method) — both always emitted, so the on-device `mopActivitySourceComponents` flag keeps selecting at run time.
5. **OPTIONSMENU records**: `optionsMenus: [{activity, hasFlaggedWidget}]`, one per **distinct base activity** owning an `OPTIONSMENU`-type window, `activity` = base activity of the window name, `hasFlaggedWidget` = the OR, across that activity's menu windows, of "any widget parsed from the window is MOP-flagged" — tested before the empty-short-id drop, since an id-less menu item still makes the menu a gateway. Merging per activity rather than emitting one record per window is observationally identical, because the on-device recompute adds the activity when *any* of its records qualifies, and it removes an ordering question from the wire: two records for one activity would need a defined order for the bytes to be canonical. It also matches the singular "its record" the `mop-guidance` delta already assumes. The pinned corpus has zero apps with two `OPTIONSMENU` windows on one base activity, so this hardens against a shape the gate cannot exercise rather than resolving a live divergence. (The gateway *set* is recomputed on-device from these records + WTG + the selected activity set, keeping INV-MOP-13 flag-sensitive.)
6. **WTG**: `wtg.<sourceBaseActivity> = [{widget, target}]` per INV-DRV-03; `widget` is the transition event's `widgetName`; `widgetClass` SHALL NOT be emitted (zero production readers).
7. **Components**: `activities[]` (`className`, `isMain`, `permission`, `reachesMop`, `deepLinkUri` per INV-DRV-07), `receivers[]`/`services[]` (adds `intentFilters` with `actions`+`categories` only, and `hasTargetMethods`), `providers[]` (adds `authorities`). `reachesMop` is the D7 rename of the wire `reachesTarget`. The D15 `data` block itself, `readPermission`/`writePermission`, `targetMethods` signature lists, and `exported` SHALL NOT be emitted — but the `data` block's one production use is preserved as the derived `deepLinkUri` string; the rest remains available host-side in the full JSON.
8. **Stats**: `windows`, `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `wtgEdges`, `dedupedTransitions` (INV-DRV-04).

Derivation preconditions: `document["complete"] == true` and a non-null `package`; otherwise `DerivationError` (a truncated analysis SHALL never yield an artifact — the completeness sentinel becomes a generation precondition instead of a device-side check).

#### Scenario: cryptoapp derivation matches the known ground truth
- **WHEN** `derive` runs on `cryptoapp.apk.gh60-fresh.json`
- **THEN** the artifact SHALL flag exactly `buttonGenerateHash`, `btn_cipher_encrypt` and `executeButton` (all `transitive` on their click events; no `direct` flag anywhere)
- **AND** `mopActivities` SHALL equal `{MessageDigestActivity, CipherActivity, CryptographyActivity}` (base names)
- **AND** `executeButton` SHALL be flagged **only** through the D8 recovery: its handler `CryptographyActivity$$ExternalSyntheticLambda0:onClick` has no exact `reachability[]` signature, and the flag comes from the enclosing class's reaching `lambda$setupExecuteButton$0`. This makes the fixture a live test of INV-DRV-01's recovery rather than only of the exact-join path — a derivation that skipped the recovery would still satisfy every other clause of this scenario
- **AND** `optionsMenus` SHALL contain the `MainActivity` record, and the `wtg` map SHALL carry the click edges from `MainActivity` to both MOP sub-activities
- **AND** `components.activities` SHALL have 4 entries, `providers` 1 entry with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`, every component `reachesMop==false`
- **AND** `stats.windows==5`, `stats.widgetsTotal==30`, `stats.flagged==3`, `stats.handlersUnmatched==5`, `stats.syntheticLambda==1`, `stats.recovered==1`
- **AND** `stats.wtgEdges==16` with `stats.dedupedTransitions==1` — the jar's own load record on the same raw fixture reports `wtgEdges=17`, because it keeps an exact-duplicate `(widget, target)` edge that this derivation removes. That difference is deliberate and unobservable: **no production consumer reads WTG edge multiplicity** (`MopScorer.scoreWtg` and `StatefulAgent.frontierBoost` return on the first match; `FrontierPass`, `MopFrontierPass` and `qualifyingMopTargets` accumulate into sets), so the gate of the "Equivalence gate for the parser cutover" requirement SHALL compare WTG views as sets. `wtgEdges` itself is a pure counter under INV-DRV-04 and steers nothing

#### Scenario: incomplete full JSON refuses to derive
- **WHEN** `derive` runs on a document with `complete` absent or `false`
- **THEN** `DerivationError` SHALL be raised
- **AND** no artifact file SHALL be written

#### Scenario: no Target vocabulary and no call graph on the wire
- **WHEN** any artifact is generated
- **THEN** the only key matching `*Target*` SHALL be `hasTargetMethods` on a receiver or service entry, and it SHALL contain no `reachability`/`windows`/`transitions`/`listeners` section (INV-DRV-06). The assertion SHALL run against a document declaring receivers and services — over one whose component lists are empty it is vacuous

#### Scenario: unflagged metadata-less widgets are projected away
- **WHEN** the full JSON contains a widget with no MOP-reaching listener and no `inputType`/`hint`/`prompt`/`spinnerMode`/`contentDescription`/`tooltipText`/`entries`
- **THEN** the artifact SHALL NOT contain that widget
- **AND** an unflagged widget with a non-empty `hint` SHALL be emitted (typed input reads it)

#### Scenario: a flagged widget with an empty short id still marks its activity
- **WHEN** the only MOP-flagged widget of base activity `C` carries `idName == ""`
- **THEN** the artifact SHALL NOT contain a widget entry for it, and `stats.droppedFlaggedNoId` SHALL count it
- **AND** `mopActivities` SHALL nevertheless contain `C` (INV-DRV-02)
- **AND** consequently `activityHasMop("C")` SHALL be true on-device, so `C` keeps its WTG score, its frontier weight, its `stateMopDensity` contribution and its place in the launcher census — exactly as it does today

#### Scenario: Activity sets — the A′ union draws on three sources, distinctly
- **WHEN** a document declares a flagged widget on base activity `A`, a `components.activities[]` entry `B` with `reachesTarget == true`, a `reachability[]` class `C` with `componentType == "activity"` and ≥1 reaching method, and a `components.activities[]` entry `D` with `reachesTarget == false`
- **THEN** `mopActivities` SHALL be `["A"]` and `mopActivitiesAugmented` SHALL be `["A", "B", "C"]`
- **AND** `D` SHALL appear in neither set: the union adds activities that reach a monitored operation, not every declared activity
- **AND** the three sources SHALL be exercised **distinctly** — one member per source — because they are not redundant. Source 2 alone is a false negative on any activity whose reach passes through a D8-desugared lambda handler, which the producer's call graph does not traverse; source 3 is the only one immune to that gap, and it is why the union has three members rather than two (device-verified on cryptoapp: all 4 activities report `reachesTarget=false` while `reachability[]` marks `CryptographyActivity` with 13 reaching methods)
- **AND** the union SHALL be additive: `mopActivities ⊆ mopActivitiesAugmented` on every document, so the on-device flag selects between a set and a superset of it and can never subtract (`mop-guidance` INV-MOP-27)

#### Scenario: deep link derived from the first ACTION_VIEW filter
- **WHEN** an activity declares an intent-filter with `android.intent.action.VIEW` and `data.schemes == ["myapp"]`, `data.hosts == ["detail"]`, `data.paths == ["/x"]`
- **THEN** its emitted `deepLinkUri` SHALL be `"myapp://detail/x"`
- **AND WHEN** the filter declares `ACTION_VIEW` with an empty scheme list, or declares schemes without `ACTION_VIEW`, or the activity declares no intent-filter at all
- **THEN** `deepLinkUri` SHALL be absent, and the activity trigger SHALL fall back to the explicit-component intent — the same outcome `buildDeepLinkUri` returning null produces today
- **AND** the artifact SHALL carry no `data` block, no scheme list, and no host or path list (INV-DRV-07)

---

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

