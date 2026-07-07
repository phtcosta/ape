# back-menu-pick-cap

## Why

The cmpft2 trace audit (657 traces, 219 APKs × 3 reps — `rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md` §8) measured that 25.3% of all executed steps are MODEL_BACK (15.4%) + MODEL_MENU (9.9%), and MODEL_MENU has the worst interacted/discovered ratio of any action type (0.586). A quarter of the exploration budget is spent on two navigation actions instead of touching undiscovered widgets.

Two code-level causes:
1. BACK and MENU are per-state `ModelAction`s with `target == null` (`State.java:63-66`), so the existing `mopTargetPickCap` never covers them (`mopPickKey` returns null for target-less actions), and NamingFactory refinement re-arms their "unvisited" pick paths on every freshly minted sibling state of the same screen — the exact re-fire pathology `mop-target-revisit-cap` fixed for MOP targets. With the default `useActionDiffer=true` (`Config.java:87`) the dominant BACK re-pick channel is **not** the epsilon-greedy short-circuit but the EARLY_STAGE backward harvester (`findGreedyActionBackward`, `SataAgent.java:1278-1283`): it picks the unvisited BACK unconditionally at phase `:441`, before `selectNewActionEpsilonGreedyRandomly` (`:446`) is ever reached, so the epsilon BACK short-circuit (`:468-476`) is shadowed/dead whenever BACK is unvisited. BACK/MENU also leak into the EARLY_STAGE forward roulette candidates (`findGreedyActionForward` → `randomPickWithPriority`, `SataAgent.java:1218-1236`) on first-step/activity-change. The cap must therefore cover **six** discretionary channels — the two EARLY_STAGE sites in addition to the four epsilon-greedy channels.
2. The open-menu gateway boost (`ape.mopWeightOpenMenu`, default 250, gh13 T1.2) lifts an already-visited MENU action from priority 8 to 258 in `adjustActionsByGUITree` (`StatefulAgent.java:1430-1440`), letting it dominate the priority-weighted epsilon-greedy roulette indefinitely.

## What Changes

- Add `Config.backMenuPickCap` (`ape.backMenuPickCap`, default 3; <= 0 = unlimited): a per-run cap on **discretionary** BACK/MENU picks, keyed by `activity + "|" + actionType` (activity-scoped so refinement-minted sibling states cannot re-arm the count).
- Apply the cap across all six discretionary channels sharing one `backMenuPicks` counter: the two EARLY_STAGE sites — the unvisited-BACK pick in `findGreedyActionBackward` (`SataAgent.java:1278-1283`) and the BACK/MENU candidates fed to `randomPickWithPriority` in `findGreedyActionForward` (`:1218-1236`) — plus the four epsilon-greedy channels: the BACK unvisited short-circuit (`:468-476`), the MENU unvisited short-circuit (`:477-485`), the least-visited scan (`greedyPickLeastVisited`, `:511`), and the priority roulette (`:517-523`). Capped BACK/MENU are skipped by the short-circuit/direct-pick sites and filtered from the candidate sets shared by `greedyPickLeastVisited`, `randomlyPickAction`, and the EARLY_STAGE forward roulette.
- Navigation-essential BACK sites stay **uncapped**: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` (last-resort) — the cap bounds redundant re-picks, never the agent's ability to navigate back or escape.
- Gate the open-menu boost by the same eligibility: `adjustActionsByGUITree` applies `mopWeightOpenMenu` to a MENU action only while the activity's MENU key is still under the cap, so a capped MENU cannot re-dominate the roulette through its +250 priority.
- Log `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` once per key, on the pick that reaches the cap (mirrors the `MOP target capped` line).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `action-selection`: add a per-run discretionary pick cap for the target-less BACK/MENU actions (activity + action-type key), bounding their EARLY_STAGE forward/backward picks together with the epsilon-greedy short-circuits and roulette participation; navigation-essential BACK paths are explicitly exempt.
- `mop-guidance`: the open-menu gateway boost (`ape.mopWeightOpenMenu`) becomes conditional on the MENU pick cap — the boost is suppressed for an activity whose MENU key is capped.

## Impact

- **Code**: `SataAgent.java` (the EARLY_STAGE backward direct-pick guard at `findGreedyActionBackward` and the EARLY_STAGE forward candidate filter at `findGreedyActionForward` + the shared least-visited/roulette candidate set + the two epsilon-greedy BACK/MENU short-circuits + static seams and pick-count map, mirroring the `mopTargetPickCap` mechanics), `StatefulAgent.java` (menu-boost gate at `adjustActionsByGUITree`), `Config.java` (one new flag).
- **Behavior**: expected drop of the BACK+MENU share of executed steps (25.3% in cmpft2; target < 15%) with the freed budget flowing to widget-targeting actions; no change to model construction or naming.
- **Telemetry**: one new trace line (`BACK/MENU capped`), same shape as `MOP target capped`; `[APE-STEP]` and UICOV lines unchanged.
- **Tests**: pure static seams unit-tested like `SataAgentMopShortCircuitTest` (SataAgent is not instantiable on the JVM); menu-boost gate covered via the existing `StatefulAgent` boost-pass tests.
- **Archive ordering**: the cap requirement is written standalone against the main `action-selection` spec (no reference to the unarchived `INV-SEL-MOP-*` deltas); the `mop-guidance` delta modifies the open-menu boost requirement that already lives in the main spec (gh13 archived). No ordering constraint beyond the standard ones.
- **Risk**: over-capping could strand the agent on screens where BACK-via-short-circuit was doing useful escape work; mitigated by the uncapped navigation sites and `handleNullAction` fallback, and by the `<= 0` rollback knob.
- **Arm asymmetry**: the menu-boost gate lives inside `if (_mopData != null)` (`StatefulAgent.java:1402`), so cap-driven boost suppression fires only in the MOP arm; once an activity hits the cap the gh13 T1.2 OPTIONSMENU gateway (+250) is permanently suppressed for that activity, a MOP-arm-only effect. Mitigation: the device smoke checks per-arm cov_mop non-regression (task 5.3).
