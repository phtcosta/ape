## Purpose

This delta spec adds dynamic epsilon decay to the SATA exploration strategy. The existing fixed epsilon (`ape.defaultEpsilon = 0.05`) provides a constant 5% random exploration rate throughout the run. Dynamic epsilon adapts the exploration rate based on the UI coverage gap of the current state: when many widgets remain untested (high gap), epsilon is high (more diversity); as widgets get tested (low gap), epsilon drops (deeper exploitation). This creates a natural feedback loop — the agent explores broadly in new territory and exploits deeply in familiar territory — without requiring knowledge of the total run duration.

**Scope note**: `egreedy()` is called from `selectNewActionEpsilonGreedyRandomly()`, which is itself one of the last steps in the SATA selection chain. Before reaching epsilon-greedy, `selectNewActionNonnull()` tries: LLM hooks, action buffer, back-to-activity, early-stage forward greedy, trivial activity, and early-stage backward. Many decisions never reach epsilon-greedy. Dynamic epsilon therefore has an **incremental** impact — it improves the quality of the epsilon-greedy decisions that DO occur, but does not affect decisions resolved earlier in the chain. The primary impact amplifier is the greedy priority tiebreaker (action-selection/spec.md), which makes all priority boosts effective in the dominant greedy path.

## ADDED Requirements

### Requirement: Dynamic Epsilon Decay

`SataAgent.egreedy()` SHALL compute a coverage-adaptive epsilon when `Config.dynamicEpsilon` is `true`. The epsilon value SHALL be interpolated between `Config.minEpsilon` and `Config.maxEpsilon` based on the current state's UI coverage gap:

```
epsilon = minEpsilon + (maxEpsilon - minEpsilon) × coverageGap
```

Where `coverageGap` is obtained from `UICoverageTracker.getCoverageGap(currentState)` where `currentState` is the `State` object from `StatefulAgent.newState`. The SataAgent accesses the tracker via the `getCoverageTracker()` accessor inherited from `StatefulAgent`.

This approach is superior to time-based decay because:
- It adapts to app complexity: simple apps converge quickly, complex apps maintain diversity longer
- It is independent of timeout duration — works correctly for 1-minute tests and 3-hour experiments alike
- It creates a feedback loop: test widgets → gap drops → ε drops → more greedy → test remaining widgets

When `Config.dynamicEpsilon` is `false`, the existing behavior SHALL be preserved: epsilon is the fixed value `Config.defaultEpsilon`.

#### Scenario: New state (high gap)
- **WHEN** `egreedy()` is called in a state with coverageGap=1.0 (no widgets tested yet) and maxEpsilon=0.15, minEpsilon=0.02
- **THEN** the effective epsilon SHALL be 0.15
- **AND** the probability of random action selection (exploration path) SHALL be 0.15

#### Scenario: Partially explored state
- **WHEN** `egreedy()` is called in a state with coverageGap=0.5 (half widgets tested)
- **THEN** the effective epsilon SHALL be 0.085 (midpoint between 0.15 and 0.02)

#### Scenario: Well-explored state (low gap)
- **WHEN** `egreedy()` is called in a state with coverageGap=0.1 (90% widgets tested)
- **THEN** the effective epsilon SHALL be 0.033

#### Scenario: Fully explored state
- **WHEN** `egreedy()` is called in a state with coverageGap=0.0 (all widgets tested)
- **THEN** the effective epsilon SHALL be 0.02 (minEpsilon)

#### Scenario: Unknown state (cold start)
- **WHEN** `egreedy()` is called before any state is registered in UICoverageTracker
- **THEN** `getCoverageGap()` returns 1.0 (INV-COV-03)
- **AND** the effective epsilon SHALL be 0.15 (maxEpsilon)

#### Scenario: Oscillation between states
- **WHEN** the agent moves from a well-explored state (gap=0.1) to a new state (gap=1.0)
- **THEN** epsilon SHALL jump from 0.033 to 0.15 in the new state
- **AND** this is correct behavior — explore new territory, exploit familiar territory

#### Scenario: Works for any timeout
- **WHEN** the agent runs for 3 hours on a complex app
- **THEN** epsilon SHALL remain high as long as coverage gaps are high, regardless of elapsed time
- **AND** epsilon SHALL only drop when widgets are actually tested

#### Scenario: Dynamic epsilon disabled
- **WHEN** `Config.dynamicEpsilon` is `false`
- **THEN** `egreedy()` SHALL use the fixed `Config.defaultEpsilon` value (default 0.05)
- **AND** behavior SHALL be identical to the pre-change implementation

### Requirement: Config Flags for Dynamic Epsilon

`Config.java` SHALL declare the following flags:

| Flag | Property Key | Type | Default | Description |
|------|-------------|------|---------|-------------|
| `dynamicEpsilon` | `ape.dynamicEpsilon` | boolean | `true` | Enable coverage-adaptive epsilon |
| `maxEpsilon` | `ape.maxEpsilon` | double | `0.15` | Epsilon when coverage gap is 1.0 (fully unexplored) |
| `minEpsilon` | `ape.minEpsilon` | double | `0.02` | Epsilon when coverage gap is 0.0 (fully explored) |

#### Scenario: Config flags loaded from properties
- **WHEN** `ape.properties` contains `ape.maxEpsilon=0.20` and `ape.minEpsilon=0.01`
- **THEN** `Config.maxEpsilon` SHALL be 0.20 and `Config.minEpsilon` SHALL be 0.01

#### Scenario: Default values when not set
- **WHEN** no epsilon-related flags are present in `ape.properties`
- **THEN** dynamic epsilon SHALL be enabled with maxEpsilon=0.15, minEpsilon=0.02

## Invariants

- **INV-EPS-01**: The effective epsilon SHALL always be in the closed interval [`Config.minEpsilon`, `Config.maxEpsilon`].
- **INV-EPS-02**: When `Config.dynamicEpsilon` is `false`, the behavior of `egreedy()` SHALL be identical to the pre-change implementation using fixed `Config.defaultEpsilon`.
- **INV-EPS-03**: When `UICoverageTracker` is null (tracker not initialized), `egreedy()` SHALL fall back to `Config.defaultEpsilon` (same as dynamicEpsilon=false).
