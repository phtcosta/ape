# Verification: llm-coordinate-single-base

Part A record, written as the tasks run. Baseline before any change (2026-10-08, HEAD `02d65651`,
after `mvn clean`): 1123 tests, 0 failures, 0 errors, 19 skipped. A first `mvn test` without
`clean` reported 1141 tests and 18 errors, all in `DynamicRegionTest`: a stale class left in
`target/test-classes` by the abandoned `llm-opaque-screen` branch, with no source in git.

## Task 2.6: test-side copies of the forward formula

| Site | Before | After |
|---|---|---|
| `PromptIntegrationTest` `allTuples_normalizedCoordsInRange`, `tuple001_cipherButtonNormalizedCoords` | re-implemented the formula with H = 1920 | rewritten to build the prompt from `GUITreeNode`/`ModelAction` fixtures of the parsed widgets (`PromptFixtures.click`) and to read the coordinates back from `build`'s output, at 1080 × 1794, the fixtures' dump size (root `[0,0][1080,1794]`). CIPHER `[0,336][1080,462]` now asserts the listed `@(500,222)`. The range test also asserts that every on-screen widget is listed with a coordinate |
| `CoordinateMapIntegrationTest` `toNormX`/`toNormY` | private copies "mirroring ApePromptBuilder.formatActionLine" | deleted; the six call pairs call `CoordinateNormalizer.toNormalized` |
| `CoordinateMapIntegrationTest` `statusBarNormY`, `navBarNormY` | inline copies | `CoordinateNormalizer.toNormalized(0, y, W, H)[1]`. The band constants and H = 1920 of these cases are task 4.6 |
| `SglangLiveTest` `toQwenX`/`toQwenY` | private copies, used to rebuild the prompt list | deleted; the list calls `CoordinateNormalizer.toNormalized`. `DEVICE_H` stays 1920: the live test sends the fixture screenshots, which are full 1080 × 1920 captures (`001.png`), so 1920 is the base of the image it shows. Its `toPixelX`/`toPixelY` copy the inverse, not the forward formula, and are out of this task |

`SglangLiveTest` compiles; its six live cases are skipped without `SGLANG_URL`.

## Tasks 3.5–3.7: the agent side of the history

`recordActionHistory` is reachable from a JVM fixture: `StatefulAgentActionHistoryTest` allocates a
`SataAgent` through `Unsafe` (no constructor runs, as in `StatefulAgentTearDownTest`) under a plan
with `ape.llmUrl`, so `RunContext.current().hasLlm()` is true. It covers the four `exploration`
recording scenarios (node click → bounds `[0,105][1080,252]`, a copy the node no longer affects; LLM
tap → `[600,900][601,901]`, long tap → `long_click`; menu → `menu` with null bounds; icon with only a
content-description → `ImageButton` / `"Settings"`), plus `back` and the ring buffer's eviction. The
JVM `Rect` stub has no `equals`, so bounds are compared edge by edge.

`updateStateInternal` is not reachable: it builds the new state from an `AccessibilityNodeInfo`
(`buildAndValidateNewState`). The fill of D12 is therefore its own method,
`StatefulAgent.fillLastHistoryResult()`, called from `updateStateInternal` right after
`_stateBeforeLast = _lastState; _lastState = currentState;` and before `resolveNewAction()`. The
three-way comparison moved into it from `recordActionHistory`; no copy remains there. The test
replays the two pointer moves `updateStateInternal` makes before the fill and the `currentState`
move of `moveForward`, and covers "each entry carries its own action's outcome" (read back from the
`v13` prompt built from the agent's buffer: `A1 → new screen`, `A2 → same`), "a step without a model
action does not shift an outcome", and the rewritten "Action history populated after action" (empty
at selection, `new screen` after the next update) and "Action returns to previous screen". The call
site's position inside `updateStateInternal` is checked by inspection here and by the device trace
check of task 8.2, which compares every entry's result with its own step's transition.

## Tasks 3.10–3.12: an action a restart replaces (review WR-01)

