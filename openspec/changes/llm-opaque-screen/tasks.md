## 0. Baseline capture (before any code change)

- [x] 0.1 On jar `e93dea86` (current `master`), record the plan `digest`, `features` and `params` resolved for the `llm` and `llm_mop` presets with `ape.llmUrl` set and `ape.llmPercentageNoSubstrate=-1`; store them as the golden for task 2.2
- [x] 0.2 Write the draw-sequence fixture for task 3.4: a step sequence mixing opaque (`[MODEL_BACK, MODEL_MENU]`), trivial non-opaque (`[MODEL_CLICK, MODEL_BACK]`), widget-rich and buffer-non-empty steps, with the expected pre-change draw positions for `LlmRandomStage` computed from the current gate

## 1. Opaque predicate and gate

- [x] 1.1 Add `LlmGate.isOpaque(State)` — true iff `state.getActions()` is non-empty and none has `requireTarget()` (INV-RTR-21)
- [x] 1.2 Replace `LlmGate.allows(StepContext)` with `allows(StepContext, boolean opaqueEnabled)` = `bufferEmpty && (size > 2 || (opaqueEnabled && isOpaque))`; delete the one-argument form (INV-RTR-22/23)
- [x] 1.3 Rewrite the `LlmGate.allows` javadoc: state the size rule as "two actions or fewer" (the current text says three), the opaque clause, and why the buffer conjunct stays
- [x] 1.4 Add `LlmGateTest`: truth table over buffer × size × opaque × flag, including `ape.modelMenuEnabled=false` one-button state (not opaque), a state with a non-actionable widget (opaque) and an empty action list (not opaque)
- [x] 1.5 Run `/sdd-test-run agent/pipeline`

## 2. Plan: remove the Config seam, prove the plan unchanged

- [x] 2.1 Delete `Config.llmPercentageNoSubstrate` and `Config.clampLlmPercentageNoSubstrate`, and the `ConfigTest` cases at `ConfigTest.java:164-172`; leave `Feature.java:182`, `KeyOwnership.java:222`, `RunSpec.java:267` and `Presets.java:126` untouched (the key stays an `LLM_RANDOM` sub-parameter — INV-RTR-25, design D3)
- [x] 2.2 `RunSpecResolveTest`: the `llm` and `llm_mop` presets with `-1` reproduce the 0.1 golden `digest`, `features` and `params`; the existing `-1`-inert and `0.5`-aborts cases (`:49-62`) pass unchanged
- [x] 2.3 Confirm `KeyOwnershipTotalityTest`, `FeatureDerivationTest` and `PresetsTest` pass unchanged
- [x] 2.4 Run `/sdd-test-run runtime`

## 3. Stages and assembly

- [x] 3.1 `LlmNewStateStage` and `LlmStagnationStage`: take `boolean opaqueEnabled` at construction and pass it to `LlmGate.allows`
- [x] 3.2 `LlmRandomStage`: take `double opaqueRate` at construction (`< 0` = feature absent); per step `rate = opaqueEnabled && isOpaque ? opaqueRate : percentage`; draw only when the gate allows and `rate > 0` (INV-RTR-24)
- [x] 3.3 `DecisionPipeline.fromSpec`: derive `opaqueRate = spec.has(LLM_RANDOM) ? spec.llm().dbl("ape.llmPercentageNoSubstrate") : -1` and `opaqueEnabled = opaqueRate >= 0`; inject the flag into the three LLM stages and the rate into `LlmRandom`; no stage reads `Config` (keep `StaticConfigReadGuardTest` green)
- [x] 3.4 `LlmRandomStageTest`: replace the INV-RTR-09 no-consumer guard (`:279-310`) with (a) feature-off draw sequence equal to the 0.2 fixture's expected positions, (b) opaque step uses the opaque rate, non-opaque step uses `llmPercentage`, (c) zero opaque rate draws nothing on opaque steps
- [x] 3.5 `LlmNewStateStageTest` / `LlmStagnationStageTest`: opaque first visit and opaque midpoint call the engine with the feature on and return `Continue` without calling it with the feature off; buffered navigation closes the gate on an opaque step
- [x] 3.6 `DecisionPipelineFromSpecTest`: rosters of the four presets unchanged; an `llm` plan with `0.5` assembles the same roster and hands the flag and rate to the stages
- [x] 3.7 `LlmTapResolutionTest` (or a new case in it): on an opaque state, a `click` answer at a point with no widget is accepted as a resolved `LlmTapAction`
- [x] 3.8 `CoordinateMapperOffTreeTapTest`: on an opaque state, a `click` at `pixelY = 0.97h` is rejected (`boundary`) and one at `0.5h` yields an `LlmTapAction` — pins the declared limitation
- [x] 3.9 Run `/sdd-test-run agent/pipeline`
- [x] 3.10 Run `/sdd-verify agent/pipeline`

