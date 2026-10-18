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
