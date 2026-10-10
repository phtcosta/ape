# Specification: MOP-Guided Action Scoring

## Purpose

MOP guidance steers APE's action priorities toward widgets and activities that reach monitored operations (MOP specs). `ape.mopDataPath` points at the **compact MOP artifact, format 2**, on the device, and the jar reads that format only (INV-MOP-34): besides the format-1 flags, activity sets, menus, WTG and components, it carries the target count `targets`, per-widget distance pairs `dist` (`[i, d]`, every target at `d ≤ 3`), per-activity pairs `activityDist` (the three nearest targets) and a `handlers` table from handler class to flags and pairs. `MopData` loads it, and one lookup, `MopData.lookup`, answers what MOP knows about a node for an event: the handler stamp's class when `handlers` lists it, else the resource-id key (INV-MOP-36). `MopScorer` turns that answer into a priority boost in one of two modes selected by `ape.mopScoring`: `flag` (default), the direct/transitive mark, or `distance`, a weight by the nearest unretired target, with targets retiring after a number of first interactions (INV-MOP-37). The boosts are applied by the scoring passes (`MopWidgetPass`, `MopFrontierPass`, `MenuGatewayPass`, `WtgPass`) that run inside `StatefulAgent.adjustActionsByGUITree()`, after the base SATA priority is assigned and before the agent selects.

**The jar derives nothing.** The cross-referencing that identifies which widgets reach a monitored operation — call-graph reachability and distances, listener-to-handler joins, collision resolution between widgets sharing a short id — happens **host-side**, in the rv-android generator that produces the artifact. What reaches the device is the result: per `(baseActivity, shortId)` a widget carries a per-event `mop` flag distinguishing a direct from a transitive reach and its per-event distance pairs, a handler class carries the same for its events, activities arrive as two precomputed sets with their distance pairs, and the trigger surface arrives as declared fields. So the artifact has no `windows[]`, no `reachability[]` and no `transitions[]` to parse, and a **legacy full static-analysis JSON is rejected rather than parsed**, as is a format-1 artifact: the version gate refuses both.

**Absence is fatal on a MOP arm, deliberately.** An arm asking for MOP guidance whose artifact is missing or underivable fails its task instead of continuing as plain `sata` (INV-APERV-05). Silently degrading to bare APE while still reporting as a MOP arm is the failure class this capability exists to prevent: it produces a run that looks like treatment and behaves like control. A non-MOP arm simply never names an artifact and never loads one.

Two things stay computed on the device on purpose. The **OPTIONSMENU gateway** is recomputed at runtime from the artifact's menu and activity-set views rather than shipped precomputed (INV-MOP-13), because whether the gateway opens depends on which activity set the arm selected. And the **activity set is chosen at load**: `mopActivities` (widget-derived) or `mopActivitiesAugmented` (the A′ union of three sources), under `ape.mopActivitySourceComponents` — the same set that feeds the cadence launcher in `component-triggering`, which is what makes the two arms' launched sets differ.

---

## Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the **derived compact MOP artifact** (`formatVersion: 2`) produced host-side by `aperv-tool` and build the typed query model:

1. **Version gate**: the top-level `formatVersion` field is mandatory and SHALL equal `2` (`MopData.SUPPORTED_FORMAT_VERSION`, `MopData.java:149`, gate `:207-213`). A missing value, `1`, or any other value ⇒ `status=rejected reason=version-mismatch`, null return (INV-MOP-34). There is no reader for format 1 (design D15, SDD-P3): a format-1 `.mop.json`, derived before the bump or served by a stale host cache, makes a MOP arm abort (INV-MOP-22). The version gate replaces the `"complete": true` sentinel of the full-JSON era (INV-MOP-09).
2. **Top-level scalars**: `getPackageName()`, `getMainActivity()` from the artifact's `package` / `mainActivity`, and the target count from `targets` (a non-negative integer; the number of entries of the producer's `distanceTargets`, direct targets followed by boundary targets). Every distance pair indexes this count. No target signature and no target kind is on the wire, and the jar does not distinguish boundary targets from direct ones (design D15, "B weighs as C"). A format-2 artifact that lacks `targets` or `handlers`, or whose `targets` is not an integer, SHALL be rejected with `status=rejected reason=parse-error` and a null return, so a MOP arm aborts (INV-MOP-22): the two members are always emitted (`static-analysis-entrypoints`), and their absence is a generator↔jar skew, not an empty table. A `targets` of `0` is valid and loads in both modes: a document without `distanceTargets` (a pre-gh120 `.apk.json`, which no guard stops, design D15) or an empty one derives to it. The run SHALL continue without distance data (under `distance` every weight is 0 and the launcher order has no key), and `MOP_DATA.targets` reports `0`. The jar has no special treatment for it (author, 2026-10-08).
3. **Widgets**: `getWidget(activity, shortId)` serves the artifact's `widgets` map (`baseActivity → shortId → widget`). Each `Widget` carries the consumed metadata fields — `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (each null/empty when absent on the wire) — and the MOP flags decoded from the wire `mop` map (normalized eventType → `none|direct|transitive|both`): `directMopByEventType`/`transitiveMopByEventType` are populated with an entry for **every** wire key (including `none` entries — key presence drives the per-event vs aggregate fallback), and the aggregate `directMop`/`transitiveMop` are the OR across the map values. `Widget.isDirectMop`/`isTransitiveMop` and their normalization-and-fallback semantics (INV-MOP-08, INV-MOP-14) are unchanged. A widget additionally carries its distance pairs from the wire `dist` map (normalized eventType → `[[i, d], …]`), stored per normalized event in the order the wire gives them (ascending `d`, then `i`; the generator bounds a widget's or handler's list to the targets at `d ≤ 3` and an `activityDist` entry to three pairs, and the loader reads any length). An absent `dist` means no pair for any event. The widget also keeps its click aggregate, the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (`MopData.mergeByMinimum`), which `lookup` reads for a click when the widget's `mop` map has no `click` key ("Handler Stamp Lookup"). Fields with no production consumer (`id`, `type`, `text`, raw `listeners`) SHALL NOT exist on the wire or in the model.
4. **Activity distances**: the wire `activityDist` map (`baseActivity → [[i, d], …]`) SHALL be loaded as a per-activity pair list in wire order, served to the launcher (`component-triggering`, design D20). An activity absent from the map has no pair.
5. **Handler table**: the wire `handlers` map (binary class name → `{mop: {event: flag}, dist: {event: [[i, d], …]}}`) SHALL be loaded as a per-class record, with the event keys normalized and the flags decoded as for widgets. A listed class with `mop` `none` and no `dist` SHALL be kept: it states that the handler reaches no target, and "Handler Stamp Lookup" relies on it. The table is read only by `MopData.lookup`.
6. **MOP-activity sets**: `mopActivities` and `mopActivitiesAugmented` are read as-is from the wire. `getMopActivities()` / `activityHasMop(activity)` SHALL serve the augmented set when `Config.mopActivitySourceComponents == true` and the widget-derived set otherwise (INV-MOP-27's selection semantics, now between two precomputed sets). Their meaning is format 1's, except that the generator's A′ source 3 no longer counts an activity class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`); the distance data does not change them.
7. **OPTIONSMENU gateways**: `activityHasMopOptionsMenu(activity)` SHALL be computed at load time from the artifact's `optionsMenus[]` records (`{activity, hasFlaggedWidget}`) and the loaded WTG view: an activity qualifies iff its record has `hasFlaggedWidget == true` OR any WTG click edge from that activity targets an activity in the *selected* MOP-activity set (same two-condition rule as before, INV-MOP-13).
8. **WTG**: `hasWtgData()` / `getWtgTransitions(activity)` serve the wire `wtg` map (`sourceBaseActivity → [{widget, target}]`, already base-activity-keyed and deduplicated host-side per INV-WTG-04). `WtgTransition` carries `widgetName` and `targetActivity`; the `widgetClass` field is deleted (zero production readers).
9. **Components**: each entry carries `className`, `componentType` (from the parent array key), `isMain`, `permission` (null when no gate), `reachesMop` (wire name — the D7 rename of `reachesTarget`); activities additionally carry `deepLinkUri` (null when absent on the wire); receivers/services carry `intentFilters` (`actions` + `categories` only) and `hasTargetMethods` (boolean — the only consumed use of the former signature list is an emptiness test); providers additionally carry `authorities`. The D15 `data` block of the gh60 producer, `readPermission`/`writePermission`, the `targetMethods` signature list, and `exported` SHALL NOT exist on the wire (they remain in the host-side full JSON). `exported` leaves with them because no jar code path reads it and none may: the launcher's eligibility rule forbids consulting it, so its absence makes that prohibition structural rather than merely stated.

   `MopLauncherStage.buildDeepLinkUri` is deleted with the filter structure it walked: the activity launcher SHALL read `ComponentInfo.deepLinkUri` directly and pass it to `ActivityTriggerAction` unchanged, so `MonkeySourceApe`'s dispatch — `Intent.ACTION_VIEW` + `Uri.parse(uri)` when non-null, explicit component otherwise — keeps its exact behavior with the assembly rule living in the generator (INV-DRV-07).
10. **Sanity check**: `package`/`mainActivity` comparison per the "MopData — Package / MainActivity Sanity Check" requirement (unchanged semantics).
11. **Stats echo**: the artifact's `stats` block (generator-computed diagnostics: `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, handler-join counters, `wtgEdges`) is carried through to the load status record without recomputation; it SHALL NOT influence any query result or the load outcome (the discipline of the former INV-MOP-31/32).

**Malformed distance pairs.** A pair, in `dist`, `activityDist` or `handlers`, that is not a two-integer array, whose index lies outside `[0, targets)`, or whose distance is negative, SHALL be dropped at load and counted (`droppedPairs` on the load record, "MopData — Load Status Line and Fail-Fast"). The load SHALL continue on the remaining pairs and SHALL NOT be rejected for it (design, Error Handling). A conforming generator emits no such pair, so a non-zero count is a generator↔jar skew signal.

Unknown JSON keys within `formatVersion: 2` are ignored (INV-MOP-11). This tolerance is not a compatibility affordance: it is the `org.json` DOM's default behavior, the *absence* of a check, so rejecting unknown keys would mean writing new code. Generator and jar ship together, so an unknown key is a skew signal, not a version to accommodate. The file is read once with a single pre-sized allocation and parsed once into an `org.json` DOM. No cross-referencing, flag or distance derivation, dialog re-keying, activity augmentation, or transition processing happens on-device (INV-MOP-35); those are generator requirements (`static-analysis-entrypoints` "Derived compact MOP artifact — projection contents").

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so every header of the main spec is kept, with its body restated for format 2. Several headers were written about the full-JSON parser and read oddly against a body about the compact reader; they are kept to be matched, not as evidence that anything parses a call graph on the device. The two package-sanity headers belong to the "MopData — Package / MainActivity Sanity Check" requirement, and each body says so.

#### Scenario: Real cryptoapp fixture loads every typed field
- **WHEN** `MopData.load()` is called on the `cryptoapp.apk.mop.json` fixture, format 2, derived by the rv-android generator (`gh122`) from the gh120 fixture `modules/rv-static-analysis/tests/resources/cryptoapp.apk.json` (task 10.1)
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getPackageName()=="br.unb.cic.cryptoapp"`, `getMainActivity()=="br.unb.cic.cryptoapp.MainActivity"`, and the target count SHALL be 27 (23 direct targets, then 4 boundary targets, the four activity constructors)
- **AND** the MOP-flagged widgets SHALL be exactly three: `buttonGenerateHash` (MessageDigestActivity), `btn_cipher_encrypt` (CipherActivity), and `executeButton` (CryptographyActivity) — all `transitiveMop==true`, `directMop==false`
- **AND** their `click` pairs SHALL be `[[22,2]]`, none and `[[16,3],[17,3],[18,3]]` respectively: `btn_cipher_encrypt`'s nearest targets, 0 and 1, lie at `d = 4`, beyond the generator's `d ≤ 3` cut, so the widget carries no `dist`, and `executeButton` carries every target of its wrapper at `d ≤ 3`. `executeButton` is flagged through the exact join: under gh120 its wrapper `CryptographyActivity$$ExternalSyntheticLambda0.onClick` is listed in `reachability[]` reaching, because the producer links it to its own `lambda$setupExecuteButton$0$CryptographyActivity(android.view.View)` body, so the class recovery is not consulted (rv-android INV-DRV-09)
- **AND** the handler table SHALL list exactly `br.unb.cic.cryptoapp.cipher.CipherActivity$1` and `br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0`, the two classes of the fixture that declare `onClick(android.view.View)`, each with `click` `transitive` and the same pairs as its widget (none for `CipherActivity$1`)
- **AND** `activityHasMop` SHALL be true for `MessageDigestActivity`, `CipherActivity` and `CryptographyActivity`, and false for `MainActivity` (default flag state). With `mopActivitySourceComponents=true` it SHALL still be false for `MainActivity`: under gh120 its only reaching method is its constructor, which reaches boundary target 23, and the A′ source 3 leaves out a class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`), so on this fixture `mopActivitiesAugmented` equals `mopActivities`
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway via WTG edges to the MOP sub-activities, INV-MOP-13)
- **AND** the `spinnerMessageDigest` widget SHALL carry its 13 `entries` and its metadata fields
- **AND** `getActivities().size()==4`; `getProviders().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `getReceivers().isEmpty()`; `getServices().isEmpty()`; every component `reachesMop==false`

#### Scenario: Legacy full static-analysis JSON is rejected
- **WHEN** `MopData.load()` is pointed at a full static-analysis JSON (top-level `windows`/`reachability`, no `formatVersion`)
- **THEN** `load` SHALL return null
- **AND** emit exactly one `status=rejected reason=version-mismatch` record
- **AND** with `ape.mopDataPath` set, the run SHALL abort via `StopTestingException` (INV-MOP-22) — never proceed as pure SATA
- **AND WHEN** `MopData.load()` is pointed at a format-1 compact artifact (`"formatVersion": 1`)
- **THEN** the outcome SHALL be the same `version-mismatch` rejection and, with `ape.mopDataPath` set, the same abort (INV-MOP-34)

#### Scenario: Per-event-type reachability maps built
- **WHEN** a wire widget has `"mop": {"click": "both", "scroll": "none"}`
- **THEN** `isDirectMop("click")==true` and `isTransitiveMop("click")==true`; `isDirectMop("scroll")==false` and `isTransitiveMop("scroll")==false` (explicit `none` entry, no aggregate fallback)
- **AND** `isDirectMop("longClick")==true` (key absent ⇒ aggregate fallback; aggregates are the OR of the respective bits over the map)
- **AND** the wire values decode to the two bits positionally (`none`=00, `direct`=10, `transitive`=01, `both`=11)
- **AND** the loader SHALL decode `direct` faithfully rather than rejecting it, even though a conforming generator never emits it: `direct` implies `transitive` at derivation time (INV-DRV-01)
- **AND WHEN** the same widget carries `"dist": {"click": [[2,0],[5,1]]}`
- **THEN** its `click` pairs SHALL be `[[2,0],[5,1]]`, in that order, and its `scroll` pairs SHALL be empty (explicit `none` entry, no aggregate fallback)
- **AND** its `longClick` pairs SHALL be empty: only a click falls back to an aggregate of pairs, and only when the widget has no `click` key

#### Scenario: Flag-selected MOP-activity set
- **WHEN** the artifact carries `mopActivities=["A"]` and `mopActivitiesAugmented=["A","B"]`
- **THEN** with `Config.mopActivitySourceComponents=false`, `activityHasMop("B")==false` and `getMopActivities()` SHALL equal `{"A"}`
- **AND** with the flag true, `activityHasMop("B")==true` and `getMopActivities()` SHALL equal `{"A","B"}`
- **AND** the OPTIONSMENU-gateway recompute SHALL use the selected set for its condition-2 test
- **AND** neither set SHALL depend on `activityDist`

#### Scenario: Deep-link dispatch reads the wire field
- **WHEN** the artifact's `components.activities[]` entry for `X` carries `"deepLinkUri": "myapp://detail/x"` and the MOP stagnation launcher selects `X`
- **THEN** the `ActivityTriggerAction` SHALL carry that string verbatim, and the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` targeted at the package via `setPackage`, the platform resolving the handling activity (INV-CT-07 dispatch unchanged)
- **AND WHEN** the entry omits `deepLinkUri`
- **THEN** the intent SHALL be the explicit-component intent
- **AND** no jar code path SHALL read an intent-filter `data` block, because none exists on the wire

