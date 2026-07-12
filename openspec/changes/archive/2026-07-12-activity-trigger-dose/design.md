# Design: activity-trigger-dose

## Context

The stagnation activity launcher (`activity-frontier`, amended by the open change
`mop-activity-consumers`) fires on the exact-equality gate
`graphStableCounter == graphStableRestartThreshold / 2` in
`SataAgent.shouldTriggerAtStagnation` (`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:639-642`),
i.e. once per 50-step stagnation episode. Empirical dose (cmpft4 frontier arm, 657 traces): 114
launches, 0.17/trace. cmpft5's redesign (Gate 0 report, option 1) needs the launcher to be the live A′
consumer in both arms, which requires (a) a configurable, lower firing threshold and (b) a safety cap
so a dead-end app cannot degenerate into a launch-storm. Constraints: defaults must preserve current
behavior byte-identically (frozen gh43 arms, INV-APV-17); the change must not touch eligibility,
ordering, or step semantics owned by `activity-frontier`/`mop-activity-consumers`; P1 minimality.

## Architecture

No new components. Two Config knobs feed the existing gate, plus one counter field in `SataAgent`:

```
Config.activityTriggerStagnationStep ─┐
Config.activityTriggerMaxPerRun ──────┤
graphStableCounter (StatefulAgent) ───┼─> SataAgent.shouldTriggerAtStagnation(...)  [pure static]
_activityTriggerLaunchCount (new) ────┘        │ true
                                               v
                                    selectTriggerCandidate → ActivityTriggerAction
                                               │ (on return of the action)
                                               v
                                    _activityTriggerLaunchCount++ ; graphStableCounter = 0
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.activityTriggerStagnationStep` | firing threshold (stagnation steps between launches) | `ape.properties` | int, default 50 |
| `Config.activityTriggerMaxPerRun` | per-run launch budget, 0 = unlimited | `ape.properties` | int, default 0 |
| `SataAgent.shouldTriggerAtStagnation` | pure firing predicate (extended) | enabled, hasMopData, counter, step, launchesSoFar, maxPerRun | boolean |
| `SataAgent._activityTriggerLaunchCount` | counts `EVENT_TRIGGER_ACTIVITY` actions returned this run | — | int field |
| `Config.rvExemptReasons()` | kill-switch registry completeness (INV-ARCH-06) | — | +2 exempt entries |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| Configurable cadence (delta, Stagnation-Triggered Activity Launch) | `shouldTriggerAtStagnation` compares `counter == step` | `ActivityFrontierTest` new cases: step=10 fires at 10/20-after-reset, not at 50 |
| INV-CT-11 (default preserves behavior) | default 50 == old `100/2` | existing `ActivityFrontierTest:111-123` cases re-pass with defaults |
| INV-CT-12 (cap) | predicate returns false when `maxPerRun > 0 && launchesSoFar >= maxPerRun` | new cases: cap 2 → 3rd fire blocked; cap 0 → unlimited |
| Counter increments only on actual launch | increment where `ActivityTriggerAction` is returned (`SataAgent.java:430-436`) | unit: candidate=null path does not increment |
| INV-ARCH-06 registry completeness | `rvExemptReasons()` + guard-test expected set | `ApePureModeKillSwitchTest` updated |

## Goals / Non-Goals

**Goals:**
- Launcher dose configurable per arm via `ape.properties` (tool.py-mappable snake_case keys).
- Defaults reproduce today's behavior exactly (no re-baseline of frozen arms).
- Launch-storm bound available for the experiment arms.

**Non-Goals:**
- No change to candidate eligibility (exported/permission/non-main/unvisited/denylist), ordering
  (`triggerMopFirst`), `EVENT_TRIGGER_ACTIVITY` semantics, or `decision_source=Component` attribution.
- No new telemetry (existing `[APE-RV] Triggering activity:` line is the dose signal).
- No change to the LLM stagnation hook's `== graphStableRestartThreshold / 2` point (`SataAgent.java:396`).
- No tool.py / arm changes (rv-android repo; experiment-session task).

## Decisions

1. **Exact-equality on a dedicated step vs `%`-periodic check.** Keep exact equality
   (`counter == step`): `graphStableCounter` already resets to 0 both on graph growth
   (`StatefulAgent.java:1310-1313`) and on every launch (`SataAgent.java:431`), so equality alone
   yields periodicity under sustained stagnation, and INV-CT-05 (once per episode) keeps its
   meaning with "episode" = interval between resets. A `%` check would fire spuriously when the
   counter overshoots during buffered/replayed steps and double-fires within an episode.
