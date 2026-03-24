## Context

APE-RV reached a ~28% method coverage plateau (see proposal.md). Five independent algorithmic improvements target structural bottlenecks: lack of widget-level coverage tracking, no activity budgets, unused WTG data in static analysis JSON, fixed epsilon, and random text input. All changes touch the `ape/utils/` and `ape/agent/` packages. No external dependencies are introduced.

## Architecture

```
ApeAgent.checkInput()
  └── InputValueGenerator.generateForNode(node)          [NEW]

StatefulAgent
  ├── _coverageTracker: UICoverageTracker                 [NEW field]
  ├── _budgetTracker: ActivityBudgetTracker               [NEW field]
  ├── updateStateInternal()
  │     ├── register widgets → _coverageTracker
  │     └── register activity → _budgetTracker
  ├── moveForward()
  │     ├── record interaction → _coverageTracker
  │     └── record iteration → _budgetTracker
  └── adjustActionsByGUITree()
        ├── base priority (existing)
        ├── MOP boost (existing)
        ├── WTG boost → MopScorer.scoreWtg()              [NEW pass]
        └── coverage boost → _coverageTracker.getCoverageGap() [NEW pass]

SataAgent
  ├── selectNewActionNonnull()
  │     └── budget check → _budgetTracker.isBudgetExhausted() [NEW check]
  └── egreedy()
        └── computeDynamicEpsilon()                        [NEW method]

MopData.load()
  ├── Pass 1: reachability[] (existing)
  ├── Pass 2: windows[] (existing)
  └── Pass 3: transitions[] → WTG map                     [NEW pass]

MopScorer
  ├── score() (existing)
  └── scoreWtg()                                           [NEW method]
```

### Key Components

| Component | Responsibility | File |
|-----------|---------------|------|
| `UICoverageTracker` | Per-state widget coverage gap computation | `ape/utils/UICoverageTracker.java` (NEW) |
| `ActivityBudgetTracker` | Per-activity iteration budgeting | `ape/utils/ActivityBudgetTracker.java` (NEW) |
| `InputValueGenerator` | Heuristic text input by field type | `ape/utils/InputValueGenerator.java` (NEW) |
| `MopData.load()` | Parse transitions[] for WTG | `ape/utils/MopData.java` (MODIFIED) |
| `MopScorer.scoreWtg()` | WTG-based priority boost | `ape/utils/MopScorer.java` (MODIFIED) |
| `StatefulAgent.adjustActionsByGUITree()` | Integration: WTG + coverage boosts | `ape/agent/StatefulAgent.java` (MODIFIED) |
| `SataAgent.egreedy()` | Dynamic epsilon decay | `ape/agent/SataAgent.java` (MODIFIED) |
| `ApeAgent.checkInput()` | Use InputValueGenerator | `ape/agent/ApeAgent.java` (MODIFIED) |
| `Config` | 8 new flags | `ape/utils/Config.java` (MODIFIED) |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|------------------------|----------------|------|
| UICoverageTracker — Widget Registration | `UICoverageTracker.registerScreenElements()` | `UICoverageTrackerTest.testRegister*` |
| UICoverageTracker — Interaction Recording | `UICoverageTracker.recordInteraction()` | `UICoverageTrackerTest.testRecord*` |
| UICoverageTracker — Coverage Gap | `UICoverageTracker.getCoverageGap()` | `UICoverageTrackerTest.testGap*` |
| INV-COV-01..04 | Enforced by implementation | `UICoverageTrackerTest.testInvariants` |
| ActivityBudgetTracker — Allocation | `ActivityBudgetTracker.registerActivity()` | `ActivityBudgetTrackerTest.testRegister*` |
| ActivityBudgetTracker — Exhaustion | `ActivityBudgetTracker.isBudgetExhausted()` | `ActivityBudgetTrackerTest.testExhaust*` |
| INV-BUD-01..03 | Enforced by implementation | `ActivityBudgetTrackerTest.testInvariants` |
| MopData — WTG Parsing | `MopData.load()` Pass 3 | `MopDataTest.testWtgParsing` |
| MopScorer — WTG Boost | `MopScorer.scoreWtg()` | `MopScorerTest.testScoreWtg*` |
| INV-WTG-01..03 | Enforced by MopData + MopScorer | `MopDataTest + MopScorerTest` |
| Dynamic Epsilon Decay | `SataAgent.computeDynamicEpsilon()` | `SataAgentTest.testDynamicEpsilon*` |
| INV-EPS-01..03 | Enforced by computeDynamicEpsilon | `SataAgentTest.testEpsilonInvariants` |
| InputValueGenerator — Detection | `InputValueGenerator.detectCategory()` | `InputValueGeneratorTest.testDetect*` |
| InputValueGenerator — Generation | `InputValueGenerator.generateForNode()` | `InputValueGeneratorTest.testGenerate*` |
| INV-INP-01..03 | Enforced by implementation | `InputValueGeneratorTest.testInvariants` |
| Coverage Boost in Action Scoring | `StatefulAgent.adjustActionsByGUITree()` | Integration test via `mvn package` + device run |
| Budget Check in SATA Action Selection | `SataAgent.selectNewActionNonnull()` | Integration test via device run |
| ActivityBudgetTracker — Iteration Counting | `ActivityBudgetTracker.recordIteration()` | `ActivityBudgetTrackerTest.testCount*` |
| Config Flags for Dynamic Epsilon | `Config.java` declarations | `ConfigTest.testEpsilonFlags` |
| ApeAgent.checkInput() Integration | `ApeAgent.checkInput()` | Integration test via device run |
| Config Flag for WTG Weight | `Config.java` declaration | `ConfigTest.testWtgFlag` |
| INV-MOP-05..06 | Enforced by `adjustActionsByGUITree()` + `MopScorer.scoreWtg()` | `MopScorerTest.testScoreWtg*` |