## 4. Telemetry

- [x] 4.1 `EventSink.beginStep(..., boolean opaque)`: add the parameter to the interface, `NdjsonSink`, `NoopSink`; `StepRecord` writes `dec.opaque:1` when true and nothing when false, for model and non-model records alike (INV-SNK-15)
- [x] 4.2 `StatefulAgent.resolveNewAction` (`StatefulAgent.java:1567`): pass `LlmGate.isOpaque(newState)` to `beginStep` on every arm
- [x] 4.3 `EventSink.restartRequested()`: add to the interface and both sinks; `ApeAgent.requestRestart()` (`ApeAgent.java:318`) calls it; `NdjsonSink.runEnd` writes `counters.restarts` beside `acts`/`states`, including `0` (INV-SNK-16)
- [x] 4.4 `NdjsonSinkTest`: `opaque` present on an opaque step, absent otherwise, present on a non-model record from an opaque state; `restarts:3` after three calls; `restarts:0` written when none
- [x] 4.5 Confirm `SinkNeutralityTest` and the parity goldens pass unchanged
- [x] 4.6 Run `/sdd-test-run telemetry`

## 5. Documentation

- [x] 5.1 CLAUDE.md: describe `ape.llmPercentageNoSubstrate` (`LLM_RANDOM` sub-parameter; `-1` off, `0` opens the gate for new-state/stagnation only, `> 0` also the random rate on opaque steps; requires `llmPercentage > 0`; boundary bands still apply), mention `dec.opaque` and `RUN_END.counters.restarts` in the Telemetry section, and update the test count in Notes
- [x] 5.2 Check the javadocs of `LlmTapAction`, `CoordinateMapper.map` and the three stages describe the gate as it now is (no stale "more than two actions" rule)
- [x] 5.3 Record for rv-android (not done here): `openspec/specs/aperv/spec.md:760` stays an `LLM_RANDOM` sub-parameter; only its meaning changes (`-1` = opaque routing off, `>= 0` = on)

## 6. Device validation (first gate)

> 6.1–6.4 run in the Study 03 replication session (rep-pack-e03) on jar `f828e5e6` (sha256 `f94cebee…6bd8ae0bc`), handed off 2026-09-25. Results received 2026-09-27 (E5c smoke and campaign); recorded in `evidence.md`.