2. **Independent step flag vs deriving from `graphStableRestartThreshold`.** Independent flag:
   deriving (e.g. `threshold/K`) couples launcher dose to the restart heuristic; experiment arms
   must be able to set dose without touching restart behavior. Default 50 keeps the old coupling
   *numerically* without keeping it *structurally*.
3. **Cap checked inside the pure predicate vs at the call site.** Inside
   `shouldTriggerAtStagnation`: keeps the whole firing decision in the one pure, JVM-testable seam
   (established pattern — the existing tests target this method directly). The counter increments at
   the call site where the action is actually returned, so a fired-but-no-candidate step does not
   consume budget.
4. **Registry bucket: `rvExemptReasons()` vs `rvForcedOffValues()`.** Exempt, with reason
   "launcher sub-param; inert when activityTriggerEnabled is forced false" — same pattern as
   `activityBaseBudget`/`activityBudgetPerWidget` (sub-params of a forced-off master gate). Forcing
   an int to 0 would be wrong anyway (0 is not inert for the step semantics; for the cap 0 means
   unlimited).
5. **LLM hook left untouched.** With step ≠ 50 the LLM stagnation point and the launcher point
   decouple; the launcher block already runs after the LLM hooks, so at step == 50 with LLM enabled
   the LLM keeps precedence (unchanged text in the spec). cmpft5 runs LLM OFF.

## API Design

### `static boolean shouldTriggerAtStagnation(boolean enabled, boolean hasMopData, int graphStableCounter, int stagnationStep, int launchesSoFar, int maxPerRun)`

- Pre: `stagnationStep > 0` (Config loader clamps ≤0 to the default 50 — a 0 step would fire every
  step at counter 0); `launchesSoFar >= 0`; `maxPerRun >= 0`.
- Post: true iff `enabled && hasMopData && graphStableCounter == stagnationStep && (maxPerRun == 0 || launchesSoFar < maxPerRun)`.
- Signature replaces the current 4-arg form (the old `graphStableRestartThreshold` param and its `/2`
  disappear); the two call/test sites are updated in this change. No deprecation shim (P3).

## Data Flow

`ape.properties` → `Config` statics (after apePureMode forcing; both flags exempt) →
`selectNewActionNonnull` trigger block passes `Config.activityTriggerStagnationStep`,
`_activityTriggerLaunchCount`, `Config.activityTriggerMaxPerRun` → on returned
`ActivityTriggerAction`: `_activityTriggerLaunchCount++`, `graphStableCounter = 0` → existing
`[APE-RV] Triggering activity:` log line = dose telemetry consumed by the cmpft5 Gate 0 dose gate.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `activityTriggerStagnationStep <= 0` in properties | operator typo | clamp to default 50 at load, log the clamp | run proceeds with default cadence |
| `activityTriggerMaxPerRun < 0` in properties | operator typo | clamp to 0 (unlimited) at load, log the clamp | run proceeds uncapped |

(No runtime failure modes: the predicate is pure; launch dispatch errors are owned by
`activity-frontier` and unchanged.)

## Risks / Trade-offs

- [Low step still yields no launches when the eligible pool is empty (all activities visited or
  denylisted)] → expected fall-through (INV-CT-08 semantics unchanged); the cmpft5 dose gate measures
  realized dose in the smoke before the full run, which is exactly its purpose.
- [Aggressive step (e.g. 5) could dominate exploration in dead-end apps] → `activityTriggerMaxPerRun`
  set in the experiment arms (recommended: step 10, cap 8 for a 5-min run); defaults unchanged.
- [Same-requirement collision with open change `mop-activity-consumers`] → delta written against its
  amended text; archive ordering documented in proposal (mop-activity-consumers first).

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (`ActivityFrontierTest`) | predicate: step equality, cap blocking, cap=0 unlimited, disabled/no-data guards, default==old behavior | direct static calls (existing pattern) | ~8 new |
| Unit (`ConfigTest` or loader test) | clamp of step≤0 and cap<0 | property-injection pattern used by existing Config tests | ~2 new |
| Guard (`ApePureModeKillSwitchTest`) | registry completeness with 2 new exempt entries | update expected exempt set | 1 updated |
| Integration (device smoke) | dose ≥3 launches/run median on Gate 0 apps with step=10 | cmpft5 Gate 0 (experiment session) | manual gate |

## Open Questions

- Arm values for step/cap (protocol suggests step=10, cap=8) are experiment-session decisions
  (tool.py, rv-android), pre-registered in the revised cmpft5 protocol — not baked into this change.
