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
