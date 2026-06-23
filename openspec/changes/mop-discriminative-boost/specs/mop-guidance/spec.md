## MODIFIED Requirements

### Requirement: MopScorer — Priority Boost

`MopScorer.score(String activity, String shortId, MopData data)` SHALL return an integer priority boost according to the following scale. The boost is additive: it is added to the existing `ModelAction.priority`, never replacing it.

| Condition | Boost |
|-----------|-------|
| `data.getWidget(activity, shortId).directMop == true` | `+mopWeightDirect` (500) |
| `data.getWidget(activity, shortId).transitiveMop == true` (and not direct) | `+mopWeightTransitive` (300) |
| widget is `null` OR resolves with all MOP flags false | `0` |

The scorer SHALL NOT apply any activity-level fallback. A resolved-but-unflagged widget and a null widget — including on a MOP-bearing activity — both score `0`. This removes the prior `+mopWeightActivity` (100) fallback, which was applied uniformly to every target widget on a MOP-bearing activity and therefore could not re-rank candidates. The previously-required `INV-MOP-07` (which mandated that fallback) is obsolete and removed; `MopData.activityHasMop(activity)` remains as a predicate consumed by WTG scoring and `stateMopDensity`, but is no longer a branch of `MopScorer.score`.

#### Scenario: Direct MOP-reachable widget
- **WHEN** `data.getWidget(...)` returns flags `{directMop=true, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightDirect` (500)

#### Scenario: Transitive only
- **WHEN** `data.getWidget(...)` returns flags `{directMop=false, transitiveMop=true}`
- **THEN** the returned boost SHALL be `mopWeightTransitive` (300)

#### Scenario: Resolved-but-unflagged widget scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns a non-null widget with `directMop=false` AND `transitiveMop=false`
- **AND** `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: Widget null scores zero
- **WHEN** `data.getWidget(activity, shortId)` returns `null` AND `data.activityHasMop(activity)` returns `true`
- **THEN** the returned boost SHALL be `0`

#### Scenario: No match
- **WHEN** the widget carries no MOP flag AND the activity has no MOP association
- **THEN** the returned boost SHALL be `0`

### Requirement: Config.mopDataPath Flag

`Config.java` SHALL declare `public static final String mopDataPath` loaded via `Config.get("ape.mopDataPath")`. The default value is `null`. When set (via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties`), it points to the static analysis JSON file path on the device.

`Config.java` SHALL also declare the following MOP weight fields with defaults matching the MopScorer boost table:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.mopWeightDirect` | int | `500` | Boost for direct MOP-reachable widget |
| `ape.mopWeightTransitive` | int | `300` | Boost for transitive MOP-reachable widget |

These weights are configurable via `ape.properties` but the hardcoded defaults SHALL be 500/300. `Config.mopWeightActivity` SHALL NOT exist — the activity-level fallback it parameterised is removed (see "MopScorer — Priority Boost").

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

#### Scenario: Custom MOP weights override
- **WHEN** `ape.properties` contains `ape.mopWeightDirect=200`
- **THEN** `Config.mopWeightDirect` SHALL equal `200`
- **AND** `Config.mopWeightTransitive` SHALL retain its default (`300`)
