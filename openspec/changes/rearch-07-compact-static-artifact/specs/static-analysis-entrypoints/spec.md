# Delta: Static Analysis Entry Points (rearch-07-compact-static-artifact)

## Purpose

This delta adds the **derived compact MOP artifact** to the static-analysis data chain. The producer (rvsec-gator via `rv-static-analysis`) and its full JSON output are unchanged: the full JSON (`<results_dir>/<apk_name>.json`, with `reachability[]`, `windows[]`, `transitions[]`, `components{}`, `*Target` vocabulary) **remains the host-side source of truth** — it is what the frozen phase-2 metric definitions read (*MOP coverage* over `directly_reaches_mop`; hard constraint 4b / rule R9), what offline consolidation re-parses, and what the derivation below takes as input.

What is new is a host-side derivation step, owned by `aperv-tool` (`derive_mop_artifact.py`): a pure projection of the full JSON into the explorer-shaped artifact (`formatVersion: 1`) that is the **only** static-analysis data ever pushed to the device. Every parse-time semantic that `MopData.load` used to execute on-device — the listener-handler × reachability cross-reference, producer-flag precedence, D8 synthetic-lambda recovery, widget-collision ranking, empty-id dropping, DIALOG re-keying, base-activity WTG keying with deduplication, the A′ activity-set union, and the OPTIONSMENU flagged-widget test — relocates here, unchanged in meaning, now unit-testable in pure Python against the same fixtures. The `Target`→`MOP` vocabulary boundary (gh13 D7) moves with the derivation: `reachesTarget`/`directlyReachesTarget`/`targetMethods` appear only where the generator reads the full JSON; everything it emits speaks `MOP`.

The generator is deterministic by contract — identical full-JSON bytes produce a byte-identical artifact — and records provenance (`source.digest` = SHA-256 of the full JSON) so every device artifact, and every trace's `MOP_DATA` record, names its exact static-analysis input.

## Data Contracts

### Input
- `<results_dir>/<apk_name>.json` — full static-analysis JSON (existing producer output, unchanged; `complete == true` required for derivation).

### Output
- `<results_dir>/<apk_name>.mop.json` — compact MOP artifact, cached next to the full JSON; canonical bytes (see determinism requirement); consumed only by the `aperv-tool` push path and, on-device, by `MopData`.

### Side-Effects
- **[Host filesystem]**: one cached artifact per (apk, full-JSON digest); regenerated transparently when missing or stale.

### Error
- `DerivationError` — full JSON structurally unusable (missing `package`, `complete != true`, non-dict/array sections); the generator never writes a partial artifact. Propagated by `aperv-tool` as a task failure on MOP arms (see the `aperv-tool` delta).

## Invariants