- [x] 6.1 `mvn package`; run `com.serwylo.retrowars_70.apk` under the `llm` preset with an SGLang server, same seed, 600 s, once with `ape.llmPercentageNoSubstrate=-1` and once with `0.7`
- [x] 6.2 With `-1`: `RUN_START.digest` equals the `e93dea86` digest for the same keys, `counters.llm.calls == 0`, every step record carries `dec.opaque:1` (smoke: digest `551f4904314c8c19` equal to E5b's, 0 calls, 64/64 opaque)
- [x] 6.3 With `0.7`: `counters.llm.calls > 0`, `llm_tap > 0`, taps dispatched (no `stale ephemeral edge` warnings beyond isolated ones), `restarts` recorded, and the share of opaque-step answers rejected with `reason:"boundary"`; compare host-side method coverage and violations between the two runs and record the numbers in the change directory (29 calls, 12 `llm_tap`, 0 warnings; coverage from E5c in `evidence.md`)
- [x] 6.4 Run one widget-rich APK (`test-apks/cryptoapp.apk`) with `-1` and confirm its action sequence matches a `e93dea86` run under the same seed and scripted conditions (or state why a device run cannot be deterministic and rely on 3.4 and the parity goldens) (not run on a device: cryptoapp is not in the mounted dataset; relies on the digest equality of 6.2, 3.4 and `RunSpecResolveTest.sentinelPlanDigestUnchanged`)

- [x] 6.5 Build the jar at a committed revision, record its sha256 and commit, and send both to the rep-pack-e03 session (it bind-mounts the jar for E5c)

## 7. Final verification (first gate)

- [x] 7.1 `mvn test` green; update the test count in CLAUDE.md if it changed
- [x] 7.2 Run `/sdd-qa-lint-fix src/main/java/com/android/commands/monkey/ape`
- [x] 7.3 Run `/sdd-verify src/main/java/com/android/commands/monkey/ape`
- [x] 7.4 Invoke `/sdd-code-reviewer` via Skill tool
- [x] 7.5 Run `/sdd-docs-sync src/main/java/com/android/commands/monkey/ape`

## 8. Revision: record the evidence

- [x] 8.1 Write `evidence.md` (E5c results, Part B confound, device dumps, dataset survey) and revise proposal, design (D8–D10) and the three delta specs (INV-RTR-23/24/26, INV-SNK-17)

## 9. Dynamic-region predicate (INV-RTR-26, design D8/D9)

- [x] 9.1 Copy the compressed dumps captured on the API 30 emulator into `src/test/resources/dynamic-region/` (retrowars menu and in-game, Shattered PD title, mtgfam and urlchecker progress dialogs, smokingtracker loading dialog, flyingcarpet About, osmtracker empty-list dialog, createpdf/deepr/paperwork capture, myne splash, and the ordinary screens `ord_*`); confirm `GUITreeBuilder`'s XML reader loads them (attributes `class`, `bounds`, `focusable`, `text`, `content-desc`), converting only what it does not read (the reader takes every attribute but `content-desc`; the test loader `tree/DumpTrees` reads nodes through `GUITreeBuilder.buildNodeFromXml` and copies `content-desc` afterwards — the production reader is unchanged)
- [x] 9.2 Add `LlmGate.hasDynamicRegion(GUITree)` with `DYNAMIC_REGION_MIN_AREA = 0.5`: one pass from the root, not descending into `androidx.compose.ui.platform.ComposeView` or `android.webkit.WebView` subtrees; a node qualifies when class is `android.view.View`, it has no children, text and content-desc are empty, it is focusable, clickable or long-clickable, and `area(bounds ∩ rootBounds) >= 0.5 × area(rootBounds)`; null tree or empty root bounds → `false`
- [x] 9.3 Add `LlmGate.isOpaqueDynamic(StepContext)` = `isOpaque(newState) && hasDynamicRegion(newGUITree)`, in that order
- [x] 9.4 `DynamicRegionTest` on the fixtures of 9.1: LibGDX dumps → `true`; every dialog, capture, splash and ordinary dump → `false`
- [x] 9.5 `DynamicRegionTest` on synthetic trees, clause by clause: area 0.49 → `false` and 0.5 → `true`; non-empty text; non-empty content-desc; not focusable/clickable/long-clickable; a child present; `ComposeView` ancestor; `WebView` ancestor; null tree; empty root bounds
- [x] 9.6 Run `/sdd-test-run agent/pipeline`

## 10. Gate and rate

- [x] 10.1 `LlmGate.allows(ctx, opaqueEnabled)` = `bufferEmpty && (size > 2 || (opaqueEnabled && isOpaqueDynamic(ctx)))`; the flag-off path still never walks the tree (INV-RTR-22)
- [x] 10.2 `LlmRandomStage`: `rate = opaqueEnabled && isOpaqueDynamic(ctx) ? opaqueRate : percentage`
- [x] 10.3 Update the javadocs of `LlmGate.allows`/`isOpaque`, the three stages and `DecisionPipeline.fromSpec` to state the opaque-dynamic rule (no text may say every opaque step is routed)
- [x] 10.4 `LlmGateTest`: extend the truth table with the region (opaque ∧ region opens with the flag on; opaque without region stays closed; flag off unchanged for every combination; buffer still closes)
- [x] 10.5 `LlmRandomStageTest`: opaque dynamic step uses the opaque rate; opaque step without region draws no coin; the feature-off draw sequence of 3.4 still matches the 0.2 fixture
- [x] 10.6 `LlmNewStateStageTest` / `LlmStagnationStageTest`: opaque dynamic first visit / midpoint call the engine with the flag on; opaque without region returns `Continue` without calling it; `FakeStepContext` gains a settable tree
- [x] 10.7 `RunSpecResolveTest.sentinelPlanDigestUnchanged` and `DecisionPipelineFromSpecTest` pass unchanged (no plan key added — D9) (the rosters and the digest pass unchanged; `modesOnAnOpaqueStep` now sets a canvas tree, since an opaque step without a region no longer opens the gate — the test probes the gate, not the plan)
- [x] 10.8 Run `/sdd-test-run agent/pipeline` and `/sdd-verify agent/pipeline`

## 11. Telemetry (INV-SNK-17, design D10)

- [x] 11.1 `EventSink.beginStep(..., boolean opaque, boolean dyn)` in the interface, `NdjsonSink` and `NoopSink`; `StepRecord` writes `dec.dyn:1` when true and nothing when false, for model and non-model records alike
- [x] 11.2 `StatefulAgent.resolveNewAction`: pass `LlmGate.hasDynamicRegion(newGUITree)` on every arm
- [x] 11.3 `OracleDriver` and `EventSink` javadoc follow the new signature; the oracle passes `false` (no tree)
- [x] 11.4 `NdjsonSinkTest`: `dyn` present when true, absent when false, independent of `opaque` (all four combinations)
- [x] 11.5 Confirm `SinkNeutralityTest` and the parity goldens pass unchanged
- [x] 11.6 Run `/sdd-test-run telemetry`

## 12. Documentation

- [x] 12.1 CLAUDE.md: `ape.llmPercentageNoSubstrate` routes opaque steps whose tree has a dynamic region (state the rule in one line), `dec.dyn` in the Telemetry section, test count in Notes
- [x] 12.2 Update `followups.md` item for rv-android (`aperv/spec.md:760`) if the wording changed during implementation

## 13. Device validation (revised gate)

- [x] 13.1 `mvn package`; on the emulator, run APE standalone with `ape.llmPercentageNoSubstrate=0.7` and an SGLang server for about 3 min each on retrowars, shatteredpixeldungeon, mtgfam (DB-update dialog), smokingtracker (loading dialog path) and one zxing app (createpdf capture); check from the trace that `dec.dyn:1` appears on the games' steps and not on the dialog, camera or splash steps, and that LLM calls on opaque steps happen only where `dec.dyn:1` (every opaque-step call fell on a `dec.dyn:1` step: retrowars 53/53, shatteredpixeldungeon 53/53, mtgfam 1/1; the DB-update dialog took 259/260 opaque steps without a region and no call; one transient `dyn` step on the dialog state; the smokingtracker loading dialog and createpdf capture were not reached in 3 min and rest on their dumps in `DynamicRegionTest`; numbers in `evidence.md`)
- [x] 13.2 Same runs with `-1`: 0 LLM calls on opaque steps, `RUN_START.digest` equal to the `e93dea86` golden (digest `a67b096e757d83ad` on all five, 0 opaque-step attempts)
- [ ] 13.3 After group 17, build the jar at a committed revision, record its sha256 and commit, and send both to the rep-pack-e03 session with the E5c follow-up on arm order (`followups.md`). (A provisional jar, `8125a651` / `d990abd5…`, was sent before group 14 and withdrawn as provisional; only the jar built after group 17 is final)

## 14. Final verification (revised gate)

Group 14 ran on `8125a651`. Its code review found the issues group 16 fixes; the docs-sync step and a repeat of 14.1–14.3 run in group 17, after the second revision.

- [x] 14.1 `mvn test` green; update the test count in CLAUDE.md if it changed
- [x] 14.2 Run `/sdd-qa-lint-fix src/main/java/com/android/commands/monkey/ape` (checkstyle is not installed and the pom has no checkstyle plugin; no file changed)
- [x] 14.3 Run `/sdd-verify src/main/java/com/android/commands/monkey/ape` (1181 tests green at `8125a651`, no `src` change since; lint skipped, checkstyle not installed)
- [x] 14.4 Invoke `/sdd-code-reviewer` via Skill tool (verdict COMMENT: 0 critical, 2 warnings, 7 notes; WR-01, WR-02, NT-01, NT-03, NT-04 and NT-06 are addressed in group 16; NT-02, NT-05 and NT-07 are accepted as they are)

## 16. Second revision: edge bands on opaque dynamic steps (D11, INV-RTR-27) and the review findings

- [x] 16.1 Revise proposal, design (D5b narrowed, D8 (d) native clickability and the Flutter note, D11, D12), the `llm-routing` delta (INV-RTR-26, INV-RTR-27, MODIFIED "Coordinate-to-ModelAction Mapping", the opaque-routing requirement and its scenarios), a new `llm-infrastructure` delta (MODIFIED "ToolCallParser — 3-Level Fallback Parser") and this file; `openspec validate llm-opaque-screen --strict` passes
- [x] 16.2 `LlmGate`: the input clause of `isRegion` reads `isFocusable() || (isClickable() && !isPatchedClickable()) || isLongClickable()` (WR-01); drop the `null` checks on `getBoundsInScreen()`, which never returns null (NT-01); the class javadoc names the tree classifier as the class's second role (NT-03); the `hasDynamicRegion` javadoc states the Flutter false positive (NT-04)
- [x] 16.3 `DumpTrees.load` applies `GUITreeBuilder.patchGUITree` after reading, as production does on every tree; its javadoc states what still differs from production (the XML reader instead of `AccessibilityNodeInfo`, and no text truncation) (WR-02)
- [x] 16.4 `DynamicRegionTest`: a clickable container holding a label-less `View` leaf covering the root, patched by `patchGUITree`, is `false`; the same leaf natively clickable is `true`; the 26 fixtures keep their verdicts
- [x] 16.5 `CoordinateMapper.map(..., boolean edgeBandsOff)`: when true, skip both bands and return null when `pixelX == 0 || pixelY == 0` (the parser's default for an unreadable coordinate); when false, exactly the current behavior; javadoc states the frame (`Display.getSize()`, the crop) and the rule
- [x] 16.6 `LlmEngine.selectAction(..., boolean edgeBandsOff)` passes the argument to `map` and to `classify`, which names the null `degenerate` when `edgeBandsOff` holds and either parsed coordinate is `0` (unchanged otherwise); javadoc
- [x] 16.7 The three LLM stages compute `edgeBandsOff = opaqueEnabled && LlmGate.isOpaqueDynamic(ctx)` and pass it; `LlmRandomStage` computes it once and uses it for the rate too; the flag-off path still never reads the tree (INV-RTR-22)
- [x] 16.8 Test doubles follow the new `selectAction` signature (`PipelineFixture.StubLlm`, the three stage tests' stubs, `oracle/ScriptedLlm`); every existing `CoordinateMapper` test call passes `false` and keeps its expectation
- [x] 16.9 `CoordinateMapperOffTreeTapTest`: with `edgeBandsOff`, `click` at `0.97h` and at `0.02h` yields an `LlmTapAction`, and `(0, 0)`, `(540, 0)` and `(0, 900)` yield null; without it, `0.97h` is still rejected; `LlmEngine.classify` names the flagged zero-axis rejections `degenerate` and keeps `boundary` for `(540, 0)` without the flag (replaces the declared-limitation case of 3.8)
- [x] 16.10 Stage tests: the stub engine records `edgeBandsOff`; it is `true` only on an opaque step with a dynamic region and opaque routing on, `false` on a widget step and with opaque routing off
- [x] 16.11 Line length and reflow in the javadocs touched by this change (`LlmGate`, `LlmRandomStage`, `LlmGateTest`) (NT-06)
- [x] 16.12 `ToolCallParser.parseJsonString`: after a successful parse of `click`/`long_click` with `x` or `y` absent or unreadable as an integer, run `lastResortIntScan` on the original text and return its result when non-null; otherwise the parsed action unchanged (D12, `llm-infrastructure` delta)
- [x] 16.13 `ToolCallParserTest`: `{"x": {"x": 288, 587} }` (XML and native raw forms) → `(288, 587)`, `int_scan`; `{"name":"click","arguments":{}}` → `(0, 0)`, `none`; a `back` and a `type_text` are unaffected; every existing case unchanged
- [x] 16.14 Run `/sdd-test-run agent/pipeline` and `/sdd-test-run llm`; confirm `RunSpecResolveTest.sentinelPlanDigestUnchanged`, `SinkNeutralityTest` and the parity goldens pass unchanged
- [x] 16.15 CLAUDE.md: the `ape.llmPercentageNoSubstrate` entry says the boundary bands are lifted on opaque steps with a dynamic region, and the parser note says a tap answer without readable coordinates falls to the integer scan (`repair:"int_scan"`) on every arm; test count in Notes
- [x] 16.16 `followups.md`: measuring the bands against the system bars on every step (D11 alternative B, with the ordinary-screen footer evidence); the `UiAutomation.takeScreenshot` fallback capturing the full physical screen while the mapping uses `Display.getSize()`; and what D12 leaves: a tap with no recoverable coordinate still parses as `0` on the missing axis (making it a parse failure would count against the breaker on every arm)
- [x] 16.17 Device check: `mvn package`; with the owner's LLM server, run shatteredpixeldungeon and retrowars at `0.7` for about 3 min each and one of them at `-1`; record `llm_tap` against `boundary` on `dec.dyn` steps next to task 13's numbers, and the `-1` digest, in `evidence.md`

## 18. Third revision: screenshot orientation (D13)

- [x] 18.1 Revise proposal, design (D13, API, Risks, Testing Strategy), the `llm-infrastructure` delta (MODIFIED "ScreenshotCapture — SurfaceControl Screenshot"), `evidence.md` (the probe) and this file; `openspec validate llm-opaque-screen --strict` passes
- [x] 18.2 `ScreenshotCapture.captureViaSurfaceControl` passes `displayRotation(AndroidDevice.getRotation())` as the rotation, read after the reflective lookup; `static int displayRotation(int)` returns `0…3` unchanged and `0` otherwise; javadoc states the frame and why
- [x] 18.3 `ScreenshotCaptureStageTest`: `displayRotation` keeps `0…3` and maps `-1` and `4` to `0`; the existing stage cases unchanged
- [x] 18.4 CLAUDE.md: the LLM notes say the capture follows the display's rotation (every arm); test count
- [ ] 18.5 Device re-test on the jar with 18.2: retrowars (landscape) and shatteredpixeldungeon at `0.7`, shatteredpixeldungeon at `-1`, about 3 min each, as in 16.17; record next to 16.17 in `evidence.md` (retrowars' `back` share and taps, the `-1` digest)

## 17. Final verification (second and third revisions)

- [ ] 17.1 `mvn test` green; update the test count in CLAUDE.md if it changed
- [ ] 17.2 Run `/sdd-qa-lint-fix src/main/java/com/android/commands/monkey/ape`
- [ ] 17.3 Run `/sdd-verify src/main/java/com/android/commands/monkey/ape`
- [ ] 17.4 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 17.5 Run `/sdd-docs-sync src/main/java/com/android/commands/monkey/ape`

## 15. Merge

- [ ] 15.1 After the revised gate passes its test (groups 13, 16 and 18) and group 17 and task 13.3 are done, merge branch `llm-opaque-screen` into `master`. This task stays open until the merge is done, so the change cannot be archived before it
