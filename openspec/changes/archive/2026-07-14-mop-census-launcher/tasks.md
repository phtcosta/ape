# Tasks: mop-census-launcher

<!-- Small change (2 source files + 3 test files), net-negative LOC. Sequential groups; TDD per
     project convention: write failing tests first within each group. NOT approved for apply yet —
     artifacts under user review. -->

## 1. Delete triggerMopFirst (Config + kill-switch)

- [x] 1.1 RED: update `ConfigTest` — remove the `triggerMopFirst` default test; update
      `ApePureModeKillSwitchTest` — `ape.triggerMopFirst` no longer in the forced list
      (INV-ARCH-06 completeness guard must still pass with the entry gone)
- [x] 1.2 GREEN: delete `Config.triggerMopFirst` (field + load) and its entry in the kill-switch
      forced list; re-document `activityTriggerStagnationStep` as the launcher cadence (property
      name kept for the rv-android `tool.py` mapping — state this constraint at the declaration)
- [x] 1.3 Run `/sdd-test-run ape.utils` (Config + kill-switch tests green)

## 2. Cadence firing predicate + census-only selection in SataAgent

- [x] 2.1 RED: rework `ActivityFrontierTest` firing cases for
      `shouldFireLauncher(enabled, hasMopData, stepsSinceFiring, cadence, launchesSoFar, maxPerRun)`:
      fires at counter==cadence (10 and 50), not below/above; cap=2 blocks the 3rd fire; cap=0
      unlimited; disabled/no-MopData still gate (INV-CT-12 cases carried over; stagnation-gate and
      INV-CT-11 byte-identical cases deleted)
- [x] 2.2 RED: rework candidate-selection cases: census membership required (non-census candidate
      never returned, even with the census empty — no fallback); `exported=false` census candidate
      IS returned; permission/main/visited/denylist still skip; round-robin order preserved across
      calls (E-mín two-group and fallback cases deleted)
- [x] 2.3 GREEN: rename `shouldTriggerAtStagnation` → `shouldFireLauncher` operating on the new
      `_stepsSinceLauncherFiring` counter (incremented once per launcher-block pass; reset to 0 at
      every firing point regardless of candidate outcome); launcher block stops reading/resetting
      `graphStableCounter`
- [x] 2.4 GREEN: collapse `selectTriggerCandidate` + `firstEligible` into the single
      census-restricted walk (drop the `requireMop` tri-state and the two-group logic; drop
      `c.exported`; require `mopActivities.contains(className)`); call site passes
      `getMopData().getMopActivities()` unconditionally
- [x] 2.5 Confirm the LLM stagnation hook still uses `graphStableRestartThreshold / 2` unchanged
      (comment updated: the launcher no longer shares any firing point with it)
- [x] 2.6 Run `/sdd-test-run ape.agent`

## 3. Verification

- [x] 3.1 Full suite: `mvn test` green (609 run, 0 fail, 19 skip; net −6 vs baseline 615 from
      deleting the E-mín two-group / INV-CT-11 / triggerMopFirst cases — LOC net-negative)
- [x] 3.2 `openspec validate mop-census-launcher --strict` passes (also `--specs`: 19/19)
- [x] 3.3 Run `/sdd-verify ape` (PASS: 609 tests; lint skipped — no linter for java)
- [x] 3.4 Device smoke (cmpft5 Gate 0 v4, experiment session): rebuild
      `mvn install -Drvsec_home=...`, record jar md5, bind-mount; arms
      `activity_trigger_enabled=true, activity_trigger_stagnation_step=30, activity_trigger_max_per_run=8`,
      contrast `mop_activity_source_components` — gate: dose > 0 (`[APE-RV] Triggering activity:`)
      in BOTH arms including one large app (speakthat-class), AND the launched sets differ between
      arms in ≥1 app, AND zero denylisted launches; post-launch MOP events counted from the trace
      (not assumed from `onCreate`)
      → **PASSOU** (Gate 0 v4, jar md5 `ba845d2e` bind-mounted; evidência:
      `rvsec/rv-android/docs/20260711_cmpft5.md` §7.4). Dose>0 nos 6 arm-runs incl. speakthat
      (activity 8 / widget 4); launched-sets diferem nos 3 apps; zero denylist; monitor JavaMOP
      vivo ponta-a-ponta (freeotp/activity → MacSpec em TokenCodeUtil.getHOTP); `mopActsAugmented>0`
      só no tratamento. Validação de desfecho pelo **run completo cmpft5** (1314/1314 COMPLETED,
      219 APKs × 2 braços × 3 reps): **H1 APOIADA** no estrato A′-delta pré-registrado (n=185),
      `cov_mop` sata_mop_activity > sata_mop_widget — p=9,643×10⁻⁸ (Wilcoxon signed-rank),
      rank-biserial=+0,473 [IC95% 0,32; 0,62], diff mediana pareada +1,01 p.p.; laudo detalhado:
      `rvsec/rv-android/docs/20260714_analise_detalhada_cmpft5.md`
