## Context

APE-RV reached a ~28% method coverage plateau (see proposal.md). Six independent algorithmic improvements target structural bottlenecks: lack of widget-level coverage tracking, no activity budgets, unused WTG data in static analysis JSON, fixed epsilon, random text input, and priority-blind greedy selection. All changes touch the `ape/utils/`, `ape/agent/`, and `ape/model/` packages. No external dependencies are introduced.

## Architecture

```
ApeAgent.checkInput()
  +-- InputValueGenerator.generateForNode(node)          [NEW]

StatefulAgent
  |-- _coverageTracker: UICoverageTracker                 [NEW field, protected accessor]
  |-- _budgetTracker: ActivityBudgetTracker               [NEW field, protected accessor]
  |-- updateStateInternal()
  |     |-- register widgets -> _coverageTracker
  |     +-- register activity -> _budgetTracker
  |-- moveForward()
  |     |-- record interaction -> _coverageTracker
  |     +-- record iteration -> _budgetTracker
  +-- adjustActionsByGUITree()
        |-- base priority (existing)
        |-- MOP boost (existing)
        |-- WTG boost -> MopScorer.scoreWtg()              [NEW pass]
        +-- coverage boost -> per-action via _coverageTracker [NEW pass]

SataAgent
  |-- selectNewActionNonnull()
  |     +-- budget check -> _budgetTracker.isBudgetExhausted() [NEW check]
  +-- egreedy()
        +-- computeDynamicEpsilon()                        [NEW method]

State
  +-- greedyPickLeastVisited()                             [MODIFIED: priority tiebreaker]

MopData.load()
  |-- Pass 1: reachability[] (existing)
  |-- Pass 2: windows[] (existing)
  +-- Pass 3: transitions[] -> WTG map                     [NEW pass]

MopScorer
  |-- score() (existing)
  +-- scoreWtg()                                           [NEW method]
```

### Key Components

| Component | Responsibility | File |
|-----------|---------------|------|
| `UICoverageTracker` | Per-state widget coverage gap computation (per-action) | `ape/utils/UICoverageTracker.java` (NEW) |
| `ActivityBudgetTracker` | Per-activity iteration budgeting | `ape/utils/ActivityBudgetTracker.java` (NEW) |
| `InputValueGenerator` | Heuristic text input by field type | `ape/utils/InputValueGenerator.java` (NEW) |
| `MopData.load()` | Parse transitions[] for WTG | `ape/utils/MopData.java` (MODIFIED) |
| `MopScorer.scoreWtg()` | WTG-based priority boost | `ape/utils/MopScorer.java` (MODIFIED) |
| `StatefulAgent.adjustActionsByGUITree()` | Integration: WTG + per-action coverage boosts | `ape/agent/StatefulAgent.java` (MODIFIED) |
| `SataAgent.egreedy()` | Dynamic epsilon decay | `ape/agent/SataAgent.java` (MODIFIED) |
| `SataAgent.selectNewActionNonnull()` | Budget check + restart fallback | `ape/agent/SataAgent.java` (MODIFIED) |
| `ApeAgent.checkInput()` | Use InputValueGenerator | `ape/agent/ApeAgent.java` (MODIFIED) |
| `State.greedyPickLeastVisited()` | Priority tiebreaker for greedy selection | `ape/model/State.java` (MODIFIED) |
| `Config` | 8 new flags + 1 threshold change | `ape/utils/Config.java` (MODIFIED) |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|------------------------|----------------|------|
| UICoverageTracker -- Widget Registration (Name-based IDs) | `UICoverageTracker.registerScreenElements()` | `UICoverageTrackerTest.testRegister*` |
| UICoverageTracker -- Interaction Recording | `UICoverageTracker.recordInteraction()` | `UICoverageTrackerTest.testRecord*` |
| UICoverageTracker -- Coverage Gap | `UICoverageTracker.getCoverageGap()` | `UICoverageTrackerTest.testGap*` |
| UICoverageTracker -- Per-Widget Interaction Count | `UICoverageTracker.getInteractionCount()` | `UICoverageTrackerTest.testInteractionCount*` |
| INV-COV-01..04 | Enforced by implementation | `UICoverageTrackerTest.testInvariants` |
| Per-Action Coverage Boost | `StatefulAgent.adjustActionsByGUITree()` coverage pass | `StatefulAgentTest.testPerActionCoverageBoost` |
| ActivityBudgetTracker -- Allocation | `ActivityBudgetTracker.registerActivity()` | `ActivityBudgetTrackerTest.testRegister*` |
| ActivityBudgetTracker -- Exhaustion + Restart Fallback | `ActivityBudgetTracker.isBudgetExhausted()` | `ActivityBudgetTrackerTest.testExhaust*` |
| INV-BUD-01..03 | Enforced by implementation | `ActivityBudgetTrackerTest.testInvariants` |
| MopData -- WTG Parsing | `MopData.load()` Pass 3 | `MopDataTest.testWtgParsing` |
| MopScorer -- WTG Boost | `MopScorer.scoreWtg()` | `MopScorerTest.testScoreWtg*` |
| INV-WTG-01..03 | Enforced by MopData + MopScorer | `MopDataTest + MopScorerTest` |
| Dynamic Epsilon Decay | `SataAgent.computeDynamicEpsilon()` | `SataAgentTest.testDynamicEpsilon*` |
| INV-EPS-01..03 | Enforced by computeDynamicEpsilon | `SataAgentTest.testEpsilonInvariants` |
| InputValueGenerator -- Detection | `InputValueGenerator.detectCategory()` | `InputValueGeneratorTest.testDetect*` |
| InputValueGenerator -- Generation | `InputValueGenerator.generateForNode()` | `InputValueGeneratorTest.testGenerate*` |
| INV-INP-01..03 | Enforced by implementation | `InputValueGeneratorTest.testInvariants` |
| Greedy Priority Tiebreaker | `State.greedyPickLeastVisited()` | `StateTest.testGreedyTiebreaker*` |
| INV-SEL-01..03 | Enforced by implementation | `StateTest.testGreedyInvariants` |
| Budget Check + Restart in SATA | `SataAgent.selectNewActionNonnull()` | Integration test via device run |
| Config Flags | `Config.java` declarations | `ConfigTest.testNewFlags` |
| ApeAgent.checkInput() Integration | `ApeAgent.checkInput()` | Integration test via device run |

