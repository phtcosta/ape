## Context

The gh9 exploration enhancements added MOP/WTG/coverage priority boosts to the SATA agent's action selection. These boosts operate on a priority space designed for values 1–60, but use magnitudes of 100–500, creating 13× inflation. A 49-APK experiment (refs #10) shows this causes high variance (-13pp to +30pp) with no net improvement (p=0.078). This design normalizes the boosts to restore exploration balance.

## Architecture

The change touches only the priority computation pipeline in `adjustActionsByGUITree()` and the configuration defaults. No structural changes to components.

```
adjustActionsByGUITree() pipeline:
  [Base priority 1-60] → [MOP boost] → [WTG boost] → [Coverage boost] → [NEW: Cap pass] → Action selection
```

### Key Components

| Component | Responsibility | Change |
|-----------|---------------|--------|
| `Config.java` | Parameter defaults | 6 weight defaults reduced 10×, add `maxBoostCap`, `dynamicEpsilon` → false |
| `StatefulAgent.adjustActionsByGUITree()` | Priority computation | Add capping pass after line 1239 |
| `MopScorer.score()` | MOP scoring logic | No change (reads Config values) |
| `MopScorer.scoreWtg()` | WTG scoring logic | No change (reads Config values) |
| `UICoverageTracker` | Coverage tracking | No change |
| `SataAgent.computeDynamicEpsilon()` | Epsilon calculation | No change (behavior controlled by Config.dynamicEpsilon) |

## Mapping: Spec → Implementation

| Requirement | Implementation | Verification |
|-------------|---------------|-------------|
| MOP weight defaults | `Config.java` lines 128-130 | 5-APK triage: MOP coverage not zero |
| WTG weight default | `Config.java` line 149 | 5-APK triage: WTG boost in logs |
| Coverage boost weight | `Config.java` line 146 | 5-APK triage: coverage boost in logs |
| Per-action boost cap | `StatefulAgent.java` new pass after line 1239 | Log capped actions count |
| INV-SEL-04 | `Config.getInteger("ape.maxBoostCap", 80)` | Value >= 0 enforced by usage |
| INV-SEL-05 | Cap pass positioned after all boost passes | Code review |
| INV-SEL-06 | Cap pass skips `!action.requireTarget()` | Code review |
| Dynamic epsilon default | `Config.java` line 150 | `computeDynamicEpsilon()` returns 0.05 |

## Goals / Non-Goals

**Goals:**
- Reduce action selection variance to be comparable with original APE
- Maintain MOP/WTG/coverage guidance as a meaningful signal (not zero)
- Keep all scoring logic and configurability intact

**Non-Goals:**
- Optimizing weight values for maximum coverage (that's the MadEvolve work on gh10-madevolve)
- Changing the scoring algorithms (MopScorer, UICoverageTracker)
- Changing the SATA dispatch chain or action selection order

## Decisions

### D1: 10× reduction (not 5× or 20×)

10× makes max MOP boost (50) approximately 1.5× the base priority (~32). This is enough to be a meaningful tiebreaker without dominating. A 5× reduction (100) would still allow 75%+ selection probability. A 20× reduction (25) would make MOP nearly invisible.

### D2: Cap at 80 (2× max base priority)

Max base priority after unvisited+transition bonuses is ~40. Cap at 80 means guidance can at most double an action's selection probability. This prevents gravity wells regardless of weight configuration.

### D3: Disable dynamic epsilon rather than fix formula

The dynamic epsilon feedback loop (coverage gap → epsilon → exploration → coverage gap) is fundamentally problematic in short runs. Fixing it (e.g., time-based decay) requires experimentation. Disabling it and using the well-tested static epsilon is the safe path. The feature remains available via config.

## Data Flow

```
Config.mopWeightDirect (50) ──→ MopScorer.score() ──→ action.setPriority(+50)
Config.mopWeightWtg (20) ──→ MopScorer.scoreWtg() ──→ action.setPriority(+20)
Config.coverageBoostWeight (20) ──→ decay formula ──→ action.setPriority(+0..20)
                                                          │
                                        ┌─────────────────┘
                                        ▼
                              Cap pass: if (boost > 80) → set to basePriority + 80
                                        │
                                        ▼
                              randomlyPickAction() / greedyPickLeastVisited()
```

## Risks / Trade-offs

- **Risk**: 10× reduction may be too aggressive — MOP guidance becomes too weak. **Mitigation**: Weights remain configurable; MadEvolve can tune them.
- **Risk**: Disabling dynamic epsilon removes adaptive behavior. **Mitigation**: Static epsilon (0.05) is the APE default, well-tested. Can re-enable via config.
- **Risk**: Cap at 80 may clip legitimate MOP signals in apps with many crypto APIs. **Mitigation**: Cap is configurable (`ape.maxBoostCap`); value 0 disables capping.

## Testing Strategy

| Layer | What | How |
|-------|------|-----|
| Build | Compilation | `mvn package` |
| Unit | Existing tests | `mvn test` (145 tests) |
| Triage | 5-APK evaluation | `evaluate.sh` — compare variance with baseline |
| Full | 49-APK experiment | rv-experiment — statistical comparison with existing results |
