## Context

**Bug surfaced empirically (2026-05-29).** `MopData.parseMethod` switches on the legacy gh57 keys `"directlyReachesMop"` / `"reachesMop"`; gh60-C1f renamed both to `"directlyReachesTarget"` / `"reachesTarget"`. Default switch case is `reader.skipValue()`. Against any post-gh60 JSON, `bySignature` stays empty ⇒ every widget cross-reference fails ⇒ `Widget.directMop` / `transitiveMop` stay false ⇒ `activityHasMop` returns false everywhere ⇒ `MopScorer.score(...)` returns 0 for every action ⇒ MOP-guidance pass at `StatefulAgent:1221-1243` applies zero boost. **Effect: APE-RV's SATA-MOP mode has been functionally equivalent to bare APE on any corpus regenerated after gh60-C1f merged.** `parseComponentArray` carries the same regression at the component path. This is the load-bearing reason to ship before the 380-APK re-run.

Two upstream changes in `rvsec/rv-android` reshape the static-analysis JSON consumed by `MopData.load`:

- **`gh57-static-analysis-overhaul`** — four widget XML attributes (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`), `OPTIONSMENU` windows populated as separate top-level `Window` entries (`type="OPTIONSMENU"`, `name="<activity>#OptionsMenu"`, flat MenuItem widgets as siblings — NO nested `items[]`), Spinner `entries[]` from `<string-array>`/`<integer-array>`/`<array>` XML (G6.4 pulled into gh60), decoupled `windows[]` from WTG completion.
- **`gh60-targets-core`** — BREAKING wire-level rename MOP→Target, top-level sentinel `"complete": true`, top-level `package` / `mainActivity`, `components` dict keyed by `{activities, receivers, services, providers}` (the section name IS the type — no per-element `componentType` field), per-component `reachesTarget` / `targetMethods[]` / `exported` / `isMain`, structured `intentFilters[]` with `actions[]` + `categories[]`, per-method `reachable` / `name` / `signature` / `reachesTarget` / `directlyReachesTarget`.

**Empirical baseline (cryptoapp gh60 fixtures, verified 2026-05-29):** top-level keys `{package, mainActivity, reachability, windows, transitions, components, complete}`; `complete=true`; 16 reachability classes (55 reachable / 32 reachesTarget / 21 directlyReachesTarget); 5 windows (4 ACTIVITY + 1 OPTIONSMENU `MainActivity#OptionsMenu` with 3 flat MenuItems); 51 widgets; 35 transitions; components `{activities:4, receivers:0, services:0, providers:1}`, all components `reachesTarget=false`. **cryptoapp is menu-driven and the MOP signal is a navigation gateway, not a menu-widget property.** The only two MOP-reaching widgets are `buttonGenerateHash` (in `MessageDigestActivity`) and `btn_cipher_encrypt` (in `CipherActivity`), both `transitiveMop` (`reachesTarget=true, directlyReachesTarget=false`) and both in ACTIVITY windows. **No** widget has `directMop` (no listener handler is itself `directlyReachesTarget` — the 21 directly-reaching methods are leaf JCA helpers, never wired as listeners). The OPTIONSMENU items (`menu_item_message_digest`, `menu_item_cipher`) do **not** reach target within their `onMenuItemClick` handlers; they carry WTG click-transitions that **navigate** to those MOP sub-activities. This gateway shape drives the T1.2 design (D13). Widget JSON shape has NO `directMop`/`transitiveMop`/`items` fields — these are local derivations. Listener JSON shape is `{eventType, handler}` only — `handlerReachesTarget` belongs to gh60 C3 (deferred).

The current consumer-side use of MopData is shallow even when the parser worked: `MopScorer` reads three booleans; `triggerMopComponent` uses only `actions.get(0)` and excludes activities + providers; `ApeFuzzer` is `inputType`-blind; `Listener.eventType` is dropped; Spinner `entries[]` are dropped; OPTIONSMENU windows are not exploited. Seven Tier-1 effective-use gaps were identified in the 2026-05-26 audit, all folded into this change.

This change is the consumer-side counterpart to gh57+gh60 — it is the bug fix AND the unblocker for the final 380-APK ground-truth re-run.

## Architecture

```
analysis.json (post-gh57+gh60)
   │
   │  package, mainActivity, complete:true (last)
   │  reachability[], windows[] (incl. OPTIONSMENU populated), transitions[], components{}
   │
   ▼
MopData (post-this-change) ── COMPLETE TYPED MODEL
   │
   ├── packageName / mainActivity / isComplete
   ├── reachability: List<ReachabilityClass>     ── className, componentType, isMain, methods
   ├── windows: List<Window>                      ── id, type, name, widgets
   ├── windowsById: Map<Integer, Window>          ── O(1) transition resolution
   ├── widgetData: Map<activity, Map<shortId, Widget>>
   ├── activitiesWithMopOptionsMenu: Set<String>  ── PRECOMPUTED — drives T1.2
   ├── transitions: List<Transition>
   ├── components (richer ComponentInfo)
   └── wtgTransitions (convenience, click-only) — preserved
                │
   ┌────────────┴───────────────────────────────────────────┐
   ▼                       ▼                                ▼
MopScorer (extended)    StatefulAgent (extended)          ApePromptBuilder (extended)
   │                       │                                │
   ├── score(act, id,      ├── triggerMopComponent          ├── Widget metadata in
   │   data, eventType)    │      (T1.4 + T1.5 rewrite)     │      widget descriptions
   │      (T1.6)           │   build (component × filter ×  │   (T1.1)
   ├── scoreWtg            │   action) tuples; round-robin  │   prompt/spinnerMode/
   │      (unchanged)      │   + provider content-query     │   contentDescription/
   ├── scoreOpenMenu       │                                │   tooltipText/entries/
   │      (T1.2 new)       ├── action-priority pass:        │   inputType/hint
   ├── stateMopDensity     │   - MopScorer.score(eventType) │
   │      (unchanged)      │   - MopScorer.scoreWtg         │
   └── eventTypeOf         │   - MopScorer.scoreOpenMenu    │
       (T1.6 helper)       │     applied to MODEL_MENU      │
                           │                                │
                           └── input injection path:
                                MopData.Widget.inputType →
                                ApeFuzzer.generateInputForType (T1.3)
```

## POJO Graph

All POJOs nested static under `MopData`. Public mutable fields (matches existing project style). Nullable strings default `null`; primitive ints default `-1`; lists default empty `ArrayList`. Eventually-immutable after `MopData.load` returns.

```
MopData
├── String packageName
├── String mainActivity
├── List<ReachabilityClass> reachability
├── List<Window> windows
├── Map<Integer, Window> windowsById
├── Map<String, Map<String, Widget>> widgetData
├── Set<String> mopActivities
├── Set<String> activitiesWithMopOptionsMenu        // T1.2
├── Map<String, List<WtgTransition>> wtgTransitions // convenience, click-only
├── List<Transition> transitions
└── List<ComponentInfo.{Activity,Receiver,Service,Provider}Info>

MopData.Window           { int id; String type; String name; List<Widget> widgets; }

MopData.Widget {
    // Fields READ from JSON:
    int id;
    String idName;
    String type;            // android.widget.Button etc.
    String text;
    String hint;
    String inputType;       // T1.3 input
    List<String> entries;   // Spinner items (T1.1 in LLM, foundation for T2.1)
    String prompt;
    String spinnerMode;
    String contentDescription;
    String tooltipText;
    List<Listener> listeners;
    // Fields DERIVED locally during parseWindow's cross-reference pass
    // (NOT emitted by gh60 — would be by gh60-C3 if/when adopted):
    boolean directMop;                                 // aggregate over all eventTypes
    boolean transitiveMop;
    Map<String, Boolean> directMopByEventType;         // T1.6 per-event-type
    Map<String, Boolean> transitiveMopByEventType;
    boolean isDirectMop(String eventType);     // per-eventType when present, aggregate otherwise
    boolean isTransitiveMop(String eventType);
    // NOTE: No `items[]` field — gh60 does not emit nested submenu items.
    // OPTIONSMENU windows carry flat MenuItem widgets as siblings (see D3).
}

MopData.Listener         { String eventType; String handler;
                           // gh60-C3 forward compat — current gh60 producer does NOT emit;
                           // both null on every listener until C3 lands.
                           Boolean handlerReachesTarget;
                           Boolean handlerDirectlyReachesTarget; }

MopData.Transition       { int sourceId; int targetId; List<TransitionEvent> events; }
MopData.TransitionEvent  { String type; String handler; int widgetId;
                           String widgetClass; String widgetName; }

MopData.ReachabilityClass  { String className; String componentType; boolean isMain;
                             List<ReachabilityMethod> methods; }
MopData.ReachabilityMethod { String name; String signature; boolean reachable;
                             boolean reachesTarget; boolean directlyReachesTarget; }

MopData.WtgTransition (existing, preserved)

ComponentInfo (extended)
├── String className
├── String componentType                          // derived from parent dict key
│                                                  // in JSON: "activity"/"receiver"/"service"/"provider"
├── boolean isMain
├── boolean exported
├── List<IntentFilter> intentFilters              // structured, with categories[]
├── boolean reachesTarget                         // read from JSON
├── List<String> targetMethods                    // gh60
└── getActions() / getCategories()                // flat-union convenience
    ProviderInfo { String authorities; }

ComponentInfo.IntentFilter { List<String> actions; List<String> categories; }
```

## Tier-1 Effective Use — Implementation Mapping

| Item | Where | Mechanism |
|------|-------|-----------|
| **T1.1** Widget metadata + entries in LLM | `ApePromptBuilder.formatActionLine` / `buildRvsmartV13UserText` / `buildExplorationContext` | append `prompt="..." spinnerMode="..." contentDescription="..." tooltipText="..." entries=[...] inputType="..." hint="..."` to widget description, 80-char cap, 10-entry cap, null-omitted |
| **T1.2** OPTIONSMENU boost | precomputed `activitiesWithMopOptionsMenu` (Pass 2 byproduct) + `MopScorer.scoreOpenMenu(activity, data)` + hook in `StatefulAgent` action-priority pass on the `MODEL_MENU` action | weight `Config.mopWeightOpenMenu` (default 250 — between `mopWeightWtg=200` and `mopWeightTransitive=300`) |
| **T1.3** Typed fuzzing | `ApeFuzzer.generateInputForType(inputType, hint, rnd)` + hook in input-injection path reading `MopData.Widget.inputType` + `Config.fuzzInputTyped` | per-type generators (password/number/phone/email/uri/date) + hint-based fallback heuristics + legacy fallback |
| **T1.4** Tuple-based component triggering | `StatefulAgent.triggerMopComponent` rewrite: build `List<TriggerTuple>` once, round-robin index advances through `(component × filter × action)` tuples. Skip `reachesTarget=false` / `exported=false`. | preserves existing `SystemBroadcastCatalog` extra application |
| **T1.5** Provider triggering | new `ProviderTuple { provider; operation }` in the same round-robin; `AndroidDevice.runShell("content " + op + " --uri content://" + authorities)` | operations: query / insert / update sub-cycle |
| **T1.6** Event-type-aware scoring | `MopData.Widget.directMopByEventType` populated during cross-ref pass; `MopScorer.score(act, id, data, eventType)` overload; `MopScorer.eventTypeOf(ModelAction)` mapper | match-any fallback when listener.eventType missing or candidate eventType null (backward compat) |
| **T1.7** Package sanity check | `MopData.load(path, expectedPackage, expectedMainActivity)` 3-arg overload + `Config.mopStrictPackageMatch` | default warn-only; strict mode rejects |

## Mapping: Spec → Implementation → Test

| Requirement / Scenario | Implementation | Test |
|------------------------|---------------|------|
| Real fixture loads every typed field | `MopData.load` + all POJOs populated | `testFullFixtureLoadsAllFields`, `testSecondFixtureLoadsAllFields` |
| Post-gh60 target keys | `parseMethod` / `parseComponentArray` cases renamed | `testJsonKeysRenamedToTarget`, `testComponentReachesTargetReadFromJson` |
| Sentinel required | Pass 5 | `testCompleteSentinelMissing/False/True_*` |
| Top-level package/mainActivity | Pass 1 | `testTopLevelPackageAndMainActivity` |
| Sanity check default warn / strict reject | 3-arg `load` + `Config.mopStrictPackageMatch` | `testPackageMismatchWarnsByDefault`, `testPackageMismatchRejectsWhenStrict` |
| ReachabilityClass + method full fields | Pass 1 | `testReachabilityClassFieldsCaptured` |
| Widget full fields | Pass 2 cases | `testWidgetCoreFieldsCaptured`, `testParsesFourNewWidgetAttributes`, `testSpinnerEntriesCaptured` |
| Listener full fields | `parseListener` | `testListenerFieldsCaptured`, `testListenerHandlerReachesTarget*` |
| Bug-fix regression (legacy keys → empty bySignature) | `parseMethod` switch labels renamed | `testWidgetTransitiveMopDerivedFromGh60Targets` |
| Transition / Event full fields | Pass 3 | `testTransitionEventFieldsCaptured`, `testTransitionImplicitEventsPreserved` |
| Component full fields incl. categories | Pass 4 | `testComponentFieldsCaptured`, `testIntentFilterPreservesCategoriesAndActions` |
| Precomputed OPTIONSMENU set | Pass 2 byproduct | `testActivitiesWithMopOptionsMenuPrecomputed` |
| Per-eventType reachability maps | Cross-ref in Pass 2 | `testWidgetEventTypeMapsBuilt` |
| MopScorer.scoreOpenMenu (T1.2) | new method | `testScoreOpenMenuBoosts*` |
| MopScorer eventType-aware (T1.6) | 4-arg overload | `testScoreEventTypeAwareMatchesClick`, `testScoreEventTypeNullFallsBackToAggregate`, `testEventTypeOfMapsActionTypes` |
| ApeFuzzer typed input (T1.3) | new static helper | `testPasswordInputType`, `testNumberInputType`, `testPhoneInputType`, `testEmailInputType`, `testHintBasedFallback`, `testUnknownInputTypeFallsBack` |
| Component triggering rewrite (T1.4+T1.5) | tuple-based round-robin + provider branch | `testTriggerSkipsNonReachableComponents`, `testTriggerSkipsNonExportedActivities`, `testTriggerRoundRobinsAllIntentFilterActions`, `testTriggerProviderRoundRobinsOperations`, `testTriggerLogsContainExpectedFields` |
| ApePromptBuilder metadata (T1.1) | extended widget description | 6 test methods |
| INV-MOP-07..14 | various | covered by tests above |

## Goals / Non-Goals

**Goals:**
- Complete typed model for the post-gh60 JSON.
- Seven Tier-1 effective-use items wired end-to-end.
- Preserve `MopScorer.score(act, id, data)` 3-arg semantics (backward compat via 4-arg with `eventType=null` ⇒ match-any).
- Defense-in-depth: `Config.activityTriggerEnabled=false` default keeps activity triggering off until calibrated (gh11 evidence: -45pp on sandwichroulette).
- `Config.mopStrictPackageMatch=false` default — debugging aid, opt-in CI gate.

**Non-Goals:**
- Backward compat with pre-gh57 or pre-gh60 JSONs (P3).
- Producer-side `--targets-file` consumer (non-MOP target sources).
- Modifying `MopScorer` scoring logic beyond adding new methods. Existing `score()` semantics preserved exactly when callers don't pass eventType.
- Renaming top-level `MopData` / `MopScorer` / `Config.mopWeight*`. Wire contract follows gh60; Java internals keep the JavaMOP-targets semantic.
- Tier-2 items (Spinner action-per-entry, WTG multi-hop pathfinding, `reachable=false` de-prioritization, pre-gh60-C3 transition-handler cross-ref). Listed in proposal "Out of Scope" with rationale.

## Decisions

### D1 — All POJOs nested under `MopData`

Matches existing pattern (`MopData.WtgTransition`, the renamed-soon `WidgetMopFlags`). Avoids polluting `ape.utils` with eight new top-level classes. File size bounded to ~900 LOC.

### D2 — Mutable public fields

Matches existing project style. Avoids ~30 accessors. Java 11 release — `record` is unavailable. Constructor + accessors would dwarf the actual logic.

### D3 — OPTIONSMENU is a separate top-level `Window`, not nested `items[]`

Empirical verification of the gh60 emitter and the cryptoapp smoke JSON confirmed: OPTIONSMENU appears as a `Window` with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` carrying flat MenuItem widgets as siblings in its `widgets[]` array. There is no recursive `items[]` field in the emitted widget JSON. The earlier mental model in this design (items[] recursion, depth cap 8, parent.items + flatten-into-window) was based on a stale reading of gh57's intent and did not match what gh60 actually ships. Parser does NOT implement recursive descent on widget; `MopData.Widget` has no `items` field.

### D4 — OPTIONSMENU detection via window-name suffix match (gateway-inclusive)

The precomputed `activitiesWithMopOptionsMenu` set is populated after windows, widgets, and WTG transitions are built: for each Window where `type=="OPTIONSMENU"`, strip the `"#OptionsMenu"` suffix from `name` to recover the owning activity class, then qualify that activity when the window contains at least one widget that **either** has a derived `directMop || transitiveMop` flag **or** has a WTG click-transition to a `hasMop` activity (the gateway case). The gateway clause is load-bearing: in menu-driven apps (cryptoapp being the primary corpus) the menu items navigate to MOP-bearing sub-activities rather than reaching target within their own handler, so a widget-MOP-only check would never fire (see Context + D13). Producer guarantees `idName` uniqueness within a window (gh57 D7), so the flat widget map keyed by `(activity, shortId)` continues to work for `MopScorer.getWidget` lookups when the GUI is showing the menu.

### D5 — Sentinel `complete: true` mandatory

Per gh60 ADR-6, sentinel is the truncation-detection contract. With the `org.json` DOM model (D21) the check is a single `root.optBoolean("complete", false)` on the parsed root — position-independent: a sentinel appearing anywhere in the root object is recognized (§15.24).

### D6 — `WidgetMopFlags` renamed to `MopData.Widget`

The old name scoped to "MOP flags" — semantically misleading once the POJO carries the full widget shape (14+ fields). One-shot mechanical rename validated by `mvn compile` + zero-hit grep.

### D7 — Naming boundary: `Target` on the wire, `MOP` inside aperv

This is the single source of truth for the MOP-vs-Target naming, and it is deliberate — not a leftover. The two words name the **same concept from two vantage points**:

| Layer | Vocabulary | Rationale |
|------|-----------|-----------|
| **JSON wire** (rv-android gh60 producer) | **Target** — `reachesTarget`, `directlyReachesTarget`, `targetMethods` | The static analyzer was generalized so its "targets" could be any method set, not only JavaMOP ops. The producer therefore speaks the neutral word. |
| **aperv Java model** (this repo) | **MOP** — `MopData`, `MopScorer`, `directMop`/`transitiveMop`, `activityHasMop`, `Config.mopWeight*`, `ape.mopWeight*` | aperv is **exclusively a JavaMOP consumer** — the only targets it cares about *are* MOP monitored operations. |

**The one rule:** `*Target` appears **only** at the point where JSON is read (the reachability-method and component parsing in `MopData`). Everywhere else inside aperv the concept is `*Mop`. A JSON method with `reachesTarget=true` is stored as a widget with `transitiveMop=true`; `directlyReachesTarget=true` maps to `directMop=true`.

Renaming the Java side to `Target` would churn `MopScorer`, `ApePromptBuilder`, `LlmRouter`, two agents, five test classes, `Config` (4+ `mopWeight*` properties — user-facing `ape.properties` surface), and `ape.properties` itself — ~80 occurrences for zero behavioral gain. gh60's producer-side proposal preserves the `--mop-dir` CLI for exactly this reason. The `MopData` class javadoc carries this same table so the boundary is documented at the code site, not only here.

### D8 — Listener `handlerReachesTarget` prefers producer over cross-ref (defensive forward-compat)

The current gh60 producer does NOT emit `handlerReachesTarget` / `handlerDirectlyReachesTarget` per listener — that belongs to the **C3** follow-up (`gh<N+2>-agent-enrichment`). The fields are declared on `MopData.Listener` as nullable `Boolean` so the parser reads them when they appear without a second pass. While both are null (current state), the local handler cross-reference against `bySignature` is the single source of truth. Once C3 lands and producer emits non-null values, the producer value takes precedence over the local lookup (which may diverge if the producer's call-graph view is more accurate). Reading defensively NOW means no parser change when C3 ships.

### D9 — `WtgTransition` convenience view preserved alongside raw `Transition`

Existing consumers depend on click-only `getWtgTransitions(activity)`. The new typed model exposes implicit events for future consumers. Zero behavior change for current callers.

### D10 — `ComponentInfo` keeps top-level identity, expands its surface

`ComponentInfo` is referenced by existing call sites. New `intentFilters: List<IntentFilter>` replaces the lossy `actions: List<String>` flat field. `getActions()` / `getCategories()` convenience preserves flat-union read patterns. Two views, one source.

### D11 — No `schemaVersion` consultation (P3)

Only one schema. Unknown root fields are simply not read from the `org.json` DOM (D21) — no version branching.

### D12 — Unknown fields ignored (forward compat)

Every known gh60 field is read explicitly into a typed POJO. With the `org.json` DOM model (D21), keys that are not read are simply ignored — forward compatibility with future producer revisions is automatic, with no `default: skipValue()` branch. The completeness mandate (T1.x "complete parser") is satisfied by reading every documented field; forward compat is the orthogonal defensive guarantee.

### D13 — OPTIONSMENU boost via precomputed activity set (gateway-aware)

**Problem this solves:** cryptoapp (and menu-driven apps generally) reach crypto by *opening the options menu* and tapping an item that *navigates* to a MOP-bearing sub-activity. The existing `scoreWtg` already boosts those menu-item clicks — but only once the menu is open. The action that opens the menu (`MODEL_MENU`) is boosted by nothing, so the agent under-explores the gateway. T1.2 supplies the missing boost on `MODEL_MENU` itself. This is the "single biggest cryptoapp win" the proposal cites.

**Choice:** After windows + widgets + WTG transitions are built, precompute `Set<String> activitiesWithMopOptionsMenu` by iterating windows with `type="OPTIONSMENU"`, parsing the activity prefix from `name` (split on `"#OptionsMenu"`), and qualifying the activity when any widget in that window **either** has a derived `directMop || transitiveMop` flag **or** carries a WTG click-transition whose target activity is `hasMop`. `MopScorer.scoreOpenMenu(activity, data)` is then an O(1) set lookup.

**Why gateway-inclusive:** an empirical check of both pinned cryptoapp fixtures showed **zero** OPTIONSMENU widgets reach target within their own handler — the menu items' `onMenuItemClick` handlers navigate (`startActivity`), they don't compute. A widget-MOP-only definition would leave `activitiesWithMopOptionsMenu` empty on the primary corpus, defeating T1.2. The gateway clause makes `MainActivity` qualify because `menu_item_message_digest` → `MessageDigestActivity` (hasMop) and `menu_item_cipher` → `CipherActivity` (hasMop).

**Why precompute:** the naive alternative (scan all windows on every action-priority pass) is O(W × widgets) per state visit. Precomputation amortizes the cost to once per load.

**Weight (`Config.mopWeightOpenMenu`):** default 250 — between `mopWeightWtg=200` (1-hop WTG to MOP activity) and `mopWeightTransitive=300` (transitive MOP at the widget itself). Calibration knob; can be tuned via `ape.properties`.

**Where applied:** in `StatefulAgent`'s existing MOP-guidance pass, AFTER the widget-level loop, scan for the `MODEL_MENU` action; apply the boost.

### D14 — Type-aware fuzzing scope: EditText input only

**Choice:** `ApeFuzzer.generateInputForType` covers text-injection patterns (`textPassword`, `number`, `phone`, `textEmailAddress`, `textUri`, `date`/`time`). Drag, scroll, click coordinates remain unchanged.

**Why:** The bulk of the MOP-coverage win comes from EditText input — cipher apps demand correct-shape password/key text to reach MOP-checked code paths. Drag/scroll heuristics are orthogonal to static-analysis signal.

**Gating:** `Config.fuzzInputTyped` (default `true`). When `false`, behaves exactly as today — safe rollback knob.

**Hint-based fallback:** when `inputType` is empty (apps that don't declare it), heuristics on `hint` ("email" substring ⇒ email gen, "senha"/"password" ⇒ password gen, all-digits ⇒ number gen). Final fallback ⇒ legacy generator.

### D15 — Component triggering generalizes to (component × filter × action) tuples; provider branch added

**Choice:** Build `List<TriggerTuple>` once at session start (or on first call). Tuple cursor advances modulo total list size. Provider branch sub-cycles over `query`/`insert`/`update` operations.

**Why:** Today's `actions.get(0)` misses N-1 actions per multi-action filter. Apps with `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` receivers only ever get the first triggered. Per-tuple round-robin guarantees eventual coverage.

**Activity branch:** gated by `Config.activityTriggerEnabled` (default `false`). Evidence from gh11 ("sandwichroulette -45pp") flagged activity triggering as a risk; explicit opt-in until per-app calibration confirms safety.

**Categories:** `addCategory()` for each category in the filter — required for some implicit intents (e.g. `LAUNCHER` category on `ACTION_MAIN`).

**Provider invocation:** `AndroidDevice.runShell("content " + op + " --uri content://" + authorities)`. Insert/update with minimal `--bind`. Non-zero exit ⇒ WARN with stderr.

### D16 — Event-type-aware scoring with match-any fallback

**Choice:** `MopScorer.score(act, id, data, eventType)` overload. When `eventType` non-null AND `widget.directMopByEventType.containsKey(eventType)`, use the per-event-type flag. Otherwise fall back to aggregate `widget.directMop` boolean (match-any).

**Why:** A widget with `click` listener reaching MOP and `longClick` listener NOT reaching MOP should boost the click action but NOT the long-click action. Today both get equal boost (false positive). Match-any fallback ensures non-regression on JSONs without `eventType` and on `ModelAction` types the mapper doesn't know.

**`eventTypeOf(ModelAction)` mapper:** `MODEL_CLICK→"click"`, `MODEL_LONG_CLICK→"longClick"`, Spinner-bearing input action→`"itemSelected"`, `MODEL_SCROLL→"scroll"`, else `null` (triggers match-any).

**LLM markers (T1.1):** in v1, the `[DM]` / `[M]` markers remain event-type-agnostic. If LLM telemetry shows confusion (LLM picks a long-click on a widget marked `[DM]` whose long-click listener doesn't actually reach MOP), revisit to emit `[DM:click]` etc.

### D17 — Package / mainActivity sanity check is opt-in strict, default warn

**Choice:** `MopData.load(path, expectedPackage, expectedMainActivity)` three-arg overload. Default `Config.mopStrictPackageMatch=false` ⇒ warn-only. Strict mode opt-in for CI.

**Why:** Running against a stale fixture (wrong APK) is a debugging hazard. Warn-only ensures dev workflow keeps working when the fixture is intentionally generic. Strict mode is the CI / production gate.

**Caller:** `StatefulAgent` constructor passes the runtime package name (the value `MonkeySourceApe` was started with via `-p <package>`) when invoking `MopData.load`. If runtime package unavailable, passes `null` ⇒ warn-only regardless of config.

### D18 — Widget MOP flags are DERIVED, not READ

The gh60 producer does NOT emit `directMop`/`transitiveMop`/`directMopByEventType`/`transitiveMopByEventType` per widget. They are computed by the parser during Pass 2 (`parseWindow`) by:

1. Grouping `widget.listeners` by `eventType`.
2. For each `(eventType, handler)` pair, looking up `handler` in the `bySignature` index built during Pass 1 from `reachability[].methods[]`.
3. Setting `directMopByEventType[eventType]` and `transitiveMopByEventType[eventType]` via OR semantics across all listeners of that event type.
4. Aggregate booleans (`directMop`, `transitiveMop`) are the OR across all eventTypes.

If a future gh60-C3 starts emitting widget-level MOP flags, the producer value should take precedence (same pattern as D8 for listeners). Until then, derivation is the only path.

### D19 — Components dispatch on JSON dict key, not per-element field

The gh60 JSON encodes the type as the parent dict key: `components.activities[]`, `components.receivers[]`, `components.services[]`, `components.providers[]`. There is no per-element `componentType` field in the JSON. The parser builds the type as it dispatches into each sub-array; the `ComponentInfo.componentType` field in the Java POJO is a convenience for consumers, populated by the parser, not read from the wire.

### D20 — The bug-fix surface: legacy `*Mop` switch cases replaced atomically

The pre-fix parser at `MopData.parseMethod` (lines 198-227 of the pre-change source) reads:

```java
case "directlyReachesMop": directlyReachesMop = reader.nextBoolean(); break;
case "reachesMop":         reachesMop         = reader.nextBoolean(); break;
default:                   reader.skipValue();
```

Against a gh60 JSON, neither case matches (the keys are `directlyReachesTarget` / `reachesTarget`); `skipValue` runs silently. `bySignature` ends up empty. Same pattern in `parseComponentArray` (`case "reachesMop"`).

The fix reads the gh60 keys `directlyReachesTarget` / `reachesTarget` where the pre-fix parser read `directlyReachesMop` / `reachesMop`. Legacy keys are deleted (P3, no dual recognition). The regression test that pins this is `testWidgetTransitiveMopDerivedFromGh60Targets` in `MopDataTest` — it loads `cryptoapp.apk.gh60-fresh.json` and asserts the widget `buttonGenerateHash` (in `MessageDigestActivity`) has `transitiveMop=true` (its `click` listener handler cross-references into a `reachesTarget=true` method). cryptoapp's reachability is transitive — every listener handler calls JCA helpers indirectly, so no widget is `directMop`; `directMop` derivation is pinned by the synthetic per-event-type tests (§15.20). On the pre-fix parser the assertion fails (`bySignature` empty → `transitiveMop=false` → score 0); on the post-fix parser it passes. The test is the contract that "SATA-MOP is not silently bare APE."

### D21 — Parser uses `org.json` DOM, not `android.util.JsonReader`

The bug-fix regression gate (§15.2 / §21.6) is the central contract of this change: `testWidgetTransitiveMopDerivedFromGh60Targets` MUST load the real `cryptoapp.apk.gh60-fresh.json` fixture during `mvn test` and assert `transitiveMop==true` on `buttonGenerateHash`. The pre-change parser uses `android.util.JsonReader`, which is **excluded from the surefire test classpath** (`pom.xml` excludes `com.android:dalvik-stub` and `com.android:framework-full-debug`); the existing `MopDataTest` works around this with the `forTest(...)` factory and never parses real JSON. Any test calling `MopData.load(realFixture)` under `JsonReader` fails with `NoClassDefFoundError` — the gate would never execute.

`org.json` (`JSONObject` / `JSONArray` / `JSONTokener`) is available in test scope via the `org.json:json` dependency AND bundled in the Android framework at production runtime. It is already the repo's JVM-testable JSON path (`ToolCallParser`, `SglangClient` import it in `src/main`). Therefore `MopData.load` is rewritten to parse the file once into a `JSONObject` root and navigate the in-memory tree. Consequences:

- The five logical "passes" (reachability, windows/widgets, transitions, components, sentinel) become navigation phases over one parsed root rather than five file reopens. Pass ordering matters only for the reachability→widget cross-reference: `bySignature` must be built before widget derivation.
- Forward compat for unknown fields is automatic — unread keys are simply ignored; there is no `default: skipValue()` branch (D12 updated).
- The sentinel check is `root.optBoolean("complete", false)` on the root object — position-independent, so a sentinel appearing anywhere (§15.24) is recognized (D5 updated).
- Malformed JSON surfaces as `JSONException` (caught → `null` + WARN) instead of `IOException`.

Deviation from the design's original streaming model, accepted at implementation kickoff (2026-05-29): without it the change's defining test cannot run. The whole-file DOM costs more memory than streaming, but the fixture is ~64 KB and sweep JSONs are bounded — negligible against the existing all-GUITrees-in-memory footprint.

## API Design

### `MopData.load` (extended)

```java
public static MopData load(String path) {
    return load(path, null, null);
}

public static MopData load(String path,
                           String expectedPackage,
                           String expectedMainActivity);
```

Returns `null` when: null path / missing file / malformed JSON / sentinel absent or false / strict-mode package mismatch.

### `MopData.Widget`

```java
public static class Widget {
    public int id, /* -1 when absent */;
    public String idName, type, text, hint, inputType;
    public List<String> entries;
    public String prompt, spinnerMode, contentDescription, tooltipText;
    public List<Listener> listeners;
    public boolean directMop, transitiveMop;
    public Map<String, Boolean> directMopByEventType, transitiveMopByEventType;

    public boolean isDirectMop(String eventType) {
        if (eventType != null && directMopByEventType.containsKey(eventType)) {
            return directMopByEventType.get(eventType);
        }
        return directMop;  // match-any fallback
    }
    public boolean isTransitiveMop(String eventType) { /* symmetric */ }
}
```

### `MopScorer` (extended)

```java
public static int score(String activity, String shortId, MopData data) {
    return score(activity, shortId, data, null);  // delegate, match-any
}

public static int score(String activity, String shortId,
                        MopData data, String candidateEventType);

public static int scoreOpenMenu(String activity, MopData data);  // new (T1.2)

public static String eventTypeOf(ModelAction action);  // new (T1.6 mapper)

// unchanged: scoreWtg, stateMopDensity
```

### `ApeFuzzer` (extended)

```java
public static String generateInputForType(String inputType,
                                          String hint,
                                          java.util.Random rnd);
```

### `Config` (new flags)

```java
public static final int     mopWeightOpenMenu       = getInteger("ape.mopWeightOpenMenu", 250);
public static final boolean fuzzInputTyped          = getBoolean("ape.fuzzInputTyped", true);
public static final boolean mopStrictPackageMatch   = getBoolean("ape.mopStrictPackageMatch", false);
public static final boolean activityTriggerEnabled  = getBoolean("ape.activityTriggerEnabled", false);
```

## Data Flow

The file is parsed **once** into an `org.json.JSONObject` root (D21); the "passes" below are navigation phases over that in-memory tree, not file reopens. Only the reachability→widget ordering is load-bearing (`bySignature` before widget derivation).

```
MopData.load(path, expectedPackage?, expectedMainActivity?)
  │
  ├── parse file once → JSONObject root (org.json); JSONException ⇒ null + WARN
  │
  ├── Pass 1: read reachability[] + top-level package/mainActivity
  │     ├── packageName, mainActivity
  │     ├── reachability[] populated (full fields)
  │     └── bySignature index built
  │
  ├── Pass 2: parseWindows → parseWindow → parseWidget (flat — no recursion)
  │     ├── Window full fields (incl. type ∈ {ACTIVITY, OPTIONSMENU, …})
  │     ├── windowsById map built
  │     ├── Widget full fields (all 12 JSON-read fields)
  │     ├── parseListener: eventType, handler, handlerReachesTarget? (defensive nullable)
  │     ├── per-listener cross-ref against bySignature builds
  │     │   widget.{direct,transitive}MopByEventType maps (D18)
  │     └── widget.directMop = OR across all eventTypes (aggregate, match-any)
  │
  ├── Pass 3: parseTransitions → parseTransition → parseTransitionEvent
  │     ├── Transition full fields
  │     ├── TransitionEvent full fields (incl. handler, widgetId)
  │     └── wtgTransitions convenience view built (click-only)
  │
  ├── Pass 4: parseComponents
  │     ├── ComponentInfo full fields (componentType, isMain, exported,
  │     │                              intentFilters with categories,
  │     │                              reachesTarget read from JSON, targetMethods)
  │     └── ProviderInfo.authorities
  │
  ├── Pass 5: verifyCompleteSentinel — null/WARN if absent or false
  │
  ├── activitiesWithMopOptionsMenu precomputed (T1.2, D4/D13) — after WTG built
  │     for each Window w where w.type=="OPTIONSMENU":
  │         activity = w.name.substring(0, w.name.indexOf("#OptionsMenu"))
  │         if any widget in w.widgets has directMop || transitiveMop
  │            OR any WtgTransition from w.name targets a hasMop activity:  // gateway
  │             activitiesWithMopOptionsMenu.add(activity)
  │
  └── Sanity check (T1.7)
        if expectedPackage != null && != packageName: WARN
        if expectedMainActivity != null && != mainActivity: WARN
        if Config.mopStrictPackageMatch && mismatch: return null

Consumer side:
  StatefulAgent constructor:
    String pkg = (extracted from monkey CLI args / system state)
    _mopData = MopData.load(Config.mopDataPath, pkg, null)

  StatefulAgent action-priority pass (per state visit):
    for action in state.getActions():
      // existing widget MOP boost — now eventType-aware
      eventType = MopScorer.eventTypeOf(action)
      boost = MopScorer.score(activity, shortId, _mopData, eventType)
      action.setPriority(... + boost)

      // existing WTG boost — unchanged
      ...

      // NEW: open-menu boost (T1.2)
      if action.type == MODEL_MENU:
        boost = MopScorer.scoreOpenMenu(activity, _mopData)
        action.setPriority(... + boost)

  StatefulAgent.triggerMopComponent (T1.4 + T1.5):
    tupleList = buildTriggerTuples(_mopData)
    tuple = tupleList[componentTriggerIndex++ % tupleList.size]
    if (tuple is ProviderTuple): AndroidDevice.runShell("content " + op + " --uri ...")
    else: build Intent with action + categories, sendBroadcast / startService / startActivity

  MonkeySourceApe.generateInputText path (T1.3):
    if (Config.fuzzInputTyped && _mopData != null):
      Widget w = _mopData.getWidget(currentActivity, shortId)
      if (w != null):
        return ApeFuzzer.generateInputForType(w.inputType, w.hint, rnd)
    return legacy generator
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `JSONException` / `IOException` (malformed JSON or read error) | `org.json` parse | caught in `MopData.load` | `null` return + WARN |
| Sentinel absent / false | Pass 5 | explicit `null` return + WARN | caller treats as "no MOP data" |
| Package mismatch (default mode) | Pass after load | WARN log, return parsed data | runtime continues, dev notices in log |
| Package mismatch (strict mode) | Pass after load | `null` return + WARN | caller treats as "no MOP data" — CI fails the gate |
| `content query` non-zero exit | Provider trigger | WARN + stderr tail | round-robin advances; next tuple tried next cycle |
| Activity trigger fails (am start) | Tuple invocation | WARN | round-robin advances |
| Unknown new field from future producer | `org.json` DOM | key not read (ignored, D12) | parser keeps working |
| EditText widget not in static map | Typed fuzzing | fall back to legacy generator | input still produced; no regression |

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| `MopData.java` size grows past ~850 LOC | Bounded; readability gain outweighs size; nested POJOs are simple POJOs |
| Mechanical rename `WidgetMopFlags` → `MopData.Widget` misses a caller | `mvn compile` is exhaustive — every miss is a compile error; `grep -rn` gate confirms |
| ComponentInfo constructor break breaks tests | All callers go through factory/JSON parse path; mechanical fix |
| OPTIONSMENU boost over-prioritizes menu opening (false positive on activities with menu but no actual MOP path) | `Set<String>` is precomputed by checking widget flags — only activities whose menu has a directMop or transitiveMop widget are boosted; false positive only if static analysis itself is over-optimistic |
| Tuple-based triggering generates huge tuple lists on apps with many filters | Bounded — empirical cap on intent filters per component is ~3 (Android manifest convention); 50 receivers × 3 filters × 2 actions = 300 tuples — fits in memory |
| Provider `content insert` causes side effects | Out-of-process query first; insert/update minimal `--bind` with empty values; non-zero exit acceptable (logged, round-robin advances) |
| Typed fuzzing produces wrong shape (e.g., `phone` generator on a non-phone EditText labeled with `inputType="phone"` but expecting Brazilian-only format) | Legacy fallback gate `Config.fuzzInputTyped=false` |
| Activity triggering disrupts SATA flow (gh11 sandwichroulette -45pp evidence) | `Config.activityTriggerEnabled=false` default; opt-in only |
| Package mismatch false-rejects when runtime package legitimately differs (multi-process apps, custom processes) | Default warn-only; strict mode opt-in; runtime package extraction tolerant of unavailability (`null` ⇒ warn-only regardless) |
| LLM prompt budget bloat from 6+ new metadata tokens per widget | 80-char per-field cap; entries capped to 10; null-omission; revisit if `PromptIntegrationTest` regresses |
| Pre-gh60 sweep results in `out/sweep_jca400_v1/` unusable | Accepted; gh60 itself triggers a 380-APK re-run |
| Test fixtures drift from real producer output | Two real fixtures (cryptoapp + second) in `src/test/resources/`; lock the contract |
| `org.json` DOM holds the whole file in memory (vs. JsonReader streaming) | Fixture ~64 KB; sweep JSONs bounded; negligible vs. the existing all-GUITrees-in-memory footprint. Required for the §15.2 bug-fix gate to execute in `mvn test` (D21) |

## Testing Strategy

| Layer | What to test | Count |
|-------|-------------|-------|
| Unit — parser | Real cryptoapp fixture, bug-fix regression, empty-array corner cases, multi-listener idempotence, all field assertions, sentinel paths (incl. non-last position), package sanity, eventType maps, OPTIONSMENU set precomputation, transition events, ComponentInfo expanded shape, Config flag wiring, null-path / unknown-id null safety | 26 |
| Unit — ComponentInfo | Field captures, intent filter structure, provider authorities, reachesTarget honored, `getActions`/`getCategories` flatten across multi-filter, `componentType` derived from JSON dict key | 7 |
| Unit — MopScorer | scoreOpenMenu boost paths, eventType-aware scoring, eventTypeOf mapper (incl. Spinner detection), match-any fallback, null-MopData safety, `stateMopDensity` regression | 8 |
| Unit — ApeFuzzer | per-type generators (password/number/phone/email/uri/date/time/datetime), hint-based fallback, legacy fallback, `fuzzInputTyped=false` rollback path | 10 |
| Unit — StatefulAgent triggering | skip-non-reachable, skip-non-exported, round-robin actions, provider round-robin operations, log content, `activityTriggerEnabled=false` rollback, empty-list returns false, filter-null tuple from non-empty targetMethods, provider non-zero exit WARN | 9 |
| Unit — ApePromptBuilder | metadata in prompt, null omission, 80-char cap, 10-entry cap, newline flatten, inputType/hint appear, special-char safety | 7 |
| Integration | gh57+gh60 archive, real fixtures, mvn install, aperv smoke, LLM prompt spot-check | Deferred (§22) |

Total new unit tests: **67** (26 + 7 + 8 + 10 + 9 + 7). Expected post-change suite: ~212. Test priority distribution: HIGH 6 (rollback knobs `fuzzInputTyped=false` / `activityTriggerEnabled=false`, empty-list returns false, empty-arrays-parse-cleanly, OR-idempotent multi-listener, filter-null tuple from non-empty targetMethods), MED 11, LOW 4 — see tag suffixes in `tasks.md`. Baseline 46 tests (the prior plan) preserved; the +21 are adversarial / rollback / corner-case extensions.

## Implementation Deviations (recorded 2026-05-29, during execution)

Mechanism-level deltas from the original plan, all forced by the JVM-test classpath reality or by the actual codebase API. Behavior matches the spec; only the realization differs. Flagged here for reconciliation against incoming gh60 upstream changes.

- **D21 (org.json):** `MopData` parses via `org.json` not `android.util.JsonReader`. Already a decision above; restated for completeness.
- **`android.util.Log` → `Logger`:** `android.util.Log` is also excluded from the surefire classpath (same as JsonReader). `MopData` logs via the project's `ape.utils.Logger` (System.out-based) so `MopData.load()` runs in unit tests.
- **T1.3 generator lives in `TypedInputGenerator` (new `ape.utils` class), not `ApeFuzzer`:** `ApeFuzzer`'s static initializer references `android.view.KeyEvent`/`Surface`, so the class can't load in plain JVM and `generateInputForType` could not be tested there. The pure logic was extracted to `TypedInputGenerator.generateForType(inputType, hint, rnd)`; `ApeAgent.generateInputText` calls it. Spec/INV-MOP-16 behavior unchanged.
- **Provider triggering uses `AndroidDevice.executeCommandAndWaitFor(String[])`** (returns exit code) rather than a `runShell` helper, which does not exist. `StatefulAgent.runContentCommand` is an overridable seam around it.
- **`eventTypeOf` maps the real `ActionType` enum:** there is no `MODEL_INPUT`; scroll types are `MODEL_SCROLL_*`. Mapping: `MODEL_CLICK`→`"click"` (or `"itemSelected"` when the target widget class contains "Spinner"), `MODEL_LONG_CLICK`→`"longClick"`, any `MODEL_SCROLL_*`→`"scroll"`, else null. A `(ActionType, String widgetClass)` overload makes the Spinner heuristic unit-testable.
- **4 new `Config` flags are non-final `static`** (not `static final`) so unit tests can toggle the rollback knobs (`fuzzInputTyped`, `activityTriggerEnabled`, `mopStrictPackageMatch`) at runtime. Production reads them identically.
- **`MopData.forTest` widened to `public`** so the agent-package trigger tests can build fixtures.
- **`StatefulAgent` constructor still calls `MopData.load(Config.mopDataPath)`** (expected package = null ⇒ warn-only), consistent with the D17 fallback ("runtime package unavailable ⇒ null ⇒ warn-only"). Wiring the runtime package name is deferred — no behavior gap, sanity check is opt-in.
- **§19 dispatch + §17.8 density:** the Intent-send / shell-exec side effects and the populated-`State` density path require the Android runtime (no Mockito available), so those tests exercise the extracted static builders (`buildTriggerTuples`/`buildProviderTuples`/`triggerLogLine`/`buildContentCommand`) and null guards; full dispatch verification is part of the deferred §22 integration smoke.

## gh60 Reconciliation (recorded 2026-05-29, against `gh60-targets-core` C1)

The pinned fixture was generated before gh60's D14/D15 landed. After reading the gh60 artifacts and copying its authoritative fixture (`rv-static-analysis/tests/resources/cryptoapp.apk.json`) into all four repo fixture slots (`src/test/resources/cryptoapp.apk.gh60{,-fresh}.json`, `src/test/resources/fixtures/cryptoapp/cryptoapp.apk.json`, `test-apks/cryptoapp.apk.json`), the schema delta vs the old pin was **purely additive** and **wholly contained to components**:

- **gh60 D15 (component trigger surface):** every component entry gained `permission`; each `intentFilters[]` gained a `data{schemes,hosts,ports,paths,pathPrefixes,pathPatterns,mimeTypes}` block; providers gained `readPermission`/`writePermission`. Reconciled by extending `ComponentInfo` (`permission`, `hasPermissionGate()`), `IntentFilter` (`DataSpec data`, `hasData()`), and `ProviderInfo` (`readPermission`/`writePermission`) with **back-compat-preserving overloaded constructors** (old call sites still compile), and by extending `MopData.parseComponents`/`parseIntentFilters` to read the new keys with empty/null defaults. New coverage: `ComponentInfoTest` §16.8–16.12 (5 tests). `StatefulAgent.triggerLogLine` now surfaces `permission=` so a SecurityException trigger failure is diagnosable — **trigger selection logic is unchanged** (gh13 frozen; permission-aware skipping and deep-link/MIME Intent construction are explicitly deferred, matching gh60's framing of the data block as future-explorer scope).
- **gh60 D14 (component-section reorder):** components now serialize before the heavy sections. **No parser impact** — `MopData` uses an order-agnostic `org.json` DOM (D21).
- **gh60 D12 (cha→spark precision):** the new fixture's reachability counts are byte-identical to the old pin (16 classes / 106 methods / 21 directlyReachesTarget / 32 reachesTarget), so every count-pinned `MopDataTest`/`MopScorerTest` assertion survived unchanged. **One semantic divergence surfaced and was reconciled:** `PromptIntegrationTest.mopJson_cryptographyActivity_executeButtonIsMop` asserted `executeButton` was transitively MOP — but under gh60's spark call graph its handler (a desugared `$$ExternalSyntheticLambda0.onClick`) no longer links to a target, so `reachesTarget=false`. This is the intended spark precision improvement (a known invokedynamic false-negative), not an aperv regression. The test was flipped to assert the gh60 truth and renamed `…executeButtonNotMopUnderSparkCG`, kept as a tripwire (fires for re-review if a future analysis re-links the lambda).
- **Second instance of the load-bearing bug found & fixed:** `PromptIntegrationTest.parseMopForActivity` had its **own inline parser** reading the legacy `directlyReachesMop`/`reachesMop` wire keys against a legacy gh57-era fixture. Repointed to gh60 `directlyReachesTarget`/`reachesTarget` and the fixture refreshed; stale `reachesMop` comments updated to `reachesTarget` (D7 boundary hygiene).

**Net:** full suite **343 tests, 0 failures, 15 skipped** on the gh60-authoritative fixture. No gh13 behavior change beyond the additive D15 parse surface + one diagnostic log field. Items genuinely deferred to gh60 follow-ups (not this change): permission-gated trigger pruning and deep-link/MIME Intent construction (the explorer-side use of D15 `DataSpec`); C3 listener-level `handlerReachesTarget` enrichment (absent in gh60 C1, parser already reads it defensively per D8).

## Open Questions

- None blocking. Two follow-ups deferred:
  - **T2.1** Spinner `entries[]` → 1 action per entry (touches `ModelAction` creation; larger change).
  - **T2.2** WTG multi-hop Dijkstra pathfinding (current `scoreWtg` is 1-hop only).
