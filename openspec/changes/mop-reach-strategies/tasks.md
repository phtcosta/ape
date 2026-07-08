# Tasks — mop-reach-strategies

> Depends on `rv-scoring-pipeline` (B is a `ScoringPass`) and on the 5 open changes being archived (E-mín extends `activity-frontier`'s launcher). Order relative to the sibling: implement after `rv-scoring-pipeline` fixes the `ScoringPass` interface and property names.

## 1. Config flags (P4 current-state comments, no default changes)

- [x] 1.1 Add `Config.mopActivitySourceComponents` (`ape.mopActivitySourceComponents`, default `false`) next to the MOP flags
- [x] 1.2 Add `Config.mopFrontierWeight` (`ape.mopFrontierWeight`, default `0`) next to `mopWeightWtg`/`frontierBoostWeight`
- [x] 1.3 Add `Config.triggerMopFirst` (`ape.triggerMopFirst`, default `false`) next to `activityTriggerEnabled`
- [x] 1.4 Add `Config.llmPercentageNoSubstrate` (`ape.llmPercentageNoSubstrate`, default `-1`); load with the `-1` sentinel exempt from the `[0,1]` clamp, `>=0` clamped like `llmPercentage` (clamp extracted to the testable seam `Config.clampLlmPercentageNoSubstrate`; any real negative collapses to the `-1` sentinel)
- [x] 1.5 Register the four new flags in the `apePureMode` RV-flag registry from `rv-scoring-pipeline` (INV-ARCH-06): `mopActivitySourceComponents→false`, `mopFrontierWeight→0`, `triggerMopFirst→false` (all in `rvForcedOffValues`); `llmPercentageNoSubstrate` classified **exempt** (`rvExemptReasons`, "inert when the LLM masters are forced off and llmPercentage 0" — consistent with `llmModel`/`llmPromptVariant`/etc., avoids polluting the off-value shape buckets; user-approved deviation from the literal `→-1`). Kill-switch completeness guard stays green (575 tests)

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

- [x] 3.1 Test-first: `MopData.isWidgetlessSubstrate()` — 0-widget fixture → true, ≥1-widget fixture → false, empty `windows[]` → true (INV-MOP-28). `MopDataTest` +3 (`widgetlessSubstrate{FalseWhenAWidgetPresent,TrueWhenWindowHasNoWidgets,TrueWhenNoWindows}`)
- [x] 3.2 Implement `isWidgetlessSubstrate()` as a pure sum over `windows[].widgets` (short-circuit on first non-empty); no consumer
- [x] 3.3 `Config.llmPercentageNoSubstrate` load (default `-1` unclamped; `1.5` → `1.0`; `-0.2` → `-1` sentinel) is pinned in `ConfigTest` 1.4. Added the "no consumer" half in `LlmRouterTest.llmPercentageNoSubstrate_isExposedButNotConsumedByRouting`: exposed `-1` sentinel + structural guard that `LlmRouter` source never references the flag, so a future wiring must be a deliberate spec change (INV-RTR-09)

## 4. B — MopFrontierPass (TDD; after rv-scoring-pipeline)

- [x] 4.1 Tested at the JVM-runnable granularity (android stubs `Graph`/`GUITreeNode`/`ModelAction` are surefire-excluded, so `apply()` is device-deferred like `FrontierPass.apply` — task 7.4): (a) the MOP∩unvisited predicate `MopFrontierPass.qualifyingMopTargets` as a pure seam — MOP+unvisited qualifies, MOP+visited excluded, non-MOP+unvisited excluded (`MopFrontierPassTest`, INV-MFP-01); (b) the full `isEnabled()` gate — `mopFrontierWeight` is non-final so the JVM drives both weight=0→disabled (INV-MFP-03) and weight>0 with/without MopData+WTG (`ScoringPassGateTest`, try/finally-restored). The `setPriority`+`wtgBoost` read-modify-write accumulation (INV-MFP-02) runs on a resolved `ModelAction` → device (7.4), same boundary as the generic frontier boost
- [x] 4.2 Implemented `MopFrontierPass implements ScoringPass` in `com.android.commands.monkey.ape.agent.scoring`: own `getWtgTransitions(activity)` lookup, three-condition gate (widget↔transition match, `activityHasMop(target)`, `Graph.getActivityNode(target)==null`), `setPriority` increment + `setWtgBoost` read-modify-write; `isEnabled()` = `mopFrontierWeight>0 && MopData!=null && hasWtgData()`
- [x] 4.3 Registered `MopFrontierPass` in `ScoringPipeline.fromConfig` immediately after `FrontierPass`, before `CoveragePass` (frontier family contiguous; disabled by default weight 0). Position pinned by `ScoringPipelineTest.fromConfigInsertsMopFrontierAfterFrontierBeforeCoverageWhenWeighted`; the default-config `…AssemblesAllSixInOrder` stays valid (MopFrontier absent at weight 0)

## 5. E-mín — MOP-first launch ordering (TDD; after activity-frontier)

- [x] 5.1 Test-first: `selectTriggerCandidate` with `triggerMopFirst=true` prefers an eligible **MOP-reaching** candidate (membership from the `mopActivities` set = `activityHasMop` truth, NOT `ComponentInfo.reachesTarget`); falls back to a non-MOP eligible candidate when none MOP-reaching; deterministic under fixed order; flag-off = round-robin unchanged (INV-CT-09). `ActivityFrontierTest` +5 (`testMopFirst{PrefersReachingCandidate,FallsBackToNonMopWhenNoneReaching,OffIdenticalToRoundRobin,EligibilityUnchanged,RoundRobinWithinReachingGroup}`) — all 4 spec scenarios + within-group round-robin; pure JVM seam (no device deferral, unlike `MopFrontierPass.apply`). Tests pass a synthetic `Set<String>` membership so the fixture models the lambda-false-negative case (a candidate MOP-reaching yet `components.reachesTarget=false`)
- [x] 5.2 Implement the stable two-pass ordering (MOP-reaching eligible group first, each group in round-robin order) in `SataAgent.selectTriggerCandidate`, gated by `Config.triggerMopFirst`; eligibility filters unchanged. Added a 5th `Set<String> mopActivities` param (null ⇒ flag-off round-robin, MOP not consulted — the pure-seam idiom, decoupled from live `MopData`) + private `firstEligible(…, Boolean requireMop)` helper; `requireMop==null` reproduces the plain activity-frontier walk byte-identical (flag-off parity). **Signal is the reachability-augmented `activityHasMop` truth, not the lambda-false-negative `ComponentInfo.reachesTarget`** (verified on cryptoapp: all 4 activities `components.reachesTarget=false` but Cipher/MessageDigest/Cryptography genuinely reach MOP per `reachability[]`). Production call site passes `Config.triggerMopFirst ? getMopData().<mopActivities set> : null`; the 10 existing 4-arg seam calls updated to 5-arg (`null`)

## 6. G-2 — too-large reject unit consistency (regression guard; no code fix required)

> **Finding (2026-07-08, verified):** the premised decimal-MB vs binary-MiB unit bug **does not exist
> in the code**. `MopData.load`'s too-large comparison was born byte-consistent in `c6c5d1f`
> (the `mop-data-load-oom` archive): `File.length()` (bytes) `>` `budgetBytes / PARSE_FOOTPRINT_FACTOR`
> with `budgetBytes = Runtime.getRuntime().maxMemory()` (bytes). A `git log -S` over the file's history
> finds no decimal-MB (`1000000` / `1024*1024` / `/1000`) form on either operand at any commit. So G-2's
> code fix is a no-op; the residual, real value is a **regression-guard test** that pins INV-MOP-29 so a
> future refactor cannot introduce a mismatch. The redreader "recovers 3/657 runs" claim is **not
> attributable to a unit fix** — a 48.3 MiB file's rejection is a genuine heap-budget decision
> (`48.3 MiB × factor` vs device `maxMemory()`), not a unit artifact (see proposal/design corrections).

- [x] 6.1 Regression-guard test added (`MopDataLoadTest.fileBelowByteBudgetNotFalselyRejected`): a fixture at the exact byte boundary (`budget = size × factor` ⇒ `budget/factor == size`, strict `>` does not fire) SHALL NOT be rejected `too-large`; a decimal-MB conversion of either operand would move the boundary and flip it. Complements the existing `oversizedFileRejectedTooLarge` (reject side, `size=`/`budget=` in bytes). Green (INV-MOP-29)
- [x] 6.2 No code change: the too-large comparison is already unit-consistent (both operands in bytes/binary; `budget/factor` avoids overflow) since `c6c5d1f`; INV-MOP-26 OOM containment unchanged. Verified by blame + `git log -S` history search

## 7. Verification

- [x] 7.1 Full suite: `mvn test` (0 failures/errors) — 589 pass / 0 fail / 19 skip (was 584; +5 E-mín tests)
- [x] 7.2 `openspec validate mop-reach-strategies --strict` — "Change 'mop-reach-strategies' is valid"
- [x] 7.3 Device re-smoke on `cryptoapp` (RVSec API 30, shell/non-root) — VERIFIED 2026-07-08. Load line: `flagged=3 … handlersUnmatched=5 syntheticLambda=1 recovered=1` (Execute button recovered). `sata_mop_widget` (default): 772 events, `mop=300` on 12 steps, the Execute button selected `decision_source=MOP priority=652 mop=300` (+ cascade `wtg=200` as the recovered widget puts CryptographyActivity in `mopActivities`). `sata_mop_activity` (`mopActivitySourceComponents=true`): 681 events, 11 `mop>0` steps, 8× `decision_source=MOP`. Before the fix: `mop=0` on all 60 steps, no `decision_source=MOP`. Direct regression check for the bug found during `rv-scoring-pipeline` 7.6 — PASS
- [ ] 7.4 Device smoke on `cryptoapp` (RVSec API 30, shell/non-root) — PARTIAL, done this session for B; full validation run deferred to gh74:
  - **B (MopFrontierPass) — VERIFIED**: `ape.mopFrontierWeight=200` + `mopDataPath` → `[APE-ARCH] passes=[MopWidgetPass, MenuGatewayPass, WtgPass, FrontierPass, MopFrontierPass, CoveragePass, FormCompletionPass]` (correct position). `[APE-RV] MopFrontier boost: state=…MainActivity…, boosted=3, weight=200` fired; the GENERATE button (leads to unvisited MOP `CryptographyActivity`) got `[APE-STEP] … wtg=600` = FrontierPass 200 + MopFrontierPass 200 + WtgPass 200 → confirms the `setWtgBoost` RMW accumulation (INV-MFP-02) that task 4.1 deferred to device. Execute button still `decision_source=MOP mop=300` (lambda fix intact). Log: `docs/…` (scratchpad `armA_mopfrontier.log`, 120 steps, 0 SecEx)
  - **E-mín (triggerMopFirst) — NOT distinguishable on cryptoapp** (substrate, not a defect): the three eligible launch candidates (Cipher/MessageDigest/Cryptography, non-main, exported) are ALL MOP-reaching per `reachability[]` (2/1/13 methods); the only non-MOP activity is `MainActivity` (0 reaching methods — a pure navigation hub that only `startActivity`s the others; ICC/Intent edges are not modeled by the static analysis), which E-mín excludes anyway via `!isMain`. With no eligible non-MOP candidate, MOP-first ordering coincides with round-robin. The discriminative preference (mix of MOP/non-MOP eligible) is proven by the `selectTriggerCandidate` JVM tests. Correct signal is `activityHasMop`, NOT `ComponentInfo.reachesTarget` (all 4 report `components.reachesTarget=false` — the lambda false-negative that motivated the fix). Integration confirmed this session: `ape.triggerMopFirst=true`+`activityTriggerEnabled=true` ran clean (145 `[APE-STEP]`, 0 SecEx, no crash, `flagged=3` load). Full stagnation-launch + weight calibration deferred to gh74 on a deeper app (with an eligible non-MOP activity)
  - Confirm `sata_mop_widget` (A′ off) vs `sata_mop_activity` (A′ on) differ only in `mopActivitySourceComponents` (already shown in 7.3); redreader-scale JSON parses
