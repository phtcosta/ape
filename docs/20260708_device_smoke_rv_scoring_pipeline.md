# Device smoke — rv-scoring-pipeline (tasks 7.4 & 7.6)

Date: 2026-07-08 · Branch `mop-fairtest` @ `0976eec` · jar rebuilt `target/ape-rv.jar` (mvn package, Java 11 + d8).

## Environment

- AVD **RVSec** (Android 11 / API 30 x86_64 google_apis — the production default per
  `docker/android/Dockerfile`), headless (`-no-window -wipe-data -no-snapshot-save`), `emulator-5554`.
- **Runs as shell (uid 2000) — `adb root` is NOT required** (corrected). Production explicitly does
  *not* root: `rv-android-core/.../android.py:150` — "Phase 3 (`adb root` + `adb remount`) was
  intentionally removed (commit c0274def, gh50 §17)"; the emulator is `-read-only` and no runtime
  tool needs system writes. The `SecurityException while injecting event` seen on the very first
  attempt was the **fresh `-wipe-data` first-boot state** (device not yet settled / target not
  foregrounded), not a uid problem. Isolation test: after the device settled, `adb unroot` back to
  shell (uid 2000) and re-run → 649 events, 0 SecurityException, 60 `[APE-STEP]`. The rv-platform
  command is byte-identical (`aperv-tool/tool.py:_build_main_command`). **Harness note for gh74:**
  wait for the device to fully settle (boot + launcher idle) before the first APE injection.
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

## MOP boost inert on cryptoapp — REAL pre-existing bug (not substrate/timing)

All 60 MOP-arm steps report `mop=0 wtg=0 menu=0 form=0`, `mopReach=0`. This is **not** "substrate /
timing" (my first, lazy read). Root-caused to a **widget→MOP signature-join failure on D8-desugared
lambdas**, compounded by shallow exploration. This is a pre-existing defect in `MopData.load`
(untouched by the pipeline refactor).

**Bug (primary).** `MopData.deriveWidgetMopFlags` (`MopData.java:488`) joins a widget's
`listeners[].handler` to reachability by **exact string**: `bySignature.get(l.handler)`. The producer
per-listener override (`handlerReachesTarget`, line 484) is null on every listener ("until C3 lands"),
so exact match is the only path. In `cryptoapp.apk.json` there are 7 widget handlers; only **2** match
a `reachesTarget:true` signature — exactly the runtime `flagged=2`:

| widget handler | matches reachability? |
|---|---|
| `CipherActivity$1: onClick` | ✓ |
| `MessageDigestActivity: generateHash` | ✓ |
| **`CryptographyActivity$$ExternalSyntheticLambda0: onClick`** (Execute button) | ✗ **DROPPED** |
| `MainActivity: showGenerated / showScreenCipher / showScreenMessageDigest` | ✗ (navigation) |

The Execute button triggers **all** the crypto (`executeOperation`→`encryptWithSecretKey`→`Cipher`…).
Reachability *does* flag it — but under its **pre-desugar** name
`CryptographyActivity: lambda$setupExecuteButton$0$CryptographyActivity(...)` (GATOR/Soot), while the
widget + transitions carry the **post-D8** name `$$ExternalSyntheticLambda0: onClick` (dexlib2). Same
lambda, two toolchains, two signatures → exact join can never match. So the single most
MOP-relevant widget in the app is silently unflagged. This bites **non-obfuscated** apps too — it is
D8 lambda desugaring, distinct from the R8-rename/package-filter join gap noted earlier.

**Compound (secondary).** The 2 handlers that *do* match live in `CipherActivity` /
`MessageDigestActivity`. In this run APE never left `CryptographyActivity` (61/61 steps —
`UICOV-ACT` shows only that activity), so it never exercised the 2 correctly-matched widgets either.
Both effects together → `mop=0` everywhere.

**Scope.** The join lives in `MopData` (consumer) but the real fix is producer-side: emit per-listener
`handlerReachesTarget`/`directlyReachesTarget` (the gh60-C3 path) resolving the lambda mapping where
the desugaring info exists, OR reconcile the reachability signatures to the post-D8 names used by
widgets/transitions. This belongs to **change #2 (mop-reach-strategies)** / an rv-android producer
fix — **not** change #1. This smoke verified **pipeline composition + flag gating** (fully passed);
it also **surfaced** that MOP steering cannot fire on lambda-handler widgets until the join is fixed —
a hard prerequisite for any MOP arm in the gh74 experiment.
