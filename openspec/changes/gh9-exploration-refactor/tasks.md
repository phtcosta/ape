<!-- Subagent dispatch: Groups 1-5 are independent and can be dispatched in parallel.
     Group 6 (integration) depends on all previous groups.
     Group 7 (verification) depends on Group 6.
     Critical path: any of 1-5 -> 6 -> 7 -->

## 1. Config Flags

- [x] 1.1 Add 8 new Config flags to `src/main/java/com/android/commands/monkey/ape/utils/Config.java`: coverageBoostWeight (int, 100), activityBaseBudget (int, 50), activityBudgetPerWidget (int, 5), mopWeightWtg (int, 200), dynamicEpsilon (boolean, true), maxEpsilon (double, 0.15), minEpsilon (double, 0.02), heuristicInput (boolean, true). Change existing `activityStableRestartThreshold` default from `Integer.MAX_VALUE` to `200`.
- [x] 1.2 Add unit tests for Config flag loading (`src/test/java/.../ape/utils/ConfigTest.java`)

## 2. UICoverageTracker

- [x] 2.1 Create `src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java` with:
  - `registerScreenElements(State state, List<ModelAction> actions)` — widget IDs via `action.getTarget().toXPath()` for targeted actions, `action.getType().name()` for non-targeted
  - `recordInteraction(State state, ModelAction action)` — increment count for the action's widget ID
  - `getCoverageGap(State state) -> float` — fraction of uninteracted widgets
  - `getInteractionCount(State state, String widgetId) -> int` — per-widget interaction count
  - Internal map keyed by `State` object (uses existing equals/hashCode via StateKey)
- [x] 2.2 Add unit tests `src/test/java/.../ape/utils/UICoverageTrackerTest.java` covering INV-COV-01..04, per-widget interaction counts, and all scenarios from spec
- [x] 2.3 Run /sdd-test-run

## 3. ActivityBudgetTracker

- [x] 3.1 Create `src/main/java/com/android/commands/monkey/ape/utils/ActivityBudgetTracker.java` with registerActivity(), recordIteration(), isBudgetExhausted(), getRemainingBudget(). Widget count uses `action.requireTarget()` filter (excludes BACK/MENU).
- [x] 3.2 Add unit tests `src/test/java/.../ape/utils/ActivityBudgetTrackerTest.java` covering INV-BUD-01..03 and all scenarios from spec
- [x] 3.3 Run /sdd-test-run

## 4. WTG Navigation

- [x] 4.1 Add Pass 3 to `src/main/java/com/android/commands/monkey/ape/utils/MopData.java`: parse transitions[], build activityName -> List<WtgTransition> map, add getWtgTransitions() and hasWtgData() methods
- [x] 4.2 Add `MopScorer.scoreWtg(String activity, String shortId, MopData data)` to `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java`
- [x] 4.3 Add unit tests for WTG parsing in `src/test/java/.../ape/utils/MopDataTest.java` (extend existing) covering INV-WTG-01..03
- [x] 4.4 Add unit tests for scoreWtg in `src/test/java/.../ape/utils/MopScorerTest.java` (extend existing)
- [x] 4.5 Run /sdd-test-run

## 5. InputValueGenerator and Dynamic Epsilon

- [x] 5.1 Create `src/main/java/com/android/commands/monkey/ape/utils/InputValueGenerator.java` with detectCategory(), generateForNode(), InputCategory enum
- [x] 5.2 Add unit tests `src/test/java/.../ape/utils/InputValueGeneratorTest.java` covering INV-INP-01..03 and all scenarios from spec
- [x] 5.3 Add `computeDynamicEpsilon()` method to `src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java` using `_coverageTracker.getCoverageGap(newState)`, modify `egreedy()` to use it when `Config.dynamicEpsilon` is true
- [x] 5.4 Add unit tests for dynamic epsilon in `src/test/java/.../ape/agent/DynamicEpsilonTest.java` covering INV-EPS-01..03
- [x] 5.5 Run /sdd-test-run

## 6. Agent Integration

