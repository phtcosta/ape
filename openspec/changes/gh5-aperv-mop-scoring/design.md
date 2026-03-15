## Context

The APE-RV comparison experiment (169 APKs, 1014 tasks) confirmed MOP guidance has a statistically significant but small effect (~1pp). Two root causes were identified:

1. **Weight imbalance**: MOP boost (+500) overwhelms base SATA priority (~32), making MOP-boosted actions always win regardless of exploration value. 47% of APKs show identical traces between `sata` and `sata_mop`.
2. **No navigation influence**: MOP scoring only operates inside `adjustActionsByGUITree()`, affecting action selection within a state. The navigation decisions in `SataAgent` (ABA, trivial activity) that determine *which state to visit next* ignore MOP data entirely.

This change addresses both by rebalancing weights and adding MOP density as a tiebreaker in state navigation. See proposal.md and GitHub Issue #5.

## Architecture

```
Config.java                    MopScorer.java
  ├── mopDataPath                ├── score(activity, shortId, data)
  ├── mopWeightDirect (100)      └── stateMopDensity(state, data)
  ├── mopWeightTransitive (60)
  └── mopWeightActivity (20)
         │                              │
         ▼                              ▼
StatefulAgent.java              SataAgent.java
  ├── adjustActionsByGUITree()    ├── selectNewActionEarlyStageForABAInternal()
  │     MOP pass: score()         │     prefer greedy state with higher MOP density
  │     telemetry: log counts     ├── selectNewActionForTrivialActivity()
  └── _mopData field              │     prefer path to MOP-rich trivial activity
                                  └── (both use MopScorer.stateMopDensity as tiebreaker)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.mopWeight*` | Load configurable MOP weights from ape.properties | `ape.properties` keys | `int` constants |
| `MopScorer.score()` | Compute action-level MOP boost using configurable weights | activity, shortId, MopData | `int` boost |
| `MopScorer.stateMopDensity()` | Count MOP-reachable widgets in a state's action list | State, MopData | `int` count |
| `StatefulAgent.adjustActionsByGUITree()` | Apply MOP boosts + log telemetry | state actions, MopData | boosted priorities + log line |
| `SataAgent` ABA/trivial navigation | Use MOP density as tiebreaker for state selection | greedy states, MopData | preferred state |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| MopScorer configurable weights | `MopScorer.score()` reads from Config | Device validation: log output shows correct boost values |
| Config weight keys | `Config.java` 3 new fields | Device validation: override via ape.properties |
| Navigation MOP density | `MopScorer.stateMopDensity()` + SataAgent tiebreaker | Device validation: logcat shows MOP-preferred state |
| Telemetry logging | `StatefulAgent.adjustActionsByGUITree()` log line | Device validation: grep logcat for `[APE-RV] MOP boost` |
| INV-MOP-03 (additive) | `adjustActionsByGUITree()` uses `getPriority() + boost` | Invariant preserved by code structure |
| INV-MOP-04 (null skip) | `if (_mopData != null)` guard | Invariant preserved by code structure |

## Goals / Non-Goals

**Goals:**
- MOP boost influences action selection without dominating SATA heuristic
- Navigation decisions (ABA, trivial activity) incorporate MOP density as tiebreaker
- Weights configurable via `ape.properties` for experiment tuning
- Telemetry logging for post-experiment analysis of MOP impact

**Non-Goals:**
- Python-side changes in rv-android (aperv-tool/tool.py) — tracked separately
- Changing the MopData JSON parsing or format
- Adding a new exploration strategy or mode
- Automated test suite (project has none; validation is on real devices)

## Decisions

### D1: Weight scale — 100/60/20 (not 50/30/10 or 200/120/40)

The base SATA priorities in `adjustActionsByGUITree()` are: unvisited +20, aliased actions +N×basePriority, same-activity transition +10. Total range for a typical action is ~32-50. The new weights (100/60/20) ensure MOP boost is influential (2-3x base) but not dominant (was 15x at 500). This preserves SATA's ability to discover new states via unvisited actions while still steering toward MOP-reachable widgets.

**Alternative: 50/30/10** — too weak; MOP boost would be comparable to the unvisited bonus (+20), making it noise rather than signal.
**Alternative: 200/120/40** — still too strong; a direct-MOP action at 200 would almost always win over an unvisited non-MOP action at ~50.

### D2: Navigation tiebreaker (not primary selector)

MOP density is used as a **tiebreaker** in ABA and trivial-activity navigation, not as the primary selector. The primary selector remains visit count (coldest state). When two candidate states have equal visit counts, the one with higher MOP density wins.

**Rationale**: Making MOP density the primary selector would bias exploration toward a few MOP-rich activities, reducing overall coverage. The experiment showed that exploration breadth matters — 47% of APKs had deterministic traces precisely because MOP dominated.