#### Scenario: Absent metadata stays null and costs zero tokens
- **WHEN** a wire widget omits `hint`, `prompt`, `entries`
- **THEN** the corresponding `Widget` fields SHALL be null / empty
- **AND** `ApePromptBuilder` metadata rendering and `ApeAgent.generateInputText` SHALL behave exactly as they do for null/empty fields (INV-MOP-10 unchanged)

#### Scenario: Unknown keys in a v1 artifact are ignored
- **WHEN** a `formatVersion: 2` artifact carries an additional unknown top-level, per-widget or per-handler key
- **THEN** `load` SHALL succeed and ignore it (INV-MOP-11)
- **AND** a `formatVersion: 1` artifact SHALL NOT reach this tolerance: it is rejected by the version gate before any key is read (INV-MOP-34). The header names v1 only because the archive matches on it

#### Scenario: gh60 D15 component trigger-surface fields parsed
- **WHEN** `MopData.load()` parses an artifact's `components` block
- **THEN** the trigger surface SHALL be exactly `permission` (null when no gate, `hasPermissionGate()` false), `intentFilters` carrying `actions` and `categories` only, `hasTargetMethods` on receivers and services, and `authorities` on providers
- **AND** `IntentFilter.data`, `DataSpec`, `ProviderInfo.readPermission`/`writePermission`, the `targetMethods` signature list and `exported` SHALL NOT exist on the wire **or on the model**
- **AND** trigger *selection* (INV-MOP-15) SHALL be unchanged: `buildTriggerTuples` reads `reachesMop`, `intentFilters[].actions` and the emptiness of the target-method list, and `buildProviderTuples` reads `reachesMop` and `authorities`
- **AND** the absence of `exported` SHALL keep the launcher's prohibition on consulting it structural (`component-triggering` INV-CT-06)

#### Scenario: Bug-fix regression — widget transitiveMop derived from gh60 Target keys
- **WHEN** `MopData.load()` is called on the format-2 cryptoapp fixture
- **THEN** the widget `buttonGenerateHash` in `MessageDigestActivity` SHALL have `transitiveMop==true`, `directMop==false` and `isTransitiveMop("click")==true`
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true`
- **AND** under `ape.mopScoring=flag`, a click action on an unstamped `buttonGenerateHash` node SHALL score `mopWeightTransitive` (300), through `MopData.lookup` with source `ID`
- **AND** the gateway SHALL hold: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **NOTE**: this scenario is the contract that "SATA-MOP is not silently bare APE" on this side. The derivation it used to assert is the generator's (INV-DRV-01) and is tested in rv-android. What the jar can still fail at, and asserts here, is decoding a flag that arrived correct into a boost that fires

#### Scenario: Widget metadata extracted on post-task-11 fixture
- **WHEN** `MopData.load()` is called on the format-2 cryptoapp fixture
- **THEN** `getWidget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "editTextMessageDigest")` SHALL carry `inputType=="textPersonName"` and `hint=="Input text ..."`
- **AND** the Spinner widget `spinnerMessageDigest` SHALL carry `entries.size()==13`, the JCA algorithm list
- **AND** `type` and `text` SHALL NOT be asserted, because they do not exist on the wire (INV-MOP-35)

#### Scenario: Top-level package and mainActivity sanity check (default warn-only)
- **WHEN** `MopData.load(path, "x.y.z.OTHER", null)` is called and the artifact's `package=="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null and a `WARN` log line SHALL be emitted naming both the expected and the parsed value
- **AND** this claim is owned by the "MopData — Package / MainActivity Sanity Check" requirement (scenario `Default warn-only on mismatch`), whose semantics this change leaves unchanged

#### Scenario: Package mismatch rejected in strict mode
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package=="x.y.z"`
- **THEN** `load` SHALL return `null`, a `WARN` log line SHALL be emitted, and the load record SHALL carry `reason=package-mismatch`
- **AND** the owning requirement is "MopData — Package / MainActivity Sanity Check" (scenario `Strict-mode rejection on mismatch`)

#### Scenario: OPTIONSMENU window with MOP widget triggers activityHasMopOptionsMenu
- **WHEN** the artifact's `optionsMenus[]` carries `{activity: "com.x.A", hasFlaggedWidget: true}`
- **THEN** `activityHasMopOptionsMenu("com.x.A")` SHALL return `true` (condition 1, INV-MOP-13)
- **AND** the qualification SHALL be computed at load from that record, not read from a precomputed gateway set on the wire, because condition 2 depends on the flag-selected activity set

#### Scenario: OPTIONSMENU window without MOP widget does not trigger
- **WHEN** the record for `com.x.B` carries `hasFlaggedWidget: false` AND no WTG click edge from `com.x.B` targets an activity in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.B")` SHALL return `false`

#### Scenario: OPTIONSMENU gateway — menu item navigates to a MOP activity
- **WHEN** the record for `com.x.C` carries `hasFlaggedWidget: false` but the wire `wtg["com.x.C"]` contains an edge whose `target` is `com.x.CryptoActivity`, and `com.x.CryptoActivity` is in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.C")` SHALL return `true` (condition 2, the gateway case)
- **AND** the same artifact SHALL yield `false` for `com.x.C` under `mopActivitySourceComponents=false` when `com.x.CryptoActivity` is present only in `mopActivitiesAugmented`

#### Scenario: Multiple listeners to the same handler do not double-count
- **WHEN** a widget's wire `mop` map carries `{"click": "direct"}`
- **THEN** a click action on that widget's unstamped node under `ape.mopScoring=flag` SHALL score `mopWeightDirect` exactly, never a multiple of it
- **AND** the double-count SHALL be unrepresentable: `listeners` do not exist on the wire, the `mop` map holds one value per normalized event type, and the `dist` list holds each target index at most once, so there is no multiplicity for the jar to fold

