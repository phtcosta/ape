## Why

**Bug revealed (2026-05-29 audit).** The current `MopData.parseMethod` switches on the legacy gh57 keys `"directlyReachesMop"` / `"reachesMop"`. The gh60-C1f rename (merged upstream) replaced those keys with `"directlyReachesTarget"` / `"reachesTarget"`. Default case in the switch is `reader.skipValue()` — no warning, no count. Against any post-gh60 JSON the `bySignature` index stays empty, every widget cross-reference fails, `Widget.directMop`/`transitiveMop` stay `false` for every widget, `activityHasMop` returns `false` for every activity, and `MopScorer.score(...)` returns `0` for every action. The MOP-guidance pass at `StatefulAgent:1221-1243` applies zero priority boost. **Effect: APE-RV in SATA-MOP mode has been functionally equivalent to bare APE on any corpus regenerated under gh60.** `parseComponentArray` has the same regression at the component path (`case "reachesMop"` → `case "reachesTarget"`).

This bug is the load-bearing reason to ship this change before the 380-APK re-run: without it, the re-run measures bare-APE-with-extra-logging, not MOP-guided exploration.

**Upstream shape changes** (`rvsec/rv-android`) consumed alongside the bug fix:

- **`gh57-static-analysis-overhaul`** — four widget XML attributes (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`), populated `OPTIONSMENU` windows as separate top-level `Window` entries with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` (carrying flat MenuItem widgets as siblings — there is no `items[]` nesting in the emitted JSON), Spinner `entries[]` from `<string-array>`/`<integer-array>`/`<array>` XML (G6.4 pulled forward into gh60), decoupled `windows[]` from WTG completion.
- **`gh60-targets-core`** — BREAKING rename MOP→Target end-to-end, top-level sentinel `"complete": true`, top-level `package` / `mainActivity`, structured `components` dict keyed by `{activities, receivers, services, providers}` (NOT a flat list with `componentType` field — the section name IS the type), per-component `reachesTarget` / `targetMethods[]` / `exported` / `isMain`, structured `intentFilters[]` with `actions[]` AND `categories[]`, per-method `reachable` / `name` / `componentType` / `isMain`. **gh60 D14/D15** (landed 2026-05-29): `components` serialized before the heavy sections (order-agnostic for the `org.json` DOM parser); each component carries the trigger surface `permission` + per-`IntentFilter` `data{schemes,hosts,ports,paths,pathPrefixes,pathPatterns,mimeTypes}`, providers add `readPermission`/`writePermission`. **gh60 D12** cha→spark call-graph precision (intended): `executeButton`'s desugared synthetic-lambda handler no longer links to a target (`reachesTarget=false`), a known spark invokedynamic false-negative — reconciled in `PromptIntegrationTest` as a tripwire.

**Empirical analysis** of the gh60 smoke JSON for cryptoapp (2026-05-29):

- Top-level keys present: `package`, `mainActivity`, `reachability`, `windows`, `transitions`, `components`, `complete`. Sentinel `complete=true` last field.
- Counts: 16 reachability classes (55 reachable, 32 `reachesTarget=true`, 21 `directlyReachesTarget=true`), 5 windows (4 `ACTIVITY` + 1 `OPTIONSMENU` named `MainActivity#OptionsMenu` carrying 3 flat MenuItem widgets), 51 widgets total, 35 transitions, components = `{activities: 4, receivers: 0, services: 0, providers: 1}`.
- 7 unique listener handlers across all widgets, all 7 cross-reference into `reachability[].methods[].signature` ⇒ once the parser reads the renamed keys, every widget that should boost will boost.
- All 4 activities + 1 provider carry `reachesTarget=false` and empty `targetMethods` — cryptoapp does not exercise component triggering; the T1.4/T1.5 smoke test needs an APK whose static analysis flags at least one receiver/service/provider as `reachesTarget=true`.
- Widget JSON shape has NO `directMop` / `transitiveMop` / `items` fields. Those flags are derived locally by `parseWidget` cross-referencing `listener.handler` against `bySignature`. The per-`eventType` maps (T1.6) are likewise derived by grouping listeners before cross-reference.
- Listener JSON shape is `{eventType, handler}` only. `handlerReachesTarget` / `handlerDirectlyReachesTarget` belong to gh60 follow-up **C3** (`gh<N+2>-agent-enrichment`); the parser reads them defensively as nullable Booleans for forward-compat (when C3 lands they take precedence over local cross-reference).
- The cryptoapp fixture pinned in this change pre-dates the gh60 task 11 hint/text fix (widget `hint`/`text` populate as empty strings). The user will regenerate.

**Shallow consumer use today** (after the bug fix lands): `MopScorer` reads only the three aggregate booleans; `triggerMopComponent` uses only `actions.get(0)` and excludes activities + providers + intent categories + filter structure; `ApeFuzzer` is blind to `inputType`; Spinner `entries[]` are dropped; `Listener.eventType` is dropped; OPTIONSMENU windows are not exploited as a target signal. Most of the JSON is still parsed and thrown away.

A deep audit (2026-05-26 session) classified the gap into three tiers and the user mandated **all seven Tier-1 items** land in this change, on top of the bug fix:

1. **T1.1** Widget metadata + Spinner entries in LLM prompts.
2. **T1.2** OPTIONSMENU-aware menu-open boost: precomputed `Set<String> activitiesWithMopOptionsMenu` populated during `MopData.load` by scanning `Window`s of `type="OPTIONSMENU"` (`name="<activity>#OptionsMenu"`) for a widget that **either** has `directMop || transitiveMop` **or** has a WTG click-transition to a `hasMop` activity (gateway case). Scored against the `MODEL_MENU` action. Single biggest cryptoapp win: cryptoapp is menu-driven — the options menu is the gateway to every crypto sub-activity (`menu_item_message_digest` → `MessageDigestActivity`, `menu_item_cipher` → `CipherActivity`), and nothing boosts the menu-open action today (design D13).
3. **T1.3** `inputType`-aware fuzzing: domain-correct values for `textPassword` / `number` / `phone` / `textEmailAddress` / etc. (was: random text).
4. **T1.4** Component triggering rewrite: skip `reachesTarget=false`, skip `exported=false` activities, round-robin over `(component × intentFilter × action)` tuples (was: only `actions.get(0)`).
5. **T1.5** Provider triggering: `content query` over `authorities` URI when `reachesTarget=true` (was: providers excluded entirely).
6. **T1.6** `Listener.eventType` cross-ref: `MopScorer.score` only counts listeners whose `eventType` matches the candidate action's type (was: any listener handler boosts any action). Match-any fallback when JSON lacks `eventType`.
7. **T1.7** Package / mainActivity sanity check at load time. Optional rejection mode for CI; default is warn-only.

Tracked as GitHub issue #13. Per project principle **P3 (no backward compatibility)**, this change replaces the parser surface — there is no v1/v2 split, no `schemaVersion` consultation. Legacy JSONs are obsoleted by the gh57+gh60 ground-truth re-run.

## What Changes

### Parser — complete typed model under `MopData`

Every documented field in the post-gh60 JSON has a typed Java home. No silent drops of known fields. The parser reads the file once into an `org.json` DOM (`JSONObject`/`JSONArray`) rather than `android.util.JsonReader` — required so the §15.2/§21.6 bug-fix regression gate can actually execute under `mvn test` (`JsonReader` is excluded from the surefire classpath; design D21). Forward compat for *unknown* fields is automatic — unread keys are ignored.

- **Top-level**: `packageName`, `mainActivity`, `complete`.
- **`MopData.ReachabilityClass`**: `className`, `componentType`, `isMain`, `methods[]`.
- **`MopData.ReachabilityMethod`**: `name`, `signature`, `reachable`, `reachesTarget`, `directlyReachesTarget`.
- **`MopData.Window`**: `id`, `type`, `name`, `widgets[]`.
- **`MopData.Widget`** (replaces `WidgetMopFlags`): `id`, `idName`, `type`, `text`, `hint`, `inputType`, `entries[]`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `listeners[]`, plus **derived** (not emitted by gh60) `directMop`/`transitiveMop` and per-event-type maps `directMopByEventType`/`transitiveMopByEventType`. No `items[]` field — gh60 does not emit nested submenu items; OPTIONSMENU is a separate top-level `Window` with flat MenuItem widgets as siblings.
- **`MopData.Listener`**: `eventType`, `handler`, plus defensive nullable `Boolean handlerReachesTarget` / `Boolean handlerDirectlyReachesTarget` (gh60-C3 forward compat — NOT emitted by current gh60 producer).
- **`MopData.Transition`**: `sourceId`, `targetId`, `events[]`.
- **`MopData.TransitionEvent`**: `type`, `handler`, `widgetId`, `widgetClass`, `widgetName`.
- **`ComponentInfo` (extended)**: `className`, `componentType` (derived from the parent dict key in gh60 — `activities`/`receivers`/`services`/`providers`), `isMain`, `exported`, `intentFilters[]` (structured with `actions[]` AND `categories[]` AND `data`), `authorities` (providers), `reachesTarget` (read from JSON, not hardcoded), `targetMethods[]`, `permission` (gh60 D15; `hasPermissionGate()`). `ProviderInfo` adds `readPermission`/`writePermission` (gh60 D15).
- **`ComponentInfo.IntentFilter`**: `actions[]`, `categories[]`, `data` (gh60 D15 `DataSpec`: `schemes`/`hosts`/`ports`/`paths`/`pathPrefixes`/`pathPatterns`/`mimeTypes`; never null; `hasData()`).

### Parser — wire contract

- **Bug fix:** read the post-gh60 keys `reachesTarget` / `directlyReachesTarget` / `targetMethods` in `parseMethod` AND `parseComponentArray`. Legacy `*Mop` keys are deleted (P3); no dual recognition.
- Components dispatch by parent dict key: `components.activities → ComponentType.ACTIVITY`, `components.receivers → RECEIVER`, etc. The section name IS the type — there is no per-element `componentType` field in the JSON.
- Sentinel check: `"complete": true` mandatory; absent or `false` ⇒ `null` return + WARN.
- Listeners read `eventType` + `handler`; defensive read of `handlerReachesTarget` / `handlerDirectlyReachesTarget` as nullable `Boolean` (when gh60-C3 lands these take precedence over local cross-reference).
- Widget `directMop` / `transitiveMop` / per-event-type maps are derived locally: group `widget.listeners` by `eventType`, cross-reference each handler against `bySignature`, OR the bits into the per-event-type maps; aggregate booleans are the OR across all event types (match-any backward compat).
- No recursive `items[]` parsing — the field is not emitted by gh60. OPTIONSMENU widgets are flat siblings inside the OPTIONSMENU `Window`.

### Consumer wiring

**LLM (T1.1)** — `ApePromptBuilder` surfaces in widget descriptions, when non-null/non-empty: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `entries=[…]` (10-element cap), `inputType`, `hint`. 80-char per-field cap; newlines flattened.

**OPTIONSMENU boost (T1.2)** — new `MopScorer.scoreOpenMenu(activity, data)` returns `Config.mopWeightOpenMenu` (default 250) when an OPTIONSMENU `Window` with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` exists AND any widget in that window **either** has `directMop || transitiveMop` (derived from listener cross-reference) **or** has a WTG click-transition to a `hasMop` activity (gateway case — the pattern cryptoapp actually exhibits; design D13). Precomputed `Set<String> activitiesWithMopOptionsMenu` during `MopData.load` (after WTG transitions are built) — for each Window of type OPTIONSMENU, split `name` on `"#OptionsMenu"`, evaluate the two conditions over the flat widget list, add the prefix to the set on the first hit. `StatefulAgent` action-priority pass applies the boost to the `MODEL_MENU` action of the current state. Decoupled from the existing widget-level passes.

**Fuzzing (T1.3)** — new `ApeFuzzer.generateInputForType(String inputType, String hint, java.util.Random)` returning a domain-correct string. Mapping:

| `inputType` (Android constant or XML token) | Generator |
|---|---|
| `textPassword` / `numberPassword` / `textVisiblePassword` | random 8–12 char password with mixed case + digits + symbol |
| `number` / `numberSigned` / `numberDecimal` | random int / signed int / decimal |
| `phone` | `+55 11 9XXXX-XXXX` template |
| `textEmailAddress` | random local + `@example.com` |
| `textUri` | `https://example.com/<rand>` |
| `date` / `time` / `datetime` | ISO 8601 |
| anything else (including empty `inputType`) | fall back to current `RandomHelper.randomString()` |

The `hint` parameter is used to bias seed selection only when `inputType` is empty and `hint` matches a heuristic ("email" substring ⇒ email gen; "senha"/"password" ⇒ password gen; numeric-only chars ⇒ number gen). Hook lives in `MonkeySourceApe.generateEventsForAction` / `ApeFuzzer.injectInputText` path. New `Config.fuzzInputTyped` (default `true`); when false, ignore static `inputType` and use legacy generator.

**Component triggering (T1.4 + T1.5)** — rewrite `StatefulAgent.triggerMopComponent`:

- Build round-robin over **`(component × intentFilter × action)` tuples**, not over components alone. `componentTriggerIndex` becomes a tuple cursor.
- Skip any component with `reachesTarget=false`.
- Skip activities with `exported=false` (am start would fail or hit a permission error).
- For each tuple, build an `Intent` with `setAction(action)` and `addCategory(category)` for each category in the filter.
- **Provider branch**: when `componentType="provider"` and `authorities` non-null and `reachesTarget=true`, invoke `AndroidDevice.runShell("content query --uri content://" + authorities)` (and optionally `content insert` / `content update` in a round-robin sub-cycle). Capture exit code; log success/failure.
- **Activity branch (was: excluded)**: when `componentType="activity"` and `exported=true` and `reachesTarget=true`, send `am start -n <pkg>/<className>` via existing intent path with the filter's category set. Keep the "evidence: sandwichroulette -45pp" caveat as a tunable: new `Config.activityTriggerEnabled` (default `false`) gates this branch until calibrated.
- Per-trigger logging: `[APE-RV] Triggering <type>: <className> action=<a> categories=<c1,c2> reachesTarget=true`.

**`eventType` cross-ref (T1.6)** — `MopScorer.score(activity, shortId, data, candidateEventType)` overload:

- New `candidateEventType` parameter is the event type the candidate action represents: `"click"` for `MODEL_CLICK`, `"longClick"` for `MODEL_LONG_CLICK`, `"itemSelected"` for Spinner-like, `null` for unknown.
- When scoring, a listener contributes its `directMop`/`transitiveMop` to the widget's flags only if `listener.eventType` equals `candidateEventType` — OR if either side is null/empty (match-any fallback for legacy JSONs and unknown action types).
- The boolean flags on `MopData.Widget.directMop` / `transitiveMop` are now precomputed PER eventType: `Map<String, Boolean> directMopByEventType`. Convenience accessors `Widget.isDirectMop(eventType)` and `Widget.isTransitiveMop(eventType)`. The original boolean fields stay as "any-event-type" aggregate (backward compatible).
- The two existing call sites (`MopScorer.score` from `StatefulAgent` action-priority pass, and `ApePromptBuilder.buildMopMarker`) pass the candidate event type. Callers passing `null` get the legacy "any" behavior.

**Sanity check (T1.7)** — new `MopData.load(String path, String expectedPackage, String expectedMainActivity)` overload. When `expectedPackage` / `expectedMainActivity` non-null and diverge from the JSON's values, emit `WARN` log naming both pairs. Default behavior: warn only, still return parsed data. New `Config.mopStrictPackageMatch` (default `false`); when `true`, divergence ⇒ `null` return. The existing `MopData.load(path)` becomes a thin delegate to `load(path, null, null)`. Caller in `StatefulAgent` constructor passes the runtime package name (from `AndroidDevice.getCurrentPackage()` or similar) when available.

### Naming

`WidgetMopFlags` → `MopData.Widget` (mechanical rename). Top-level class names (`MopScorer`, `MopData`, `Config.mopDataPath`, `Config.mopWeight*`) preserved — the ape repo is a JavaMOP-targets consumer and Java-side semantic is preserved (D7 in `design.md`).

## Capabilities

### Modified Capabilities

- `mop-guidance`: extended with:
  - `MopData — Static Analysis JSON Loader` (complete-parser specification, every field typed)
  - `ApePromptBuilder — Widget Metadata in LLM Context` (T1.1)
  - `MopScorer — OPTIONSMENU-Aware Menu Boost` (T1.2, new)
  - `MopScorer — Event-Type-Aware Reachability Scoring` (T1.6, new)
  - `ApeFuzzer — Type-Aware Input Generation` (T1.3, new)
  - `StatefulAgent — Tuple-Based Component Triggering` (T1.4 + T1.5, new)
  - `MopData — Package / MainActivity Sanity Check` (T1.7, new)

## Impact

- `src/main/java/com/android/commands/monkey/ape/utils/MopData.java` — full rewrite (~800 LOC). Migrated from `android.util.JsonReader` to `org.json` DOM (single parse into a `JSONObject` root; design D21). New nested POJOs (`Window`, `Widget`, `Listener`, `Transition`, `TransitionEvent`, `ReachabilityClass`, `ReachabilityMethod`). Five logical navigation phases over the parsed root. Precomputed `activitiesWithMopOptionsMenu` set. Three-arg `load(path, expectedPackage, expectedMainActivity)` overload. **Carries the bug fix** (read `reachesTarget` / `directlyReachesTarget` keys in reachability-method and component parsing).
- `src/main/java/com/android/commands/monkey/ape/utils/ComponentInfo.java` — expanded fields + nested `IntentFilter`. Constructors updated; old `(className, List<String> actions)` constructor removed (P3).
- `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java` — `score(activity, shortId, data)` overload preserved + new `score(activity, shortId, data, eventType)`. New `scoreOpenMenu(activity, data)`. `scoreWtg` unchanged.
- `src/main/java/com/android/commands/monkey/ape/utils/Config.java` — 4 new flags: `mopWeightOpenMenu` (250), `fuzzInputTyped` (true), `mopStrictPackageMatch` (false), `activityTriggerEnabled` (false).
- `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java` — `triggerMopComponent` rewrite (~120 LOC delta). New hook in action-priority pass for `MODEL_MENU` boost. New `mopData.load` invocation passing runtime package name.
- `src/main/java/com/android/commands/monkey/ape/events/ApeFuzzer.java` — new `generateInputForType` static helper (~60 LOC). New per-type generators.
- `src/main/java/com/android/commands/monkey/MonkeySourceApe.java` — hook the typed generator into the text-injection path; minimal touch.
- `src/main/java/com/android/commands/monkey/ape/llm/ApePromptBuilder.java` — extended widget description emission (metadata + entries + inputType + hint).
- `src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java` / `agent/SataAgent.java` — mechanical `WidgetMopFlags` → `MopData.Widget` rename only.
- `src/test/` — new test classes / methods: `MopDataTest` (26), `ComponentInfoTest` (7), `MopScorerTest` extensions (8), `ApeFuzzerInputTypeTest` (10), `StatefulAgentTriggerTest` (9), `ApePromptBuilderTest` extensions (7). Total **+67 unit tests** on top of the existing ~145. Tests are tagged HIGH (6) / MED (11) / LOW (4) priority in `tasks.md`; HIGH covers the rollback-knob contracts (`fuzzInputTyped=false`, `activityTriggerEnabled=false`), the empty-list / empty-array corner cases (cryptoapp + complete-but-empty timeout JSONs), the OR-idempotent multi-listener case, and the filter-null component-name-only tuple path. Includes the **bug-fix regression test** `testWidgetTransitiveMopDerivedFromGh60Targets` — pins `Widget.transitiveMop=true` on `buttonGenerateHash` (would have caught the legacy-keys bug at CI time; cryptoapp's reachability is transitive, so `directMop` is pinned by synthetic §15.20).
- `src/test/resources/cryptoapp.apk.gh60-fresh.json` — primary fixture (pinned 2026-05-29 post gh60 task 11 hint/text fix). Pre-fix `cryptoapp.apk.gh60.json` retained as a second fixture for empty-string regression coverage.
- No build or dependency changes. `MopScorer.score` core logic semantics preserved when `candidateEventType=null` (backward compatible).
- Cross-repo coordination: `mvn install -Drvsec_home=<rvsec_root>` after merge refreshes `ape-rv.jar` into the aperv-tool module. Aperv smoke from `rvsec/rv-android` (gh57 task 9.5) validates end-to-end. Gating: gh57 AND gh60 must archive upstream before integration smoke.

## Out of Scope (deferred follow-ups)

- **T2.1** Spinner `entries[]` as 1-action-per-entry in action generation — touches `ModelAction` creation, much larger change.
- **T2.2** WTG multi-hop Dijkstra pathfinding (current `scoreWtg` is 1-hop only).
- **T2.3** Using `reachable=false` to de-prioritize dead-code handlers (needs calibration).
- **T2.4** Pre-emptive `TransitionEvent.handler` cross-ref before gh60-C3 emits it natively.
