# Design: mop-activity-consumers

## D1 — Density floor semantics: `1 + count`, not a weight, not a new pass

Chosen: `stateMopDensity = activityHasMop(activity) ? 1 + widgetFlaggedActionCount : 0`.

- **Why a floor and not a uniform boost**: the repo already removed the uniform
  `+mopWeightActivity` per-candidate fallback because a constant added to every candidate on a
  screen cannot re-rank candidates (mop-guidance, MopScorer requirement; INV-MOP-07 obsolete).
  `stateMopDensity` is different: it ranks **states/paths against each other** in the SATA
  navigation tiebreaks (`SataAgent.java:1059,1071` cold-state/cold-activity comparisons;
  `:1311-1323` path selection). A floor of 1 is discriminative at exactly that granularity:
  MOP-bearing state (A′ or widget) > non-MOP state.
- **Why `1 +` and not `max(count, 1)`**: identical for count=0; for count≥1 both preserve
  ordering among widget-flagged states; `1 +` keeps the widget-flagged > A′-only strict ordering
  at count=1 (`2 > 1`) whereas `max` would tie them (`1 == 1`). Widget-level evidence is more
  specific (the flagged widget is on-screen and clickable) so it should outrank the
  activity-level-only signal.
- All pre-change comparisons between two nonzero states are preserved (both shift by +1);
  the only behavioral delta is A′-only states now beating non-MOP states instead of tying at 0.
- INV-MOP-24 is amended accordingly (it currently forbids anything but the widget count; the
  floor is a deliberate, bounded exception — the invariant's intent, "never reduce to a total
  action count", is kept verbatim).

## D2 — Denylist: prefix match on `className`, inside `firstEligible`, no new flag

- **Verified before designing (2026-07-10)**: the codebase has **no existing activity/component
  exclusion list** to reuse. Grep over `src/main/java` for exclusion/blacklist/androidx patterns
  finds only: permission blacklist (`AndroidDevice`), naming-refinement blacklists
  (`NamingFactory`), refresh-check state blacklist (`StatefulAgent`), and two `androidx.*`
  *widget-class* arrays in `LlmRouter.java:47`/`ApePromptBuilder.java:35` (LLM prompt concerns,
  unrelated to component eligibility). The launcher's single eligibility point is
  `SataAgent.firstEligible` (`:691-693`) — the denylist extends that predicate; nothing is
  duplicated.
- **Why class-namespace prefixes and not a manifest/package check**: the garbage targets ARE
  legitimate manifest components of the app package (Compose/leakcanary tooling injected by
  manifest merger into debug builds; `exported=true`), and the launch already uses
  `MopData.getPackageName()` for the `ComponentName` package (INV-CT-04). The discriminator is
  the **class namespace**: app code is never authored under `android.`/`androidx.`/`kotlin.`
  etc. Abstractness cannot be checked from the JSON (and `Class.forName` is unavailable across
  processes), but the observed abstract offender (`androidx.activity.ComponentActivity`) falls
  to the same prefix rule.
- Prefix set (constant `FRAMEWORK_ACTIVITY_PREFIXES`, `String[]` next to `firstEligible`):
  `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`,
  `org.junit.`, `leakcanary.`. Covers 100% of the garbage observed in cmpft4 (PreviewActivity,
  ComponentActivity, test/instrumentation scaffolds). Deliberately a code constant, not config:
  the list is a correctness fix, not a tunable (P1; adding a flag would also require
  INV-ARCH-06 registry work for a knob nobody varies).
- Placement inside `firstEligible` keeps the round-robin index semantics unchanged (skipped
  candidates don't consume an episode's launch, same as the existing visited/permission skips)
  and applies identically to both `triggerMopFirst` groups (INV-MOP-15 ordering untouched).

## D3 — Observability: two load-line counters + one decision log

- `[APE-MOP-DATA] status=loaded ...` gains `mopActivities=<n> mopActsAugmented=<m>`:
  `n` = final `mopActivities.size()` after Pass-2/DIALOG-rekey/A′; `m` = entries added by
  `augmentActivitiesFromSources` beyond the pre-existing set (0 when
  `mopActivitySourceComponents=false`). Mirrors the FIX-3 pattern (`handlersUnmatched=...`):
  pure counters, never alter outcomes. This makes the widget/activity arm contrast visible on
  line 1 of every trace (cmpft4's censuses had to be inferred from a config-echo diff).
- Path-selection tiebreak (`SataAgent.java:1311-1323`) logs
  `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>` **only when** the densities were not all
  equal (i.e., density actually chose the path instead of the random fallback). The two
  cold-state/cold-activity comparison sites (`:1059`, `:1071`) do NOT log — they run inside a
  per-step candidate loop and would flood the trace; the path-refill site fires at most once
  per buffer refill.

## D4 — No flag, no tool.py, apePureMode untouched

Both code paths are gated behind `getMopData() != null` (density tiebreaks and launcher alike);
`apePureMode` unsets `ape.mopDataPath`, so the pure arm cannot reach them — no new registry
entry (INV-ARCH-06 list unchanged, guard test must stay green). The rv-android side needs
nothing: no new property key, and the arm flags that gate the behavior
(`mop_activity_source_components`, `activity_trigger_enabled`) already exist in
`APERV_PROPERTY_MAPPING` (gh74).

## D5 — Collision analysis vs the open `mop-reach-strategies` (archive ordering)

`mop-reach-strategies` (27/28, cmpft4-validated) ADDED the A′ union, FIX-2/FIX-3, and the
`triggerMopFirst` ordering requirement, whose text asserts "eligibility unchanged (INV-CT-06)".
This change MODIFIES eligibility (INV-CT-06) and the `stateMopDensity` requirement/INV-MOP-24 —
requirements `mop-reach-strategies` references but does not modify. No same-requirement delta
overlap exists, but the semantic ordering matters: **archive `mop-reach-strategies` first**,
then apply this change's deltas. The delta texts below are written against the
post-`mop-reach-strategies` main spec.

## D6 — Explicitly rejected alternatives

- **New `MopActivityPass`** (uniform on-screen boost): re-creates the removed INV-MOP-07
  mechanism; cannot re-rank within a state. Rejected.
- **Widening `hasWtgData` beyond click transitions**: no evidence of benefit — cmpft4 stratified
  on the 60 gate-passing apps still shows frontier ≤ widget (cov_mop p=0.26). Rejected.
- **Seeding cmpft5**: RNG pairing cannot survive divergent priorities (roulette,
  `SataAgent.java:1468`) nor GUI-environment nondeterminism; power comes from
  stratification/reps. Rejected (user decision 2026-07-10).
- **Config-tunable denylist**: correctness fix, not a tunable; would add registry surface for
  no experimental use. Rejected.
