<!-- Complete-parser + Tier-1 consumer-wiring change + bug fix.
     Bug surfaced 2026-05-29: pre-change parseMethod / parseComponentArray
     read legacy gh57 keys (directlyReachesMop / reachesMop / mopMethods);
     gh60-C1f renamed all to *Target. Switch default is skipValue() ⇒
     bySignature stays empty ⇒ MopScorer.score returns 0 everywhere ⇒
     SATA-MOP has been functionally bare APE on any post-gh60 corpus.
     Regression pinned by §15.2 testWidgetDirectMopDerivedFromGh60Targets.
     User mandate (2026-05-26):
       (a) Every documented field in post-gh60 JSON has a typed Java home.
       (b) Seven Tier-1 effective-use items folded in: LLM metadata (T1.1),
           OPTIONSMENU boost (T1.2), inputType fuzzing (T1.3), tuple-based
           component triggering (T1.4), provider triggering (T1.5),
           eventType-aware scoring (T1.6), package sanity check (T1.7).
     Schema reality (2026-05-29 cryptoapp empirical):
       - NO recursive items[] in widgets (OPTIONSMENU is a separate top-level
         Window with flat MenuItem siblings)
       - NO widget-level directMop/transitiveMop emission (derived locally)
       - components is a DICT keyed by activities/receivers/services/providers
         (NOT a flat list with componentType field)
       - Listener has only {eventType, handler}; handlerReachesTarget belongs
         to gh60 C3 (deferred — read defensively as nullable)
     Critical path: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → tests.
     Gating: integration smoke (§22) is BLOCKED until gh57 AND gh60 archive. -->

## 1. POJO graph under MopData

- [ ] 1.1 Declare nested `public static class` for `Window`, `Widget`, `Listener`, `Transition`, `TransitionEvent`, `ReachabilityClass`, `ReachabilityMethod`. Public fields. Nullable strings default `null`; primitive ints default `-1`; lists default empty `ArrayList`.
- [ ] 1.2 Field map per POJO:
  - `Window { int id; String type; String name; boolean isMain; List<Widget> widgets; }`
  - `Widget { int id; String idName; String type; String text; String hint; String inputType; List<String> entries; String prompt; String spinnerMode; String contentDescription; String tooltipText; List<Listener> listeners; /* derived locally */ boolean directMop; boolean transitiveMop; Map<String,Boolean> directMopByEventType; Map<String,Boolean> transitiveMopByEventType; }` — **No `items` field**: gh60 does not emit nested widgets (D3, INV-MOP-17).
  - `Listener { String eventType; String handler; Boolean handlerReachesTarget; Boolean handlerDirectlyReachesTarget; }` — last two nullable, null on every listener until gh60-C3 lands.
  - `Transition { int sourceId; int targetId; List<TransitionEvent> events; }`
  - `TransitionEvent { String type; String handler; int widgetId; String widgetClass; String widgetName; }`
  - `ReachabilityClass { String className; String componentType; boolean isMain; List<ReachabilityMethod> methods; }`
  - `ReachabilityMethod { String name; String signature; boolean reachable; boolean reachesTarget; boolean directlyReachesTarget; }`
- [ ] 1.3 Rename `MopData.WidgetMopFlags` → `MopData.Widget`. Add helper accessors `Widget.isDirectMop(String eventType)` and `Widget.isTransitiveMop(String eventType)` (return per-event-type map value when `eventType` non-null and key present; else fall back to the aggregate `directMop`/`transitiveMop` boolean — match-any). Widget grew from 2 fields → 12 JSON-read + 4 derived.
- [ ] 1.4 Keep `MopData.WtgTransition` for the existing convenience view; derived from `transitions[]` click events.

## 2. ComponentInfo extension

- [ ] 2.1 `ComponentInfo` base fields: `String className`, `String componentType` ("activity"/"receiver"/"service"/"provider"), `boolean isMain`, `boolean exported`, `List<IntentFilter> intentFilters`, `boolean reachesTarget`, `List<String> targetMethods`. Constructor takes all of them; subclass constructors thin pass-throughs setting `componentType` literal.
- [ ] 2.2 Nested `ComponentInfo.IntentFilter { List<String> actions; List<String> categories; }` immutable POJO.
- [ ] 2.3 `ProviderInfo` keeps `authorities` String on top of the base.
- [ ] 2.4 Drop hardcoded `reachesMop=true` in subclasses. Drop the old `(className, List<String> actions)` constructor signature (P3).
- [ ] 2.5 Convenience helpers: `getActions()` returns flat union of all `intentFilters[i].actions[]`; `getCategories()` symmetric. Used by legacy callers that don't care about filter structure.

## 3. Parser — Pass 1 (reachability + top-level scalars)

