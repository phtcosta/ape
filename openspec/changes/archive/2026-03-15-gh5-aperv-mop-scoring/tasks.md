## 1. Config — Configurable MOP Weight Keys

- [x] 1.1 Add `mopWeightDirect`, `mopWeightTransitive`, `mopWeightActivity` fields to `Config.java` using `Config.getInteger()` with defaults 100/60/20
- [x] 1.2 Add comment block above the new fields explaining their purpose and valid range

## 2. MopScorer — Configurable Weights

- [x] 2.1 Replace hardcoded 500/300/100 in `MopScorer.score()` with `Config.mopWeightDirect`, `Config.mopWeightTransitive`, `Config.mopWeightActivity`
- [x] 2.2 Add `MopScorer.stateMopDensity(State state, MopData data)` — counts target-requiring valid actions with MOP reachability; returns 0 when `data` is null

## 3. StatefulAgent — Telemetry Logging

- [x] 3.1 In `adjustActionsByGUITree()`, after the MOP pass, add telemetry: count boosted actions, total target actions, max boost value; emit `[APE-RV] MOP boost: state=<activity>#<stateKey>, boosted=<N>/<total>, maxBoost=<value>` (use `newState.getStateKey().toString()` for stateKey)
- [x] 3.2 Ensure telemetry is only emitted when `_mopData` is non-null

## 4. SataAgent — Navigation MOP Density Tiebreaker

- [x] 4.1 In `selectNewActionEarlyStageForABAInternal()`: when comparing candidate greedy states with equal visit counts (lines 530-541), add MOP density tiebreaker via `MopScorer.stateMopDensity()` when `_mopData` is non-null
- [x] 4.2 In `selectNewActionForTrivialActivity()`: when multiple shortest paths are available (line 761), prefer the path whose target state has higher MOP density when `_mopData` is non-null; fall back to random selection when densities are equal or `_mopData` is null
- [x] 4.3 Add `protected MopData getMopData()` getter in `StatefulAgent.java` — SataAgent uses this to access `_mopData` (field stays `private final`)

## 5. Build and Validation

- [x] 5.1 `mvn clean package` succeeds with no compilation errors
- [x] 5.2 `mvn install -Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec` — JAR copied to rv-android aperv-tool
- [x] 5.3 Verify `ape-rv.jar` contains `MopScorer.class` with updated bytecode (check with `jar tf`)

## 6. Verification

- [x] 6.1 Update `CLAUDE.md` — mark Phase 3 as implemented, document new config keys
- [x] 6.2 Commit all changes with `refs #5` in message
- [x] 6.3 Push to remote
