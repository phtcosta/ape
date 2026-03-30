# Delta Specification: MOP-Guided Action Scoring

## Purpose

Extends `MopData` to parse the new `components[]` section from the static analysis JSON. Service and BroadcastReceiver classes listed in `components[]` have their methods in `reachability[]` — cross-referencing these provides component-level MOP reachability that complements the existing widget-level scoring.

---

## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build an in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`). Cross-referencing is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`.

Additionally, `MopData.load()` SHALL parse the `components[]` section (if present) and build a set of component class names with MOP-reachable lifecycle methods. A component has MOP reachability if any of its lifecycle methods (`onStartCommand`, `onBind`, `onReceive`, etc.) appear in `reachability[]` with `reachesMop=true` or `directlyReachesMop=true`.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`. The transform is: if `resourceId` contains `":id/"`, take the substring after `":id/"`. If `resourceId` is null or does not contain `":id/"`, use an empty string as key (activity-level fallback applies).

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed. In the last case it SHALL log a WARNING with the parse error details. This ensures graceful degradation to plain `sata` behaviour when MOP data is unavailable.

The `components[]` section is optional for backward compatibility. If absent, `MopData` SHALL behave identically to the previous version (no component-level data, no error).

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a `WidgetMopFlags` with `directMop=true` if the widget's handler appears in `reachability[]` with `directlyReachesMop=true`

#### Scenario: JSON with components section
- **WHEN** `MopData.load()` is called and the JSON contains `components: [{"className": "com.example.CryptoService", "type": "SERVICE"}]`
- **AND** `reachability[]` contains an entry for `com.example.CryptoService` with a method where `reachesMop=true`
- **THEN** `hasComponentMop("com.example.CryptoService")` SHALL return `true`
- **AND** `getComponentCount()` SHALL return a value >= 1

#### Scenario: JSON without components section (backward compatibility)
- **WHEN** `MopData.load()` is called and the JSON does not contain a `components` key
- **THEN** `getComponentCount()` SHALL return `0`
- **AND** all existing widget-level and activity-level queries SHALL behave identically to the previous version

#### Scenario: File missing — graceful null return
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file does not exist
- **THEN** `null` SHALL be returned
- **AND** a WARNING SHALL be logged
- **AND** no exception SHALL propagate to the caller

#### Scenario: path is null
- **WHEN** `MopData.load(null)` is called
- **THEN** `null` SHALL be returned immediately without attempting file I/O
