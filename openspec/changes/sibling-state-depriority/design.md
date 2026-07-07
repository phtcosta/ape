# Design — sibling-state-depriority

## Context

The load-bearing signal is refinement exhaustion. `NamingFactory` refinement (`resolveNonDeterminism`, `NamingFactory.java:131-170`) plus organic StateKey growth mint sibling states of one physical screen; each sibling's actions restart at `visitedCount == 0`, so the SATA least-visited/unvisited machinery re-exercises the same widgets once per sibling. `maxStatesPerActivity` (default 10, `Config.java:109`) only *rejects further refinement* once the count reaches it (`NamingFactory.java:276,1176`) — it neither evicts nor bounds organic states (`ActivityNode.addState`, unbounded HashSet). So beyond the cap the model can no longer merge or split away the fragmentation: additional siblings are pure organic waste the naming lattice cannot fix, which is exactly the condition this pass triggers on.

The `liveStates >= 10` figure (34% of cmpft2 activity rollups, `UICOV-ACT`, §8 of the verification report) is a correlated proxy, not the trigger metric. `liveStates` counts non-evicted `UICoverageTracker` fragments — an access-ordered LRU capped at `coverageMaxStates = 2000` (`UICoverageTracker.java:59-69`) — whereas this pass reads the Model-side population `Graph.getActivityNode(activity).getStates().size()`, which is uncapped but pruned on `Model.rebuild()` (`Graph.java:1244` `removeState`). Two different populations; at the threshold scale (10) neither cap bites, so the proxy tracks the trigger closely. The `hasActivityInteraction` gate additionally exempts legitimately-distinct multi-screen flows (e.g. wizards) whose siblings each touch new widgets, damping false positives.

Options considered at recon: (A) harden `maxStatesPerActivity` — does not bound organic growth, degrades abstraction fidelity globally; (B) more conservative refinement criteria — highest blast radius (CEGAR core), untestable off-device; (C) agent-side scoring containment — chosen: zero naming impact, composes with the coverage boost, host-testable.

## Architecture

One new pass in `StatefulAgent.adjustActionsByGUITree` (`StatefulAgent.java:1344+`), placed immediately after the coverage-boost pass (`:1474-1497`), consuming two existing signals: the activity's model state count and the activity-scoped interaction membership.

