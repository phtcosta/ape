# Specification: MOP-Guided Action Scoring

## Purpose

MOP guidance steers APE's action priorities toward widgets and activities that reach monitored operations (MOP specs). `ape.mopDataPath` points at a **compact v1 artifact** on the device; `MopData` loads it and `MopScorer` turns its flags into priority boosts, applied by the scoring passes (`MopWidgetPass`, `MopFrontierPass`, `MenuGatewayPass`, `WtgPass`) that run inside `StatefulAgent.adjustActionsByGUITree()`, after the base SATA priority is assigned and before the agent selects.

**The jar derives nothing.** The cross-referencing that identifies which widgets reach a monitored operation — call-graph reachability, listener-to-handler joins, collision resolution between widgets sharing a short id — happens **host-side**, in the rv-android generator that produces the artifact. What reaches the device is the result: per `(baseActivity, shortId)` a widget carries a `mopRank` distinguishing a direct from a transitive reach, activities arrive as two precomputed sets, and the trigger surface arrives as declared fields. So the artifact has no `windows[]`, no `reachability[]` and no `transitions[]` to parse, and a **legacy full static-analysis JSON is rejected rather than parsed** — the loader recognises it and fails.

**Absence is fatal on a MOP arm, deliberately.** An arm asking for MOP guidance whose artifact is missing or underivable fails its task instead of continuing as plain `sata` (INV-APERV-05). Silently degrading to bare APE while still reporting as a MOP arm is the failure class this capability exists to prevent: it produces a run that looks like treatment and behaves like control. A non-MOP arm simply never names an artifact and never loads one.

Two things stay computed on the device on purpose. The **OPTIONSMENU gateway** is recomputed at runtime from the artifact's menu and activity-set views rather than shipped precomputed (INV-MOP-13), because whether the gateway opens depends on which activity set the arm selected. And the **activity set is chosen at load**: `mopActivities` (widget-derived) or `mopActivitiesAugmented` (the A′ union of three sources), under `ape.mopActivitySourceComponents` — the same set that feeds the cadence launcher in `component-triggering`, which is what makes the two arms' launched sets differ.

---
## Requirements
### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the **derived compact MOP artifact** (`formatVersion: 1`) produced host-side by `aperv-tool` and build the typed query model:

