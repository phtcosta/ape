## Purpose

This delta spec aligns the `Config.java` default values for MOP weight parameters with the existing `mop-guidance` specification. The main spec (`openspec/specs/mop-guidance/spec.md`) already specifies boosts of +500/+300/+100 in the MopScorer requirement. However, `Config.java` currently declares defaults of 100/60/20 (v2 values from gh5), which diverges from the spec. This delta corrects the defaults to match the specified behavior.

The revert is motivated by experimental evidence: exp1+exp2 (169 APKs) showed v1 weights (500/300/100) outperform v2 (100/60/20) by +1.00pp method coverage (Wilcoxon p=0.031) and +3 unique violation types.

---

## MODIFIED Requirements

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