- [ ] 3.1 Capture top-level `package` → `MopData.packageName` and `mainActivity` → `MopData.mainActivity`.
- [ ] 3.2 Populate `List<ReachabilityClass> reachability` with full per-class fields (`className`, `componentType`, `isMain`, `methods`).
- [ ] 3.3 Per-method full fields (`name`, `signature`, `reachable`, `reachesTarget`, `directlyReachesTarget`). Build `bySignature` index for cross-ref (existing).

## 4. Parser — Pass 2 (windows + widgets, eventType-typed cross-ref, NO recursion)

- [ ] 4.1 `parseWindow` populates `Window{id, type, name, isMain, widgets}`. Register in `List<Window> windows` AND `Map<Integer, Window> windowsById`.
- [ ] 4.2 `parseWidget(reader, bySignature, widgetsMap)` populates `Widget` with all 12 JSON-read fields. **Flat parse — no recursion** (D3: gh60 emits no `items[]`).
- [ ] 4.3 Per-listener: build `Listener{eventType, handler, handlerReachesTarget, handlerDirectlyReachesTarget}` — the last two nullable; absent or JSON `null` ⇒ Java `null`.
- [ ] 4.4 Cross-ref pass per widget builds the per-event-type reachability maps (D18, INV-MOP-17):
  - For each `Listener` in `widget.listeners`:
    - If `listener.handlerReachesTarget != null` ⇒ use that producer value (D8, INV-MOP-12).
    - Else resolve `(directReach, transReach)` from `bySignature[listener.handler]`.
    - Merge into `widget.directMopByEventType[listener.eventType]` (OR semantics: any matching listener flips to true).
    - Aggregate `widget.directMop = OR over all eventTypes` (match-any backward compat).

## 5. Parser — Pass 3 (transitions, full fields)

- [ ] 5.1 `parseTransition` populates `Transition{sourceId, targetId, events}` into `List<Transition> transitions`.
- [ ] 5.2 `parseTransitionEvent` populates `TransitionEvent{type, handler, widgetId, widgetClass, widgetName}` — every field, no drops.
- [ ] 5.3 Preserve `WtgTransition` convenience view (click events only, resolved via `windowsById`).

## 6. Parser — Pass 4 (components, intent filters with categories)

- [ ] 6.1 Populate expanded `ComponentInfo` per §2. Dispatch on the parent dict key (`activities`/`receivers`/`services`/`providers` — there is no per-element `componentType` field in the JSON; D19). Per-element fields: `className`, `isMain`, `exported`, `reachesTarget` (read from JSON — replaces hardcoded subclass default; INV-MOP-08), `targetMethods` (array of signatures, may be empty).
- [ ] 6.2 Inner `intentFilters[]` walk builds `List<IntentFilter>` keeping `actions` + `categories` per filter.
- [ ] 6.3 Provider branch keeps `authorities` String.
- [ ] 6.4 **Bug-fix surface**: rename switch cases in the legacy `parseComponentArray` from `"reachesMop"` to `"reachesTarget"`; add `"targetMethods"` case (replaces nothing — was silently dropped pre-change).

## 7. Parser — Pass 5 (sentinel) + load() glue

- [ ] 7.1 `verifyCompleteSentinel(path)` Pass 5 scans top-level `"complete"`. Absent or `false` ⇒ `null` return + WARN.
- [ ] 7.2 Three-arg overload `MopData.load(String path, String expectedPackage, String expectedMainActivity)`. The one-arg `load(path)` delegates to `load(path, null, null)`.
- [ ] 7.3 Sanity check (T1.7): when `expectedPackage` non-null and `expectedPackage != mopData.packageName` ⇒ WARN log naming both. When `expectedMainActivity` non-null and diverges ⇒ WARN. If `Config.mopStrictPackageMatch=true`, divergence ⇒ `null` return.
- [ ] 7.4 Precompute `Set<String> activitiesWithMopOptionsMenu` (T1.2, D4): for each `Window` with `type=="OPTIONSMENU"`, parse the activity prefix from `name` (`name.substring(0, name.indexOf("#OptionsMenu"))`); if any widget in that window's flat `widgets` list has `directMop || transitiveMop` (derived in §4.4), add the activity name to the set.
- [ ] 7.5 Public API additions: `getPackageName()`, `getMainActivity()`, `isComplete()`, `getWindows()`, `getWindow(int id)`, `getReachability()`, `getTransitions()`, `activityHasMopOptionsMenu(String activity)`. Existing API preserved.
- [ ] 7.6 `MopData.load` returns `null` on: null path, missing file, malformed JSON, sentinel absent/false, strict-mode package mismatch.

## 8. Consumer — mechanical renames

- [ ] 8.1 `grep -rn 'WidgetMopFlags' src/` → for every hit, replace with `MopData.Widget`. Files touched: `MopScorer.java`, `ApePromptBuilder.java`, `LlmRouter.java`, `SataAgent.java`, `StatefulAgent.java`, plus test classes. `mvn compile` proves completeness.
- [ ] 8.2 `ComponentInfo.actions` (flat field) was read by `triggerMopComponent` (now rewritten in §11). Confirm no other readers of the flat field remain; `getActions()` helper preserves the read pattern for any survivor.

