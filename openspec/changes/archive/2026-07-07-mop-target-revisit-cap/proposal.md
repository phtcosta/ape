# Proposal: mop-target-revisit-cap

## Why

The cmpft validation run showed that the discriminative MOP short-circuits (change `mop-discriminative-boost`) concentrate the exploration budget on a handful of statically-flagged widgets without producing new violations. Trace evidence: dnsfilter spent 111–130 `decision_source=MOP` steps per rep (25–49% of the budget) hammering the same 2 widgets 30×+ each, held a 103–110-step consecutive streak inside `DNSServerConfigActivity`, inverted the app's activity dominance, and lost 6.6pp of method coverage (z=−8.5, 3/3 reps) — while its unique-violation set stayed identical to the no-MOP baseline. Across the 6 most MOP-active apps, the violation sets were identical to baseline in 6/6; across all 22 MOP-active apps, the mean coverage delta was +1.33pp vs +2.86pp for MOP-inactive apps.

The root mechanism: the short-circuits are bounded to *unvisited* actions (INV-SEL-MOP-01), but state refinement mints near-duplicate states (dnsfilter: 138 distinct states vs 82 in baseline) in which the same physical widget is "unvisited" again, so the deterministic pick re-fires indefinitely. A statically-reachable widget that has not produced a violation after a few interactions will not produce one on the 30th (verified: violations coupled to MOP picks fire within the first interactions or not at all), so unbounded re-picking is pure opportunity cost.

## What Changes

- A per-run revisit cap on the deterministic MOP short-circuits: each physical widget and action type (keyed by target XPath + action type + activity, independent of abstract state) is eligible for `selectUnvisitedMopTarget` / `pickBestMopTarget` at most `ape.mopTargetPickCap` times (default 3; <= 0 = unlimited). Once capped, the key falls out of the deterministic path; its `mopBoost` still participates in the priority roulette. This bounds the deterministic-override streaks; residual probabilistic concentration through the roulette remains possible (strictly less than today's unbounded deterministic re-picks).
- One log line on the deterministic pick that reaches the cap for a key: `[APE-RV] MOP target capped: activity=<a> widget=<xpath> picks=<n>` (once per key per run).
- New config flag `ape.mopTargetPickCap` (same family as the existing `mopWeight*` knobs; <= 0 = unlimited).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `action-selection`: the MOP short-circuit eligibility gains a cross-state revisit cap (new requirement; constrains INV-SEL-MOP-01/03 eligibility).

## Impact

- **Components**: `SataAgent` (short-circuit sites + pick counter), `Config` (one flag). `MopScorer`/boost computation unchanged.
- **Experiments**: only the `sata_mop` arm is affected (short-circuits exist only there); expected to reverse the dnsfilter-class regressions. The 25–49% budget-return figure is an upper-bound extrapolation from the dnsfilter traces, not a set-wide prediction across the 38 substrate-bearing apps.
- **Risk**: a cap of 3 may under-serve a widget whose violation genuinely needs many interactions — bounded by the fact that no such case was observed in 657 traces (violation-producing widgets fired within the first interactions), and the flag allows restoring unlimited behavior.
- **Archive ordering**: this change constrains the MOP short-circuit eligibility (INV-SEL-MOP-01/03) and the `selectUnvisitedMopTarget` / `pickBestMopTarget` deterministic paths, all defined only in the unarchived `mop-discriminative-boost` delta — the main `action-selection` spec has no MOP short-circuit content. This change MUST therefore be archived AFTER `mop-discriminative-boost`, so the requirements it references are present in the main spec at archive time.