`checkRestart` is reachable from a JVM fixture: `StatefulAgentActionHistoryTest.RestartingAgent`
extends `SataAgent` and overrides only `getStartAction` (`ApeAgent.java:267-269` reads
`ape.randomlyPickMainApp()`). It is allocated through `Unsafe` like the other fixtures, with
`actionBuffer` set by hand (`startNewEpisode` → `resetTrace` → `clearBuffer` reads it), and `restart`
and `nextRestartThreshold` set by reflection. Four cases: the `exploration` scenarios "a model action
replaced by a restart leaves no entry" (RED first: 2 entries where 1 was expected, the ghost entry)
and "a restart that replaces no model action removes nothing", plus no restart → the entry stays,
and a model action whose recording failed → the filled entry of the previous action stays. The
test drives the `restart` branch of `ApeAgent.checkRestart`. The threshold branch returns through
the same `return` of `super.checkRestart`, so the override handles both: checked by inspection
(`ApeAgent.java:278-298`). `MonkeySourceApe`'s `startNewEpisode` calls (`:656`, `:999`, `:1163`,
`:1251`) do not go through `checkRestart` and are untouched (`git diff` shows neither `ApeAgent.java`
nor `MonkeySourceApe.java`).

## Task 8.6a: review notes

- NT-01: the `CoordinateNormalizer` class javadoc states INV-LLM-13's condition, dimensions below
  2000 px.
- NT-03: `ActionHistoryEntry` copies its `Rect` (null-safe), so `withResult` no longer shares it.
  `ApePromptBuilderTest.actionHistoryEntry_withResultCopiesEverythingElse` changed from
  `assertSame` to `assertNotSame` plus edges; new `actionHistoryEntry_copiesItsBounds`. The agent
  test that mutates the node after recording is kept.
- NT-04: `CoordinateNormalizer.MAX_NORMALIZED = 999`, used by `toNormalized`'s clamp and by
  `CoordinateMapper.inAnswerSpace`.
- NT-07: the `Config` J1b comment, the `CoordinateMapperOffTreeTapTest` bottom-of-display comment
  and the `KeyOwnershipTotalityTest.theTopBandsNeutralValueIsItsDefault` javadoc describe the
  current state. The `KeyOwnership.RETIRED` reason keeps naming the change, as that table does for
  every retired key.
- NT-10: `ApePromptBuilderElementListTest.elementLines` fails when no element-list header is
  found; `emptyListStillSaysSo` asserts one line.
- NT-11: `StatefulAgent.recordActionHistory` uses the imported `Rect` and `GUITreeNode`.

Full suite after 3.10–3.12 and 8.6a (`mvn clean test`): 1180 tests, 0 failures, 0 errors, 19
skipped (1175 + 4 restart cases + `actionHistoryEntry_copiesItsBounds`).

The 6.3 grep gate, re-run on the working tree: the `1000` arithmetic hits are the same three
(`StatefulAgent.java:714`, `:786`, `ImageProcessor.java:13`); `999` in `ape/llm/` and
`StatefulAgent` occurs only as `CoordinateNormalizer.MAX_NORMALIZED` and in two of its comments;
`getRootNode` in `ApePromptBuilder`: none; `llmBoundaryBottomPct`: unchanged; entry `bounds` reads:
the constructor's copy (`:113`) and `formatHistoryEntry` (`:547`); `centerX`/`centerY`: `:897`,
`visibleCentre`; the `recordActionHistory` body: still empty.

## Task 4.6: `CoordinateMapIntegrationTest`

The four band cases of section 7.1.2-C (`statusBarZone_rejected`, `navBarZone_rejected`,
`statusBarBoundaryExact_atThreshold`, `navBarBoundaryExact_atThreshold`) are deleted. They never
called `CoordinateMapper`: each recomputed the band from the test's own constants and asserted the
result, so they could not observe the mapper's band. `CoordinateMapperBoundaryTest` (task 4.1)
tests the band through `map` itself. The remaining fixture check,
`tuple001_allClickableWidgets_notInStatusOrNavBar`, becomes `..._belowTheStatusBarBand` at the
0.035 default. The class moves to the display base 1080 × 1794, which is the fixtures' own dump size
(root `[0,0][1080,1794]`), and its expected values follow: CIPHER `normY` 207 → 222 (round trip
397 → 398), GENERATE HASH 263 → 281 (comment), MESSAGE DIGEST 142 → 152, EXECUTE 767 → 821 (round
trip stays 1472). The class goes from 14 cases to 10.