## 9. Consumer — Config flags

- [ ] 9.1 Add to `Config.java`:
  - `mopWeightOpenMenu` — `Config.getInteger("ape.mopWeightOpenMenu", 250)`
  - `fuzzInputTyped` — `Config.getBoolean("ape.fuzzInputTyped", true)`
  - `mopStrictPackageMatch` — `Config.getBoolean("ape.mopStrictPackageMatch", false)`
  - `activityTriggerEnabled` — `Config.getBoolean("ape.activityTriggerEnabled", false)`
- [ ] 9.2 Document the new flags in `CLAUDE.md` under the existing "Central Configuration" knob list.

## 10. Consumer — MopScorer extensions (T1.2 + T1.6)

- [ ] 10.1 New method `MopScorer.scoreOpenMenu(String activity, MopData data)`: returns `Config.mopWeightOpenMenu` if `data != null && data.activityHasMopOptionsMenu(activity)`, else `0`.
- [ ] 10.2 New overload `MopScorer.score(String activity, String shortId, MopData data, String candidateEventType)`: when `candidateEventType` non-null, prefer `widget.isDirectMop(candidateEventType)` / `isTransitiveMop(candidateEventType)` over the aggregate booleans. Fallback to aggregate when per-event-type map has no entry (match-any).
- [ ] 10.3 The existing 3-arg `score(activity, shortId, data)` delegates to the 4-arg with `eventType=null`. Backward compatible.
- [ ] 10.4 Action-type ↔ eventType mapping helper `MopScorer.eventTypeOf(ModelAction a)`:
  - `ActionType.MODEL_CLICK` → `"click"`
  - `ActionType.MODEL_LONG_CLICK` → `"longClick"`
  - `ActionType.MODEL_INPUT` / Spinner-bearing actions → `"itemSelected"` (Spinner heuristic: widget class contains "Spinner")
  - `ActionType.MODEL_SCROLL` → `"scroll"`
  - anything else → `null` (triggers match-any)

## 11. Consumer — StatefulAgent triggerMopComponent rewrite (T1.4 + T1.5)

- [ ] 11.1 Define inner `TriggerTuple { ComponentInfo component; IntentFilter filter; String action; }` plus `ProviderTuple { ProviderInfo p; String operation; }` (`operation ∈ {"query","insert","update"}`).
- [ ] 11.2 Build the round-robin tuple list once per session (cache on `_mopData` load):
  - Iterate `getReceivers() + getServices() + (Config.activityTriggerEnabled ? getActivities() : emptyList())`.
  - Skip `component.reachesTarget == false`.
  - Skip `component.exported == false` when `componentType == "activity"`.
  - For each remaining component, for each `IntentFilter f` in `component.intentFilters`, for each `a` in `f.actions`, emit `TriggerTuple(component, f, a)`. Components with empty `intentFilters` but non-empty `targetMethods` get one tuple with `filter=null` and `action=null` (component-name-only intent — receivers can sometimes be hit this way; activities via `am start -n <pkg>/<cls>` no action).
  - For each provider with `reachesTarget=true` and non-null `authorities`, emit three `ProviderTuple` (query/insert/update) in round-robin (insert/update minimal — empty content-values).
- [ ] 11.3 `triggerMopComponent()` advances `componentTriggerIndex` modulo (tupleList.size + providerList.size); selects tuple; builds intent / provider command; sends; logs `[APE-RV] Triggering <type>: <className> action=<a> categories=<c1,c2> reachesTarget=true`. When `filter != null`, `addCategory(category)` for each category in `filter.categories`.
- [ ] 11.4 Provider invocation: `AndroidDevice.runShell("content " + operation + " --uri content://" + authorities)` (plus minimal `--bind` for insert/update). Capture exit code; non-zero ⇒ WARN with stderr tail.
- [ ] 11.5 Drop the `_broadcastCatalog` `IntentExtra` lookup path? **Keep it** — `SystemBroadcastCatalog` still adds known-good extras for system broadcasts. Apply it AFTER `setAction`, BEFORE `sendBroadcast`.
- [ ] 11.6 Remove the `Activities excluded` comment block and the corresponding code. The decision is now controlled by `Config.activityTriggerEnabled` flag.

## 12. Consumer — ApePromptBuilder extensions (T1.1)

- [ ] 12.1 In the widget description rendering path (`buildMopMarker` callers — lines ~448 and ~512, plus the per-action `formatActionLine`), append the following when the corresponding `Widget` fields are non-empty:
  - ` prompt="<v>"`, ` spinnerMode="<v>"`, ` contentDescription="<v>"`, ` tooltipText="<v>"`, ` entries=[<v1, v2, …>]`, ` inputType="<v>"`, ` hint="<v>"`.
