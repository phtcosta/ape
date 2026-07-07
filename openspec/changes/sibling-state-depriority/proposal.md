# sibling-state-depriority

## Why

The load-bearing justification is refinement exhaustion. `NamingFactory` stops refining an activity once its state count reaches `maxStatesPerActivity` (default 10 — the cap is enforced at `NamingFactory.java:276,1176`). Past that point the model can no longer split or merge to resolve fragmentation: any further sibling states of one physical screen are organic growth the naming lattice cannot fix. Every such sibling restarts its actions at `visitedCount == 0`, so the SATA least-visited machinery re-exercises the same physical widgets once per sibling and the exploration budget drains into redundant re-interaction. The `coverage-boost-activity-scope` change stopped the *coverage boost* from re-arming on siblings; this change adds the complementary *penalty* for the widgets the agent still re-visits on their own per-state merits.

Supporting evidence (a correlated proxy, not the trigger metric): the cmpft2 trace audit (`rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md` §8) measured 34% of activity rollups ending the run with `liveStates >= 10` (`UICOV-ACT`). That figure counts non-evicted `UICoverageTracker` fragments — an access-ordered LRU capped at `coverageMaxStates = 2000` (`UICoverageTracker.java:59-69`) — whereas this pass triggers on the Model-side population `Graph.getActivityNode(activity).getStates().size()`, which is uncapped but pruned on `Model.rebuild()` (`Graph.java:1244` `removeState`). They are different populations; at the threshold scale (10) neither cap bites, so the proxy tracks the trigger closely. The `hasActivityInteraction` redundancy gate further dampens false positives on legitimately-distinct multi-screen flows (e.g. wizards), where each sibling touches genuinely new widgets and is therefore exempt.

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
- **Dependency (satisfied)**: this change consumes `hasActivityInteraction`, introduced by `coverage-boost-activity-scope`. That change is already archived (`openspec/changes/archive/2026-07-07-coverage-boost-activity-scope`) and its `ui-coverage` delta is merged into the main specs, so the dependency is met — no archive-ordering constraint remains.
- **Soft co-dependency (`activity-frontier`)**: the `wtgBoost > 0` exemption already works today via the pre-existing WTG-MOP pass (`StatefulAgent.java:1455-1458`), so this change is functional standalone. Only the *frontier-specific* half of that exemption (frontier boosts routed through `wtgBoost` by `activity-frontier`) becomes exercisable once `activity-frontier` merges — until then no action carries a frontier-sourced `wtgBoost`, so that clause is simply inert, not broken.
- **Risk**: deprioritizing redundancy in fragmented activities could slow revisits that would have found new transitions; bounded by the floor at priority 1 (actions stay pickable), the novel/MOP exemptions, and the `0 = disabled` rollback knob.
