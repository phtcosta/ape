## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build:
1. An in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`).
2. Lists of `ReceiverInfo`, `ServiceInfo`, `ActivityInfo`, `ProviderInfo` objects parsed from `components{}`.

Cross-referencing for widgets is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`.

The widget map is keyed by base activity name and short widget resource ID. When two or more widgets within the same base activity resolve to the same short id, the map SHALL retain the widget with the strongest MOP flag — `directMop` ranks above `transitiveMop`, which ranks above unflagged — and SHALL NOT overwrite an already-stored flagged widget with an unflagged widget (INV-MOP-09). This replaces the prior last-write-wins behavior, under which an unflagged sibling could overwrite a flagged widget and silently demote its `+500`/`+300` boost to the activity-level fallback.

Widgets whose short id is empty SHALL NOT be stored in the map (INV-MOP-10). The empty-string key is unreachable at runtime because `extractShortId(GUITreeNode.getResourceID())` never yields an empty id for a widget that carries a resource id; storing empty-id widgets only collapses them into a single unreachable bucket. The number of MOP-flagged widgets dropped for lacking a resource id SHALL be counted during parsing and logged once per load (a single `[APE-RV] MopData` line). Matching such widgets by class/text/bounds is out of scope.

For components, `MopData` SHALL parse all four arrays in `components{}` (`activities[]`, `receivers[]`, `services[]`, `providers[]`), retaining ALL entries (not filtered by `reachesMop`). Intent-filter actions SHALL be extracted for receivers/services/activities; authorities for providers.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`.

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed.

The `components{}` section is optional for backward compatibility. If absent, `MopData` SHALL behave identically to the previous version (empty receiver/service lists, no error).

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a widget with `directMop=true` if the widget's handler appears in `reachability[]` as a `directlyReachesTarget` method

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** one base activity contains two widgets with short id `"submit"`, the first with `directMop=true` and the second (a later window/fragment) unflagged
- **THEN** `getWidget(activity, "submit")` SHALL return the `directMop=true` widget
- **AND** the unflagged widget SHALL NOT overwrite it

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the unflagged widget with short id `"submit"` is parsed first and the `directMop=true` widget second
- **THEN** `getWidget(activity, "submit")` SHALL return the `directMop=true` widget (stronger flag wins on order-independent comparison)

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity contains a widget whose `idName` is the empty string and which is MOP-flagged
- **THEN** the widget map for that activity SHALL NOT contain an entry under the empty-string key
- **AND** the per-load flagged-no-id drop count SHALL be incremented by one
- **AND** `activityHasMop(activity)` SHALL still return `true` (the activity-level association is unaffected)

#### Scenario: JSON with components section
- **WHEN** `MopData.load()` is called and the JSON contains `components.receivers` with entry `{"className": "com.example.BootReceiver", "reachesMop": true, "intentFilters": [{"actions": ["android.intent.action.BOOT_COMPLETED"]}]}`
- **THEN** `getReceivers()` SHALL return a list containing a `ReceiverInfo` for `com.example.BootReceiver`
- **AND** `ReceiverInfo.getActions()` SHALL contain `"android.intent.action.BOOT_COMPLETED"`
- **AND** `hasComponents()` SHALL return `true`

#### Scenario: JSON without components section (backward compatibility)
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