- [ ] 12.2 Per-field rules: 80-char per-value cap with `…` ellipsis; newlines flattened to single space; `entries=[…]` capped to 10 elements with `, …` suffix when truncated. Null/empty values ⇒ zero tokens.
- [ ] 12.3 The existing `[DM]` / `[M]` markers stay. Optional: when `MopScorer.eventTypeOf(action)` produces a non-null event type and the widget has eventType-specific flags, refine the marker to `[DM:click]` / `[M:itemSelected]` etc. **Decision in design D16: keep markers eventType-agnostic in v1; revisit if LLM telemetry shows the LLM gets confused by mismatched action/listener types.**

## 13. Consumer — ApeFuzzer typed input (T1.3)

- [ ] 13.1 New static `ApeFuzzer.generateInputForType(String inputType, String hint, java.util.Random rnd)`. Returns a `String`. Switch on `inputType`:
  - contains "Password" ⇒ random 8–12 char mixed-class (letters + digits + 1 symbol)
  - equals "number" / "numberSigned" / "numberDecimal" ⇒ `rnd.nextInt(...)` formatted
  - "phone" ⇒ `+55 11 9XXXX-XXXX` template
  - "textEmailAddress" ⇒ `<8 lower>@example.com`
  - "textUri" ⇒ `https://example.com/<8 lower>`
  - "date" / "time" / "datetime" ⇒ ISO 8601 random in last decade
  - else: if `hint` non-empty:
    - contains "email" (case-insensitive) ⇒ email
    - contains "senha" or "password" ⇒ password
    - all-digits ⇒ number
  - final fallback ⇒ legacy `RandomHelper.randomString(rnd, 6, 12)` (or equivalent existing helper)
- [ ] 13.2 Hook into the input-injection path. Find where `MonkeySourceApe` / `ApeFuzzer` generates a string for an `EditText` target (likely around the `setText` event construction). Read the target widget's `Widget.inputType` and `Widget.hint` via `mopData.getWidget(activity, shortId)`; pass to `generateInputForType`.
- [ ] 13.3 Gated by `Config.fuzzInputTyped` (default `true`). When `false`, behave exactly as today.
- [ ] 13.4 When `mopData == null` OR the widget is not in the static map ⇒ fall back to legacy generator (no regression on non-instrumented apps).

## 14. Consumer — StatefulAgent action-priority hook for MODEL_MENU (T1.2)

- [ ] 14.1 In the existing MOP-guidance pass (StatefulAgent.java around line 1221), AFTER the widget loop, scan the state's actions for the `MODEL_MENU` action. If `_mopData != null && _mopData.activityHasMopOptionsMenu(newState.getActivity())`, apply `MopScorer.scoreOpenMenu` boost to the action's priority. Logger.iformat one-line summary.
- [ ] 14.2 In the same pass, pass `MopScorer.eventTypeOf(action)` to the new `MopScorer.score` 4-arg overload (T1.6 wiring). The existing call site changes from `score(activity, shortId, _mopData)` to `score(activity, shortId, _mopData, MopScorer.eventTypeOf(action))`.

## 15. Tests — MopDataTest (parser, 26 tests)