- **INV-DRV-01** (ex INV-MOP-12/17/30): Widget MOP flags SHALL be derived per listener and per normalized `eventType`, OR-aggregated across a widget's listeners; a producer-supplied `handlerReachesTarget`/`handlerDirectlyReachesTarget` pair takes precedence over the local cross-reference when non-null; a handler with no exact `reachability[].methods[].signature` match that is a D8 synthetic-lambda wrapper (`X$$ExternalSyntheticLambdaN`) SHALL be recovered from `X`'s reaching `lambda$…` methods, and SHALL NOT be flagged when `X` has no reaching lambda method. The two axes SHALL remain independent and SHALL NOT be collapsed into each other: `direct` is the handler's own `directlyReachesTarget` — a monitored operation invoked in the handler's own body, the property `ape.mopWeightDirect` exists to reward — and `transitive` is its `reachesTarget` **OR** `direct`, so **`direct` implies `transitive`** on every derivation path. The OR is load-bearing rather than cosmetic: the producer emits 33 methods across 16 corpus apps with `directlyReachesTarget && !reachesTarget`, and the old parser's `bySignature` path stored `reachesTarget` unmodified, which would yield a widget that is direct but not transitive. Signatures appearing more than once in `reachability[]` SHALL be merged by OR rather than by last-write, so the index does not depend on producer emission order.
- **INV-DRV-02** (ex INV-MOP-19/20): On a `shortId` collision within a base activity, the emitted widget SHALL carry the strongest MOP flag (direct > transitive > unflagged), order-independently; widgets with an empty short id SHALL NOT be emitted, and the count of MOP-flagged widgets so dropped SHALL be recorded in `stats.droppedFlaggedNoId`. **A MOP-flagged widget SHALL add its base activity to the widget-derived MOP-activity set before the empty-short-id drop is applied**, so an activity whose only flagged widgets are unaddressable by resource id still counts as MOP-bearing: the widget is unscorable, the activity is not. This is the current on-device behavior (`MopData.java:428-444` marks the activity, then drops the widget and increments the counter) and the rule the whole `mopActivities` chain rests on — `scoreWtg`, `MopFrontierPass`, the `stateMopDensity` floor, the OPTIONSMENU gateway's second condition and the launcher census all read that set. Deriving it from the *emitted* widget map instead would shrink it silently, with a normal `status=loaded` in the trace.
- **INV-DRV-03** (ex INV-MOP-25, INV-WTG-04): DIALOG windows SHALL be merged into their host activity (first incoming transition wins; `mopRank` collision policy; move-not-copy; a flagged merge adds the host to the widget-derived activity set; the dialog class's own activity-set entry is retained); WTG edges SHALL be keyed by base **source** activity with base target activities, click events only, exact duplicates removed; orphan dialogs are counted in `stats.orphanDialogs`.
- **INV-DRV-04** (ex INV-MOP-31/32): All `stats` fields SHALL be pure counters over the derivation; their values SHALL NOT influence any emitted set, flag, or edge.
- **INV-DRV-05**: Derivation SHALL be deterministic at the byte level: identical full-JSON input bytes SHALL yield an identical artifact byte sequence (canonical serialization: UTF-8, lexicographically sorted object keys, `,`/`:` separators, deterministic array orders — source first-occurrence for edges and component lists, sorted for activity sets).
- **INV-DRV-07** (ex the jar-side assembly half of INV-CT-07): Each emitted activity SHALL carry `deepLinkUri`, derived by the rule the jar applies today — the first intent-filter that declares `android.intent.action.VIEW` **and** a non-empty scheme list yields `scheme + "://" + host + path`, where host and path are the filter's first entries or the empty string when absent; when no filter qualifies, the field SHALL be absent (equivalently null), which the dispatcher reads as "use the explicit-component intent". The filter structure itself SHALL NOT be on the wire.
- **INV-DRV-06**: The artifact SHALL contain no call-graph data: no `reachability` section, no method signatures, no raw `windows`/`transitions`/`listeners`; and no `*Target` key other than `hasTargetMethods` on receivers and services, the boolean the `targetMethods` signature list compacts to. That exception is deliberate and is the one place the D7 rename does not reach: the name is part of this wire format and the loader reads it verbatim. Stated without it, the rule was unsatisfiable by the schema this same delta specifies, and held only for documents declaring no receiver or service. The full JSON SHALL remain unmodified on the host, and no metric or analysis pipeline SHALL read the derived artifact.

## ADDED Requirements

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
- **AND** `stats.windows==5`, `stats.widgetsTotal==51`, `stats.flagged==3`, `stats.handlersUnmatched==5`, `stats.syntheticLambda==1`, `stats.recovered==1`

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

### Requirement: Corpus equivalence gate for the parser cutover

Before the jar's full-JSON parser is deleted, a one-shot equivalence gate SHALL demonstrate, over the pinned real-JSON corpus (`rvsec-dataset/static_analysis/*.apk.json`, 345 apps, plus the cryptoapp fixture), that for every app the projections served by the **old** parser on the full JSON and by the **new** parser on the derived artifact are identical: widget flag maps (per-event entries and aggregates), widget metadata fields, both MOP-activity sets (flag off and on), OPTIONSMENU-gateway sets (flag off and on), WTG views, component trigger tuples and provider tuples, **the per-activity deep-link URI including its null cases** (INV-DRV-07 — activities are otherwise absent from the tuple comparison, so nothing else would catch a deep-link divergence), and `package`/`mainActivity`. The gate is the merge condition for the cutover commit; the gate code (and the old parser it needs as oracle) is deleted immediately after it passes (P3 — the permanent protection is the per-rule generator unit tests plus the jar fixture tests).

The gate SHALL additionally report how many corpus apps actually exercise each relocated rule — flagged-and-dropped empty-id widgets (INV-DRV-02), recovered synthetic-lambda handlers (INV-DRV-01), re-keyed dialogs (INV-DRV-03), and an A′ union that differs from the widget-derived set — and SHALL fail if any of the four is exercised by **zero** apps. Equality over a corpus that never triggers a rule is not evidence for that rule; when a count comes back zero the rule's coverage moves to a synthetic fixture in the permanent Python suite, and that substitution is recorded.

#### Scenario: equivalence over the corpus
- **WHEN** the equivalence gate runs with `-Dmop.corpusDir=<workspace>/rvsec-dataset/static_analysis`
- **THEN** all 345 apps SHALL compare equal on every projection listed above
- **AND** any inequality SHALL fail the gate naming the app and the first differing projection
- **AND** the gate SHALL report a non-zero exercise count for each of the four relocated rules, failing if any is zero

#### Scenario: gate is a cutover artifact, not a permanent test
- **WHEN** the cutover commit lands
- **THEN** the repository SHALL contain neither the full-JSON parser nor the gate test
- **AND** the permanent suites (generator unit tests, jar fixture tests) SHALL cover every relocated invariant (INV-DRV-01..04)