`ape.llmBoundaryBottomPct` is set nowhere in rv-android (`git grep -n llmBoundary` over the
repository is empty, 2026-10-08), so retiring it aborts no harness-written plan.

## Task 6.2: INV-RTR-21 and the step-8 branch of INV-RTR-22, by inspection

`LlmEngine.selectAction` (`LlmEngine.java:87` after this change) declares `deviceWidth` and
`deviceHeight` once, at `:97-98`, from `screenshot.deviceDimensions(tree)`, and never reassigns them.
They are the only dimension values passed to `screenshot.capture` (`:109`), `promptBuilder.build`
(`:125`), `mapper.toPixels` (`:163`) and `mapper.map` (`:166-167`). `tree` has no other use in the
method. `nearest` takes the pixels `toPixels` produced, not dimensions.

Step 8: `pastTheImage` is true for a tool other than `back` whose `x` or `y` fails
`CoordinateMapper.inAnswerSpace`. Then `map` is not called and `match` is null; `toPixels` has
already run (`:163`), so the sub-event's `px` and `nearest` still get the clamped pixel. With `match`
null the ban is not consulted (`banned` stays false), so `classify` returns `no_match` with
`degenerate` for `(0,0)`, which is inside the answer space and never takes this branch, and
`boundary` otherwise. A `back` answer skips the check and reaches `map`, which returns the state's
back action.

## Task 6.3: grep gate

Run 2026-10-08 on the working tree.

- `1000` in `ape/llm/` and `StatefulAgent`, outside `CoordinateNormalizer`, as arithmetic
  (`git grep -nE "[*/] *1000|1000(\.0)? *[*/]"`): `StatefulAgent.java:714` and `:786`
  (`maxIdleTimeoutMs() / 1000`, milliseconds to seconds) and `ImageProcessor.java:13` (a comment). Every
  other `1000` hit is a `[0,1000)` string or comment, or `ImageProcessor.MAX_EDGE_PX`. No
  normalization.
- `getRootNode` in `ApePromptBuilder`: none.
- `llmBoundaryBottomPct` in `src/`: `KeyOwnership.java:278` (the `RETIRED` entry) and its tests
  (`KeyOwnershipTotalityTest:193`, `RunSpecAbortTest:86,88`).
- Reads of an entry's `bounds` in `ApePromptBuilder`: `:547`, inside `formatHistoryEntry`. The
  other two hits belong to `ActionHistoryEntry` itself (its constructor, `:113`, and `withResult`,
  which passes the field to the copy).
- `centerX`/`centerY` in `ApePromptBuilder`: `:897`, inside `visibleCentre`.
- In `StatefulAgent.recordActionHistory`: no `.getClassName(`, `.getText(`, `centerX`, `centerY`,
  `left +`, `top +`, `getDisplayBounds` or `1000` (`awk` over the method body, then `grep -nE`;
  empty). A plain `grep getText` matches `widgetText`, which is not a read.

## Group 7: regression floor and package

- 7.1: `mvn test -Dtest='ParityOracle*Test,PreemptionGoldenTest,GoldenFileTest'`: 30 tests, 0
  failures. `git status` and `git diff 5953c8a3 -- src/test/resources/goldens` are empty: no golden
  moved, as expected, since the scripted engine overrides `selectAction` and builds no prompt.
