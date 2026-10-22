# Specification: MOP-Guided Action Scoring

## Purpose

MOP guidance steers exploration toward widgets and activities that reach monitored operations (MOP specs). It is one of the behaviour groups mounted by declaration (`guidance-groups`, design D-B1): the group is switched by `ape.mop`, every MOP member (`WTG`, `MENU_GATEWAY`, `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`, `MOP_COIN`) depends on it, and a member whose group is off is absent from the plan. A use site asks for its member through `RunContext.current().spec().has(Feature.X)` and reads its parameters through `spec().mop()`.

`ape.staticDataPath` (`STATIC_DATA`) points at the **compact artifact, format 2**, on the device, and the jar reads that format only (INV-MOP-34): besides the format-1 flags, activity sets, menus, WTG and components, it carries the target count `targets`, per-widget distance pairs `dist` (`[i, d]`, every target at `d ≤ 3`), per-activity pairs `activityDist` (the three nearest targets), a `handlers` table from handler class to flags and pairs and, additively, the reduced intent-filter `data` of receivers and services (D-B13). The artifact is loaded if and only if `STATIC` or `MOP` is mounted (D-B14.20). `StaticArtifactLoader.load` reads it once and splits it into two read-only providers: `StaticGuiModel` (package, main activity, WTG, widget metadata, components) and `MopData` (targets, distances, handlers, MOP activities). STATIC reads only the first; MOP reads both (D-B2). Typed input and the prompt's widget metadata belong to STATIC and read `StaticGuiModel`. Without `MOP`, no `MopData` reaches any consumer, even when the artifact was loaded for STATIC (D-B14.21).

One lookup, `MopData.lookup`, answers what MOP knows about a node for an event: the handler stamp's class when `handlers` lists it, else the resource-id key (INV-MOP-36). `MopScorer` turns that answer into a weight in one of two modes selected by `ape.mopScoring`: `flag` (default), the direct/transitive mark, or `distance`, a weight by the nearest unretired target, with targets retiring after a number of first interactions (INV-MOP-37). The boosts are applied by the MOP scoring passes (`MopWidgetPass`, `MopFrontierPass`, `MenuGatewayPass`, `WtgPass`) that run inside `StatefulAgent.adjustActionsByGUITree()`, after the base SATA priority is assigned and before the agent selects; they do not read the interaction ledger (D-B19, reverted).

MOP does not decide inside SATA. Its decider is the **MOP coin** (`MOP_COIN`), a stage after the LLM coin (`decision-pipeline`): eligible when the screen holds an action with a positive unretired weight whose (activity, `xpath|ActionType`) key `MopInteractionLedger` has counted fewer than `ape.mopCoinLedgerMaxInteractions` times (default 1), it takes the action with the largest weight with probability `p_M` (`ape.mopCoinPercentage`, default 1.0), drawn from its own stream (D-A10, D-A15, D-B10, D-B17). `ape.mopTargetPickCap` is a retired key. Inside SATA, MOP data is read only by the MOP variants of the extension points X3 and X4 (`action-selection`, D-A7, D-B12), mounted only with `MOP`. The launcher (`MOP_LAUNCHER`) and its cadence are specified in `component-triggering` and `decision-pipeline`, and the telemetry fields in `event-sink`.

**The jar derives nothing.** The cross-referencing that identifies which widgets reach a monitored operation — call-graph reachability and distances, listener-to-handler joins, collision resolution between widgets sharing a short id — happens **host-side**, in the rv-android generator that produces the artifact. What reaches the device is the result: per `(baseActivity, shortId)` a widget carries a per-event `mop` flag distinguishing a direct from a transitive reach and its per-event distance pairs, a handler class carries the same for its events, activities arrive as two precomputed sets with their distance pairs, and the trigger surface arrives as declared fields. So the artifact has no `windows[]`, no `reachability[]` and no `transitions[]` to parse, and a **legacy full static-analysis JSON is rejected rather than parsed**, as is a format-1 artifact: the version gate refuses both.

**Absence is fatal on a cell that reads the artifact, deliberately.** A run whose plan mounts `STATIC` or `MOP` and whose artifact is missing or unreadable aborts instead of continuing without data (INV-MOP-22, INV-APERV-05); switching either group on without `ape.staticDataPath` is a plan error. Silently degrading to bare APE while still reporting as a guided cell is the failure class this capability exists to prevent: it produces a run that looks like treatment and behaves like control. A cell with neither group never loads an artifact.

Two things stay computed on the device on purpose. The **OPTIONSMENU gateway** is recomputed at runtime from the artifact's menu and activity-set views rather than shipped precomputed (INV-MOP-13), because whether the gateway opens depends on which activity set the run selected. And the **activity set is chosen at load**: `mopActivities` (widget-derived) or, with `MOP_ACTIVITY_SOURCE` (`ape.mopActivitySourceComponents`) in the plan, `mopActivitiesAugmented` (the A′ union of three sources) — the same set that feeds the launcher in `component-triggering`.

---

## Requirements

### Requirement: MopData — Static Analysis JSON Loader

`StaticArtifactLoader.load(String path, String expectedPackage, String expectedMainActivity, EventSink sink)` SHALL parse the **derived compact artifact** (`formatVersion: 2`) produced host-side by `aperv-tool` — wire format 2, with the additive intent-filter `data` member (D-B13; design P-1) — exactly once, and SHALL yield two read-only providers (`StaticArtifactLoader.Loaded`; design § Architecture). The public entry reads the `MOP_ACTIVITY_SOURCE` choice from the plan; a package-private five-argument form that takes the choice as a `boolean` is the test seam.

