# Delta: MOP-Guided Action Scoring (rearch-07-compact-static-artifact)

## Purpose

This delta changes the **input contract** of `MopData`: instead of the full static-analysis JSON (call-graph-heavy `reachability[]` + `windows[]` + `transitions[]` + `components{}`, 1.5–48 MB), the jar consumes a **derived compact MOP artifact** (`formatVersion: 1`) generated on the host by `aperv-tool` from that same full JSON. The artifact contains exactly the projection the explorer reads — the widget MOP-flag/metadata map, the two MOP-activity sets, OPTIONSMENU records, the WTG click view, and the component trigger surface — so the on-device parse of call-graph data is deleted entirely, and with it the `too-large` rejection class and the repo's only `catch(OutOfMemoryError)` (report V19).

Every consumer-facing query of `MopData` is unchanged: `MopScorer`, the scoring passes, the launcher census, component triggering, typed input, and the LLM prompt builder read the same API and see the same values. What moves is *where* those values are computed: the cross-reference of listener handlers against reachability, the synthetic-lambda recovery, the collision policy, the empty-id drop, the dialog re-keying, the base-activity WTG keying, and the A′ activity-set union are now **generator responsibilities** (specified in the `static-analysis-entrypoints` delta of this change) and arrive on the wire precomputed. The `Target`→`MOP` vocabulary boundary (gh13 D7) moves with them: the compact wire format speaks `MOP`; `*Target` keys survive only inside the host-side generator, where the full JSON is read.

Absent-artifact behavior hardens from warn-and-continue to fail-fast: the jar side already aborts when `ape.mopDataPath` is set and the load fails (INV-MOP-22); this change extends the same discipline to new failure classes (`version-mismatch`) and — together with the `aperv-tool` delta — removes the last silent path (a MOP arm whose artifact was never pushed, report V21). Composed with the `rearch-02-runspec` plan validation, there is no input state under which a MOP-planned run silently explores as pure SATA.

## Data Contracts

### Input
- `ape.mopDataPath: String` — device path of the **compact MOP artifact** (pushed as `/data/local/tmp/mop-artifact.json` by `aperv-tool`); null disables MOP guidance (unchanged).
- Compact MOP artifact (`formatVersion: 1`) — see the schema in this change's `design.md` and the generation requirements in the `static-analysis-entrypoints` delta.

### Output
- Unchanged `MopData` query API: `getWidget`, `activityHasMop`, `getMopActivities`, `activityHasMopOptionsMenu`, `hasWtgData`/`getWtgTransitions`, `getReceivers`/`getServices`/`getActivities`/`getProviders`/`hasComponents`, `getPackageName`/`getMainActivity`, `extractShortId`.