- 7.2: `mvn package` (d8 from build-tools 35.0.1) produces `target/ape-rv.jar` (293,984 bytes).
  The compiled `BuildInfo` in `target/ape-rv-classes.jar` carries `GIT_SHA=02d6565` and
  `JAR_BUILT=2026-10-08T18:33:52Z`, which `RunSpecEcho` puts in `RUN_START.build`. The time is this
  build's. The SHA is HEAD's, and the stamp has no dirty-tree marker, so until this change is
  committed a jar built from the working tree shares its SHA with a jar of `02d65651`, whose `src/`
  is that of `5953c8a3`. Only `JAR_BUILT` separates them. A jar meant to be traced to this change
  (the device smoke of 8.1, any campaign) should be rebuilt after the commit.

## Task 8.4: lint

No linter is configured for the module: `.sdd/sdd-config.yaml` says `linter: none` and the pom has
no checkstyle plugin. `/sdd-qa-lint-fix` fell back to checkstyle 8.39 with its built-in
`google_checks.xml` over the 31 `src/` files the change touches. It has no auto-fix mode, so no
file changed. On the changed lines: 1075 warnings, 0 errors; 960 are `Indentation` and
`CustomImportOrder`, the gap between Google style (2-space indent) and the project's (4-space). The
rest is style: braceless one-line `if`s, one-line `try`/`catch`, `WidgetKey`'s four fields declared
on one line, declaration distance, test-method naming, `Assert.*` imports. None points to a defect.
The one-line `catch (Exception ignored) {}` the lint flags in `recordActionHistory` is the idiom of
the surrounding code (four occurrences in `StatefulAgent` at HEAD). Nothing was changed for it.

## Task 8.1: device smoke (2026-10-08)

**Jar.** `target/ape-rv.jar` built after commit `624d1701` (sha256 `3e15e05d…c7bf9`, the same on the
device); `RUN_START.build` = `{"sha":"624d170","time":"2026-10-08T19:17:51Z"}`.

**Server.** vLLM v0.29.0, image `vllm/vllm-openai:v0.29.0@sha256:c2914767…`, serving
`Qwen/Qwen3-VL-4B-Instruct-FP8` at the pinned revision `fefbb44c…` with the argv of
`rvsec-study03-replication-package/experiments/E5b-inloop/config/servers/m1.sh` (`--served-model-name`,
`--revision`, `--port 30000`, `--max-model-len 8192`, `--max-num-seqs 64`,
`--enable-auto-tool-choice`, `--tool-call-parser hermes`); no `tool_choice` in the request. Two
differences, both forced by running outside the campaign compose: `docker run` with
`-p 30000:30000` (the emulator runs on the host and reaches the server at `10.0.2.2`), and
`HF_HUB_OFFLINE=1` (the pinned revision was already in the local cache). The compose path the task
cites (`experiments/E6-campaign/config/docker-compose.e6.yml`) does not exist in that repository;
`m1.sh` records the campaign argv instead.

**Device.** AVD `RVSec` (`system-images/android-30/google_apis/x86_64`, 420 dpi, physical
1080 × 1920), started by `scripts/run_emulator.sh`. All 179 calls resolve to the display
1080 × 1794 through `display_of`, the campaign AVD's base.

**Plan.** `ape.preset=llm`, `ape.llmPromptVariant=v13`, `ape.llmPercentage=0.7`, plus the campaign
arm's LLM-side values from `E5b-inloop/config/tool.py` (`mop_on_llm_70`, cell A): `llmTemperature=0`,
`llmTopP=1.0`, `llmTopK=-1`, `llmSnapTolerancePx=150`. `--running-minutes 5 --ape sata`.

**APK.** `test-apks/cryptoapp.apk` (package `br.unb.cic.cryptoapp`), by the author's choice instead
of `app.eduroam.geteduroam`. As copied into `test-apks/` (gitignored) it does not install on API 30 (`Failure [-124 …]
resources.arsc … uncompressed and aligned`), so a copy in the session scratchpad was re-zipped with
`resources.arsc` stored, `zipalign -p 4`'d and re-signed with a throwaway key. The app's code is
unchanged.

**Two environment findings, not caused by this change.**
- Run from the `shell` uid, the first event fails: `ActivityTaskManager: Permission Denial:
  package=null does not belong to uid=2000` → `** Error: SecurityException while injecting event`,
  because `MonkeyActivityEvent.injectEvent` calls `startActivityAsUser` with a null calling package
  and API 30 checks it. After `adb root` the run proceeds. CLAUDE.md's standalone recipe now says so.