#### Scenario: Complete-but-empty JSON parses cleanly (gh51-D5 timeout bucket)
- **WHEN** `MopData.load()` is called on a `formatVersion: 2` artifact with `targets: 0` whose `widgets`, `wtg`, `optionsMenus`, `activityDist`, `handlers`, activity sets and component lists are all empty
- **THEN** the returned `MopData` SHALL be non-null
- **AND** every accessor SHALL return empty (`getReceivers()`, `getServices()`, `getActivities()`, `getProviders()`, `getMopActivities()`, `getWtgTransitions(any)`) and `hasWtgData()` SHALL be `false`
- **AND** `MopData.lookup(any, any node, any event)` SHALL return `NONE` and every action SHALL score `0` in both scoring modes, without `NullPointerException`
- **AND** there SHALL be no `isComplete()` and no `complete` sentinel: what reaches the device is governed by the generator's preconditions (`static-analysis-entrypoints`)

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** two widgets of one base activity resolve to the short id `"submit"`, one `direct`-flagged on `click` with pairs `[[3,0]]` and one `transitive`-flagged on `click` with pairs `[[3,1],[5,2]]`
- **THEN** the artifact SHALL carry exactly one entry under `widgets.<activity>.submit`, and `getWidget(activity, "submit")` SHALL serve the `direct` flag
- **AND** its `click` pairs SHALL be `[[3,0],[5,2]]`, the merge by the minimum distance per target (INV-MOP-19 as amended)
- **AND** the resolution SHALL have happened host-side; on this side a JSON object admits one value per key, so the collision is unrepresentable

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the same two widgets appear in the reverse order in the producer's document
- **THEN** the artifact, and therefore `getWidget(activity, "submit")` and its pairs, SHALL be identical to the previous scenario's
- **AND WHEN** a third, unflagged widget with no pairs also resolves to `"submit"`, in any position
- **THEN** the entry SHALL keep the `direct` flag and the pairs `[[3,0],[5,2]]`
- **AND** the order-independence SHALL be the generator's, reinforced by INV-DRV-05: two orderings that derived to different artifacts would break byte-level determinism before they reached the jar

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity's only MOP-flagged widget carries an empty `idName`
- **THEN** the artifact SHALL carry no entry under the empty-string key for that activity, and `getWidget(activity, "")` SHALL return null
- **AND** `activityHasMop(activity)` SHALL still return `true`, and the drop SHALL be counted in `stats.droppedFlaggedNoId`, echoed on the load record as `droppedNoId`
- **AND** a node of that widget that carries a handler stamp listed in `handlers` SHALL still be resolved by `MopData.lookup` with source `STAMP`: the stamp needs no resource id

#### Scenario: Malformed distance pair dropped and counted
- **WHEN** a `formatVersion: 2` artifact with `targets: 27` carries a widget with `"dist": {"click": [[16,3],[27,1],[-1,2]]}`
- **THEN** `load` SHALL succeed, the widget's `click` pairs SHALL be `[[16,3]]`, and the load record SHALL carry `droppedPairs=2`

#### Scenario: Format-2 artifact missing a format-2 member is rejected
- **WHEN** a `formatVersion: 2` artifact has no `targets` member, or no `handlers` member, or carries `"targets": "27"`
- **THEN** `load` SHALL return null and emit one `status=rejected reason=parse-error` record
- **AND** with `ape.mopDataPath` set, the run SHALL abort via `StopTestingException` (INV-MOP-22)

#### Scenario: an artifact with no target runs without distance data
- **WHEN** a `formatVersion: 2` artifact carries `"targets": 0`, under either `ape.mopScoring` value
- **THEN** `load` SHALL return a non-null `MopData` and emit `MOP_DATA` with `targets: 0`
- **AND** the run SHALL proceed, and under `distance` every action SHALL score 0

---

### Requirement: MopScorer — Priority Boost

`MopScorer.score(MopContext, State, ModelAction)` SHALL return an integer priority boost for one valid, target-requiring action (INV-MOP-02), computed from `MopData.lookup` (INV-MOP-36) over the action's containment nodes ("Parent/child widget granularity reconciliation") for the action's event (`MopScorer.eventTypeOf`). The boost is additive: `MopWidgetPass` adds it to `ModelAction.priority`, never replacing it (INV-MOP-03), and stores it as the action's `mopBoost`. The mode is the plan key `ape.mopScoring` (`run-spec`), `flag` or `distance`, default `flag` (design D18); the `MopContext` carries the data, the mode, the weights and the run's retirement (design D17, Plumbing).

**`flag` mode.** The boost comes from the largest flag weight over the containment lookups, ties to the earlier lookup in containment order; this is the strongest flag (direct > transitive > none) whenever `mopWeightDirect >= mopWeightTransitive`, as with the defaults:

| Best containment lookup | Boost |
|-----------|-------|
| direct flag for the event | `+mopWeightDirect` (500) |
| transitive flag for the event (and not direct) | `+mopWeightTransitive` (300) |
| no flag on any containment node, or `NONE` everywhere | `0` |

On an unstamped APK every lookup takes the id key, so `flag` reproduces the E6 weights. `MopRetirement` is null under `flag`, and distances are not read.

**`distance` mode.** Let `d` be the smallest distance over the pairs of all the action's containment lookups whose target is not retired ("Target Retirement"). The weight is `ape.mopWeightD1` (500) for `d ≤ 1`, `ape.mopWeightD2` (400) for `d = 2`, `ape.mopWeightD3` (300) for `d = 3`, and `0` for `d ≥ 4` or when no unretired pair exists. Flags are not read. `score` returns this weight before the tie split.

**Tie split (`distance` only).** `MopWidgetPass.apply` (`:50-84`) SHALL first compute the weight and `d` of every scored action of the state. Ties are by weight level, not by exact `d`: `d = 0` and `d = 1` form one level (weight `ape.mopWeightD1`), and `d = 2` and `d = 3` one level each. The tied actions are those whose undivided weight equals the state's best level, the largest positive undivided weight among the state's scored actions. When there are `n > 1` of them, each SHALL receive `max(W / n, W_next)`, with `W` the weight of that level, `n` the number of tied actions, integer division, and `W_next` the weight of the next level (`ape.mopWeightD2` for the `d ≤ 1` level, `ape.mopWeightD3` for `d = 2`, `0` for `d = 3`). The split follows the plan (§6.7, "o reforço se divide pelo número de empatados"): a mark common on the screen attracts less. The floor is the author's decision of 2026-10-07: without it, two actions at `d ≤ 1` would get 250 each and fall below a single action at `d = 2` (400), so the explorer would prefer the farther widget. Actions at a worse level keep their undivided weight. The split value is what is added to the priority and stored as `mopBoost`. The undivided weight SHALL be stored beside it as the action's `mopWeight`, which the MOP shortcut ranks by (`action-selection`, design D21), and reset with `mopBoost` (`ModelAction.java:325`). Under `flag` `mopWeight` equals `mopBoost`. The levels are found by weight value, so the floor assumes `ape.mopWeightD1 >= ape.mopWeightD2 >= ape.mopWeightD3`, as the defaults are: a plan with equal level weights merges those levels into one tie set, and one with inverted weights can give tied actions more than their undivided weight. Such a plan is accepted, not rejected (author, 2026-10-09; review NT-02).

`mopWeightDirect`/`mopWeightTransitive` are inert under `distance`, and `mopWeightD1..3` under `flag`; `RUN_START.inert` reports them (`run-spec`). The menu-gateway, WTG and frontier passes are the same in both modes. WTG and frontier read `activityHasMop`; the menu gateway reads `activityHasMopOptionsMenu`, which load computes from `optionsMenus` and the WTG edges into the selected MOP set (`MopData.recomputeMopOptionsMenus`, `:441-458`). Neither changes meaning with the mode; both move with the A′ source-3 constructor exclusion (`static-analysis-entrypoints`).

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a `NONE` lookup — including on a MOP-bearing activity — both score `0`. `MopData.activityHasMop(activity)` remains a predicate consumed by WTG scoring and `stateMopDensity`, and is not a branch of `MopScorer.score`.

