# Evidence for the revision: opaque is not dynamic

The first implementation (`f828e5e6`) routed every opaque step to the LLM. The Study 03 replication
session measured it in campaign E5c; this file records what that measurement and the follow-up
investigation (2026-09-27) established, because the revised gate (design D8–D10) rests on it.

## E5c (jar `f828e5e6`, `mop_on_llm_70`, `ape.llmPercentageNoSubstrate` −1 vs 0.7, 600 s)

Source: `rvsec-study03-replication-package/openspec/changes/e5c-opaque-screen/report.md` and the
traces under `rv-android/data/results/e5c_0N/`.

- The mechanism acts: on the 7 Part A APKs the LLM reaches opaque steps (≈102 calls and 12
  `llm_tap` per APK per task, against 0 when off). Smoke: `off` digest `551f4904314c8c19` equals
  E5b's; retrowars `off` has 0 calls over 64 opaque steps.
- Coverage does not follow: Part A `cov_method` −0.07 pp [−2.61, +2.12]; the one clear gain is
  `com.serwylo.retrowars_70` (+4.84 pp).
- Where the opaque calls went (≈2,300 over the `on` tasks, labelled from the traces and the app
  sources): ≈34 % on the two LibGDX games (retrowars, shatteredpixeldungeon), the only
  dynamic-interactive screens. The rest on screens the LLM cannot act on: stuck non-cancelable
  progress dialogs (mtgfam DB update, smokingtracker LoadingDialog, urlchecker hosts download,
  squeezer, osmtracker, AnyMemo), a text-only About dialog (flyingcarpet, 145 calls), passportreader's
  `AdActivity` (804 calls), zxing camera previews (createpdf, deepr, paperwork — the preview takes no
  touch), and Compose splash/loading screens (myne, lunachron, owncloud, jerboa, cointrend, eduroam).
- On an opaque state `matched` can only be `MODEL_BACK` (the model has no MENU tool); `boundary`
  also absorbs `type_text` and other null answers, so its count over-states band rejections.
- Part B (ordinary APKs, −1.93 pp `cov_method`, −3.50 pp `cov_mop`) is mostly noise plus a
  run-order confound, not a mechanism of the flag: seeds are unpaired; every container ran
  off r1, off r2, on r1, on r2 and coverage falls with position (+2.29, −0.36, −0.31, −1.63);
  within-arm SD is 5.9 pp per task over 2 reps; the loss is larger in `on` tasks with ≤ 1 opaque call
  (−2.33) than with ≥ 5 (−0.75); the biggest losers had 0–4 opaque calls; restarts (1 vs 1) and LLM
  latency (648 vs 643 ms) are unchanged. The one direct cost is on camera screens, where a tap leaves
  the preview 37 % of the time against 62 % for SATA's BACK.

## Device verification (emulator API 30, 1080×1920, APKs from `APKS_INSTRUMENTED_jca_android_dexlib2`)

Compressed dump (`uiautomator dump --compressed`, what APE sees: `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`
cleared at `MonkeySourceApe.java:185-189`) versus the real hierarchy (`dumpsys activity top`):

| Screen | Compressed tree | Window | Real class of the content |
|---|---|---|---|
| retrowars menu and in-game; Shattered PD title | FrameLayout → one leaf `android.view.View`, focusable, focused, not clickable, full-root bounds, no text/desc/id (menu and in-game dumps byte-identical) | base, full | `com.badlogic.gdx.backends.android.surfaceview.GLSurfaceView20` |
| zxing capture (createpdf, deepr, paperwork) | FrameLayout → `zxing_status_view` TextView only | base, full | `SurfaceView`/`TextureView` + `ViewfinderView`, both absent (not important) |
| mtgfam, smokingtracker, urlchecker progress dialogs | `alertTitle`/message TextViews + `ProgressBar`, 5–6 nodes, none actionable | dialog, 16–32 % of screen | — |
| flyingcarpet About; osmtracker empty list dialog | `alertTitle` + TextView / empty ListView | dialog, 17–85 % | — |
| myne (Compose) splash | FrameLayout → `ComposeView` → chain of full-screen `android.view.View`, none focusable | base, full | `AndroidComposeView` |

- `SurfaceView`, `GLSurfaceView` and `TextureView` do not override `getAccessibilityClassName`
  (javap on `framework/classes-full-debug.jar`): when present they report `android.view.View`; a
  surface with no listener and no focusability is absent from the compressed tree.
- A class-name list (`SurfaceView`, `GLSurfaceView`, …) therefore matches nothing APE sees.
- Setting `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` would roughly double the node count on ordinary screens
  (median ≈ 2×, almost all layout containers) with the same actionable count; it is not adopted.
- Captures (screenshots, both dumps, `dumpsys`) are kept in the session scratchpad; the compressed
  dumps used as test fixtures are copied into `src/test/resources` by task 9.1.

