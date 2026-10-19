# isolate-guidance-groups — evidence

## 11.7 Fixture re-derive with the sister derive (2026-10-17)

- Derive: rv-android worktree `worktrees-aperv/isolate-guidance-groups/rv-android`, HEAD `c38cd624`,
  which contains `482c7cfb`; `derive_mop_artifact.py` is unchanged between the two
  (`git diff --stat 482c7cfb HEAD` empty). Generator `aperv-derive/2`.
- Procedure: the library calls of
  `openspec/changes/archive/2026-10-09-llm-coordinate-single-base/evidence/derive_fixtures.py`
  (`derive`, `serialize_canonical`, `digest_of_file`), run with the worktree's `.venv`
  (`python -I -B`), output to a scratch directory. The archived script itself no longer runs: it
  imports `digest_of_file` from `derive_mop_artifact`, which no longer re-exports it; the wrapper
  imports it from `rv_android_core.util.analysis_document` (where `tool.py:61-63` takes it).
- Result: byte-identical to the committed fixtures, so nothing was rewritten.

| Fixture | Bytes | sha256 (first 16) | vs committed |
|---|---|---|---|
| `test-apks/cryptoapp.apk.mop.json` | 4657 | `8a4103615ce6ca49` | identical |
| `src/test/resources/cryptoapp.apk.mop.json` | 4657 | `8a4103615ce6ca49` | identical |
| `src/test/resources/synthetic-activity-selection.mop.json` | 975 | `413407ff431250e1` | identical |

## Wave 2 notes accepted as-is (recorded, not changed)

- F1 writes `dec.rot` after `updateState`; on the bad-state path it can overwrite the previous
  record's `rot`.
- F1 draws the fuzz toss after the step's events (single consumer, same sequence).
- F3 `dec.nav` may stay stale across an aborted ABA attempt (the spec says MAY).
- F4 `own.g` is cleared when a later LLM stage of the same step calls.

## 11.8 / 11.9 Package and install (2026-10-17)

- `mvn clean test` on HEAD `f588eb61` plus the uncommitted wave-2 tree: 1439 tests, 0 failures, 0 errors, 24 skipped.
  [verified]
- `mvn package`: `target/ape-rv.jar` sha256 `7b334d26451dda07f63909ef7e2025070b563568133a424d00c097769a69b5c4`,
  `BuildInfo.GIT_SHA = f588eb6`. [verified]
- `mvn install -Drvsec_home=<worktrees-aperv/isolate-guidance-groups>`: the installed jar (rebuilt, so `JAR_BUILT`
  differs) has sha256 `2d3f14a68bad02e1650285512ac46be8dec9b5d41b933d547067cd61eebc048c`, `GIT_SHA = f588eb6`, and is
  byte-identical to `target/ape-rv.jar`. [verified]
- F7 module tests against that jar (run by `aperv-tool`): aperv-tool suite 791 passed, 72 skipped; migration with
  `APE_REPO` on this working tree 24 passed + 1 XPASS(strict) `test_preset_names_are_exactly_the_six_cells` (expected:
  the tree has only the six cells; the marker flips against the wave-2 SHA); M.4 against `src/test/resources/cells/`
  50 passed + 2 XPASS(strict); no difference against contract v3.2. [reported]
- Both jars predate the wave-2 commit; the jar handed out for 12.1 is rebuilt after it.
- Wave 2 committed `a2fde6cc` (goldens' `capturedAt` = `f588eb61`, re-stamped before the commit; only the header
  changed, because the comparison excludes `capturedAt` and the suite passes without regeneration). Jar rebuilt on
  the commit and installed in the worktree: sha256 `d6781375e18afc655c5f7f4337924f4f792830dadf5c8834e8bf980a426844b4`,
  `GIT_SHA = a2fde6c`, `JAR_BUILT = 2026-10-17T21:22:57Z`; sent to `aperv-tool` and `mini-experimento`. [verified]

- F7 against `a2fde6cc` (run by `aperv-tool`, its worktree commit `2d10b003`): jar sha256 and the `GIT_SHA`/`JAR_BUILT`
  stamps confirmed in `classes.dex`; `cells/` fixtures byte-identical to those tested; both strict xfails removed;
  migration with `APE_REPO` 52 passed; module 841 passed / 49 skipped with `APE_REPO`, 791 / 99 without. M.4, M.5 and
  S.1 closed. [reported]