#### Scenario: Direct MOP-reachable widget
- **WHEN** under `flag` the best containment lookup for a click action carries `{direct=true, transitive=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** under `flag` the best containment lookup carries `{direct=false, transitive=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget scores zero
- **WHEN** under `flag` every containment lookup resolves through the id key to a widget with no flag for the event, or through a listed stamp whose record is `none`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: Widget null scores zero
- **WHEN** every containment lookup is `NONE` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0` in both modes

#### Scenario: No match
- **WHEN** no containment lookup carries a flag or a pair AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0` in both modes

#### Scenario: Distance weights by nearest unretired target
- **WHEN** under `distance` with default weights, an action's containment lookups carry, as nearest unretired pair, `d = 0`, `1`, `2`, `3` or `4`, and no other action of the state has a positive weight
- **THEN** its boost SHALL be 500, 500, 400, 300 or 0 respectively
- **AND** an action whose lookups carry no pair SHALL score 0

#### Scenario: Weight split among the actions tied at the state's best distance
- **WHEN** under `distance` with default weights, a state has two actions at `d = 1`, one action at `d = 3`, and the rest at no pair
- **THEN** each of the two `d = 1` actions SHALL receive a `mopBoost` of 400 (`max(500 / 2, 400)`: the floor at the `d = 2` weight)
- **AND** the two `d = 1` actions SHALL keep a `mopWeight` of 500
- **AND** the `d = 3` action SHALL receive 300, undivided
- **AND WHEN** three actions share the best `d = 2`
- **THEN** each SHALL receive 300 (`max(400 / 3, 300)`)
- **AND WHEN** four actions share the best `d = 3`
- **THEN** each SHALL receive 75 (`max(300 / 4, 0)`)

#### Scenario: d = 0 and d = 1 are one tie level
- **WHEN** under `distance` with default weights, a state has two actions at `d = 0`, one action at `d = 1`, and no other action with a positive weight
- **THEN** all three SHALL be tied at level D1, since each has the undivided weight 500
- **AND** each SHALL receive a `mopBoost` of 400 (`max(500 / 3, 400)`), the `d = 0` actions included

#### Scenario: flag mode reproduces the E6 mark on an unstamped APK
- **WHEN** under `flag` the format-2 cryptoapp fixture is loaded and the tree carries no stamp
- **THEN** a click on `buttonGenerateHash`, `btn_cipher_encrypt` or `executeButton` SHALL score 300, as in E6
- **AND WHEN** the same run is under `distance`
- **THEN** the same three clicks, alone on their screens, SHALL score 400 (`d = 2`), 0 (`d = 4`) and 300 (`d = 3`)

---

### Requirement: Parent/child widget granularity reconciliation

The MOP scoring pass SHALL reconcile parent/child granularity over **nodes**, not over resource ids (design D17). For an action's resolved node it SHALL run `MopData.lookup` on the node itself, on up to two ancestors (`getParent`), and on its descendants down to two levels (children, then grandchildren), and SHALL keep the best result: under `flag` the largest flag weight (the strongest flag, direct > transitive > none, whenever `mopWeightDirect >= mopWeightTransitive`), under `distance` the smallest unretired distance. The node's own lookup is not privileged; the best result wins wherever it comes from, as `MopWidgetPass.mopBoostWithContainment` (`:92-108`) already takes the maximum today. Each containment node is looked up with its own stamp and its own resource id, so a stamped ancestor or descendant contributes its handler's record.

This addresses the case where static analysis flags a container id (e.g., a `CardView`) while the runtime resolves a child (e.g., the inner `LinearLayout`), or vice versa, and the case where the stamped handler sits on a node the action does not target. The depth bound guards against over-boost (a root container marked by a single MOP child). A boost won by a node other than the action's own is counted as a containment hit on the pass's log line, as today.

Containment applies to scoring and to the readers that share its test: the shortcut ranking, the prompt marker, and the retirement trigger, which counts the targets of the lookup that won the action its weight ("Target Retirement"; author, 2026-10-07 night). It SHALL NOT apply to the state MOP density of the navigation tiebreak, which reads each action's own node, as today (design D17).

#### Scenario: Static flags parent, runtime resolves child
- **WHEN** the action's own node has a `NONE` lookup for its event
- **AND** an ancestor within depth 2 has a lookup with a MOP flag or an unretired pair
- **THEN** the scorer SHALL use that ancestor's lookup for the boost

#### Scenario: Depth bound respected
- **WHEN** the only node with a MOP flag or a pair is an ancestor at depth 3
- **THEN** the reconciliation SHALL NOT match it (bound is depth ≤ 2)
- **AND** the scorer SHALL fall through to `0` (no activity-level fallback)

---

### Requirement: eventType normalization in the consumer

The scorer SHALL normalize `eventType` tokens to a single canonical form before comparison so that a producer `snake_case` token (e.g., `long_click`, `item_selected`) and the consumer `camelCase` token (e.g., `longClick`, `itemSelected`) for the same event compare equal (B6). Normalization SHALL be performed in the consumer (`MopData`/`MopScorer`); the producer and the gh13 JSON schema are NOT changed.

#### Scenario: snake_case producer token matches camelCase consumer token
- **WHEN** the JSON listener `eventType` is `long_click`
- **AND** the consumer compares it against `longClick`
- **THEN** the two SHALL compare equal after normalization

---

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath")`. The default value is `null`. When set, it points to the **derived compact MOP artifact** on the device (`aperv-tool` pushes it to `/data/local/tmp/mop-artifact.json`). The full static-analysis JSON is never pushed to the device and no jar code path accepts it (INV-MOP-34).

`Config.java` SHALL also declare the following MOP weight fields with defaults matching the MopScorer boost table:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |

These weights are configurable via `ape.properties` but the hardcoded defaults SHALL be 500/300. `Config.mopWeightActivity` SHALL NOT exist — the activity-level fallback it parameterised is removed (see "MopScorer — Priority Boost").

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.mopDataPath`
- **THEN** `Config.mopDataPath` SHALL be `null`
- **AND** `StatefulAgent` SHALL initialise `_mopData` to `null`, disabling MOP scoring

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** `Config.mopDataPath` SHALL equal `"/data/local/tmp/mop-artifact.json"`

#### Scenario: Default MOP weights
- **WHEN** `ape.properties` does not contain any `ape.mopWeight*` keys
- **THEN** `Config.mopWeightDirect` SHALL equal `500`
- **AND** `Config.mopWeightTransitive` SHALL equal `300`

#### Scenario: Custom MOP weights override
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `Config.mopWeightDirect` SHALL equal `200`
- **AND** `Config.mopWeightTransitive` SHALL retain its default (`300`)

---

### Requirement: WTG Scoring Pass in adjustActionsByGUITree

`StatefulAgent.adjustActionsByGUITree()` SHALL include a WTG scoring pass after the existing MOP scoring pass. For each valid, target-requiring, resolved action, the pass SHALL call `MopScorer.scoreWtg(activity, shortId, mopData)` and add the result to the action's priority. This pass SHALL only execute when `_mopData` is non-null and has WTG transitions loaded.

#### Scenario: WTG boost applied alongside MOP boost
- **WHEN** widget has direct MOP listener (MOP boost = +500) AND WTG leads to MOP activity (WTG boost = +200)
- **THEN** total priority boost SHALL be +700

#### Scenario: No WTG data
- **WHEN** `_mopData` is null or has no transitions
- **THEN** WTG scoring pass SHALL be skipped

### Requirement: Config Flag for WTG Weight

`Config.java` SHALL declare `mopWeightWtg` loaded via `ape.mopWeightWtg`, defaulting to `200`. It parameterises the WTG navigation boost; a value of `0` SHALL disable the WTG scoring pass (`MopScorer.scoreWtg` returns `0`).

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `mopWeightWtg` | `ape.mopWeightWtg` | int | 200 | WTG navigation boost (0 = disabled) |

#### Scenario: Default WTG weight
- **WHEN** `ape.properties` does not contain `ape.mopWeightWtg`
- **THEN** `Config.mopWeightWtg` SHALL equal `200`

#### Scenario: WTG weight disabled
- **WHEN** `ape.properties` contains `ape.mopWeightWtg=0`
- **THEN** `Config.mopWeightWtg` SHALL equal `0`
- **AND** `MopScorer.scoreWtg` SHALL return `0` for any widget

---

### Requirement: ApePromptBuilder — Widget Metadata in LLM Context

`ApePromptBuilder` SHALL surface, in widget descriptions emitted to the LLM, the following fields when non-null/non-empty: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (rendered as `entries=[v1, v2, …]`, capped to 10 with trailing `, …`), `inputType`, `hint`. The fields are read with `MopData.getWidget`, keyed by id, because the static metadata is keyed by id (INV-MOP-36).

Each field SHALL appear in the form ` <fieldName>="<value>"` (or `entries=[…]` for the list). Per-value 80-char cap with `…` ellipsis on overflow. Newlines (`\n`, `\r`) flattened to single space. Null / empty ⇒ zero tokens (INV-MOP-10).

This requirement governs the metadata fields only. The `[DM]`/`[M]` markers are no longer event-type-agnostic: they are the scoring's verdict for the line, per mode, through the same lookup with containment, and their rule is owned by `llm-prompt` "MOP Marker Annotation" (design D22). The main spec's sentence that the markers "stay event-type-agnostic in v1 (D16)" referred to an earlier change's D16 and is superseded.

#### Scenario: Widget metadata appears when fields populated
- **WHEN** `ApePromptBuilder` renders a widget with `contentDescription="Encrypt button"`, `tooltipText="Tap to encrypt"`, `inputType=""` (Button), other metadata `null`
- **THEN** prompt SHALL contain `contentDescription="Encrypt button"` AND `tooltipText="Tap to encrypt"`
- **AND** prompt SHALL NOT contain `prompt=`, `spinnerMode=`, `entries=`, `inputType=`

#### Scenario: All-null metadata produces no tokens
- **WHEN** widget has all metadata fields null AND empty `entries`
- **THEN** prompt SHALL contain none of `prompt=`, `spinnerMode=`, `contentDescription=`, `tooltipText=`, `entries=`, `inputType=`, `hint=`

#### Scenario: Long metadata truncated at 80 chars
- **WHEN** widget has `contentDescription` of 200 chars
- **THEN** rendered substring SHALL contain at most 80 value chars followed by `…"`

#### Scenario: Spinner entries appear and are capped at 10
- **WHEN** widget has `entries=["MD2","MD5","SHA-1","SHA-256","SHA-512"]`
- **THEN** prompt SHALL contain `entries=[MD2, MD5, SHA-1, SHA-256, SHA-512]`
- **WHEN** widget has 15 entries
- **THEN** rendered substring SHALL contain exactly 10 element tokens followed by `, …`

#### Scenario: Newlines flattened
- **WHEN** widget has `contentDescription="line1\nline2"`
- **THEN** rendered substring SHALL contain `line1 line2` (single-space replacement)

#### Scenario: InputType and hint surfaced for EditText
- **WHEN** widget has `type="android.widget.EditText"`, `inputType="textPassword"`, `hint="Your password"`
- **THEN** prompt SHALL contain `inputType="textPassword"` AND `hint="Your password"`

---

### Requirement: MopScorer — OPTIONSMENU-Aware Menu Boost

`MopScorer.scoreOpenMenu(String activity, MopData data)` SHALL return `Config.mopWeightOpenMenu` (default 250) when `data.activityHasMopOptionsMenu(activity)` is `true`, else `0`. The lookup SHALL be O(1) over the precomputed `activitiesWithMopOptionsMenu` set (INV-MOP-13).

`StatefulAgent`'s action-priority pass SHALL apply this boost to the `MODEL_MENU` action of the current state when `_mopData != null` AND the activity's MENU pick key is still eligible under `ape.backMenuPickCap`. Eligibility is consulted through the protected hook `StatefulAgent.menuPickEligible(activity)` — base implementation returns `true` (RandomAgent/ReplayAgent unchanged); `SataAgent` overrides it with the cap check over its discretionary pick counts. When the hook returns `false`, the pass SHALL NOT modify the MENU action's priority and SHALL NOT set its `menuBoost`.

#### Scenario: Boost applied when OPTIONSMENU has MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.A")==true` AND `MopScorer.scoreOpenMenu("com.x.A", data)` is called
- **THEN** the return value SHALL equal `Config.mopWeightOpenMenu`

#### Scenario: Zero when no OPTIONSMENU MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.B")==false`
- **THEN** `MopScorer.scoreOpenMenu("com.x.B", data)` SHALL return `0`

#### Scenario: Action-priority pass boosts MODEL_MENU when activity has MOP options menu
- **WHEN** `StatefulAgent`'s action-priority pass runs on a state whose activity is in `activitiesWithMopOptionsMenu` AND `menuPickEligible(activity)` returns `true`
- **THEN** the `MODEL_MENU` action's priority SHALL be incremented by `Config.mopWeightOpenMenu`
- **AND** a `Logger.iformat` line SHALL summarize the boost

#### Scenario: Boost suppressed once the MENU key is capped
- **WHEN** the activity's MENU pick key has reached `ape.backMenuPickCap` (so `menuPickEligible(activity)` returns `false`) AND the action-priority pass runs on a state of that activity
- **THEN** the `MODEL_MENU` action's priority SHALL NOT be incremented and its `menuBoost` SHALL remain 0

### Requirement: MopScorer — Event-Type-Aware Reachability Scoring

MOP scoring SHALL be event-type-aware. The event of an action is `MopScorer.eventTypeOf(action)`, and `MopData.lookup` selects what MOP knows for that event ("Handler Stamp Lookup"):
- through a stamp, only for `click` and `longClick`, from the stamped class's record for that event;
- through the id key, from the widget's per-event flag when its `mop` map has an entry for that event, and otherwise from the aggregate `directMop`/`transitiveMop` (match-any fallback, INV-MOP-14); a null event uses the aggregate. Distance pairs are taken under the event's own key, except that a click on a widget with no `click` key in its `mop` map reads the widget's click aggregate (design D17 as amended 2026-10-09); through a stamp, only the record's pairs for the event.

The scorer SHALL NOT read flags by `(activity, shortId)` itself: the former string-keyed `score(activity, shortId, data[, eventType])` forms are replaced by the action-level `score` of "MopScorer — Priority Boost", which passes the event to the lookup.

`MopScorer.eventTypeOf(ModelAction action)` SHALL map:
- `ActionType.MODEL_CLICK` → `"click"`
- `ActionType.MODEL_LONG_CLICK` → `"longClick"`
- `ActionType.MODEL_INPUT` on a Spinner widget (class contains "Spinner") → `"itemSelected"`
- `ActionType.MODEL_SCROLL` → `"scroll"`
- anything else → `null`

#### Scenario: Click-only listener boosts click action only
- **WHEN** an unstamped node's widget has `directMopByEventType={click:true, longClick:false}`
- **AND** a `MODEL_CLICK` action on it is scored under `flag`
- **THEN** the boost SHALL equal `mopWeightDirect`
- **WHEN** a `MODEL_LONG_CLICK` action on the same node is scored under `flag`
- **THEN** the boost SHALL equal `0`

#### Scenario: Null eventType falls back to aggregate (match-any)
- **WHEN** an unstamped node's widget has `directMop=true` (aggregate) and `dist = {select: [[4,2]]}`, AND `MopData.lookup(activity, node, null)` is called
- **THEN** the lookup SHALL carry `direct=true` with source `ID`, so an action whose `eventTypeOf` is null scores `mopWeightDirect` under `flag`
- **AND** the lookup SHALL carry no distance pair: only a click reads an aggregate of pairs

#### Scenario: Action-type mapper
- **THEN** `MopScorer.eventTypeOf(action of type MODEL_CLICK)` SHALL return `"click"`
- **AND** `eventTypeOf(MODEL_LONG_CLICK)` SHALL return `"longClick"`
- **AND** `eventTypeOf(MODEL_SCROLL)` SHALL return `"scroll"`
- **AND** `eventTypeOf(unknown type)` SHALL return `null`

---

### Requirement: ApeFuzzer — Type-Aware Input Generation

`ApeFuzzer.generateInputForType(String inputType, String hint, java.util.Random rnd)` SHALL return a domain-correct random string based on `inputType`:

| `inputType` | Output shape |
|---|---|
| contains "Password" | 8–12 char mixed-class: ≥1 letter, ≥1 digit, ≥1 symbol |
| "number" / "numberSigned" / "numberDecimal" | numeric (signed/decimal as applicable) |
| "phone" | matches Brazilian phone template `+55 11 9XXXX-XXXX` (or locale-appropriate) |
| "textEmailAddress" | `<localPart>@example.com` with `localPart` 4–10 lowercase letters |
| "textUri" | `https://example.com/<8 lowercase>` |
| "date" / "time" / "datetime" | ISO 8601 random within the last decade |
| empty/unknown — and `hint` contains "email" (case-insensitive) | email shape |
| empty/unknown — and `hint` contains "senha" / "password" | password shape |
| empty/unknown — and `hint` matches `^\d+$` | numeric shape |
| else | legacy `RandomHelper.randomString` (or equivalent) |

When `Config.fuzzInputTyped=false`, the typed path SHALL be bypassed entirely — callers fall back to the legacy generator. When `MopData` is unavailable or the widget is not in the static map, callers SHALL also fall back to the legacy generator (no regression on non-instrumented apps).

#### Scenario: Password input type produces mixed-class string
- **WHEN** `ApeFuzzer.generateInputForType("textPassword", "", rnd)` is called
- **THEN** the output SHALL be 8 to 12 chars
- **AND** the output SHALL contain at least one letter, one digit, and one symbol

#### Scenario: Number input type produces digits
- **WHEN** `ApeFuzzer.generateInputForType("number", "", rnd)` is called
- **THEN** the output SHALL match `^-?\d+$`

#### Scenario: Phone input type produces template-conformant string
- **WHEN** `ApeFuzzer.generateInputForType("phone", "", rnd)` is called
- **THEN** the output SHALL match a phone-shape regex

#### Scenario: Email input type
- **WHEN** `ApeFuzzer.generateInputForType("textEmailAddress", "", rnd)` is called
- **THEN** the output SHALL match `^[a-z]{4,10}@example\.com$`

#### Scenario: Hint-based fallback to email
- **WHEN** `ApeFuzzer.generateInputForType("", "Your email", rnd)` is called
- **THEN** the output SHALL match the email shape

#### Scenario: Unknown inputType falls back to legacy generator
- **WHEN** `ApeFuzzer.generateInputForType("weird_unknown", "", rnd)` is called
- **THEN** the output SHALL be a non-empty string from the legacy generator
- **AND** the output SHALL NOT match the password / number / phone / email shapes

#### Scenario: fuzzInputTyped=false bypasses typed generation (rollback guard)
- **WHEN** `Config.fuzzInputTyped=false` AND `ApeFuzzer.generateInputForType("textPassword", "Your password", rnd)` is called
- **THEN** the output SHALL NOT match the password shape (no required letter+digit+symbol mix)
- **AND** the output SHALL match the legacy random-string shape
- **NOTE**: this is the operator-level rollback contract — if T1.3 corrupts a corpus run, `ape.fuzzInputTyped=false` in `ape.properties` MUST restore the pre-change behavior

---

### Requirement: StatefulAgent — Tuple-Based Component Triggering

`StatefulAgent.triggerMopComponent` SHALL build a round-robin list of `(component, intentFilter, action)` tuples (plus a parallel `(provider, operation)` list) and advance an index per call. Tuple construction SHALL:

- Iterate `getReceivers() + getServices() + (Config.activityTriggerEnabled ? getActivities() : emptyList())`.
- Skip any component with `reachesTarget=false` (INV-MOP-15).
- Skip activities with `exported=false` (INV-MOP-15).
- For each surviving component, for each `IntentFilter`, for each `action` in the filter, emit one tuple. Components with empty `intentFilters` but `targetMethods` non-empty emit one tuple with `filter=null, action=null` (component-name-only intent).
- For each provider with `reachesTarget=true` and non-null `authorities`, emit three `ProviderTuple` (query, insert, update) in a sub-cycle.

For each invocation:
- Tuple selection: `tupleList[componentTriggerIndex++ % tupleList.size]`.
- Intent construction: `setComponent(...)`; if `action != null`, `setAction(action)`; for each `category` in `filter.categories`, `addCategory(category)`.
- Existing `SystemBroadcastCatalog` extra application preserved AFTER `setAction`, BEFORE `sendBroadcast`.
- Provider invocation: `AndroidDevice.runShell("content " + operation + " --uri content://" + authorities)`. Non-zero exit ⇒ WARN with stderr.
- Logging: `[APE-RV] Triggering <type>: <className> action=<a> categories=<c1,c2> reachesTarget=true`.

#### Scenario: Skips non-reachable components
- **WHEN** `MopData` has a receiver with `reachesTarget=false`
- **THEN** the tuple list SHALL NOT contain any tuple from that receiver

#### Scenario: Skips non-exported activities even when activity triggering is enabled
- **WHEN** `Config.activityTriggerEnabled=true` AND `MopData` has an activity with `exported=false`
- **THEN** the tuple list SHALL NOT contain any tuple from that activity

#### Scenario: Round-robins all intent filter actions
- **WHEN** a receiver has one `IntentFilter` with two actions `["action1", "action2"]`
- **AND** `triggerMopComponent` is called 4 times in sequence
- **THEN** action1 and action2 SHALL each have been used exactly 2 times

#### Scenario: Provider operations round-robin
- **WHEN** `MopData` has a provider with `reachesTarget=true` and `authorities="com.x.p"`
- **AND** `triggerMopComponent` is called 6 times in sequence (provider-only fixture)
- **THEN** the shell commands invoked SHALL be in the cyclic order `content query`, `content insert`, `content update`, `content query`, …

#### Scenario: Log line contains expected fields
- **WHEN** a receiver tuple is triggered
- **THEN** the logged line SHALL contain `className=…`, `action=…`, `categories=…`, `reachesTarget=true`

#### Scenario: activityTriggerEnabled=false excludes activities from the tuple list (rollback guard)
- **WHEN** `Config.activityTriggerEnabled=false` (default) AND `MopData` carries one reachable+exported activity AND one reachable receiver
- **THEN** the built tuple list SHALL contain ≥1 tuple from the receiver AND zero tuples from the activity
- **WHEN** `Config.activityTriggerEnabled=true` AND the tuple list is rebuilt
- **THEN** the tuple list SHALL contain ≥1 tuple from the activity
- **NOTE**: gh11 sandwichroulette evidence (-45pp) — default `false` is load-bearing for experiment integrity

#### Scenario: Empty trigger list returns false without side effects
- **WHEN** `MopData` carries components but ALL have `reachesTarget=false` (cryptoapp-like)
- **AND** `triggerMopComponent()` is invoked
- **THEN** the call SHALL return `false`
- **AND** no shell command / broadcast / service start SHALL be invoked
- **AND** the round-robin counter SHALL NOT advance

#### Scenario: Component with empty intentFilters but non-empty targetMethods emits component-name-only tuple
- **WHEN** `MopData` carries a receiver with `reachesTarget=true`, `intentFilters=[]`, `targetMethods=["<some_sig>"]`
- **THEN** the tuple list SHALL contain exactly one `TriggerTuple` for this receiver with `filter=null` and `action=null`
- **AND** the built Intent SHALL have `setComponent(...)` called and SHALL NOT have any `setAction` / `addCategory` calls

---

### Requirement: MopData — Package / MainActivity Sanity Check

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL compare the artifact's top-level `package` / `mainActivity` against the optional expected values. When `expectedPackage` non-null and not equal to the parsed value, OR `expectedMainActivity` non-null and not equal to the parsed value, a `WARN` log line naming both pairs SHALL be emitted. When `Config.mopStrictPackageMatch=true` and either comparison mismatches, `MopData.load` SHALL return `null` (reject reason `package-mismatch`). Default behavior (`mopStrictPackageMatch=false`) is warn-only — the parsed `MopData` is still returned. The generator copies both scalars verbatim from the full JSON, so a mismatch means the wrong artifact was derived or pushed for this APK.

The production call site (`StatefulAgent` constructor) SHALL pass the runtime package name and main activity of the app under test, making the sanity check reachable in production. The single-argument `MopData.load(path)` overload does not exist (P3); the three-argument form is the sole loader entry point.

#### Scenario: Default warn-only on mismatch
- **WHEN** `Config.mopStrictPackageMatch=false` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Strict-mode rejection on mismatch
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be `null`
- **AND** a `WARN` log line SHALL be emitted
- **AND** the load record SHALL carry `reason=package-mismatch`

#### Scenario: Null expected values bypass the check
- **WHEN** `MopData.load(path, null, null)` is called
- **THEN** no sanity-check WARN log SHALL be emitted (regardless of `mopStrictPackageMatch`)
- **AND** the parsed `MopData` SHALL be returned

#### Scenario: Production call site passes runtime identity
- **WHEN** `StatefulAgent` loads MOP data for an app whose runtime package is `br.unb.cic.cryptoapp`
- **THEN** it SHALL call `MopData.load(Config.mopDataPath, "br.unb.cic.cryptoapp", <mainActivity>)`
- **AND** an artifact derived for a different APK SHALL trigger the sanity-check WARN (and rejection under `mopStrictPackageMatch=true`)

---

### Requirement: MopData — Load Status Line and Fail-Fast

`MopData.load` SHALL emit exactly one load status record per invocation, on both outcomes. The record is the out-of-step `MOP_DATA` NDJSON record of the event sink:

- success: `status=loaded formatVersion=<n> sourceDigest=<sha256> package=<pkg> windows=<n> widgets=<n> flagged=<n> droppedNoId=<n> wtgEdges=<n> mopActivities=<n> mopActsAugmented=<n> components=<n> handlersUnmatched=<n> syntheticLambda=<n> recovered=<n> targets=<n> handlers=<n> droppedPairs=<n>` — counts sourced from the loaded structures and the artifact's generator-computed `stats` echo (`droppedNoId`, handler-join counters, `orphanDialogs` are host facts, echoed not recomputed). `targets` is the artifact's target count, `handlers` the number of classes in the handler table (design D23), and `droppedPairs` the number of malformed distance pairs dropped at load ("MopData — Static Analysis JSON Loader")
- failure: `status=rejected reason=<file-missing|parse-error|version-mismatch|package-mismatch>`

`mopActsAugmented` SHALL be `|mopActivitiesAugmented \ mopActivities|` over the two wire sets — what the augmented source would contribute — and SHALL NOT vary with `Config.mopActivitySourceComponents`. The flag is carried independently by `RUN_START`. `mopActivities` remains the size of the **selected** set, so the two fields answer different questions on purpose.

`windows` is in the list deliberately: it survives inside the artifact as `stats.windows`, and dropping it would make `MOP_DATA` lose a field it has carried since stage 4. `transitions` is the single deliberate omission, superseded by `wtgEdges`: it counts the flat transition list, not the click-only view the frontier passes gate on. The record gains fields across changes and never loses one; `targets`, `handlers` and `droppedPairs` are added by this change.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden.

Fail-fast composition:

1. **Plan level** (`run-spec`): a plan that declares the MOP feature without an artifact path is invalid and aborts at plan validation, before agent construction.
2. **Artifact level** (this requirement): when `Config.mopDataPath` is set and `MopData.load` returns `null` — for any reject reason, including a format-1 artifact — `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing as pure SATA (INV-MOP-22, unchanged).
3. **Host level** (`aperv-tool` in rv-android): a MOP arm whose full JSON is missing or whose derivation fails raises before launch, and the host cache keys on the format as well as `source.digest`, so a cached format-1 artifact is re-derived rather than pushed (rv-android `gh122`).

When `Config.mopDataPath` is unset, behavior is unchanged (MOP scoring disabled, no status record required beyond the absence of a load).

#### Scenario: successful load emits counters
- **WHEN** `MopData.load` parses a format-2 artifact derived from a full JSON whose SHA-256 is `d`, with 30 widgets, 3 flagged, 0 dropped, 16 WTG edges, `targets: 27` and 2 handler classes
- **THEN** one `status=loaded` record SHALL be emitted carrying `formatVersion=2`, `sourceDigest=d`, those counters, `targets=27`, `handlers=2` and `droppedPairs=0`

#### Scenario: transitions present, click edges absent
- **WHEN** the artifact's `wtg` map is empty because the producer's document carried transitions but none of them a `click` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry **no** `transitions` field
- **AND** the jar SHALL have no other number to confuse it with: raw transitions do not exist on the wire (INV-MOP-35), so the deduplication and the click-only filter that produce `wtgEdges` are the generator's (INV-DRV-03)

#### Scenario: rejected load names the reason
- **WHEN** the file at `mopDataPath` is not a format-2 compact artifact (a full JSON, a format-1 artifact, or a document without `formatVersion`)
- **THEN** one `status=rejected reason=version-mismatch` record SHALL be emitted
- **AND** `load` SHALL return `null`

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** `ape.mopDataPath` is set and `MopData.load` returns `null`
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed as pure SATA

#### Scenario: unset path keeps SATA behavior
- **WHEN** `ape.mopDataPath` is not set
- **THEN** `_mopData` SHALL be `null` and exploration SHALL proceed with MOP scoring disabled, with no `MopRetirement`

### Requirement: Typed-Input Widget Resolution via Containment

When `ApeAgent.generateInputText` resolves the static-analysis widget for an `EditText` (to read its `inputType`/`hint` for type-aware generation), the lookup SHALL apply the same parent/child containment-reconciliation policy used by the MOP scoring pass (±2-level ancestor/descendant id probe, as specified in "Parent/child widget granularity reconciliation"): the node's own short id is tried first, then the containment walk. Exact-id-only lookup misses whenever static analysis flags a container id while runtime resolves a child id (or vice versa) — the granularity mismatch that motivated containment in the scorer — so typed generation rarely activated and most inputs fell back to the legacy generator. When no widget resolves (or `inputType`/`hint` are empty), the existing fallback to the legacy generator is unchanged.

#### Scenario: child id resolves the container's typed metadata
- **WHEN** the static JSON flags widget `login_form` with `inputType="textEmailAddress"` and the runtime EditText resolves to child id `login_form_field`, one containment level below
- **THEN** `generateInputText` SHALL resolve the `login_form` widget via the containment walk
- **AND** the generated value SHALL be email-shaped

#### Scenario: no static widget still falls back
- **WHEN** neither the node's id nor its containment neighborhood matches a static widget
- **THEN** the legacy generator SHALL be used, as before

---

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopData, int timestamp)` (`MopScorer.java:101`) SHALL return `0` when the state's activity does not satisfy `activityHasMop(activity)` (cheap early-out, no widget resolution). Otherwise it SHALL return `1 + count`, where `count` counts only actions whose resolved widget is MOP-flagged: for each valid, target-requiring action, resolve the node's short id, look up the widget (same resolution as `score`), and count it when `isDirectMop(eventType)` or `isTransitiveMop(eventType)` holds.

The `+1` term is the **activity-substrate floor**: a state on a MOP-bearing activity that carries no MOP-flagged widget (the A′-contributed case — activities whose MOP reach flows through lambdas or component-level evidence, invisible at widget granularity) SHALL rank above a state on a non-MOP activity (`1 > 0`) in the SATA navigation tiebreaks, while any widget-flagged state still ranks strictly above an activity-floor-only state (`1 + count > 1` for `count ≥ 1`). Comparisons between two widget-flagged states are unchanged (both shift by +1). The floor is a cross-state ranking signal only; it SHALL NOT feed any per-candidate boost (`MopScorer.score` remains widget-discriminative — the removed uniform `+mopWeightActivity` fallback stays removed).

The method gains an `int timestamp` parameter (previously 2-arg `stateMopDensity(State, MopData)`); `int` matches the codebase-wide timestamp type (`ModelAction.isResolvedAt(int)`, `Agent.getTimestamp():int`) and is required for the per-action `getWidget` resolution. All five call sites (`SataAgent.java:707, 719, 957, 960, 969`) SHALL pass the current GUITree timestamp via `getTimestamp()`.

Previously the method gated on `activityHasMop` and then counted **every** valid targeted action — a widget-count proxy, not a MOP density. Its consumers (`SataAgent` ABA and trivial-activity navigation tiebreakers, `SataAgent.java:702,714,952-964`) therefore steered exploration toward widget-dense screens inside MOP activities rather than toward the screens actually carrying MOP-flagged widgets, diluting the navigation signal the mechanism exists to provide.

#### Scenario: MOP-flagged widgets counted above the floor, unflagged ignored
- **WHEN** a state in a MOP activity has 10 valid targeted actions of which 2 resolve to MOP-flagged widgets
- **THEN** `stateMopDensity` SHALL return 3 (floor 1 + count 2)

#### Scenario: A′-only activity ranks above non-MOP activity
- **WHEN** state A is on an activity with `activityHasMop==true` contributed solely by the A′ component/reachability sources (zero MOP-flagged widgets resolve), and state B is on an activity with `activityHasMop==false`
- **THEN** `stateMopDensity(A) == 1` and `stateMopDensity(B) == 0` (navigation tiebreak prefers A)

#### Scenario: widget evidence outranks activity-floor-only evidence
- **WHEN** state A has exactly 1 MOP-flagged resolved action and state B is on a MOP activity with none
- **THEN** `stateMopDensity(A) == 2` and `stateMopDensity(B) == 1` (widget-flagged state preferred)

#### Scenario: dense non-MOP screen scores below sparse MOP screen
- **WHEN** state A has 12 valid actions, none MOP-flagged, on a non-MOP activity, and state B has 3 valid actions, one MOP-flagged, in a MOP activity
- **THEN** `stateMopDensity(A) == 0` and `stateMopDensity(B) == 2` (navigation tiebreak prefers B)

#### Scenario: non-MOP activity unchanged
- **WHEN** the state's activity has no MOP-reachable methods
- **THEN** `stateMopDensity` SHALL return 0 without resolving any widget

---

### Requirement: Navigation MOP-Tiebreak Decision Log

When the SATA trivial-activity path selection chooses among multiple shortest paths and the `stateMopDensity` comparison is decisive (densities not all equal — the non-random branch), the agent SHALL log exactly one line: `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>`, where `<d>` is the winning path's target-state density and `<n>` the number of candidate paths. When all densities are equal (random fallback) or `MopData` is null, no line SHALL be logged. The per-step cold-state/cold-activity density comparisons SHALL NOT log (they run inside a candidate loop; logging there would flood the trace).

#### Scenario: decisive density logs once
- **WHEN** 3 candidate paths have target densities 0, 0, 2
- **THEN** the path with density 2 SHALL be chosen and one `[APE-RV] Nav MOP tiebreak: density=2 paths=3` line SHALL be logged

#### Scenario: all-equal densities stay silent
- **WHEN** all candidate paths have equal target density
- **THEN** the path SHALL be chosen by the existing random fallback and no tiebreak line SHALL be logged

---

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

The A′ 3-source union (widget-derived activities ∪ component-flagged activities ∪ reachability-flagged activity classes) SHALL be computed **host-side by the generator** and shipped as the wire field `mopActivitiesAugmented`, alongside the widget-derived `mopActivities` (see the `static-analysis-entrypoints` delta for the derivation rules). On-device, `Config.mopActivitySourceComponents` selects which set backs `activityHasMop`/`getMopActivities`:

- flag `false` (default): the widget-derived set, exactly — byte-identical membership to the pre-change widget-derived behavior.
- flag `true`: the augmented set.

The selection SHALL happen once at load; every downstream consumer (launcher census ordering, `stateMopDensity` substrate floor, WTG/MopFrontier target tests, OPTIONSMENU-gateway condition 2) reads the selected set (INV-MOP-27's observable semantics preserved).

The key's plan grounding from `rearch-02-runspec` is **carried forward unchanged**: in the run-spec `Feature` model, `ape.mopActivitySourceComponents` activates the `MOP_ACTIVITY_SOURCE` feature, which depends on `MOP`. An explicit `true` on a plan without `ape.mopDataPath` aborts resolution as a missing dependency; with the feature absent, the widget-derived source is the only one that exists (INV-RUN-05 of `run-spec` — the recorded substitute for the dissolved INV-ARCH-06 kill-switch registration). This stage changes only *where the augmented set is computed* (host-side generator instead of on-device union), never the key's ownership or its fail-fast behavior.

#### Scenario: flag off preserves widget-only source
- **WHEN** `mopActivitySourceComponents=false` and the artifact's augmented set contains an activity absent from the widget-derived set
- **THEN** `activityHasMop` for that activity SHALL be `false`
- **AND** the launcher census SHALL NOT include it

#### Scenario: component-level activity added under the flag
- **WHEN** `mopActivitySourceComponents=true`
- **THEN** `getMopActivities()` SHALL equal the wire `mopActivitiesAugmented` set
- **AND** the OPTIONSMENU-gateway recompute SHALL test condition 2 against it

#### Scenario: union preserves widget-derived entries
- **WHEN** `mopActivitySourceComponents=true` and activity `com.x.A` is in the artifact's `mopActivities` through a flagged widget
- **THEN** `activityHasMop("com.x.A")` SHALL remain `true` — turning the flag on SHALL never remove an activity
- **AND** the additivity SHALL be a property of the wire rather than of the selection: `mopActivities ⊆ mopActivitiesAugmented` holds by construction of the union (INV-DRV-06), asserted outright by `gh96` `test_augmented_superset_of_widget_derived`, so the flag switches between a set and a superset of it and cannot subtract

#### Scenario: non-reaching component not added
- **WHEN** `mopActivitySourceComponents=true` and `com.x.Plain` has no flagged widget, `reachesMop==false`, and no reaching method in the producer's `reachability[]`
- **THEN** `activityHasMop("com.x.Plain")` SHALL return `false` under **both** flag states, the activity being in neither wire set
- **AND** the exclusion SHALL be the generator's: `gh96` `test_augmented_union_three_sources` includes an activity with `reachesTarget: False` and asserts it enters neither `mopActivities` nor `mopActivitiesAugmented`

#### Scenario: reachability-method source flags a lambda-gapped activity (source 3)
- **WHEN** `mopActivitySourceComponents=true` and `com.x.CryptoActivity` has `components.activities[].reachesTarget==false` and no MOP-flagged widget, BUT its `reachability[]` class (`componentType=="activity"`) carries ≥1 method with `reachesTarget==true`
- **THEN** it SHALL be present in the artifact's `mopActivitiesAugmented` and absent from `mopActivities`, so `activityHasMop("com.x.CryptoActivity")` SHALL be `true` under the flag and `false` without it
- **AND** source 3 SHALL remain a distinct source rather than being folded into source 2, for the reason that made it necessary: the producer's call graph does not traverse D8-desugared lambda edges, so an activity whose UI genuinely reaches a monitored operation through a lambda handler is a **false negative at the component level** — device-verified on cryptoapp, where all 4 activities report `reachesTarget=false` while `reachability[]` marks `CryptographyActivity` with 13 reaching methods, `CipherActivity` 2 and `MessageDigestActivity` 1
- **AND** the three sources contributing distinctly SHALL be asserted by `gh96` `test_augmented_union_three_sources` and by the `Activity sets` scenario of this change's `static-analysis-entrypoints` delta

#### Scenario: explicit activation without MOP data aborts
- **WHEN** `ape.properties` sets `ape.mopActivitySourceComponents=true` and no `ape.mopDataPath`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MOP_ACTIVITY_SOURCE` and `MOP`
- **AND** the abort SHALL precede any artifact read (the dependency is a plan property, not an artifact property)

---

### Requirement: Handler Stamp Lookup

`MopData` SHALL answer, for one node and one event, what MOP knows about the action, through `MopData.Lookup MopData.lookup(String activity, GUITreeNode node, String eventType)` (design D17, API Design). A `Lookup` carries the direct flag, the transitive flag, the distance pairs `int[][] dist` (each `[i, d]`, possibly empty) and the source, one of `STAMP`, `ID` or `NONE`. `lookup` SHALL never return null and SHALL be pure: it reads the artifact and the node, never the retirement state.

The result SHALL be computed in this order:

1. **Stamp.** If `eventType` normalizes to `click` or `longClick`, the node's stamp for that event (`GUITreeNode.getHandlerClick()` or `getHandlerLongClick()`, `ui-tree` "Handler Stamp Capture") is non-null, and the artifact's `handlers` map lists that class, the result SHALL be that class's record for the event, with source `STAMP`. This holds when the record says the class reaches nothing (`mop` `none`, no `dist`): the result then has no flag and no pair, and the id key SHALL NOT be consulted. An event missing from a listed class's record reads as `none` with no pairs.
2. **Id key.** Otherwise, if `getWidget(activity, shortId)` resolves for the node's short id, the result SHALL carry that widget's flags for the event, with today's per-event fallback to the aggregate when the event has no key in the widget's `mop` map (`Widget.isDirectMop`/`isTransitiveMop`, `MopData.java:782,791`, INV-MOP-14), and the widget's `dist` pairs under the event's key (none when the key is absent), except for `click`: when the widget's `mop` map has no `click` key, a click SHALL read the widget's click aggregate, the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (design D17 as amended 2026-10-09), with source `ID`. The click aggregate takes the flags' condition (key absence; an explicit `click` key, `none` included, blocks it) but not their scope: `longClick`, `itemSelected`, `scroll` and a null event read their own pairs alone, so a long-click on a widget with no long-click listener gets no distance weight. The aggregate does not apply to step 1: a stamp names the handler of one event.
3. **Nothing.** Otherwise the result SHALL be `NONE`, with no flag and no pair.

A stamp naming a class that `handlers` does not list SHALL fall back to step 2. That covers the dispatchers gh121 records for library-routed handlers: `androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener` for XML `android:onClick`, and toolbar, menu and `SearchView` classes (gh121 design, Risks `:238` at HEAD, `:239` in the rv-android working tree: "the consumer must keep the resource-id key as fallback"). A listed class wins over the id key even when the id key is flagged, because the stamp names the handler that runs, while the id key carries GATOR's attribution, which spreads one handler over many widgets.

The events `itemSelected` and `scroll`, and a null event, SHALL skip step 1: the instrumenter stamps only click and long-click handlers. A Compose node's stamp is the class of the app's handler lambda (the `onClick`/`onLongClick` field of the modifier node behind the semantics action, or the action lambda's own class when no modifier node is reached; gh121 INV-INS-178), and that class's `java.lang.Object invoke()`, or `java.lang.Object invoke(java.lang.Object)` for a toggleable's `onValueChange`, fills both `click` and `longclick` in its `handlers` record (`static-analysis-entrypoints`), so the stamp answers whichever event the node's key names.

On an APK instrumented without `--stamp-handlers`, every stamp is null, every lookup goes through step 2, and the result equals the E6 per-event flag of the id key.

#### Scenario: listed stamp wins over the id key
- **WHEN** a node in `br.unb.cic.cryptoapp.generated.CryptographyActivity` carries `handlerClick = "br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0"`, and `handlers` lists that class with `mop: {click: transitive}` and `dist: {click: [[16,3],[17,3],[18,3]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `direct = false`, `dist = [[16,3],[17,3],[18,3]]` and source `STAMP`
- **AND** this SHALL hold whatever the node's resource id is, including none

#### Scenario: listed stamp with none wins over a flagged id key
- **WHEN** a node's `handlerClick` names class `com.x.Foo$1`, `handlers["com.x.Foo$1"]` is `{mop: {click: none}}` with no `dist`, and the node's resource id resolves to a widget whose `mop` is `{click: direct}`
- **THEN** `lookup(activity, node, "click")` SHALL return no flag, no pair and source `STAMP`
- **AND** under `ape.mopScoring=flag` the action SHALL score `0`, not `mopWeightDirect`

#### Scenario: unlisted stamp falls back to the id key
- **WHEN** the `buttonGenerateHash` node of `MessageDigestActivity` (declared with XML `android:onClick="generateHash"`) carries `handlerClick = "androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener"`, which `handlers` does not list, and the widget `buttonGenerateHash` has `mop: {click: transitive}` and `dist: {click: [[22,2]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[22,2]]` and source `ID`

#### Scenario: no stamp uses the id key
- **WHEN** a node carries no stamp (both fields null) and its resource id resolves to a widget with `mop: {click: transitive}`
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true` with source `ID`
- **AND WHEN** the resource id resolves to no widget
- **THEN** the result SHALL be `NONE`, with no flag and no pair

#### Scenario: itemSelected and scroll ignore stamps
- **WHEN** a Spinner node carries `handlerClick = "com.x.Foo$1"`, `handlers` lists `com.x.Foo$1` with `mop: {click: direct}`, and the node's resource id resolves to a widget with `mop: {itemselected: transitive}`
- **THEN** `lookup(activity, node, "itemSelected")` SHALL return `transitive = true`, `direct = false` with source `ID`
- **AND** `lookup(activity, node, "scroll")` SHALL not consult `handlers` either

#### Scenario: a click on a widget flagged under another event reads its aggregate pairs
- **WHEN** an unstamped node's widget has `mop: {drag: transitive}` and `dist: {drag: [[9,2]]}` (a touch listener; GATOR's `drag`), and no `click` key
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[9,2]]` and source `ID`
- **AND** under `ape.mopScoring=distance` a click on it SHALL weigh `ape.mopWeightD2` (400) before the tie split, as under `flag` it weighs `ape.mopWeightTransitive`
- **AND** `lookup(activity, node, "longClick")` SHALL return `transitive = true` (the flags' aggregate fallback) and no pair, so a long-click on it weighs 0 under `distance`

#### Scenario: the click aggregate leaves out long-press events
- **WHEN** an unstamped node's widget has `mop: {longclick: transitive, select: transitive}` and `dist: {longclick: [[3,1]], select: [[9,2]]}`, and no `click` key
- **THEN** `lookup(activity, node, "click")` SHALL return `dist = [[9,2]]`, without target 3

#### Scenario: an explicit event key blocks the aggregate pairs
- **WHEN** an unstamped node's widget has `mop: {click: none, select: transitive}` and `dist: {select: [[9,2]]}`
- **THEN** `lookup(activity, node, "click")` SHALL return no flag and no pair, with source `ID`, as the flags read `click` alone

#### Scenario: a Compose invoke class answers click and longClick
- **WHEN** a Compose node with no resource id carries `handlerClick = "com.x.ui.ScreenKt$Body$1$1"` and `handlerLongClick = null`, and `handlers` lists `com.x.ui.ScreenKt$Body$1$1` with `mop: {click: transitive, longclick: transitive}` and `dist: {click: [[3,2]], longclick: [[3,2]]}` (its `invoke()` fills both events)
- **THEN** `lookup(activity, node, "click")` SHALL return `transitive = true`, `dist = [[3,2]]` and source `STAMP`
- **AND WHEN** another Compose node carries the same class under `handlerLongClick`
- **THEN** `lookup(activity, thatNode, "longClick")` SHALL return the same flags and pairs with source `STAMP`

---

### Requirement: Target Retirement

Under `ape.mopScoring=distance`, the agent SHALL keep one counter per target, sized by the artifact's `targets` (`MopRetirement`, design D19 and API Design). `MopRetirement` is created once per run by `StatefulAgent` after the artifact loads; it is null when no artifact is loaded or when the mode is `flag`.

Retirement SHALL be counted at selection, in `StatefulAgent.moveForward` (`:1495-1504`), where the coverage tracker records the interaction: `updateStateInternal` calls `resolveNewAction()` (`StatefulAgent.java:850`), then `markVisited`, `recordActionHistory` and `moveForward()` (`:852-854`), so the counted action is the one dispatched next. Before `recordInteraction` marks the selected action as seen, the agent SHALL check whether this is the first interaction of the action's widget in its activity (`UICoverageTracker.hasActivityInteraction(activity, widgetId(action))` is false). Novelty is per (widget, action type): `widgetId` is `xpath|ActionType` (`UICoverageTracker.java:240-250`), so a long-click on a widget already clicked in the activity is a first interaction. If it is, the agent SHALL take the **weight's lookup** for the action's event (`MopScorer.eventTypeOf`): among the action's containment lookups ("Parent/child widget granularity reconciliation"), the one holding the action's smallest unretired distance, ties going to the earlier node in containment order (the node itself, its ancestors, then its descendants). It is the lookup behind `dec.mopd`/`dec.mops` (`event-sink`) and the shortcut's target count (`action-selection`), so what gives an action its weight is what retires it (author, 2026-10-07 night). Only an action the score treats as MOP retires anything: when the action's weight before the tie split is 0 (no containment lookup has an unretired pair, or its smallest unretired distance is 4 or more, or the arm's distance weights are 0), nothing is counted, so a target is not retired by exploration it did not attract (author, 2026-10-09). Otherwise the agent SHALL keep the weight's lookup as the step's pending count. `ApeAgent.updateState` passes the selected action through `checkRestart` after `updateStateInternal` (`ApeAgent.java:363`); when a restart replaces it, the action never runs, so the `StatefulAgent.checkRestart` override, the one that already drops the replaced action's history entry (design D12), discards the pending count, and when the action survives it applies it by calling `MopRetirement.onFirstInteraction(weightLookup)` (review WR-01, author, 2026-10-09). That call adds one to the counter of each target index in the lookup's `dist` whose pair lies at `d*`, the lookup's smallest unretired distance (the distance behind `dec.mopd`), each index once, and returns the indices whose counter reached `ape.mopRetireAfter` (default 3) on this call. Those indices are the step's `dec.ret`: the `checkRestart` override passes them to `EventSink.mopRetired` (`event-sink`, D23), because the decision call has already run (`StatefulAgent.java:1581-1590`, inside `resolveNewAction`).

A target whose counter is at least `ape.mopRetireAfter` is **retired**. The key has no lower bound and resolution does not reject any integer: with `ape.mopRetireAfter <= 0` every target reads as retired from the start, so under `distance` every weight is 0 and the run explores without distance guidance, no `dec.ret` is written and `RUN_END.retired` stays 0 (author, 2026-10-09: a parameter value never aborts a run). A retired target SHALL NOT count toward an action's distance in "MopScorer — Priority Boost", toward the prompt marker (`llm-prompt` "MOP Marker Annotation"), or toward the launcher order (`component-triggering`). `MopRetirement.nearest(int[][] dist, int dmax)` returns the smallest `d` over the unretired pairs with `d ≤ dmax`, or `Integer.MAX_VALUE` when there is none. The record SHALL be made at selection from the selected action, which is the action dispatched next; that meets the plan's "a partir da ação executada" (§6.6 `:816-818`). It SHALL NOT be rebuilt afterwards from an xpath and a short id.

The following are known limits, kept as decided (D19; plan review R12, `20261006_revisao_analise_plano_guia_mop.md:758-778`, and the analysis `20261006_analise_rigorosa_plano_guia_mop.md:281-287`):
- an off-tree LLM tap (`MODEL_LLM_TAP`) has no node, so it never counts;
- `widgetId` comes from `Name.toXPath()`, so a refinement that renames a widget makes its next interaction a first one again.

Only the targets at `d*` are counted (author, 2026-10-09; design D19 as amended). Widget and handler lists carry every target at `d ≤ 3` (`static-analysis-entrypoints`), and counting a whole list would retire up to several hundred targets through one interaction.

#### Scenario: first interaction counts each target of the node's own lookup once
- **WHEN** under `distance` the agent executes a click on a node whose own lookup has `dist = [[16,3],[17,3],[18,3]]`, no containment node carries a nearer unretired pair, and the widget has no interaction yet in its activity
- **THEN** the counters of targets 16, 17 and 18 SHALL each increase by exactly 1
- **AND** no target of another containment node's lookup SHALL be counted

#### Scenario: only the targets at the weight's distance count
- **WHEN** under `distance` the agent executes the first interaction of a click whose weight's lookup carries `[[4,1],[7,2],[9,3]]`, no target retired
- **THEN** the counter of target 4 SHALL increase by exactly 1, and the counters of targets 7 and 9 SHALL NOT change
- **AND WHEN** target 4 is retired and another widget's first interaction has a weight's lookup carrying `[[4,1],[7,2],[11,2]]`
- **THEN** `d*` SHALL be 2 and the counters of targets 7 and 11 SHALL each increase by exactly 1, while target 4's SHALL NOT change

#### Scenario: a weight won through containment retires its own targets
- **WHEN** under `distance` the agent executes the first interaction of a click on a clickable `LinearLayout` with no resource id and no stamp, whose child `TextView` resolves through the id key to `dist = [[7,2]]`, so the action's weight (400) comes from the child
- **THEN** the counter of target 7 SHALL increase by exactly 1
- **AND** `dec.mopd` of that step SHALL be 2, from the same lookup

#### Scenario: a first interaction with weight 0 counts nothing
- **WHEN** under `distance` the agent executes the first interaction of a click whose containment lookups carry only `[[5,4],[9,6]]`, so the action's weight before the tie split is 0
- **THEN** no counter SHALL change and the step SHALL carry no `dec.ret`

#### Scenario: an action a restart replaces counts nothing
- **WHEN** under `distance` the agent selects the first interaction of a widget whose weight's lookup carries `[[22,2]]`, and `checkRestart` then replaces the action with a start action
- **THEN** no counter SHALL change and the step SHALL carry no `dec.ret`
- **AND WHEN** `checkRestart` returns the selected action unchanged
- **THEN** target 22's counter SHALL increase by exactly 1

#### Scenario: second interaction of the same widget in the same activity does not count
- **WHEN** under `distance` the same widget is executed again in the same activity, so `hasActivityInteraction` returns true
- **THEN** no counter SHALL change

#### Scenario: a target retires at the third count
- **WHEN** under `distance` with `ape.mopRetireAfter=3`, target 22 has counter 2, and a first interaction of another widget whose lookup carries `[22,2]` is executed
- **THEN** target 22's counter SHALL become 3 and `onFirstInteraction` SHALL return `[22]`
- **AND** the step's record SHALL carry `dec.ret` with index 22, and `retired(22)` SHALL be true for the rest of the run

#### Scenario: retired target ignored by distance scoring
- **WHEN** under `distance` an action's lookup carries `dist = [[4,1],[7,2]]` and target 4 is retired
- **THEN** the action's distance SHALL be 2 and its weight before the tie split SHALL be `ape.mopWeightD2` (400)
- **AND WHEN** target 7 is also retired
- **THEN** the action's weight SHALL be 0

#### Scenario: no counting under flag
- **WHEN** `ape.mopScoring=flag` and the agent executes the first interaction of a widget whose lookup carries pairs
- **THEN** no `MopRetirement` SHALL exist, no counter SHALL change, and no step record SHALL carry `dec.ret`

#### Scenario: an off-tree LLM tap never counts
- **WHEN** under `distance` the executed action is a `MODEL_LLM_TAP` at a pixel that lies inside a widget whose lookup carries pairs
- **THEN** no counter SHALL change, because the tap has no resolved node to look up

---

## Invariants

- **INV-MOP-01**: `MopData.load()` SHALL never throw a checked or unchecked exception to the caller. All I/O and parse errors SHALL be caught internally and result in a `null` return with a WARNING log.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `Config.mopDataPath` is `null`, the MOP scoring pass SHALL be skipped entirely. The `sata` variant's behaviour SHALL be identical with and without `MopData.java` present in the JAR.
- **INV-MOP-05**: The WTG scoring pass SHALL execute AFTER the existing MOP scoring pass in `adjustActionsByGUITree()`. Pass order: base priority -> unvisited bonus -> state transition bonus -> MOP boost -> WTG boost -> coverage boost.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0.
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.
- **INV-MOP-21**: Every `MopData.load` invocation SHALL emit exactly one `[APE-MOP-DATA]` status line, never to logcat.
- **INV-MOP-22**: A run with `ape.mopDataPath` set SHALL either have non-null `_mopData` or abort; it SHALL never run as pure SATA.
- **INV-MOP-23**: Static-widget resolution for typed input and MOP resolution SHALL walk the same containment node set: the node, up to two ancestors, and its descendants two levels down. On each node, MOP resolution applies `MopData.lookup` (stamp, then id key), and typed-input resolution applies the id key, because the static metadata is keyed by id. The two paths SHALL NOT diverge in which nodes they visit or in the order they visit them.
- **INV-MOP-24**: above the `+1` activity-substrate floor, `stateMopDensity` SHALL count only MOP-flagged resolved widgets; it SHALL never reduce to a total action count, and the floor SHALL be exactly `1` (never proportional to action count or widget count).
- **INV-MOP-32**: `mopActivities`/`mopActsAugmented` SHALL be pure counters over the load; their presence or values SHALL NOT change widget flags, the `mopActivities` set itself, or the loaded/rejected decision.
- **INV-MOP-33**: the tiebreak log SHALL be emitted only from the path-selection site and only on the decisive branch; it SHALL NOT alter which path is selected.
- **INV-MOP-19**: On a `shortId` collision within a base activity, the widget's MOP flags SHALL be the strongest (direct > transitive > unflagged), an unflagged widget SHALL never overwrite a flagged one, and the widget's `dist` list for each event SHALL be the merge of the colliding widgets' lists by the minimum distance per target, cut to the targets at `d ≤ 3` (design D15 as amended 2026-10-09). Both outcomes SHALL be order-independent. Both rules are applied by the generator (design D15); on this side a JSON object admits one value per key, so a collision is unrepresentable.
- **INV-MOP-20**: Widgets with an empty `idName` SHALL NOT be stored; the count of MOP-flagged widgets dropped for lacking a resource id SHALL be logged once per load.
- **INV-MOP-25**: After load, every DIALOG window reachable via a WTG transition SHALL have its widgets queryable under the host activity's key (subject to the standard collision policy) and removed from the dialog-class **widget-map** key; when a merged widget is flagged, `activityHasMop(host)` SHALL reflect it. The move-not-copy removal applies to the widget map only — the dialog class's Pass-2 `mopActivities` entry SHALL be retained so the OPTIONSMENU-gateway precompute (condition 2) still fires for activities that navigate into a MOP-bearing dialog. Orphan (unreachable) DIALOG windows are counted on a dedicated `[APE-RV]` diagnostic line, never on the `[APE-MOP-DATA]` status line.
- **INV-MOP-34**: `MopData.load` SHALL accept an artifact whose `formatVersion` is `2`, and no other value. A missing `formatVersion`, the value `1`, or any other value SHALL yield `status=rejected reason=version-mismatch` and a null return. The jar holds no reader for format 1. The generator (`static-analysis-entrypoints`, rv-android `gh122`) emits `2`, and both sides move together.
- **INV-MOP-36**: Every reader of MOP flags or MOP distances for a node and an event SHALL obtain them from `MopData.lookup(activity, node, eventType)`: the scoring pass, the shortcut ranking (`action-selection`), the prompt marker and the `MOP NAVIGATION` count (`llm-prompt`), and the retirement trigger. A stamp SHALL be consulted only for the events `click` and `longClick`, and only when `handlers` lists the stamped class. A listed class SHALL decide the result even when it reaches nothing. Outside `MopData` itself, `MopData.getWidget` SHALL remain only for the static metadata of the list (`buildWidgetMetadata`) and for typed input.
- **INV-MOP-37**: Retirement SHALL exist only under `ape.mopScoring=distance`. A target's counter SHALL change only for a selected model action that survives `StatefulAgent.checkRestart` (the count is taken at selection in `StatefulAgent.moveForward` and applied in the `checkRestart` override; a restart that replaces the action discards it), by one per first interaction of the executed action's widget in its activity, for each target of the executed action's weight's lookup (the containment lookup holding its smallest unretired distance, "Target Retirement") whose pair lies at that distance `d*`. Counters SHALL never decrease, so a retired target stays retired for the rest of the run. Under `flag`, no counter exists and no target is retired.

---

## Data Contracts

### Input
- `Config.mopDataPath: String` — device path to static analysis JSON (null = MOP disabled)
- Static analysis JSON file at `Config.mopDataPath` — produced by rv-android static analysis component; format: `{"windows": [...], "reachability": [...]}`

### Output
- `ModelAction.priority` boosted for MOP-reachable widget actions (additive, in-memory only; not persisted)
- WARNING log entry when JSON is missing or malformed

### Side-Effects
- None beyond the in-memory priority adjustments on `ModelAction` objects
- **[Trace]**: `[APE-MOP-DATA] status=rejected reason=too-large size=<bytes> budget=<bytes>` — file exceeds the parse budget (pre-read).
- **[Trace]**: `[APE-MOP-DATA] status=rejected reason=oom` — read/parse ran out of memory despite the guard (backstop).

### Error
- No exceptions propagate from `MopData` or `MopScorer` to callers
- Never propagates `OutOfMemoryError` or `IOException` to the caller; all failures return null after emitting exactly one status line (INV-MOP-21).
