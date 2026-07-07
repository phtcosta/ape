# Design — back-menu-pick-cap

## Context

cmpft2 measured 25.3% of all executed steps as MODEL_BACK (15.4%) + MODEL_MENU (9.9%), with MODEL_MENU holding the worst interacted/discovered ratio (0.586) of any action type (`docs/20260707_verificacao_mecanismos_cmpft2.md` §8, rv-android repo). BACK/MENU are per-state target-less `ModelAction`s (`State.java:63-66`); NamingFactory refinement mints sibling states whose fresh BACK/MENU actions are unvisited again, re-arming the epsilon-greedy short-circuits (`SataAgent.java:467-485`) without bound — the same pathology `mop-target-revisit-cap` fixed for MOP targets, which cannot cover BACK/MENU because `mopPickKey` returns null for target-less actions (`SataAgent.java:585-598`). Independently, the gh13 open-menu gateway boost (`StatefulAgent.java:1429-1440`, `ape.mopWeightOpenMenu` default 250) lifts visited MENU actions from priority 8 to 258, dominating the priority roulette.

Constraint: BACK is also the agent's navigation/escape primitive. The uncapped sites (`selectNewActionBackToActivity` `SataAgent.java:866-908`, `backToTrivialActivity` `:1103-1125`, `checkBackTrack` `:299-342`, `handleNullAction` `StatefulAgent.java:1612-1621`) must keep emitting BACK freely.

## Architecture

