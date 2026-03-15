## MODIFIED Requirements

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data)` SHALL return an integer priority boost according to configurable weights loaded from `Config`. The default weight scale is:

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `Config.mopWeightDirect` (default: 100) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `Config.mopWeightTransitive` (default: 60) |
| `data.getWidget(activity, shortId) == null` AND `data.activityHasMop(activity) == true` | `Config.mopWeightActivity` (default: 20) |
| No match at any level | 0 |

The boost is additive: it is added to the existing `ModelAction.priority` value, not used as a replacement.

#### Scenario: Direct MOP-reachable widget with default weights
- **WHEN** `MopScorer.score("com.example.MainActivity", "btn_encrypt", data)` is called
- **AND** `data.getWidget(...)` returns `WidgetMopFlags{directMop=true, transitiveMop=true}`
- **AND** `Config.mopWeightDirect` is at its default value of `100`
- **THEN** the returned boost SHALL be `100`

#### Scenario: Direct MOP-reachable widget with custom weights
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **AND** `MopScorer.score("com.example.MainActivity", "btn_encrypt", data)` is called
- **AND** `data.getWidget(...)` returns `WidgetMopFlags{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `200`

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns `WidgetMopFlags{directMop=false, transitiveMop=true}`
- **AND** `Config.mopWeightTransitive` is at its default value of `60`
- **THEN** the returned boost SHALL be `60`

#### Scenario: Activity-level only
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **AND** `Config.mopWeightActivity` is at its default value of `20`
- **THEN** the returned boost SHALL be `20`

#### Scenario: No match
- **WHEN** neither the widget nor the activity has any MOP association
- **THEN** the returned boost SHALL be `0`

---

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath")`. The default value is `null`. When set (via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties`), it points to the static analysis JSON file path on the device.

`Config.java` SHALL additionally declare three integer fields for MOP scoring weights:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | 100 | Boost for actions targeting a direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | 60 | Boost for actions targeting a transitive-only MOP-reachable widget |
| `ape.mopWeightActivity` | int | 20 | Boost for actions on an activity that has MOP-reachable widgets but no specific widget match |

#### Scenario: Flag absent
- **WHEN** `ape.properties` does not contain `ape.mopDataPath`
- **THEN** `Config.mopDataPath` SHALL be `null`
- **AND** `StatefulAgent` SHALL initialise `_mopData` to `null`, disabling MOP scoring

#### Scenario: Flag set
- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json`
- **THEN** `Config.mopDataPath` SHALL equal `"/data/local/tmp/static_analysis.json"`

#### Scenario: Weight overrides
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=50`
- **THEN** `Config.mopWeightDirect` SHALL equal `50`
- **AND** `Config.mopWeightTransitive` SHALL remain at its default (`60`) if not overridden
- **AND** `Config.mopWeightActivity` SHALL remain at its default (`20`) if not overridden

---

## ADDED Requirements

### Requirement: MopScorer — State MOP Density

`MopScorer.stateMopDensity(State state, MopData data)` SHALL return the count of target-requiring, valid actions in `state` whose widget has any MOP reachability (direct or transitive) according to `data`. When `data` is `null`, the method SHALL return `0`.

The density count includes both direct and transitive MOP matches without weighting. An action is counted if `data.getWidget(activity, shortId)` returns non-null OR if no widget-level match exists but `data.activityHasMop(activity)` returns `true`.

#### Scenario: State with 3 MOP-reachable widgets out of 10 actions
- **WHEN** `MopScorer.stateMopDensity(state, data)` is called
- **AND** `state` has 10 actions, of which 8 require a target and are valid
- **AND** 3 of those 8 have widgets matching entries in `data`
- **THEN** the returned density SHALL be `3`

#### Scenario: MopData is null
- **WHEN** `MopScorer.stateMopDensity(state, null)` is called
- **THEN** the returned density SHALL be `0`

#### Scenario: State with no valid target actions
- **WHEN** `state` has only `MODEL_BACK` and `MODEL_MENU` actions (no target-requiring actions)
- **THEN** the returned density SHALL be `0`

---

### Requirement: MOP Telemetry Logging

After the MOP scoring pass in `StatefulAgent.adjustActionsByGUITree()`, a telemetry log line SHALL be emitted summarising the MOP boost applied to the current state. The format SHALL be:

```
[APE-RV] MOP boost: state=<activity>#<stateKey>, boosted=<N>/<total>, maxBoost=<value>
```

Where `<activity>` is `newState.getActivity()`, `<stateKey>` is `newState.getStateKey().toString()`, `<N>` is the count of actions that received a non-zero boost, `<total>` is the total count of target-requiring valid actions, and `<maxBoost>` is the highest boost value applied.

The telemetry line SHALL only be emitted when `_mopData` is non-null. When `_mopData` is `null`, no telemetry line SHALL be logged.

#### Scenario: State with MOP-boosted actions
- **WHEN** `adjustActionsByGUITree()` runs with `_mopData` non-null
- **AND** the current state has 15 target-requiring valid actions
- **AND** 4 of them receive a non-zero MOP boost, with the highest being 100
- **THEN** the log SHALL contain: `[APE-RV] MOP boost: state=com.example.MainActivity#<stateKey>, boosted=4/15, maxBoost=100`

#### Scenario: State with no MOP matches
- **WHEN** `adjustActionsByGUITree()` runs with `_mopData` non-null
- **AND** no actions in the current state match any MOP entry
- **THEN** the log SHALL contain: `[APE-RV] MOP boost: state=com.example.MainActivity#<stateKey>, boosted=0/15, maxBoost=0`

#### Scenario: MOP disabled
- **WHEN** `adjustActionsByGUITree()` runs with `_mopData` null
- **THEN** no `[APE-RV] MOP boost:` line SHALL appear in the log
