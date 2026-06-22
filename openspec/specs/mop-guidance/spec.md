# Specification: MOP-Guided Action Scoring

## Purpose

MOP guidance integrates the static analysis JSON produced by the rv-android pipeline into APE's action priority system. When `ape.mopDataPath` points to a valid JSON file on the device, `MopData` parses the file and `MopScorer` assigns priority boosts to widget actions that reach monitored operations (MOP specs). The boost is applied in `StatefulAgent.adjustActionsByGUITree()` — the designated extension point — after the base SATA priority is assigned and before the agent's selection step. When `ape.mopDataPath` is absent or the file is missing, the system operates identically to plain `sata` with no change to priority values.

The JSON contains `windows[]`, `reachability[]`, `transitions[]`, and `components{}` sections. Cross-referencing widget listeners with MOP-reachable methods identifies which widgets, when interacted with, trigger spec-monitored operations. The `components{}` section provides data on all Android components (Activities, Services, BroadcastReceivers, ContentProviders) with their intent-filters, exported status, and MOP reachability, enabling component triggering during exploration. This allows `aperv:sata_mop` to steer exploration toward security-relevant code paths and exercise non-GUI components.

---
## Requirements
### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build:
1. An in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`) — existing, unchanged.
2. Lists of `ReceiverInfo`, `ServiceInfo`, `ActivityInfo`, `ProviderInfo` objects parsed from `components{}` — new.

Cross-referencing for widgets is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature` — unchanged.

For components, `MopData` SHALL parse all four arrays in `components{}` (`activities[]`, `receivers[]`, `services[]`, `providers[]`), retaining ALL entries (not filtered by `reachesMop`). Intent-filter actions SHALL be extracted for receivers/services/activities; authorities for providers.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`.

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed.

The `components{}` section is optional for backward compatibility. If absent, `MopData` SHALL behave identically to the previous version (empty receiver/service lists, no error).

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a `WidgetMopFlags` with `directMop=true` if the widget's handler appears in `reachability[]` with `directlyReachesMop=true`

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

---

## Invariants

- **INV-MOP-01**: `MopData.load()` SHALL never throw a checked or unchecked exception to the caller. All I/O and parse errors SHALL be caught internally and result in a `null` return with a WARNING log.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `Config.mopDataPath` is `null`, the MOP scoring pass SHALL be skipped entirely. The `sata` variant's behaviour SHALL be identical with and without `MopData.java` present in the JAR.
- **INV-MOP-05**: The WTG scoring pass SHALL execute AFTER the existing MOP scoring pass in `adjustActionsByGUITree()`. Pass order: base priority -> unvisited bonus -> state transition bonus -> MOP boost -> WTG boost -> coverage boost.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0.
- **INV-MOP-07**: The activity-level fallback (`+mopWeightActivity`) SHALL be reachable both when widget resolution returns `null` AND when it returns a widget whose MOP flags are all false. A resolved-but-unflagged widget SHALL NOT short-circuit to `0` before the activity check.
- **INV-MOP-08**: `eventType` comparison in the scorer SHALL be normalization-invariant: a producer `snake_case` token and the consumer `camelCase` token for the same event SHALL compare equal.

### Requirement: WTG Scoring Pass in adjustActionsByGUITree

`StatefulAgent.adjustActionsByGUITree()` SHALL include a WTG scoring pass after the existing MOP scoring pass. For each valid, target-requiring, resolved action, the pass SHALL call `MopScorer.scoreWtg(activity, shortId, mopData)` and add the result to the action's priority. This pass SHALL only execute when `_mopData` is non-null and has WTG transitions loaded.

#### Scenario: WTG boost applied alongside MOP boost
- **WHEN** widget has direct MOP listener (MOP boost = +500) AND WTG leads to MOP activity (WTG boost = +200)
- **THEN** total priority boost SHALL be +700

#### Scenario: No WTG data
- **WHEN** `_mopData` is null or has no transitions
- **THEN** WTG scoring pass SHALL be skipped

### Requirement: Config Flag for WTG Weight

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `mopWeightWtg` | `ape.mopWeightWtg` | int | 200 | WTG navigation boost (0 = disabled) |

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
