# Tasks — mop-reach-strategies

> Depends on `rv-scoring-pipeline` (B is a `ScoringPass`) and on the 5 open changes being archived (E-mín extends `activity-frontier`'s launcher). Order relative to the sibling: implement after `rv-scoring-pipeline` fixes the `ScoringPass` interface and property names.

## 1. Config flags (P4 current-state comments, no default changes)

- [x] 1.1 Add `Config.mopActivitySourceComponents` (`ape.mopActivitySourceComponents`, default `false`) next to the MOP flags
- [ ] 1.2 Add `Config.mopFrontierWeight` (`ape.mopFrontierWeight`, default `0`) next to `mopWeightWtg`/`frontierBoostWeight`
- [ ] 1.3 Add `Config.triggerMopFirst` (`ape.triggerMopFirst`, default `false`) next to `activityTriggerEnabled`
- [ ] 1.4 Add `Config.llmPercentageNoSubstrate` (`ape.llmPercentageNoSubstrate`, default `-1`); load with the `-1` sentinel exempt from the `[0,1]` clamp, `>=0` clamped like `llmPercentage`
- [ ] 1.5 (PARTIAL: mopActivitySourceComponents registered in rvForcedOffValues + kill-switch guard green; 3 remaining flags pending their levers) Register the four new flags in the `apePureMode` RV-flag registry from `rv-scoring-pipeline` (INV-ARCH-06): `mopActivitySourceComponents→false`, `mopFrontierWeight→0`, `triggerMopFirst→false`, `llmPercentageNoSubstrate→-1`; the sibling's kill-switch completeness guard must stay green

## 2. A′ — activity-level activityHasMop source, 3-source union (TDD)

- [x] 2.1 Test-first: `MopData.load` on a fixture with a `components.activities[]` entry `reachesTarget=true` and no MOP-flagged widget — assert `activityHasMop==true` only when `mopActivitySourceComponents=true`; assert widget-derived entries preserved (union, not replacement); assert flag-off = widget-only set (INV-MOP-27)
- [x] 2.1b Test-first (source 3, the cryptoapp case): a fixture where `components.activities[].reachesTarget=false` and no flagged widget, BUT the activity's `reachability[]` class (`componentType=activity`) has ≥1 method `reachesTarget=true` — assert `activityHasMop==true` only when the flag is on; assert an activity with a `reachability` class but NO reaching method is NOT added (INV-MOP-27)
- [x] 2.2 Implement the 3-source union in `MopData` (widget-derived + `components.activities[].reachesTarget` + `reachability[]` activity-class-with-any-reachesTarget-method). Read JSON `reachesTarget` at the parse boundary (`Target` vocabulary); write to `mopActivities` (`MOP` vocabulary; gh13 D7). Scorer arithmetic unchanged
- [x] 2.3 Verify no regression on the existing widget-derived scenarios with the flag off (byte-identical `mopActivities`)

## 2A. FIX 2 — widget MOP-flag recovery for desugared-lambda handlers (TDD, always-on when MOP loaded)

- [x] 2A.1 Test-first: a widget whose only listener handler is `<X$$ExternalSyntheticLambda0: void onClick(...)>` (reachability `reachesTarget=false`) with enclosing class `X` carrying a `lambda$…` method `reachesTarget=true` → widget `transitiveMop==true`, base activity in `mopActivities`; a `$$ExternalSyntheticLambda` handler whose enclosing class has no reaching lambda → NOT flagged; exact-match handlers unchanged (INV-MOP-30)
- [x] 2A.2 Implement the enclosing-class fallback in `MopData.deriveWidgetMopFlags`: on exact-join miss, if `l.handler` class matches `<ENCLOSING$$ExternalSyntheticLambda\d+: …>`, mark `transitiveMop` (and `directMop` when the reaching lambda is `directlyReachesTarget`) iff `ENCLOSING` has a `lambda$…` reachability method with `reachesTarget=true`. Needs a `className → hasReachingLambda` index built in `parseReachability`. Active only when MOP data loaded; no flag (correctness fix)
- [x] 2A.3 Verify: with `mopDataPath` null (default / `apePureMode`) the recovery path is never entered (standalone default byte-identical)

## 2B. FIX 3 — handler-join diagnostics on the load line (TDD)

- [x] 2B.1 Test-first: `MopData.load` counts distinct unmatched handlers, of which D8 synthetic-lambda, of which recovered; asserts `recovered ≤ syntheticLambda ≤ handlersUnmatched`; asserts counters do not alter `mopActivities`/widget flags/load outcome (INV-MOP-31)
- [x] 2B.2 Implement `handlersUnmatched=<n> syntheticLambda=<m> recovered=<k>` fields on the `[APE-MOP-DATA] status=loaded` line (diagnostic only)

## 3. F′ seams (TDD)

- [ ] 3.1 Test-first: `MopData.isWidgetlessSubstrate()` — 0-widget fixture → true, ≥1-widget fixture → false, empty `windows[]` → true (INV-MOP-28)
- [ ] 3.2 Implement `isWidgetlessSubstrate()` as a pure sum over `windows[].widgets`; no consumer
- [ ] 3.3 Test-first: `Config.llmPercentageNoSubstrate` load — default `-1` unclamped; `1.5` → `1.0`; `-0.2` (a real negative, not the sentinel) behaviour documented (INV-RTR-09). Assert `LlmRouter`/`shouldRouteRandom` unchanged (no consumer)

## 4. B — MopFrontierPass (TDD; after rv-scoring-pipeline)

- [ ] 4.1 Test-first (scoring-pass test with stub `Graph`+`MopData`): MOP+unvisited target boosted; MOP+visited not; non-MOP+unvisited not; weight `0` = byte-identical; accumulation into `wtgBoost` with `mopWeightWtg`/`frontierBoostWeight` (INV-MFP-01/02/03)
- [ ] 4.2 Implement `MopFrontierPass implements ScoringPass` in `com.android.commands.monkey.ape.agent.scoring`: own `getWtgTransitions(activity)` lookup, three-condition gate, `setPriority` increment + `setWtgBoost` read-modify-write; `isEnabled()` = `mopFrontierWeight>0 && MopData!=null && hasWtgData()`
- [ ] 4.3 Register `MopFrontierPass` in `ScoringPipeline.fromConfig` (position per the sibling's pass table — additive to the generic `FrontierPass`)

## 5. E-mín — MOP-first launch ordering (TDD; after activity-frontier)

- [ ] 5.1 Test-first: `selectTriggerCandidate` with `triggerMopFirst=true` prefers a `reachesTarget=true` eligible candidate; falls back to `reachesTarget=false` when no MOP candidate eligible; deterministic under fixed order; flag-off = round-robin unchanged (INV-CT-09)
- [ ] 5.2 Implement the stable two-pass ordering (MOP-reachable eligible group first, each group in round-robin order) in `SataAgent.selectTriggerCandidate`, gated by `Config.triggerMopFirst`; eligibility filters unchanged

## 6. G-2 — too-large reject unit fix (TDD)

- [ ] 6.1 Test-first: a fixture whose true byte size is below `maxMemory()/factor` (redreader-scale) SHALL NOT be rejected `too-large`; a genuinely oversized fixture SHALL still be rejected; `size=`/`budget=` fields report bytes (INV-MOP-29)
- [ ] 6.2 Make the too-large comparison unit-consistent in `MopData.load` (both operands in bytes/binary; no decimal-MB conversion on one side); preserve INV-MOP-26 OOM containment verbatim

## 7. Verification

- [ ] 7.1 Full suite: `mvn test` (0 failures/errors)
- [ ] 7.2 `openspec validate mop-reach-strategies --strict`
- [ ] 7.3 Device re-smoke on `cryptoapp` (RVSec API 30, shell/non-root, wait for boot to settle): confirm FIX 2 + A′ make MOP actually fire — with `mopDataPath` set, the load line shows `flagged>2` and `recovered≥1` (Execute button recovered); at least one `[APE-STEP]` shows `mop>0`; with `mopActivitySourceComponents=true` the 3 crypto activities enter `mopActivities` (from source 3, since `components.activities[].reachesTarget=false` for all). This is the direct regression check for the bug found during `rv-scoring-pipeline` 7.6
- [ ] 7.4 Device smoke (future full validation run): `MopFrontierPass` boost visible in `[APE-STEP] wtg=`; with `triggerMopFirst=true` a MOP activity is launched first at stagnation; redreader-scale JSON parses. Calibrate `mopFrontierWeight` vs `frontierBoostWeight`. Confirm the `sata_mop_widget` (A′ off) vs `sata_mop_activity` (A′ on) arms differ only in `mopActivitySourceComponents`
