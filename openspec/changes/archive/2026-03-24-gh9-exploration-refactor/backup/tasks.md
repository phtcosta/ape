<!-- Subagent dispatch: Groups 1-5 are independent and can be dispatched in parallel.
     Group 6 (integration) depends on all previous groups.
     Group 7 (verification) depends on Group 6.
     Critical path: any of 1-5 → 6 → 7 -->

## 1. Config Flags

- [ ] 1.1 Add 8 new Config flags to `src/main/java/com/android/commands/monkey/ape/utils/Config.java`: coverageBoostWeight (int, 100), activityBaseBudget (int, 50), activityBudgetPerWidget (int, 5), mopWeightWtg (int, 200), dynamicEpsilon (boolean, true), maxEpsilon (double, 0.15), minEpsilon (double, 0.02), heuristicInput (boolean, true)
- [ ] 1.2 Add unit tests for Config flag loading (`src/test/java/.../ape/utils/ConfigTest.java`)

## 2. UICoverageTracker

- [ ] 2.1 Create `src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java` with registerScreenElements(), recordInteraction(), getCoverageGap(), elementId()
- [ ] 2.2 Add unit tests `src/test/java/.../ape/utils/UICoverageTrackerTest.java` covering INV-COV-01..04 and all scenarios from spec
- [ ] 2.3 Run /sdd-test-run

## 3. ActivityBudgetTracker

- [ ] 3.1 Create `src/main/java/com/android/commands/monkey/ape/utils/ActivityBudgetTracker.java` with registerActivity(), recordIteration(), isBudgetExhausted(), getRemainingBudget()
- [ ] 3.2 Add unit tests `src/test/java/.../ape/utils/ActivityBudgetTrackerTest.java` covering INV-BUD-01..03 and all scenarios from spec
- [ ] 3.3 Run /sdd-test-run

## 4. WTG Navigation

- [ ] 4.1 Add Pass 3 to `src/main/java/com/android/commands/monkey/ape/utils/MopData.java`: parse transitions[], build activityName → List<WtgTransition> map, add getWtgTransitions() and hasWtgData() methods
- [ ] 4.2 Add `MopScorer.scoreWtg(String activity, String shortId, MopData data)` to `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java`
- [ ] 4.3 Add unit tests for WTG parsing in `src/test/java/.../ape/utils/MopDataTest.java` (extend existing) covering INV-WTG-01..03
- [ ] 4.4 Add unit tests for scoreWtg in `src/test/java/.../ape/utils/MopScorerTest.java` (extend existing)
- [ ] 4.5 Run /sdd-test-run

## 5. InputValueGenerator and Dynamic Epsilon

- [ ] 5.1 Create `src/main/java/com/android/commands/monkey/ape/utils/InputValueGenerator.java` with detectCategory(), generateForNode(), InputCategory enum
- [ ] 5.2 Add unit tests `src/test/java/.../ape/utils/InputValueGeneratorTest.java` covering INV-INP-01..03 and all scenarios from spec
- [ ] 5.3 Add `computeDynamicEpsilon()` method to `src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java` using `_coverageTracker.getCoverageGap()`, modify `egreedy()` to use it
- [ ] 5.4 Add unit tests for dynamic epsilon in `src/test/java/.../ape/agent/SataAgentTest.java` covering INV-EPS-01..03
- [ ] 5.5 Run /sdd-test-run

## 6. Agent Integration

- [ ] 6.1 Add `_coverageTracker` and `_budgetTracker` fields to `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java`, instantiate in constructor
- [ ] 6.2 Register widgets in `updateStateInternal()` after GUITree is built, register activity with widget count
- [ ] 6.3 Record interaction in `moveForward()` after action execution, record iteration for budget
- [ ] 6.4 Add WTG scoring pass and coverage boost pass to `adjustActionsByGUITree()` after existing MOP pass
- [ ] 6.5 Add budget exhaustion check at top of `SataAgent.selectNewActionNonnull()` before LLM hooks
- [ ] 6.6 Modify `ApeAgent.checkInput()` to use `InputValueGenerator.generateForNode()` when `Config.heuristicInput` is true
- [ ] 6.7 Run /sdd-test-run

## 7. Final Verification

- [ ] 7.1 Run /sdd-qa-lint-fix
- [ ] 7.2 Run /sdd-verify
- [ ] 7.3 `mvn clean package` — verify ape-rv.jar builds successfully
- [ ] 7.4 Run /sdd-code-reviewer
