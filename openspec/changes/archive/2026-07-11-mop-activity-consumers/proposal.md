# Proposal: mop-activity-consumers

## Why

The cmpft4 forensic analysis (`rv-android/docs/20260710_analise_forense_cmpft4.md`, 2026-07-10;
6 parallel investigations over all 2628 traces + the `2f95711` jar + the 219 producer JSONs)
proved that the A′ activity-substrate widening shipped by `mop-reach-strategies` **worked at load
time but had no live consumer**, and that the stagnation activity launcher **fires at real
targets only ~24% of the time**:

- **A′ is inert in the `sata_mop_activity` arm.** The flag reached the jar (config echo in all
  traces) and widened `mopActivities` from 44 to 192/219 apps (87.7%). But the only two
  selection-affecting consumers in that arm are the OPTIONSMENU gateway (fires on 0.01–0.06% of
  steps) and the `stateMopDensity` navigation tiebreak — which is **self-defeating on exactly the
  activities A′ rescues**: it gates on `activityHasMop` (true for the A′ set) but then counts only
  widget-level MOP flags (`MopScorer.java:139` vs `:154-161`), which are what those activities
  lack by definition (that is why A′ exists). Density is 0-vs-0 wherever A′ made the difference.
  Empirically the three MOP arms were statistically identical (Friedman across arms:
  cov_method p=0.967) — **the A′ strategy was never actually exercised, so the H1 question is
  still open, not answered**.
- **The activity launcher aims at framework/tooling garbage.** Of the 114
  `EVENT_TRIGGER_ACTIVITY` launches in the `act_frontier` arm, 63 (55%) targeted
  `androidx.compose.ui.tooling.PreviewActivity`, 19 (17%) the abstract
  `androidx.activity.ComponentActivity`, ~5 test scaffolds; only ~27 (24%) were genuine app
  activities. These classes pass the existing eligibility (`exported && permission==null &&
  !isMain && unvisited`, `SataAgent.firstEligible`) because debug-build manifest merging declares
  them as exported components of the app package. There is **no existing activity exclusion list
  anywhere in the codebase** (verified 2026-07-10: the only `androidx.*` constants are widget
  class names in `LlmRouter`/`ApePromptBuilder`, unrelated) — the fix extends the single existing
  eligibility predicate rather than adding a parallel mechanism.
- **A′ is invisible in traces.** `augmentActivitiesFromSources` logs nothing and the
  `[APE-MOP-DATA]` line carries no activity-substrate counter, so no trace can distinguish the
  `widget` and `activity` arms at runtime. The strategy is unfalsifiable without telemetry.

## What Changes

One change, deliberately small and single-purpose so it can be discarded as a unit if cmpft5
shows no effect:

1. **`stateMopDensity` activity-substrate floor** (`MopScorer.java`). New semantics:
   `0` when `!activityHasMop(activity)`; otherwise `1 + <count of MOP-flagged resolved widget
   actions>`. The `+1` floor makes an A′-rescued activity (no widget flags) rank above a
   non-MOP activity in the navigation tiebreaks, while widget-flagged states still rank above
   A′-only states (ordering among previously-nonzero states is preserved — all shift by +1).
   This is a cross-*state* ranking signal, not a per-candidate boost — it does not reintroduce
   the removed uniform `+mopWeightActivity` fallback (whose defect was shifting every candidate
   *within* a state equally).
2. **Framework/tooling denylist in the launcher eligibility** (`SataAgent.firstEligible`).
   A candidate whose `className` starts with a framework/tooling namespace prefix
   (`android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`,
   `org.junit.`, `leakcanary.`) is ineligible. Extends the existing single predicate; no new
   mechanism, no new flag (the launcher stays gated by `ape.activityTriggerEnabled`).
3. **A′ observability.** The `[APE-MOP-DATA] status=loaded` line additionally reports
   `mopActivities=<n>` (final set size) and `mopActsAugmented=<m>` (entries contributed by the
   A′ sources beyond the widget-derived set; 0 with the flag off). The navigation path-selection
   tiebreak logs one `[APE-RV] Nav MOP tiebreak` line when (and only when) density actually
   decided the path. Together these make the `widget`-vs-`activity` arm contrast auditable in
   traces.
4. **No new Config flag, no tool.py change, no new scoring pass.** Everything rides the
   existing arm flags (`ape.mopActivitySourceComponents` gates the A′ set;
   `ape.activityTriggerEnabled` gates the launcher). `apePureMode` is unaffected (it never loads
   MopData, so both touched code paths are unreachable in the pure arm; INV-ARCH-06 registry
   unchanged).

**Non-goals** (explicitly out, with reasons):
- **No new activity-level scoring pass.** The uniform per-activity boost was removed on purpose
  (`mop-guidance` MopScorer requirement; obsolete INV-MOP-07): a boost applied equally to every
  candidate on a screen cannot re-rank them. The floor (#1) acts where ranking actually happens
  (across states/paths).
- **No widening of the frontier `hasWtgData` click-gate.** cmpft4 stratified analysis showed the
  frontier arm does not beat widget even on the 60 apps where the gate passed (cov_mop p=0.26)
  — widening the gate has no supporting evidence.
- **No seed wiring.** Paired seeds cannot align trajectories across arms with different
  priorities (roulette diverges at the first differing boost) nor across the nondeterministic
  GUI environment; cmpft5 power comes from stratification/reps, not seeds.

## Impact

- **Specs**: `mop-guidance` (MODIFIED: `stateMopDensity` requirement + INV-MOP-24; ADDED:
  activity-substrate load-line counters + tiebreak decision log), `component-triggering`
  (MODIFIED: Stagnation-Triggered Activity Launch eligibility + INV-CT-06; ADDED INV-CT-10).
- **Code**: `MopScorer.java` (density floor), `SataAgent.java` (denylist constant + predicate;
  tiebreak log), `MopData.java` (two load-line counters). Test-only elsewhere.
- **Dependency / archive ordering**: builds on the A′ union and load-line diagnostics shipped by
  `mop-reach-strategies` (open, 27/28) — **archive `mop-reach-strategies` before this change**.
  Its deltas are untouched: it changes launcher *ordering* (`triggerMopFirst`) and asserts
  eligibility unchanged; this change alters *eligibility* (INV-CT-06) afterwards.
- **Experiment**: cmpft5 can A/B `sata_mop_widget` vs `sata_mop_activity` with an actually-live
  A′ consumer, auditable per trace; the launcher stops wasting its one-shot-per-episode on
  tooling activities.