## Goals / Non-Goals

**Goals:**
- Break the ~28% method coverage plateau with structural improvements
- All 6 improvements independently toggleable via Config flags (except greedy tiebreaker, which is always-on)
- Zero regressions -- existing behavior preserved when flags are at defaults or disabled
- Each new class is self-contained, testable without Android runtime
- Priority boosts (MOP, WTG, coverage) influence BOTH the random and greedy action selection paths

**Non-Goals:**
- LLM integration changes (separate track, GH#43/GH#8)
- CEGAR refinement changes (naming subsystem untouched)
- aperv-tool Python changes (separate PR in rv-android)
- Fine-tuning or prompt engineering
- Cross-state navigation scheduler
- BroadcastReceiver/Service triggering from APE-RV (ActionTypes for `am broadcast`/`am startservice`)

## Decisions

**D1: UICoverageTracker uses State object as key, not a structural hash**
The `State` object's `equals()`/`hashCode()` delegates to `StateKey` (activity + naming + widgets[]). This is the canonical state identity in APE-RV. No `getStructuralHash()` method exists or is needed. Using the `State` object directly avoids inventing a parallel identification scheme.

**D2: Widget IDs use Name.toXPath(), not coordinates**
Coordinates (`getBoundsInScreen()`) change after scrolling — the same widget gets different bounds, creating spurious "new" widgets in the tracker. `Name.toXPath()` is scroll-stable, consistent with the model, and 1:1 with `ModelAction.getTarget()`. This is the only identification scheme that satisfies INV-COV-02 (monotonic gap decrease) across scroll events.

**D3: Coverage boost is per-action with state-visit decay**
A per-action boost (weight for untested widgets, zero for tested) creates a differential that steers toward unexplored interactions. The boost decays with state visits: `boost = weight / (1 + stateVisits / 5)`. Without decay, E2E testing showed the boost trapping the agent in complex states (simplenotes SettingsActivity: 27 widgets, -12.56pp regression). The decay provides full boost on first visit (~100) and diminishes after repeated visits (~16 at 25 visits), balancing depth (test all widgets) with breadth (visit all activities).

**D4: ActivityBudgetTracker budget is computed once, not recalculated, and never resets**
Recalculating on every visit would penalize activities that discover new states (and thus new widgets). One-time allocation is simpler and predictable. Budget exhaustion falls through to normal SATA chain when no trivial activity is available. Both hard fallbacks (EVENT_RESTART and MODEL_BACK) were tested and found harmful — restart causes restart loops, BACK causes stuck loops. Fallthrough lets the SATA chain with priority boosts handle exploration naturally.

The budget does NOT reset after restart. Re-exploration of budget-exhausted activities is handled by existing mechanisms: stability counters trigger restarts when the agent stagnates, and dynamic epsilon gives high exploration rate when revisiting states after restart. Additionally, `activityStableRestartThreshold` is activated (changed from MAX_VALUE to 200) to force restart when stuck in one activity — complementing the budget without introducing reset cycles.

**D4.1: Naming refinement resets coverage data — by design**
When NamingFactory refines a State (splits coarse abstraction into finer States), UICoverageTracker loses data for the old State because the new State has a different StateKey. This is correct: refinement means new widgets to discover, so coverage gap resets to 1.0, forcing re-exploration. This aligns with the CEGAR paradigm — discovery of finer structure requires re-exploration.

**D4.2: Infinite scroll handled by activity budget, not coverage tracker**
In infinite scroll screens, each scroll reveals new items with new Names, creating new States. Coverage gap never converges because new widgets keep appearing. The ActivityBudgetTracker limits time spent in such activities, and activityStableRestartThreshold=200 provides a secondary safety net. The coverage tracker does not need special handling for this case.

**D4.3: Activate activityStableRestartThreshold (was disabled)**
The existing `activityStableCounter` in StatefulAgent already counts consecutive same-activity transitions and triggers restart when the threshold is exceeded. The threshold was `Integer.MAX_VALUE` (effectively disabled). Changing it to 200 activates this mechanism, providing a safety net when budget exhaustion + trivial activity heuristic + restart fallback still leave the agent stuck. This uses existing infrastructure rather than inventing new reset logic.

**D5: WTG scoring is in MopScorer, not a separate scorer class**
APE-RV uses MopScorer directly in `adjustActionsByGUITree()`, not a scorer chain like rvsmart. Adding WTG as a method on MopScorer follows the existing pattern (P1: simplicity).

**D6: greedyPickLeastVisited() uses priority as tiebreaker**
The greedy path (85-98% of decisions) previously ignored all priority boosts. Adding priority as a tiebreaker when `visitedCount` is equal makes MOP, WTG, and coverage boosts effective in the dominant selection path. The change is conservative: `visitedCount` remains the primary criterion, priority only breaks ties. This single change amplifies the impact of all other priority-based improvements.

**D7: Dynamic epsilon is coverage-based, not time-based**
Epsilon is derived from `UICoverageTracker.getCoverageGap(currentState)` instead of elapsed time. This eliminates the need to know the total run duration, adapts to app complexity, and works correctly for any timeout. SataAgent accesses the tracker via the `_coverageTracker` field added to StatefulAgent.

**D8: InputValueGenerator is stateless except for rotation counters**
No dependency on Android runtime -- category detection uses strings from GUITreeNode. This enables unit testing without mocking Android APIs.

## API Design

### `UICoverageTracker.registerScreenElements(State state, List<ModelAction> actions)`
- **Pre**: state non-null; actions may be empty
- **Post**: All actions registered with widget IDs derived from `Name.toXPath()` or `ActionType.name()`
- **Error**: None (no-op on null state)

### `UICoverageTracker.recordInteraction(State state, ModelAction action)`
- **Pre**: state and action non-null
- **Post**: Interaction count incremented for the action's widget ID
- **Error**: None (no-op on null args)

### `UICoverageTracker.getCoverageGap(State state) -> float`
- **Pre**: None
- **Post**: Returns [0.0, 1.0]; 1.0 for unknown states
- **Error**: None

### `UICoverageTracker.getInteractionCount(State state, String widgetId) -> int`
- **Pre**: None
- **Post**: Returns >= 0; 0 for unknown state/widget combinations
- **Error**: None

### `ActivityBudgetTracker.isBudgetExhausted(String activityName) -> boolean`
- **Pre**: None
- **Post**: Returns false for unregistered activities
- **Error**: None

### `MopScorer.scoreWtg(String activity, String shortId, MopData data) -> int`
- **Pre**: None (all params nullable)
- **Post**: Returns 0 when data is null or no WTG match; Config.mopWeightWtg otherwise
- **Error**: None

### `InputValueGenerator.generateForNode(GUITreeNode node) -> String`
- **Pre**: node non-null
- **Post**: Returns non-null, non-empty string
- **Error**: None (falls back to StringCache.nextString())

### `State.greedyPickLeastVisited(ActionFilter filter) -> ModelAction`
- **Pre**: At least one action passes filter
- **Post**: Returns action with lowest visitedCount; ties broken by highest priority
- **Error**: Returns null if no action passes filter (unchanged)

## Data Flow

1. `MopData.load(path)` -> parses JSON -> builds widgetData + mopActivities (existing) + **wtgTransitions** (new)
2. `StatefulAgent` constructor -> instantiates `UICoverageTracker` + `ActivityBudgetTracker`
3. Each iteration:
   - `updateStateInternal()` -> registers widgets in `_coverageTracker`, registers activity in `_budgetTracker`
   - `adjustActionsByGUITree()` -> MOP boost -> **WTG boost** -> **per-action coverage boost**
   - `SataAgent.selectNewActionNonnull()` -> **budget check** (with restart fallback) -> LLM hooks -> SATA chain (with **coverage-adaptive epsilon** via `_coverageTracker.getCoverageGap()`)
   - `SataAgent.egreedy()` -> greedy path: `greedyPickLeastVisited()` **with priority tiebreaker**; random path: `randomlyPickAction()` with priority-weighted sampling
   - `ApeAgent.checkInput()` -> **InputValueGenerator** for EditText
   - `moveForward()` -> records interaction in `_coverageTracker`, records iteration in `_budgetTracker`

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| MopData JSON missing `transitions` key | MopData.load() Pass 3 | Skip pass, log info | WTG scoring returns 0 |
| Unknown state in coverage tracker | getCoverageGap() | Return 1.0 | Treated as fully unexplored |
| Unknown activity in budget tracker | isBudgetExhausted() | Return false | No budget constraint |
| Budget exhausted, no trivial activity | selectNewActionNonnull() | Fall through to SATA chain | Normal exploration continues |
| Null GUITreeNode in InputValueGenerator | generateForNode() | Fall back to StringCache | Existing random behavior |

## Risks / Trade-offs

- **[Memory growth]** UICoverageTracker and ActivityBudgetTracker grow with discovered states/activities. For a 10-minute run this is negligible (~1000 entries max). -> No mitigation needed.
- **[WTG accuracy]** Static WTG may not reflect runtime navigation (dynamic content, conditional flows). -> WTG boost is additive (200), not dominant -- wrong predictions just don't help, they don't hurt.
- **[Epsilon decay interaction with LLM]** Dynamic epsilon changes exploration rate, which affects when LLM stagnation hook fires. -> Both are independently configurable; LLM is a separate concern.
- **[Budget too aggressive]** Low baseBudget + budgetPerWidget may force premature activity switching. -> Defaults (50 + 5/widget) are conservative; tunable via Config. MODEL_BACK fallback provides soft navigation pressure without destructive restarts.
- **[Budget exhaustion fallback]** RESOLVED after 3 iterations: (1) EVENT_RESTART caused restart loops (40+/run, -3.39pp). (2) MODEL_BACK caused stuck loops (78 useless BACKs/run burning 67% of time, -1.10pp on 20 APKs overnight). (3) Fallthrough to SATA chain is correct: budget is advisory, exploration continues normally. Validated: +3.67pp method, +6.01pp MOP on cryptoapp 3 reps.
- **[Logging overhead reduces throughput]** E2E testing showed aperv executed ~14% fewer steps than ape (229 vs 267 per 5 min run). Three Logger.iformat() calls per step (MOP+WTG+Coverage) contribute to this. Fix: only log when at least one action was boosted (skip silent passes).
- **[Greedy tiebreaker changes behavior]** When multiple unvisited actions exist, the tiebreaker favors MOP/WTG/coverage-boosted actions instead of array order. -> This is strictly better than arbitrary order. INV-SEL-01 guarantees visitedCount remains the primary criterion.
- **[Naming refinement resets coverage]** When NamingFactory refines a State, coverage data is lost for the old State. -> By design: refinement means new widgets to discover; re-exploration is correct (see D4.1).
- **[Infinite scroll never converges]** Coverage gap never reaches 0 for infinite scroll screens. -> Handled by activity budget limiting time per activity (see D4.2).

## Testing Strategy

| Layer | What | How | Count |
|-------|------|-----|-------|
| Unit | UICoverageTracker logic (Name-based IDs, per-action boost) | JUnit, no Android deps | ~10 tests |
| Unit | ActivityBudgetTracker logic | JUnit, no Android deps | ~5 tests |
| Unit | InputValueGenerator detection + generation | JUnit with mock GUITreeNode | ~10 tests |
| Unit | MopData WTG parsing | JUnit with test JSON | ~4 tests |
| Unit | MopScorer.scoreWtg | JUnit | ~4 tests |
| Unit | Dynamic epsilon computation | JUnit | ~4 tests |
| Unit | State.greedyPickLeastVisited tiebreaker | JUnit with mock State | ~5 tests |
| Integration | Full run with cryptoapp.apk | `adb shell app_process` 1 min | Manual |

## Known Limitations

### GUI-only exploration cannot reach BroadcastReceivers and Services

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Methods reachable only through non-GUI paths are invisible to the explorer:

- **BroadcastReceivers**: code triggered by `ACTION_BOOT_COMPLETED`, `CONNECTIVITY_CHANGE`, SMS, etc.
- **Services**: code in `onStartCommand()`, `onBind()`, `onHandleIntent()` for background work
- **ContentProviders**: internal queries/inserts

The `RvsecAnalysisClient` in rvsec-gator (`getEntryPoints()`, line 252-266) currently iterates only over `output.getActivities()`, ignoring Services and Receivers as entry points. This means methods reachable only via these components do not appear in the `reachableSet` and are excluded from MOP reachability analysis.

**Current state in GATOR**: The `DefaultXMLParser` already parses `<service>` and `<receiver>` tags from AndroidManifest.xml (lines 415-436) including their IntentFilters. Services are stored in `services` (ArrayList) with `getServices()` accessor. Receivers are stored in `receivers` (ArrayList) but have **no public getter** (`getReceivers()` is missing from the XMLParser interface).

**Required changes (separate change in rv-android, PREREQUISITE for gh9)**:

1. **XMLParser**: add `getReceivers()` accessor (field already exists)
2. **RvsecAnalysisClient.getEntryPoints()**: iterate over Services (`xml.getServices()`) and Receivers (`xml.getReceivers()`), adding their lifecycle methods as entry points (`onStartCommand`, `onBind`, `onReceive`, etc.)
3. **JSON output**: include Services/Receivers in a new `components[]` section or extend `windows[]`

This must be done BEFORE gh9 implementation so the static analysis JSON consumed by APE-RV already includes the enriched entry points and reachability data. Without this, MOP reachability for methods behind Services/Receivers is incomplete, limiting the effectiveness of WTG navigation and MOP-guided scoring.

Adding ActionTypes in APE-RV for `am broadcast` and `am startservice` to exercise non-GUI components directly is a non-goal for gh9 but could be a separate change.

## Resolved Questions

- **Budget resetable after RESTART?** NO. Budget is one-shot. Re-exploration is handled by existing mechanisms: stability counters, periodic restart, and dynamic epsilon. Activating `activityStableRestartThreshold=200` (was MAX_VALUE) provides the safety net.
- **WTG includes MenuItem clicks?** YES. Empirical data from cryptoapp (4/17 = 24%) and ApkTrack (5/6 = 83%) confirms MenuItem clicks are a significant portion of WTG transitions. MenuItem `widgetName` values reliably match `shortId` from runtime `getResourceID()`.
- **Budget exhaustion fallback?** NONE — fallthrough to SATA chain. Tested 3 approaches: EVENT_RESTART (restart loop, -3.39pp), MODEL_BACK (stuck loop, -1.10pp on 20 APKs), fallthrough (correct, +3.67pp on cryptoapp). Budget is advisory — when exhausted without trivial activity, normal exploration with priority boosts is the best strategy.
- **Logging overhead acceptable?** Conditional logging needed. 3 Logger.iformat() per step reduced throughput ~14% (229 vs 267 steps in 5 min). Fix: only log when boostedCount > 0.
