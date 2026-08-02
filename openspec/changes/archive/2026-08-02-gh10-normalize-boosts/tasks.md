## Tasks

### Group 1: Config changes

- [ ] **T1**: Change `Config.java` defaults: `mopWeightDirect` 500→50, `mopWeightTransitive` 300→30, `mopWeightActivity` 100→10 (lines ~128-130)
- [ ] **T2**: Change `Config.java` defaults: `mopWeightWtg` 200→20 (line ~149), `coverageBoostWeight` 100→20 (line ~146)
- [ ] **T3**: Add `Config.java`: `public static final int maxBoostCap = Config.getInteger("ape.maxBoostCap", 80);`
- [ ] **T4**: Change `Config.java`: `dynamicEpsilon` default `true`→`false` (line ~150)

### Group 2: Capping pass

- [ ] **T5**: Add capping pass in `StatefulAgent.adjustActionsByGUITree()` after the coverage boost pass (after line 1239). For each target-requiring action, if total boost exceeds `Config.maxBoostCap` (and maxBoostCap > 0), cap priority to `basePriority + maxBoostCap`. Requires tracking basePriority per action before boost passes. Log capped count.

### Group 3: Build + test

- [ ] **T6**: `mvn package` — build must succeed
- [ ] **T7**: `mvn test` — all existing tests must pass

### Group 4: Update specs

- [ ] **T8**: Update `openspec/specs/mop-guidance/spec.md` — change default values in the weights table
- [ ] **T9**: Update `openspec/specs/action-selection/spec.md` — add maxBoostCap requirement and INV-SEL-04/05/06, document dynamicEpsilon default change
