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

## Part B, wave 2 (group 12), 2026-10-08

- **Lookup** (`MopDataLookupTest`, 12): every "Handler Stamp Lookup" scenario; a listed class whose record lacks the event reads `none` with no pair and no aggregate fallback (a mutation that reads the fallback fails it).
- **Containment over nodes** (`MopScorerContainmentTest`, 13): walk order unchanged from `containmentShortIds` (node, two ancestors, each child then its children); a stamp on a parent or grandchild is found, one three levels up is not; under `flag` the largest flag weight wins, ties to the earlier node.
- **Behavior under `flag` with unstamped nodes is unchanged**: `MopScorerTest` keeps every asserted value (its calls go through the lookup); the MOP parity goldens (`ParityOracleMopTest`, `ParityOracleLlmMopTest`, `PreemptionGoldenTest`, `GoldenFileTest`) pass unchanged.
- **Grep gate 12.3**: `git grep -n 'getWidget(' -- src/main` → `ApeAgent.java:244` (typed input), `ApePromptBuilder.java:459` (`buildMopMarker`, group 15), `:480` (`buildWidgetMetadata`), `:600` (`buildExplorationContext`, group 15), `MopData.java` (definition and the id-key step of `lookup`). `containmentShortIds` has no hit.
- **Amended on the author's decisions**: API Design `MopScorer.score(MopContext, State, ModelAction)`; mop-guidance "MopScorer — Priority Boost" and "Parent/child widget granularity reconciliation", design D17: under `flag` the largest flag weight, which is the strongest flag whenever `mopWeightDirect >= mopWeightTransitive`; `ScoringParams` drops the two MOP-widget weights.
- Suite `mvn clean test`: 1261 / 0 / 0 / 24.

## Part B, wave 3 (tasks 13.3–13.7), 2026-10-09

- **Distance scoring** (`MopScorerDistanceTest`, 13): level weights, retired targets skipped, the tie split `max(W / n, W_next)` by level, `mopWeight` undivided beside the split `mopBoost`. One mapping (`MopScorer.distanceWeight`) and one split (`MopScorer.splitTies`).
- **Retirement** (`MopRetirementTest`, 11; `StatefulAgentMopRetirementTest`, 9): `moveForward` is reachable from the JVM, so the agent-level scenarios run there: first interaction counts and the second does not; novelty per activity and per action type; a containment-only weight retires its own targets; the retiring step's indices reach `EventSink.mopRetired`; an off-tree LLM tap, `flag`, and no artifact count nothing; a first interaction whose weight is 0 counts nothing.
- **Decisions of 2026-10-09**: only a weighted action retires anything (spec "Target Retirement" + new scenario, D19); `ape.mopRetireAfter <= 0` is documented (every target reads as retired, the run explores unguided), never rejected.
- **Known edge cases, recorded as they are**: with level weights equal or inverted by a plan (e.g. `mopWeightD1 == mopWeightD2`) ties are by weight value, so such levels merge into one tie set; at the d = 3 level the floor is 0, so more than 300 tied actions get a split boost of 0 while `mopWeight` stays 300.
- Suite `mvn clean test`: 1294 / 0 / 0 / 24; MOP parity goldens unchanged.

## Part B, wave 4 (groups 14–16), 2026-10-09

- **Launcher** (`MopLauncherStageTest`, +7) and **shortcut** (`SataAgentMopShortCircuitTest`, +10): D20 order and rotation, D21 ranking; `flag` launcher identical to the round-robin cursor by cursor.
- **Marker** (`ApePromptBuilderMopMarkerTest`, 17): one predicate over `MopScorer.weigh` for the marker and the `N/M MOP.` count. `PromptIntegrationTest` 7.1.1-E reads the gh60 full document and never called the builder; it is kept as facts of that analysis input, with its header reworded (author, 2026-10-09).
- **Telemetry** (`NdjsonSinkTest` +10, `StatefulAgentMopTelemetryTest` 11): `dec.mopd`/`dec.mops` from one `weigh` at the decision call, `dec.ret` through `EventSink.mopRetired`, RUN_END `stampNodes`/`stampHits`/`retired`. `stampNodes` is counted in `StatefulAgent.buildState`, which every capture passes through; that call needs `AccessibilityNodeInfo` and is not JVM-reachable, so 17.2 is its gate. Example records (jshell against `target/classes`):