- [x] 6.1 Add `private final` fields `_coverageTracker` (UICoverageTracker) and `_budgetTracker` (ActivityBudgetTracker) to `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java`, instantiate in constructor. Add `protected` accessor methods (`getCoverageTracker()`, `getBudgetTracker()`) following the same pattern as `_mopData`/`getMopData()`. SataAgent accesses them via the accessors for dynamic epsilon and budget checks.
- [x] 6.2 Register widgets in `updateStateInternal()` AFTER `preEvolveModel()` completes (i.e., after any Naming refinement) and BEFORE `resolveNewAction()`. This ensures the registered State is the final post-refinement one. Call: `_coverageTracker.registerScreenElements(newState, newState.getActions())`; register activity with widget count (filtered by `requireTarget()`)
- [x] 6.3 Record interaction in `moveForward()` after action execution: `_coverageTracker.recordInteraction(newState, action)`; record iteration for budget
- [x] 6.4 Add WTG scoring pass to `adjustActionsByGUITree()` immediately after the MOP logging statement (after the closing brace of the `if (_mopData != null)` block). Pass order: base priority -> unvisited/transition bonuses -> MOP boost -> WTG boost -> coverage boost.
- [x] 6.5 Add per-action coverage boost pass to `adjustActionsByGUITree()` after WTG pass (last pass in the method): for each action, boost = `Config.coverageBoostWeight` if `getInteractionCount(state, widgetId) == 0`, else 0
- [x] 6.5.1 Add `Logger.iformat()` telemetry in each new pass: log state, boosted action count, max boost, and coverage gap. Follow the same pattern as the existing MOP boost log.
- [x] 6.6 Modify `State.greedyPickLeastVisited(ActionFilter)` in `src/main/java/com/android/commands/monkey/ape/model/State.java`: when multiple actions share the minimum `visitedCount`, select the one with the highest `priority`. Must remain single-pass O(n). (See action-selection/spec.md)
- [x] 6.7 Add budget exhaustion check at top of `SataAgent.selectNewActionNonnull()` before LLM hooks. When budget exhausted: (1) try `selectNewActionForTrivialActivity()`; (2) if null, fall through to SATA chain. (Original: EVENT_RESTART fallback, changed to fallthrough after E2E revealed restart loop — see task 8.1 for MODEL_BACK fix)
- [x] 6.8 Modify `ApeAgent.checkInput()` to use `InputValueGenerator.generateForNode()` when `Config.heuristicInput` is true
- [x] 6.9 Add unit tests for `State.greedyPickLeastVisited()` tiebreaker in `src/test/java/.../ape/model/StateTest.java` covering INV-SEL-01..03
- [x] 6.10 Run /sdd-test-run

## 7. Initial Verification (completed)

- [x] 7.1 Validate WTG matching: cryptoapp match rate 67% (6/9). Buttons: 100% match. MenuItems: 0% (Android limitation). Above 50% threshold.
- [x] 7.2 `mvn clean package` — 271 tests, 0 failures, 227KB JAR
- [x] 7.3 Smoke test: 1-min sata on cryptoapp with mopDataPath. WTG boost (3/5, +200), Coverage boost (gap 1.0->0.27), MOP boost (2/10, +300). No crashes.
- [x] 7.4 E2E v1 (pre-fix, single run): ape 35.59% / aperv 34.75%. Revealed restart loop problem.
- [x] 7.5 E2E v2 (pre-fix, 10 min): ape 38.98% / aperv 35.59%. Confirmed restart loop: 40 budget exhaustions -> 40 EVENT_RESTARTs. Aperv -3.39pp vs ape.
- [x] 7.6 E2E v3 (post restart-loop fix, 3 reps x 5 min): aperv avg 34.46% method (+3.67pp vs ape 30.79%), 46.45% MOP (+6.01pp vs ape 40.44%). Fix validated.
- [x] 7.7 Trace analysis: EARLY_STAGE dominates 99% of aperv decisions (vs 83% ape). Logging overhead reduces throughput ~14% (229 vs 267 steps/5min).

## 8. E2E Fixes (post trace analysis)

- [x] 8.1 Fix budget exhaustion fallback in `SataAgent.selectNewActionNonnull()`: removed all hard fallbacks (EVENT_RESTART caused restart loops, MODEL_BACK caused stuck loops). Budget is now advisory — fallthrough to SATA chain when no trivial activity available.
- [x] 8.2 Fix logging overhead in `StatefulAgent.adjustActionsByGUITree()`: wrapped WTG and Coverage boost logs in `if (boostedCount > 0)` guards.
- [x] 8.3 `mvn clean package` — BUILD SUCCESS, all tests pass
- [x] 8.4 E2E revalidation: 20 APKs, 2 reps. Aperv +0.79pp method, +1.60pp MOP vs ape. 8 wins / 7 losses / 5 ties. Superseded by decay fix (group 9).

## 9. Coverage Boost Decay (post E2E regression analysis)

- [x] 9.1 Add coverage boost decay by state visit count in `StatefulAgent.adjustActionsByGUITree()`: `boost = coverageBoostWeight / (1 + stateVisits / 5)` for untested widgets. Prevents agent from being trapped in complex states with many widgets.
- [x] 9.2 `mvn clean package` — 271 tests, 0 failures, BUILD SUCCESS
- [x] 9.3 E2E quick validation: 3 APKs, 1 rep, 300s. Decay fixed simplenotes regression: -12.56pp → -0.23pp. Ludo still +23.25pp. ApkTrack -1.04pp (stable).

## 10. Final Verification

- [ ] 10.1 Run /sdd-qa-lint-fix
- [ ] 10.2 Run /sdd-verify
- [ ] 10.3 Run /sdd-code-reviewer
