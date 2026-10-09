# Delta: Static Analysis Entry Points (llm-coordinate-single-base)

## Purpose

The compact MOP artifact is the only static-analysis input the jar reads. Until this change it carried a yes/no flag per `(base activity, short id, event)` and nothing else about reachability. GATOR change gh120 (rv-android, archived `c9a43d98`) now writes, for every app method, its call-graph distance to each monitored target (`reachability[].methods[].targetDistances`, pairs `[i, d]` indexing the document's `distanceTargets`), and instrumenter change gh121 writes each widget's handler class on its accessibility node. The jar's MOP guidance (`mop-guidance`, design Part B) needs both on the wire: distances to rank widgets and activities, and a table from handler class to flags and distances to read the stamp.

This delta defines **format 2** of the artifact (design D15): the target count, per-widget and per-handler distance pairs (every target at `d ≤ 3`), per-activity distance pairs (the K = 3 nearest targets), and the handler table. Everything in format 1 stays, with the same meaning, except that the A′ source 3 of `mopActivitiesAugmented` leaves out an activity class's own `<init>`/`<clinit>` (item 4). INV-DRV-06 is amended to allow integer indices, integer distances and class names, and still no method signature.

The generator, `derive_mop_artifact.py`, is implemented in rv-android under its own Fast-Forward change `gh122` (design Part B, author's decisions of 2026-10-07; tasks group R). That change also keys the host cache on the format and amends rv-android's `aperv` spec through a delta, which is why its track is FF SDD rather than Quick Path (rv-android `docs/WORKFLOW.md:178`: Quick Path has no delta spec). **This delta is the contract** both sides implement; task 9.2 checks that the two specs describe the same format 2.

The rewrite also brings two statements of this mirror up to the rv-android generator as it stands: the `"complete"` sentinel is not a derivation precondition (rv-android INV-DRV-08, gh113, 2026-09-14), and on the gh120 cryptoapp fixture the Execute button is flagged through the exact join, not the D8 class recovery (rv-android INV-DRV-09 and `analysis` INV-ANA-77, gh120).

## Invariants

- **INV-DRV-06** (amended; the defining text lives in rv-android's `aperv` spec, which `gh122` amends the same way; this repository's main spec cites it without listing it, so this text is added to `## Invariants` at archive, task 9.2): The artifact SHALL contain no call-graph data — no `reachability` section, no method signature and no method name, no call edge, no raw `windows`/`transitions`/`listeners` — and no `*Target` key other than `hasTargetMethods` on receivers and services (rv-android `aperv` spec `:181-183`; the check is a case-sensitive substring test for `Target`, `tests/test_aperv_tool.py:1532`). Format 2 MAY carry integer target indices and integer distances (`targets`, `dist`, `activityDist`, the `dist` of a `handlers` record) and binary class names (the keys of `handlers`). A target index is joined to its signature only offline, through `source.digest` and the full JSON it names. The full JSON SHALL remain unmodified on the host.

## MODIFIED Requirements

### Requirement: Derived compact MOP artifact — projection contents

`derive_mop_artifact.derive(document)` SHALL produce a `formatVersion: 2` artifact containing exactly the projection the explorer consumes:

1. **Scalars**: `package`, `mainActivity` copied verbatim (they feed the T1.7 strict-match check and trigger `ComponentName` construction, INV-CT-04).
2. **Provenance**: `source.digest` (`sha256:<hex>` of the full-JSON bytes), `source.file` (basename), `source.generator` = `"aperv-derive/2"`.
3. **Widgets** (`widgets.<baseActivity>.<shortId>`): per-normalized-eventType MOP map `mop` with values `none|direct|transitive|both` (INV-DRV-01; keys pre-normalized — lowercase, `_`/`-` stripped), plus the consumed metadata fields `inputType`, `hint`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries`, each emitted only when non-empty, plus the distance map `dist` (item 9). A widget SHALL be emitted only when it is MOP-flagged OR carries at least one metadata field (an unflagged, metadata-less widget is unreachable through every production query). `id`, `type`, `text`, and raw `listeners` SHALL NOT be emitted.
4. **Activity sets**: `mopActivities` — derived from the **MOP-flagged widgets**, not from the widgets that survive into the emitted map: a flagged widget contributes its base activity even when an empty short id keeps the widget itself off the wire (INV-DRV-02), and the dialog merge promotes a flagged dialog's host (INV-DRV-03) — and `mopActivitiesAugmented` (A′ 3-source union: widget-derived ∪ `components.activities[].reachesTarget==true` ∪ `reachability[]` classes with `componentType=="activity"` and ≥ 1 reaching method other than the class's own `<init>` and `<clinit>`) — both always emitted, so the on-device `mopActivitySourceComponents` flag keeps selecting at run time. The constructor exclusion of source 3 has the reason of the `activityDist` one (item 9): every activity constructor is a gh120 boundary target (D12), so without it every activity would reach a target and enter the treatment census (author's decision, 2026-10-07). Distances do not change either set.
5. **OPTIONSMENU records**: `optionsMenus: [{activity, hasFlaggedWidget}]`, one per **distinct base activity** owning an `OPTIONSMENU`-type window, `activity` = base activity of the window name, `hasFlaggedWidget` = the OR, across that activity's menu windows, of "any widget parsed from the window is MOP-flagged" — tested before the empty-short-id drop, since an id-less menu item still makes the menu a gateway. (The gateway *set* is recomputed on-device from these records + WTG + the selected activity set, keeping INV-MOP-13 flag-sensitive.)
6. **WTG**: `wtg.<sourceBaseActivity> = [{widget, target}]` per INV-DRV-03; `widget` is the transition event's `widgetName`; `widgetClass` SHALL NOT be emitted (zero production readers).
7. **Components**: `activities[]` (`className`, `isMain`, `permission`, `reachesMop`, `deepLinkUri` per INV-DRV-07), `receivers[]`/`services[]` (adds `intentFilters` with `actions`+`categories` only, and `hasTargetMethods`), `providers[]` (adds `authorities`). `reachesMop` is the D7 rename of the wire `reachesTarget`. The gh60 D15 `data` block itself, `readPermission`/`writePermission`, `targetMethods` signature lists, and `exported` SHALL NOT be emitted — but the `data` block's one production use is preserved as the derived `deepLinkUri` string; the rest remains available host-side in the full JSON.
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
- **AND** `optionsMenus` SHALL contain the `MainActivity` record, and the `wtg` map SHALL carry the click edges from `MainActivity` to all three MOP sub-activities (rv-android `aperv` spec `:1309` still says "both", from format 1; `gh122` corrects it)
- **AND** `components.activities` SHALL have 4 entries, `providers` 1 entry with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`, every component `reachesMop==false`
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
- **AND** the artifact SHALL carry no `data` block, no scheme list, and no host or path list (INV-DRV-07)

#### Scenario: widget distance is the minimum over its handlers, cut to three
- **WHEN** a widget has two `click` listeners whose handler methods carry `targetDistances` `[[2,3],[4,1],[6,5]]` and `[[2,1],[7,2],[8,2],[9,4]]`
- **THEN** its `dist.click` SHALL be `[[2,1],[4,1],[7,2]]`: per target the minimum (`2→1`, `4→1`, `6→5`, `7→2`, `8→2`, `9→4`), then the three smallest by `d`, ties by `i`
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