- **`StaticGuiModel`** — the static GUI model: `package`, `mainActivity`, the WTG view, the widget metadata and the manifest components with their intent filters (design D-B13). The STATIC group reads only this provider (`NavFrontierPass`, typed input, the prompt's widget metadata, component triggering).
- **`MopData`** — the spec targets: the target count, the MOP flags and distance pairs of widgets and handlers, `activityDist`, the MOP-activity sets, the per-component `reachesMop` facts and the OPTIONSMENU gateways. The MOP group reads both providers: the MOP members that need the WTG or the components (`WtgPass`, `MopFrontierPass`, `MopScorer`, `MopLauncherStage`) read them from `StaticGuiModel` (plan P-1). `MopLauncherStage` reads the manifest activities, the main activity and the package from `StaticGuiModel`, and the MOP-activity census and `activityDist` from `MopData`.

The agent's `getMopData()` SHALL return null if and only if `MOP` is not mounted, even when the loader ran because `STATIC` is mounted (design D-B14.21): a `MopData` parsed for a STATIC-only cell is not handed to any consumer. No MOP consumer SHALL then hold a `MopData` reference, and the UICOV coverage dump (`SataAgent.dumpCoverage`) SHALL write `mopReach` only when `MOP` is mounted and omit it otherwise, consistent with `ACT.mop` (`event-sink` INV-SNK-18).

The loader runs if and only if the resolved plan mounts `STATIC` or `MOP` (design D-B14.20); both groups require `STATIC_DATA` (`ape.staticDataPath` set, `guidance-groups`). It hands each provider only to the mounted members that consume it. A path given while neither group is mounted is inert: it is listed in `RUN_START.inert` (`run-spec`), and no load, no `MOP_DATA` census and no rejection follow. Neither provider is constructed in a cell without the artifact (`base`). The items below say which provider serves each part of the wire:

1. **Version gate**: the top-level `formatVersion` field is mandatory and SHALL equal `2` (`StaticArtifactLoader.SUPPORTED_FORMAT_VERSION`, checked in `StaticArtifactLoader.load` before any other member is read). A missing value, `1`, or any other value ⇒ `status=rejected reason=version-mismatch`, null return (INV-MOP-34). There is no reader for format 1 (design D15, SDD-P3): a format-1 `.mop.json`, derived before the bump or served by a stale host cache, makes a MOP arm abort (INV-MOP-22). There is no `complete` sentinel: the version gate is the only document-level check the jar makes.
2. **Top-level scalars**: `StaticGuiModel.packageName()`, `StaticGuiModel.mainActivity()` from the artifact's `package` / `mainActivity`, and `MopData`'s target count (`MopData.targetCount()`) from `targets` (a non-negative integer; the number of entries of the producer's `distanceTargets`, direct targets followed by boundary targets). Every distance pair indexes this count. No target signature and no target kind is on the wire, and the jar does not distinguish boundary targets from direct ones (design D15, "B weighs as C"). A format-2 artifact that lacks `targets` or `handlers`, or whose `targets` is not an integer, SHALL be rejected with `status=rejected reason=parse-error` and a null return, so a MOP arm aborts (INV-MOP-22): the two members are always emitted (`static-analysis-entrypoints`), and their absence is a generator↔jar skew, not an empty table. A `targets` of `0` is valid and loads in both modes: a document without `distanceTargets` (a pre-gh120 `.apk.json`, which no guard stops, design D15) or an empty one derives to it. The run SHALL continue without distance data (under `distance` every weight is 0 and the launcher order has no key), and `MOP_DATA.targets` reports `0`. The jar has no special treatment for it (author, 2026-10-08).
3. **Widgets**: the artifact's `widgets` map (`baseActivity → shortId → widget`) is split by provider. `StaticGuiModel.widget(activity, shortId)` serves the consumed metadata fields (`StaticGuiModel.WidgetMeta`) — `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (each null/empty when absent on the wire). `MopData` keeps, under the same key, the MOP flags decoded from the wire `mop` map (normalized eventType → `none|direct|transitive|both`): `directMopByEventType`/`transitiveMopByEventType` are populated with an entry for **every** wire key (including `none` entries — key presence drives the per-event vs aggregate fallback), and the aggregate `directMop`/`transitiveMop` are the OR across the map values. The flag accessors `isDirectMop`/`isTransitiveMop` (`MopData.MopEntry`) apply the normalization and fallback of INV-MOP-08 and INV-MOP-14. A widget additionally carries its distance pairs from the wire `dist` map (normalized eventType → `[[i, d], …]`), stored per normalized event in the order the wire gives them (ascending `d`, then `i`; the generator bounds a widget's or handler's list to the targets at `d ≤ 3` and an `activityDist` entry to three pairs, and the loader reads any length). An absent `dist` means no pair for any event. The widget also keeps its click aggregate, the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (`MopData.clickAggregate`, `MopData.mergeByMinimum`), which `lookup` reads for a click when the widget's `mop` map has no `click` key ("Handler Stamp Lookup"). Fields with no production consumer (`id`, `type`, `text`, raw `listeners`) SHALL NOT exist on the wire or in the model.
4. **Activity distances**: the wire `activityDist` map (`baseActivity → [[i, d], …]`) SHALL be loaded as a per-activity pair list in wire order (`MopData.activityDist(activity)`), served to the launcher (`component-triggering`, design D20). An activity absent from the map has no pair.
5. **Handler table**: the wire `handlers` map (binary class name → `{mop: {event: flag}, dist: {event: [[i, d], …]}}`) SHALL be loaded as a per-class record (`MopData.Handler`), with the event keys normalized and the flags decoded as for widgets. A listed class with `mop` `none` and no `dist` SHALL be kept: it states that the handler reaches no target, and "Handler Stamp Lookup" relies on it. The table is read only by `MopData.lookup`.
6. **MOP-activity sets** (`MopData`): `mopActivities` and `mopActivitiesAugmented` are read as-is from the wire. `getMopActivities()` / `activityHasMop(activity)` SHALL serve the augmented set when `MOP_ACTIVITY_SOURCE` is in the resolved plan and the widget-derived set otherwise (INV-MOP-27). Their meaning is format 1's, except that the generator's A′ source 3 no longer counts an activity class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`); the distance data does not change them.
7. **OPTIONSMENU gateways** (`MopData`): `activityHasMopOptionsMenu(activity)` SHALL be computed at load time (`MopData.recomputeMopOptionsMenus`) from the artifact's `optionsMenus[]` records (`{activity, hasFlaggedWidget}`) and `StaticGuiModel`'s WTG view: an activity qualifies iff its record has `hasFlaggedWidget == true` OR any WTG click edge from that activity targets an activity in the *selected* MOP-activity set (INV-MOP-13).
8. **WTG** (`StaticGuiModel`): `hasWtgData()` / `wtgTransitions(activity)` serve the wire `wtg` map (`sourceBaseActivity → [{widget, target}]`, already base-activity-keyed and deduplicated host-side per INV-WTG-04). `StaticGuiModel.WtgTransition` carries `widgetName` and `targetActivity`; there is no `widgetClass` field.
9. **Components** (`StaticGuiModel`, design D-B13): each entry (`ComponentInfo`) carries `className`, `componentType` (from the parent array key), `isMain`, `permission` (null when no gate); activities additionally carry `deepLinkUri` (null when absent on the wire); receivers/services carry `intentFilters` (`actions`, `categories` and, when the wire has it, the reduced `data` member `{schemes, hosts, paths, pathPrefixes, mimeTypes}`, empty lists omitted, `ports` and `pathPatterns` not projected (design D-B14.11); an absent `data` reads as "no data") and `hasTargetMethods` (boolean — the only consumed use of the former signature list is an emptiness test); providers additionally carry `authorities`. The wire `reachesMop` (the D7 rename of the producer's `reachesTarget`) of each component is kept by `MopData` alone (`MopData.reachesMop(className)`), keyed by class name, for the MOP consumers that need it; `StaticGuiModel` exposes no `reachesMop`. The `data` member is additive inside `formatVersion: 2`. The full D15 `data` block of the gh60 producer (beyond the four reduced lists), `readPermission`/`writePermission`, the `targetMethods` signature list, and `exported` SHALL NOT exist on the wire (they remain in the host-side full JSON). `exported` leaves with them because no jar code path reads it and none may: the launcher's eligibility rule forbids consulting it, so its absence makes that prohibition structural rather than merely stated.

   The activity launcher SHALL read `ComponentInfo.deepLinkUri` directly and pass it to `ActivityTriggerAction` unchanged, so `MonkeySourceApe.generateActivityTriggerEvent` dispatches `Intent.ACTION_VIEW` + `Uri.parse(uri)` + `setPackage` when it is non-null and the explicit component otherwise, with the assembly rule living in the generator (rv-android INV-DRV-07).
10. **Sanity check** (`StaticArtifactLoader`, before either provider is handed out): `package`/`mainActivity` comparison per the "MopData — Package / MainActivity Sanity Check" requirement.
11. **Stats echo**: the `MOP_DATA` record echoes, without recomputation, the generator-computed diagnostics of the artifact's `stats` block — `windows`, `widgetsTotal` (as `widgets`), `flagged`, `droppedFlaggedNoId` (as `droppedNoId`), `handlersUnmatched`, `syntheticLambda`, `recovered`, `wtgEdges`. `stats.orphanDialogs` stays in the artifact and is not echoed. The echo SHALL NOT influence any query result or the load outcome (INV-MOP-32).

**Malformed distance pairs.** A pair, in `dist`, `activityDist` or `handlers`, that is not a two-integer array, whose index lies outside `[0, targets)`, or whose distance is negative, SHALL be dropped at load and counted (`droppedPairs` on the load record, "MopData — Load Status Line and Fail-Fast"). The load SHALL continue on the remaining pairs and SHALL NOT be rejected for it (design, Error Handling). A conforming generator emits no such pair, so a non-zero count is a generator↔jar skew signal.

Unknown JSON keys within `formatVersion: 2` are ignored (INV-MOP-11). This tolerance is not a compatibility affordance: it is the `org.json` DOM's default behavior, the *absence* of a check, so rejecting unknown keys would mean writing new code. Generator and jar ship together, so an unknown key is a skew signal, not a version to accommodate. The file is read once with a single pre-sized allocation and parsed once into an `org.json` DOM. No cross-referencing, flag or distance derivation, dialog re-keying, activity augmentation, or transition processing happens on-device; the loader derives only the OPTIONSMENU gateway set and each widget's click aggregate (INV-MOP-35). The rest are generator requirements (`static-analysis-entrypoints` "Derived compact MOP artifact — projection contents"). The INV-DRV-* invariants this specification cites are rv-android's (its `aperv` spec).

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so every header of the main spec is kept, with its body restated for format 2. Several headers were written about the full-JSON parser and read oddly against a body about the compact reader; they are kept to be matched, not as evidence that anything parses a call graph on the device. The two package-sanity headers belong to the "MopData — Package / MainActivity Sanity Check" requirement, and each body says so.

#### Scenario: Real cryptoapp fixture loads every typed field
- **WHEN** `StaticArtifactLoader.load()` is called on the `cryptoapp.apk.mop.json` fixture, format 2, derived by the rv-android generator (`gh122`) from the gh120 fixture `modules/rv-static-analysis/tests/resources/cryptoapp.apk.json` (task 10.1)
- **THEN** the returned `StaticGuiModel` and `MopData` SHALL both be non-null
- **AND** `StaticGuiModel.packageName()=="br.unb.cic.cryptoapp"`, `StaticGuiModel.mainActivity()=="br.unb.cic.cryptoapp.MainActivity"`, and `MopData.targetCount()` SHALL be 27 (23 direct targets, then 4 boundary targets, the four activity constructors)
- **AND** the MOP-flagged widgets SHALL be exactly three: `buttonGenerateHash` (MessageDigestActivity), `btn_cipher_encrypt` (CipherActivity), and `executeButton` (CryptographyActivity) — all `transitiveMop==true`, `directMop==false`
- **AND** their `click` pairs SHALL be `[[22,2]]`, none and `[[16,3],[17,3],[18,3]]` respectively: `btn_cipher_encrypt`'s nearest targets, 0 and 1, lie at `d = 4`, beyond the generator's `d ≤ 3` cut, so the widget carries no `dist`, and `executeButton` carries every target of its wrapper at `d ≤ 3`. `executeButton` is flagged through the exact join: under gh120 its wrapper `CryptographyActivity$$ExternalSyntheticLambda0.onClick` is listed in `reachability[]` reaching, because the producer links it to its own `lambda$setupExecuteButton$0$CryptographyActivity(android.view.View)` body, so the class recovery is not consulted (rv-android INV-DRV-09)
- **AND** the handler table SHALL list exactly `br.unb.cic.cryptoapp.cipher.CipherActivity$1` and `br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0`, the two classes of the fixture that declare `onClick(android.view.View)`, each with `click` `transitive` and the same pairs as its widget (none for `CipherActivity$1`)
- **AND** `activityHasMop` SHALL be true for `MessageDigestActivity`, `CipherActivity` and `CryptographyActivity`, and false for `MainActivity` (default flag state). With `mopActivitySourceComponents=true` it SHALL still be false for `MainActivity`: under gh120 its only reaching method is its constructor, which reaches boundary target 23, and the A′ source 3 leaves out a class's own `<init>`/`<clinit>` (`static-analysis-entrypoints`), so on this fixture `mopActivitiesAugmented` equals `mopActivities`
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway via WTG edges to the MOP sub-activities, INV-MOP-13)
- **AND** `StaticGuiModel.widget` for the `spinnerMessageDigest` widget SHALL carry its 13 `entries` and its metadata fields
- **AND** `StaticGuiModel`'s `activities().size()==4`; `providers().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `receivers().isEmpty()`; `services().isEmpty()`; `MopData` SHALL record `reachesMop==false` for every component

#### Scenario: the as cell loads the artifact and exposes no MopData
- **WHEN** the `as` cell runs with `ape.staticDataPath=/data/local/tmp/mop-artifact.json` on `cryptoapp.apk.mop.json`
- **THEN** the loader SHALL parse the artifact once, `StaticGuiModel` SHALL be non-null and `getMopData()` SHALL return null
- **AND** no constructed component SHALL hold a `MopData` reference, and the UICOV coverage dump SHALL carry no `mopReach` member
- **AND WHEN** the `as_mop` cell runs on the same artifact
- **THEN** `getMopData()` SHALL be non-null and the UICOV dump SHALL carry `mopReach`

#### Scenario: Legacy full static-analysis JSON is rejected
- **WHEN** `StaticArtifactLoader.load()` is pointed at a full static-analysis JSON (top-level `windows`/`reachability`, no `formatVersion`)
- **THEN** `load` SHALL return no provider
- **AND** emit exactly one `MOP_DATA` record with `status` `rejected` and `reason` `version-mismatch`
- **AND** with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the run SHALL abort via `StopTestingException` (INV-MOP-22) — never proceed as pure SATA
- **AND WHEN** `StaticArtifactLoader.load()` is pointed at a format-1 compact artifact (`"formatVersion": 1`)
- **THEN** the outcome SHALL be the same `version-mismatch` rejection and, with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the same abort (INV-MOP-34)

#### Scenario: Per-event-type reachability maps built
- **WHEN** a wire widget has `"mop": {"click": "both", "scroll": "none"}`
- **THEN** `isDirectMop("click")==true` and `isTransitiveMop("click")==true`; `isDirectMop("scroll")==false` and `isTransitiveMop("scroll")==false` (explicit `none` entry, no aggregate fallback)
- **AND** `isDirectMop("longClick")==true` (key absent ⇒ aggregate fallback; aggregates are the OR of the respective bits over the map)
- **AND** the wire values decode to the two bits positionally (`none`=00, `direct`=10, `transitive`=01, `both`=11)
- **AND** the loader SHALL decode `direct` faithfully rather than rejecting it, even though a conforming generator never emits it: `direct` implies `transitive` at derivation time (rv-android INV-DRV-01)
- **AND WHEN** the same widget carries `"dist": {"click": [[2,0],[5,1]]}`
- **THEN** its `click` pairs SHALL be `[[2,0],[5,1]]`, in that order, and its `scroll` pairs SHALL be empty (explicit `none` entry, no aggregate fallback)
- **AND** its `longClick` pairs SHALL be empty: only a click falls back to an aggregate of pairs, and only when the widget has no `click` key

#### Scenario: Flag-selected MOP-activity set
- **WHEN** the artifact carries `mopActivities=["A"]` and `mopActivitiesAugmented=["A","B"]`
- **THEN** with `MOP_ACTIVITY_SOURCE` absent from the plan, `activityHasMop("B")==false` and `getMopActivities()` SHALL equal `{"A"}`
- **AND** with `MOP_ACTIVITY_SOURCE` in the plan, `activityHasMop("B")==true` and `getMopActivities()` SHALL equal `{"A","B"}`
- **AND** the OPTIONSMENU-gateway recompute SHALL use the selected set for its condition-2 test
- **AND** neither set SHALL depend on `activityDist`

#### Scenario: Deep-link dispatch reads the wire field
- **WHEN** the artifact's `components.activities[]` entry for `X` carries `"deepLinkUri": "myapp://detail/x"` and the MOP launcher selects `X`
- **THEN** the `ActivityTriggerAction` SHALL carry that string verbatim, and the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` targeted at the package via `setPackage`, the platform resolving the handling activity
- **AND WHEN** the entry omits `deepLinkUri`
- **THEN** the intent SHALL be the explicit-component intent
- **AND** the activity's `deepLinkUri` SHALL be served by `StaticGuiModel`; the reduced intent-filter `data` member is read only for receivers and services (`component-triggering`)

#### Scenario: Absent metadata stays null and costs zero tokens
- **WHEN** a wire widget omits `hint`, `prompt`, `entries`
- **THEN** the corresponding `StaticGuiModel.WidgetMeta` fields SHALL be null / empty
- **AND** the STATIC prompt contribution's metadata rendering and `ApeAgent.generateInputText` SHALL behave exactly as they do for null/empty fields (INV-MOP-10)

#### Scenario: Unknown keys in a v1 artifact are ignored
- **WHEN** a `formatVersion: 2` artifact carries an additional unknown top-level, per-widget or per-handler key
- **THEN** `load` SHALL succeed and ignore it (INV-MOP-11)
- **AND** a `formatVersion: 1` artifact SHALL NOT reach this tolerance: it is rejected by the version gate before any key is read (INV-MOP-34). The header names v1 only because the archive matches on it

#### Scenario: gh60 D15 component trigger-surface fields parsed
- **WHEN** `StaticArtifactLoader.load()` parses an artifact's `components` block
- **THEN** `StaticGuiModel`'s trigger surface SHALL be exactly `permission` (null when no gate, `hasPermissionGate()` false), `intentFilters` carrying `actions`, `categories` and the optional reduced `data` (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`), `hasTargetMethods` on receivers and services, and `authorities` on providers
- **AND WHEN** a receiver's filter carries `"data": {"schemes": ["content"], "mimeTypes": ["text/plain"]}`
- **THEN** its `IntentFilter` SHALL carry those two lists and empty `hosts`/`paths`/`pathPrefixes`, and a filter without `data` SHALL carry five empty lists
- **AND** the full gh60 `DataSpec`, `ProviderInfo.readPermission`/`writePermission`, the `targetMethods` signature list and `exported` SHALL NOT exist on the wire **or on the model**
- **AND** each component's `reachesMop` SHALL be held by `MopData` only; trigger selection is owned by `component-triggering` (a STATIC member, design D-B13), which reads no MOP data
- **AND** the absence of `exported` SHALL keep the launcher's prohibition on consulting it structural (`component-triggering` INV-CT-06)

#### Scenario: Bug-fix regression — widget transitiveMop derived from gh60 Target keys
- **WHEN** `StaticArtifactLoader.load()` is called on the format-2 cryptoapp fixture
- **THEN** the widget `buttonGenerateHash` in `MessageDigestActivity` SHALL have `transitiveMop==true`, `directMop==false` and `isTransitiveMop("click")==true`
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true`
- **AND** under `ape.mopScoring=flag`, a click action on an unstamped `buttonGenerateHash` node SHALL score `mopWeightTransitive` (300), through `MopData.lookup` with source `ID`
- **AND** the gateway SHALL hold: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **NOTE**: this scenario is the contract that "SATA-MOP is not silently bare APE" on this side. The derivation it used to assert is the generator's (rv-android INV-DRV-01) and is tested in rv-android. What the jar can still fail at, and asserts here, is decoding a flag that arrived correct into a boost that fires

#### Scenario: Widget metadata extracted on post-task-11 fixture
- **WHEN** `StaticArtifactLoader.load()` is called on the format-2 cryptoapp fixture
- **THEN** `StaticGuiModel.widget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "editTextMessageDigest")` SHALL carry `inputType=="textPersonName"` and `hint=="Input text ..."`
- **AND** the Spinner widget `spinnerMessageDigest` SHALL carry `entries.size()==13`, the JCA algorithm list
- **AND** `type` and `text` SHALL NOT be asserted, because they do not exist on the wire (INV-MOP-35)

#### Scenario: Top-level package and mainActivity sanity check (default warn-only)
- **WHEN** `StaticArtifactLoader.load(path, "x.y.z.OTHER", null, sink)` is called and the artifact's `package=="x.y.z"`
- **THEN** both providers SHALL be returned and a `WARN` log line SHALL be emitted naming both the expected and the parsed value
- **AND** this claim is owned by the "MopData — Package / MainActivity Sanity Check" requirement (scenario `Default warn-only on mismatch`)

#### Scenario: Package mismatch rejected in strict mode
- **WHEN** `ape.mopStrictPackageMatch=true` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null, sink)` is called on an artifact with `package=="x.y.z"`
- **THEN** `load` SHALL return no provider, a `WARN` log line SHALL be emitted, and the load record SHALL carry `reason=package-mismatch`
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
- **WHEN** `StaticArtifactLoader.load()` is called on a `formatVersion: 2` artifact with `targets: 0` whose `widgets`, `wtg`, `optionsMenus`, `activityDist`, `handlers`, activity sets and component lists are all empty
- **THEN** the returned `StaticGuiModel` and `MopData` SHALL both be non-null
- **AND** every accessor SHALL return empty (`MopData.getMopActivities()`; `StaticGuiModel`'s `receivers()`, `services()`, `activities()`, `providers()`, `wtgTransitions(any)`) and `StaticGuiModel.hasWtgData()` SHALL be `false`
- **AND** `MopData.lookup(any, any node, any event)` SHALL return `NONE` and every action SHALL score `0` in both scoring modes, without `NullPointerException`
- **AND** there SHALL be no `isComplete()` and no `complete` sentinel: what reaches the device is governed by the generator's preconditions (`static-analysis-entrypoints`)

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** two widgets of one base activity resolve to the short id `"submit"`, one `direct`-flagged on `click` with pairs `[[3,0]]` and one `transitive`-flagged on `click` with pairs `[[3,1],[5,2]]`
- **THEN** the artifact SHALL carry exactly one entry under `widgets.<activity>.submit`, and `MopData`'s entry for `(activity, "submit")` SHALL carry the `direct` flag
- **AND** its `click` pairs SHALL be `[[3,0],[5,2]]`, the merge by the minimum distance per target (INV-MOP-19)
- **AND** the resolution SHALL have happened host-side; on this side a JSON object admits one value per key, so the collision is unrepresentable

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the same two widgets appear in the reverse order in the producer's document
- **THEN** the artifact, and therefore `MopData`'s entry for `(activity, "submit")` and its pairs, SHALL be identical to the previous scenario's
- **AND WHEN** a third, unflagged widget with no pairs also resolves to `"submit"`, in any position
- **THEN** the entry SHALL keep the `direct` flag and the pairs `[[3,0],[5,2]]`
- **AND** the order-independence SHALL be the generator's, reinforced by rv-android INV-DRV-05: two orderings that derived to different artifacts would break byte-level determinism before they reached the jar

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity's only MOP-flagged widget carries an empty `idName`
- **THEN** the artifact SHALL carry no entry under the empty-string key for that activity, and neither `StaticGuiModel.widget(activity, "")` nor `MopData` SHALL hold one
- **AND** `activityHasMop(activity)` SHALL still return `true`, and the drop SHALL be counted in `stats.droppedFlaggedNoId`, echoed on the load record as `droppedNoId` (INV-MOP-20)
- **AND** a node of that widget that carries a handler stamp listed in `handlers` SHALL still be resolved by `MopData.lookup` with source `STAMP`: the stamp needs no resource id

#### Scenario: Malformed distance pair dropped and counted
- **WHEN** a `formatVersion: 2` artifact with `targets: 27` carries a widget with `"dist": {"click": [[16,3],[27,1],[-1,2]]}`
- **THEN** `load` SHALL succeed, the widget's `click` pairs SHALL be `[[16,3]]`, and the load record SHALL carry `droppedPairs=2`

#### Scenario: Format-2 artifact missing a format-2 member is rejected
- **WHEN** a `formatVersion: 2` artifact has no `targets` member, or no `handlers` member, or carries `"targets": "27"`
- **THEN** `load` SHALL return no provider and emit one `status=rejected reason=parse-error` record
- **AND** with `ape.staticDataPath` set and `STATIC` or `MOP` mounted, the run SHALL abort via `StopTestingException` (INV-MOP-22)

#### Scenario: an artifact with no target runs without distance data
- **WHEN** a `formatVersion: 2` artifact carries `"targets": 0`, under either `ape.mopScoring` value
- **THEN** `load` SHALL return both providers and emit `MOP_DATA` with `targets: 0`
- **AND** the run SHALL proceed, and under `distance` every action SHALL score 0

### Requirement: MopScorer — Priority Boost

`MopScorer.score(MopContext, State, ModelAction)` SHALL return an integer priority boost for one valid, target-requiring action (INV-MOP-02), computed from `MopData.lookup` (INV-MOP-36) over the action's containment nodes ("Parent/child widget granularity reconciliation") for the action's event (`MopScorer.eventTypeOf`). The boost is additive: `MopWidgetPass`, the MOP group's widget scorer, mounted only when `MOP` is in the plan (`scoring-pipeline` "Scorer Ownership by Group"), adds it to `ModelAction.priority`, never replacing it (INV-MOP-03), and stores it as the action's `mopBoost`. The mode is the plan key `ape.mopScoring` (`run-spec`), `flag` or `distance`, default `flag` (design D18); the `MopContext`, built from `spec().mop()`, carries the data, the mode, the weights and the run's retirement (design D17, Plumbing). No weight is read from `Config`.

**`flag` mode.** The boost comes from the largest flag weight over the containment lookups, ties to the earlier lookup in containment order; this is the strongest flag (direct > transitive > none) whenever `mopWeightDirect >= mopWeightTransitive`, as with the defaults:

| Best containment lookup | Boost |
|-----------|-------|
| direct flag for the event | `+mopWeightDirect` (500) |
| transitive flag for the event (and not direct) | `+mopWeightTransitive` (300) |
| no flag on any containment node, or `NONE` everywhere | `0` |

On an unstamped APK every lookup takes the id key, so `flag` reproduces the E6 weights. `MopRetirement` is null under `flag`, and distances are not read.

**`distance` mode.** Let `d` be the smallest distance over the pairs of all the action's containment lookups whose target is not retired ("Target Retirement"). The weight is `ape.mopWeightD1` (500) for `d ≤ 1`, `ape.mopWeightD2` (400) for `d = 2`, `ape.mopWeightD3` (300) for `d = 3`, and `0` for `d ≥ 4` or when no unretired pair exists. Flags are not read. `score` returns this weight before the tie split.

**Tie split (`distance` only).** `MopWidgetPass.apply` SHALL first compute the weight and `d` of every scored action of the state. Ties are by weight level, not by exact `d`: `d = 0` and `d = 1` form one level (weight `ape.mopWeightD1`), and `d = 2` and `d = 3` one level each. The tied actions are those whose undivided weight equals the state's best level, the largest positive undivided weight among the state's scored actions. When there are `n > 1` of them, each SHALL receive `max(W / n, W_next)`, with `W` the weight of that level, `n` the number of tied actions, integer division, and `W_next` the weight of the next level (`ape.mopWeightD2` for the `d ≤ 1` level, `ape.mopWeightD3` for `d = 2`, `0` for `d = 3`). The floor is the author's decision of 2026-10-07: without it, two actions at `d ≤ 1` would get 250 each and fall below a single action at `d = 2` (400). Actions at a worse level keep their undivided weight. The split value is what is added to the priority and stored as `mopBoost`. The undivided weight SHALL be stored beside it as the action's `mopWeight`, which the MOP coin ranks by ("MOP Coin Decider", design D-B10) and which is never part of the scorer sum (`scoring-pipeline`), and reset with `mopBoost`. Under `flag` `mopWeight` equals `mopBoost`. The levels are found by weight value, so the floor assumes `ape.mopWeightD1 >= ape.mopWeightD2 >= ape.mopWeightD3`, as the defaults are: a plan with equal or inverted level weights is accepted, not rejected (author, 2026-10-09; review NT-02).

**Every weighted action is boosted, tried or not** (design D-B19, reverted 2026-10-19). The pass SHALL NOT read `MopInteractionLedger`: whether an action's (activity, `xpath|ActionType`) key was already executed changes neither its boost nor the tie split.

Under rule R1 (`scoring-pipeline` "Scorer Independence (Rule R1)") the scorer reads neither the action's priority nor another scorer's field. `mopWeightDirect`/`mopWeightTransitive` are inert under `distance`, and `mopWeightD1..3` under `flag`; `RUN_START.inert` reports them (`run-spec`). The menu-gateway, WTG and MOP-frontier passes are the same in both modes. WTG and the MOP frontier read `activityHasMop`; the menu gateway reads `activityHasMopOptionsMenu`, which load computes from `optionsMenus` and the WTG edges of `StaticGuiModel` into the selected MOP set. Neither changes meaning with the mode. The STATIC `NavFrontierPass` reads no MOP datum (`wtg-navigation`).

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a `NONE` lookup — including on a MOP-bearing activity — both score `0`. `MopData.activityHasMop(activity)` remains a predicate consumed by WTG scoring, the MOP frontier and `stateMopDensity`, and is not a branch of `MopScorer.score`.

#### Scenario: a tried key keeps its boost
- **WHEN** under `distance` a state of activity `A` holds X and Y, both at `d ≤ 1` (undivided weight 500), and the ledger holds X's key for `A`
- **THEN** X and Y SHALL both be tied, each with `mopBoost` `max(500/2, 400)` = 400 added to its priority, and `mopWeight` 500
- **AND** the step's `dec.mopx` SHALL be `[2, n]` (`event-sink`)

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
- **AND** the two `d = 1` actions SHALL keep a `mopWeight` of 500, the value the MOP coin ranks by
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
- **WHEN** under `flag` the format-2 cryptoapp fixture is loaded in a cell with `MOP` and the tree carries no stamp
- **THEN** a click on `buttonGenerateHash`, `btn_cipher_encrypt` or `executeButton` SHALL score 300, as in E6
- **AND WHEN** the same run is under `distance`
- **THEN** the same three clicks, alone on their screens, SHALL score 400 (`d = 2`), 0 (`d = 4`) and 300 (`d = 3`)

### Requirement: Parent/child widget granularity reconciliation

The MOP scoring pass SHALL reconcile parent/child granularity over **nodes**, not over resource ids (design D17). For an action's resolved node it SHALL run `MopData.lookup` on the node itself, on up to two ancestors (`getParent`), and on its descendants down to two levels (children, then grandchildren), and SHALL keep the best result: under `flag` the largest flag weight (the strongest flag, direct > transitive > none, whenever `mopWeightDirect >= mopWeightTransitive`), under `distance` the smallest unretired distance. The node's own lookup is not privileged; the best result wins wherever it comes from. Each containment node is looked up with its own stamp and its own resource id, so a stamped ancestor or descendant contributes its handler's record.

This addresses the case where static analysis flags a container id (e.g., a `CardView`) while the runtime resolves a child (e.g., the inner `LinearLayout`), or vice versa, and the case where the stamped handler sits on a node the action does not target. The depth bound guards against over-boost (a root container marked by a single MOP child). A boost won by a node other than the action's own is counted as a containment hit on the pass's log line, as today.

Containment applies to scoring and to the readers that share its test: the MOP coin's eligibility and ranking ("MOP Coin Decider"), the prompt marker, and the retirement trigger, which counts the targets of the lookup that won the action its weight ("Target Retirement"). The typed-input path of the STATIC group walks the same node set over `StaticGuiModel.getWidget` (INV-MOP-23). Containment SHALL NOT apply to the state MOP density read by the X3/X4 MOP variants, which reads each action's own node (design D17).

#### Scenario: Static flags parent, runtime resolves child
- **WHEN** the action's own node has a `NONE` lookup for its event
- **AND** an ancestor within depth 2 has a lookup with a MOP flag or an unretired pair
- **THEN** the scorer SHALL use that ancestor's lookup for the boost

#### Scenario: Depth bound respected
- **WHEN** the only node with a MOP flag or a pair is an ancestor at depth 3
- **THEN** the reconciliation SHALL NOT match it (bound is depth ≤ 2)
- **AND** the scorer SHALL fall through to `0` (no activity-level fallback)

### Requirement: eventType normalization in the consumer

The scorer SHALL normalize `eventType` tokens to a single canonical form before comparison so that a producer `snake_case` token (e.g., `long_click`, `item_selected`) and the consumer `camelCase` token (e.g., `longClick`, `itemSelected`) for the same event compare equal (B6). Normalization SHALL be performed in the consumer (`MopData`/`MopScorer`); the producer and the gh13 JSON schema are NOT changed.

#### Scenario: snake_case producer token matches camelCase consumer token
- **WHEN** the JSON listener `eventType` is `long_click`
- **AND** the consumer compares it against `longClick`
- **THEN** the two SHALL compare equal after normalization

---

### Requirement: Static Data Path and MOP Weight Keys

The device path of the derived compact artifact SHALL be the plan key `ape.staticDataPath` (design D-B2), owned by the data root feature `STATIC_DATA` (PRESENT, default `null`); it is read through the resolved plan (`spec().staticData().dataPath()`), not through `Config`. When set, it points to the artifact on the device (`aperv-tool` pushes it to `/data/local/tmp/mop-artifact.json`). The full static-analysis JSON is never pushed to the device and no jar code path accepts it (INV-MOP-34). `ape.mopDataPath` is a retired key: a properties file that sets it aborts resolution with `retired_key` and the reason "renamed ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)." (`KeyOwnership`, `run-spec`).

The MOP group is switched by its own key, `ape.mop` (boolean, default `false`), which requires `STATIC_DATA`: the path alone mounts no MOP consumer, and `ape.mop=true` without a path aborts resolution with `MISSING_DEPENDENCY` (`guidance-groups`). The cells `mop`, `as_mop` and `as_mop_llm` set both.

The following MOP weight keys keep their defaults, matching the MopScorer boost table, and belong to the MOP group; they reach the scorer through the run's `MopContext`, built from `spec().mop()`:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |

These weights are configurable via `ape.properties` but the defaults SHALL be 500/300. `ape.mopWeightActivity` is a retired key: a properties file that sets it aborts resolution with `retired_key` (`KeyOwnership`), and the activity-level fallback it parameterised does not exist (see "MopScorer — Priority Boost").

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.staticDataPath`
- **THEN** `STATIC_DATA` SHALL be absent from the plan, no artifact SHALL be loaded, and neither `StaticGuiModel` nor `MopData` SHALL exist
- **AND** an explicit `ape.mop=true` SHALL abort resolution with `MISSING_DEPENDENCY`
- **AND WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL abort with `retired_key`

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and `ape.mop=true`
- **THEN** the plan SHALL carry `STATIC_DATA` and `MOP`, and the loader SHALL read `"/data/local/tmp/mop-artifact.json"`
- **AND WHEN** the same path is set with `ape.mop=false`
- **THEN** `MOP` SHALL be absent from the plan and no `MopData` SHALL be handed to any consumer
- **AND WHEN** the same path is set with neither `ape.mop` nor `ape.static` true
- **THEN** the artifact SHALL NOT be loaded, no `MOP_DATA` record SHALL be written, and `ape.staticDataPath` SHALL be listed in `RUN_START.inert` (design D-B14.20)

#### Scenario: Default MOP weights
- **WHEN** `ape.properties` does not contain any `ape.mopWeight*` keys
- **THEN** `mopWeightDirect` SHALL equal `500`
- **AND** `mopWeightTransitive` SHALL equal `300`

#### Scenario: Custom MOP weights override
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `mopWeightDirect` SHALL equal `200`
- **AND** `mopWeightTransitive` SHALL retain its default (`300`)

### Requirement: WTG Scoring Pass in adjustActionsByGUITree

The WTG-MOP term SHALL be computed by `WtgPass`, a MOP scorer of the scoring pipeline (`scoring-pipeline` "Scorer Ownership by Group"), assembled by `ScoringPipeline.fromParams` in the pipeline that `StatefulAgent.adjustActionsByGUITree()` runs. `fromParams` constructs every candidate pass and retains only the enabled ones; the census of all candidates is recorded in `PIPELINE.candidates`. `WtgPass` SHALL be enabled, and so retained, if and only if the member feature `WTG` (a MOP member, activated by `ape.mopWeightWtg`) is in the resolved plan (a non-zero injected weight), `MopData` is present and `StaticGuiModel.hasWtgData()` holds; otherwise the pipeline does not run it, with no per-call test. For each valid, target-requiring, resolved action, the pass SHALL call `MopScorer.scoreWtg` with the action's activity and short widget id, the WTG view of `StaticGuiModel`, the MOP-activity set of `MopData` and the injected weight (`wtg-navigation` "MopScorer — WTG Priority Boost"), write the result to the action's `wtgBoost` and add it to the priority. Under rule R1 it reads neither the priority nor any other scorer's field, so its term does not depend on the MOP widget pass or on any other pass, and `wtgBoost` has no other writer.

#### Scenario: WTG boost applied alongside MOP boost
- **WHEN** in a cell with `MOP` a widget's lookup is direct (MOP widget term +500 under `flag`) AND its WTG transition leads to a MOP activity (WTG term +200)
- **THEN** the total priority gain from the two passes SHALL be +700
- **AND** the action SHALL carry `mopBoost == 500` and `wtgBoost == 200` in separate fields

#### Scenario: No WTG data
- **WHEN** `StaticGuiModel.hasWtgData()` is false, or `WTG` is absent from the plan (no `MOP`, or `ape.mopWeightWtg=0`)
- **THEN** `WtgPass` SHALL NOT be enabled, the pipeline SHALL NOT run it (`PIPELINE.candidates` records it as not enabled), and no action SHALL carry a `wtgBoost`

### Requirement: Config Flag for WTG Weight

The WTG navigation weight SHALL be the plan key `ape.mopWeightWtg` (int, jar default `200`), the activation key of the MOP member feature `WTG` (POSITIVE, neutral `0`, depends on `MOP`). It is read through the resolved plan and reaches `WtgPass` injected through `ScoringParams`; no scorer SHALL read it from `Config` (`scoring-pipeline` INV-ARCH-11). A value `<= 0` leaves `WTG` out of the plan, with no abort, on a plan with or without `MOP`, so `WtgPass` is not enabled and no negative boost is ever applied (design D-B22). An explicit positive value on a plan without `MOP` aborts resolution with `MISSING_DEPENDENCY` (`run-spec`). The MOP block of the cell presets states `ape.mopWeightWtg=200` (`guidance-groups`).

| Key | Owner | Type | Default | Description |
|-----|-------|------|---------|-------------|
| `ape.mopWeightWtg` | `WTG` (MOP) | int | 200 | WTG navigation boost (`<= 0` = feature absent) |

#### Scenario: Default WTG weight
- **WHEN** a plan with `MOP` does not state `ape.mopWeightWtg`
- **THEN** `WTG` SHALL be in the plan's `features` and `WtgPass` SHALL boost with weight `200` (when `StaticGuiModel.hasWtgData()` holds)

#### Scenario: WTG weight disabled
- **WHEN** a plan with `MOP` states `ape.mopWeightWtg=0`
- **THEN** `WTG` SHALL be absent from the plan's `features` and `WtgPass` SHALL NOT be enabled
- **AND** no action SHALL carry a `wtgBoost`, and no step record a `dec.wtg`
- **AND WHEN** the plan states `ape.mopWeightWtg=-100` instead, with or without `MOP`
- **THEN** `WTG` SHALL likewise be absent, resolution SHALL NOT abort, and no negative `wtgBoost` SHALL be applied (design D-B22)

### Requirement: ApePromptBuilder — Widget Metadata in LLM Context

The widget metadata of the LLM prompt is the STATIC group's prompt contribution (`StaticPromptContribution`, a `PromptContribution`, `llm-prompt`), mounted only when `STATIC` is in the plan. It SHALL surface, in widget descriptions emitted to the LLM, the following fields when non-null/non-empty: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (rendered as `entries=[v1, v2, …]`, capped to 10 with trailing `, …`), `inputType`, `hint`. The fields are read with `StaticGuiModel.widget(activity, shortId)` for the action's resolved node, keyed by its short resource id, because the static metadata is keyed by id (INV-MOP-36); a node with no resource id has no metadata. The contribution reads no `MopData`. Without `STATIC`, no metadata field appears and `ApePromptBuilder` does not test for a provider: the contribution is simply not mounted.

Each field SHALL appear in the form ` <fieldName>="<value>"` (or `entries=[…]` for the list). Per-value 80-char cap with `…` ellipsis on overflow. Newlines (`\n`, `\r`) flattened to single space, and double quotes escaped. Null / empty ⇒ zero tokens (INV-MOP-10).

This requirement governs the metadata fields only. The `[DM]`/`[M]` markers are the MOP group's contribution, and their rule is owned by `llm-prompt` "MOP Marker Annotation".

#### Scenario: Widget metadata appears when fields populated
- **WHEN** the `as_llm` cell renders a widget with `contentDescription="Encrypt button"`, `tooltipText="Tap to encrypt"`, `inputType=""` (Button), other metadata `null`
- **THEN** prompt SHALL contain `contentDescription="Encrypt button"` AND `tooltipText="Tap to encrypt"`
- **AND** prompt SHALL NOT contain `prompt=`, `spinnerMode=`, `entries=`, `inputType=`

#### Scenario: All-null metadata produces no tokens
- **WHEN** widget has all metadata fields null AND empty `entries`, or `STATIC` is absent from the plan
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

### Requirement: MopScorer — OPTIONSMENU-Aware Menu Boost

`MopScorer.scoreOpenMenu(activity, data, weightOpenMenu)`, given the activity, the `MopData` and the injected weight, SHALL return that weight when `data.activityHasMopOptionsMenu(activity)` is `true`, else `0` (also `0` for a null `data` or activity). The weight is the plan key `ape.mopWeightOpenMenu` (default 250), the activation key of the MOP member `MENU_GATEWAY` (which also requires the ADDONS member `MODEL_MENU`), read through `spec().mop()` and injected through `ScoringParams`; no code SHALL read it from `Config`. The lookup SHALL be O(1) over the gateway set computed at load (INV-MOP-13). The MOP block of the cell presets does not state the key (design D-B14.1): its jar default mounts `MENU_GATEWAY` where `MODEL_MENU` is present (`as_mop`, `as_mop_llm`) and leaves it out silently in the `mop` cell.

`MenuGatewayPass`, enabled (and so retained by the scoring pipeline) only when its injected weight is positive — `MENU_GATEWAY` in the plan — and `MopData` is present, SHALL apply this boost to the `MODEL_MENU` action of the current state: it writes `menuBoost` and adds it to the priority. Under rule R1 (`scoring-pipeline`) the pass SHALL NOT read the ADDONS BACK/MENU cap (design D-B14.19): it consults no `menuPickEligible` hook and no pick counter, so its term is the same whether or not `BACK_MENU_CAP` is mounted. A capped MENU is kept out of selection by the cap itself, which removes it from every discretionary pick (`action-selection` "BACK/MENU discretionary pick cap"), so the boost on a capped MENU steers nothing.

#### Scenario: Boost applied when OPTIONSMENU has MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.A")==true` AND `MopScorer.scoreOpenMenu` is called for `com.x.A` with the injected weight 250
- **THEN** the return value SHALL equal `250`, the injected `ape.mopWeightOpenMenu`

#### Scenario: Zero when no OPTIONSMENU MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.B")==false`
- **THEN** `MopScorer.scoreOpenMenu` for `com.x.B` SHALL return `0`

#### Scenario: Action-priority pass boosts MODEL_MENU when activity has MOP options menu
- **WHEN** `MenuGatewayPass` runs in the `as_mop` cell on a state whose activity is a gateway (`activityHasMopOptionsMenu` true)
- **THEN** the `MODEL_MENU` action's priority SHALL be incremented by the injected `ape.mopWeightOpenMenu` and its `menuBoost` SHALL equal it
- **AND** a `Logger.iformat` line `[APE-RV] menu boost: state=<activity>#<key>, +<w> on MODEL_MENU` SHALL summarize the boost

#### Scenario: Boost suppressed once the MENU key is capped
- **WHEN** in the `as_mop` cell the activity's MENU pick key has reached `ape.backMenuPickCap` AND `MenuGatewayPass` runs on a state of that activity whose activity is a gateway
- **THEN** the `MODEL_MENU` action SHALL still receive the boost (`menuBoost` equal to the injected weight), because the pass reads no cap state (design D-B14.19)
- **AND** the capped `MODEL_MENU` action SHALL NOT be selected by any discretionary pick, since the cap filters it out of them (`action-selection`)
- **AND** the header is kept because `openspec archive` matches scenarios by name: the suppression it names comes from the cap's filter, not from the boost pass

### Requirement: MopScorer — Event-Type-Aware Reachability Scoring

MOP scoring SHALL be event-type-aware. The event of an action is `MopScorer.eventTypeOf(action)`, and `MopData.lookup` selects what MOP knows for that event ("Handler Stamp Lookup"):
- through a stamp, only for `click` and `longClick`, from the stamped class's record for that event;
- through the id key, from the widget's per-event flag when its `mop` map has an entry for that event, and otherwise from the aggregate `directMop`/`transitiveMop` (match-any fallback, INV-MOP-14); a null event uses the aggregate. Distance pairs are taken under the event's own key, except that a click on a widget with no `click` key in its `mop` map reads the widget's click aggregate (design D17 as amended 2026-10-09); through a stamp, only the record's pairs for the event.

The scorer SHALL NOT read flags by `(activity, shortId)` itself: it scores at the action level (`MopScorer.score(MopContext, State, ModelAction)`, "MopScorer — Priority Boost"), which passes the event to the lookup.

`MopScorer.eventTypeOf(ModelAction action)` SHALL map, through `eventTypeOf(ActionType, String widgetClass)` with the class of the action's resolved node:
- `ActionType.MODEL_CLICK` → `"itemSelected"` when the node's class name contains `"Spinner"`, `"click"` otherwise
- `ActionType.MODEL_LONG_CLICK` → `"longClick"`
- any scroll type (`ActionType.isScroll()`: `MODEL_SCROLL_TOP_DOWN`, `MODEL_SCROLL_BOTTOM_UP`, `MODEL_SCROLL_LEFT_RIGHT`, `MODEL_SCROLL_RIGHT_LEFT`) → `"scroll"`
- anything else (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP` and the non-model types), or a null action → `null`

There is no `MODEL_INPUT` action type: text input is a side effect of a `MODEL_CLICK` on an `EditText`, so a Spinner is recognised on the click.

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
- **THEN** `MopScorer.eventTypeOf(action of type MODEL_CLICK)` on a non-Spinner node SHALL return `"click"`, and on a node whose class contains `Spinner` SHALL return `"itemSelected"`
- **AND** `eventTypeOf(MODEL_LONG_CLICK)` SHALL return `"longClick"`
- **AND** `eventTypeOf` of every `MODEL_SCROLL_*` type SHALL return `"scroll"`
- **AND** `eventTypeOf(unknown type)` SHALL return `null`

### Requirement: TypedInputGenerator — Type-Aware Input Generation

`TypedInputGenerator.generateForType(String inputType, String hint, java.util.Random rnd)` (`ape/utils/TypedInputGenerator`) SHALL return a domain-correct random string based on `inputType` when the STATIC member `TYPED_FUZZ` is in the resolved plan:

| `inputType` | Output shape |
|---|---|
| contains "Password" | 8–12 chars with ≥1 lowercase letter, ≥1 digit and ≥1 symbol (from `!@#$%&*?`), the rest mixed, shuffled |
| "number" | a non-negative integer below 1,000,000 |
| "numberSigned" | an integer in `[-1,000,000, 1,000,000)` |
| "numberDecimal" | `<integer below 100,000>.<two digits>` |
| "phone" | `+55 11 9XXXX-XXXX` |
| "textEmailAddress" | `<localPart>@example.com` with `localPart` 4–10 lowercase letters |
| "textUri" | `https://example.com/<8 lowercase>` |
| "date" / "time" / "datetime" | ISO 8601 (`YYYY-MM-DD`, `HH:MM:SS`, `date` + `T` + `time`), the year within the current one and the ten before |
| empty — and `hint` contains "email" (case-insensitive) | email shape |
| empty — and `hint` contains "senha" / "password" (case-insensitive) | password shape |
| empty — and `hint` matches `\d+` | numeric shape |
| else, including a non-empty unrecognised `inputType` | legacy shape: 6–12 lowercase letters |

With `TYPED_FUZZ` absent from the plan, `generateForType` returns the legacy shape for every input.

The typed path is the STATIC member feature `TYPED_FUZZ`, activated by `ape.fuzzInputTyped` (default `true`) and depending on `STATIC`. Its caller, `ApeAgent.generateInputText`, SHALL try it first only when `TYPED_FUZZ` is in the resolved plan and the static model exists: it walks the node's containment nodes (INV-MOP-23) and uses the first static widget (`StaticGuiModel.widget`, keyed by the top activity and the short resource id) that carries a non-empty `inputType` or `hint`. It reads no `MopData`. When the feature is absent (`STATIC` off, or `ape.fuzzInputTyped=false`), or no static widget with `inputType` or `hint` resolves, the caller SHALL use the next text source — `InputValueGenerator` when `HEURISTIC_INPUT` is in the plan, else `StringCache.nextString()` — so a non-instrumented app sees no regression. A step whose text came from the typed path SHALL carry `dec.inp:"typed"` (`event-sink`).

#### Scenario: Password input type produces mixed-class string
- **WHEN** `TypedInputGenerator.generateForType("textPassword", "", rnd)` is called with `TYPED_FUZZ` in the plan
- **THEN** the output SHALL be 8 to 12 chars
- **AND** the output SHALL contain at least one letter, one digit, and one symbol

#### Scenario: Number input type produces digits
- **WHEN** `TypedInputGenerator.generateForType("number", "", rnd)` is called with `TYPED_FUZZ` in the plan
- **THEN** the output SHALL match `^-?\d+$`

#### Scenario: Phone input type produces template-conformant string
- **WHEN** `TypedInputGenerator.generateForType("phone", "", rnd)` is called with `TYPED_FUZZ` in the plan
- **THEN** the output SHALL match `^\+55 11 9\d{4}-\d{4}$`

#### Scenario: Email input type
- **WHEN** `TypedInputGenerator.generateForType("textEmailAddress", "", rnd)` is called with `TYPED_FUZZ` in the plan
- **THEN** the output SHALL match `^[a-z]{4,10}@example\.com$`

#### Scenario: Hint-based fallback to email
- **WHEN** `TypedInputGenerator.generateForType("", "Your email", rnd)` is called with `TYPED_FUZZ` in the plan
- **THEN** the output SHALL match the email shape

#### Scenario: Unknown inputType falls back to legacy generator
- **WHEN** `TypedInputGenerator.generateForType("weird_unknown", "", rnd)` is called
- **THEN** the output SHALL be 6 to 12 lowercase letters (the legacy shape)
- **AND** the output SHALL NOT match the password / number / phone / email shapes

#### Scenario: fuzzInputTyped=false bypasses typed generation (rollback guard)
- **WHEN** `ape.fuzzInputTyped=false` in a cell with `STATIC`, or the cell has no `STATIC` (`base`, `mop`), and an EditText whose static widget carries `inputType="textPassword"`, `hint="Your password"` receives text
- **THEN** `TYPED_FUZZ` SHALL be absent from the plan, `generateInputText` SHALL NOT call `TypedInputGenerator`, and the text SHALL come from the next source: `InputValueGenerator` (`dec.inp:"heuristic"`) when `HEURISTIC_INPUT` is mounted (the `as*` cells), else `StringCache.nextString()` (`dec.inp:"random"`)
- **AND** in the `mop` cell the typed path SHALL stay off although the artifact is loaded, because typed input is a STATIC member, not a MOP one

### Requirement: StatefulAgent — Tuple-Based Component Triggering

`StatefulAgent.triggerComponent(int target)` (design D-B14.15) SHALL fire the target at index `target` of the component trigger pool, whose size `StatefulAgent.componentTargetCount()` reports. It returns nothing and owns no cursor: it is invoked only by the `ComponentTrigger` stage, which exists only when the STATIC member `COMPONENT_TRIGGER` is in the plan, owns the round-robin cursor and passes `cursor % componentTargetCount()` (`component-triggering`, `decision-pipeline` INV-DP-07; design D-B13). The pool is built on the first `componentTargetCount()` call and cached for the run: the receiver and service tuples (`TriggerTuple`) at the low indices, followed by the provider tuples (`ProviderTuple`), so one cursor walks both. Tuple construction (`buildTriggerTuples`, `buildProviderTuples`) SHALL read `StaticGuiModel` only and no MOP datum (`component-triggering` INV-CT-16):

- Iterate `receivers()` then `services()`. Activities SHALL NOT enter the pool under any configuration: they are launched only by the `MopLauncher` stage (`component-triggering` INV-CT-03).
- Admit every receiver and service: there is no permission, package, `reachesMop`/`reachesTarget`, `hasTargetMethods`, distance or handler filter (the permission gate is only logged).
- For each admitted component, for each `IntentFilter`, for each `action` in the filter, emit one tuple. A Service with no filter action emits exactly one component-name-only tuple (`filter=null, action=null`); a Receiver with no filter action emits none (design D-B14.15).
- For each provider with non-null, non-empty `authorities`, emit three `ProviderTuple` (query, insert, update), in that order.

For each invocation:
- The fired target is recorded first as the step's `dec.trig` (kind `broadcast`, `service` or `provider`, and the component class), whatever the platform answers.
- Intent construction (`dispatchTrigger`): `setComponent(new ComponentName(StaticGuiModel.packageName(), className))`; if `action != null`, `setAction(action)`; for each `category` in `filter.categories`, `addCategory(category)`; when the filter carries a `data` member, the intent's data and type from it (`intentDataAndType`, `component-triggering` INV-CT-17): the URI `scheme://host + path` from the first scheme, the first host and the first path, falling back to the first path prefix, `host` and `path` empty when absent (design D-B14.11, D-B14.15).
- For a receiver, the `SystemBroadcastCatalog` extras of the action are applied after `setAction` and before `AndroidDevice.sendBroadcast`; the catalog is loaded iff `COMPONENT_TRIGGER` is mounted. A service is started with `AndroidDevice.startService`.
- Provider invocation (`dispatchProvider`): the command `content <operation> --uri content://<authorities>`, with `--bind ape_probe:s:` appended for `insert` and `update` (`buildContentCommand`), run through `AndroidDevice.executeCommandAndWaitFor`. It is preceded by the line `[APE-RV] Triggering provider: <className> op=<operation> uri=content://<authorities>`; a non-zero exit logs the warning `[APE-RV] provider <className> op=<operation> exit=<n>`.
- Logging (receiver and service, `triggerLogLine`): `[APE-RV] Triggering <componentType>: <className> action=<a> categories=<c1,c2> permission=<p|none>`, the class name unkeyed. The line SHALL NOT carry `reachesTarget`.

#### Scenario: Skips non-reachable components
- **WHEN** `StaticGuiModel` has a receiver with a filter action whose component `MopData` would record `reachesMop=false`
- **THEN** the tuple list SHALL contain that receiver's tuple, because the pool reads no MOP data
- **AND** the header is kept because `openspec archive` matches scenarios by name: the reachability filter it names does not exist (design D-B13)

#### Scenario: Skips non-exported activities even when activity triggering is enabled
- **WHEN** the plan carries `MOP_LAUNCHER` and the artifact lists activities
- **THEN** the tuple list SHALL contain no tuple from any activity, exported or not (`exported` is not on the wire)

#### Scenario: Round-robins all intent filter actions
- **WHEN** a receiver has one `IntentFilter` with two actions `["action1", "action2"]` and no other component yields a tuple
- **AND** the `ComponentTrigger` stage fires 4 times in sequence, so `triggerComponent` is called with targets 0, 1, 0, 1
- **THEN** action1 and action2 SHALL each have been used exactly 2 times

#### Scenario: Provider operations round-robin
- **WHEN** `StaticGuiModel` has a provider with `authorities="com.x.p"`
- **AND** the stage fires 6 times in sequence (provider-only fixture)
- **THEN** the shell commands invoked SHALL be in the cyclic order `content query`, `content insert`, `content update`, `content query`, …, the insert and update commands carrying `--bind ape_probe:s:`

#### Scenario: Log line contains expected fields
- **WHEN** a receiver tuple is triggered
- **THEN** the logged line SHALL contain the component type, the class name, `action=…`, `categories=…` and `permission=…`
- **AND** it SHALL NOT contain `reachesTarget`

#### Scenario: activityTriggerEnabled=false excludes activities from the tuple list (rollback guard)
- **WHEN** the artifact carries one activity and one receiver with a filter action, whether `ape.activityTriggerEnabled` is `false` or `true`
- **THEN** the built tuple list SHALL contain ≥1 tuple from the receiver AND zero tuples from the activity
- **AND** `ape.activityTriggerEnabled` SHALL govern only the `MopLauncher` stage (`component-triggering`)

#### Scenario: Empty trigger list returns false without side effects
- **WHEN** `StaticGuiModel` carries components but none yields a tuple (only receivers with empty `intentFilters`, no service, no provider with `authorities`)
- **AND** the `ComponentTrigger` stage wins its coin on a step
- **THEN** `componentTargetCount()` SHALL be `0`, `triggerComponent` SHALL NOT be called, and the stage SHALL return `SideEffect` ("no triggerable component target")
- **AND** no shell command / broadcast / service start SHALL be invoked, and no `dec.trig` SHALL be recorded
- **AND** the stage's round-robin cursor SHALL NOT advance
- **AND** the header is kept because `openspec archive` matches scenarios by name: no method returns `false`

#### Scenario: Component with empty intentFilters but non-empty targetMethods emits component-name-only tuple
- **WHEN** `StaticGuiModel` carries a service with `intentFilters=[]`, whatever its `hasTargetMethods`
- **THEN** the tuple list SHALL contain exactly one `TriggerTuple` for this service with `filter=null` and `action=null`
- **AND** the built Intent SHALL have `setComponent(...)` called and SHALL NOT have any `setAction` / `addCategory` calls
- **AND WHEN** a receiver carries `intentFilters=[]`
- **THEN** the tuple list SHALL contain no tuple for it (design D-B14.15)

### Requirement: MopData — Package / MainActivity Sanity Check

`StaticArtifactLoader.load(String path, String expectedPackage, String expectedMainActivity, EventSink sink)` SHALL compare the artifact's top-level `package` / `mainActivity` against the optional expected values, before either provider is handed out. When `expectedPackage` non-null and not equal to the parsed value, OR `expectedMainActivity` non-null and not equal to the parsed value, a `WARN` log line naming both values of each mismatching pair SHALL be emitted. The strict mode is the plan key `ape.mopStrictPackageMatch`, which keeps its name and is owned by `STATIC_DATA` (`StaticParams.strictPackageMatch`, design D-B14.3), because the package check is on the static model; it is read through the plan as `spec.staticData().strictPackageMatch()`, never from `spec.mop()` and never from `Config`. When it is `true` and either comparison mismatches, `load` SHALL return no provider (reject reason `package-mismatch`). Default behavior (`false`) is warn-only — both providers are still returned. The generator copies both scalars verbatim from the full JSON, so a mismatch means the wrong artifact was derived or pushed for this APK.

The production call site (`StatefulAgent` constructor, reached only when `STATIC` or `MOP` is mounted) SHALL pass the path from `StaticParams`, the runtime package name and main activity of the app under test (`ape.getMainApp()`) and the run's sink (`RunContext.current().sink()`), making the sanity check reachable in production. The four-argument form is the public loader entry point; the five-argument form is a package-private test seam.

#### Scenario: Default warn-only on mismatch
- **WHEN** `ape.mopStrictPackageMatch=false` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null, sink)` is called on an artifact with `package="x.y.z"`
- **THEN** both providers SHALL be returned
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Strict-mode rejection on mismatch
- **WHEN** `ape.mopStrictPackageMatch=true` AND `StaticArtifactLoader.load(path, "x.y.z.OTHER", null, sink)` is called on an artifact with `package="x.y.z"`
- **THEN** no provider SHALL be returned
- **AND** a `WARN` log line SHALL be emitted
- **AND** the load record SHALL carry `reason=package-mismatch`

#### Scenario: Null expected values bypass the check
- **WHEN** `StaticArtifactLoader.load(path, null, null, sink)` is called
- **THEN** no sanity-check WARN log SHALL be emitted (regardless of `ape.mopStrictPackageMatch`)
- **AND** both providers SHALL be returned

#### Scenario: Production call site passes runtime identity
- **WHEN** `StatefulAgent` loads the artifact for an app whose runtime package is `br.unb.cic.cryptoapp`, in a cell that mounts `STATIC` or `MOP`
- **THEN** it SHALL call `StaticArtifactLoader.load(<StaticParams path>, "br.unb.cic.cryptoapp", <mainActivity>, <run sink>)`
- **AND** an artifact derived for a different APK SHALL trigger the sanity-check WARN (and rejection under `ape.mopStrictPackageMatch=true`)

### Requirement: MopData — Load Status Line and Fail-Fast

`StaticArtifactLoader.load` SHALL emit exactly one load status record per invocation with a non-null path, on both outcomes. The record is the out-of-step `MOP_DATA` NDJSON record of the event sink (`EventSink.mopData`), which stays the census of the whole artifact load (both providers):

- success: `status` `loaded`, with the fields `formatVersion`, `sourceDigest` (omitted when the artifact has no `source.digest`), `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers`, `droppedPairs`. `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda` and `recovered` are the artifact's generator-computed `stats` echo (host facts, echoed not recomputed; `stats.orphanDialogs` is not echoed). `components` counts the loaded receivers, services, activities and providers. `targets` is the artifact's target count, `handlers` the number of classes in the handler table, and `droppedPairs` the number of malformed distance pairs dropped at load ("MopData — Static Analysis JSON Loader")
- failure: `status` `rejected` with `reason` one of `file-missing`, `parse-error`, `version-mismatch`, `package-mismatch`

`mopActsAugmented` SHALL be `|mopActivitiesAugmented \ mopActivities|` over the two wire sets — what the augmented source would contribute — and SHALL NOT vary with `MOP_ACTIVITY_SOURCE`. `mopActivities` remains the size of the **selected** set, so the two fields answer different questions on purpose.

`windows` is in the list deliberately: it survives inside the artifact as `stats.windows`. `transitions` is the single deliberate omission, superseded by `wtgEdges`. The record gains fields across changes and never loses one.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden.

Fail-fast composition:

1. **Plan level** (`run-spec`, `guidance-groups`): a plan that mounts `STATIC` or `MOP` without `ape.staticDataPath` is invalid and aborts at plan validation (`MISSING_DEPENDENCY`), before agent construction.
2. **Artifact level** (this requirement): when the plan mounts `STATIC` or `MOP` and `StaticArtifactLoader.load` returns no provider — for any reject reason, including a format-1 artifact — `StatefulAgent` SHALL abort the run (throw `StopTestingException`, `StatefulAgent.requireMopArm`) instead of continuing without the group (INV-MOP-22).
3. **Host level** (`aperv-tool` in rv-android): a cell whose full JSON is missing or whose derivation fails raises before launch, and the host cache keys on the format as well as `source.digest`.

When `ape.staticDataPath` is unset, or when it is set and neither `STATIC` nor `MOP` is mounted (the path is then inert, design D-B14.20), nothing is loaded and no status record is written.

#### Scenario: successful load emits counters
- **WHEN** `StaticArtifactLoader.load` parses a format-2 artifact derived from a full JSON whose SHA-256 is `d`, with 30 widgets, 3 flagged, 0 dropped, 16 WTG edges, `targets: 27` and 2 handler classes
- **THEN** one `MOP_DATA` record with `status` `loaded` SHALL be emitted carrying `formatVersion:2`, the artifact's `source.digest` (`sha256:d`) as `sourceDigest`, those counters, `targets:27`, `handlers:2` and `droppedPairs:0`

#### Scenario: transitions present, click edges absent
- **WHEN** the artifact's `wtg` map is empty because the producer's document carried transitions but none of them a `click` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry **no** `transitions` field
- **AND** the jar SHALL have no other number to confuse it with: raw transitions do not exist on the wire (INV-MOP-35)

#### Scenario: rejected load names the reason
- **WHEN** the file at `ape.staticDataPath` is not a format-2 compact artifact (a full JSON, a format-1 artifact, or a document without `formatVersion`)
- **THEN** one `MOP_DATA` record with `status` `rejected` and `reason` `version-mismatch` SHALL be emitted
- **AND** `load` SHALL return no provider

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** the `as_mop` cell sets `ape.staticDataPath` and `StaticArtifactLoader.load` returns no provider
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed without its STATIC and MOP groups

#### Scenario: unset path keeps SATA behavior
- **WHEN** the `base` cell runs, with no `ape.staticDataPath`
- **THEN** no provider SHALL exist and exploration SHALL proceed with no STATIC or MOP consumer, no `MopRetirement` and no `MopInteractionLedger`

### Requirement: Typed-Input Widget Resolution via Containment

When `ApeAgent.generateInputText` resolves the static widget for an `EditText` under `TYPED_FUZZ` (to read its `inputType`/`hint` for type-aware generation), the lookup SHALL go through `StaticGuiModel.widget` and SHALL apply the same parent/child containment walk used by the MOP scoring pass (`MopScorer.containmentNodes`: the node, up to two ancestors, and its descendants two levels down, as specified in "Parent/child widget granularity reconciliation"): the node's own short id is tried first, then the containment walk, and the first widget with a non-empty `inputType` or `hint` wins (INV-MOP-23). The walk is shared code; the typed-input path reads no `MopData`, so it works identically in a cell with STATIC and without MOP. When no widget resolves (or `inputType`/`hint` are empty), the fallback to the next text source is unchanged.

#### Scenario: child id resolves the container's typed metadata
- **WHEN** in the `as` cell the static model holds widget `login_form` with `inputType="textEmailAddress"` and the runtime EditText resolves to child id `login_form_field`, one containment level below
- **THEN** `generateInputText` SHALL resolve the `login_form` widget via the containment walk
- **AND** the generated value SHALL be email-shaped and the record SHALL carry `dec.inp:"typed"`

#### Scenario: no static widget still falls back
- **WHEN** neither the node's id nor its containment neighborhood matches a static widget
- **THEN** the typed path SHALL produce nothing and the next text source SHALL be used, as before

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopContext, int timestamp)` SHALL return `0` when the context is null or the state's activity does not satisfy `activityHasMop(activity)` (cheap early-out, no widget resolution). Otherwise it SHALL return `1 + count`, where `count` counts the valid, target-requiring actions resolved at `timestamp` whose resolved node's own lookup, `MopData.lookup(activity, node, eventTypeOf(action))` with no containment walk (INV-MOP-36, design D17), counts under the scoring mode (`MopScorer.countsTowardDensity`):

- under `flag`, the lookup reports a direct or transitive flag;
- under `distance`, the lookup alone gives a positive weight: an unretired pair at `d ≤ 3` (`MopScorer.best` over that one lookup).

In the scenarios below, "MOP-flagged" reads as "counts under the mode".

The `+1` term is the **activity-substrate floor**: a state on a MOP-bearing activity that carries no MOP-flagged widget (the A′-contributed case) SHALL rank above a state on a non-MOP activity (`1 > 0`) in the MOP navigation tie-breaks, while any widget-flagged state still ranks strictly above an activity-floor-only state (`1 + count > 1` for `count ≥ 1`). The floor is a cross-state ranking signal only; it SHALL NOT feed any per-candidate boost.

Its only callers SHALL be the MOP variants of the SATA extension points X3 and X4 ("Navigation Tie-Breaks as MOP Variants"), which exist only when `MOP` is in the plan. `SataAgent` SHALL contain no call guarded by `getMopData() != null` (INV-MOP-41). The callers pass the current GUITree timestamp via `getTimestamp()`.

#### Scenario: MOP-flagged widgets counted above the floor, unflagged ignored
- **WHEN** a state in a MOP activity has 10 valid targeted actions of which 2 resolve to MOP-flagged nodes
- **THEN** `stateMopDensity` SHALL return 3 (floor 1 + count 2)

#### Scenario: A′-only activity ranks above non-MOP activity
- **WHEN** state A is on an activity with `activityHasMop==true` contributed solely by the A′ sources (zero MOP-flagged nodes resolve), and state B is on an activity with `activityHasMop==false`
- **THEN** `stateMopDensity(A) == 1` and `stateMopDensity(B) == 0` (the X3/X4 MOP variants prefer A)

#### Scenario: widget evidence outranks activity-floor-only evidence
- **WHEN** state A has exactly 1 MOP-flagged resolved action and state B is on a MOP activity with none
- **THEN** `stateMopDensity(A) == 2` and `stateMopDensity(B) == 1` (widget-flagged state preferred)

#### Scenario: dense non-MOP screen scores below sparse MOP screen
- **WHEN** state A has 12 valid actions, none MOP-flagged, on a non-MOP activity, and state B has 3 valid actions, one MOP-flagged, in a MOP activity
- **THEN** `stateMopDensity(A) == 0` and `stateMopDensity(B) == 2` (the X4 MOP variant prefers the path ending in B)

#### Scenario: non-MOP activity unchanged
- **WHEN** the state's activity has no MOP-reachable methods
- **THEN** `stateMopDensity` SHALL return 0 without resolving any node

#### Scenario: under distance a flagged node with no near target does not count
- **WHEN** under `ape.mopScoring=distance` a state of a MOP activity has two valid targeted actions, one whose own lookup is transitive-flagged with pairs `[[5,4]]` and one whose own lookup carries the unretired pair `[[7,2]]`
- **THEN** `stateMopDensity` SHALL return 2 (floor 1 + the `d = 2` action), the flagged action at `d = 4` having weight 0

### Requirement: Navigation MOP-Tiebreak Decision Log

When the X4 MOP variant (`MopTrivialPathPick`) chooses among multiple shortest paths and the `stateMopDensity` comparison is decisive (densities not all equal), it SHALL log exactly one line: `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>`, where `<d>` is the winning path's end-state density and `<n>` the number of candidate paths, and the step's record SHALL carry `dec.nav` with `x:"X4"`, `c:"mop"`, `alt:1`, `drew:0` (`action-selection`). When all densities are equal (the `randomPick` fallback) no line SHALL be logged and `dec.nav` carries `alt:0`, `drew:1`. The APE default, mounted without `MOP`, never logs this line. The X3 variant does not log (it runs inside a candidate loop; logging there would flood the trace); its decision is visible in `dec.nav`. `dec.nav.alt` SHALL be `1` if and only if the MOP variant decided by its own rule — for X3, it replaced the incumbent; for X4, the density chose the path with no draw — and `0` otherwise (design decision A2). X1 has no MOP variant and always records `alt:0`.

#### Scenario: decisive density logs once
- **WHEN** in a cell with `MOP` 3 candidate paths have end-state densities 0, 0, 2
- **THEN** the path with density 2 SHALL be chosen, one `[APE-RV] Nav MOP tiebreak: density=2 paths=3` line SHALL be logged, and the record SHALL carry `dec.nav:{"x":"X4","c":"mop","alt":1,"drew":0}`

#### Scenario: all-equal densities stay silent
- **WHEN** all candidate paths have equal end-state density, or the cell has no `MOP`
- **THEN** the path SHALL be chosen by `RandomHelper.randomPick` and no tiebreak line SHALL be logged

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

The A′ 3-source union (widget-derived activities ∪ component-flagged activities ∪ reachability-flagged activity classes) SHALL be computed **host-side by the generator** and shipped as the wire field `mopActivitiesAugmented`, alongside the widget-derived `mopActivities` (see `static-analysis-entrypoints` for the derivation rules). On-device, the resolved plan selects which set backs `activityHasMop`/`getMopActivities`:

- `MOP_ACTIVITY_SOURCE` absent from the plan: the widget-derived set, exactly.
- `MOP_ACTIVITY_SOURCE` present: the augmented set.

The selection SHALL happen once at load, in `StaticArtifactLoader.load`, from `spec().has(Feature.MOP_ACTIVITY_SOURCE)` (the public entry reads the plan; the package-private test seam takes it as a parameter); no `Config` field exists for the key (design D-B6). Every downstream consumer (launcher census ordering, `stateMopDensity` substrate floor, WTG/MopFrontier target tests, OPTIONSMENU-gateway condition 2, the MOP prompt contribution) reads the selected set (INV-MOP-27).

In the run-spec `Feature` model, `ape.mopActivitySourceComponents` activates `MOP_ACTIVITY_SOURCE`, a MOP member that depends on `MOP` (which requires `STATIC_DATA`). An explicit `true` on a plan without `MOP` aborts resolution as a missing dependency; with `MOP` off and the key at its default, the feature is left out silently. The MOP block of the cell presets sets it `true`.

#### Scenario: flag off preserves widget-only source
- **WHEN** `ape.mopActivitySourceComponents=false` in a cell with `MOP` and the artifact's augmented set contains an activity absent from the widget-derived set
- **THEN** `activityHasMop` for that activity SHALL be `false`
- **AND** the launcher census SHALL NOT include it

#### Scenario: component-level activity added under the flag
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan (the `mop` cell preset)
- **THEN** `getMopActivities()` SHALL equal the wire `mopActivitiesAugmented` set
- **AND** the OPTIONSMENU-gateway recompute SHALL test condition 2 against it

#### Scenario: union preserves widget-derived entries
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan and activity `com.x.A` is in the artifact's `mopActivities` through a flagged widget
- **THEN** `activityHasMop("com.x.A")` SHALL remain `true` — turning the feature on SHALL never remove an activity
- **AND** the additivity SHALL be a property of the wire: `mopActivities ⊆ mopActivitiesAugmented` holds by construction of the union (rv-android INV-DRV-06)

#### Scenario: non-reaching component not added
- **WHEN** `MOP_ACTIVITY_SOURCE` is in the plan and `com.x.Plain` has no flagged widget, `reachesMop==false`, and no reaching method in the producer's `reachability[]`
- **THEN** `activityHasMop("com.x.Plain")` SHALL return `false` under both selections, the activity being in neither wire set

#### Scenario: reachability-method source flags a lambda-gapped activity (source 3)
- **WHEN** `com.x.CryptoActivity` has `components.activities[].reachesTarget==false` and no MOP-flagged widget, BUT its `reachability[]` class (`componentType=="activity"`) carries ≥1 method with `reachesTarget==true`
- **THEN** it SHALL be present in the artifact's `mopActivitiesAugmented` and absent from `mopActivities`, so `activityHasMop("com.x.CryptoActivity")` SHALL be `true` with `MOP_ACTIVITY_SOURCE` in the plan and `false` without it
- **AND** source 3 SHALL remain a distinct source, because the producer's call graph does not traverse D8-desugared lambda edges

#### Scenario: explicit activation without MOP data aborts
- **WHEN** `ape.properties` sets `ape.mopActivitySourceComponents=true` and no `ape.mop=true` (with or without `ape.staticDataPath`)
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MOP_ACTIVITY_SOURCE` and `MOP`
- **AND** the abort SHALL precede any artifact read (the dependency is a plan property, not an artifact property)

### Requirement: Handler Stamp Lookup

`MopData` SHALL answer, for one node and one event, what MOP knows about the action, through `MopData.Lookup MopData.lookup(String activity, GUITreeNode node, String eventType)` (design D17, API Design). A `Lookup` carries the direct flag, the transitive flag, the distance pairs `int[][] dist` (each `[i, d]`, possibly empty) and the source, one of `STAMP`, `ID` or `NONE`. `lookup` SHALL never return null (a null node yields `NONE`) and SHALL be pure: it reads the artifact and the node, never the retirement state.

The result SHALL be computed in this order:

1. **Stamp.** If `eventType` normalizes to `click` or `longClick`, the node's stamp for that event (`GUITreeNode.getHandlerClick()` or `getHandlerLongClick()`, `ui-tree` "Handler Stamp Capture") is non-null, and the artifact's `handlers` map lists that class (`MopData.getHandler`), the result SHALL be that class's record for the event, with source `STAMP`. This holds when the record says the class reaches nothing (`mop` `none`, no `dist`): the result then has no flag and no pair, and the id key SHALL NOT be consulted. An event missing from a listed class's record reads as `none` with no pairs.
2. **Id key.** Otherwise, if the widget of `(activity, shortId)` resolves for the node's short id (`MopData.extractShortId`, the package-private `MopData.getWidget`), the result SHALL carry that widget's flags for the event, with the per-event fallback to the aggregate when the event has no key in the widget's `mop` map (`MopData.MopEntry.isDirectMop`/`isTransitiveMop`, INV-MOP-14), and the widget's `dist` pairs under the event's key (none when the key is absent), except for `click`: when the widget's `mop` map has no `click` key, a click SHALL read the widget's click aggregate (`MopData.Widget.clickAggregate()`), the merge by the minimum distance per target of the pairs of every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu` and `scroll` (design D17 as amended 2026-10-09), with source `ID`. The click aggregate takes the flags' condition (key absence; an explicit `click` key, `none` included, blocks it) but not their scope: `longClick`, `itemSelected`, `scroll` and a null event read their own pairs alone, so a long-click on a widget with no long-click listener gets no distance weight. The aggregate does not apply to step 1: a stamp names the handler of one event.
3. **Nothing.** Otherwise the result SHALL be `NONE`, with no flag and no pair.

A stamp naming a class that `handlers` does not list SHALL fall back to step 2. That covers the dispatchers gh121 records for library-routed handlers: `androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener` for XML `android:onClick`, and toolbar, menu and `SearchView` classes (gh121 design, Risks `:238` at HEAD, `:239` in the rv-android working tree: "the consumer must keep the resource-id key as fallback"). A listed class wins over the id key even when the id key is flagged, because the stamp names the handler that runs, while the id key carries GATOR's attribution, which spreads one handler over many widgets.

The events `itemSelected` and `scroll`, and a null event, SHALL skip step 1: the instrumenter stamps only click and long-click handlers. A Compose node's stamp is the class of the app's handler lambda (the `onClick`/`onLongClick` field of the modifier node behind the semantics action, or the action lambda's own class when no modifier node is reached; gh121, rv-android `instrumentation` INV-INS-178), and that class's `java.lang.Object invoke()`, or `java.lang.Object invoke(java.lang.Object)` for a toggleable's `onValueChange`, fills both `click` and `longclick` in its `handlers` record (`static-analysis-entrypoints`), so the stamp answers whichever event the node's key names.

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

### Requirement: Target Retirement

Under `ape.mopScoring=distance`, the agent SHALL keep one counter per target, sized by `MopData`'s `targets` (`MopRetirement`). `MopRetirement` is created once per run by `StatefulAgent` after the artifact loads when `MOP` is in the plan; it is null when `MOP` is absent or the mode is `flag`.

Retirement SHALL compose on `MopInteractionLedger` ("MopInteractionLedger — Executions per Activity and Key") and keep no first-interaction record of its own. In `StatefulAgent.moveForward`, for the model action about to be executed, the agent SHALL first read `MopInteractionLedger.tried(activity, xpath|ActionType)`; if the key is not yet there, this is a **first interaction**. It SHALL then record the execution, whether first or not (every execution adds one, design D-B17); only a first interaction counts targets. Novelty is per (widget, action type) and per activity, so a long-click on a widget already clicked in the activity is a first interaction.

On a first interaction, the agent SHALL take the **weight's lookup** for the action's event (`MopScorer.eventTypeOf`): among the action's containment lookups ("Parent/child widget granularity reconciliation"), the one holding the action's smallest unretired distance, ties going to the earlier node in containment order (the node itself, its ancestors, then its descendants). It is the lookup behind `dec.mopd`/`dec.mops` (`event-sink`) and the MOP coin's target count, so what gives an action its weight is what retires it. Only an action the score treats as MOP retires anything: when the action's weight before the tie split is 0, nothing is counted. Otherwise the agent SHALL call `MopRetirement.onFirstInteraction(weightLookup)`, which adds one to the counter of each target index in the lookup's `dist` whose pair lies at `d*`, the lookup's smallest unretired distance, each index once, and returns the indices whose counter reached `ape.mopRetireAfter` (default 3) on this call. Those indices are the step's `dec.ret` (`EventSink.mopRetired`, `event-sink`).

The count is applied at `moveForward` without a pending step, and only for an action that is executed. On the stage path (the pipeline assembled `RESTART`, `decision-pipeline` "Restart Guard Stage") the restart is a guard placed before selection, so no selected action is replaced after selection; a step the `RESTART` guard takes selects the restart action, which has no target and counts nothing. On the post-selection path (no `RESTART` stage, as in the `mop` cell, `decision-pipeline` "Post-Selection Restart") an action that `checkRestart` replaces by a start action is not executed: `StatefulAgent.updateStateInternal` resets the trace instead of calling `moveForward`, so the replaced action records no ledger key, counts no target and retires nothing, and the step carries no `dec.ret`. No `checkRestart` override applies or discards a count in either path.

A target whose counter is at least `ape.mopRetireAfter` is **retired**. The key has no lower bound and resolution does not reject any integer: with `ape.mopRetireAfter <= 0` every target reads as retired from the start, so under `distance` every weight is 0, the MOP coin is never eligible and the run explores without distance guidance, no `dec.ret` is written and `RUN_END.retired` stays 0. A retired target SHALL NOT count toward an action's distance in "MopScorer — Priority Boost", toward the coin's eligibility and ranking, toward the prompt marker (`llm-prompt` "MOP Marker Annotation"), or toward the launcher order (`component-triggering`). `MopRetirement.nearest(int[][] dist, int dmax)` returns the smallest `d` over the unretired pairs with `d ≤ dmax`, or `Integer.MAX_VALUE` when there is none.

Known limits, kept as decided (D19 of `llm-coordinate-single-base`):
- an off-tree LLM tap (`MODEL_LLM_TAP`) has no node, so it never counts and records no ledger key;
- the key comes from `Name.toXPath()`, so a refinement that renames a widget makes its next interaction a first one again;
- **batch retirement** (recorded, not changed; author 2026-10-18): one first interaction counts every target at `d*` of its lookup, so three touches of widgets whose generic handler reaches many targets at one distance retire the whole set together. In `e03igg` the 16 steps that retired 5 or more targets hold 240 of 721 retirements and 170 of the 234 targets never reached in their task (owncloud: 84 in one step from the login screen); a target retiring alone had been reached already in 80 % of cases.

Only the targets at `d*` are counted: widget and handler lists carry every target at `d ≤ 3`, and counting a whole list would retire up to several hundred targets through one interaction.

#### Scenario: first interaction counts each target of the node's own lookup once
- **WHEN** under `distance` the agent executes a click on a node whose own lookup has `dist = [[16,3],[17,3],[18,3]]`, no containment node carries a nearer unretired pair, and the ledger has no entry for the widget's key in its activity
- **THEN** the counters of targets 16, 17 and 18 SHALL each increase by exactly 1, and the ledger SHALL then hold the key
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
- **AND** the ledger SHALL still record the key

#### Scenario: an action a restart replaces counts nothing
- **WHEN** under `distance` in a plan whose pipeline assembled `RESTART` a restart is due at step 40, so the `RESTART` guard takes the step before any selection, while the state holds an untried widget whose weight's lookup carries `[[22,2]]`
- **THEN** no counter SHALL change, the ledger SHALL NOT record that widget's key, and the step SHALL carry `dec.src:"restart"` and no `dec.ret`
- **AND WHEN** in the `mop` cell under `distance` the MOP coin selects that widget's action at step 40 and a post-selection restart replaces it by a start action
- **THEN** no counter SHALL change, the ledger SHALL NOT record the widget's key, and the step SHALL carry `dec.src:"restart"` and no `dec.ret`
- **AND WHEN** at a later step no restart is due and that widget's action is selected and executed
- **THEN** target 22's counter SHALL increase by exactly 1

#### Scenario: second interaction of the same widget in the same activity does not count
- **WHEN** under `distance` the same widget is executed again in the same activity, so `MopInteractionLedger.tried` returns true
- **THEN** no counter SHALL change

#### Scenario: a target retires at the third count
- **WHEN** under `distance` with `ape.mopRetireAfter=3`, target 22 has counter 2, and a first interaction of another widget whose lookup carries `[22,2]` is executed
- **THEN** target 22's counter SHALL become 3 and `onFirstInteraction` SHALL return `[22]`
- **AND** the step's record SHALL carry `dec.ret` with index 22, and `retired(22)` SHALL be true for the rest of the run

#### Scenario: retired target ignored by distance scoring
- **WHEN** under `distance` an action's lookup carries `dist = [[4,1],[7,2]]` and target 4 is retired
- **THEN** the action's distance SHALL be 2 and its weight before the tie split SHALL be `ape.mopWeightD2` (400)
- **AND WHEN** target 7 is also retired
- **THEN** the action's weight SHALL be 0 and it SHALL NOT be eligible for the MOP coin

#### Scenario: no counting under flag
- **WHEN** `ape.mopScoring=flag` and the agent executes the first interaction of a widget whose lookup carries pairs
- **THEN** no `MopRetirement` SHALL exist, no counter SHALL change, and no step record SHALL carry `dec.ret`
- **AND** the ledger SHALL still record the key, because the coin reads it in both modes

#### Scenario: an off-tree LLM tap never counts
- **WHEN** under `distance` the executed action is a `MODEL_LLM_TAP` at a pixel that lies inside a widget whose lookup carries pairs
- **THEN** no counter SHALL change and no ledger key SHALL be recorded, because the tap has no resolved node to look up

### Requirement: MOP Coin Decider

`MopCoinStage` (feature `MOP_COIN`, a MOP member activated by `ape.mopCoinPercentage`, POSITIVE, neutral `0`, jar default `1.0`, clamped to `[0,1]` at resolution — no abort on a value) SHALL be the MOP group's decider. It sits in the decision pipeline after `MOP_LAUNCHER` and `COMPONENT_TRIGGER` and before `SATA_CHAIN` (`decision-pipeline`), so with the LLM mounted it decides only the steps the LLM leaves — the `1 − p_L` steps and the steps a gate, an error or a decline returns — and with the LLM off it is the first decider after the guards and the launcher (the component trigger is a side effect, not a decider; plan D-A10). A step makes at most one LLM call (design decision A3): once an LLM stage has called, the later LLM stages return `Continue` without calling, so the MOP coin is reached after at most one call.

**Eligibility.** The stage is eligible on a step when the new state's `getActions()` holds at least one action that is valid, enabled and target-requiring, and that meets the three conditions of design D-B10:

1. its undivided weight `ModelAction.getMopWeight()` is positive — under `distance`, the weight of an unretired target ("MopScorer — Priority Boost", "Target Retirement"); under `flag`, the direct/transitive mark;
2. `MopInteractionLedger.count(activity, xpath|ActionType)` is below `ape.mopCoinLedgerMaxInteractions` (default 1, so `tried` is false; "MopInteractionLedger — Executions per Activity and Key", design D-B17);
3. when `FORM_COMPLETION` is in the plan, it is not the submit candidate the form-completion context excludes (INV-FORM-06).

Visit count is not a condition: an action visited in the abstract state but never tried in the activity is eligible, and one tried in another state of the same activity is not, at the default k = 1 (with k > 1, until its key counts k executions).

**The coin.** The stage reads `u_M`, drawn at step open from the MOP coin's own stream (`StepRng.mopCoin()`, `decision-pipeline`, P-3). When the step is eligible and `u_M < p_M`, the stage SHALL select; otherwise it continues to the SATA chain. With `p_M = 1.0` the stage selects on every eligible step it reaches.

**Ranking.** Among the eligible actions, the stage SHALL take, each key consulted only to break a tie on the keys before it:

1. the largest `mopWeight` (undivided: the tie split of `distance` is not read, so three actions at `d ≤ 1` split to 400 each do not tie with one at `d = 2`);
2. under `ape.mopScoring=distance` only, the larger number of distinct unretired targets within `d + 1` over the pairs of the same lookup that produced the weight, `d` being the distance behind that weight;
3. the first in `State.getActions()` order.

Priority SHALL NOT be a key, so no ADDONS or STATIC scorer can steer the coin (rule R1, `scoring-pipeline`).

**Record.** A step the coin takes SHALL carry `dec.src:"mop"`, the coin's draw as `dec.own.uM`, and the lookup fields of the chosen action (`dec.mopd`, `dec.mops`, `dec.hnd`). The stage commits its pick when the agent keeps it (`onSelectionKept`, `decision-pipeline` "Episode-State Ownership and Reset"): then, when the SATA buffer is non-empty, it abandons it without weakening and the record carries `dec.buf:"mop"` (`action-selection` "Buffer Abandonment Without Weakening"), and `RUN_END.counters.coinMop` counts the step. A pick that a post-selection restart replaces (a plan without `APE_FIXES`, such as the `mop` cell) counts no `coinMop` and leaves the buffer to the restart's new episode, which weakens the planned edge as in APE (`decision-pipeline` "Buffer Abandonment by a Preempting Owner"). On the stage path every pick is kept. `dec.own.uM` is written on every step while `MOP` is mounted (`event-sink`).

#### Scenario: eligible step taken at the default dose
- **WHEN** the `mop` cell runs under `distance` with `p_M = 1.0`, the state of activity `A` holds X (`mopWeight=500`, untried in `A`) and Y (`mopWeight=0`), and no earlier stage took the step
- **THEN** the stage SHALL select X, and the record SHALL carry `dec.src:"mop"`

#### Scenario: a pick replaced by a post-selection restart is not counted
- **WHEN** in the `mop` cell the stage selects X with a planned transition in the SATA buffer, and a periodic restart then replaces X after selection
- **THEN** `coinMop` SHALL NOT count the step, the record SHALL carry no `dec.buf`, and the restart's new episode SHALL weaken the planned edge

#### Scenario: largest weight wins, priority ignored
- **WHEN** two eligible actions carry `mopWeight` 400 (priority 900, raised by `coverageBoost`) and 500 (priority 532)
- **THEN** the stage SHALL select the `mopWeight=500` action

#### Scenario: target count breaks a weight tie under distance
- **WHEN** `ape.mopScoring=distance` and two eligible actions both carry `mopWeight=400` from a nearest unretired target at `d = 2`, X's lookup with unretired targets at distances `[2, 3, 3]` and Y's at `[2, 5]`, Y first in `getActions()`
- **THEN** the stage SHALL select X

#### Scenario: equal on every key, the first in action order wins
- **WHEN** `ape.mopScoring=flag` and two eligible actions both carry `mopWeight=500`, X before Y in `getActions()`
- **THEN** the stage SHALL select X

#### Scenario: an action tried in the activity is not eligible
- **WHEN** at the default `ape.mopCoinLedgerMaxInteractions=1` the only action with `mopWeight > 0` targets widget W, whose key `xpath|MODEL_CLICK` the ledger holds for activity `A` from an execution in another state of `A`
- **THEN** the step SHALL NOT be eligible and SHALL continue to the SATA chain, with no `dec.src:"mop"`

#### Scenario: an unvisited-but-tried action and a visited-but-untried action
- **WHEN** at the default `ape.mopCoinLedgerMaxInteractions=1` action X is unvisited in the current abstract state but its key is in the ledger for the activity, and action Y has `visitedCount=2` in this state but its key is not in the ledger (both `mopWeight=300`)
- **THEN** only Y SHALL be eligible

#### Scenario: excluded submit is skipped when form completion is mounted
- **WHEN** the `as_mop` cell runs, the only eligible-by-weight action is the form submit, and the form-completion context holds
- **THEN** the step SHALL NOT be eligible
- **AND WHEN** the `mop` cell (no `FORM_COMPLETION`) meets the same screen
- **THEN** the submit SHALL be eligible and taken

#### Scenario: the coin loses
- **WHEN** `ape.mopCoinPercentage=0.4`, the step is eligible and `u_M = 0.7`
- **THEN** the stage SHALL continue to the SATA chain, the record SHALL carry `dec.src:"sata"` and `dec.own.uM:0.7`

#### Scenario: a zero dose does not mount the stage and shifts no draw
- **WHEN** `ape.mopCoinPercentage=0` in a cell with `MOP`
- **THEN** `MOP_COIN` SHALL be absent from the plan's `features` and `MopCoinStage` SHALL NOT be mounted
- **AND** the sequence of SATA's choices SHALL equal that of the same cell with `p_M = 1.0` on every step where the coin would not have been eligible, because `u_M` is drawn from its own stream (`RngNeutralityTest`)

#### Scenario: the LLM coin comes first
- **WHEN** the `as_mop_llm` cell runs with `p_L = 0.9`, the step is eligible for the MOP coin, `u_L = 0.95` and `u_M = 0.1`
- **THEN** the LLM coin SHALL lose, the MOP coin SHALL take the step, and the record SHALL carry `dec.src:"mop"`
- **AND WHEN** `u_L = 0.3` and the LLM call returns a usable action
- **THEN** the LLM SHALL take the step and the MOP coin SHALL NOT be reached
- **AND WHEN** `u_L = 0.3`, the new-state stage has already called the model on the step and its answer was declined
- **THEN** the LLM coin SHALL return `Continue` without a second call, and the MOP coin SHALL take the step with `u_M = 0.1`

### Requirement: MopInteractionLedger — Executions per Activity and Key

`MopInteractionLedger` (`agent/MopInteractionLedger`) SHALL be a MOP-owned count of the executions of each (activity, key) pair, where key is `xpath|ActionType` (the convention of `UICoverageTracker.widgetId`, `UICoverageTracker.java:240-250`). Its interface is `int count(String activity, String key)`, `boolean tried(String activity, String key)` (`count > 0`) and `void record(String activity, String key)`, which adds one (design D-B17). It is constructed with the coin's budget k and answers `belowBudget(activity, key)` (`count < k`), so the coin needs no parameter of its own. It exists once per run when `MOP` is in the plan, in both scoring modes, and is absent otherwise. It reaches the stages through `StageCollaborators.mopLedger()` (`decision-pipeline`). No scoring pass reads it.

The agent SHALL record into the ledger, in `StatefulAgent.moveForward`, every executed model action that has a resolved target, whatever owner selected it (SATA, the LLM, the coin). A `MODEL_LLM_TAP` off the tree has no node and records nothing. An action that a post-selection restart replaces by a start action (`decision-pipeline` "Post-Selection Restart", a plan whose pipeline did not assemble `RESTART`, such as the `mop` cell) is not executed and never reaches `moveForward`, so it records nothing, whichever owner selected it; it stays marked visited in the graph, as in APE, which the ledger does not read. Recording a key already present adds one to its count. The ledger is independent of `UICoverageTracker`, which belongs to the ADDONS coverage scorer: a cell without ADDONS still has a ledger when it has `MOP`.

The MOP coin reads `belowBudget` for eligibility, against `ape.mopCoinLedgerMaxInteractions` (k, `MOP_COIN`, int, default 1, `< 1` clamped to 1 at resolution, as the other plan clamps); target retirement reads it to decide whether an execution is a first interaction ("Target Retirement"), reading before `record` writes. Neither keeps a first-interaction record of its own (INV-MOP-40). Because the coin's own pick is recorded when executed, a key can be taken by the coin at most k times per run (once at the default), which is the property `ape.mopTargetPickCap` used to bound. Retirement reads only `tried`, so k does not change what retires.

#### Scenario: every execution adds one to its key's count
- **WHEN** a cell with `MOP` executes a `MODEL_CLICK` on widget `/a/b` in activity `A`, twice in two different states of `A`
- **THEN** after the first execution `tried("A", "/a/b|MODEL_CLICK")` SHALL be true
- **AND** the second execution SHALL add one to the key's count and SHALL NOT be a first interaction

#### Scenario: with k = 3 the coin may take a key three times; retirement counts once
- **WHEN** `ape.mopCoinLedgerMaxInteractions=3` and widget `/a/b` (`mopWeight=500`, weight's lookup `[[22,1]]`) in activity `A` was executed once, by SATA
- **THEN** the action on `/a/b` SHALL still be eligible for the coin in `A` until the ledger counts 3 executions of its key, whatever their owners
- **AND** target 22's counter SHALL have increased by exactly 1 over those executions

#### Scenario: action type and activity are part of the key
- **WHEN** widget `/a/b` was clicked in activity `A`
- **THEN** `tried("A", "/a/b|MODEL_LONG_CLICK")` SHALL be false and `tried("B", "/a/b|MODEL_CLICK")` SHALL be false

#### Scenario: no ledger without MOP
- **WHEN** the `as` cell runs
- **THEN** no `MopInteractionLedger` SHALL be constructed and `StageCollaborators.mopLedger()` SHALL not be reached by any mounted stage

#### Scenario: an action replaced after selection records nothing
- **WHEN** in the `mop` cell SATA selects a `MODEL_CLICK` on widget `/a/b` in activity `A` whose key the ledger does not hold, and a post-selection restart replaces it by a start action
- **THEN** `tried("A", "/a/b|MODEL_CLICK")` SHALL still be false after the step
- **AND** the action on `/a/b` SHALL remain eligible for the MOP coin in `A` at the default `ape.mopCoinLedgerMaxInteractions=1`

### Requirement: Lookup Names the Stamped Handler

`MopData.Lookup` SHALL carry, beside `direct`, `transitive`, `dist` and `source` ("Handler Stamp Lookup"), the field `handler`: the binary class name of the stamp that answered the lookup when `source` is `STAMP`, and null for `ID` and `NONE` (design D-B17). It is set by `MopData.lookup` on its stamp branch and read only by telemetry (`dec.hnd`, `event-sink`); no decision reads it.

#### Scenario: a stamped lookup names its handler
- **WHEN** a click on a node stamped `rvsec.click=com.x.ui.ListAdapter$1` is looked up, and `handlers` lists `com.x.ui.ListAdapter$1`
- **THEN** the lookup SHALL have `source` `STAMP` and `handler` `com.x.ui.ListAdapter$1`
- **AND WHEN** the same widget is looked up through its id key
- **THEN** `handler` SHALL be null

### Requirement: Navigation Tie-Breaks as MOP Variants

The MOP group SHALL contribute the MOP variants of two SATA extension points (`action-selection` "SATA Navigation Extension Points (X1, X3, X4)"), mounted by the `SataAgent` composition root when `MOP` is in the plan (D-A7, D-B12):

- **X3, `MopAbaTargetTie`**: on a visit-count tie between the incumbent greedy target and a candidate in the ABA scan, `replaces(incumbent, candidate)` SHALL return true when `MopScorer.stateMopDensity(candidate) > MopScorer.stateMopDensity(incumbent)`, and false otherwise. It draws nothing.
- **X4, `MopTrivialPathPick`**: among the shortest paths to a trivial activity, `pick(paths)` SHALL return the first path whose end state holds the strictly largest `stateMopDensity`; when every end-state density is equal it SHALL return `RandomHelper.randomPick(paths)`. With one path, the densities are trivially equal and `randomPick` is called, as in the APE default.

Both variants read `MopData` through `MopScorer.stateMopDensity` ("MopScorer — MOP-Flagged State Density") and nothing else. In a cell without `MOP` the APE defaults are mounted and no density is computed (INV-MOP-41).

#### Scenario: X3 variant replaces on a denser candidate
- **WHEN** the `mop` cell's ABA scan meets two greedy targets of activity `A` with visit count 2 each, incumbent density 1 and candidate density 3
- **THEN** the candidate SHALL become the target

#### Scenario: X3 variant keeps the incumbent on equal density
- **WHEN** the incumbent and the candidate have visit count 2 and density 2 each
- **THEN** the incumbent SHALL stay, and no draw SHALL be consumed

#### Scenario: X4 variant falls back to the APE draw
- **WHEN** the `mop` cell finds two shortest paths to a trivial activity, both end-state densities 0
- **THEN** `RandomHelper.randomPick` SHALL choose, consuming one helper-stream draw

#### Scenario: no density without MOP
- **WHEN** the `as` cell, whose plan loads the artifact for STATIC but has no `MOP`, reaches the ABA scan and the trivial-path choice
- **THEN** `stateMopDensity` SHALL NOT be called and the APE defaults SHALL decide

## Invariants

- **INV-MOP-01**: `StaticArtifactLoader.load()` SHALL never throw a checked exception or a JSON parse exception to the caller. Read and parse errors SHALL be caught internally and result in a `null` return with a WARNING log and a `MOP_DATA` rejection record.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `MOP` is absent from the resolved plan, no MOP scoring pass, no MOP coin, no ledger, no retirement and no MOP navigation variant SHALL be constructed, and no `MopData` SHALL be handed to any consumer: `getMopData()` SHALL return null if and only if `MOP` is not mounted, even when the artifact was loaded for STATIC (design D-B14.21), and the UICOV coverage dump SHALL omit `mopReach`. A cell without `MOP` SHALL behave identically whether or not `ape.staticDataPath` names an artifact, except for the STATIC consumers.
- **INV-MOP-05**: The MOP scoring passes SHALL run inside the scoring pipeline of `StatefulAgent.adjustActionsByGUITree()`, after the base SATA priority is assigned, in the fixed order of `scoring-pipeline` INV-ARCH-03 (`MopWidget → MenuGateway → WTG → NavFrontier → MopFrontier → Coverage → FormCompletion`, `ScoringPipeline.fromParams`). Under rule R1 no pass reads the priority or another scorer's field, so the WTG term does not depend on this order.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `WTG` is absent from the plan, when `StaticGuiModel` holds no WTG data, when the widget has no matching WTG transition, or when the transition's target is not in the selected MOP-activity set. The weight SHALL be injected from the plan (`ape.mopWeightWtg`); no scorer SHALL read it from `Config`. `WTG`'s rule is POSITIVE: a value `<= 0` leaves `WTG` out of the plan, inert, without aborting (design D-B22).
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.
- **INV-MOP-10**: The STATIC prompt contribution (`StaticPromptContribution.widgetMetadata`) SHALL emit each widget metadata field (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `inputType`, `hint`) and the Spinner `entries` only when non-null and non-empty; a widget with every field null or empty, or no static widget, adds zero tokens.
- **INV-MOP-11**: Unknown keys in a `formatVersion: 2` artifact (top level, per widget, per handler) SHALL be ignored: the loader reads the members it knows from the `org.json` DOM and has no check that rejects the others. A `formatVersion: 1` artifact never reaches this tolerance (INV-MOP-34).
- **INV-MOP-13**: `MopData.activityHasMopOptionsMenu(activity)` SHALL be an O(1) test over the gateway set `MopData.recomputeMopOptionsMenus` computes once at load from the artifact's `optionsMenus` records, `StaticGuiModel.wtgTransitions` and the selected MOP-activity set: an activity qualifies iff its record has `hasFlaggedWidget == true`, or any WTG click edge from that base activity targets an activity of the selected set (the gateway case). `MopScorer.scoreOpenMenu(activity, data, weight)` reads it.
- **INV-MOP-14**: A flag for an event SHALL be read per event when the entry's `mop` map has a key for that event, an explicit `none` included, and otherwise from the entry's aggregate `directMop`/`transitiveMop` (the OR over the map); a null event reads the aggregate (`MopData.MopEntry.isDirectMop`/`isTransitiveMop`). A handler record read through a stamp has no aggregate fallback: an event missing from it reads as `none` (`isDirectMopForEvent`/`isTransitiveMopForEvent`).
- **INV-MOP-21**: Every `StaticArtifactLoader.load` invocation with a non-null path SHALL record exactly one `MOP_DATA` sink record — the census of the loaded artifact, or `status` `rejected` with its reason — never to logcat.
- **INV-MOP-22**: A run whose plan mounts `STATIC` or `MOP` SHALL either obtain both providers from `StaticArtifactLoader.load` or abort; it SHALL never run with a mounted group and no data.
- **INV-MOP-23**: Static-widget resolution for typed input and MOP resolution SHALL walk the same containment node set (`MopScorer.containmentNodes`): the node, up to two ancestors, and its descendants two levels down. On each node, MOP resolution applies `MopData.lookup` (stamp, then id key), and typed-input resolution applies `StaticGuiModel.widget` (id key). The two paths SHALL NOT diverge in which nodes they visit or in the order they visit them.
- **INV-MOP-24**: Above the `+1` activity-substrate floor, `stateMopDensity` SHALL count only actions whose resolved node's own lookup counts under the scoring mode: under `flag` a direct or transitive flag for the action's event, under `distance` a positive weight (an unretired pair at `d ≤ 3`). It SHALL never reduce to a total action count, and the floor SHALL be exactly `1` (never proportional to action count or widget count).
- **INV-MOP-32**: The `MOP_DATA` counters — `mopActivities`, `mopActsAugmented`, and the `stats` echo (`windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`) — SHALL be pure counters over the load; their presence or values SHALL NOT change widget flags, the selected MOP-activity set, or the loaded/rejected decision.
- **INV-MOP-33**: The navigation tie-break log SHALL be emitted only by the X4 MOP variant and only on its decisive branch; it SHALL NOT alter which path is selected.
- **INV-MOP-19**: On a `shortId` collision within a base activity, the widget's MOP flags SHALL be the strongest (direct > transitive > unflagged), an unflagged widget SHALL never overwrite a flagged one, and the widget's `dist` list for each event SHALL be the merge of the colliding widgets' lists by the minimum distance per target, cut to the targets at `d ≤ 3` (design D15 as amended 2026-10-09). Both outcomes SHALL be order-independent. Both rules are applied by the generator (design D15); on this side a JSON object admits one value per key, so a collision is unrepresentable.
- **INV-MOP-20**: The artifact SHALL carry no widget under an empty short id (the drop is the generator's, rv-android INV-DRV-02). The count of MOP-flagged widgets the generator dropped for lacking a resource id SHALL be echoed once per load as `MOP_DATA.droppedNoId`, from the artifact's `stats.droppedFlaggedNoId`; the jar recomputes nothing.
- **INV-MOP-27**: `MopData.activityHasMop` and `MopData.getMopActivities` SHALL serve the wire `mopActivitiesAugmented` set when `MOP_ACTIVITY_SOURCE` is in the resolved plan and the wire `mopActivities` set otherwise. The selection SHALL be made once, in `StaticArtifactLoader.load`, from `spec().has(Feature.MOP_ACTIVITY_SOURCE)`; every consumer (launcher census, `stateMopDensity` floor, WTG and MOP-frontier target tests, OPTIONSMENU gateway condition 2, the MOP prompt contribution) reads the selected set. The jar computes neither set; `mopActivities ⊆ mopActivitiesAugmented` is a property of the wire (rv-android INV-DRV-06).
- **INV-MOP-34**: `StaticArtifactLoader.load` SHALL accept an artifact whose `formatVersion` is `2`, and no other value. A missing `formatVersion`, the value `1`, or any other value SHALL yield a `MOP_DATA` record with `status` `rejected` and `reason` `version-mismatch`, and a null return. The jar holds no reader for format 1. The generator (`static-analysis-entrypoints`, rv-android `gh122`) emits `2`, and both sides move together.
- **INV-MOP-35**: The jar SHALL NOT parse, store or expose `reachability[]`, `windows[]` or raw `transitions[]` data. Every value it serves SHALL come from the artifact's precomputed fields, plus two derivations made at load from artifact fields only: the OPTIONSMENU gateway set (INV-MOP-13) and a widget's click aggregate (`MopData.clickAggregate`).
- **INV-MOP-36**: Every reader of MOP flags or MOP distances for a node and an event SHALL obtain them from `MopData.lookup(activity, node, eventType)`: the scoring pass, the MOP coin's ranking, the prompt marker and the `MOP NAVIGATION` count (`llm-prompt`), and the retirement trigger. A stamp SHALL be consulted only for the events `click` and `longClick`, and only when `handlers` lists the stamped class. A listed class SHALL decide the result even when it reaches nothing. Static widget metadata SHALL be read only through `StaticGuiModel.widget` (the prompt's metadata and typed input).
- **INV-MOP-37**: Retirement SHALL exist only under `ape.mopScoring=distance`. A target's counter SHALL change only when a model action is executed whose (activity, `xpath|ActionType`) key was not yet in `MopInteractionLedger`, by one for each target of the action's weight's lookup whose pair lies at that lookup's smallest unretired distance `d*`. An action that a post-selection restart replaces is not executed and changes no counter. Counters SHALL never decrease. Under `flag`, no counter exists and no target is retired.
- **INV-MOP-38**: The MOP coin SHALL select only an action that is valid, enabled, target-requiring, has `mopWeight > 0`, whose (activity, `xpath|ActionType`) key has a `MopInteractionLedger` count below `ape.mopCoinLedgerMaxInteractions` (default 1: no entry; design D-B17), and is not the submit candidate excluded by form completion when `FORM_COMPLETION` is mounted. Its ranking SHALL NOT read `priority`.
- **INV-MOP-39**: The MOP coin's draw `u_M` SHALL come from its own stream, drawn once at every step open while `MOP` is mounted, before any stage runs; whether the coin is eligible, wins or is reached SHALL NOT change any draw of the agent or helper stream.
- **INV-MOP-40**: `MopInteractionLedger` SHALL be written only after a model action with a resolved target is executed (an action that a post-selection restart replaces is not executed and records nothing), SHALL count every such execution of a key, and SHALL never forget a key; the coin and retirement SHALL read the same ledger and keep no first-interaction record of their own.
- **INV-MOP-41**: No MOP data SHALL be read by SATA outside the MOP variants of X3 and X4 and the scorer fields of the mounted MOP passes.

---

## Data Contracts

### Input
- `ape.staticDataPath: String` (plan key, `STATIC_DATA`; read through `RunContext.current().spec().staticData().dataPath()`) — device path to the compact artifact, format 2 (`/data/local/tmp/mop-artifact.json` as aperv-tool pushes it). Read only when `STATIC` or `MOP` is mounted; otherwise inert.
- The compact artifact at that path — derived host-side by rv-android's generator (`aperv-derive/2`) from the full static-analysis JSON; `formatVersion: 2`, no `windows`/`reachability`/`transitions` (INV-MOP-34).

### Output
- `StaticGuiModel` and `MopData` (`StaticArtifactLoader.Loaded`), or null; `MopData` reaches consumers only with `MOP` (INV-MOP-04)
- MOP scorer fields on `ModelAction` (`mopBoost`, `wtgBoost`, `mopFrontierBoost`, `menuBoost`) and the undivided `mopWeight`, added to the priority by the mounted passes (additive, in-memory only; not persisted)
- One `MOP_DATA` sink record per load with a non-null path (INV-MOP-21)

### Side-Effects
- In-memory priority adjustments on `ModelAction` objects; `MopInteractionLedger` counts and, under `distance`, retirement counters (in-memory, per run)
- **[Trace]**: `MOP_DATA` — the load census, or `status` `rejected` with `reason` `file-missing`, `parse-error`, `version-mismatch` or `package-mismatch`

### Error
- `StaticArtifactLoader.load` catches read and JSON errors and returns null after recording the rejection; on a plan that mounts `STATIC` or `MOP`, that null aborts the run (INV-MOP-22). There is no parse budget and no `OutOfMemoryError` containment: the artifact is bounded by construction (design D5).
- No exception propagates from `MopData` or `MopScorer` to callers
