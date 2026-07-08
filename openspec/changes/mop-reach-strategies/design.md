# Design — mop-reach-strategies

## Context

This change is line 2 of the separation-architecture design (`docs/20260708_arquitetura_separacao_aperv.md` §6). It implements the reach levers from the investigation memo §2 (A′, B, E-mín) plus the F′ seams and the G-2 hygiene fix. The science: MOP fires on screen arrival / component wake-up (§1.2), so **reaching** unvisited MOP screens dominates **granularity** (what to boost inside a screen). Every lever here is consumer-side and small; none changes a scorer's arithmetic except by widening the substrate it already reads.

Building blocks already in place:
- `MopData` parses `components.activities[].reachesTarget` into `ComponentInfo` but no scoring path consumes it (verified, memo §2 A′).
- `activityHasMop(activity)` / `mopActivities` — populated today only from flagged widgets (`MopData.java:385-389`).
- WTG view `getWtgTransitions(activity)` keyed by base activity, each `WtgTransition.targetActivity` a base activity (wtg-navigation INV-WTG-04/05).
- `Graph.getActivityNode(name) == null` — the live "unvisited activity" test that `activity-frontier`'s generic frontier boost uses.
- `activity-frontier`'s `selectTriggerCandidate(activities, visited, main, rrIndex)` — the pure candidate seam for the stagnation launcher.
- The `ScoringPass` interface + `ScoringPipeline.fromConfig(Config)` assembly point introduced by the sibling `rv-scoring-pipeline`.

## Decisions

### D1 — A′ is a UNION of sources, not a replacement

When `mopActivitySourceComponents=true`, `mopActivities` becomes the union of (a) the existing widget-derived activities and (b) every `components.activities[].className` whose JSON `reachesTarget` is true. Union, never replacement: an app that already flags an activity through a widget listener keeps that association even if the component-level data disagrees. Default false reproduces today's widget-only source byte-for-byte. This keeps A′ a pure extension of a predicate's extent — the lowest-risk possible change — and lets the two experiment arms differ only in this flag.

**Vocabulary boundary (gh13 D7):** the read is of the JSON `reachesTarget` field of `components.activities[]` (`Target` vocabulary lives on the JSON side); the write is to `mopActivities` (`MOP` vocabulary, Java side). The `*Target` identifier appears only at the JSON read, matching the `MopData` javadoc boundary.

**Saturation risk is a consumer concern, not a source concern.** In the 70 saturated apps (>80% of screens MOP-bearing) A′ makes the predicate near-constant; that is handled by the experiment's pre-registered slice analysis (memo §3), not by narrowing the source.

### D2 — B is a ScoringPass, additive to and disjoint-in-intent from the generic frontier

`MopFrontierPass` (new, in `com.android.commands.monkey.ape.agent.scoring`) fires the three-condition boost:

```
shortId == WtgTransition.widgetName
  AND activityHasMop(WtgTransition.targetActivity)
  AND Graph.getActivityNode(WtgTransition.targetActivity) == null
```

None of the existing scorers combines these three: `MopScorer.scoreWtg` boosts MOP targets but ignores visitation; `activity-frontier`'s `FrontierPass` boosts **any** unvisited target and ignores MOP. B is the intersection — "an unvisited screen that is known to reach MOP" — the best possible frontier, and the 52.5%-of-missed-screens lever.

- **Additive, not substitutive.** When the same transition target is both MOP-bearing and unvisited, the action accumulates `mopWeightWtg` (from the WTG-MOP pass) + `frontierBoostWeight` (generic frontier, if enabled) + `mopFrontierWeight` (this pass). All three are recorded into the action's `wtgBoost` telemetry field by read-modify-write, and each is a real `setPriority` increment (the field is telemetry-only and never enters `getPriority()`, exactly as `activity-frontier` established). Default `mopFrontierWeight=0` makes the pass byte-identical to absent.
- **Own transition lookup.** Like `activity-frontier`, the pass does its own `MopData.getWtgTransitions(activity)` walk with resource-id matching; it cannot ride `scoreWtg`'s `int` return (which hides the target activity and only fires when MOP-reachable, so it can neither identify the frontier target nor separate visited from unvisited).
- **Why a pass and not an inline edit.** The current touchpoint is the WTG loop (`StatefulAgent.java:1539-1564`); once `rv-scoring-pipeline` extracts the passes, B lives as its own pass so the assembly line and per-pass tests attribute its effect cleanly. This is the whole reason B depends on the sibling.
- **Calibration deferred.** `mopFrontierWeight` vs `frontierBoostWeight` interaction (both are frontier passes) is a smoke-test calibration before the large run (memo §4.3); the default 0 ships the mechanism dormant.

### D3 — E-mín reorders the launcher's candidate selection, deterministically

`activity-frontier`'s `selectTriggerCandidate` walks the manifest activity list from a persisted round-robin index and returns the first eligible candidate (exported ∧ permission==null ∧ !isMain ∧ unvisited). E-mín adds, gated by `triggerMopFirst`:

- When true, the walk considers eligible candidates with `reachesTarget=true` **before** eligible candidates with `reachesTarget=false`. Implemented as a stable two-pass over the eligible set (MOP-reachable group first, each group in round-robin order), so the selection stays **deterministic and reproducible** under a fixed seed — no randomness, no set-iteration-order dependence.
- When false (default), the walk is exactly `activity-frontier`'s single-pass round-robin — behaviour unchanged.

E-mín only reorders **which eligible candidate is picked first**; it never widens eligibility (still exported/permission/main/visited filtered) and never launches receivers/services (E-ext non-goal). It is additive to `activity-frontier`'s "Stagnation-Triggered Activity Launch" requirement, which it depends on but does not restate.

### D4 — F′ seams expose classifiers, consume nothing

- `Config.llmPercentageNoSubstrate` (double, default −1). The −1 is a sentinel meaning "inherit `llmPercentage`". It is loaded and exposed; **no code reads it** in this change. Critically it is exempt from the `[0,1]` clamp that `llmPercentage` gets (INV-RTR-08): clamping −1 would collapse the sentinel to 0. When configured ≥0 it is clamped to `[0,1]` like `llmPercentage`; the −1 sentinel passes through unclamped.
- `MopData.isWidgetlessSubstrate()` returns true when the sum of `windows[i].widgets.size()` over all windows is 0 — the 65 widgetless apps (Compose-pure/GL/games) that will never get widget/WTG/frontier steering. Pure predicate over already-parsed data; no consumer.

Both are the smallest honest footprint for round-2 adaptive LLM routing (F′), which will read the bit at load and raise `llmPercentage` only for widgetless apps. Shipping the seams now keeps that round consumer-only.

### D5 — G-2 makes the too-large check unit-consistent

`MopData.load`'s pre-read reject compares file size against a heap-derived budget. redreader (true size 48.3 MiB, reported as "50.6 MB" decimal) is falsely rejected because the size operand and the budget operand are expressed in mismatched units (decimal 10^6 vs binary 2^20). The fix computes both operands in bytes (binary) so a file whose true byte size is below `maxMemory()/factor` is not rejected, and the `size=`/`budget=` fields on the `[APE-MOP-DATA] status=rejected reason=too-large` line report consistent units. This is a MODIFY of the existing `Load memory safety` requirement (the bug lives in its comparison clause); INV-MOP-26 and the OOM-containment behaviour are preserved verbatim. Effect: redreader parses; 3/657 previously-aborted runs recover.

## Interaction map (this change × the frontier passes)

| Action situation | mopWeightWtg | frontierBoostWeight (activity-frontier) | mopFrontierWeight (B) |
|---|---|---|---|
| WTG target MOP-bearing, visited | applies | — | — |
| WTG target non-MOP, unvisited | — | applies | — |
| WTG target MOP-bearing, unvisited | applies | applies | **applies** |
| WTG target non-MOP, visited | — | — | — |

All applicable terms accumulate into `wtgBoost` and into `priority`. B is precisely the third row — the intersection that neither existing pass rewards on its own.

## Testing Strategy

| Layer | What | How |
|---|---|---|
| Unit (A′) | union population: component `reachesTarget=true` adds the activity; widget-derived entries preserved; flag off = widget-only; `activityHasMop` reflects the union | `MopData.load` on a fixture with a `reachesTarget=true` activity carrying no flagged widget |
| Unit (A′ boundary) | `Target` read only at JSON parse; `mopActivities` is the Java-side set | assertion on the populated set |
| Unit (isWidgetlessSubstrate) | 0-widget fixture → true; ≥1-widget fixture → false | `MopData` predicate |
| Unit (B) | three-condition boost: MOP+unvisited boosted; MOP+visited not; non-MOP+unvisited not; weight 0 = byte-identical; accumulation into `wtgBoost` with mopWeightWtg/frontierBoostWeight | scoring-pass test with stub graph + MopData |
| Unit (E-mín) | `triggerMopFirst=true` picks the reachesTarget=true eligible candidate first; determinism under fixed order; flag off = round-robin unchanged | `selectTriggerCandidate` seam test |
| Unit (F′ flag) | default −1 not clamped; ≥0 clamped to [0,1]; no routing behaviour change | Config load + LlmRouter unchanged assertion |
| Unit (G-2) | redreader-sized file below budget parses (not rejected); genuinely oversized still rejected; status line units consistent | size-check seam / small + oversized fixtures |
| Device smoke | `sata_mop_activity` and `sata_mop_act_frontier` on a deep app: `activityHasMop` count rises with A′; MopFrontier boost lines appear; MOP-first launch ordering observable; calibrate `mopFrontierWeight` | future validation run |

## Open Questions

- `mopFrontierWeight` default magnitude relative to `frontierBoostWeight` — first setting, calibrated in smoke (memo §4.3).
- Whether B needs to be isolated from E-mín in a separate arm — decided post-smoke if `sata_mop_act_frontier` needs decomposition (design doc §7).