```
{"s":120,"t":5321,"act":0,"st":0,"dec":{"a":"model=CLICK@[0,0][1080,200]","src":"MOP","ch":"short_circuit_unvisited","pri":404,"mop":400,"mopx":[3,12],"mopd":2,"mops":"s","ret":[4,11],"patched":0,"cf":{"changed":0}},"out":{"new_state":true,"target":1}}
{"type":"RUN_END","reason":"timeout","steps":2,"t_first_step":5321,"t_last_step":5400,"counters":{"acts":1,"states":2,"stampNodes":57,"stampHits":2,"retired":2}}
```

- **Amended (author, 2026-10-09)**: event-sink `mopd` is present when the undivided weight is positive; `mops` records the weight's lookup.
- **Grep gate 12.3, final**: `getWidget(` in `src/main` only at `ApeAgent.java:244` (typed input), `ApePromptBuilder.java:520` (`buildWidgetMetadata`) and `MopData.java` (definition, id-key step of `lookup`).
- **MOP parity goldens**: identical after the shortcut ordering (D21) and the marker (D22); no golden reaches a reordering case.
- Suite `mvn clean test` (integrated): 1349 / 0 / 0 / 24.

## Part B, wave 5 (tasks 17.1, 17.3, 17.4), 2026-10-09

- **17.1**: `-Dape.oracle.regenerate=true` on `ParityOracleMopTest`, `ParityOracleLlmMopTest`, `PreemptionGoldenTest` at `adc194f0`: 0 decision lines differ from the goldens of 10.4 (only `capturedAt`); reverted and kept, as decided for 10.4. Under `flag` with the unstamped scripted fixtures, neither the shortcut ordering (D21) nor the marker (D22) reaches a golden decision.
- **Package**: `mvn package -DskipTests` → `target/ape-rv.jar` (303,720 bytes), `BuildInfo.GIT_SHA` `adc194f`.
- **17.4**: `openspec validate --strict` valid; `/sdd-verify` PASS: `mvn clean test` 1349 run, 0 failures, 0 errors, 24 skipped (18 `@Ignore`, 6 `Assume`); lint skipped (`.sdd/sdd-config.yaml` `linter: none`, `checkstyle` not on PATH); complexity: no tool for Java.

## Part B code review (2026-10-09)

`/sdd-code-reviewer` on `f3122cee..1888af7d -- src`: verdict COMMENT, 0 critical, 2 warnings, 9 notes. Both warnings were checked in the code before they were reported.

- **WR-01** (confirmed): retirement was counted in `moveForward`, inside `updateStateInternal`, before `ApeAgent.updateState` applies `checkRestart` (`ApeAgent.java:363`), so an action a restart replaced, which never runs, still counted. Author's decision: the count is pending until `checkRestart`; repaired in task 17.5 (artifacts amended: INV-MOP-37, "Target Retirement" + scenario, event-sink `dec.ret`, design D19/D23/API/Data Flow).
- **WR-02** (confirmed): the stamp strings bypassed `StringCache`. Repaired in 17.6. The reviewer's suggested `StringCache.cacheString` throws on null; `cacheStringNullable` composes it.
- **Notes**: NT-04, NT-05, NT-07, NT-09 applied; NT-01, NT-02, NT-03 documented; NT-06 (event normalization repeated per lookup) and NT-08 (stamp in the XML dump) not applied.
- Suite `mvn clean test`: 1351 / 0 / 0 / 24.

## Format-2 gaps, group 18 (tasks 18.2–18.4, 18.7), 2026-10-09

