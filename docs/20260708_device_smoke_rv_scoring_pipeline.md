# Device smoke — rv-scoring-pipeline (tasks 7.4 & 7.6)

Date: 2026-07-08 · Branch `mop-fairtest` @ `0976eec` · jar rebuilt `target/ape-rv.jar` (mvn package, Java 11 + d8).

## Environment

- AVD **RVSec** (Android 11 / API 30), headless (`-no-window -wipe-data -no-snapshot-save`), `emulator-5554`.
- **`adb root` required.** As shell (uid 2000) event injection fails on this image with
  `SecurityException while injecting event` after 1 event (cross-window inject denied), so APE
  never leaves the launcher. After `adb root` (adbd → uid 0), injection works: 500–660 events/run.
  This is a device-harness note, not a code issue — the rv-platform command is byte-identical
  (`aperv-tool/tool.py:_build_main_command`); the production emulator runs a root adbd.
- App under test: **`br.unb.cic.cryptoapp`** (instrumented APK from `results/e2e_resume`, whose
  `cryptoapp.apk.json` is byte-identical — md5 `e4f7d9af…` — to `test-apks/cryptoapp.apk.json`).
- Invocation per arm: `CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin
  com.android.commands.monkey.Monkey -p br.unb.cic.cryptoapp --running-minutes 1 --ape sata`,
  arm selected by `/data/local/tmp/ape.properties` alone (single binary).

## 7.4 — startup `[APE-ARCH]` line

| Arm (`ape.properties`) | `[APE-ARCH] passes=[...]` |
|---|---|
| default (`ape.takeScreenshot=false`) | `passes=[CoveragePass, FormCompletionPass]` |
| `ape.apePureMode=true` | `passes=[]` + 27 `[APE-ARCH] apePureMode forced <key>=<value>` lines |

Exactly one `passes=[…]` line per run, in pipeline order. **PASS.**

## 7.6 — per-arm composition + OFF-direction checklist

| Arm | `passes=[...]` | `[APE-STEP]` | events | SecurityException |
|---|---|---|---|---|
| `ape_pure` | `[]` | 0 | 527 | 0 |
| `sata` | `[CoveragePass, FormCompletionPass]` | 68 | 658 | 0 |
| `MOP` (`ape.mopDataPath=…cryptoapp.apk.json`) | `[MopWidgetPass, MenuGatewayPass, WtgPass, FrontierPass, CoveragePass, FormCompletionPass]` | 60 | 659 | 0 |

The single binary composes each arm from properties alone — **confirmed**. MOP load line:
`[APE-MOP-DATA] status=loaded package=br.unb.cic.cryptoapp windows=5 widgets=30 flagged=2
transitions=35` → `hasWtgData()` true, so Wtg+Frontier both enable (full 6-pass set).

OFF-direction checklist under `apePureMode=true` (the observations 6.3/6.5 deferred here past the
static-final wall):

- **(a)** `passes=[]` + per-flag `apePureMode forced` lines (weights/caps→0,
  `activityStableRestartThreshold=2147483647`, `mopDataPath`/`llmUrl`=`<unset>`). ✓ observed
- **(b)** no `MODEL_MENU` — action-type histogram `MODEL_MENU` = 0; never selected in 527 events. ✓ observed
- **(c)** fixed epsilon — `dynamicEpsilon` forced false (logged); strategy histogram `EPSILON_GREEDY`=0. ✓ via forcing
- **(d)** legacy `StringCache` input — `heuristicInput` + `fuzzInputTyped` forced false (logged). ✓ via forcing
- **(e)** zero `[APE-STEP]` (vs 68 in `sata` — the gate demonstrably works both directions). ✓ observed
- **(f)** upstream tree perception — `treeEnhancementsEnabled` forced false (logged). ✓ via forcing
- **(g)** no `ActivityBudgetTracker` — `activityBudgetEnabled` forced false (logged). ✓ via forcing
- **(h)** no form completion — `formCompletionEnabled` forced false + `FormCompletionPass` absent from `passes=[]`. ✓ observed
- **(i)** always-on exceptions still fire — run seeded (`seed 1783582729063`, RandomHelper from `-s`); `ApePinchOrZoom` guard unflagged/always-on. ✓ (seed observed)

**PASS** for composition + gating. Items (c)/(d)/(f)/(g) are verified at the kill-switch level
(each flag is demonstrably forced off, logged per-key; the flag's downstream effect is unit-tested
in JVM) rather than by re-observing the downstream behavior on device — the correct evidence level
for a forcing switch.

## Caveat — MOP boost inert on cryptoapp (not a pipeline defect)

All 60 MOP-arm steps report `mop=0 wtg=0 menu=0 form=0`, `decision_source` ∈ {Coverage, SATA},
`mopReach=0`. The MOP passes are **assembled but did not fire** during this run. Consistent with the
documented substrate/timing finding: MOP targets fire on lifecycle/background transitions, not the
widget clicks that dominate a 1-minute exploration, and the substrate is thin here (2 of 30 widgets
flagged). This smoke verifies **pipeline composition and flag gating**, not MOP steering efficacy —
that is the gh74 controlled experiment's job.