## 12.1 / 12.2 (device smoke, `mini-experimento` short round `e03iggsmk`, 2026-10-17)

Jar `d6781375…44b4` / `a2fde6c`, mounted from the aperv-tool worktree `2d10b003`. Full report (PT):
`rvsec/rv-android/docs/20261017_e03iggsmk.md` (§5, §6). 56/56 admissible tasks (8 APKs × `ape` + six cells) plus the
retrowars extra; no jar exception. [reported]

**12.1.** `RUN_START` equals `cells/<cell>.run_start.json` in `preset`, `variant`, `features`, `params`, `inert` and
`digest` on all 48 cell runs (checked by `mini-experimento`'s smoke report and by `aperv-tool`'s `cell_gates`, S.3);
the retrowars run differs only by `ape.llmGateMinActions`, by construction. New `dec` members: `own` and `rot` on 100 %
of steps in every cell; `trig`/`navf` absent in `base`/`mop` and present in the `as*` cells (`as`: 198 / 105; absent
in spell4wiki `as_mop_llm`, see D1); `bmc` 0 in `base`/`mop`; `cfc` 0 in `base`; `buf` 0 in `base`; `inp`,
`restart`, `strat`, `draw`, `nav` in every cell. `RUN_END` sums: `base` coinMop = launches = coinLlm = bufAbandoned = 0;
`mop` coinMop 78, launches 88, stampHits 2014, retired 96; `as_mop` coinMop 84, launches 93; `as_llm` coinLlm 4045,
gateBreaker 1573, gateMinActions 2; `as_mop_llm` coinMop 32, launches 59, coinLlm 3631, gateBreaker 1193. S.4
isolation: no `mopReach` and no MOP fields in the controls; malformed lines 0. [reported]

**12.2.** retrowars, `as_mop_llm`, `ape.llmGateMinActions=0`, 321 steps, `RUN_END.reason = timeout`. (a) `dec.rot = 1`
on 298/298 LLM steps; the LLM screenshot is landscape and upright (the answer (695,66) lands on the top "Back").
(b) 182 `llm_tap`, 96 `back` (`tool:"back"`, matched), 20 `no_match` (all `dead_pair`). (c) the most-tapped px
(897,515) lies on "Play online" in the screencap; the second cluster (938,648) in the gap between button and label;
screencaps `rvsec/rv-android/data/e03igg_analysis/out/e03iggsmk/retrowars_screencap*.png`. Verdict on (c): the
author's. Trace: `rvsec/rv-android/data/results/e03iggsmk_08/…/com.serwylo.retrowars_70.apk/`. [reported]

**12.2 (c), verdict: the rotation is correct (author, 2026-10-18).** Evidence gathered offline from the retrowars
run (trace `…/com.serwylo.retrowars_70.apk__1__900__aperv:as_mop_llm.trace.ndjson.gz` and its `.logcat`):
- [verified] All 202 LLM taps carry `dec.rot = 1` and map the answer as `px = qwen × (1793, 1080) / 1000`, i.e. on a
  1794 × 1080 landscape display (a portrait frame would scale by ≈ 1080 in x and ≈ 1794 in y).