The discretionary BACK/MENU picks live in two phases of `selectNewActionNonnull`, both reached before `handleNullAction`: the EARLY_STAGE phases (`findGreedyActionForward` at phase `:431` and `findGreedyActionBackward` at phase `:441`) and the epsilon-greedy phase (`selectNewActionEpsilonGreedyRandomly` at phase `:446`, `SataAgent.java:467-524`). Under the default `useActionDiffer=true` the EARLY_STAGE backward harvester (`findGreedyActionBackward:1278-1283`) picks unvisited BACK first, shadowing the epsilon BACK short-circuit; the EARLY_STAGE forward roulette (`findGreedyActionForward:1218-1236`) admits BACK/MENU into its `randomPickWithPriority` candidates on first-step/activity-change. The cap therefore wires into both EARLY_STAGE sites as well as the epsilon-greedy path, all sharing one `backMenuPicks` counter, plus one gate in the menu-boost pass (`StatefulAgent.adjustActionsByGUITree`).

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.backMenuPickCap` | flag `ape.backMenuPickCap` (default 3, <=0 unlimited) | properties | int |
| `SataAgent.backMenuPickKey(type, activity)` (static, pure) | cap key for the two target-less types | ActionType, activity | `activity + "\|" + type.name()`, null for other types |
| `SataAgent.backMenuPicks` (instance field) | per-run pick counts | — | `Map<String,Integer>` |
| `eligibleForMopPick` / `recordMopPick` (existing, `SataAgent.java:605-626`) | reused verbatim — generic over (map, key, cap) | map, key, cap | boolean |
| capped-`ActionFilter` wrapper | excludes MODEL_BACK/MODEL_MENU whose key is capped from least-visited and roulette | base filter + eligibility | `ActionFilter` |
| `StatefulAgent.menuPickEligible(activity)` (protected hook) | gates the gh13 menu boost by cap state | activity | boolean (base: true; `SataAgent` override: cap check) |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-SEL-NAV-01 (cap bounds discretionary picks) | eligibility check in the two epsilon short-circuits + wrapped filter at `greedyPickLeastVisited`/`randomlyPickAction` call sites + EARLY_STAGE guards (`findGreedyActionBackward:1278-1283` skip, `findGreedyActionForward:1218-1236` candidate filter) | `SataAgentBackMenuCapTest` (static seams) |
| INV-SEL-NAV-02 (cap <= 0 = identical behavior, no side effects) | `eligibleForMopPick`/`recordMopPick` existing guards | reuse of `SataAgentMopShortCircuitTest` pattern |
| INV-SEL-NAV-03 (navigation sites uncapped) | those sites not modified (no call into the cap) | code inspection + scenario test on seams |
| INV-SEL-NAV-04 (cap log once per key) | `recordMopPick` returns true exactly once | `capLogEmittedOnce` |
| menu-boost gate (mop-guidance delta) | `menuPickEligible` hook consulted before `scoreOpenMenu` boost | `StatefulAgent` boost-pass test |

## Goals / Non-Goals

**Goals:** bound discretionary BACK/MENU re-picks per (activity, type) across sibling states; stop the +250 menu boost from re-dominating the roulette after the cap; free budget for widget-targeting actions.

**Non-Goals:** capping navigation-essential BACK; changing model construction, naming, or the SATA phase order; any new scoring signal.

## Decisions

1. **Key = `activity|type`, not `stateKey|type`.** The whole point is absorbing refinement re-arm; a stateKey-scoped count would reset per sibling exactly like `visitedCount` does. Activity scope mirrors the reasoning validated for `mopTargetPickCap` (xpath is unavailable — no target).
2. **Reuse `eligibleForMopPick`/`recordMopPick` unchanged.** They are already generic over (map, key, cap) and unit-tested; only the key derivation and the map are new. Alternative (dedicated near-identical statics) rejected — duplication with no behavioral difference.
3. **The filter must also cover `greedyPickLeastVisited` (:511), not just the short-circuits and roulette.** A capped BACK/MENU falling out of the short-circuit would immediately be re-elected by least-visited: refinement-minted siblings carry `visitedCount == 0`, which wins the least-visited scan outright. Capping only the short-circuit would merely relabel the re-pick channel. All three epsilon-greedy consumers therefore share one wrapped `ActionFilter` (stable across `countActionPriority`/`pickAction` — required by the roulette's two-pass pick).
4. **Menu-boost gate as a protected hook (`menuPickEligible`) on `StatefulAgent`, overridden in `SataAgent`.** The boost pass lives in `StatefulAgent`, the pick map in `SataAgent`. A hook keeps the map instance-owned (like `mopTargetPicks`) and the base class agnostic; `RandomAgent`/`ReplayAgent` inherit the default `true` (no behavior change — they never populate the map). Alternative (move the map to `StatefulAgent`) rejected: the cap is SATA policy, not shared agent state.
5. **Roulette/least-visited picks of BACK/MENU also count toward the cap.** The cap bounds discretionary selection wherever it happens; counting only short-circuit picks would let the roulette re-pick a boosted MENU indefinitely below the radar.
6. **BACK-only states are safe.** When the wrapped filter leaves no candidate, `selectNewActionEpsilonGreedyRandomly` returns null and `handleNullAction` (uncapped, `includeBack=true`) picks BACK as last resort — the stuck-loop guard (gh9) is preserved.

7. **The cap must also cover the two EARLY_STAGE sites, not only the epsilon-greedy path.** Under the default `useActionDiffer=true` (`Config.java:87`), `findGreedyActionBackward` picks the unvisited BACK directly and unconditionally at `SataAgent.java:1278-1283` (via `ENABLED_VALID_UNVISITED.include(back)`) at phase `:441` — before `selectNewActionEpsilonGreedyRandomly` (`:446`) runs — so the epsilon BACK short-circuit is dead code whenever BACK is unvisited, and a cap on the epsilon path alone would leave the dominant BACK channel unbounded. Symmetrically, `findGreedyActionForward` feeds BACK/MENU into `RandomHelper.randomPickWithPriority` at `:1227` on first-step/activity-change (target-less actions enter the candidate list via `StateActionDiffer.getUnsaturated`'s fallback branches — `ENABLED_VALID_UNSATURATED` does not require a target, and for target-less actions `isSaturated()==isVisited()`). Both EARLY_STAGE sites therefore reuse the same `backMenuPicks` counter and `backMenuPickKey`/eligibility check as the epsilon path: (a) in `findGreedyActionBackward:1278-1283`, skip the direct unvisited-BACK pick when the BACK key is capped (fall through to backtrack); (b) in `findGreedyActionForward:1218-1236`, drop capped target-less BACK/MENU from `candidates` before `randomPickWithPriority`, and `recordMopPick` the key when a BACK/MENU wins the roulette. No new mechanism, map, or flag — the same seams from Decisions 2–3.

8. **Arm asymmetry from the menu-boost gate.** The `menuPickEligible` gate lives inside `if (_mopData != null)` (`StatefulAgent.java:1402`; boost at `:1429-1440`), so cap-driven boost suppression fires only in the MOP arm. Once an activity's MENU key hits the cap, the gh13 T1.2 OPTIONSMENU gateway (+250) is permanently suppressed for that activity — a MOP-arm-only effect that could depress cov_mop where the OPTIONSMENU is a MOP gateway. This is accepted as intended (the cap's purpose is to stop the +250 from re-dominating the roulette after the budget is spent), but the risk is real; mitigation is a per-arm cov_mop non-regression check in the device smoke (tasks.md 5.3).

## API Design

### `static String backMenuPickKey(ActionType type, String activity)`
- Returns `activity + "|" + type.name()` iff `type == MODEL_BACK || type == MODEL_MENU`; null otherwise (uncountable → always eligible). Pure, null-tolerant on activity (null activity → null key).

### `protected boolean menuPickEligible(String activity)` (StatefulAgent)
- Base implementation returns true. `SataAgent` override: `eligibleForMopPick(backMenuPicks, backMenuPickKey(MODEL_MENU, activity), Config.backMenuPickCap)`.
- Consulted in the menu-boost pass: `int menuBoost = menuPickEligible(activity) ? MopScorer.scoreOpenMenu(activity, _mopData) : 0;`.

### Cap flow inside `selectNewActionEpsilonGreedyRandomly`
1. Compute `backKey`/`menuKey` once per invocation; eligibility via `eligibleForMopPick`.
2. Short-circuits: only return the unvisited BACK/MENU when its key is eligible; on return, `recordMopPick` + cap log when it reports the cap was reached.
3. Build `cappedFilter` wrapping `ActionFilter.ENABLED_VALID`: excludes MODEL_BACK when `backKey` capped, MODEL_MENU when `menuKey` capped. Pass it to both `greedyPickLeastVisited` (:511) and `randomlyPickAction` (:519).
4. When the least-visited/roulette result is a BACK/MENU action, `recordMopPick` its key (+ cap log on reach).

Log line: `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` (mirrors `SataAgent.java:651`).

### Cap flow at the two EARLY_STAGE sites
1. `findGreedyActionBackward` (`:1278-1283`): before returning the unvisited BACK, check `eligibleForMopPick(backMenuPicks, backMenuPickKey(MODEL_BACK, next.getActivity()), Config.backMenuPickCap)`. When ineligible, skip the direct pick and fall through to the backtrack path (`:1285+`); when eligible and returned, `recordMopPick` + cap log on reach.
2. `findGreedyActionForward` (`:1218-1236`): after the existing submit exclusion narrows `candidates` (INV-FORM-06 — the submit candidate is dropped while the form has unfilled EditTexts; BACK/MENU are never that candidate, so the two exclusions compose), additionally drop any capped target-less BACK/MENU from `candidates` before `RandomHelper.randomPickWithPriority`. When the roulette winner is a BACK/MENU action, `recordMopPick` its key (+ cap log on reach). The MOP-target probe (`pickCappedMopTarget`, `:1218`) is untouched.

## Data Flow

`Config.backMenuPickCap` (startup) → `selectNewActionEpsilonGreedyRandomly` consults/updates `backMenuPicks` → wrapped filter shapes least-visited/roulette candidate sets → `menuPickEligible` gates the +250 boost during the next `adjustActionsByGUITree` pass → capped keys emit one trace line each.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| No candidate after filtering | all actions capped/invalid | return null (existing contract) | `handleNullAction` picks BACK/random uncapped |
| null activity | rare pre-model states | `backMenuPickKey` returns null → always eligible, never counted | none needed |

## Risks / Trade-offs

- [Cap strands exploration on a screen needing repeated BACK] → navigation sites (`backToActivity`, `backtrack`, `handleNullAction`) are untouched; only discretionary epsilon-greedy picks are bounded; rollback knob `<= 0`.
- [Menu with genuinely deep submenus needs > 3 opens] → the cap counts per activity, not per submenu level; MENU picked by the navigation-free paths is unaffected, and `ape.backMenuPickCap` is tunable per experiment.
- [Interaction with `mopWeightOpenMenu` experiments] → the gate only ever suppresses the boost after the cap is reached; below the cap gh13 semantics are identical.
- [Arm asymmetry: cap + menu-boost gate bite harder in the MOP arm] → the gate lives inside `if (_mopData != null)` (`StatefulAgent.java:1402`), so once an activity is capped the gh13 T1.2 OPTIONSMENU gateway is permanently disabled for it — MOP-arm-only, a potential cov_mop drag (Decision 8). Mitigation: per-arm cov_mop non-regression in the device smoke (tasks 5.3); rollback knob `<= 0`.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit (pure seams) | `backMenuPickKey` type/null contract; eligibility/record round-trip at cap boundary; once-only cap log signal | new `SataAgentBackMenuCapTest`, same pattern as `SataAgentMopShortCircuitTest` (SataAgent not instantiable on JVM) |
| Unit (filter) | wrapped filter excludes exactly the capped types and is stable across two passes | direct `ActionFilter` test with stub actions |
| Unit (EARLY_STAGE seams) | backward: capped BACK-key → direct pick skipped; forward: capped BACK/MENU dropped from candidates, INV-FORM-06 submit exclusion still composes | `SataAgentBackMenuCapTest` seam cases |
| Unit (boost gate) | `menuPickEligible` false → no `setPriority`/`setMenuBoost` call | extend existing StatefulAgent boost-pass test |
| Device smoke | BACK+MENU share of `[APE-STEP]` drops (25.3% → target < 15%) on a trace subset; `BACK/MENU capped` lines present; cov_act not reduced; per-arm cov_mop non-regression (arm-asymmetry mitigation) | future validation run |

## Open Questions

- Default cap value 3 mirrors `mopTargetPickCap`; whether BACK deserves a higher cap than MENU (navigation pressure) is left to the validation run — single knob until evidence says otherwise (P1).
