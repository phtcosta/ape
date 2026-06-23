# Specification: MOP-Guided Action Scoring

## Purpose

MOP guidance integrates the static analysis JSON produced by the rv-android pipeline into APE's action priority system. When `ape.mopDataPath` points to a valid JSON file on the device, `MopData` parses the file and `MopScorer` assigns priority boosts to widget actions that reach monitored operations (MOP specs). The boost is applied in `StatefulAgent.adjustActionsByGUITree()` — the designated extension point — after the base SATA priority is assigned and before the agent's selection step. When `ape.mopDataPath` is absent or the file is missing, the system operates identically to plain `sata` with no change to priority values.

The JSON contains `windows[]`, `reachability[]`, `transitions[]`, and `components{}` sections. Cross-referencing widget listeners with MOP-reachable methods identifies which widgets, when interacted with, trigger spec-monitored operations. The `components{}` section provides data on all Android components (Activities, Services, BroadcastReceivers, ContentProviders) with their intent-filters, exported status, and MOP reachability, enabling component triggering during exploration. This allows `aperv:sata_mop` to steer exploration toward security-relevant code paths and exercise non-GUI components.

---
## Requirements
### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` and `MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the post-gh57+gh60 static analysis JSON file and build a complete typed model:

1. **Top-level scalars**: `getPackageName()`, `getMainActivity()`, `isComplete()`.
2. **Reachability**: `getReachability()` returns immutable `List<ReachabilityClass>` with full per-class (`className`, `componentType`, `isMain`, `methods`) and per-method (`name`, `signature`, `reachable`, `reachesTarget`, `directlyReachesTarget`) fields.
3. **Windows**: `getWindows()` ordered list; `getWindow(int id)` lookup; each `Window` carries `id`, `type`, `name`, `widgets`.
4. **Widgets**: `getWidget(activity, shortId)` returns `Widget` carrying read-from-JSON `id`, `idName`, `type`, `text`, `hint`, `inputType`, `entries`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `listeners`, plus **derived** `directMop`/`transitiveMop` and per-event-type maps `directMopByEventType`/`transitiveMopByEventType` (INV-MOP-17). No `items` field — gh60 does not emit nested widgets.
5. **Listeners**: each carries `eventType`, `handler`, plus nullable forward-compat `handlerReachesTarget` / `handlerDirectlyReachesTarget` (null on every listener until gh60-C3 lands).
6. **Transitions**: `getTransitions()` carries full per-event fields including `handler` and `widgetId`.
7. **Components**: each `ComponentInfo` carries `className`, `componentType` (derived from JSON parent dict key — `activities`/`receivers`/`services`/`providers`), `isMain`, `exported`, `intentFilters` (structured with `actions` AND `categories` AND a gh60-D15 `data` block — `schemes`/`hosts`/`ports`/`paths`/`pathPrefixes`/`pathPatterns`/`mimeTypes`), `reachesTarget` (read from JSON, not hardcoded), `targetMethods`, and `permission` (gh60-D15, null when no gate). `ProviderInfo` additionally carries `authorities` plus `readPermission`/`writePermission` (gh60-D15). See INV-MOP-18.
8. **Sentinel**: top-level `"complete": true` mandatory (INV-MOP-09).
9. **Precomputed OPTIONSMENU set**: `activityHasMopOptionsMenu(activity)` returns true iff a `Window` with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` exists in `getWindows()` containing at least one widget that **either** has derived `directMop || transitiveMop` **or** has a WTG click-transition to a `hasMop` activity (gateway case; INV-MOP-13). OPTIONSMENU widgets are flat siblings of the Window's `widgets[]` — no nested items.
10. **Sanity check**: when `expectedPackage` / `expectedMainActivity` non-null and diverge from parsed values, emit WARN log. `Config.mopStrictPackageMatch=true` makes mismatch ⇒ `null` return.

Cross-referencing for widgets matches `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`. Per-event-type maps are populated by grouping listeners by `eventType` during the cross-reference pass; aggregate `directMop`/`transitiveMop` are the OR across all event types (backward compat). When `Listener.handlerReachesTarget` is non-null, the producer value takes precedence over the cross-reference (INV-MOP-12).

Unknown JSON keys are ignored for forward compatibility (INV-MOP-11); the parser reads the file once into an `org.json` DOM (design D21).

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
- **AND** the only widgets with derived MOP SHALL be `buttonGenerateHash` (in `MessageDigestActivity`) and `btn_cipher_encrypt` (in `CipherActivity`), both `transitiveMop==true` and `directMop==false`; **no** widget SHALL have `directMop==true`
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true` AND `activityHasMop("br.unb.cic.cryptoapp.cipher.CipherActivity")==true`
- **AND** `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true` (gateway: the `MainActivity#OptionsMenu` items `menu_item_message_digest` / `menu_item_cipher` carry WTG click-transitions to those MOP sub-activities, INV-MOP-13)
- **AND** (gh60 D15) every component's `permission` SHALL be null (cryptoapp declares no `android:permission`); each `IntentFilter.data` SHALL be non-null (empty for cryptoapp's launcher filters); the provider's `readPermission`/`writePermission` SHALL be null

