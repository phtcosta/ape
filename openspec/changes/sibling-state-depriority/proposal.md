# sibling-state-depriority

## Why

The cmpft2 trace audit (`rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md` §8) measured that 34% of activity rollups end the run with 10+ live abstract states of the same activity (`UICOV-ACT liveStates`): NamingFactory refinement plus organic StateKey growth split one physical screen into many sibling states, and the exploration budget drains into re-exercising the same widgets across siblings. The recently validated `coverage-boost-activity-scope` change stopped the *coverage boost* from re-arming on siblings; but the agent still *visits* each sibling's actions on their own merits (per-state `visitedCount` resets on every mint), so redundant re-interaction survives.

Containment inside the naming/CEGAR core was evaluated and rejected: lowering the refinement thresholds risks under-refinement (non-determinism persists, model unreliable), and `maxStatesPerActivity` only blocks *refinement*-minted states — organic states grow unbounded in `ActivityNode`'s set. The lowest-blast-radius containment is agent-side, in the same scoring pass family as the coverage boost.

## What Changes

- Add a sibling-redundancy deprioritization pass to `StatefulAgent.adjustActionsByGUITree`, adjacent to the coverage-boost pass: when the current activity's model state count (`Graph.getActivityNode(activity).getStates().size()`) exceeds `Config.maxStatesPerActivity` (the existing refinement cap, default 10 — no new threshold flag), subtract `Config.siblingStatePenalty` from the priority of actions that are **redundant at activity scope** (their widget already has an activity-scoped interaction, `UICoverageTracker.hasActivityInteraction == true`), floored at priority 1.
- Exemptions (never penalized): actions whose widget is activity-novel (`hasActivityInteraction == false`) — first-touch stays protected; actions carrying `mopBoost > 0` — MOP steering is not suppressed; actions carrying `wtgBoost > 0` — WTG/frontier steering (incl. the frontier boost `activity-frontier` routes through `wtgBoost`) is not suppressed; target-less BACK/MENU — covered by their own cap (`back-menu-pick-cap`).
- New flag `Config.siblingStatePenalty` (`ape.siblingStatePenalty`, default 24; 0 = pass disabled). 24 drops a visited CLICK (priority 32) into the BACK/MENU league (8), making redundant re-clicks in over-fragmented activities roulette-unattractive without removing them.
- Telemetry: `[APE-RV] Sibling depriority: state=<activity>#<stateKey>, penalized=<n>/<total>, siblings=<s>` once per scoring pass when `n > 0`.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `ui-coverage`: add the sibling-redundancy deprioritization requirement to the action-scoring family (consumes `hasActivityInteraction`, the activity-scoped membership introduced by `coverage-boost-activity-scope`).

## Impact

- **Code**: `StatefulAgent.java` (one new pass in `adjustActionsByGUITree`), `Config.java` (one flag). Naming, NamingFactory, Model/Graph untouched.
- **Behavior**: in activities fragmented beyond the refinement cap, redundant actions lose roulette weight; novel, MOP-boosted, and WTG/frontier-steered actions keep full priority. Simple/unfragmented apps see zero change (state count never exceeds the threshold).
- **Telemetry**: one new trace line family; existing lines unchanged.
- **Tests**: JVM-testable via the existing StatefulAgent scoring-pass test pattern plus `UICoverageTracker` fixtures.
- **Archive ordering**: this change consumes `hasActivityInteraction`, defined only in the unarchived `coverage-boost-activity-scope` delta (`ui-coverage`). It MUST be archived AFTER `coverage-boost-activity-scope`.
- **Risk**: deprioritizing redundancy in fragmented activities could slow revisits that would have found new transitions; bounded by the floor at priority 1 (actions stay pickable), the novel/MOP exemptions, and the `0 = disabled` rollback knob.