- **Scenarios against the code**: the amended "Handler Stamp Lookup", loader, "Event-Type-Aware" and "Target Retirement" scenarios match the code paths they name (`MopData.lookup` step 2, `parseCompactWidget`, `MopRetirement.onFirstInteraction`); no artifact change was needed.
- **18.2, click aggregate**: `MopData.Widget.clickAggregate()` built at load through `mergeByMinimum`, read by `lookup` for a click when the widget's `mop` map has no `click` key. `Widget.dist(event)` keeps its meaning; its only `src/main` caller is `lookup` (`git grep -n "\.dist(" -- src/main`). A stamp reads its record's event alone. RED: 5 failures (`pair count expected:<1> but was:<0>`, `click under distance expected:<400> but was:<0>`, `expected:<[[9,1],[4,3]]> but was:<[]>`).
- **18.3, retirement at `d*`**: `onFirstInteraction` counts only the pairs at `nearest(this, dist, Integer.MAX_VALUE)`. RED: 3 failures (`array lengths differed, expected.length=1 actual.length=3`; all-retired `expected:<1> but was:<2>`; agent-level target 4 at `d = 3`). Three test fixtures that put a counted pair at a second distance moved to one distance (tasks.md 18.3); one spec scenario (event-sink "retirement recorded on the step that caused it") states no distance and is unchanged.
- **18.4**: comments only.
- **Suite** `mvn clean test`: 1361 run, 0 failures, 0 errors, 24 skipped (1351 + 10). MOP set (`MopData*`, `MopScorer*`, `MopRetirementTest`, `StatefulAgentMop*`, `MopWidgetPassTest`, `MopLauncherStageTest`, `SataAgentMop*`, `ApePromptBuilderMop*`): 206 / 0 / 0 / 0. Parity floor (`ParityOracle*`, `PreemptionGoldenTest`, `GoldenFileTest`): 30 / 0 / 0 / 0, goldens unchanged (they score under `flag`, which neither change reaches).
- **Open** at the time: 18.5 (gh122's cut) and 18.6 (fixture re-derive) waited on rv-android; both closed below.

### Tasks 18.5 and 18.6: the cut lands, fixtures re-derived (2026-10-09)

- **18.5**: rv-android `cf012e98` (gh122; local, not pushed) cuts widget and handler `dist` lists at `d ≤ 3` (`DIST_WEIGHED_MAX`, `_cut_weighed`) and `activityDist` at K = 3 (`DIST_K`, `_cut_nearest`), and replaces `digest_of(bytes)` with `digest_of_file(path)` (same value). gh122 reports 777 passed in the aperv-tool suite with `APE_REPO` at `469269e9`; reported, not re-run here.
- **18.6**: `evidence/derive_fixtures.py` now calls `digest_of_file(source)` (the Task 10.1 note above records the `c7da2cec` call). `python3 -I -B evidence/derive_fixtures.py <rv-android root>`: `source.digest` unchanged (cryptoapp `sha256:005d6a19…7213`, synthetic `sha256:8bbb1c8a…fcc7`); both cryptoapp copies byte-identical; synthetic fixture identical. Structural diff against `469269e9`: only `widgets[CipherActivity].btn_cipher_encrypt.dist` and `handlers["br.unb.cic.cryptoapp.cipher.CipherActivity$1"].dist` are removed (`click [[0,4],[1,4]]`, d = 4 beyond the cut); `executeButton` (`[[16,3],[17,3],[18,3]]`), `buttonGenerateHash` (`[[22,2]]`), the other handler and `activityDist` unchanged; `formatVersion` 2, `targets` 27.
- **Tests**: `MopDataTest.testCompactFixtureTargetsAndWidgetPairs` asserts no `click` pair on `btn_cipher_encrypt`; `MopScorerDistanceTest` already expected weight 0 there; `testCompactFixtureHandlerTable` compares handler with widget pairs (both empty now); `PromptIntegrationTest` reads the gh60 document.
- **Suite** `mvn clean test`: 1361 run, 0 failures, 0 errors, 24 skipped (no test added). MOP set: 206 / 0 / 0 / 0. Parity floor: 30 / 0 / 0 / 0, goldens unchanged.

## Task 17.2: device smoke (2026-10-09)

Jar `5904362` (`mvn package -DskipTests` after the 18.6 commit; `RUN_START.build.sha` in every trace). AVD `RVSec`, API 30. Checks by `evidence/smoke_17_2_check.py` (MOP_DATA against the artifact given, `RUN_END` counters, `dec` member census, MOP picks by widget, launcher sequence, the 17.2 checks of the run's mode); outputs `smoke_17_2_check_cryptoapp.out`, `smoke_17_2_check_password_monitor.out`.

### Standalone, cryptoapp (APK D-3, artifact of 18.6)

The stamped APK `rv-android/experimento-smk121/results/instrumented_apks/cryptoapp.apk`, re-aligned and re-signed in the scratchpad; `test-apks/cryptoapp.apk.mop.json` pushed as `/data/local/tmp/mop-artifact.json`; `ape.preset=mop`, `ape.activityTriggerEnabled=true`, 5 min each.

| Run | Trace | steps | `stampNodes` / `stampHits` | `mopd` | `mops` s / i | `ret` | `retired` |
|---|---|---|---|---|---|---|---|
| `distance` | `smoke_17_2_cryptoapp_distance.trace.gz` | 308 | 226 / 41 | 11 (d 3: 10, d 2: 1) | 41 / 99 | none | 0 |
| `flag` | `smoke_17_2_cryptoapp_flag.trace.gz` | 336 | 217 / 34 | none | 34 / 109 | none | 0 |
| `distance`, `ape.mopRetireAfter=1` | `smoke_17_2_cryptoapp_distance_retire1.trace.gz` | 294 | 260 / 35 | 2 | 35 / 84 | step 2 `[16,17,18]`, step 51 `[22]` | 4 |

- `MOP_DATA` in every run: `formatVersion` 2, `targets` 27, `handlers` 2, `droppedPairs` 0, digest `sha256:005d6a19…7213`.
- **Group 18 on the device**: `btn_cipher_encrypt` is picked through its stamp (`mops:"s"`, `CipherActivity$1`) with no weight under `distance` (its pairs at d = 4 are cut) and `mop` 300 under `flag` (the flag survives the cut). Under `distance` only `executeButton` (stamp `CryptographyActivity$$ExternalSyntheticLambda0`, d = 3, 300) and `buttonGenerateHash` (id key, d = 2, 400) weigh.
- **`dec.ret` at the default `ape.mopRetireAfter=3` is unreachable on cryptoapp**, not a matter of run length: a target counts once per first interaction of an (activity, widget, action type) (`StatefulAgent.pendingRetirement`, `hasActivityInteraction`), and each target here has one weighted action, so no counter passes 1. The third run (author's decision; a deviation from the task text) sets `ape.mopRetireAfter=1`: the first `executeButton` click retires `[16,17,18]`, the first `buttonGenerateHash` click `[22]`, and `executeButton` weighs 0 on its 11 later picks.
- **Launcher**: under `distance`, CryptographyActivity (d = 0) first, then MessageDigestActivity (d = 2, tied with CipherActivity, which was already visited); in the `retireAfter=1` run, with Cryptography visited, MessageDigestActivity and then, after target 22 retires, CipherActivity. Under `flag`, the manifest round-robin.

### Through rv-experiment, E6 corpus APK `com.password.monitor_102`

The author asked for a real run of the final-experiment instrumentation (Study 03 replication package, `data/raw/e6-corpus/`, dexlib2 + `--stamp-handlers`, image `rvandroid:0.9.5`; status reported by the `rep-pack-e03` session). The APK was chosen from a scan of the 87 corpus documents of at most 8 MB, derived in the scratchpad with gh122's `derive`: 71 targets, 53 widgets and 24 handlers with pairs, 3 MOP activities (the session's suggested `net.gaast.giggity_769` derives to 1 target and no pair).

- **Invocation**: rv-android's venv, `rv-experiment run --tools aperv:mopd_on_llm_off:mop_on_llm_off --skip-monitors --skip-instrument --skip-static --timeouts 300 --repetitions 1 --no-window --logcat-diagnostics`, through `evidence/smoke_17_2_run_aperv.py`, which replaces `ApeRVTool._resolve_jar_path` in-process: the module-local `ape-rv.jar` (2026-09-27) wins the resolver, knows no `ape.mopScoring`, and has no override (author's decision: nothing is written into rv-android). rv-android at `46110e85` (gh122 archived; the arms of `cf012e98`). The pipeline started and killed its own emulator, derived the artifact at run time (`sha256:62edb5f4…`) and pushed it; 2 tasks, 2 successful.
- `mopd_on_llm_off` (`distance`): 409 steps; `stampNodes` 2712, `stampHits` 155; `mopd` on all 121 weighted picks (d 0: 33, 1: 1, 2: 75, 3: 12); `mops` s 155 / i 81; `retired` 0; 2 launches (MultiPwdActivity, DetailsActivity). Methods 67.67 %, MOP methods 71.98 %.
- `mop_on_llm_off` (`flag`): 450 steps; `stampNodes` 3084, `stampHits` 167; no `mopd`, no `ret`; 7 launches, 6 of them DetailsActivity. Methods 66.95 %, MOP methods 70.88 %. One run per arm: no comparison is drawn.
- **The click aggregate on a real app**: under `distance`, `passwordText` and `multiPwdText` (only `entertext` keys, d = 0) and the switches (only `select`, d = 3) weigh on a click; their long-clicks and scrolls do not, while under `flag` they do (the flag aggregate of INV-MOP-14).
- **Why `retired` is 0** (`smoke_17_2_retire_bound.py`, output `smoke_17_2_retire_bound_password_monitor.out`): counting each distinct weighted (activity, widget, action type) by its widget's id-key pairs at `d*` gives at most 3 on targets 33, 35 and 38, through `theme`, `support` and `licenses`, which share the settings fragment's pairs. All three were picked through their stamps (`mops:"s"`), and the stamped handlers `SettingsFragment$$ExternalSyntheticLambda1`, `…Lambda2` and `…Lambda9` reach one target each (33, 38, 35); the stamp narrows the id key's reach as design D16 intends, so no counter passes 1. This is read from the artifact; the trace does not name the handler of a stamped pick.

## Task 9.2: archive and manual sync (2026-10-09)

`openspec archive llm-coordinate-single-base --yes`: 11 main specs updated (+6 requirements added, ~26 modified). Pre-archive main specs were snapshotted, and every requirement and scenario title of the touched specs is still present afterwards.

**Manual syncs** (the archive does not sync `## Invariants` and drops a delta's `## Purpose`):

- `llm-prompt`: INV-PRM-03 replaced by its amended text, INV-PRM-06 added; `## Data Contracts` (`build` takes `deviceWidth`/`deviceHeight` and no `GUITree`; `recentActions` entries carry pixel bounds and their own result); `## Purpose` (no `GUITree` input; one coordinate base per decision, visible centre).
- `llm-infrastructure`: INV-LLM-13 added; `## Purpose` and `## Data Contracts` (`CoordinateNormalizer` in both directions, forward output `int[] normalized`; dimensions from `ScreenshotStep.deviceDimensions`: display, then root node, then constants).
- `llm-routing`: INV-RTR-21 and INV-RTR-22 added. The archived `2026-07-23-llm-native-toolcall-repair` delta carries a dated note that INV-RTR-14's band-default clause is superseded by INV-RTR-22.
- `mop-guidance`: INV-MOP-19 (cut `d ≤ 3`) and INV-MOP-23 replaced; INV-MOP-34, INV-MOP-36 and INV-MOP-37 (only the targets at `d*` count) added; `## Purpose` rewritten for the format-2 artifact (it said "compact v1 artifact" and `mopRank`).
- `static-analysis-entrypoints`: INV-DRV-06 added in its amended form; `## Purpose` names format 2.
- `event-sink`: a `## Invariants` section created, holding INV-SNK-05 as amended. `ui-tree`: INV-TREE-14 and the stamp fields' `## Data Contracts` line. `component-triggering`: INV-CT-15. `action-selection`: INV-SEL-MOP-06, and the weights in `## Purpose` by mode. `run-spec`: INV-RUN-09, plus one `## Purpose` sentence (inertness inside a present feature).
- `exploration`: no edit; the synced "Action History Ring Buffer" and `llm-prompt` "Action History" agree on `bounds`, `menu` and the class and text helpers (D13).
- **This change's own stale text, corrected (author, 2026-10-09)**:
  - `static-analysis-entrypoints` scenario "widget distance is the minimum over its handlers, cut to three" now expects `[[2,1],[4,1],[7,2],[8,2]]` under `d ≤ 3`; it still expected the K = 3 count cut;
  - the cryptoapp scenario's parenthetical about rv-android `aperv` `:1309` was removed (gh122 corrected it);
  - `llm-routing` and `llm-prompt` name `MopContext mop` (the code's parameter since wave 4) where they said `MopData mopData`.
- `pending.md`: P1 implemented; P2 and P3 not confirmed, kept as candidates; P4 confirmed for a change of its own (author, 2026-10-09).

**Validation**: each of the 11 specs passes `openspec validate <spec> --type spec --strict`. `openspec validate --specs --strict` gives 22 passed and 1 failed: `scoring-pipeline`, whose `## Purpose` is still the archive placeholder. That spec failed before this archive, and the archive did not touch it.

### Known divergences at archive (recorded, not resolved; author: for a later spec-hygiene change)

Pointed out by task 9.2, left as they are:
- `llm-prompt` "System Message", scenario "System message content": it requires every system message to explain `[DM]`/`[M]`, which neither `ape_current` nor `v13` does (design D14, "Not done here").
- `llm-routing` "Stagnation LLM Mode" (`spec.md:114`): it still shows `selectAction(..., "stagnation", step)`; the code passes no `step` (`LlmStagnationStage.java:111-112`, `LlmEngine.java:87-92`).
- `mop-guidance` "MopScorer — Event-Type-Aware Reachability Scoring" (`spec.md:425-426`): it maps `MODEL_INPUT` on a Spinner to `itemSelected` and `MODEL_SCROLL` to `scroll`; the code maps `MODEL_CLICK` on a Spinner to `itemSelected` and every `isScroll()` type to `scroll` (`MopScorer.java:425-438`, whose Javadoc and design D17 agree with the code).

Found during the sync; all predate this change:
- Invariants stated only in archived deltas: INV-SNK-01..04 and 06..13 (`2026-08-07-rearch-04-step-ndjson-telemetry`; the event-sink requirements cite 01, 06, 07, 09, 10, 13), INV-TREE-13 (`2026-08-07-rearch-06-memory-surgical`), and INV-MOP-35 (`2026-08-07-rearch-07-compact-static-artifact`, cited at `mop-guidance` `:37`, `:114`, `:620`).
- `mop-guidance` `## Data Contracts` → Input still describes the full JSON (`{"windows": [...], "reachability": [...]}`); INV-MOP-21 still names the `[APE-MOP-DATA]` line, and INV-MOP-25 the on-device dialog merge.
- `scoring-pipeline` `## Purpose` is the archive placeholder (the one `--specs --strict` failure).
- `exploration` table: `widgetText` "truncated to 50 chars"; the code stores the text whole and truncates it when rendering (`formatHistoryEntry`, `MAX_TEXT_LEN`).
- `llm-prompt` `## Purpose`, paragraph 3: an off-element LLM target "may return null … a future enhancement could inject a raw coordinate click"; the off-tree `MODEL_LLM_TAP` exists.
- `ScreenshotStep.deviceDimensions` Javadoc says a source that reports a non-positive edge is passed over; the code falls back to the root node only when the display call throws (code-side comment; the spec follows the code).
- rv-android `aperv` (gh122) and this repository's `static-analysis-entrypoints` agree on format 2's members, the generator id, the `d ≤ 3` and K = 3 cuts and the handler sources. Only rv-android states INV-DRV-10 (pair bounds), the explicit ban on a raw `distanceTargets` section in INV-DRV-06, and the rules for malformed `targetDistances` entries and the null-event `""` key.
