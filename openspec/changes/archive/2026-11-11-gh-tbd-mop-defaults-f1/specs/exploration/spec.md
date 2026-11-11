## MODIFIED Requirements

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a supported configuration channel; note that `Config` builds its `Properties` with `System.getProperties()` as defaults, so a system property could reach a key that `Config` reads without plan validation or echo. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; tests construct `RunSpec` values instead of mutating `Config`. `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority. A key that the plan owns SHALL be read from the plan (`RunContext.current().spec()`) and has no `Config` field: in particular `ape.doFuzzing`, `ape.fuzzingRate` and `ape.checkRestart` are BASE keys read from the plan (design D-B6, extended by author decision A10), and `ape.apeFixes` is the activation key of the ADDONS member `APE_FIXES`, read through `spec().has(Feature.APE_FIXES)`.

The table below lists the configuration keys relevant to the exploration engine with their types and defaults:

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart (BASE) |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart (BASE) |
| `ape.activityStableRestartThreshold` | int | 200 in the ADDONS block | Steps in same activity before forced restart. Activation key of the ADDONS member `ACTIVITY_STABLE_RESTART` (POSITIVE, neutral 0), no longer a BASE key; when the feature is absent (ADDONS off, or the key `0`) the threshold is the upstream `Integer.MAX_VALUE` (design D-B5) |
| `ape.apeFixes` | boolean | true (requires `ape.addons`; `false` when ADDONS is off) | Activation key of the ADDONS member `APE_FIXES`: with it the restart guard stage, the bounded foreground wait, the off-screen drop, the pinch/zoom emission and the `fuzz`-stream toss; without it APE's code at each of those points |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection (BASE, read from the plan) |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step (BASE, read from the plan) |
| `ape.checkRestart` | boolean | true | Enable restarts (BASE, read from the plan): the `Restart` guard stage when `APE_FIXES` is in the plan, the post-selection `checkRestart` otherwise |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.takeScreenshotForNewState` | boolean | true | Save one PNG per new abstract state (with `ape.takeScreenshot`) |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

The keys `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` are retired (persistence removal) and abort resolution if present. The keys `ape.mopDataPath` (renamed `ape.staticDataPath`), `ape.leastVisitedPriorityTiebreak` (replaced by the X1 scorer-sum tie) and `ape.mopTargetPickCap` (replaced by `ape.mopCoinLedgerMaxInteractions`, k, the MOP coin's limit on executions by any owner per activity and `xpath|ActionType` key) are retired and abort resolution with `retired_key` (`run-spec`).

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** the plan's `spec().exploration().graphStableRestartThreshold()` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields and all BASE plan keys SHALL take their hardcoded default values, and no group member SHALL be in the plan, so `ACTIVITY_STABLE_RESTART` and `APE_FIXES` are absent and the activity threshold is `Integer.MAX_VALUE`
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

#### Scenario: the activity-stable threshold follows ADDONS
- **WHEN** the `as` cell resolves, whose ADDONS block sets `ape.activityStableRestartThreshold=200`
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be in the plan and the activity-stability check SHALL raise the restart flag once the counter exceeds `200`
- **AND WHEN** the `base` cell resolves
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be absent and the threshold SHALL be `Integer.MAX_VALUE`, so the activity check never raises the flag

#### Scenario: APE_FIXES follows ADDONS
- **WHEN** the `as`, `as_mop`, `as_llm` or `as_mop_llm` cell resolves, whose ADDONS block states `ape.apeFixes=true`
- **THEN** `APE_FIXES` SHALL be in the plan
- **AND WHEN** the `base` or `mop` cell resolves
- **THEN** `APE_FIXES` SHALL be absent, so every point it decides runs upstream APE's code