## Goals / Non-Goals

**Goals:**
- Break the ~28% method coverage plateau with structural improvements
- All 5 improvements independently toggleable via Config flags
- Zero regressions — existing behavior preserved when flags are at defaults or disabled
- Each new class is self-contained, testable without Android runtime

**Non-Goals:**
- LLM integration changes (separate track, GH#43/GH#8)
- CEGAR refinement changes (naming subsystem untouched)
- aperv-tool Python changes (separate PR in rv-android)
- Fine-tuning or prompt engineering

## Decisions

**D1: UICoverageTracker uses GUITree structural hash, not State hash**
Widget sets depend on the concrete GUITree (which widgets are visible), not the abstract State (which may merge multiple GUITrees). Using structural hash ensures consistent widget IDs across visits.

**D2: ActivityBudgetTracker budget is computed once, not recalculated**
Recalculating on every visit would penalize activities that discover new states (and thus new widgets). One-time allocation is simpler and predictable.

**D3: WTG scoring is in MopScorer, not a separate scorer class**
APE-RV uses MopScorer directly in `adjustActionsByGUITree()`, not a scorer chain like rvsmart. Adding WTG as a method on MopScorer follows the existing pattern (P1: simplicity).

**D4: Dynamic epsilon is coverage-based, not time-based**
Epsilon is derived from `UICoverageTracker.getCoverageGap(currentState)` instead of elapsed time. This eliminates the need to know the total run duration, adapts to app complexity, and works correctly for any timeout (1 min, 10 min, 3 hours). SataAgent accesses the tracker via the inherited `_coverageTracker` field from StatefulAgent. No modification to Monkey.java or ApeAgent.beginMillis needed.

**D5: InputValueGenerator is stateless except for rotation counters**
No dependency on Android runtime — category detection uses strings from GUITreeNode. This enables unit testing without mocking Android APIs.

## API Design

### `UICoverageTracker.registerScreenElements(String stateHash, List<GUITreeNode> widgets)`
- **Pre**: stateHash non-null; widgets may be empty
- **Post**: All interactable widgets registered for the state
- **Error**: None (no-op on null stateHash)

### `UICoverageTracker.getCoverageGap(String stateHash) -> float`
- **Pre**: None
- **Post**: Returns [0.0, 1.0]; 1.0 for unknown states
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

## Data Flow

1. `MopData.load(path)` → parses JSON → builds widgetData + mopActivities (existing) + **wtgTransitions** (new)
2. `StatefulAgent` constructor → instantiates `UICoverageTracker` + `ActivityBudgetTracker`
3. Each iteration:
   - `updateStateInternal()` → registers widgets in `_coverageTracker`, registers activity in `_budgetTracker`
   - `adjustActionsByGUITree()` → MOP boost → **WTG boost** → **coverage boost**
   - `SataAgent.selectNewActionNonnull()` → **budget check** → LLM hooks → SATA chain (with **coverage-adaptive epsilon** via `_coverageTracker.getCoverageGap()`)
   - `ApeAgent.checkInput()` → **InputValueGenerator** for EditText
   - `moveForward()` → records interaction in `_coverageTracker`, records iteration in `_budgetTracker`

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| MopData JSON missing `transitions` key | MopData.load() Pass 3 | Skip pass, log info | WTG scoring returns 0 |
| Unknown stateHash in coverage tracker | getCoverageGap() | Return 1.0 | Treated as fully unexplored |
| Unknown activity in budget tracker | isBudgetExhausted() | Return false | No budget constraint |
| Null GUITreeNode in InputValueGenerator | generateForNode() | Fall back to StringCache | Existing random behavior |

## Risks / Trade-offs

- **[Memory growth]** UICoverageTracker and ActivityBudgetTracker grow with discovered states/activities. For a 10-minute run this is negligible (~1000 entries max). → No mitigation needed.
- **[WTG accuracy]** Static WTG may not reflect runtime navigation (dynamic content, conditional flows). → WTG boost is additive (200), not dominant — wrong predictions just don't help, they don't hurt.
- **[Epsilon decay interaction with LLM]** Dynamic epsilon changes exploration rate, which affects when LLM stagnation hook fires. → Both are independently configurable; LLM is a separate concern.
- **[Budget too aggressive]** Low baseBudget + budgetPerWidget may force premature activity switching. → Defaults (50 + 5/widget) are conservative; tunable via Config.

## Testing Strategy

| Layer | What | How | Count |
|-------|------|-----|-------|
| Unit | UICoverageTracker logic | JUnit, no Android deps | ~8 tests |
| Unit | ActivityBudgetTracker logic | JUnit, no Android deps | ~5 tests |
| Unit | InputValueGenerator detection + generation | JUnit with mock GUITreeNode | ~10 tests |
| Unit | MopData WTG parsing | JUnit with test JSON | ~4 tests |
| Unit | MopScorer.scoreWtg | JUnit | ~4 tests |
| Unit | Dynamic epsilon computation | JUnit | ~4 tests |
| Integration | Full run with cryptoapp.apk | `adb shell app_process` 1 min | Manual |

## Open Questions

- Should ActivityBudgetTracker budget be resetable (e.g., after RESTART)? Current design: no reset.
- Should the coverage boost weight be proportional to MOP density of the state? Current design: flat weight.
- Should WTG transitions include menu item clicks (widgetClass=MenuItem)? Current design: yes, all click types.