**Hard ordering requirement:** the sibling pass MUST run *after* the WTG pass (`StatefulAgent.java:1443-1467`). That pass is what populates `mopBoost`/`wtgBoost` on the actions; the sibling exemptions in INV-COV-11 read those fields to protect MOP/WTG/frontier-steered widgets. Inserting the sibling pass before the WTG pass would read `mopBoost == 0`/`wtgBoost == 0` on not-yet-boosted actions and silently penalize frontier/WTG widgets — the exact opposite of the intended exemption. Placement immediately after the coverage-boost pass (itself after the WTG pass) satisfies this.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.siblingStatePenalty` | flag `ape.siblingStatePenalty` (default 24; 0 = disabled) | properties | int |
| threshold | reuse of `Config.maxStatesPerActivity` (no new flag) | — | int |
| `Graph.getActivityNode(activity).getStates().size()` (existing, `Graph.java:205`, `ActivityNode.java:71`) | live sibling count | activity | int |
| `UICoverageTracker.hasActivityInteraction(activity, widgetId)` (existing, from `coverage-boost-activity-scope`) | redundancy test | activity, widgetId | boolean |
| sibling-depriority pass | subtract penalty from redundant actions, floor 1 | state actions | mutated priorities + trace line |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-COV-10 (penalty only beyond threshold) | state-count guard before the loop | threshold boundary test |
| INV-COV-11 (novel/MOP/WTG/target-less exemptions) | per-action exemption checks | exemption matrix test |
| INV-COV-12 (floor at 1; disabled at 0) | `Math.max(1, p - penalty)`; `penalty > 0` guard | floor + disabled tests |
| trace line | `Logger.iformat` once per pass when `n > 0` | log-emission test |

## Goals / Non-Goals

**Goals:** stop over-fragmented activities from draining budget into redundant re-interaction; keep novel widgets and MOP targets at full priority; zero change for unfragmented apps.

**Non-Goals:** bounding state creation (naming/CEGAR untouched); model eviction; changing `maxStatesPerActivity` semantics; per-sibling ranking (HashSet order is unstable — the pass keys on the *count*, not on a sibling's rank).

## Decisions

1. **Threshold = existing `Config.maxStatesPerActivity`.** Beyond it, refinement is already blocked, so additional siblings are pure fragmentation cost; reusing the knob avoids a second threshold whose relationship to the first would need explaining (P1, no gratuitous flags).
2. **Count-based trigger, not rank-based.** `ActivityNode.states` is an unordered HashSet; any "penalize the (K+1)-th sibling" rule would be nondeterministic across runs. Penalizing *redundant actions* in *any* state of an over-threshold activity is order-free and directly targets the waste (the redundancy test, not the state identity, carries the signal).
3. **Redundancy signal = `hasActivityInteraction`.** Cumulative, eviction-proof (INV-COV-09), exactly the sibling-collapse semantic the boost change introduced; a per-state signal (`getInteractionCount`) would re-arm on every mint — the bug this family of changes exists to fix.
4. **Exempt `mopBoost > 0` and `wtgBoost > 0`.** MOP steering has its own bounded pipeline (discriminative boost + revisit cap); silently subtracting the penalty from MOP targets would couple the two mechanisms and confound the fair-test arms. Both the pre-existing WTG-MOP boost AND the frontier boost (which `activity-frontier` routes through `wtgBoost`) are steering signals that must stay at full priority — the change's Purpose keeps MOP targets and steered widgets un-suppressed, so any action carrying `wtgBoost > 0` is exempt on the same grounds as `mopBoost > 0`.
5. **Exempt target-less actions (BACK/MENU).** They have no `widgetId` for the redundancy test and are governed by `back-menu-pick-cap`; penalizing them here would double-count.
6. **Fixed subtraction with floor 1, not multiplicative decay.** Matches the additive priority arithmetic of the surrounding passes (boosts add, penalty subtracts); floor 1 keeps the action roulette-visible (priority 0 would remove it from `countActionPriority` mass entirely — a silent exclusion, stronger than intended).

## API Design

### Pass pseudocode (inside `adjustActionsByGUITree`, after the coverage-boost pass)

```
if (Config.siblingStatePenalty > 0) {
    ActivityNode an = getGraph().getActivityNode(newState.getActivity());
    int siblings = an == null ? 0 : an.getStates().size();
    if (siblings > Config.maxStatesPerActivity) {
        for each action in newState.getActions():
            if !action.isValid() || !action.isResolvedAt(timestamp) -> skip  // matches coverage-boost pass
            if action.requireTarget() == false -> skip        // BACK/MENU etc.
            if action.getMopBoost() > 0 -> skip               // MOP steering exempt
            if action.getWtgBoost() > 0 -> skip               // WTG/frontier steering exempt
            widgetId = coverage widget id of action
            if !_coverageTracker.hasActivityInteraction(activity, widgetId) -> skip  // novel
            action.setPriority(Math.max(1, action.getPriority() - Config.siblingStatePenalty))
            n++
        if (n > 0) log "[APE-RV] Sibling depriority: state=%s#%s, penalized=%d/%d, siblings=%d"
    }
}
```

Preconditions: pass runs only when `newState != null` (same guard as the sibling passes). Postcondition: priorities of exempted actions unchanged; penalized priorities >= 1.

## Data Flow

`registerScreenElements`/`recordInteraction` populate `activityInteracted` (existing) → on each scoring pass, state count + membership decide the penalty → roulette mass shifts from redundant to novel/MOP actions in fragmented activities only.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `getActivityNode` returns null | activity not yet in graph | treat as 0 siblings — pass skipped | none needed |

## Risks / Trade-offs

- [Penalty slows revisits that would find new *transitions* (same widget, different outcome)] → floor 1 keeps them pickable; least-visited still prefers genuinely unvisited actions; rollback `ape.siblingStatePenalty=0`.
- [Threshold reuse couples two semantics (refinement cap / penalty trigger)] → intentional (Decision 1); if the validation run shows the coupling hurts, splitting the knob is a one-line follow-up.
- [Interaction with coverage boost on the same action] → orthogonal by construction: boost applies to novel widgets, penalty only to non-novel ones; an action can never receive both.
- [MOP-amplifying, not MOP-neutral] → exempting `mopBoost > 0`/`wtgBoost > 0` while penalizing their non-MOP neighbors *raises* MOP targets' relative roulette weight in fragmented activities. This is intentional (steering must not be suppressed), but it means the pass is not arm-neutral: when decomposing MOP-arm effects in the fair test, this amplification must be attributed to the sibling pass, not to the MOP scorer.
- [Disjointness proven only against the coverage boost] → the boost/penalty exclusivity above is established only for the coverage-boost pass; interaction with the form-completion pass (`StatefulAgent.java:1501-1521`) and the WTG passes on the *same* action is not analyzed. A redundant unfilled EditText can be penalized here and `W_FILL`-boosted at the insertion point `~:1505`. It stays net-attractive because `W_FILL = 150` (`FormCompletion.java:25`) is applied *after* this pass and vastly exceeds the penalty 24, so the −24 barely dents the form-completion pull. (The floor-of-1 is this pass's own clamp against driving a priority negative — it is not what protects the EditText; the ordering + magnitude of `W_FILL` is.)

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit | threshold boundary (siblings == cap → no penalty; cap+1 → penalty), exemption matrix (novel / mopBoost / target-less), floor at 1, disabled at 0, log emission | StatefulAgent scoring-pass test pattern (existing boost-pass tests) + `UICoverageTracker` fixtures |
| Device smoke | in a heavy-refinement app (e.g. diaguard), `Sibling depriority` lines present and redundant-pick share drops vs cmpft2; simple apps emit no lines | future validation run |

## Open Questions

- Default penalty 24 is an educated first setting (visited CLICK 32 → 8); the validation run calibrates it.