**Alternative: MOP density as primary selector** — rejected; would reduce exploration breadth.
**Alternative: Weighted combination (0.7×visitScore + 0.3×mopScore)** — rejected; introduces a tuning parameter with unclear optimal value (P1: simplicity).

### D3: MOP density metric — widget count in state (not weighted score)

`MopScorer.stateMopDensity(State, MopData)` counts the number of actions in the state whose widget has any MOP reachability (direct or transitive). It does not weight by direct/transitive distinction.

**Rationale**: For navigation purposes, we want to know "how many MOP-reachable widgets can I interact with if I go to this state?" The direct/transitive weighting is already applied at action-selection time by `score()`. Adding it again at navigation time would double-count.

### D4: `_mopData` access from SataAgent — protected getter (not change field visibility)

`StatefulAgent._mopData` is `private final`. SataAgent needs access for navigation tiebreaker. Add a `protected MopData getMopData()` getter in StatefulAgent rather than changing the field to `protected`, because: (a) a getter preserves encapsulation and makes the access point greppable, (b) other subclasses (RandomAgent, ApeAgent) don't need direct field access.

### D5: Telemetry format — one log line per state

A single log line per `adjustActionsByGUITree()` call: `[APE-RV] MOP boost: state=<activity>#<stateKey>, boosted=<N>/<total>, maxBoost=<value>`. This is greppable and parseable with standard tools.

## API Design

### `MopScorer.score(String activity, String shortId, MopData data)` → `int`

Unchanged signature. Implementation changes: reads weights from `Config.mopWeightDirect`, `Config.mopWeightTransitive`, `Config.mopWeightActivity` instead of hardcoded 500/300/100.

- **Precondition**: `data` may be null (returns 0)
- **Postcondition**: return value >= 0
- **Error**: never throws

### `MopScorer.stateMopDensity(State state, MopData data)` → `int` (NEW)

Counts actions in the state that have MOP reachability. Iterates `state.getActions()`, filters for `requireTarget() && isValid()`, extracts shortId, checks `data.getWidget(activity, shortId) != null || data.activityHasMop(activity)`.

- **Precondition**: `state` non-null, `data` may be null (returns 0)
- **Postcondition**: return value >= 0
- **Error**: never throws

### `Config` new fields

```java
public static final int mopWeightDirect = Config.getInteger("ape.mopWeightDirect", 100);
public static final int mopWeightTransitive = Config.getInteger("ape.mopWeightTransitive", 60);
public static final int mopWeightActivity = Config.getInteger("ape.mopWeightActivity", 20);
```

## Data Flow

1. **Startup**: `Config` loads `ape.mopWeightDirect/Transitive/Activity` from ape.properties (defaults: 100/60/20)
2. **Per-state action scoring** (`adjustActionsByGUITree()`):
   - Base SATA priority assigned (lines 1067-1114)
   - MOP pass: `MopScorer.score()` reads Config weights, computes boost, adds to priority
   - Telemetry: count boosted actions, log summary line
3. **Navigation** (ABA / trivial activity in SataAgent):
   - When selecting among candidate greedy states with equal visit count, call `MopScorer.stateMopDensity()` for each candidate
   - Prefer the state with higher MOP density

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Invalid weight in ape.properties | `Config.getInteger()` | Default value used | Logged by Config loader |
| `_mopData` is null | No mopDataPath configured | Skip MOP pass entirely | No MOP boost applied |
| State has no actions | Empty action list | `stateMopDensity()` returns 0 | Tiebreaker has no effect |

## Risks / Trade-offs

- **[Risk] New weights may still not produce observable effect on some APKs** → Mitigation: weights are configurable; experiment can tune via ape.properties without code changes.
- **[Risk] Navigation tiebreaker adds conditional logic to hot path (ABA selection)** → Mitigation: `stateMopDensity()` iterates state actions once; states typically have <50 actions. Negligible cost.
- **[Risk] Telemetry logging adds I/O per state** → Mitigation: single log line per state; APE already logs extensively.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Device validation | Default weights produce correct boost values | Run `sata_mop` on cryptoapp, grep logcat for boost values | 1 run |
| Device validation | Custom weights override defaults | Push ape.properties with `ape.mopWeightDirect=50`, verify in logcat | 1 run |
| Device validation | Telemetry lines present in logcat | Grep for `[APE-RV] MOP boost:` | 1 run |
| Device validation | `sata` mode unaffected (no MOP lines in logcat) | Run `sata` without mopDataPath, verify no MOP log lines | 1 run |
| Experiment | Re-run 169-APK comparison with new weights | Docker-based experiment (same protocol as original) | 1014 tasks |

## Open Questions

None — all design decisions are resolved.
