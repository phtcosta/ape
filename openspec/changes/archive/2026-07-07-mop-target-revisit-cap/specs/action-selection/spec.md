## Purpose

Bound the deterministic MOP short-circuits by physical widget, across abstract states. The `mop-discriminative-boost` change gave `mopBoost > 0` actions a deterministic path to selection in the two places that consume unvisited candidates (`selectUnvisitedMopTarget` in epsilon-greedy, `pickBestMopTarget` in the EARLY_STAGE forward roulette), bounded to unvisited actions (INV-SEL-MOP-01). That bound is per abstract state: when NamingFactory refinement mints near-duplicate states of the same screen, the same physical widget becomes "unvisited" again in each one, and the short-circuit re-fires without limit. The cmpft run measured the consequence on dnsfilter: 111–130 MOP-attributed picks per rep concentrated on ~2 widgets, a 103–110-step streak inside one activity, −6.6pp method coverage (3/3 reps), and zero new violations relative to the no-MOP baseline.

This delta adds a per-run revisit cap keyed by physical widget identity and action type — the action target's XPath, the action type, and the activity — so the deterministic preference fires a bounded number of times per (widget, action type) regardless of how many abstract states the screen splits into. A capped key keeps its `mopBoost` in the priority roulette; only the deterministic override is exhausted.

## Data Contracts

### Input
- `ape.mopTargetPickCap: int` — maximum deterministic MOP picks per (widget, action type, activity) key per run (default 3; <= 0 = unlimited). Read once at startup like the `mopWeight*` flags.

### Side-Effects
- **[Trace]**: `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — logged once per key, on the deterministic pick that reaches the cap (`picks == cap` after increment).

## ADDED Requirements

### Requirement: MOP target revisit cap

The agent SHALL count deterministic MOP picks per physical widget and action type across the whole run, keyed by `target.toXPath() + "|" + actionType + "|" + activity` — independent of the abstract state the action belongs to. MOP boosts are event-type-specific (a widget's click and long-click carry independent boosts — `MopScorer.eventTypeOf`), so the cap is per (widget, action type, activity); the key matches the existing `UICoverageTracker.widgetId` convention (xpath|type) scoped by activity. Both deterministic MOP selection paths (the epsilon-greedy path through `selectUnvisitedMopTarget` and the EARLY_STAGE path around `pickBestMopTarget`) SHALL exclude a key whose count has reached `ape.mopTargetPickCap` from the deterministic override, exactly as if its `mopBoost` were 0 for short-circuit purposes — the candidate set is filtered at the two instance call sites; the static picker itself remains pure. The count is incremented on every action the deterministic paths select. Ineligibility SHALL NOT alter the action's `mopBoost` value itself: the boost continues to participate in priority-weighted roulette selection.

With `ape.mopTargetPickCap` <= 0 (zero or negative) the cap SHALL be disabled and both sites SHALL behave exactly as specified by `mop-discriminative-boost`.

When a deterministic pick increments a key's count to exactly `ape.mopTargetPickCap` (the pick that reaches the cap), the agent SHALL log `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — once per key per run. Because capped keys are filtered before the next pick, this reach-the-cap event is the only occasion to log.

- **INV-SEL-MOP-04**: No (widget XPath + action type + activity) key SHALL be selected by the deterministic MOP sites more than `ape.mopTargetPickCap` times per run when the cap is positive.
- **INV-SEL-MOP-05**: With `ape.mopTargetPickCap` <= 0, action selection SHALL be identical to the uncapped specification with no cap-related side effects (no counter updates, no cap log lines).

#### Scenario: fourth pick of the same widget falls through to the roulette
- **WHEN** `ape.mopTargetPickCap=3` and the deterministic MOP sites have already selected actions of the same type targeting widget X (same XPath, same action type, same activity, across three different abstract states) — the third pick having logged `[APE-RV] MOP target capped` once as it reached the cap
- **THEN** on the next state where such an action targeting X is the only `mopBoost>0` candidate, `pickBestMopTarget` SHALL return null
- **AND** selection SHALL proceed to the roulette with X's `mopBoost` still in its priority

#### Scenario: distinct widgets are counted independently
- **WHEN** widget X has reached the cap and widget Y (different XPath) carries `mopBoost=300` unvisited
- **THEN** the deterministic sites SHALL still select Y

#### Scenario: cap disabled
- **WHEN** `ape.mopTargetPickCap=0`
- **THEN** the deterministic sites SHALL ignore pick counts entirely
