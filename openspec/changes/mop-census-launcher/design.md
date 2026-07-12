# Design: mop-census-launcher

## Context

Gate 0 v3 evidence (rv-android `docs/20260711_relatorio_gate0v3_cmpft5.md` + this session's deep
analysis) identified three independent structural blockers in the stagnation launcher, each alone
sufficient for a null:

1. **Exported-only eligibility** — `firstEligible` requires `c.exported`; in the 3 smoke apps every
   MOP-reachable activity is `exported=false` (only MainActivity is exported, and it is excluded by
   `isMain`). The launch path itself does not need export: `AndroidDevice.startActivity` calls
   `IActivityManager.startActivity` directly from the monkey process (uid 2000, holds
   `START_ANY_ACTIVITY`) — verified by `adb shell am start` probe (same uid, same permission check).
2. **Episode scarcity** — firing requires `graphStableCounter == step`; the counter resets on every
   graph growth. In 300s runs, 48% of cmpft4 traces never produce a single 10-stable window, and the
   deficit is anti-correlated with app size: speakthat (31 activities, census 5→30 under A′) never
   exceeded counter 6. Dose ≈ 0 exactly where the arm contrast is largest. Step recalibration cannot
   fix this (no step ≤ 6 yields meaningful dose there either).
3. **Contrast dilution** — `selectTriggerCandidate` falls back to the non-MOP group, so both arms
   launch the same eligible set; the census difference only affects ordering, which the coverage
   endpoints cannot see.

Three nulls with three accumulated patches means the mechanism, not the calibration, is wrong
(systematic-debugging: after 3 failed fixes, question the architecture). This design replaces the
mechanism with a strictly smaller one instead of adding a fourth knob.

## Goals / Non-Goals

**Goals**
- One launcher behavior: every N steps, launch the next unvisited activity from the arm's MOP census.
- Delete code: exported filter, non-MOP fallback, two-group ordering, `triggerMopFirst`,
  stagnation coupling.
- Zero new Config flags; zero rv-android changes.

**Non-Goals**
- No change to `EVENT_TRIGGER_ACTIVITY` dispatch, step attribution, or budget semantics.
- No change to the LLM stagnation hook (independent mechanism, keeps `graphStableRestartThreshold / 2`).
- No producer-side (static analysis JSON) changes.
- No seeding/protocol changes — those live in the experiment config, not here.

## Decisions

### D1 — Cadence gate on a dedicated step counter

`shouldTriggerAtStagnation` becomes `shouldFireLauncher(enabled, hasMopData, stepsSinceFiring,
cadence, launchesSoFar, maxPerRun)` (same pure-seam shape; equality test on the dedicated counter).
A new private `_stepsSinceLauncherFiring` increments once per `selectNewActionNonnull` pass through
the launcher block and resets to 0 at every firing point **regardless of candidate outcome**. The
launcher neither reads nor resets `graphStableCounter` anymore.

- Reset-on-firing-point (not reset-on-launch) keeps firing strictly periodic and prevents per-step
  rescans once the census is exhausted. Launch *budget* still only counts returned actions
  (INV-CT-12 unchanged): an empty scan costs a cadence window, not budget.
- Expected dose with cadence N in a ~300-step run: `min(maxPerRun, steps/N, |unvisited census|)` —
  deterministic in every app, including the large ones where stagnation never occurred.
- The method rename is deliberate (P4: the old name would describe a mechanism that no longer
  exists). The *property* name `ape.activityTriggerStagnationStep` is kept: renaming it would
  require touching the rv-android `tool.py` mapping, which is out of bounds for this change. The
  discrepancy is documented once, at the `Config` declaration (same wire-format-vs-model pattern as
  the `reachesTarget`/MOP boundary, gh13 D7).

### D2 — Census-only eligibility, exported term deleted

`firstEligible` keeps the round-robin walk and the conjunction `permission == null ∧ !isMain ∧
className != mainActivity ∧ !visited ∧ !framework-prefix`, drops `c.exported`, and adds
`mopActivities.contains(c.className)` as a required term (the `requireMop` tri-state parameter and
the two-group walk in `selectTriggerCandidate` collapse into this single walk).

- The census is `MopData.getMopActivities()` — the `activityHasMop` reachability-augmented set
  (INV-MOP-27), which is exactly the arm-varying quantity (`ape.mopActivitySourceComponents`
  augments it in the treatment arm). The launcher consuming *only* the census makes the launched
  set the arm contrast, which is the fair test of H1.
- Dropping `exported` is safe at the dispatch layer (uid-2000 `START_ANY_ACTIVITY`, probe-verified)
  and is the direct fix for the Gate 0 v3 structural null. `permission == null` stays: an explicit
  `android:permission` on the activity is checked even for shell-uid callers in some code paths and
  a launch denial is a wasted step either way. The denylist stays: manifest-merged framework
  entries can in principle appear in the census via over-approximated reachability, and the filter
  is one line (INV-CT-10 unchanged).
- Launching non-exported activities cold can crash activities that assume intent extras. This is
  accepted and *measured*, not prevented: `logcat_diagnostics` is already on in the cmpft5 compose,
  and the device gate counts post-launch MOP events rather than assuming `onCreate` completes.

### D3 — `triggerMopFirst` deleted (P3)

The flag only existed to order a fallback that no longer exists. Delete: `Config` field + load,
the `ape.triggerMopFirst` entry in the kill-switch forced list, `ConfigTest`/
`ApePureModeKillSwitchTest` expectations, and the "MOP-First Ordering of Stagnation-Launch
Candidates" requirement (INV-CT-09). The `tool.py` mapping line for `trigger_mop_first` stays in
rv-android untouched — arms simply stop passing it, and an unknown `ape.properties` key is ignored
by `Config`.

### D4 — What deliberately does not change

`ActivityTriggerAction` / `EVENT_TRIGGER_ACTIVITY` semantics (one `[APE-STEP]` line,
`decision_source=Component`, no graph edge — INV-CT-07), explicit-intent + deep-link dispatch and
package derivation (INV-CT-04), `activityTriggerMaxPerRun` budget accounting (INV-CT-12), clamps
(INV via ConfigTest), kill-switch registry structure (`activityTriggerEnabled` forced false under
`apePureMode`; step/cap exempt sub-params), round-robin index persistence across firings.

## Risks / Trade-offs

- **Cold-launch crashes** (non-exported internal activities may NPE on missing extras): accepted;
  measured via logcat diagnostics; a crashed activity still counts only if MOP events actually
  fired (JavaMOP monitors instrument JCA call sites, not method entry).
- **Cadence consumes steps** that SATA would have used: bounded by `maxPerRun` (cap × 1 step per
  launch; cap=8 ≈ 3% of a 300-step run).
- **Stale property name** (`...StagnationStep` meaning cadence): the cost of not touching
  rv-android; contained to one documented declaration.
- **H1 may still be null** — but then the null is informative: with guaranteed dose, viable
  eligibility, and census-valued contrast, a flat result falsifies conversion rather than the
  apparatus.

## Migration Plan

Single TDD pass in this worktree (branch `mop-fairtest`); no data or config migration. Rollback =
revert the commit. The cmpft5 protocol update (arms, gate) lives in the experiment session, not here.

## Open Questions

- Cadence value for cmpft5 (suggested N=30, cap=8 → up to 8 launches/run at ≤3% step overhead) —
  experiment-session decision, pre-registered in the protocol, not a code concern.