#### Scenario: gh60 D15 component trigger-surface fields parsed
- **WHEN** `MopData.load()` parses a component whose intent filter declares a `data` block with `schemes`/`mimeTypes` and whose element declares `permission` (and, for a provider, `readPermission`/`writePermission`)
- **THEN** `IntentFilter.data.schemes` / `.mimeTypes` (and the other five lists) SHALL reflect the JSON values and `IntentFilter.hasData()` SHALL be true
- **AND** `ComponentInfo.permission` SHALL reflect the JSON value and `hasPermissionGate()` SHALL be true; absent ⇒ null and false
- **AND** `ProviderInfo.readPermission` / `writePermission` SHALL reflect the JSON values (null when absent)
- **AND** when the `data` block is absent, `IntentFilter.data` SHALL be the empty `DataSpec` (never null) and `hasData()` SHALL be false (back-compat with pre-D15 JSON)
- **AND** trigger *selection* (INV-MOP-15) SHALL be unchanged by these fields (parsed-and-exposed only)

#### Scenario: Bug-fix regression — widget transitiveMop derived from gh60 Target keys
- **WHEN** `MopData.load()` is called on `src/test/resources/cryptoapp.apk.gh60-fresh.json` (pinned 2026-05-29)
- **THEN** the `Widget` with `idName=="buttonGenerateHash"` in `MessageDigestActivity` SHALL have a `click` `Listener` whose `handler` signature is present in `getReachability()` with `reachesTarget==true` (and `directlyReachesTarget==false`)
- **AND** the widget SHALL have `transitiveMop==true`, `directMop==false`, and `isTransitiveMop("click")==true` (derived during the widget cross-reference)
- **AND** `activityHasMop("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity")==true`
- **AND** `MopScorer.score("br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "buttonGenerateHash", data, "click")` SHALL return `Config.mopWeightTransitive`
- **AND** the gateway holds: `activityHasMopOptionsMenu("br.unb.cic.cryptoapp.MainActivity")==true`
- **NOTE**: pre-fix (legacy `directlyReachesMop`/`reachesMop` keys) the same assertions ALL fail — `bySignature` is empty, every widget flag is `false`, score returns 0, and the gateway set is empty. This scenario IS the contract that "SATA-MOP is not silently bare APE." cryptoapp's reachability is transitive (menu/button handlers call JCA helpers); no listener handler is itself a direct JCA caller, so `directMop` is demonstrated by the synthetic per-event-type scenarios, not this fixture.

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
- **WHEN** an OPTIONSMENU window has `name="com.x.B#OptionsMenu"` and all its widgets have `directMop=false && transitiveMop=false` AND no widget has a WTG click-transition to a `hasMop` activity
- **THEN** `activityHasMopOptionsMenu("com.x.B")` SHALL return `false`

#### Scenario: OPTIONSMENU gateway — menu item navigates to a MOP activity
- **WHEN** an OPTIONSMENU window `name="com.x.C#OptionsMenu"` has a widget `menu_go` with `directMop=false && transitiveMop=false` whose WTG click-transition targets `com.x.CryptoActivity`, and `activityHasMop("com.x.CryptoActivity")==true`
- **THEN** `activityHasMopOptionsMenu("com.x.C")` SHALL return `true` (gateway case, INV-MOP-13)

#### Scenario: Per-event-type reachability maps built
- **WHEN** a widget has two listeners: `{eventType:"click", handler:"<sigA>"}` and `{eventType:"longClick", handler:"<sigB>"}`, and only `<sigA>` is in `reachability[]` with `directlyReachesTarget=true`
- **THEN** `widget.isDirectMop("click")` SHALL be `true`
- **AND** `widget.isDirectMop("longClick")` SHALL be `false`
- **AND** `widget.directMop` (aggregate) SHALL be `true`
- **AND** `widget.isDirectMop(null)` SHALL be `true` (match-any fallback)

#### Scenario: Multiple listeners to the same handler do not double-count
- **WHEN** a widget has two `click` listeners both pointing to the same `handler==<sigA>`, and `<sigA>` is in `reachability[]` with `directlyReachesTarget=true`
- **THEN** `widget.directMopByEventType.get("click")` SHALL be `true` (OR-idempotent — no boolean overflow, no double-boost)
- **AND** `widget.listeners.size()` SHALL be `2` (listeners preserved as parsed, no dedup)
- **AND** `MopScorer.score(act, id, data, "click")` SHALL return `Config.mopWeightDirect` exactly (not 2×)