### Side-Effects
- **[Trace]**: one `MOP_DATA` NDJSON record per load attempt (the out-of-step sink record introduced by `rearch-04-step-ndjson-telemetry`, which is stage 4 to this change's stage 7 — the sink has landed long before this applies).

### Error
- Never propagates any exception to the caller on I/O or parse failure; all failures return null after emitting exactly one status record (INV-MOP-01, INV-MOP-21). `OutOfMemoryError` is no longer caught anywhere in the load path (see REMOVED "Load memory safety").

## Invariants

- **INV-MOP-34**: `MopData.load` SHALL accept only artifacts whose top-level `formatVersion` equals a version the jar supports (currently `1`); any other input — including a legacy full static-analysis JSON — SHALL be rejected with `status=rejected reason=version-mismatch` and a null return.
- **INV-MOP-35**: The jar SHALL NOT parse, store, or expose `reachability[]`, `windows[]`, or raw `transitions[]` data; every value it serves SHALL come from the compact artifact's precomputed fields (plus the on-device OPTIONSMENU-gateway recompute, which reads only artifact fields).
- Preserved unchanged: INV-MOP-01, INV-MOP-02, INV-MOP-03, INV-MOP-04, INV-MOP-05, INV-MOP-06, INV-MOP-08 (query-side normalization), INV-MOP-11 (unknown keys within a supported `formatVersion` are ignored), INV-MOP-13 (gateway semantics), INV-MOP-14, INV-MOP-15, INV-MOP-21, INV-MOP-22, INV-MOP-23, INV-MOP-24, INV-MOP-27 (flag-gated set selection — now between the two wire sets), INV-MOP-32, INV-MOP-33.
- Relocated host-side (restated as INV-DRV-01..04 in the `static-analysis-entrypoints` delta): INV-MOP-12, INV-MOP-17, INV-MOP-19, INV-MOP-20, INV-MOP-25, INV-MOP-30, INV-MOP-31.
- Retired with their mechanism: INV-MOP-26, INV-MOP-29 (see REMOVED "Load memory safety"), INV-MOP-28 (see REMOVED "Widgetless-Substrate Classifier").

## MODIFIED Requirements

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

#### Scenario: Compact cryptoapp fixture loads every consumed field
- **WHEN** `MopData.load()` is called on the `cryptoapp.apk.mop.json` fixture derived from `cryptoapp.apk.gh60-fresh.json`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getPackageName()=="br.unb.cic.cryptoapp"`, `getMainActivity()=="br.unb.cic.cryptoapp.MainActivity"`
- **AND** the only MOP-flagged widgets SHALL be `buttonGenerateHash` (MessageDigestActivity) and `btn_cipher_encrypt` (CipherActivity), both `transitiveMop==true`, `directMop==false`
- **AND** `activityHasMop` SHALL be true for `MessageDigestActivity` and `CipherActivity` and false for `MainActivity` (default flag state)
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway via WTG edges to the MOP sub-activities, INV-MOP-13)
- **AND** the `spinnerMessageDigest` widget SHALL carry its 13 `entries` and its metadata fields
- **AND** `getActivities().size()==4`; `getProviders().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `getReceivers().isEmpty()`; `getServices().isEmpty()`; every component `reachesMop==false`

#### Scenario: Legacy full static-analysis JSON is rejected
- **WHEN** `MopData.load()` is pointed at a pre-change full static-analysis JSON (top-level `windows`/`reachability`, no `formatVersion`)
- **THEN** `load` SHALL return null
- **AND** emit exactly one `status=rejected reason=version-mismatch` record
- **AND** with `ape.mopDataPath` set, the run SHALL abort via `StopTestingException` (INV-MOP-22) — never proceed as pure SATA

#### Scenario: Per-event flag decoding preserves fallback semantics
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
- **THEN** the `ActivityTriggerAction` SHALL carry that string verbatim, and the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")`, targeted at the component (INV-CT-07 dispatch unchanged)
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

`windows` is in that list deliberately. It is carried by the stage-4 record and by the retired `[APE-MOP-DATA]` line before it, and it survives inside the artifact as `stats.windows`; dropping it here would make the field appear at stage 4 and disappear at stage 7 — a silent mid-window schema regression in the opposite direction from the one this stage otherwise repairs. With it, the set above is exactly the stage-4 census plus the three facts only this stage can supply (`formatVersion`, `sourceDigest`, `components`), so `MOP_DATA` gains fields across the window and never loses one. `transitions` is the single deliberate omission, superseded by `wtgEdges` at stage 4 and never reinstated: it counts the flat transition list, not the click-only view the frontier passes gate on, and it is the field whose misreading this window exists to end.

The record goes to the `.trace` stream, written by the sink directly to `System.out` and never through `Logger` (INV-SNK-11). It SHALL NOT be written to logcat: the rv-platform logcat parser owns that channel and foreign lines are forbidden. The reject reasons `too-large`, `oom`, and `incomplete` no longer exist (their mechanisms are deleted; incompleteness is a host-side generation precondition).

Fail-fast composition (kills the V21 silent-degradation class end to end):

1. **Plan level** (`rearch-02-runspec`): a plan that declares the MOP feature without an artifact path is invalid and aborts at plan validation, before agent construction.
2. **Artifact level** (this requirement): when `Config.mopDataPath` is set and `MopData.load` returns `null` — for any reject reason — `StatefulAgent` SHALL abort the run (throw `StopTestingException`) instead of continuing as pure SATA (INV-MOP-22, unchanged). An operator who sets `ape.mopDataPath` has declared the run a MOP-arm run; silently executing it as `sata` mislabels the arm.
3. **Host level** (`aperv-tool` delta of this change): a MOP arm whose full JSON is missing or whose derivation fails raises before launch — the artifact-absent case can no longer reach the device as a silently-unset `ape.mopDataPath`.

When `Config.mopDataPath` is unset, behavior is unchanged (MOP scoring disabled, no status record required beyond the absence of a load).

#### Scenario: successful load emits provenance and counters
- **WHEN** `MopData.load` parses a v1 artifact derived from a full JSON whose SHA-256 is `d`, with 51 widgets, 2 flagged, 0 dropped, 12 WTG edges
- **THEN** one `status=loaded` record SHALL be emitted carrying `formatVersion=1`, `sourceDigest=d`, and those counters

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

### Requirement: MopData — Activity-Level MOP Source from Components (A′)

The A′ 3-source union (widget-derived activities ∪ component-flagged activities ∪ reachability-flagged activity classes) SHALL be computed **host-side by the generator** and shipped as the wire field `mopActivitiesAugmented`, alongside the widget-derived `mopActivities` (see the `static-analysis-entrypoints` delta for the derivation rules). On-device, `Config.mopActivitySourceComponents` selects which set backs `activityHasMop`/`getMopActivities`:

- flag `false` (default): the widget-derived set, exactly — byte-identical membership to the pre-change widget-derived behavior.
- flag `true`: the augmented set.

The selection SHALL happen once at load; every downstream consumer (launcher census ordering, `stateMopDensity` substrate floor, WTG/MopFrontier target tests, OPTIONSMENU-gateway condition 2) reads the selected set (INV-MOP-27's observable semantics preserved).

The key's plan grounding from `rearch-02-runspec` is **carried forward unchanged**: in the run-spec `Feature` model, `ape.mopActivitySourceComponents` activates the `MOP_ACTIVITY_SOURCE` feature, which depends on `MOP`. An explicit `true` on a plan without `ape.mopDataPath` aborts resolution as a missing dependency; with the feature absent, the widget-derived source is the only one that exists (INV-RUN-05 of `run-spec` — the recorded substitute for the dissolved INV-ARCH-06 kill-switch registration). This stage changes only *where the augmented set is computed* (host-side generator instead of on-device union), never the key's ownership or its fail-fast behavior.

#### Scenario: flag off ⇒ widget-derived set only
- **WHEN** `mopActivitySourceComponents=false` and the artifact's augmented set contains an activity absent from the widget-derived set
- **THEN** `activityHasMop` for that activity SHALL be `false`
- **AND** the launcher census SHALL NOT include it

#### Scenario: flag on ⇒ augmented set feeds every consumer
- **WHEN** `mopActivitySourceComponents=true`
- **THEN** `getMopActivities()` SHALL equal the wire `mopActivitiesAugmented` set
- **AND** the OPTIONSMENU-gateway recompute SHALL test condition 2 against it

#### Scenario: explicit activation without MOP data aborts
- **WHEN** `ape.properties` sets `ape.mopActivitySourceComponents=true` and no `ape.mopDataPath`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MOP_ACTIVITY_SOURCE` and `MOP`
- **AND** the abort SHALL precede any artifact read (the dependency is a plan property, not an artifact property)

---

## REMOVED Requirements

### Requirement: Load memory safety
**Reason**: The mechanism it bounds no longer exists. The on-device input is the host-generated compact artifact, bounded by construction (the generator projects away the call-graph share — 57.7 % of corpus bytes — and the duplicate-heavy raw transitions); a 48 MB input can no longer arrive. The parse-footprint budget (`PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`), the `reason=too-large` rejection, and the outer `catch(OutOfMemoryError)` (`MopData.java:328`, report V19) are **deleted completely — no shim, no smaller budget retained** (P3). INV-MOP-26 and INV-MOP-29 are retired with them. An OOM during load becomes what every other OOM already is: process death → task FAILED → supervisor retry (report Sec. 6.7). The single-allocation `readFile` survives as an implementation detail of the loader, no longer a spec-level memory-safety requirement. The pre-change fail-fast consequence (`null` → INV-MOP-22 abort) is unchanged for every remaining reject reason.

### Requirement: MopData — DIALOG Window Re-Keying to Host Activity
**Reason**: Relocated host-side, not deleted as behavior. The device artifact carries the widget map **already** dialog-merged; the jar has no `windows[]`/`transitions[]` to re-key. The exact semantics (first-incoming-edge host resolution, `mopRank` collision policy on merge, move-not-copy, host promotion to the MOP-activity set, dialog-class retention in the activity set, orphan counting) are restated as generator requirements in the `static-analysis-entrypoints` delta (INV-DRV-03); the orphan-dialog count arrives in the artifact's `stats` echo. The jar-side implementation (`rekeyDialogsToHost`) and INV-MOP-25's on-device obligation are deleted with the full-JSON parser.

### Requirement: MopData — Widget MOP Flag Recovery for Desugared-Lambda Handlers (FIX 2)
**Reason**: Relocated host-side. Flag derivation — including the D8 synthetic-lambda recovery from the enclosing class's reaching `lambda$…` methods — happens in the generator, which is the only component that still sees `reachability[]` (restated under INV-DRV-01 in the `static-analysis-entrypoints` delta, preserving INV-MOP-30's exact recovery rule). The jar receives final per-event flags on the wire and derives nothing.

### Requirement: MopData — Handler-Join Diagnostics on the Load Line (FIX 3)
**Reason**: Relocated host-side. The handler-join counters (`handlersUnmatched`/`syntheticLambda`/`recovered`) are computed by the generator over the full JSON (INV-DRV-04 in the `static-analysis-entrypoints` delta preserves INV-MOP-31's purity discipline: counters never alter derived sets) and shipped in the artifact's `stats` block; the jar echoes them on the load record without recomputation. The jar-side `computeHandlerJoinDiagnostics` is deleted.

### Requirement: MopData — Widgetless-Substrate Classifier (F′ seam)
**Reason**: Deleted completely (P3). `isWidgetlessSubstrate()` had **no production consumer** (its own javadoc: "No consumer yet") and is a pure function of `windows[].widgets`, which no longer exists on the device. The observable fact survives as the generator stat `widgetsTotal` (`0` ⇔ widgetless substrate), available in the artifact `stats` and on the load record, from which a future consumer can be built host- or jar-side if one ever materialises. INV-MOP-28 is retired.

### Requirement: MopData — Activity-Substrate Counters on the Load Line
**Reason**: Superseded by the MODIFIED "MopData — Load Status Line and Fail-Fast" requirement of this change, whose field set now includes `mopActivities=<n> mopActsAugmented=<n>` sourced from the loaded wire sets (the augmentation itself is host-side, so "counters over the load" and "counters over the wire sets" are the same numbers). INV-MOP-32's discipline — counters are pure observability and never change sets, flags, or the load outcome — is preserved and restated in the modified requirement's stats-echo clause. No behavior is lost; the standalone requirement is folded, not weakened.
