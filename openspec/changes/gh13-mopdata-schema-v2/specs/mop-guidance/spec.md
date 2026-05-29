# Delta Specification: MOP-Guided Action Scoring

## Purpose

Adapts `MopData` and its consumers (`MopScorer`, `StatefulAgent`, `ApePromptBuilder`, `ApeFuzzer`) to the post-gh57+gh60 static-analysis JSON, exposing **every documented field** via a typed object graph AND **effectively using** the new information across seven Tier-1 sites: LLM widget metadata, OPTIONSMENU boost, type-aware fuzzing, tuple-based component triggering, provider triggering, event-type-aware scoring, package sanity check.

User mandates (2026-05-26):
- "todo campo documentado tem casa Java tipada, sem drop silencioso de campos conhecidos"
- "passar a usar realmente os dados de analise estatica no aperv"

Per project principle P3 (no backward compatibility), this delta replaces the parser shape — there is no v1/v2 branching, no `schemaVersion` consultation. Pre-gh57 and pre-gh60 JSONs are obsoleted.

Java-side top-level type names (`MopScorer`, `MopData`, `Config.mopDataPath`, `Config.mopWeight*`) are preserved deliberately — the ape repo is exclusively a JavaMOP-targets consumer. The single Java-side rename is `WidgetMopFlags` → `MopData.Widget` (the POJO grew from 2 fields to 14+).

## Invariants

- **INV-MOP-07**: `MopData.parseMethod` SHALL switch on the gh60 keys `directlyReachesTarget` and `reachesTarget`. The legacy gh57 keys `directlyReachesMop` / `reachesMop` SHALL NOT appear in the source switch (P3, no dual recognition). A regression test SHALL load a gh60 fixture and assert at least one widget reaches `directMop=true` via the derived cross-reference (proves the parser is not silently empty — the bug-fix contract).
- **INV-MOP-08**: `MopData.parseComponentArray` SHALL read `reachesTarget` and `targetMethods` per component (renamed from `reachesMop` / `mopMethods`); `ComponentInfo.reachesTarget` SHALL reflect the JSON value (not a hardcoded boolean as in the pre-change subclass constructors).
- **INV-MOP-09**: `MopData.load` SHALL refuse non-null return unless JSON root contains `"complete": true`.
- **INV-MOP-10**: `ApePromptBuilder` SHALL emit each widget metadata field and Spinner entries only when non-null/non-empty.
- **INV-MOP-11**: Every documented gh60 JSON field SHALL be captured into a typed POJO field. `default: skipValue()` SHALL survive only as forward-compat fall-through.
- **INV-MOP-12**: When a `Listener` carries non-null `handlerReachesTarget` / `handlerDirectlyReachesTarget` (gh60-C3 forward compat), that producer value SHALL take precedence over the local `bySignature` cross-reference. Until C3 emits them the fields SHALL be `null` on every listener and the local cross-reference is the single source of truth.
- **INV-MOP-13**: `MopScorer.scoreOpenMenu(activity, data)` SHALL be O(1) over a precomputed `Set<String> activitiesWithMopOptionsMenu` populated during `MopData.load` by scanning Windows of `type="OPTIONSMENU"` with `name="<activity>#OptionsMenu"` whose flat widget list contains at least one derived `directMop || transitiveMop` widget.
- **INV-MOP-14**: When a candidate event type is passed to `MopScorer.score`, only listeners whose `eventType` matches (or whose `eventType` is null/empty for match-any fallback) SHALL contribute to the widget's direct/transitive flags for that candidate.
- **INV-MOP-15**: `StatefulAgent.triggerMopComponent` SHALL skip components with `reachesTarget=false` and SHALL skip activities with `exported=false`. Activity triggering SHALL be gated by `Config.activityTriggerEnabled` (default `false`).
- **INV-MOP-16**: `ApeFuzzer.generateInputForType` SHALL produce domain-correct values for `textPassword`, `number`, `phone`, `textEmailAddress`, `textUri`, `date`/`time`/`datetime`. Unknown `inputType` SHALL fall back to the legacy random-string generator. `Config.fuzzInputTyped=false` SHALL bypass the entire typed path.
- **INV-MOP-17**: `MopData.Widget.directMop` / `transitiveMop` / `directMopByEventType` / `transitiveMopByEventType` SHALL be **derived** during `parseWindow` (the current gh60 producer does not emit them). Derivation SHALL group `widget.listeners` by `eventType` and cross-reference each `handler` against the `bySignature` index built from `reachability[].methods[]`.

---

## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` and `MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the post-gh57+gh60 static analysis JSON file and build a complete typed model:

1. **Top-level scalars**: `getPackageName()`, `getMainActivity()`, `isComplete()`.
2. **Reachability**: `getReachability()` returns immutable `List<ReachabilityClass>` with full per-class (`className`, `componentType`, `isMain`, `methods`) and per-method (`name`, `signature`, `reachable`, `reachesTarget`, `directlyReachesTarget`) fields.
3. **Windows**: `getWindows()` ordered list; `getWindow(int id)` lookup; each `Window` carries `id`, `type`, `name`, `widgets`.
4. **Widgets**: `getWidget(activity, shortId)` returns `Widget` carrying read-from-JSON `id`, `idName`, `type`, `text`, `hint`, `inputType`, `entries`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `listeners`, plus **derived** `directMop`/`transitiveMop` and per-event-type maps `directMopByEventType`/`transitiveMopByEventType` (INV-MOP-17). No `items` field — gh60 does not emit nested widgets.
5. **Listeners**: each carries `eventType`, `handler`, plus nullable forward-compat `handlerReachesTarget` / `handlerDirectlyReachesTarget` (null on every listener until gh60-C3 lands).
6. **Transitions**: `getTransitions()` carries full per-event fields including `handler` and `widgetId`.
7. **Components**: each `ComponentInfo` carries `className`, `componentType` (derived from JSON parent dict key — `activities`/`receivers`/`services`/`providers`), `isMain`, `exported`, `intentFilters` (structured with `actions` AND `categories`), `reachesTarget` (read from JSON, not hardcoded), `targetMethods`. `ProviderInfo` additionally carries `authorities`.
8. **Sentinel**: top-level `"complete": true` mandatory (INV-MOP-09).
9. **Precomputed OPTIONSMENU set**: `activityHasMopOptionsMenu(activity)` returns true iff a `Window` with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` exists in `getWindows()` with at least one widget having derived `directMop || transitiveMop` (INV-MOP-13). OPTIONSMENU widgets are flat siblings of the Window's `widgets[]` — no nested items.
10. **Sanity check**: when `expectedPackage` / `expectedMainActivity` non-null and diverge from parsed values, emit WARN log. `Config.mopStrictPackageMatch=true` makes mismatch ⇒ `null` return.

Cross-referencing for widgets matches `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`. Per-event-type maps are populated by grouping listeners by `eventType` during the cross-reference pass; aggregate `directMop`/`transitiveMop` are the OR across all event types (backward compat). When `Listener.handlerReachesTarget` is non-null, the producer value takes precedence over the cross-reference (INV-MOP-12).

`default: skipValue()` survives only for unknown new fields (INV-MOP-11).

[The full set of MopData scenarios from the prior version of this spec is preserved; new scenarios appended below.]

#### Scenario: Real cryptoapp fixture loads every typed field
- **WHEN** `MopData.load()` is called on `src/test/resources/cryptoapp.apk.gh60-fresh.json`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getPackageName()=="br.unb.cic.cryptoapp"`, `getMainActivity()=="br.unb.cic.cryptoapp.MainActivity"`, `isComplete()==true`
- **AND** `getReachability().size()==16`; methods totals SHALL be `reachable=55`, `reachesTarget=32`, `directlyReachesTarget=21`; at least one class with `isMain==true`
- **AND** `getWindows().size()==5` — 4 with `type=="ACTIVITY"` and 1 with `type=="OPTIONSMENU"` named `"br.unb.cic.cryptoapp.MainActivity#OptionsMenu"` carrying 3 flat `android.view.MenuItem` widgets
- **AND** at least one `Widget` has `entries.size()==13` (the `spinnerMessageDigest`)
- **AND** `getTransitions().size()==35`; at least one `TransitionEvent` has non-empty `handler` and `widgetId>0`
- **AND** `getActivities().size()==4`; `getProviders().size()==1` with `authorities=="br.unb.cic.cryptoapp.androidx-startup"`; `getReceivers().isEmpty()`; `getServices().isEmpty()`
- **AND** every component SHALL have `reachesTarget==false` (cryptoapp's reachability is GUI-only, by design — the smoke test for non-zero component triggering needs a different fixture)
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`

#### Scenario: Bug-fix regression — widget directMop derived from gh60 Target keys
- **WHEN** `MopData.load()` is called on `src/test/resources/cryptoapp.apk.gh60-fresh.json` (pinned 2026-05-29)
- **THEN** the OPTIONSMENU `Widget` with `idName=="menu_item_message_digest"` SHALL have exactly one `Listener` with `eventType=="click"` and `handler=="<br.unb.cic.cryptoapp.MainActivity$1: boolean onMenuItemClick(android.view.MenuItem)>"`
- **AND** that handler signature SHALL be present in `getReachability()` with `directlyReachesTarget==true`
- **AND** the widget SHALL have `directMop==true` and `isDirectMop("click")==true` (derived during Pass 2 cross-reference)
- **AND** `activityHasMop("br.unb.cic.cryptoapp.MainActivity")==true`
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **AND** `MopScorer.score("br.unb.cic.cryptoapp.MainActivity", "menu_item_message_digest", data, "click")` SHALL return `Config.mopWeightDirect`
- **NOTE**: pre-fix (legacy `directlyReachesMop`/`reachesMop` switch cases) the same assertions ALL fail — `bySignature` is empty, `directMop=false` everywhere, score returns 0. This scenario IS the contract that "SATA-MOP is not silently bare APE."

#### Scenario: Widget metadata extracted on post-task-11 fixture
- **WHEN** `MopData.load()` is called on `src/test/resources/cryptoapp.apk.gh60-fresh.json`
- **THEN** `getWidget("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "editTextMessageDigest")` SHALL have `type=="android.widget.EditText"`, `inputType=="textPersonName"`, `hint=="Input text ..."`
- **AND** the count of widgets with non-empty `hint` SHALL be ≥4; non-empty `text` ≥11; non-empty `inputType` ≥4 (empirical floor)
- **AND** the Spinner widget `spinnerMessageDigest` SHALL have `entries.size()==13` covering the JCA algorithm list

#### Scenario: Top-level package and mainActivity sanity check (default warn-only)
- **WHEN** `MopData.load(path, "x.y.z.OTHER", null)` is called and the JSON's `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** a `WARN` log line SHALL be emitted naming both the expected and parsed package values

#### Scenario: Package mismatch rejected in strict mode
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on a JSON with `package="x.y.z"`
- **THEN** `MopData.load` SHALL return `null`
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: OPTIONSMENU window with MOP widget triggers activityHasMopOptionsMenu
- **WHEN** an OPTIONSMENU window has `name="com.x.A#OptionsMenu"` and contains a widget with `directMop=true`
- **THEN** `activityHasMopOptionsMenu("com.x.A")` SHALL return `true`

#### Scenario: OPTIONSMENU window without MOP widget does not trigger
- **WHEN** an OPTIONSMENU window has `name="com.x.B#OptionsMenu"` and all its widgets have `directMop=false && transitiveMop=false`
- **THEN** `activityHasMopOptionsMenu("com.x.B")` SHALL return `false`

#### Scenario: Per-event-type reachability maps built
- **WHEN** a widget has two listeners: `{eventType:"click", handler:"<sigA>"}` and `{eventType:"longClick", handler:"<sigB>"}`, and only `<sigA>` is in `reachability[]` with `directlyReachesTarget=true`
- **THEN** `widget.isDirectMop("click")` SHALL be `true`
- **AND** `widget.isDirectMop("longClick")` SHALL be `false`
- **AND** `widget.directMop` (aggregate) SHALL be `true`
- **AND** `widget.isDirectMop(null)` SHALL be `true` (match-any fallback)

[Other existing scenarios — post-gh60 target keys, sentinel paths, widget metadata captures, transition events, implicit events, component fields, file missing, null path, malformed JSON, unknown future fields — preserved from prior version. Scenarios about recursive `items[]` and depth cap are REMOVED — gh60 does not emit nested widgets.]

---

## ADDED Requirements

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

`StatefulAgent`'s action-priority pass SHALL apply this boost to the `MODEL_MENU` action of the current state when `_mopData != null`.

#### Scenario: Boost applied when OPTIONSMENU has MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.A")==true` AND `MopScorer.scoreOpenMenu("com.x.A", data)` is called
- **THEN** the return value SHALL equal `Config.mopWeightOpenMenu`

#### Scenario: Zero when no OPTIONSMENU MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.B")==false`
- **THEN** `MopScorer.scoreOpenMenu("com.x.B", data)` SHALL return `0`

#### Scenario: Action-priority pass boosts MODEL_MENU when activity has MOP options menu
- **WHEN** `StatefulAgent`'s action-priority pass runs on a state whose activity is in `activitiesWithMopOptionsMenu`
- **THEN** the `MODEL_MENU` action's priority SHALL be incremented by `Config.mopWeightOpenMenu`
- **AND** a `Logger.iformat` line SHALL summarize the boost

---

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

---

### Requirement: MopData — Package / MainActivity Sanity Check

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL compare the parsed `package` / `mainActivity` against the optional expected values. When `expectedPackage` non-null and not equal to the parsed value, OR `expectedMainActivity` non-null and not equal to the parsed value, a `WARN` log line naming both pairs SHALL be emitted. When `Config.mopStrictPackageMatch=true` and either comparison mismatches, `MopData.load` SHALL return `null`. Default behavior (`mopStrictPackageMatch=false`) is warn-only — the parsed `MopData` is still returned.

The single-argument `MopData.load(path)` SHALL delegate to `load(path, null, null)` (no sanity check).

#### Scenario: Default warn-only on mismatch
- **WHEN** `Config.mopStrictPackageMatch=false` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on a JSON with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Strict-mode rejection on mismatch
- **WHEN** `Config.mopStrictPackageMatch=true` AND `MopData.load(path, "x.y.z.OTHER", null)` is called on a JSON with `package="x.y.z"`
- **THEN** the returned `MopData` SHALL be `null`
- **AND** a `WARN` log line SHALL be emitted

#### Scenario: Null expected values bypass the check
- **WHEN** `MopData.load(path, null, null)` is called
- **THEN** no sanity-check WARN log SHALL be emitted (regardless of `mopStrictPackageMatch`)
- **AND** the parsed `MopData` SHALL be returned

#### Scenario: Single-argument load delegates
- **WHEN** `MopData.load(path)` is called
- **THEN** the behavior SHALL be identical to `MopData.load(path, null, null)`