1. **Version gate**: the top-level `formatVersion` field is mandatory and SHALL equal a supported version (currently `1`). Missing or unsupported values ⇒ `status=rejected reason=version-mismatch`, null return (INV-MOP-34). The version gate replaces the `"complete": true` sentinel of the full-JSON era (INV-MOP-09): the generator derives only from complete analyses, so a versioned artifact is complete by construction.
2. **Top-level scalars**: `getPackageName()`, `getMainActivity()` from the artifact's `package` / `mainActivity`.
3. **Widgets**: `getWidget(activity, shortId)` serves the artifact's `widgets` map (`baseActivity → shortId → widget`). Each `Widget` carries the consumed metadata fields — `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (each null/empty when absent on the wire) — and the MOP flags decoded from the wire `mop` map (normalized eventType → `none|direct|transitive|both`): `directMopByEventType`/`transitiveMopByEventType` are populated with an entry for **every** wire key (including `none` entries — key presence drives the per-event vs aggregate fallback), and the aggregate `directMop`/`transitiveMop` are the OR across the map values. `Widget.isDirectMop`/`isTransitiveMop` and their normalization-and-fallback semantics (INV-MOP-08, INV-MOP-14) are unchanged. Fields with no production consumer (`id`, `type`, `text`, raw `listeners`) SHALL NOT exist on the wire or in the model.
4. **MOP-activity sets**: `mopActivities` and `mopActivitiesAugmented` are read as-is from the wire. `getMopActivities()` / `activityHasMop(activity)` SHALL serve the augmented set when `Config.mopActivitySourceComponents == true` and the widget-derived set otherwise (INV-MOP-27's selection semantics, now between two precomputed sets).
5. **OPTIONSMENU gateways**: `activityHasMopOptionsMenu(activity)` SHALL be computed at load time from the artifact's `optionsMenus[]` records (`{activity, hasFlaggedWidget}`) and the loaded WTG view: an activity qualifies iff its record has `hasFlaggedWidget == true` OR any WTG click edge from that activity targets an activity in the *selected* MOP-activity set (same two-condition rule as before, INV-MOP-13).
6. **WTG**: `hasWtgData()` / `getWtgTransitions(activity)` serve the wire `wtg` map (`sourceBaseActivity → [{widget, target}]`, already base-activity-keyed and deduplicated host-side per INV-WTG-04). `WtgTransition` carries `widgetName` and `targetActivity`; the `widgetClass` field is deleted (zero production readers).
7. **Components**: each entry carries `className`, `componentType` (from the parent array key), `isMain`, `permission` (null when no gate), `reachesMop` (wire name — the D7 rename of `reachesTarget`); activities additionally carry `deepLinkUri` (null when absent on the wire); receivers/services carry `intentFilters` (`actions` + `categories` only) and `hasTargetMethods` (boolean — the only consumed use of the former signature list is an emptiness test); providers additionally carry `authorities`. The D15 `data` block, `readPermission`/`writePermission`, the `targetMethods` signature list, and `exported` SHALL NOT exist on the wire (they remain in the host-side full JSON). `exported` leaves with them because no jar code path reads it and none may: the launcher's eligibility rule forbids consulting it, so its absence makes that prohibition structural rather than merely stated.

   `MopLauncherStage.buildDeepLinkUri` is deleted with the filter structure it walked: the activity launcher SHALL read `ComponentInfo.deepLinkUri` directly and pass it to `ActivityTriggerAction` unchanged, so `MonkeySourceApe`'s dispatch — `Intent.ACTION_VIEW` + `Uri.parse(uri)` when non-null, explicit component otherwise — keeps its exact current behavior with the assembly rule now living in the generator (INV-DRV-07). This is a relocation of *where the string is computed*, not a change to what is dispatched.
8. **Sanity check**: `package`/`mainActivity` comparison per the "MopData — Package / MainActivity Sanity Check" requirement (unchanged semantics).
9. **Stats echo**: the artifact's `stats` block (generator-computed diagnostics: `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, handler-join counters, `wtgEdges`) is carried through to the load status record without recomputation; it SHALL NOT influence any query result or the load outcome (the discipline of the former INV-MOP-31/32).

Unknown JSON keys within a supported `formatVersion` are ignored (INV-MOP-11). This tolerance is not a compatibility affordance and adds nothing: it is the `org.json` DOM's default behavior — the *absence* of a check — so rejecting unknown keys would mean writing new code, not removing legacy code. Under the coordinated cut (design D8) generator and jar ship together, so an unknown key in a `formatVersion: 1` artifact is a generator↔jar skew signal, visible in the `stats` echo, not a version to accommodate. The file is read once with a single pre-sized allocation and parsed once into an `org.json` DOM. No cross-referencing, flag derivation, dialog re-keying, activity augmentation, or transition processing happens on-device (INV-MOP-35) — those semantics are generator requirements (see the `static-analysis-entrypoints` delta).

**On the scenario headers below, and this is the capability where it matters most.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so every claim that survives this change under new vocabulary must be restated under the *main spec's* header or the archive silently drops it. This requirement's fifteen headers were all written about the full-JSON parser group 5 deleted, so nearly every one of them now reads oddly against a body about the compact reader: there is no `reachability[]` to build maps from, no `listeners` to double-count, no `complete` sentinel, no D15 `data` block. The headers are the archive's cost and are not evidence that anything still parses a call graph. Three further headers are restated here purely to be matched, their claims being owned elsewhere: the two package-sanity ones belong to the "MopData — Package / MainActivity Sanity Check" requirement of this same delta, and each body says so.

#### Scenario: Real cryptoapp fixture loads every typed field
- **WHEN** `MopData.load()` is called on the `cryptoapp.apk.mop.json` fixture derived from `cryptoapp.apk.gh60-fresh.json`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getPackageName()=="br.unb.cic.cryptoapp"`, `getMainActivity()=="br.unb.cic.cryptoapp.MainActivity"`
- **AND** the MOP-flagged widgets SHALL be exactly three: `buttonGenerateHash` (MessageDigestActivity), `btn_cipher_encrypt` (CipherActivity), and `executeButton` (CryptographyActivity) — all `transitiveMop==true`, `directMop==false`
- **AND** the third SHALL be there because of the D8 synthetic-lambda recovery and for no other reason: `executeButton`'s handler is `CryptographyActivity$$ExternalSyntheticLambda0:onClick`, which no `reachability[]` signature matches exactly, and it is flagged only through the enclosing class's reaching `lambda$setupExecuteButton$0` (INV-DRV-01). A fixture asserting two widgets is asserting that the recovery did not run
- **AND** `activityHasMop` SHALL be true for `MessageDigestActivity`, `CipherActivity` and `CryptographyActivity`, and false for `MainActivity` (default flag state)
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway via WTG edges to the MOP sub-activities, INV-MOP-13)
- **AND** the `spinnerMessageDigest` widget SHALL carry its 13 `entries` and its metadata fields
- **AND** `getActivities().size()==4`; `getProviders().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `getReceivers().isEmpty()`; `getServices().isEmpty()`; every component `reachesMop==false`

#### Scenario: Legacy full static-analysis JSON is rejected
- **WHEN** `MopData.load()` is pointed at a pre-change full static-analysis JSON (top-level `windows`/`reachability`, no `formatVersion`)
- **THEN** `load` SHALL return null
- **AND** emit exactly one `status=rejected reason=version-mismatch` record
- **AND** with `ape.mopDataPath` set, the run SHALL abort via `StopTestingException` (INV-MOP-22) — never proceed as pure SATA

#### Scenario: Per-event-type reachability maps built
- **WHEN** a wire widget has `"mop": {"click": "both", "scroll": "none"}`
- **THEN** `isDirectMop("click")==true` and `isTransitiveMop("click")==true`; `isDirectMop("scroll")==false` and `isTransitiveMop("scroll")==false` (explicit `none` entry, no aggregate fallback)
- **AND** `isDirectMop("longClick")==true` (key absent ⇒ aggregate fallback; aggregates are the OR of the respective bits over the map)
- **AND** the wire values decode to the two bits positionally (`none`=00, `direct`=10, `transitive`=01, `both`=11), losslessly with respect to the two per-event maps the full-JSON derivation produced
- **AND** the loader SHALL decode `direct` faithfully rather than rejecting it, even though a conforming generator never emits it: `direct` implies `transitive` at derivation time (`static-analysis-entrypoints` INV-DRV-01), so the reachable value set is `none`/`transitive`/`both` and the `direct`-alone encoding exists only so the decoder stays positional

#### Scenario: Flag-selected MOP-activity set
- **WHEN** the artifact carries `mopActivities=["A"]` and `mopActivitiesAugmented=["A","B"]`
- **THEN** with `Config.mopActivitySourceComponents=false`, `activityHasMop("B")==false` and `getMopActivities()` SHALL equal `{"A"}`
- **AND** with the flag true, `activityHasMop("B")==true` and `getMopActivities()` SHALL equal `{"A","B"}`
- **AND** the OPTIONSMENU-gateway recompute SHALL use the selected set for its condition-2 test

#### Scenario: Deep-link dispatch reads the wire field
- **WHEN** the artifact's `components.activities[]` entry for `X` carries `"deepLinkUri": "myapp://detail/x"` and the MOP stagnation launcher selects `X`
- **THEN** the `ActivityTriggerAction` SHALL carry that string verbatim, and the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` targeted at the package via `setPackage`, the platform resolving the handling activity (INV-CT-07 dispatch unchanged — see the `component-triggering` delta for why that clause no longer says "component")
- **AND WHEN** the entry omits `deepLinkUri`
- **THEN** the intent SHALL be the explicit-component intent, exactly as when `buildDeepLinkUri` returned null before this change
- **AND** no jar code path SHALL read an intent-filter `data` block, because none exists on the wire

#### Scenario: Absent metadata stays null and costs zero tokens
- **WHEN** a wire widget omits `hint`, `prompt`, `entries`
- **THEN** the corresponding `Widget` fields SHALL be null / empty
- **AND** `ApePromptBuilder` metadata rendering and `ApeAgent.generateInputText` SHALL behave exactly as they do today for null/empty fields (INV-MOP-10 unchanged)

#### Scenario: Unknown keys in a v1 artifact are ignored
- **WHEN** a `formatVersion: 1` artifact carries an additional unknown top-level or per-widget key
- **THEN** `load` SHALL succeed and ignore it (INV-MOP-11)

#### Scenario: gh60 D15 component trigger-surface fields parsed
- **WHEN** `MopData.load()` parses an artifact's `components` block
- **THEN** the trigger surface SHALL be exactly `permission` (null when no gate, `hasPermissionGate()` false), `intentFilters` carrying `actions` and `categories` only, `hasTargetMethods` on receivers and services, and `authorities` on providers
- **AND** `IntentFilter.data`, `DataSpec`, `ProviderInfo.readPermission`/`writePermission`, the `targetMethods` signature list and `exported` SHALL NOT exist on the wire **or on the model** — they remain in the host-side full JSON (`static-analysis-entrypoints` §7)
- **AND** trigger *selection* (INV-MOP-15) SHALL be unchanged by this narrowing: `buildTriggerTuples` reads `reachesMop`, `intentFilters[].actions` and the emptiness of the target-method list, and `buildProviderTuples` reads `reachesMop` and `authorities` — the whole of what it ever read
- **AND** the removal of `exported` SHALL make the launcher's prohibition on consulting it structural rather than stated (`component-triggering` INV-CT-06): there is no field to consult

#### Scenario: Bug-fix regression — widget transitiveMop derived from gh60 Target keys
- **WHEN** `MopData.load()` is called on the compact fixture derived from `cryptoapp.apk.gh60-fresh.json`
- **THEN** the widget `buttonGenerateHash` in `MessageDigestActivity` SHALL have `transitiveMop==true`, `directMop==false` and `isTransitiveMop("click")==true`
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true`
- **AND** `MopScorer.score(<that activity>, "buttonGenerateHash", data, "click")` SHALL return `Config.mopWeightTransitive`
- **AND** the gateway SHALL hold: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **NOTE**: this scenario **is** the contract that "SATA-MOP is not silently bare APE", and it is the half of it that still lives on this side. The derivation it used to assert — handler signature joined against `reachability[]`, producer precedence, the D8 recovery — is the generator's under INV-DRV-01 and is asserted by `gh96`'s `test_index_reachability_stores_direct_and_transitive`, `test_producer_precedence_wins` and `test_direct_implies_transitive`. What the jar can still fail at, and therefore still asserts here, is decoding a flag that arrived correct into a boost that fires

#### Scenario: Widget metadata extracted on post-task-11 fixture
- **WHEN** `MopData.load()` is called on the compact cryptoapp fixture
- **THEN** `getWidget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "editTextMessageDigest")` SHALL carry `inputType=="textPersonName"` and `hint=="Input text ..."`
- **AND** the Spinner widget `spinnerMessageDigest` SHALL carry `entries.size()==13`, the JCA algorithm list
- **AND** `type` and `text` SHALL NOT be asserted, because they no longer exist: they had no production reader and left the wire (INV-MOP-35). The empirical floors of the pre-change scenario (`hint` ≥4, `text` ≥11, `inputType` ≥4) are not restated either — the artifact emits a widget only when flagged or metadata-bearing, so a count over the map is a count of the projection, not of the app

#### Scenario: Top-level package and mainActivity sanity check (default warn-only)
- **WHEN** `MopData.load(path, "x.y.z.OTHER", null)` is called and the artifact's `package=="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null and a `WARN` log line SHALL be emitted naming both the expected and the parsed value
- **AND** this claim is owned in full by the "MopData — Package / MainActivity Sanity Check" requirement of this same delta (scenario `Default warn-only on mismatch`), which this change modifies and whose semantics it leaves unchanged; it is restated here only because the header is one the archive matches on

#### Scenario: Package mismatch rejected in strict mode
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on an artifact with `package=="x.y.z"`
- **THEN** `load` SHALL return `null`, a `WARN` log line SHALL be emitted, and the load record SHALL carry `reason=package-mismatch`
- **AND** as above, the owning requirement is "MopData — Package / MainActivity Sanity Check" (scenario `Strict-mode rejection on mismatch`)

#### Scenario: OPTIONSMENU window with MOP widget triggers activityHasMopOptionsMenu
- **WHEN** the artifact's `optionsMenus[]` carries `{activity: "com.x.A", hasFlaggedWidget: true}`
- **THEN** `activityHasMopOptionsMenu("com.x.A")` SHALL return `true` (condition 1, INV-MOP-13)
- **AND** the qualification SHALL be computed at load from that record, not read from a precomputed gateway set on the wire: design D3 refuses to ship the set precomputed precisely because condition 2 depends on the flag-selected activity set, which is a run-time choice

#### Scenario: OPTIONSMENU window without MOP widget does not trigger
- **WHEN** the record for `com.x.B` carries `hasFlaggedWidget: false` AND no WTG click edge from `com.x.B` targets an activity in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.B")` SHALL return `false` — both conditions failing, which is the only way to a false

#### Scenario: OPTIONSMENU gateway — menu item navigates to a MOP activity
- **WHEN** the record for `com.x.C` carries `hasFlaggedWidget: false` but the wire `wtg["com.x.C"]` contains an edge whose `target` is `com.x.CryptoActivity`, and `com.x.CryptoActivity` is in the selected MOP-activity set
- **THEN** `activityHasMopOptionsMenu("com.x.C")` SHALL return `true` (condition 2, the gateway case)
- **AND** the test SHALL be against the **selected** set, so the same artifact SHALL yield `false` for `com.x.C` under `mopActivitySourceComponents=false` when `com.x.CryptoActivity` is present only in `mopActivitiesAugmented`. That flip is the whole of the evidence that the recompute reads the selection rather than a fixed set

#### Scenario: Multiple listeners to the same handler do not double-count
- **WHEN** a widget's wire `mop` map carries `{"click": "direct"}`
- **THEN** `MopScorer.score(act, id, data, "click")` SHALL return `Config.mopWeightDirect` exactly, never a multiple of it
- **AND** the double-count this scenario was written against SHALL be **unrepresentable rather than prevented**: `listeners` do not exist on the wire and the map holds one value per normalized event type, so there is no multiplicity for the jar to fold. The OR-idempotence that produced that single value is the generator's (INV-DRV-01, `gh96` `test_index_reachability_merges_duplicate_signatures_by_or`, which merges duplicate signatures by OR rather than by last-write)

#### Scenario: Complete-but-empty JSON parses cleanly (gh51-D5 timeout bucket)
- **WHEN** `MopData.load()` is called on a `formatVersion: 1` artifact whose `widgets`, `wtg`, `optionsMenus`, activity sets and component lists are all empty
- **THEN** the returned `MopData` SHALL be non-null
- **AND** every accessor SHALL return empty (`getReceivers()`, `getServices()`, `getActivities()`, `getProviders()`, `getMopActivities()`, `getWtgTransitions(any)`) and `hasWtgData()` SHALL be `false`
- **AND** `MopScorer.score(any, any, data, any)` SHALL return `0` without `NullPointerException`
- **AND** there SHALL be no `isComplete()` and no `complete` sentinel to assert: completeness became a **generation** precondition (`DerivationError` on `complete != true`), so the truncated analysis this bucket was named for is now refused host-side and never reaches a device at all — a strictly earlier failure than the one this scenario used to check

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** two widgets of one base activity resolve to the short id `"submit"`, one `direct`-flagged and one unflagged
- **THEN** the artifact SHALL carry exactly one entry under `widgets.<activity>.submit`, and `getWidget(activity, "submit")` SHALL serve the `direct`-flagged one
- **AND** the resolution SHALL have happened host-side under the `mopRank` policy of INV-DRV-02 (`gh96` `test_collision_keeps_strongest_flag`, `test_collision_direct_outranks_transitive_resident`); on this side the silent demotion the scenario guards against is **unrepresentable**, a JSON object admitting one value per key

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the same two widgets appear in the reverse order in the producer's document
- **THEN** the artifact and therefore `getWidget(activity, "submit")` SHALL be identical to the previous scenario's
- **AND** the order-independence SHALL be the generator's, asserted by `gh96` `test_collision_tie_keeps_first` and the `flagged_first` parametrization of `test_collision_keeps_strongest_flag`, and reinforced by INV-DRV-05: two orderings that derived to different artifacts would break byte-level determinism before they reached the jar

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity's only MOP-flagged widget carries an empty `idName`
- **THEN** the artifact SHALL carry no entry under the empty-string key for that activity, and `getWidget(activity, "")` SHALL return null
- **AND** `activityHasMop(activity)` SHALL still return `true`, and the drop SHALL be counted in `stats.droppedFlaggedNoId`, echoed on the load record as `droppedNoId`
- **AND** the ordering that makes this possible — mark the activity, *then* drop the widget — is the generator's and is asserted in this change's `static-analysis-entrypoints` delta (`a flagged widget with an empty short id still marks its activity`) and by `gh96` `test_flagged_empty_id_marks_activity`. Deriving the activity set from the emitted map instead would shrink it silently, under a normal `status=loaded`

---

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data, String eventType)` SHALL return an integer priority boost according to the following scale. The boost is additive: it is added to the existing `ModelAction.priority`, never replacing it. (The `eventType` parameter selects the per-event-type MOP flag and is unchanged by this change.)

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `+mopWeightDirect` (500) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `+mopWeightTransitive` (300) |
| widget is `null` OR resolves with all MOP flags false | `0` |

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a null widget — including on a MOP-bearing activity — both score `0`. This removes the prior `+mopWeightActivity` (100) fallback, which was applied uniformly to every target widget on a MOP-bearing activity and therefore could not re-rank candidates. The previously-required `INV-MOP-07` (which mandated that fallback) is obsolete and removed; `MopData.activityHasMop(activity)` remains as a predicate consumed by WTG scoring and `stateMopDensity`, but is no longer a branch of `MopScorer.score`.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `data.getWidget(...)` returns flags `{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns flags `{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns a non-null widget with `directMop=false` AND `transitiveMop=false`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: Widget null scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: No match
- **WHEN** the widget carries no MOP flag AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0`

---

### Requirement: Parent/child widget granularity reconciliation

When the exact widget lookup `widgetData.get(activity).get(shortId)` (`MopData.java:620-623`) returns no match, the scorer SHALL attempt to reconcile parent/child granularity using the GUI tree node: it SHALL try the resource ids of the node's ancestors and direct descendants (bounded depth ≤ 2) before declaring no-match. This addresses the case where static analysis flags a container id (e.g., `CardView`) while the runtime resolves a child id (e.g., the inner `LinearLayout`), or vice versa.

The reconciliation requires access to the GUI node, which `MopScorer.score()` does not currently receive. How the node reaches the lookup (a `score()` signature change versus a caller-side resolution in `StatefulAgent.java:1364`) is settled in design.md. The depth bound and a hit-rate log SHALL guard against over-boost (a root container marked by a single MOP child).

#### Scenario: Static flags parent, runtime resolves child
- **WHEN** the exact lookup for the clicked child id returns no match
- **AND** an ancestor within depth 2 has a MOP-flagged widget entry
- **THEN** the scorer SHALL use the ancestor's MOP flags for the boost

#### Scenario: Depth bound respected
- **WHEN** a MOP-flagged ancestor exists only at depth 3
- **THEN** the reconciliation SHALL NOT match it (bound is depth ≤ 2)
- **AND** the scorer SHALL fall through to `0` (the activity-level fallback is removed by this change)

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

`ApePromptBuilder` SHALL surface, in widget descriptions emitted to the LLM, the following fields when non-null/non-empty: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries` (rendered as `entries=[v1, v2, …]`, capped to 10 with trailing `, …`), `inputType`, `hint`.

Each field SHALL appear in the form ` <fieldName>="<value>"` (or `entries=[…]` for the list). Per-value 80-char cap with `…` ellipsis on overflow. Newlines (`\n`, `\r`) flattened to single space. Null / empty ⇒ zero tokens (INV-MOP-10).

The existing `[DM]` / `[M]` markers stay event-type-agnostic in v1 (D16). Revisit only if LLM telemetry shows mismatch confusion.

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

`MopScorer.score(String activity, String shortId, MopData data, String candidateEventType)` SHALL match listeners by `eventType` against the candidate. The existing three-argument `score(activity, shortId, data)` SHALL delegate to the four-argument form with `candidateEventType=null` (match-any).

When `candidateEventType` is non-null AND the widget's per-event-type map has an entry for that key, the per-event-type flag SHALL drive the boost. Otherwise the aggregate `directMop` / `transitiveMop` SHALL apply (match-any fallback, INV-MOP-14).

`MopScorer.eventTypeOf(ModelAction action)` SHALL map:
- `ActionType.MODEL_CLICK` → `"click"`
- `ActionType.MODEL_LONG_CLICK` → `"longClick"`
- `ActionType.MODEL_INPUT` on a Spinner widget (class contains "Spinner") → `"itemSelected"`
- `ActionType.MODEL_SCROLL` → `"scroll"`
- anything else → `null`

#### Scenario: Click-only listener boosts click action only
- **WHEN** widget has `directMopByEventType={click:true, longClick:false}` (per the cross-ref pass)
- **AND** `MopScorer.score(act, id, data, "click")` is called
- **THEN** the return value SHALL equal `Config.mopWeightDirect`
- **WHEN** `MopScorer.score(act, id, data, "longClick")` is called on the same widget
- **THEN** the return value SHALL equal `0`

#### Scenario: Null eventType falls back to aggregate (match-any)
- **WHEN** widget has `directMop=true` (aggregate) AND `MopScorer.score(act, id, data, null)` is called
- **THEN** the return value SHALL equal `Config.mopWeightDirect`

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

`MopData.load` SHALL emit exactly one load status record per invocation, on both outcomes. The record is the out-of-step `MOP_DATA` NDJSON record of the event sink (`rearch-04-step-ndjson-telemetry`, stage 4):

- success: `status=loaded formatVersion=<n> sourceDigest=<sha256> package=<pkg> windows=<n> widgets=<n> flagged=<n> droppedNoId=<n> wtgEdges=<n> mopActivities=<n> mopActsAugmented=<n> components=<n> handlersUnmatched=<n> syntheticLambda=<n> recovered=<n>` — counts sourced from the loaded structures and the artifact's generator-computed `stats` echo (`droppedNoId`, handler-join counters, `orphanDialogs` are host facts, echoed not recomputed)
- failure: `status=rejected reason=<file-missing|parse-error|version-mismatch|package-mismatch>`

`mopActsAugmented` SHALL be `|mopActivitiesAugmented \ mopActivities|` over the two wire sets — what the augmented source would contribute — and SHALL NOT vary with `Config.mopActivitySourceComponents`. The applied augmentation is the product of this number and the flag, and the flag is carried independently by `RUN_START` (feature list plus `params` echo), so nothing is lost by reporting availability here; the reverse is not true, since a flag-off run reporting the applied augmentation reports 0 and no record carries the availability. `mopActivities` remains the size of the **selected** set, so the two fields answer different questions on purpose.

`windows` is in that list deliberately. It is carried by the stage-4 record and by the retired `[APE-MOP-DATA]` line before it, and it survives inside the artifact as `stats.windows`; dropping it here would make the field appear at stage 4 and disappear at stage 7 — a silent mid-window schema regression in the opposite direction from the one this stage otherwise repairs. With it, the set above is exactly the stage-4 census plus the three facts only this stage can supply (`formatVersion`, `sourceDigest`, `components`), so `MOP_DATA` gains fields across the window and never loses one. `transitions` is the single deliberate omission, superseded by `wtgEdges` at stage 4 and never reinstated: it counts the flat transition list, not the click-only view the frontier passes gate on, and it is the field whose misreading this window exists to end.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden. The reject reasons `too-large`, `oom`, and `incomplete` no longer exist (their mechanisms are deleted; incompleteness is a host-side generation precondition).

Fail-fast composition (kills the V21 silent-degradation class end to end):

1. **Plan level** (`rearch-02-runspec`): a plan that declares the MOP feature without an artifact path is invalid and aborts at plan validation, before agent construction.
2. **Artifact level** (this requirement): when `Config.mopDataPath` is set and `MopData.load` returns `null` — for any reject reason — `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing as pure SATA (INV-MOP-22, unchanged). An operator who sets `ape.mopDataPath` has declared the run a MOP-arm run; silently executing it as `sata` mislabels the arm.
3. **Host level** (`aperv-tool` delta of this change): a MOP arm whose full JSON is missing or whose derivation fails raises before launch — the artifact-absent case can no longer reach the device as a silently-unset `ape.mopDataPath`.

When `Config.mopDataPath` is unset, behavior is unchanged (MOP scoring disabled, no status record required beyond the absence of a load).

#### Scenario: successful load emits counters
- **WHEN** `MopData.load` parses a v1 artifact derived from a full JSON whose SHA-256 is `d`, with 30 widgets, 3 flagged, 0 dropped, 16 WTG edges
- **THEN** one `status=loaded` record SHALL be emitted carrying `formatVersion=1`, `sourceDigest=d`, and those counters

#### Scenario: transitions present, click edges absent
- **WHEN** the artifact's `wtg` map is empty because the producer's document carried transitions but none of them a `click` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0`
- **AND** it SHALL carry **no** `transitions` field, so the number that does not gate anything cannot be read as the number that does
- **AND** the jar SHALL have no other number to confuse it with: raw transitions do not exist on the wire (INV-MOP-35), so the deduplication and the click-only filter that produce `wtgEdges` are the generator's (INV-DRV-03) and the field is an echo, not a recount

#### Scenario: rejected load names the reason
- **WHEN** the file at `mopDataPath` is not a v1 compact artifact
- **THEN** one `status=rejected reason=version-mismatch` record SHALL be emitted
- **AND** `load` SHALL return `null`

#### Scenario: fail-fast when the MOP arm cannot arm
- **WHEN** `ape.mopDataPath` is set and `MopData.load` returns `null`
- **THEN** `StatefulAgent` SHALL throw `StopTestingException` during setup
- **AND** the run SHALL NOT proceed as pure SATA

#### Scenario: unset path keeps SATA behavior
- **WHEN** `ape.mopDataPath` is not set
- **THEN** `_mopData` SHALL be `null` and exploration SHALL proceed with MOP scoring disabled, as today

---

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
- **INV-MOP-23**: Static-widget resolution for typed input SHALL use the same containment policy as MOP boost resolution; the two paths SHALL NOT diverge in matching rules.
- **INV-MOP-24**: above the `+1` activity-substrate floor, `stateMopDensity` SHALL count only MOP-flagged resolved widgets; it SHALL never reduce to a total action count, and the floor SHALL be exactly `1` (never proportional to action count or widget count).
- **INV-MOP-32**: `mopActivities`/`mopActsAugmented` SHALL be pure counters over the load; their presence or values SHALL NOT change widget flags, the `mopActivities` set itself, or the loaded/rejected decision.
- **INV-MOP-33**: the tiebreak log SHALL be emitted only from the path-selection site and only on the decisive branch; it SHALL NOT alter which path is selected.
- **INV-MOP-19**: On a `shortId` collision within a base activity, the widget map SHALL retain the strongest MOP flag (direct > transitive > unflagged); an unflagged widget SHALL never overwrite a flagged one; the outcome is order-independent.
- **INV-MOP-20**: Widgets with an empty `idName` SHALL NOT be stored; the count of MOP-flagged widgets dropped for lacking a resource id SHALL be logged once per load.
- **INV-MOP-25**: After load, every DIALOG window reachable via a WTG transition SHALL have its widgets queryable under the host activity's key (subject to the standard collision policy) and removed from the dialog-class **widget-map** key; when a merged widget is flagged, `activityHasMop(host)` SHALL reflect it. The move-not-copy removal applies to the widget map only — the dialog class's Pass-2 `mopActivities` entry SHALL be retained so the OPTIONSMENU-gateway precompute (condition 2) still fires for activities that navigate into a MOP-bearing dialog. Orphan (unreachable) DIALOG windows are counted on a dedicated `[APE-RV]` diagnostic line, never on the `[APE-MOP-DATA]` status line.

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