## Dynamic content beside widgets (dataset survey, 348 repos)

Dynamic content usually shares the screen with widgets — camera with toolbar and FAB, maps with FABs,
paint canvases with toolbars, video with controls — so those states have more than two actions and
the size rule already opens the gate; the off-tree tap already exists there
(`llm-coordinate-tap`). Only full-screen engines (the LibGDX games) are opaque for the whole run. In
Compose apps ordinary semantics nodes also report `android.view.View`, so the class alone is not a
surface signal.

## Device validation of the revised gate (task 13, jar `b8e2e67b`)

Emulator API 30 (`sdk_gphone_x86_64`), APKs from `APKS_INSTRUMENTED_jca_android_dexlib2`, APE
standalone (`--ape sata`, 3 min, `pm clear` before each run, one run per cell). LLM: vLLM v0.29.0
serving `Qwen/Qwen3-VL-4B-Instruct-FP8` at revision `fefbb44c`, E5c's server line, published on host
port 30000. `on` = preset `llm` with E5c's LLM overrides (`llmModel`, `llmTemperature=0`,
`llmTopP=1.0`, `llmTopK=-1`, `llmSnapTolerancePx=150`, `llmPromptVariant=v13`,
`llmPercentage=0.7`) and `llmPercentageNoSubstrate=0.7`. `off` = exactly the golden's keys (preset
`llm`, `llmUrl`, `llmPercentageNoSubstrate=-1`), so its digest is comparable with
`golden-e93dea86.txt`. The `off` plan's `llmModel=default` is not served, so its non-opaque calls end
in `http_error`; that does not bear on the checks below.

| App (screen reached) | Arm | Steps | Opaque | `dyn` | Opaque ∧ `dyn` | LLM attempts | on opaque ∧ ¬`dyn` | on opaque ∧ `dyn` |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| retrowars (menu, in game) | off | 127 | 127 | 104 | 104 | 0 | 0 | 0 |
| retrowars | on | 81 | 81 | 78 | 78 | 53 | 0 | 53 |
| shatteredpixeldungeon (title) | off | 107 | 107 | 107 | 107 | 0 | 0 | 0 |
| shatteredpixeldungeon | on | 84 | 84 | 84 | 84 | 53 | 0 | 53 |
| mtgfam (DB-update dialog) | off | 248 | 139 | 0 | 0 | 6 | 0 | 0 |
| mtgfam | on | 270 | 260 | 1 | 1 | 10 | 0 | 1 |
| smokingtracker (main screens) | off | 224 | 30 | 0 | 0 | 8 | 0 | 0 |
| smokingtracker | on | 160 | 0 | 0 | 0 | 101 | 0 | 0 |
| createpdf (main screens) | off | 214 | 0 | 0 | 0 | 8 | 0 | 0 |
| createpdf | on | 185 | 0 | 5 | 0 | 105 | 0 | 0 |

- **13.2.** Every `off` run's `RUN_START.digest` is `a67b096e757d83ad`, the `llm` golden of
  `e93dea86`, and no `off` run made an LLM attempt on an opaque step.
- **13.1.** Every LLM attempt on an opaque step fell on a step carrying `dec.dyn:1`: 53/53 on
  retrowars, 53/53 on shatteredpixeldungeon, 1/1 on mtgfam. The DB-update dialog took 259 of its 260
  opaque `on` steps without a region, and no call. The games carry `dyn` on nearly every step; the
  retrowars steps without it are opaque steps on the same activity, whose tree had no qualifying
  leaf at capture time.
- **The one mtgfam `dyn` step** (`s=268`) is on the dialog's abstract state (`st=2`, `W=1`), the
  state that carried 259 steps without a region. One capture of that state's tree held a
  qualifying leaf; no per-step screenshot or XML was kept, so what was on screen is not known. Its
  one call was rejected (`boundary`). It is a false positive of the rule on a transient tree, 1 of
  260 steps.
- **createpdf** carries `dyn` on 5 non-opaque `MainActivity` steps: a surface beside widgets,
  recorded and not routed (D10).
- **Not reached in 3 min:** the smokingtracker loading dialog (Settings → Restore → Confirm) and
  createpdf's capture screen. APE stayed on ordinary screens in both. The rule's verdict on those
  screens is pinned by their real dumps in `DynamicRegionTest` (`smoking_loading`,
  `createpdf_capture`, `deepr_capture`, `paperwork_qrscan`), not by this run.
- **Taps.** retrowars: 15 `llm_tap`, 34 matched, 4 `dead_pair`. shatteredpixeldungeon: all 53
  answers were `click(500, 980)` or near it on the title screen (`px` y = 1758 of 1794 = 0.98 h,
  above `llmBoundaryBottomPct=0.94`), every one rejected as `boundary`: the declared limitation
  (D5b), here with the band and not a null answer as the cause (see `followups.md`). No
  `stale ephemeral edge` warnings; `restarts` 0 in every run.
