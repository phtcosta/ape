## Context

`MopScorer.score(activity, shortId, data, eventType)` (`MopScorer.java:35-55`) returns `+500` for a direct-MOP widget, `+300` for a transitive-MOP widget, and falls back to `+100` (`Config.mopWeightActivity`) for any other widget when `data.activityHasMop(activity)` is true. The fallback fires for every target widget on a MOP-bearing activity, so it adds a constant to all candidates and cannot re-rank them (`docs/20260622_investigacao_mop.md` §1 Camada 2). The discriminative `+500`/`+300` values are added to `ModelAction.priority` and consumed in selection only via priority-weighted roulette (`EARLY_STAGE`) or as a least-visited tiebreaker (`EPSILON_GREEDY`), so even a discriminative boost only nudges probability.

This change removes the uniform fallback and adds a deterministic selection path for the discriminative case. It depends on `mop-parser-fidelity` (#0), which restores the flagged widgets that make a `+500`/`+300` available to discriminate.

## Architecture

```
MopData.getWidget → MopScorer.score → +500 / +300 / 0   (no more +100)        ← part A
                          │ writes ModelAction.mopBoost / priority
                          ▼
StatefulAgent.adjustActionsByGUITree (MOP pass, unchanged structure)
                          ▼
SataAgent.selectNewActionEpsilonGreedyRandomly:
   if a valid UNVISITED action has mopBoost>0 → select it (greedy short-circuit) ← part B
   else existing Back/Menu short-circuit → egreedy / roulette
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `MopScorer.score` | Discriminative-only boost (+500/+300/0) | activity, shortId, MopData, eventType | int boost |
| `SataAgent.selectNewActionEpsilonGreedyRandomly` | Greedy short-circuit to unvisited MOP target | current state actions | selected action |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| `mop-guidance` MODIFIED "MopScorer — Priority Boost" (no +100) | delete the `activityHasMop → mopWeightActivity` branch in `MopScorer.score` | `MopScorerTest`: unflagged/null → 0; direct/transitive unchanged |
| `mop-guidance` MODIFIED "Config.mopDataPath Flag" (no mopWeightActivity) | delete `Config.mopWeightActivity` field + Javadoc | `ConfigTest`/compile: field absent |
| `action-selection` ADDED "MOP-target greedy short-circuit" | new branch in `selectNewActionEpsilonGreedyRandomly` | `SataAgent` selection test: unvisited mopBoost>0 chosen |
| INV-SEL-MOP-01 (short-circuit only for unvisited mopBoost>0) | guard on `isUnvisited() && getMopBoost()>0` | selection test: visited MOP target not force-picked |
| `action-selection` ADDED "MOP preference in the EARLY_STAGE unvisited roulette" / INV-SEL-MOP-03 (group 4) | `findGreedyActionForward` probes `pickBestMopTarget` before `randomPickWithPriority` (`SataAgent.java:1072`), honoring the form submit exclusion; SATA chain order unchanged | `findGreedyActionForward` test: unvisited mopBoost>0 candidate preferred over roulette |
| `mop-guidance` ADDED "MopScorer — MOP-Flagged State Density" / INV-MOP-24 (group 4) | `stateMopDensity` becomes 3-arg and counts only MOP-flagged widgets (not every targeted action) | `MopScorerTest`: density counts flagged widgets only |

## Goals / Non-Goals

**Goals:**
- The MOP boost re-ranks candidates only when it carries discriminative information (`+500`/`+300`); a MOP-bearing activity with no flagged widget gets no MOP boost.
- A discriminative, unvisited MOP target is reached deterministically rather than via roulette probability.
- Remove the dead `mopWeightActivity` weight and the obsolete `INV-MOP-07`.

**Non-Goals:**
- Recalibrating `+500`/`+300` magnitudes (they already dominate base priority; not the problem).
- Changing the WTG/menu/coverage passes (separate concerns).
- Re-introducing any activity-level signal as a per-widget constant (that is the defect being removed; navigation-level MOP preference is the job of WTG/menu).

## Decisions

**D1 — Remove the `+100` fallback outright (vs. relative/mean-subtracted boost).** Subtracting the screen mean to neutralise a uniform boost is more machinery for the same effect; deleting the branch is simpler (P1) and also resolves the equal-magnitude collision with the coverage boost. A resolved-but-unflagged widget scoring `0` is correct — it carries no MOP information.

**D2 — Greedy short-circuit for the unvisited discriminative target (vs. roulette-only, vs. raising weights).** Raising `+500` further does not defeat roulette dilution; a deterministic short-circuit does, and it reuses the exact pattern already in `selectNewActionEpsilonGreedyRandomly` for unvisited Back/Menu (`:456-488`) — minimal and in-character. Bounded to `isUnvisited() && mopBoost>0` so it fires once per MOP target and does not override the least-visited strategy for visited actions. The conservative alternative (remove `+100` only, keep roulette) is recorded in Open Questions as the fallback if the short-circuit over-exploits.

**D3 — `activityHasMop` predicate stays; only its `+100` scoring use is deleted.** It still serves WTG target tests and `stateMopDensity`. Deleting the predicate would break those.

**D4 — `INV-MOP-07` removal.** This change removes the invariant that mandates the activity-level fallback. It lives in the base `mop-guidance` top-level Invariants section (not inside a requirement), so the delta records its removal in the MODIFIED "MopScorer — Priority Boost" narrative; the Invariants section is reconciled at sync/archive.

**Caveat — `INV-MOP-07` is double-booked with active gh13.** In the *base* spec `INV-MOP-07` is the activity fallback (the one removed here). The still-active `gh13-mopdata-schema-v2` change re-uses the same number `INV-MOP-07` for a *different* meaning (read the gh60 `directlyReachesTarget`/`reachesTarget` keys). So the removal here targets the activity-fallback invariant **by semantics, not by number**: if gh13 archives first, the activity-fallback `INV-MOP-07` no longer exists under that number and this change's removal becomes a no-op on an already-renumbered invariant — verify at archive time that the activity-fallback guarantee is gone and that gh13's reachability `INV-MOP-07` is preserved (renumber one of them if both must coexist). The `grep "INV-MOP-07"` gate in tasks 3.2 runs over `src/` (implementation references), which is unaffected by the spec-side numbering clash.

## API Design

### `MopScorer.score(String activity, String shortId, MopData data, String eventType) -> int`
- **Postcondition:** returns `Config.mopWeightDirect` if the resolved widget `isDirectMop(eventType)`, else `Config.mopWeightTransitive` if `isTransitiveMop(eventType)`, else `0`. The `data == null` guard and event-type fallback are unchanged. No activity-level branch.

### `SataAgent.selectNewActionEpsilonGreedyRandomly() -> ModelAction` (modified)
- **Added branch (after the Back/Menu-unvisited checks, before `egreedy()`):** if any `ENABLED_VALID` action is unvisited and has `getMopBoost() > 0`, return it (on ties, the highest `mopBoost`, then highest priority). Logged via `logActionSelected(action, EPSILON_GREEDY)` so attribution/telemetry are consistent (composes with change #3).

## Data Flow

`getWidget → score` now yields `{+500, +300, 0}`; the MOP pass writes `mopBoost` only for discriminative widgets. Selection consults `mopBoost` in the new short-circuit and `priority` in the existing roulette/tiebreaker. No persisted state changes.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `data == null` | `MopScorer.score` | existing guard returns 0 | unchanged |
| No flagged widget on a MOP activity | `score` | returns 0 (no fallback) | SATA/coverage drive selection |
| No unvisited MOP target in state | short-circuit | branch is a no-op | falls through to existing selection |

## Risks / Trade-offs

- **[Short-circuit could over-exploit MOP widgets, starving breadth]** → bounded to unvisited targets (fires once each); if measured to harm coverage, fall back to D2's conservative alternative (remove `+100` only). Recorded in Open Questions.
- **[Short-circuit may click a form submit before its fields are filled]** → when the `mopBoost>0` target is a form submit control, clicking it on an empty form wastes the monitored-operation attempt. The `form-completion` change supplies the submit-exclusion guard (INV-FORM-06) that `pickBestMopTarget` honours via its `excluded` set while the state has unfilled `EditText`s. This change and `form-completion` are **co-requisite**: the shipped short-circuit (`selectUnvisitedMopTarget`/`pickBestMopTarget`) already calls into `FormCompletion`, and the group-4 `findGreedyActionForward` probe (task 4.1) and form-completion's roulette exclusion (its task 7.3) edit the **same** `SataAgent.java:1072` site — they are integrated together, not sequenced. The convergent unfilled predicate (form-completion task 7.2) is a hard prerequisite: without it the exclusion never lifts and the submit is permanently blocked.
- **[Removing `+100` zeroes MOP-activity widgets with no flagged sibling]** → intended; those carried no MOP signal. Navigation toward MOP activities remains the WTG/menu job.
- **[INV-MOP-07 lives outside a requirement]** → removal noted in prose; archive step reconciles the Invariants section.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `score`: resolved-but-unflagged → 0, null → 0, direct → 500, transitive → 300 | `MopScorerTest` (forTest + synthetic JSON) | ~4 |
| Unit | short-circuit: unvisited mopBoost>0 selected; visited MOP target NOT force-picked; no MOP target → no-op | `SataAgent` selection test (state with mixed actions) | ~3 |
| Unit (regression) | WTG/menu/coverage boosts and `activityHasMop` predicate unchanged | existing `MopScorerTest` cases stay green | — |

## Open Questions

- **Short-circuit strength:** if the fair-test re-run shows the greedy short-circuit over-exploits (coverage drops), fall back to remove-`+100`-only and rely on the existing priority tiebreaker/roulette. Decide from the §7.5 measurement, not up front.

## Verified-Defect Fixes (tasks group 4)

Adversarial verification (2026-07-02) confirmed the epsilon-greedy short-circuit is **shadowed**: `selectNewActionNonnull` (`SataAgent.java:409-439`) runs EARLY_STAGE forward before epsilon-greedy, and `getGreedyActions` (`:630-651`) collects exactly the enabled/valid/unvisited actions, consuming unvisited MOP targets in a boost-weighted but probabilistic roulette (`randomPickWithPriority`, `:1072`) before the deterministic branch can see them. Trace mining: EARLY_STAGE = 57.6% of decisions; the short-circuit operated in <1%. Two fix options were evaluated: (a) hoist the short-circuit above EARLY_STAGE — rejected: globally reorders the SATA chain, overriding ABA/back-tracking bookkeeping for every MOP target; (b) probe `pickBestMopTarget` inside `findGreedyActionForward` just before the roulette — chosen: ~3 lines, deterministic exactly where unvisited actions are consumed, chain order intact, submit exclusion inherited.

Also confirmed: `stateMopDensity` was a widget-count proxy (gated on `activityHasMop`, then counted every valid targeted action), steering the ABA/trivial-activity tiebreakers toward widget-dense rather than MOP-dense screens. Fix mirrors `score`'s widget resolution inside the existing loop (~6-8 lines).

Steady-state context (informative, out of scope here): `greedyPickLeastVisited` (`State.java:133`) selects by minimum visit count with priority as tie-break only, so boosts cannot flip its argmax for visited actions — priority is fully consulted only in the two roulettes and the short-circuits. Re-selection of visited-but-unsaturated MOP targets is a deliberate non-goal pending its own ablation (it would change SATA's core bias for both arms).
