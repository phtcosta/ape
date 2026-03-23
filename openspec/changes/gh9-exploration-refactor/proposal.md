## Why

APE-RV reached a ~28% method coverage plateau across 7 variants (MOP weights, LLM, Optuna calibration of 14 parameters). The spread is 1.17pp — calibration on 30 APKs did not generalize to 169. The bottleneck is structural, not parametric: APE-RV is action-centric (tracks visits per ModelAction) but not widget-centric (cannot measure per-screen coverage gaps). Additionally, static analysis data (`transitions[]` from JSON) is available but unused.

Refs: [GH#9](https://github.com/phtcosta/ape/issues/9), `docs/20260323_refatoracao.md`

## What Changes

- Add per-state widget coverage tracking (UICoverageTracker) with coverage gap metric and exploration boost
- Add per-activity iteration budgets (ActivityBudgetTracker) proportional to widget count
- Parse `transitions[]` from static analysis JSON to build Window Transition Graph; boost widgets leading to MOP-reachable activities
- Replace fixed epsilon with coverage-adaptive epsilon (high diversity in new states, exploitation in explored states)
- Replace random text generation with heuristic input based on field type (email, password, URL, phone)
- Add priority tiebreaker to greedy action selection so that MOP/WTG/coverage boosts influence the dominant (85-98%) selection path
- Activate `activityStableRestartThreshold` (was MAX_VALUE/disabled, now 200) to force restart when stuck in one activity
- Add 8 new configuration flags + 1 threshold change, all with sensible defaults and independently toggleable

## Capabilities

### New Capabilities
- `ui-coverage`: Per-state widget coverage tracking, coverage gap computation, exploration boost in action scoring
- `activity-budget`: Per-activity iteration budget allocation and exhaustion detection
- `wtg-navigation`: Window Transition Graph parsing from static analysis JSON and MOP-directed navigation boost
- `heuristic-input`: Context-aware text input generation based on widget type, resourceId, and content description

### Modified Capabilities
- `exploration`: Coverage-adaptive epsilon replaces fixed epsilon in SATA epsilon-greedy selection
- `mop-guidance`: WTG-based scoring pass added after existing MOP priority boost in adjustActionsByGUITree()
- `action-selection`: Priority tiebreaker in State.greedyPickLeastVisited() makes all priority boosts effective in the greedy path

## Prerequisites

- **rv-android: Enrich static analysis with Services/Receivers** — A change in rvsec-gator's `RvsecAnalysisClient` must add Services and BroadcastReceivers as entry points in `getEntryPoints()`, and expose receivers via `XMLParser.getReceivers()`. This enriches the static analysis JSON with reachability data for methods behind non-GUI components, which gh9's WTG navigation and MOP scoring depend on. Must be completed and the new JSON format deployed BEFORE gh9 implementation.

## Impact

- **Agent subsystem** (`ape/agent/`): StatefulAgent gains coverage tracker and budget tracker fields; SataAgent gains budget check (with restart fallback) and dynamic epsilon; ApeAgent.checkInput() uses heuristic input
- **Model subsystem** (`ape/model/`): State.greedyPickLeastVisited() gains priority tiebreaker
- **Utils subsystem** (`ape/utils/`): 3 new classes (UICoverageTracker, ActivityBudgetTracker, InputValueGenerator); MopData gains third parsing pass; MopScorer gains WTG scoring method; Config gains 8 flags
- **aperv-tool** (rv-android): Must propagate new Config flags via ape.properties for new variant
- **Build**: No dependency changes — all new code uses Android SDK and Java standard library
