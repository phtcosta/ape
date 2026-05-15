# Delta Specification: MOP-Guided Action Scoring

## Purpose

Extends `MopData` to parse the post-gh57 widget shape produced by `rvsec/rv-android`'s `RvsecAnalysisClient`. Two parser-side concerns are addressed: four new widget XML attributes (`prompt`, `spinnerMode`, `contentDescription`, `tooltipText`) are surfaced on `WidgetMopFlags`, and `parseWidget` recurses into a new optional `items[]` array so that OPTIONSMENU submenu items merge into the enclosing window's widget map and their listeners participate in MOP-reachability cross-referencing.

Per project principle P3, this delta replaces the parser shape — there is no v1/v2 branching, no `schemaVersion` consultation. Pre-gh57 JSON files are obsoleted by the gh57 ground-truth re-run.

`MopScorer` is unaffected: it queries `(activity, shortId)` and treats every widget uniformly regardless of whether it originated from a layout XML, an inflated menu, or a programmatically-built submenu.

## Invariants

- **INV-MOP-07**: `parseWidget` recursion via `items[]` SHALL be bounded by a depth of 8. On overflow, `MopData.load` SHALL return `null` rather than propagating a `StackOverflowError`.
- **INV-MOP-08**: Submenu widget entries SHALL be stored in the same `widgets` map as their parent menu items, keyed by `idName`, with no parent/child reference retained.

---

## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build:
1. An in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`, plus the four widget metadata attributes below).
2. Lists of `ReceiverInfo`, `ServiceInfo`, `ActivityInfo`, `ProviderInfo` objects parsed from `components{}`.

Cross-referencing for widgets is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`. This cross-reference SHALL operate identically on widgets discovered at the top level of `windows[i].widgets[]` and on widgets discovered transitively under `widgets[j].items[]` (recursive submenu items).

For each widget object, `parseWidget` SHALL read four optional string attributes — `prompt`, `spinnerMode`, `contentDescription`, `tooltipText` — and store them on `WidgetMopFlags`. When a field is absent or its JSON value is `null`, the corresponding `WidgetMopFlags` field SHALL be `null`.

For widget objects carrying an optional `items[]` array (OPTIONSMENU submenu items), `parseWidget` SHALL recurse for each element, sharing the same `widgets` map of the enclosing window. Recursion depth SHALL be bounded by 8 (INV-MOP-07); overflow MUST cause `MopData.load` to return `null` with a `WARN` log line.

For components, `MopData` SHALL parse all four arrays in `components{}` (`activities[]`, `receivers[]`, `services[]`, `providers[]`), retaining ALL entries (not filtered by `reachesMop`). Intent-filter actions SHALL be extracted for receivers/services/activities; authorities for providers.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`.

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed.

The `components{}` section is optional. If absent, `getReceivers()`/`getServices()`/`getActivities()`/`getProviders()` SHALL return empty lists and `hasComponents()` SHALL return `false`.

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a `WidgetMopFlags` with `directMop=true` if the widget's handler appears in `reachability[]` with `directlyReachesMop=true`

#### Scenario: Widget XML attributes parsed onto WidgetMopFlags
- **WHEN** `MopData.load()` is called and a widget object contains `"prompt": "Choose cipher"`, `"spinnerMode": "dropdown"`, `"contentDescription": "Cipher selector"`, `"tooltipText": null`
- **THEN** the returned `WidgetMopFlags.prompt` SHALL equal `"Choose cipher"`
- **AND** `WidgetMopFlags.spinnerMode` SHALL equal `"dropdown"`
- **AND** `WidgetMopFlags.contentDescription` SHALL equal `"Cipher selector"`
- **AND** `WidgetMopFlags.tooltipText` SHALL be `null`

#### Scenario: Widget XML attributes absent
- **WHEN** `MopData.load()` is called and a widget object omits all four of `prompt` / `spinnerMode` / `contentDescription` / `tooltipText`
- **THEN** the returned `WidgetMopFlags` SHALL have all four fields equal to `null`

#### Scenario: OPTIONSMENU submenu items flattened into enclosing window
- **WHEN** `MopData.load()` is called and an OPTIONSMENU window contains a widget `{"idName": "menu_settings", "listeners": [...], "items": [{"idName": "menu_pref_a", "listeners": [...]}, {"idName": "menu_pref_b", "listeners": [...]}]}`
- **THEN** `getWidget(activity, "menu_settings")` SHALL return a non-null `WidgetMopFlags`
- **AND** `getWidget(activity, "menu_pref_a")` SHALL return a non-null `WidgetMopFlags`
- **AND** `getWidget(activity, "menu_pref_b")` SHALL return a non-null `WidgetMopFlags`

#### Scenario: Submenu handler participates in MOP cross-reference
- **WHEN** a submenu widget `{"idName": "menu_pref_a", "listeners": [{"handler": "void com.example.Settings.openPrefs(android.view.MenuItem)"}]}` is nested under `items[]` of a top-level menu item
- **AND** the `reachability[]` section lists `"void com.example.Settings.openPrefs(android.view.MenuItem)"` with `directlyReachesMop=true`
- **THEN** `getWidget(activity, "menu_pref_a").directMop` SHALL be `true`

#### Scenario: Recursive items[] depth cap exceeded
- **WHEN** `MopData.load()` is called and a widget chain nests `items[]` to depth 9 or deeper
- **THEN** `MopData.load` SHALL return `null`
- **AND** a `WARN` log line SHALL be emitted under tag `MopData`

#### Scenario: JSON with components section
- **WHEN** `MopData.load()` is called and the JSON contains `components.receivers` with entry `{"className": "com.example.BootReceiver", "reachesMop": true, "intentFilters": [{"actions": ["android.intent.action.BOOT_COMPLETED"]}]}`
- **THEN** `getReceivers()` SHALL return a list containing a `ReceiverInfo` for `com.example.BootReceiver`
- **AND** `ReceiverInfo.getActions()` SHALL contain `"android.intent.action.BOOT_COMPLETED"`
- **AND** `hasComponents()` SHALL return `true`

#### Scenario: JSON without components section
- **WHEN** `MopData.load()` is called and the JSON does not contain a `components` key
- **THEN** `getReceivers()` SHALL return an empty list
- **AND** `getServices()` SHALL return an empty list
- **AND** `hasComponents()` SHALL return `false`

#### Scenario: File missing — graceful null return
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file does not exist
- **THEN** `null` SHALL be returned

#### Scenario: path is null
- **WHEN** `MopData.load(null)` is called
- **THEN** `null` SHALL be returned immediately without attempting file I/O
