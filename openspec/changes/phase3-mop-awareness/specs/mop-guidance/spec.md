## Purpose

This delta replaces the placeholder `openspec/specs/mop-guidance/spec.md` ("Not yet implemented") with a complete specification for MOP-guided action scoring in the APE-RV Java core.

MOP guidance integrates the static analysis JSON produced by the rv-android pipeline into APE's action priority system. When `ape.mopDataPath` points to a valid JSON file on the device, `MopData` parses the file and `MopScorer` assigns priority boosts to widget actions that reach monitored operations (MOP specs). The boost is applied in `StatefulAgent.adjustActionsByGUITree()` — the designated extension point — after the base SATA priority is assigned and before the agent's selection step. When `ape.mopDataPath` is absent or the file is missing, the system operates identically to plain `sata` with no change to priority values.

The JSON format is the same as used by rvsmart (`windows[]` + `reachability[]` sections). Cross-referencing widget listeners with MOP-reachable methods identifies which widgets, when interacted with, trigger spec-monitored operations. This allows `aperv:sata_mop` to steer exploration toward security-relevant code paths without rewriting the SATA heuristic or introducing a separate exploration mode.

---

## ADDED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path)` SHALL parse the static analysis JSON file at `path` and build an in-memory map from activity class name to short widget resource ID to MOP reachability flags (`directMop`, `transitiveMop`). Cross-referencing is performed by matching `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`.

Widget IDs SHALL be stored in short form: `"com.example.app:id/btn_encrypt"` → `"btn_encrypt"`. The transform is: if `resourceId` contains `":id/"`, take the substring after `":id/"`. If `resourceId` is null or does not contain `":id/"`, use an empty string as key (activity-level fallback applies).

`MopData.load()` SHALL return `null` (not throw) if `path` is `null`, the file does not exist, or the JSON is malformed. In the last case it SHALL log a WARNING with the parse error details. This ensures graceful degradation to plain `sata` behaviour when MOP data is unavailable.

#### Scenario: Valid JSON loaded
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file contains valid `windows[]` and `reachability[]` sections
- **THEN** the returned `MopData` SHALL be non-null
- **AND** `getWidget("com.example.MainActivity", "btn_encrypt")` SHALL return a `WidgetMopFlags` with `directMop=true` if the widget's handler appears in `reachability[]` with `directlyReachesMop=true`

#### Scenario: File missing — graceful null return
- **WHEN** `MopData.load("/data/local/tmp/static_analysis.json")` is called and the file does not exist
- **THEN** `null` SHALL be returned
- **AND** a WARNING SHALL be logged
- **AND** no exception SHALL propagate to the caller

#### Scenario: path is null
- **WHEN** `MopData.load(null)` is called
- **THEN** `null` SHALL be returned immediately without attempting file I/O

#### Scenario: Activity-level fallback
- **WHEN** `getWidget("com.example.MainActivity", "unknown_id")` is called and no widget match exists
- **THEN** `getWidget()` SHALL return `null`
- **AND** `activityHasMop("com.example.MainActivity")` SHALL return `true` if any widget in that activity has a MOP-reachable listener

---

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data)` SHALL return an integer priority boost according to the following scale:

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | +500 |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | +300 |
| `data.getWidget(activity, shortId) == null` AND `data.activityHasMop(activity) == true` | +100 |
| No match at any level | 0 |

The boost is additive: it is added to the existing `ModelAction.priority` value, not used as a replacement.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `MopScorer.score("com.example.MainActivity", "btn_encrypt", data)` is called
- **AND** `data.getWidget(...)` returns `WidgetMopFlags{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `500`

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns `WidgetMopFlags{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `300`

#### Scenario: Activity-level only
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `100`

#### Scenario: No match
- **WHEN** neither the widget nor the activity has any MOP association
- **THEN** the returned boost SHALL be `0`

---

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath", null)`. The default value is `null`. When set (via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties`), it points to the static analysis JSON file path on the device.

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.mopDataPath`
- **THEN** `Config.mopDataPath` SHALL be `null`
- **AND** `StatefulAgent` SHALL initialise `_mopData` to `null`, disabling MOP scoring

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json`
- **THEN** `Config.mopDataPath` SHALL equal `"/data/local/tmp/static_analysis.json"`

---

## ADDED Invariants

- **INV-MOP-01**: `MopData.load()` SHALL never throw a checked or unchecked exception to the caller. All I/O and parse errors SHALL be caught internally and result in a `null` return with a WARNING log.
- **INV-MOP-02**: MOP scoring SHALL only be applied to actions where `action.requireTarget() == true` AND `action.isValid() == true`. Non-target actions (MODEL_BACK, MODEL_MENU, FUZZ, etc.) SHALL NOT receive MOP boosts.
- **INV-MOP-03**: MOP scoring SHALL be additive (`setPriority(getPriority() + boost)`), never replacing the existing priority. The base SATA priority assignment always runs first.
- **INV-MOP-04**: When `Config.mopDataPath` is `null`, the MOP scoring pass SHALL be skipped entirely. The `sata` variant's behaviour SHALL be identical with and without `MopData.java` present in the JAR.

---

## ADDED Data Contracts

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