- CLAUDE.md's standalone recipe named the package `com.example.cryptoapp`; the APK's is
  `br.unb.cic.cryptoapp`. Fixed there, with the re-alignment steps.

**Run.** `RUN_END`: `reason=timeout`, 305 steps, 3 activities, 16 states; LLM: 179 calls (169
`random`, 10 `new-state`), 92 matched, 1 `llm_tap`, 86 `no_match`/`dead_pair`, 0 timeout, 0 HTTP,
connection, parse, image or internal error, 0 breaker trips; 170 answers carry a
`repair` tag (for example `missing_y`).

## Task 8.2: trace check

Script `evidence/smoke_trace_check.py`, run on the smoke's trace
`evidence/smoke_cryptoapp_20261008.trace.gz` (output `evidence/smoke_trace_check.out`). It imports
`evidence/coordinate_counterfactual.py` for its parsing (`RE_STEP`, `RE_ACTION`,
`parse_action_rest`, `Act`), arithmetic (`to_normalized`, `contains`) and `display_of`, and
replaces only the alignment: each list line is read by indentation and paired with one group of
the step's listed target-requiring actions, grouped by (target `Name`, resolved bounds) in
first-appearance order; the line's class must equal the group's simple class. The visible centre
uses `Rect.intersect`'s edge test.

