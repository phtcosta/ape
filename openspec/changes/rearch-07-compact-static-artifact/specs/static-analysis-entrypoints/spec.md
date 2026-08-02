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

- **INV-DRV-01** (ex INV-MOP-12/17/30): Widget MOP flags SHALL be derived per listener and per normalized `eventType`, OR-aggregated across a widget's listeners; a producer-supplied `handlerReachesTarget`/`handlerDirectlyReachesTarget` pair takes precedence over the local cross-reference when non-null; a handler with no exact `reachability[].methods[].signature` match that is a D8 synthetic-lambda wrapper (`X$$ExternalSyntheticLambdaN`) SHALL be recovered from `X`'s reaching `lambda$…` methods, and SHALL NOT be flagged when `X` has no reaching lambda method.
- **INV-DRV-02** (ex INV-MOP-19/20): On a `shortId` collision within a base activity, the emitted widget SHALL carry the strongest MOP flag (direct > transitive > unflagged), order-independently; widgets with an empty short id SHALL NOT be emitted, and the count of MOP-flagged widgets so dropped SHALL be recorded in `stats.droppedFlaggedNoId`.
- **INV-DRV-03** (ex INV-MOP-25, INV-WTG-04): DIALOG windows SHALL be merged into their host activity (first incoming transition wins; `mopRank` collision policy; move-not-copy; a flagged merge adds the host to the widget-derived activity set; the dialog class's own activity-set entry is retained); WTG edges SHALL be keyed by base **source** activity with base target activities, click events only, exact duplicates removed; orphan dialogs are counted in `stats.orphanDialogs`.
- **INV-DRV-04** (ex INV-MOP-31/32): All `stats` fields SHALL be pure counters over the derivation; their values SHALL NOT influence any emitted set, flag, or edge.
- **INV-DRV-05**: Derivation SHALL be deterministic at the byte level: identical full-JSON input bytes SHALL yield an identical artifact byte sequence (canonical serialization: UTF-8, lexicographically sorted object keys, `,`/`:` separators, deterministic array orders — source first-occurrence for edges and component lists, sorted for activity sets).
- **INV-DRV-06**: The artifact SHALL contain no `*Target` key and no call-graph data: no `reachability` section, no method signatures (`targetMethods` compacts to `hasTargetMethods`), no raw `windows`/`transitions`/`listeners`. The full JSON SHALL remain unmodified on the host, and no metric or analysis pipeline SHALL read the derived artifact.

## ADDED Requirements

### Requirement: Derived compact MOP artifact — projection contents

`derive_mop_artifact.derive(document)` SHALL produce a `formatVersion: 1` artifact containing exactly the projection the explorer consumes, per the consumption inventory in this change's `design.md`:

1. **Scalars**: `package`, `mainActivity` copied verbatim (they feed the T1.7 strict-match check and trigger `ComponentName` construction, INV-CT-04).
2. **Provenance**: `source.digest` (`sha256:<hex>` of the full-JSON bytes), `source.file` (basename), `source.generator` (generator id/version).
3. **Widgets** (`widgets.<baseActivity>.<shortId>`): per-normalized-eventType MOP map with values `none|direct|transitive|both` (INV-DRV-01; keys pre-normalized — lowercase, `_`/`-` stripped), plus the consumed metadata fields `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, each emitted only when non-empty. A widget SHALL be emitted only when it is MOP-flagged OR carries at least one metadata field (an unflagged, metadata-less widget is unreachable through every production query). `id`, `type`, `text`, and raw `listeners` SHALL NOT be emitted.
4. **Activity sets**: `mopActivities` (widget-derived, post-dialog-merge) and `mopActivitiesAugmented` (A′ 3-source union: widget-derived ∪ `components.activities[].reachesTarget==true` ∪ `reachability[]` classes with `componentType=="activity"` and ≥ 1 reaching method) — both always emitted, so the on-device `mopActivitySourceComponents` flag keeps selecting at run time.
5. **OPTIONSMENU records**: `optionsMenus: [{activity, hasFlaggedWidget}]`, one per `OPTIONSMENU`-type window, `activity` = base activity of the window name, `hasFlaggedWidget` = any widget in that menu window is MOP-flagged. (The gateway *set* is recomputed on-device from these records + WTG + the selected activity set, keeping INV-MOP-13 flag-sensitive.)
6. **WTG**: `wtg.<sourceBaseActivity> = [{widget, target}]` per INV-DRV-03; `widget` is the transition event's `widgetName`; `widgetClass` SHALL NOT be emitted (zero production readers).
7. **Components**: `activities[]` (`className`, `isMain`, `exported`, `permission`, `reachesMop`), `receivers[]`/`services[]` (adds `intentFilters` with `actions`+`categories` only, and `hasTargetMethods`), `providers[]` (adds `authorities`). `reachesMop` is the D7 rename of the wire `reachesTarget`. The D15 `data` block, `readPermission`/`writePermission`, and `targetMethods` signature lists SHALL NOT be emitted — they remain available host-side in the full JSON.
8. **Stats**: `windows`, `widgetsTotal`, `flagged`, `droppedFlaggedNoId`, `orphanDialogs`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `wtgEdges`, `dedupedTransitions` (INV-DRV-04).

Derivation preconditions: `document["complete"] == true` and a non-null `package`; otherwise `DerivationError` (a truncated analysis SHALL never yield an artifact — the completeness sentinel becomes a generation precondition instead of a device-side check).

#### Scenario: cryptoapp derivation matches the known ground truth
- **WHEN** `derive` runs on `cryptoapp.apk.gh60-fresh.json`
- **THEN** the artifact SHALL flag exactly `buttonGenerateHash` and `btn_cipher_encrypt` (both `transitive` on their click events; no `direct` flag anywhere)
- **AND** `mopActivities` SHALL equal `{MessageDigestActivity, CipherActivity}` (base names)
- **AND** `optionsMenus` SHALL contain the `MainActivity` record, and the `wtg` map SHALL carry the click edges from `MainActivity` to both MOP sub-activities
- **AND** `components.activities` SHALL have 4 entries, `providers` 1 entry with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`, every component `reachesMop==false`
- **AND** `stats.windows==5`, `stats.flagged==2`

#### Scenario: incomplete full JSON refuses to derive
- **WHEN** `derive` runs on a document with `complete` absent or `false`
- **THEN** `DerivationError` SHALL be raised
- **AND** no artifact file SHALL be written

#### Scenario: no Target vocabulary and no call graph on the wire
- **WHEN** any artifact is generated
- **THEN** it SHALL contain no key matching `*Target*` and no `reachability`/`windows`/`transitions`/`listeners` section (INV-DRV-06)

#### Scenario: unflagged metadata-less widgets are projected away
- **WHEN** the full JSON contains a widget with no MOP-reaching listener and no `inputType`/`hint`/`prompt`/`spinnerMode`/`contentDescription`/`tooltipText`/`entries`
- **THEN** the artifact SHALL NOT contain that widget
- **AND** an unflagged widget with a non-empty `hint` SHALL be emitted (typed input reads it)

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

Before the jar's full-JSON parser is deleted, a one-shot equivalence gate SHALL demonstrate, over the real-JSON corpus (`data/instrumented_apks/*.apk.json`, 134 apps, plus the cryptoapp fixture), that for every app the projections served by the **old** parser on the full JSON and by the **new** parser on the derived artifact are identical: widget flag maps (per-event entries and aggregates), widget metadata fields, both MOP-activity sets (flag off and on), OPTIONSMENU-gateway sets (flag off and on), WTG views, component trigger tuples and provider tuples, and `package`/`mainActivity`. The gate is the merge condition for the cutover commit; the gate code (and the old parser it needs as oracle) is deleted immediately after it passes (P3 — the permanent protection is the per-rule generator unit tests plus the jar fixture tests).

#### Scenario: equivalence over the corpus
- **WHEN** the equivalence gate runs with `-Dmop.corpusDir=data/instrumented_apks`
- **THEN** all 134 apps SHALL compare equal on every projection listed above
- **AND** any inequality SHALL fail the gate naming the app and the first differing projection

#### Scenario: gate is a cutover artifact, not a permanent test
- **WHEN** the cutover commit lands
- **THEN** the repository SHALL contain neither the full-JSON parser nor the gate test
- **AND** the permanent suites (generator unit tests, jar fixture tests) SHALL cover every relocated invariant (INV-DRV-01..04)