#### Scenario: Complete-but-empty JSON parses cleanly (gh51-D5 timeout bucket)
- **WHEN** `MopData.load()` is called on a fixture with `complete:true` AND empty `reachability[]`, `windows[]`, `transitions[]`, `components.{activities,receivers,services,providers}=[]`
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `isComplete()` SHALL be `true`
- **AND** all accessor lists SHALL return empty (`getReachability().isEmpty()`, `getWindows().isEmpty()`, `getTransitions().isEmpty()`, `getReceivers().isEmpty()`, `getServices().isEmpty()`, `getActivities().isEmpty()`, `getProviders().isEmpty()`)
- **AND** `MopScorer.score(any, any, data, any)` SHALL return `0` without `NullPointerException`

[Other existing scenarios — post-gh60 target keys, sentinel paths, widget metadata captures, transition events, implicit events, component fields, file missing, null path, malformed JSON, unknown future fields — preserved from prior version. Scenarios about recursive `items[]` and depth cap are REMOVED — gh60 does not emit nested widgets.]

---

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data)` SHALL return an integer priority boost according to the following scale. The boost is additive: it is added to the existing `ModelAction.priority`, never replacing it.

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `+mopWeightDirect` (500) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `+mopWeightTransitive` (300) |
| widget is `null` OR resolves with all MOP flags false, AND `data.activityHasMop(activity) == true` | `+mopWeightActivity` (100) |
| no widget MOP flag AND `data.activityHasMop(activity) == false` | `0` |

The activity-level fallback SHALL be evaluated **after** the widget flags and SHALL be reachable for a resolved-but-unflagged widget (B4 fix at `MopScorer.java:48` vs `:50-51`). The early `return 0` for a resolved-but-unflagged widget is removed.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `data.getWidget(...)` returns flags `{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns flags `{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget falls back to activity
- **WHEN** `data.getWidget(activity, shortId)` returns a non-null widget with `directMop=false` AND `transitiveMop=false`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `mopWeightActivity` (100)
- **AND** the scorer SHALL NOT return `0` before the activity check

#### Scenario: Widget null falls back to activity
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `mopWeightActivity` (100)

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
- **AND** the scorer SHALL fall through to the activity-level check or `0`

---

### Requirement: eventType normalization in the consumer

The scorer SHALL normalize `eventType` tokens to a single canonical form before comparison so that a producer `snake_case` token (e.g., `long_click`, `item_selected`) and the consumer `camelCase` token (e.g., `longClick`, `itemSelected`) for the same event compare equal (B6). Normalization SHALL be performed in the consumer (`MopData`/`MopScorer`); the producer and the gh13 JSON schema are NOT changed.

#### Scenario: snake_case producer token matches camelCase consumer token
- **WHEN** the JSON listener `eventType` is `long_click`
- **AND** the consumer compares it against `longClick`
- **THEN** the two SHALL compare equal after normalization

---

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath")`. The default value is `null`. When set (via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties`), it points to the static analysis JSON file path on the device.

`Config.java` SHALL also declare the following MOP weight fields with defaults matching the MopScorer boost table:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |
| `ape.mopWeightActivity` | int | `100` | Boost for activity-level MOP fallback |

These weights are configurable via `ape.properties` but the hardcoded defaults SHALL be 500/300/100 (v1 values).

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.mopDataPath`
- **THEN** `Config.mopDataPath` SHALL be `null`
- **AND** `StatefulAgent` SHALL initialise `_mopData` to `null`, disabling MOP scoring

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json`
- **THEN** `Config.mopDataPath` SHALL equal `"/data/local/tmp/static_analysis.json"`

#### Scenario: Default MOP weights

- **WHEN** `ape.properties` does not contain any `ape.mopWeight*` keys
- **THEN** `Config.mopWeightDirect` SHALL equal `500`
- **AND** `Config.mopWeightTransitive` SHALL equal `300`
- **AND** `Config.mopWeightActivity` SHALL equal `100`

#### Scenario: Custom MOP weights override

- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `Config.mopWeightDirect` SHALL equal `200`
- **AND** `Config.mopWeightTransitive` and `Config.mopWeightActivity` SHALL retain their defaults (`300` and `100`)

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

## Invariants

- **INV-MOP-01**: `MopData.load()` SHALL never throw a checked or unchecked exception to the caller. All I/O and parse errors SHALL be caught internally and result in a `null` return with a WARNING log.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `Config.mopDataPath` is `null`, the MOP scoring pass SHALL be skipped entirely. The `sata` variant's behaviour SHALL be identical with and without `MopData.java` present in the JAR.
- **INV-MOP-05**: The WTG scoring pass SHALL execute AFTER the existing MOP scoring pass in `adjustActionsByGUITree()`. Pass order: base priority -> unvisited bonus -> state transition bonus -> MOP boost -> WTG boost -> coverage boost.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0.
- **INV-MOP-07**: The activity-level fallback (`+mopWeightActivity`) SHALL be reachable both when widget resolution returns `null` AND when it returns a widget whose MOP flags are all false. A resolved-but-unflagged widget SHALL NOT short-circuit to `0` before the activity check.
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.

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

### Error
- No exceptions propagate from `MopData` or `MopScorer` to callers