| Check | Smoke | E6 baseline |
|---|---|---|
| calls aligned to a listing | 179 / 179 | — |
| list lines whose `@(x,y)` = `toNormalized(visibleCentre(bounds, W, H))` with the call's display | 904 / 904 (302 lines collapse ≥ 2 actions) | — |
| lines of widgets disjoint from the display carrying a coordinate | 0 (no such widget listed) | 21,938 lines |
| unresolved nodes rendering `@(` | 0 (none listed) | — |
| repeated (Name, bounds) pair in one prompt; line starting with an ordinal | 0; 0 | 274,830 repeated lines |
| identical bounds with distinct `Name`s keeping a line each | not exercised (0 such pairs) | 4,633 lines |
| copied answers: tap y − visible-centre y, median per 300-px band | −2, −1, −1, −2 (bands 0–1199; 166 copies, all in [−2, −1]); every copy lands inside its widget | −12 to −105 px |
| `boundary` outcomes of `click`/`long_click` | 0 | 4,879 non-copies in y > 1686; 559 copies |
| `dead_pair` share of LLM decisions | 86 / 179 = 48.0 % | 40.7 % (cal_a1 27.5 %) |
| executed actions decided by the LLM | 93 / 305 = 30.5 % | — |
| off-tree taps within 2 px of an earlier one in the state; below y = 1686 | 0 of 1; 0 | — |
| history entries with a coordinate outside [0,999] | 0 of 879 | 2,860 entries |
| history entries whose result agrees with the entry's own step (D12) | 879 / 879 | — |
| class-less history entries at `@(0,0)` (D9) | 0 | 47,573 entries |
| `v13` system messages containing `unvisited` (D14) | 0 / 179 | all |
| history entries with a full package class (D13) | 0 of 806 node entries | all 555,981 |
| text-less entries whose list line shows a label (D13; `evidence/smoke_history_fields.py`, which runs `history_fields_scan.scan` with the list read without an ordinal and in the history's base) | 0 (194 show `id=`, P2; 6 no identifier; 133 unmatched: popup or widget gone) | 22,312 |

The D12 check takes each entry's step from the trace's model-action steps in order and compares the
result with that step's recorded transition: `out.target` against the step's own `st`
(`same`), against the previous step's `st` (`previous screen`), else `new screen`. `out.target` and
not the next record's `st`: in 3 steps they differ, because a refinement in the next update renames
the state after the fill has run. One step (288) selected a model action that the restart check
replaced (`Try to restart package … for start action`, no `out`): the next prompt shows 4 entries
and none for that action, which is the WR-01 repair on the device. Without excluding it the check
reported 9 shifted entries.

The dead-pair share is above E6's. The run is 5 minutes on one small APK whose `MessageDigestActivity`
took 166 of 179 calls, so repeated copies of the same few widgets reach five strikes quickly; the
design's risk "[The live gain depends on the dead-pair ban, …]" predicts a higher share under
correct copies, and this run is too small to size it.

**Not exercised by this APK:** landscape calls, widgets partly or wholly outside the display,
zero-area nodes, unresolved list nodes, container/child pairs with identical bounds, answers outside
[0,999], and AlertDialog states (the only overlay was the Spinner's dropdown popup, whose
`ListView`/`TextView` lines aligned and passed like the others).

## Part B, wave 1 (tasks 10.1–10.4, 11.1–11.3, 13.1–13.2), 2026-10-08

### Task 10.1: fixture sources

Every format-2 fixture is derived, never edited, by `evidence/derive_fixtures.py <rv-android root>`, which calls the gh122 library exactly as `tool.py` does (`digest_of` on the raw bytes, `derive`, `serialize_canonical`; rv-android `c7da2cec`, `GENERATOR_ID = "aperv-derive/2"`).

| Artifact | Derived from | `source.digest` |
|---|---|---|
| `test-apks/cryptoapp.apk.mop.json` | `test-apks/cryptoapp.apk.json`, now a copy of the gh120 document `rv-android/modules/rv-static-analysis/tests/resources/cryptoapp.apk.json` | `sha256:005d6a19…7213` |
| `src/test/resources/cryptoapp.apk.mop.json` | the same document; byte-identical to the one above | same |
| `src/test/resources/synthetic-activity-selection.mop.json` | `src/test/resources/synthetic-activity-selection.apk.json`, a minimal analyser document written for it (author, D-2) | `sha256:8bbb1c8a…fcc7` |

Before: the two cryptoapp copies differed from byte 1363; both were format 1 from the September document (`sha256:72f10adf…`, files `cryptoapp.apk.json` and `cryptoapp.apk.gh60-fresh.json`). The synthetic file was hand-written format 1. The derived synthetic artifact differs from it in one fact no derivation can avoid: a flagged OPTIONSMENU widget flags its activity, so `H` is in `mopActivities` (`[A,H]`, augmented `[A,B,H]`); the selection tests assert the derived sets, and their point (gateway `G` opens only under the augmented selection; `H` is the control) is unchanged. `B` reaches only through a non-constructor method (its `<init>` at d = 0 is excluded), so `activityDist.B = [[0,3]]`.

### Task 10.4: MOP parity goldens

`mvn test -Dtest='ParityOracleMopTest,ParityOracleLlmMopTest,PreemptionGoldenTest' -Dape.oracle.regenerate=true -Dape.oracle.capturedAt=f3122cee…` on the integrated wave: the diff of all three goldens is the header's `capturedAt` alone; every decision record is identical. The scenarios script their own widgets and read from the fixture only the activity list and the MOP-activity set, which name the same activities. The regeneration was reverted and the goldens kept (author, 2026-10-08); design Risks "[The parity goldens change twice]" amended.

### Task 11.3: the stamp capture

Surefire excludes the framework jar (`pom.xml` `classpathDependencyExcludes`), so `AccessibilityNodeInfo.getExtras()` inside `fillNode` is not reachable on the JVM. The bundle rules run on the JVM over a test-side `android.os.Bundle` stub (`GUITreeBuilderHandlerStampJvmTest`, 8 cases; a mutation that removes the `catch` fails one); the `@Ignore`d `GUITreeBuilderHandlerStampTest` states the scenarios on the Android runtime. Whether a stamped app's extras reach the explorer is decided by the device smoke 17.2 (`RUN_END.stampNodes > 0`).

### Wave 1 suite

`mvn clean test`: 1231 run, 0 failures, 0 errors, 24 skipped (baseline 1180 / 19; +15 loader, +13 stamp of which 5 `@Ignore`d, +23 run-spec).
