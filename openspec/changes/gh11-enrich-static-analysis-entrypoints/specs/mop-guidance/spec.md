# Delta Specification: MOP-Guided Action Scoring

## Purpose

Extends `MopData` to parse the new `components{}` section from the static analysis JSON. The `components{}` section contains `receivers[]` and `services[]` arrays, each with intent-filters, exported status, MOP reachability, and MOP method signatures. This data feeds both the component-level MOP awareness and the stagnation escape hatch that triggers broadcasts/services at runtime.

---

## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build:
1. An in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`) — existing, unchanged.
2. A list of `ReceiverInfo` objects for receivers with MOP-reachable lifecycle methods, including their intent-filter actions — new.
3. A list of `ServiceInfo` objects for services with MOP-reachable lifecycle methods, including their intent-filter actions — new.

Cross-referencing for widgets is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature` — unchanged.

For components, `MopData` SHALL parse `components.receivers[]` and `components.services[]`, retaining only entries where `reachesMop=true`. Intent-filter actions SHALL be extracted for use by the stagnation escape hatch.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`.

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed.

The `components{}` section is optional for backward compatibility. If absent, `MopData` SHALL behave identically to the previous version (empty receiver/service lists, no error).

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a `WidgetMopFlags` with `directMop=true` if the widget's handler appears in `reachability[]` with `directlyReachesMop=true`

#### Scenario: JSON with components section
- **WHEN** `MopData.load()` is called and the JSON contains `components.receivers` with entry `{"className": "com.example.BootReceiver", "reachesMop": true, "intentFilters": [{"actions": ["android.intent.action.BOOT_COMPLETED"]}]}`
- **THEN** `getMopReceivers()` SHALL return a list containing a `ReceiverInfo` for `com.example.BootReceiver`
- **AND** `ReceiverInfo.getActions()` SHALL contain `"android.intent.action.BOOT_COMPLETED"`
- **AND** `hasComponents()` SHALL return `true`

#### Scenario: JSON without components section (backward compatibility)
- **WHEN** `MopData.load()` is called and the JSON does not contain a `components` key
- **THEN** `getMopReceivers()` SHALL return an empty list
- **AND** `getMopServices()` SHALL return an empty list
- **AND** `hasComponents()` SHALL return `false`

#### Scenario: File missing — graceful null return
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file does not exist
- **THEN** `null` SHALL be returned

#### Scenario: path is null
- **WHEN** `MopData.load(null)` is called
- **THEN** `null` SHALL be returned immediately without attempting file I/O