- [verified] Joining `RVSEC-COV` (first execution of each app method per process) to the steps by the `ApeRvHb`
  heartbeat, every screen transition after launch follows an LLM tap and fires the handler of a button on the
  current screen: step 3 (897,516) → `MainMenuScreen.showGameSelectMenu`; step 4 (954,648) →
  `GameSelectScreen.onGameSelected`; step 19 (949,648) → `showMultiplayerLobby`; step 72 (925,648) →
  `MissileCommandGameScreen.<init>`; step 144 (929,628) → `MultiplayerLobbyScreen.findAndShowPublicServers` ("Play
  online"); after the restart, steps 258/279 repeat the lobby and "Play online"; steps 306–307 → `showGameSelectMenu`,
  `onGameSelected`. Taps in a transposed frame would not hit the right handler on five different screens.
- The earlier reading of the cluster at y ≈ 648 as "the gap between button and label" (above) came from one
  screencap of the MULTIPLAYER screen; those taps were on the main menu and the game-select screen, where they
  opened the lobby and selected a game. Limit: `RVSEC-COV` logs a method's first execution only, so no hit rate over
  all 182 taps is derived.
- Display width 1794 vs the 1920-px screencap [verified in code; crop semantics of `SurfaceControl.screenshot` per
  the platform API, not observed on device]: `AndroidDevice.getDisplayBounds` (`:137-147`) reads `Display.getSize()`,
  the app area without the navigation bar (1794 × 1080 in landscape); `ScreenshotCapture.captureViaSurfaceControl`
  (`:83-99`) captures `Rect(0, 0, 1794, 1080)` at 1794 × 1080, a 1:1 crop of the screen, and the mapper scales by the
  same 1794 × 1080. An image pixel is therefore the screen pixel the tap goes to: there is no offset. What the model
  does not see is the 126-px strip of the navigation bar's side, which an immersive full-screen app such as retrowars
  draws on; no tap can be aimed there. Known design (CLAUDE.md, `ape.llmBoundaryTopPct`: "the answer is mapped on the
  display, which excludes the navigation bar"); no change proposed.

**Defects raised by the round (for the author's decision; none is changed here).**
- D1, MOP launcher × null tree (spell4wiki, the three MOP cells): the launcher opens `RecordAudioActivity` without
  extras, the app NPEs, and the launcher picks it again (3/4 launches in `mop`, 7/10 in `as_mop`); 526 "Null info root
  node" follow and the run stops stepping at ≈ 465 s. [reported] Code [verified]: `MopLauncherStage.isEligible` keeps
  an activity eligible while it is *unvisited*, and an activity that crashes on launch is never visited and its targets
  are never interacted with, so it never retires and stays first in the distance order (`selectByDistance`,
  `:276-300`); the recovery `MonkeySourceApe.stopTopActivity` (`:1053-1077`) kills `getRunningAppProcesses().get(0)`,
  upstream APE code (blame `38377b4a`), not necessarily the app. The eligibility rule predates this change (blame
  `adc194f0`, llm-coordinate-single-base Part B, 2026-10-09).
- D2, MOP coin share: the coin is consistent (`coinMop = src mop = ch mop_coin`), but under D-B10 the ledger counts
  first interactions of any owner, so the coin wins ≈ 1 % of steps while a weighted widget is on screen in 28 %.
  [reported] Behaviour as designed; a change is the author's call.
- D3, LLM history after BACK: `StatefulAgent.fillLastHistoryResult` (`:2150-2166`) labels the step "new screen" when
  `_lastState` is null (106 prompts carry "back → new screen"); the dead-pair ban key is the exact pixel
  (`CoordinateMapper.banKey`, `:386`), which the model evades by moving 1 px per step (≈ 50 taps at y = 648).
  [reported; code paths verified]. Both predate this change.

## 13.1 Amendment evidence (`e03igg`, 2026-10-18)

Jar `d6781375…44b4` / `a2fde6c`. All figures are on `e03igg` `aperv:mop` (489 runs, 163 APKs × 3 × 300 s) unless
noted. Results: `rvsec/rv-android/data/results/e03igg_00…_11`. The analyses ran in earlier sessions' scratchpads and
were not re-run here.

**Launcher (D-B15).** [verified] 906 launches; 1133 firings found no eligible candidate; 212 runs launched nothing;
515 of the 906 launches were not followed by a `STATE` of the target activity. Visited-relaunch opportunity
[verified]: letting visited activities back in would give a candidate to 71 of the 1133 ineligible firings (0.15
extra launches per run) and make 27 of the 212 zero-launch runs launch. Eligible census: median 1 activity per APK,
59 of 163 APKs with none. Hence the launcher for visited activities was dropped.

**Null-tree recovery (D-B16).** [verified]
- Wrong-process kills by `stopTopActivity` (the IME `com.google.android.inputmethod.latin`, nexuslauncher) occur only
  in `aperv:mop`, in 7 runs (uhabits ×3, spell4wiki ×2, universalinstaller). In `aperv:base` the 2 kills hit the app.
- Runs that stall before 240 s occur only in `aperv:mop` (uhabits 172 s / 189 s, spell4wiki 206 s).
- Runs with more than 10 null roots: `ape` 2, `aperv:base` 6, `aperv:mop` 11.

**Isolation.** [verified] `aperv:base`: 489 runs with `features=[FUZZING]`, no MOP field in any step, no
`MOP_DATA`, no `ACT.mop`. `aperv:mop`: one feature set and one param set across its 489 runs.

**Retirement.** [verified] median 0 of 171 targets retired per run, mean 0.9 %; 308 of 489 runs retire nothing.
[reported, `mini-experimento` `retire/`] 721 retirements, all on `src=mop` steps; 59 % of the retired targets had
already been reached, 32 % never were. Batch retirement [verified on `mini-experimento`'s `retired.csv`]: 412
singleton steps (one target retired), 80 % of them reached before, a median 21 s earlier; steps retiring ≥ 5
targets hold 240 retirements and 170 of the 234 never-reached retired targets; owncloud retired 84 targets in one
step. Recorded as a known limit (author); no change.

**Ledger replay (D-B17, D-B19).** [verified] The coin's picks were on new keys in 2945 of 3219 cases. SATA's 8397
executions of a weighted action were all re-taps: 7349 of a key the coin had taken, 1048 of a key SATA had touched
while unweighted. MOP participation ≈ 11 % of steps (coin 2.8 %, launcher 0.8 %, SATA weighted 7.6 %).

**Re-tap yield (D-B19).** [reported, `mini-experimento`,
`rvsec/rv-android/data/e03igg_analysis/out/e03igg/retouch/README.md`] Share of steps yielding a new MOP-reachable
method, against unweighted steps of the same APK and minute: first touch 0.671 (3.0 × the paired base), 2nd touch
1.30 ×, 3rd 1.16 ×, 4th–5th 1.12 ×. Over all app methods, re-taps yield 0.86–0.98 × the base.

**States per busiest activity (D-B17, state scope dropped).** [verified] median abstract states in the busiest
activity of a run: Compose-only 22, View-only 15 (66 % of steps), mixed 17.

**Breaker (D-B18).** [reported, `rep-pack-e03`] `e03iggsmk` bitbanana: 17/17 captures failed, 15 trips; mensinator
had the LLM blocked ≈ 600 of 900 s. In E6 v2 the trips were nearly all screenshot failures, plus two parse trips.

## 13.11 Package and install (2026-10-18)

[verified] `mvn install -Drvsec_home=…/worktrees-aperv/isolate-guidance-groups` on `a4afd31b` (the amendment: 13.2
contracts `345006e3`, fronts F8 `362e7ef0`, F9 `6667f9ad`, F11 `a5a7519a`, F12 `799ad722`, F10 `7b0c1e52`,
integration `a4afd31b`): 1462 tests / 0 failures / 0 errors / 24 skipped; no golden moved; the only fixtures that
moved are the digests of `cells/{mop,as_mop,as_mop_llm}.run_start.json` (13.2).
- `target/ape-rv.jar` sha256 `ff0a306990794a98ad7e191c7663c968afdaf3ae7a0b6920c9bea052688e141f`, `GIT_SHA a4afd31`,
  `JAR_BUILT 2026-10-18T17:51:39Z`; byte-identical copy at
  `worktrees-aperv/isolate-guidance-groups/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`.
- The shared `rvsec/rv-android` module jar is not touched.

## 12.3 Jar recipients (2026-10-18)

Author decision: the amendment jar (`ff0a3069…141f`, `GIT_SHA a4afd31`) goes only to `mini-experimento` (sent at
13.11, also checked by `aperv-tool`); no Study 03 session receives it.

## 10.x rv-android sister change (2026-10-18)

[verified, read-only] The rv-android change `isolate-guidance-groups` (worktree
`worktrees-aperv/isolate-guidance-groups/rv-android`, HEAD `8b69458d`) has every group behind 10.1–10.6 ticked
(wave 0, T, D, R, A, M, S); only its Verification group C (C.1–C.6) is open, owned by `aperv-tool`. It maps the v3.3
contract (`edbd173d`), closed K.5 on the amendment jar (`6332cd54`), and adds the variants `mop_k2` and
`mop_nolaunch` (`9655cb64`, `8b69458d`).

## 15. `e03igg2` and the D-B19 revert (2026-10-19)

[reported, `mini-experimento`, `rvsec/rv-android/docs/20261019_e03igg2.md`] Campaign `e03igg2`: jar `a4afd31`
(`ff0a3069…141f`), 163 APKs × 5 arms × 2 reps × 300 s; 1630/1630 identities admissible. The `ape` arm is
`e03igg`'s.
- Misuses (`misuses_primary`, mean per task): `mop` 0.408 against `ape` 0.409, and `mop` in `e03igg` (`a2fde6c`)
  0.452. Per-APK `mop − ape` −0.001 [−0.042; +0.042]; in `e03igg` +0.043. M1 `mop`/`base` 0.970 [0.852; 1.104]
  (97.5 %); in `e03igg` 1.151 [1.009; 1.312]. `mop` drift against `e03igg` 0.903 [0.807; 1.01] (95 %), p = 0.073;
  `base` drift 1.07 [1.00; 1.14]. Coverage unchanged (`cov_mop` 36.7 → 36.2, `cov_method` 34.3 → 34.0, `cov_act`
  82.3).
- Mechanism (§6.6): SATA's lift on weighted actions 1.03 [0.97; 1.09] → 0.87 [0.82; 0.93] in every guided cell
  (`mop` 0.873, `mop_k2` 0.83, `mop_nolaunch` 0.868, `as_mop` 0.929). Coin lift 2.04 → 2.00, coin wins per task
  6.58 → 6.33.
- k = 2 (`mop_k2`): 0.448 misuses per task; `mop_k2`/`mop` 1.098 [0.987; 1.222] (97.5 %), p = 0.049; `mop_k2 − ape`
  +0.039 [+0.004; +0.081]; 11.1 coin wins per task.
- The amendment's fixes held in all tasks: D-B15 0 activities launched twice (75 tasks in `e03igg`); 0 foreign
  "Try to stop process" (6 tasks in `e03igg`); D-B16 0.018 relaunches per task; D-B17 0 coin picks of a key
  executed ≥ k times, 0 `dec.hnd` mismatches; D-B19 0 SATA steps with `dec.mop > 0` on a tried key.

Author decisions (2026-10-19): revert D-B19 (design D-B19); keep D-B17 with k = 2 as the `mop` default in
`aperv-tool`, not in the jar; keep D-B15; D-B16 and D-B18 unchanged; validate with one campaign on the reverted
jar against `e03igg`, no old-jar arm.

**15.4 Package and install.** [verified] `mvn clean install -Drvsec_home=…/worktrees-aperv/isolate-guidance-groups`
on `2cfa8ee2` (the revert): 1458 tests / 0 failures / 0 errors / 24 skipped; no golden or fixture moved.
- `target/ape-rv.jar` sha256 `97620f4a534ae43c5cf6283911e8298b51b4db9bc5eddb92afe8cfbcb9116000`, `GIT_SHA 2cfa8ee`,
  `JAR_BUILT 2026-10-19T12:53:22Z`; byte-identical copy at
  `worktrees-aperv/isolate-guidance-groups/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`.
- The shared `rvsec/rv-android` module jar is not touched.

## 14. Verification (2026-10-19)

**14.1 Lint and dead Config twins.** Author's go to run group 14 now, before the new mini-experiment.
- [verified] `/sdd-qa-lint-fix` did not run a linter: the project declares no `maven-checkstyle-plugin`, has no
  checkstyle configuration, and checkstyle is not installed; Java has no auto-fix in the skill. No file was
  changed by the lint step.
- [verified] Deleted `Config.activityTriggerStagnationStep`, `Config.activityTriggerMaxPerRun` and their clamp
  helpers `clampActivityTriggerStagnationStep` / `clampActivityTriggerMaxPerRun`. Their only readers were 8
  `ConfigTest` cases, deleted with them. The plan keeps owning both keys (`Feature.MOP_LAUNCHER`), and
  `RunSpec.clamp` keeps clamping them (`RunSpecResolveTest`: `0 → 50`, `-5 → 0`). The `ScenarioScript` javadoc
  now names `spec.mop().activityTriggerStagnationStep()`.
- [verified] `mvn clean package` on `6966079f`: 1450 tests / 0 failures / 0 errors / 24 skipped; no golden or
  fixture moved.
- `target/ape-rv.jar` sha256 `591a94a80eea9b77ab4d3c82e1df935118bab355c7e88d9852a4dbd9b413a9c6`, `GIT_SHA 6966079`,
  `JAR_BUILT 2026-10-19T22:29:29Z`; byte-identical copy at
  `worktrees-aperv/isolate-guidance-groups/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`
  (replacing `97620f4a…6000`). The shared `rvsec/rv-android` module jar is not touched.
- Exploration behaviour is unchanged against `97620f4a` (no runtime reader was removed). Sent to
  `mini-experimento`, `aperv-tool`, `rep-pack-e03` and `versao`.

**14.2 `/sdd-verify`.** [verified] `overall: pass` on `328de4d5`.
- tests: `mvn clean` then `mvn test`, 1450 run / 0 failures / 0 errors / 24 skipped.
- lint: skipped (checkstyle not installed; no project checkstyle configuration).
- complexity: `null` (the skill defines no Java complexity tool).

**14.3 `/sdd-code-reviewer`** (forked, over `git diff master...HEAD`, `src/main/java`; it read the decision path,
the stages, the SATA extension points, the scoring passes, the plan and the sink in depth). Verdict: request
changes, 1 critical, 4 warnings, 8 notes. [reported] BASE parity, telemetry purity and the no-value-abort rule hold
on what it read.
- **CR-01** [verified against `master` `eea2b1a9` and `6966079f`]: `BudgetStage` abandoned the buffer after the
  trivial-activity search had refilled it, so it dropped the path it planned, weakened the preempted edge through
  `refillBuffer`'s `clearBuffer`, and recorded `dec.buf:"budget"` / `bufAbandoned` on an empty pre-step buffer.
  ADDONS cells only. Author: fix it (2026-10-19) → design D-B20, group 16.
- Follow-ups, not fixed (author 2026-10-19, [reported], not re-verified):
  - WR-01: on `RandomAgent`/`ReplayAgent` with MOP mounted, the ledger and retirement count an action that the
    post-selection `ApeAgent.checkRestart` then replaces (no campaign cell runs these agents with MOP).
  - WR-02: `ApeAgent.restartIsAGuard()` is `this instanceof StageCollaborators`.
  - WR-03: `SataAgent` mounts its extension points lazily for the `Unsafe`-allocating test harnesses (fields not
    final, P1).
  - WR-04: the stage tests use fakes that model no buffer or ledger side effect; closed for the budget by 16.2.
  - Notes: a blank `ape.llmUrl` aborts as `missing_dependency` (plan error, D-B1); gate values `< 0` unmount the
    gate like 0; `ape.llmPercentageNoSubstrate` and its `Config` twin have no reader (pre-dates the change, P3);
    `DecisionSource` mixes casing; `MopTrivialPathPick` calls back into `SataAgent.navMopTiebreakLog`;
    `spec().has()` per tree node and `getRotation()` per step; `requireMopArm` still says "MOP arm"; a declining
    X3/X4 rung's `dec.nav` can stay on the record.

**16.2–16.3 Budget buffer fix (D-B20).** [verified] `mvn clean package` on `1ef8e99f`: 1452 tests / 0 failures /
0 errors / 24 skipped (−1 `BudgetStageTest` case on the stage's abandonment, +3 `BufferAbandonTest` cases on a
real agent buffer); no golden or fixture moved.
- `target/ape-rv.jar` sha256 `f48e9c8564408b48e63e5aaaaf522b7b839a6c34a8ac194c56bbea19aae039cf`, `GIT_SHA 1ef8e99`,
  `JAR_BUILT 2026-10-19T22:50:39Z`. **Not installed**: the aperv worktree
  `worktrees-aperv/isolate-guidance-groups` no longer exists (rvsec has only `modules` at `fd8cfd93`, after
  `aperv-tool` merged its side at `51962ef3`), so the `591a94a8…a9c6` copy of 14.1 is gone with it. The shared
  `rvsec/rv-android` module jar is not touched; where the jar goes now is the author's call.
- Behaviour moves only in the ADDONS cells, on budget steps. Sent to `mini-experimento`, `aperv-tool`,
  `rep-pack-e03` and `versao`.

**14.4 `/sdd-docs-sync`** on `080ee392`. [verified]
- `CLAUDE.md`: 594 backtick references. Every repository path, class and `Class.member` exists, and every `ape.*`
  key is owned in `src/main/java`. The unmatched ones are device or external paths, package names, stage names, or
  `ape.stepTelemetryEnabled`, which is documented as gone. The test count reads 1452. No drift.
- `openspec/specs`: no main spec or delta names `Config.activityTriggerMaxPerRun`, `clampActivityTrigger*`,
  `getMopLedger` or `abandonBuffer("budget")`. `Config.activityTriggerStagnationStep` remains at
  `parity-oracle:186` and `:305`. Both lines sit in requirements that the delta MODIFIES ("Per-Preset Golden
  Decision-Sequence Capture", "Preemption-Order Golden"), so the archive replaces them.
- No ape text cites `INV-CAN-21/22`, which `aperv-tool` renumbered to 27/28.
- The V57 hand-sync of the Purpose, Data Contracts and Invariants preambles is an archive step and is still open.

**14.5 Validate and `/opsx:verify`** on `080ee392`. `openspec validate isolate-guidance-groups --strict` is valid.
Tasks are 169/171 done; the two open ones are 14.5 itself and 14.6 (the merge, author's word). A read-only agent
mapped the 142 requirements of the 21 deltas. Every requirement has implementation evidence, and every REMOVED
item is gone from `src/main/java`. D-B7, D-B8, D-B9, D-B14.10/.12/.13, D-B15–D-B18, the D-B19 revert and D-B20
hold [reported]. Nothing is critical.

Warnings, open for the author:
- [verified] **W1.** A `Budget` step never writes `dec.nav`. `SataAgent.writeTrace` runs only from
  `logActionSelected` (the `SataChain` path), and `decide` replaces the trace on the next step. A budget X4 tie
  therefore draws from the helper stream with no `dec.nav` (action-selection: "SHALL carry `dec.nav`"). This
  predates D-B20.
- [verified] **W2.** `coinLlm`: event-sink says to count a won coin even after an earlier LLM stage spent the
  step's call. `LlmCoinStage.decide` returns before `count(COIN_LLM)` when `llm.spent()`, and
  `LlmGateTest#aFailedNewStateCallIsTheStepsOnlyLlmCall` asserts 0. `design.md` ("steps on which each coin was
  won") supports the code.
- [verified] **W3.** `Feature.WTG` is `Rule.NONZERO`, while mop-guidance says POSITIVE. A negative
  `ape.mopWeightWtg` mounts WTG with a negative boost.
- [reported] **W4.** Dead `Config` twins of plan-owned keys, still asserted by tests:
  - `activityStableRestartThreshold`
  - `activityBaseBudget`, `activityBudgetPerWidget`
  - `dynamicEpsilon`
  - `componentPercentage`
  - `formCompletionEnabled`, `activityBudgetEnabled`
  - `llmPercentage`, `llmPercentageNoSubstrate`

  The llm-infrastructure delta still requires `Config.llmPercentage`, which contradicts llm-routing.
- [verified] **W5.** `PIPELINE` carries the scoring-pass census (`ScoringPipeline.candidates()`) but no
  stage-candidate census. decision-pipeline and event-sink require one (every candidate, with an assembled flag).
- [reported] **W6.** Untested scenarios:
  - `ape.llmPercentage` at 0.0 and −0.2 on an LLM plan;
  - typed input through containment (`dec.inp:"typed"`).
- [reported] **W7–W9 (low).**
  - Untested: artifact loading per cell, `getScrollType`, the EARLY_STAGE submit exclusion, and MODEL_MENU.
  - The launcher clamps in `RunSpec.clamp` are silent, while component-triggering says "logged".
- [reported] **Suggestions S1–S10 (wording, tests).**
  - INV-CT-18 is defined twice.
  - Stale javadoc and spec text after D-B19.
  - The fuzz toss is drawn after `generateEventsForAction`, not at step open.
  - The goldens' `trig` is a string.
  - `org.json` is in main code (`StaticArtifactLoader`), while `CLAUDE.md` says it is test-only.
  - `CleanControlTest` gaps.
  - No steps-per-minute measurement.

## 17. Verify 14.5 follow-ups (2026-10-19; design D-B21–D-B25)

The author opened group 17 with W1, W3, W5 and W4, plus the spec fix for W2.

**Scope of W4.** [verified] I counted 46 public `Config` fields that `src/main/java` never reads, neither
qualified nor through `import static`. All 46 keys are owned by the plan. A scratch worktree with the 46 deleted
compiled `src/main` with 0 errors. No code reflects over `Config`, and `RunSpec` reads the properties files itself
(`Monkey.java:940-981`). The author: delete everything unused, and retire `ape.llmPercentageNoSubstrate`.
`Config.printConfigurations` (`ApeAgent:556`, a `[APE]` log dump) no longer lists the deleted defaults. That
changes diagnostic text only.

**D-B22 generalised.** [verified] Switching `WTG` to POSITIVE alone made a negative weight abort: the inert rule
accepted an absent feature's key only at its neutral value. The author decided that a value the activation rule
reads as off is inert and does not abort. This applies to every POSITIVE feature (`RunSpec.applyInertRule`,
`Feature.readsAsOff`).

**17.1–17.5 results.** [verified]
- Tests: `mvn clean test` gives 1453 tests / 0 failures / 0 errors / 24 skipped.
  - added: `StageCensusTest` (1), `BufferAbandonTest` (D-B21, 1), `RunSpecResolveTest` (2 WTG cases and
    `aValueItsRuleReadsAsOffIsInert`), and one `ConfigTest` retired-key test;
  - removed: 5 `ConfigTest` cases on the retired key and its clamp.
- Fixtures: no decision golden moved. Only `cells/as_llm.run_start.json` (`digest` bef607f8e6cabcc1 →
  6eacfa54458fe39e) and `cells/as_mop_llm.run_start.json` (70f54d8586ba2774 → de56d42a05f52b1c) moved. Their
  echoed `params` are identical. The digest covers the resolved plan, and the retired key's default left it.
- Specs: `openspec validate --strict` is valid.
  - New MODIFIED requirements: exploration "Per-Step Debug Artifact Defaults", llm-routing "Coordinate-to-ModelAction
    Mapping", ui-coverage "Coverage Boost in Action Scoring — Per-Action" and "Config Flag for Coverage Boost".
  - New REMOVED requirement: llm-routing "Config — llmPercentageNoSubstrate seam".
  - The deltas still name a deleted field only to prohibit or to recall it ("No site SHALL read …", "formerly …").
- Archive hand-sync (V57, in addition to 14.4's list): main-spec preamble citations of deleted fields remain at
  - component-triggering `:7`, `:18`, `:36`;
  - exploration `:1068-1069`;
  - llm-infrastructure `:17-22`;
  - llm-routing `:7`, `:9`, `:11`, `:49`, `:56-57`;
  - mop-guidance `:876`;
  - parity-oracle `:57`;
  - wtg-navigation `:49`.
- Base cell: no decision moves. `PIPELINE` gains `stageCandidates` in every cell (telemetry only).
- Caveat: a properties file that states `ape.llmPercentageNoSubstrate` now aborts with `retired_key` in any cell.
  [inferred] No current `aperv-tool` cell sends it: `tool.py:255` only maps a name, and the only `-1` occurrences
  are in `docs/gh95-migration-record`.

**17.6 Package.** [verified] `mvn clean package` on `37356827`: 1453 tests / 0 failures / 0 errors / 24 skipped.
`target/ape-rv.jar` sha256 `d2febfe8a64aec68a24d6c9037f42d30767ab5d7e0921e26ea273d78117d9c5d`, `GIT_SHA 3735682`,
`JAR_BUILT 2026-10-19T23:36:45Z`. Not installed: the author said the Docker image builds its own jar. Sent to
`mini-experimento`, `aperv-tool` (with the `tool.py` mapping of the retired key to drop), `rep-pack-e03` and
`versao`.

**14.6 Merge.** Author's word (2026-10-19): "depois faça o merge aqui na master". Before the merge,
`rvsec` `3f5289f9` (local `modules`) dropped `aperv-tool`'s `llm_percentage_no_substrate` mapping, on the
author's word and with no change: 66 entries, the aperv spec follows, and aperv-tool's pytest gives 849 passed,
55 skipped, 0 failed. `isolate-guidance-groups` is merged into the local `master` with `--no-ff`. Not pushed.