- [ ] 15.1 `testFullFixtureLoadsAllFields` — load `src/test/resources/cryptoapp.apk.gh60-fresh.json` (pinned 2026-05-29, post gh60 task 11 hint/text fix). Assert `package=="br.unb.cic.cryptoapp"`, `mainActivity=="br.unb.cic.cryptoapp.MainActivity"`, `isComplete()==true`, 16 reachability classes (55 reachable / 32 reachesTarget / 21 directlyReachesTarget across all methods), 5 windows (4 ACTIVITY + 1 OPTIONSMENU named `"…MainActivity#OptionsMenu"` with 3 flat MenuItem widgets), 51 widgets total, 35 transitions, components `{activities:4, receivers:0, services:0, providers:1}` with provider `authorities=="br.unb.cic.cryptoapp.androidx-startup"`. At least one Widget with `entries.size()==13` (the `spinnerMessageDigest`). One TransitionEvent with handler non-empty + widgetId > 0. Widget metadata extraction floor: ≥4 widgets with non-empty `hint`, ≥11 with non-empty `text`, ≥4 with non-empty `inputType` (empirical floor on the fresh fixture — `contentDescription`/`tooltipText`/`prompt`/`spinnerMode` are 0 because cryptoapp's source XML does not declare them, not a parser bug).
- [ ] 15.2 `testWidgetDirectMopDerivedFromGh60Targets` — **bug-fix regression** (INV-MOP-07, D20). Load `cryptoapp.apk.gh60-fresh.json`; assert `getWidget("br.unb.cic.cryptoapp.MainActivity","menu_item_message_digest").directMop==true` AND its single listener has `eventType=="click"` AND handler `==="<br.unb.cic.cryptoapp.MainActivity$1: boolean onMenuItemClick(android.view.MenuItem)>"` AND that handler appears in the reachability index. Assert `activityHasMop("br.unb.cic.cryptoapp.MainActivity")==true` AND `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`. Pre-fix (legacy `*Mop` keys in switch) ⇒ ALL assertions FAIL because `bySignature` is empty. Post-fix ⇒ all PASS. **This test is the contract that "SATA-MOP is not silently bare APE."**
- [ ] 15.3 `testEditTextWidgetMetadataCaptured` — load `cryptoapp.apk.gh60-fresh.json`; assert `getWidget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity","editTextMessageDigest")` has `type=="android.widget.EditText"`, `inputType=="textPersonName"`, `hint=="Input text ..."`. Validates the post-task-11 hint/text extraction is consumed correctly by the parser (T1.1 + T1.3 input surface).
- [ ] 15.4 `testJsonKeysRenamedToTarget` — synthetic fixture; post-gh60 `reachesTarget` / `directlyReachesTarget` / `targetMethods` keys populate flags. Legacy keys (synthetic JSON with `reachesMop:true`) are silently skipped (P3 + INV-MOP-11 forward-compat fall-through).
- [ ] 15.5 `testCompleteSentinelMissing_returnsNull` / `_FalseReturnsNull` / `_TrueLoadsNormally` — three sub-cases under one method.
- [ ] 15.6 `testTopLevelPackageAndMainActivity` — synthetic fixture; verify both readable from top-level scalars.
- [ ] 15.7 `testPackageMismatchWarnsByDefault` — call `load(path, "x.y.z.OTHER", null)`; assert non-null return + WARN log.
- [ ] 15.8 `testPackageMismatchRejectsWhenStrict` — set `Config.mopStrictPackageMatch=true`; assert `null` return.
- [ ] 15.9 `testReachabilityClassFieldsCaptured` — full per-class + per-method field assertion (including the `reachable` flag, which the pre-change parser dropped).
- [ ] 15.10 `testWidgetCoreFieldsCaptured` — EditText fixture with id/idName/type/text/hint/inputType.
- [ ] 15.11 `testParsesFourNewWidgetAttributes` — Spinner with prompt/spinnerMode + Button with contentDescription/tooltipText (gh57 attrs).
- [ ] 15.12 `testNewWidgetFieldsNullWhenAbsent` — omit + explicit JSON null.
- [ ] 15.13 `testSpinnerEntriesCaptured` — assert `entries` list equality.
- [ ] 15.14 `testListenerFieldsCaptured` — eventType + handler captured; `handlerReachesTarget` / `handlerDirectlyReachesTarget` null when absent from JSON (current gh60 producer state).
- [ ] 15.15 `testListenerHandlerReachesTargetHonored` — synthetic fixture with `handlerReachesTarget:true` and `bySignature` would say false ⇒ `widget.transitiveMop==true` (producer wins, D8).
- [ ] 15.16 `testListenerHandlerReachesTargetAbsentFallsBackToCrossRef` — synthetic fixture without the field; widget flag derived from `bySignature` only.
- [ ] 15.17 `testTransitionEventFieldsCaptured` — all 5 fields.
- [ ] 15.18 `testTransitionImplicitEventsPreservedInRawView` — `implicit_back_event` survives in `getTransitions()`; filtered from `getWtgTransitions`.
- [ ] 15.19 `testActivitiesWithMopOptionsMenuPrecomputed` — synthetic fixture with two activities A and B; A has an OPTIONSMENU window named `"A#OptionsMenu"` whose widget's listener handler reaches target; B's OPTIONSMENU widgets don't. Assert `activityHasMopOptionsMenu("A")==true && ...("B")==false`. Also verifies the cryptoapp case: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`.
- [ ] 15.20 `testWidgetEventTypeMapsBuilt` — widget with two listeners (`click` + `longClick`), only the click handler is in `bySignature` with `directlyReachesTarget=true`. Assert `widget.isDirectMop("click")==true && widget.isDirectMop("longClick")==false && widget.directMop==true (aggregate)`.
- [ ] 15.21 `testEmptyArraysParseToEmptyCollections` (HIGH) — synthetic fixture with `reachability:[]`, `windows:[]`, `transitions:[]`, `components:{activities:[],receivers:[],services:[],providers:[]}`, sentinel `complete:true`. Assert non-null `MopData`, `isComplete()==true`, `getReachability().isEmpty()`, `getWindows().isEmpty()`, `getTransitions().isEmpty()`, `getReceivers().isEmpty()` etc. **Why**: gh60 task 0 classification showed 651/826 sweep JSONs have empty windows/transitions (timeout-during-WTG complete-but-empty bucket). Parser MUST handle these without `NullPointerException`.
- [ ] 15.22 `testMultipleListenersSameHandlerNoDoubleCount` (HIGH) — widget with two `click` listeners pointing to the same handler signature; handler is in `bySignature` with `directlyReachesTarget=true`. Assert `widget.directMopByEventType.get("click")==true` (OR-idempotent — no boolean overflow / no quirk). Assert two listeners parsed (preserved as-is in `widget.listeners.size()==2`). Pin against silent double-boost regression.
- [ ] 15.23 `testConfigFlagsLoadFromProperties` (MED) — write a synthetic `ape.properties` with `ape.mopWeightOpenMenu=999`, `ape.fuzzInputTyped=false`, `ape.mopStrictPackageMatch=true`, `ape.activityTriggerEnabled=true`; reload `Config` (via test-only `Config.reload(File)` if exists, or document the test as Android-runtime skip and exercise via system properties in JVM). Assert all 4 flags read the custom values. **Why**: defense-in-depth that operator overrides in `ape.properties` actually take effect (the rollback knobs are useless if the flags don't bind).
- [ ] 15.24 `testCompleteSentinelInMiddleStillRecognized` (LOW) — synthetic fixture where `"complete": true` appears as the SECOND top-level key (not last). Assert `isComplete()==true`. Producer contract emits sentinel last, but the parser MUST NOT rely on positional order (the 5th-pass scan reads any key match).
- [ ] 15.25 `testLoadNullPathReturnsNullCleanly` (LOW) — `MopData.load(null)` SHALL return `null` without throwing, without WARN-level log noise (INFO-or-quieter acceptable).
- [ ] 15.26 `testGetWindowUnknownIdReturnsNull` (LOW) — load real fixture; assert `getWindow(0)==null`, `getWindow(-1)==null`, `getWindow(Integer.MAX_VALUE)==null`. Lookup is null-safe.

## 16. Tests — ComponentInfoTest (7 tests)

- [ ] 16.1 `testComponentFieldsCaptured` — all base fields including `targetMethods`.
- [ ] 16.2 `testIntentFilterPreservesCategoriesAndActions` — structure preserved; helpers flatten.
- [ ] 16.3 `testProviderAuthoritiesCaptured`.
- [ ] 16.4 `testComponentReachesTargetReadFromJson` — `reachesTarget=false` correctly read (proves not hardcoded).
- [ ] 16.5 `testGetActionsFlattensAcrossMultipleFilters` (MED) — component with two `IntentFilter`s (filter A actions `[a1,a2]`, filter B actions `[a3]`); assert `getActions()` returns `[a1,a2,a3]` (union, order preserved, no dedup).
- [ ] 16.6 `testGetCategoriesFlattensAcrossMultipleFilters` (MED) — symmetric to 16.5: filter A categories `[c1]`, filter B categories `[c2,c3]`; assert `getCategories()` returns `[c1,c2,c3]`.
- [ ] 16.7 `testComponentTypeDerivedFromJsonDictKey` (MED, D19) — synthetic JSON with `components.activities[]` and `components.receivers[]`; assert the parsed `ActivityInfo.componentType=="activity"` and `ReceiverInfo.componentType=="receiver"` — proves the dispatcher reads the dict key (NOT a per-element JSON field).

## 17. Tests — MopScorerTest extensions (8 tests)

- [ ] 17.1 `testScoreOpenMenuBoostsWhenOptionsMenuHasMopWidget` — `activityHasMopOptionsMenu` true ⇒ returns `Config.mopWeightOpenMenu`.
- [ ] 17.2 `testScoreOpenMenuZeroWhenActivityHasNoMopOptionsMenu`.
- [ ] 17.3 `testScoreEventTypeAwareMatchesClick` — widget where only the click listener reaches MOP; `score(act, id, data, "click")` returns directMop boost; `score(act, id, data, "longClick")` returns 0.
- [ ] 17.4 `testScoreEventTypeNullFallsBackToAggregate` — same widget; passing `eventType=null` returns directMop boost (match-any).
- [ ] 17.5 `testEventTypeOfMapsActionTypes` — `MODEL_CLICK → "click"`, `MODEL_LONG_CLICK → "longClick"`, etc.
- [ ] 17.6 `testScoreReturnsZeroWhenMopDataNull` (MED) — `MopScorer.score(act, id, null, "click")==0`; `scoreOpenMenu(act, null)==0`; `scoreWtg(act, id, null)==0`; `stateMopDensity(state, null)==0`. Null-safe contract across the whole API surface.
- [ ] 17.7 `testEventTypeOfSpinnerDetection` (MED) — synthetic `ModelAction` of type `MODEL_INPUT` whose target widget class is `android.widget.Spinner` ⇒ `eventTypeOf` returns `"itemSelected"`. Same action on `android.widget.EditText` ⇒ returns `null` (input on non-Spinner is not an itemSelected event).
- [ ] 17.8 `testStateMopDensityUnchanged` (MED) — pin the existing semantics: state with N target-requiring valid actions, activity is in `mopActivities` ⇒ returns N. Activity NOT in `mopActivities` ⇒ returns 0. Regression guard for the SATA tiebreaker path.

## 18. Tests — ApeFuzzerInputTypeTest (10 tests)

- [ ] 18.1 `testPasswordInputTypeProducesMixedClass` — `inputType="textPassword"` ⇒ output contains letter + digit + symbol; length in [8,12].
- [ ] 18.2 `testNumberInputTypeProducesDigits` — `inputType="number"` ⇒ output matches `^-?\d+$`.
- [ ] 18.3 `testPhoneInputTypeMatchesTemplate` — output matches phone regex.
- [ ] 18.4 `testEmailInputTypeContainsAt` — output matches `^[a-z]+@example\.com$`.
- [ ] 18.5 `testHintBasedFallbackDetectsEmail` — `inputType=""`, `hint="Your email"` ⇒ email output.
- [ ] 18.6 `testUnknownInputTypeFallsBackToLegacy` — `inputType="weird"`, hint empty ⇒ output is a non-empty string from legacy generator.
- [ ] 18.7 `testFuzzInputTypedFlagBypassesTypedPath` (HIGH, INV-MOP-16 rollback guard) — set `Config.fuzzInputTyped=false`; call `generateInputForType("textPassword", "Your password", rnd)`; assert output does NOT match the password shape (letter+digit+symbol) and instead matches the legacy `RandomHelper.randomString` shape. Proves the operator-level rollback knob is wired and effective. **Rollback contract**: if T1.3 corrupts a corpus run, flipping `Config.fuzzInputTyped=false` MUST restore legacy behavior — this test pins it.
- [ ] 18.8 `testDateInputTypeProducesIso8601` (MED) — `inputType="date"` ⇒ output matches `^\d{4}-\d{2}-\d{2}$`; year ∈ [current-10, current]; month ∈ [01,12]; day ∈ [01,31] (calendar-valid not enforced for v1 — fuzzing tolerates Feb 30).
- [ ] 18.9 `testTimeAndDatetimeInputTypesProduceIso8601` (MED) — `inputType="time"` ⇒ matches `^\d{2}:\d{2}(:\d{2})?$`; `inputType="datetime"` ⇒ matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$`.
- [ ] 18.10 `testUriInputTypeMatchesShape` (MED) — `inputType="textUri"` ⇒ matches `^https://example\.com/[a-z]{8}$`.

## 19. Tests — StatefulAgentTriggerTest (9 tests, mock-based)

- [ ] 19.1 `testTriggerSkipsNonReachableComponents` — MopData with one receiver `reachesTarget=false` and one with `true`; assert the tuple list excludes the false one.
- [ ] 19.2 `testTriggerSkipsNonExportedActivities` — activity `exported=false`; excluded from tuple list (regardless of `Config.activityTriggerEnabled`).
- [ ] 19.3 `testTriggerRoundRobinsAllIntentFilterActions` — receiver with 2 actions in one filter; advance trigger index 4 times; assert actions visited alternately (each action picked twice).
- [ ] 19.4 `testTriggerProviderRoundRobinsOperations` — provider with `reachesTarget=true`; advance trigger index; assert shell commands include all of `content query`, `content insert`, `content update`.
- [ ] 19.5 `testTriggerLogsContainExpectedFields` — capture stdout; assert log line for one trigger contains `className`, `action`, `categories=`, `reachesTarget=true`.
- [ ] 19.6 `testActivityTriggerDisabledExcludesActivitiesFromTupleList` (HIGH, INV-MOP-15 rollback guard) — `Config.activityTriggerEnabled=false` (default). MopData with one reachable+exported activity AND one reachable receiver. Assert the tuple list contains the receiver tuple AND zero activity tuples. Flip `Config.activityTriggerEnabled=true`, rebuild ⇒ activity now present. **Why**: gh11 sandwichroulette evidence (-45pp) — default OFF is load-bearing for experiment integrity. Pin against accidental enable.
- [ ] 19.7 `testTriggerReturnsFalseOnEmptyComponentList` (HIGH) — cryptoapp-like MopData (all components `reachesTarget=false`); call `triggerMopComponent()`; assert returns `false` AND no shell / broadcast / service start invoked AND counter NOT advanced. The cryptoapp common case — the 380-APK corpus contains many similar APKs; no-op MUST be cheap and silent.
- [ ] 19.8 `testTriggerEmitsComponentNameOnlyTupleWhenFiltersEmpty` (HIGH, D15) — receiver with `reachesTarget=true`, `intentFilters=[]`, `targetMethods=["<sig>"]`; assert exactly one `TriggerTuple` with `filter=null, action=null`; on invocation, intent has `setComponent` but no `setAction`/`addCategory` calls.
- [ ] 19.9 `testProviderNonZeroExitLogsWarnWithStderr` (MED) — mock `AndroidDevice.runShell` to return non-zero exit and a stderr line; call provider trigger; assert WARN log contains the stderr tail AND counter advanced AND next call moves to the next operation in the sub-cycle (no retry-loop).

## 20. Tests — ApePromptBuilderTest extensions (7 tests)

- [ ] 20.1 `testWidgetMetadataAppearsInPrompt` — contentDescription + tooltipText populated; substrings present.
- [ ] 20.2 `testNullWidgetMetadataOmittedFromPrompt` — all null; no field tokens.
- [ ] 20.3 `testWidgetMetadataTruncatedAt80Chars`.
- [ ] 20.4 `testSpinnerEntriesAppearInPromptCappedAt10`.
- [ ] 20.5 `testMetadataNewlinesFlattened`.
- [ ] 20.6 `testInputTypeAndHintAppearInPrompt` — EditText widget with `inputType="textPassword"` + `hint="Your password"`; assert both substrings.
- [ ] 20.7 `testSpecialCharsInMetadataDoNotBreakPrompt` (LOW) — widget with `contentDescription` containing `"`, `[`, `]`, `\` characters. Assert rendered substring still well-formed (no unescaped delimiter that would corrupt the LLM tool-call JSON downstream). Strategy: either escape inline (replace `"` with `\"`) or use a non-quote delimiter — either is acceptable as long as the prompt-parsing path remains intact.

## 21. Full suite + gates

- [ ] 21.1 `mvn test` — all unit tests pass; the 14 Android-runtime tests stay skipped. Target ≥ 210 tests after this change (current 145 + 67 new).
- [ ] 21.2 No `WidgetMopFlags` references remain: `grep -rn 'WidgetMopFlags' src/` ⇒ zero hits.
- [ ] 21.3 No legacy `reachesMop` / `directlyReachesMop` / `mopMethods` / `handlerReachesMop` in `src/main/`: `grep -rn 'reachesMop\|directlyReachesMop\|mopMethods\|handlerReachesMop' src/main/` ⇒ zero hits.
- [ ] 21.4 Manual review: every `default: skipValue()` survives only as forward-compat fall-through; no known gh60 field name is dropped.
- [ ] 21.5 No call site of `ComponentInfo.actions` (flat field) remains: `grep -rn '\.actions\b' src/main/java/com/android/commands/monkey/ape/agent/` flagged for review — all surviving usages MUST go through `getActions()` helper.
- [ ] 21.6 **Bug-fix gate**: `testWidgetDirectMopDerivedFromGh60Targets` PASSES on the real `cryptoapp.apk.gh60-fresh.json` fixture. Removing the §15.2 test from the suite (or breaking the fix by reverting the rename) MUST cause `mvn test` to fail — explicit verification that the regression test actually pins the bug-fix surface.

## 22. Integration smoke (DEFERRED until gh57 AND gh60 archive)

- [ ] 22.1 Wait for `gh57-static-analysis-overhaul` AND `gh60-targets-core` to archive in `rvsec/rv-android`.
- [ ] 22.2 Move `openspec/changes/gh13-mopdata-schema-v2/cryptoapp.apk.gh60-fresh.json` (pinned 2026-05-29 post hint/text fix) to `src/test/resources/cryptoapp.apk.gh60-fresh.json` at implementation kickoff. Optionally retain `cryptoapp.apk.gh60.json` (pre hint/text) as a second fixture to prove the parser handles empty `hint`/`text` strings without regressing.
- [ ] 22.3 Run `testFullFixtureLoadsAllFields`, `testWidgetDirectMopDerivedFromGh60Targets`, `testEditTextWidgetMetadataCaptured`; all must be green. When a non-cryptoapp APK with `reachesTarget=true` components becomes available, add it as fixture #3 and write `testTriggerMopComponentOnReachingFixture` covering T1.4/T1.5.
- [ ] 22.4 `mvn install -Drvsec_home=<rvsec_root>` — refreshes `ape-rv.jar`.
- [ ] 22.5 Aperv smoke from `rvsec/rv-android`: `uv run rv-experiment run --tools aperv:sata_mop --apks-dir <3 APKs> --timeout 60`. Confirm:
  - log line `[APE-RV] MOP boost: state=… boosted=N/M` non-zero on cryptoapp's MainActivity
  - log line `[APE-RV] Triggering broadcast/service/provider: …` appears
  - log line `[APE-RV] menu boost` (T1.2) appears for cryptoapp's MainActivity
  - log line indicating typed fuzzing for at least one EditText (T1.3)
- [ ] 22.6 Spot-check one LLM run (set `llmUrl`): prompt log contains `contentDescription=`/`tooltipText=`/`prompt=`/`spinnerMode=`/`entries=[…]`/`inputType=` annotations for at least one widget.

## 23. Verification

- [ ] 23.1 Invoke `sdd-code-reviewer` via Skill tool against the change diff.
- [ ] 23.2 `/opsx:verify` against this change.
- [ ] 23.3 `/opsx:archive` (after §22 passes).
- [ ] 23.4 Final commit message references `closes #13`.