- **What the band rejects on shatteredpixeldungeon** (investigated at rep-pack-e03's request,
  2026-09-27, no code change). A screenshot of the first-launch title screen (after `pm clear`,
  window 1080×1794, portrait) with the answered point marked puts `px (540, 1758)` on the
  "Enter the Dungeon" button, the screen's one way forward, which spans roughly y 1705–1840, all of
  it below the band line at 0.94 × 1794 = 1686. The model's answer is a real target, not a
  collapse to one coordinate; the band rejects the correct tap every time, and in the 3-minute
  `on` run the game never left this screen.

## Device check of the second revision (task 16.17, jar `700dbafd`, sha256 `ab878ecc…`)

Same emulator (API 30, 1080×1920, window 1080×1794 portrait), same APKs and helper as task 13,
3 min per run, vLLM `Qwen/Qwen3-VL-4B-Instruct-FP8` (the E5c server). `on` =
`ape.llmPercentage=0.7`, `ape.llmPercentageNoSubstrate=0.7` (digest `fe43b54476962c86`); `off` =
the golden keys (`ape.llmPercentageNoSubstrate=-1`).

| Run | steps | opaque ∧ `dyn` | LLM calls | `llm_tap` | matched | `dead_pair` | `boundary` | task 13 (`b8e2e67b`) |
|-----|------:|------:|------:|------:|------:|------:|------:|------|
| shatteredpixeldungeon `on` | 86 | 86 | 65 | 20 | 8 (`back`) | 37 | **0** | 53 calls, **53 `boundary`**, 0 `llm_tap` |
| retrowars `on` | 76 | 74 | 56 | 17 | 36 (`back`) | 3 | 0 | 53 calls: 15 `llm_tap`, 34 matched, 4 `dead_pair` |
| shatteredpixeldungeon `off` (`-1`) | 116 | 116 | 0 | – | – | – | – | digest `a67b096e757d83ad` |

- **Bands lifted.** Every call fell on an opaque step with `dec.dyn:1`; none was rejected as
  `boundary` (task 13: 53/53 on shatteredpixeldungeon). The "Enter the Dungeon" answers
  (`qwen (499–500, 978–980)`, `px y` 1754–1758) are now dispatched as `llm_tap` (15 of them); a
  manual `adb shell input tap 538 1758` on the same first-launch screen advances the game to
  "Choose Your Hero", so the answered point is the button and the tap acts. The abstract state does
  not change on the canvas (`acts 1`, `states 1`), so repeated answers at one coordinate still end
  in `dead_pair` after five strikes (37 on shatteredpixeldungeon: `(499, 980)` ×27, `(499, 500)` ×7,
  `(500, 980)` ×3) — the ban working as designed (design, Risks).
- **No zero-axis rejection occurred** (`degenerate` 0 in both `on` runs); the `back` answers
  (`(0, 0)`, matched to `MODEL_BACK`) take the `back` branch before the check.
- **`-1`.** `RUN_START.digest` equals the `llm` golden of `e93dea86`, and no LLM attempt was made
  on the 116 opaque steps: the gate stays closed at `-1`.
- **retrowars runs in landscape** (`ROTATION_90`, app frame 1794×1080): the `px`/`qwen` ratios
  (e.g. `qwen (564, 877)` → `px (1011, 947)`) show the mapping used the landscape frame
  `Display.getSize()` reports on each call. 36 of its 56 answers were `back`, each leaving the game
  for the menu; `restarts` is 0 in every run.

## Screenshot orientation in landscape (probe, 2026-09-27)

Asked after 16.17, because retrowars answered `back` 36 times in 56. A probe in the session
scratchpad (not in the repo) repeats `ScreenshotCapture.captureViaSurfaceControl` and
`AndroidDevice.getDisplayBounds` from `app_process`, with retrowars' menu in the foreground
(`ROTATION_90`, `Display.getSize()` 1794×1080, physical 1920×1080):

| rotation argument | image (1794×1080) |
|---|---|
| `0` (the jar's call) | the screen turned 90° and cropped: the title and buttons lie on their side, partly cut |
| `1` (the display's rotation) | the screen as `screencap` shows it |
| `3` | the screen upside down |

The coordinate mapping used the landscape frame all along (`qwen (564, 877)` → `px (1011, 947)` in
16.17); the image the model answered on did not. Fixed by D13.

**First device run of the fix (jar `f993081f`).** retrowars `on` died at step 1 with
`java.lang.NoSuchMethodError: No interface method getRotation()I in class
Landroid/view/IWindowManager` from `AndroidDevice.getRotation` inside the first capture
(`RUN_END.reason:"crash"`). The helper, never called before, used a window-manager method API 30
does not have; the probe had read `Display.getRotation()` instead. Task 18.6 moves the helper onto
that display.

