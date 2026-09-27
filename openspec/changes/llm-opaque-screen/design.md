# Design: llm-opaque-screen

## Context

The shared LLM precondition lives in one place, `src/main/java/com/android/commands/monkey/ape/agent/pipeline/LlmGate.java:52-54`:

```java
static boolean allows(StepContext ctx) {
    return ctx.actionBufferSize() == 0 && ctx.newState().getActions().size() > 2;
}
```

All three LLM stages consult it first — `LlmNewStateStage.java:71`, `LlmStagnationStage.java:104`, `LlmRandomStage.java:81`. On a screen the accessibility tree does not describe, the abstract state's action list is `[MODEL_BACK, MODEL_MENU]` (two actions; one with `ape.modelMenuEnabled=false`), so the gate never opens. The Study 03 replication session measured this on E5 (jar `e93dea86`): two LibGDX games receive zero LLM calls on every prompt and model, and their traces hold one abstract state (`AndroidLauncher@…@[W=1][A=2]`) for 600 s of alternating `MODEL_BACK`/`MODEL_MENU`. The proposal carries the numbers.

Everything downstream of the gate already handles a widgetless screen:

- `ApePromptBuilder` renders an empty element list as `(no elements)` (`llm/ApePromptBuilder.java:651`).
- `CoordinateMapper.map` (`llm/CoordinateMapper.java:120-251`) returns `state.getBackAction()` for `back`, finds no containment or snap candidate for `click`/`long_click` and synthesizes `new LlmTapAction(state, x, y, long)`, and returns null for `type_text`.
- `LlmTapAction` (`model/LlmTapAction.java`) is ephemeral (INV-MODEL-14): not in `State.actions`, never re-selected, never refined (`Model.resolveNonDeterministicTransitions` skips it, `model/Model.java:441`), dispatched against the physical display bounds.
- The dead-pair ban keys a tap on `(stateKey, pixelX, pixelY)` (`CoordinateMapper.banKey`).

The key `ape.llmPercentageNoSubstrate` exists end to end with no reader: `Config.java:184` (static field + clamp), `runtime/Feature.java:182` (sub-parameter of `LLM_RANDOM`, neutral `-1`), `runtime/KeyOwnership.java:222`, `runtime/RunSpec.java:267` (normalization), `runtime/Presets.java:126` (every preset pushes `-1`), and rv-android `tool.py:198` (`llm_percentage_no_substrate`). Its spec requirement (INV-RTR-09) says it has no effect, and `LlmRandomStageTest:279-310` guards that.

**Revision (2026-09-27).** `f828e5e6` implemented the design below with the opaque predicate alone as the trigger. E5c showed that most opaque steps are not dynamic content (stuck progress dialogs, text dialogs, an ad activity, camera previews that take no touch, Compose splashes), and device dumps showed the tree separates them (`evidence.md`). Decisions D8–D10 narrow the trigger; everything else here stands.

**Second revision (2026-09-27).** The device check of the revised gate (`evidence.md`, task 13) found every shatteredpixeldungeon answer rejected by the bottom boundary band, on a real button, and the code review found the input clause of D8 accepting clickability that `GUITreeBuilder.patchGUITree` writes. D11 lifts the boundary bands on opaque steps with a dynamic region; D8 (d) now requires native clickability; D5b is narrowed to the steps D11 does not cover; D12 extends the parser's last-resort coordinate scan to a case the pre-parse fixes created.

Constraints: the project's run-plan rules (a feature absent from the plan has no mechanism, INV-RUN-05; stages read parameters injected at assembly, never `Config`, INV-DP-12; fail-fast resolution, INV-RUN-02), the agent-generator draw-sequence discipline (`decision-pipeline` INV-DP-10, `llm-routing` "Probabilistic LLM Routing"), and telemetry identical on every arm (event-sink "Telemetry Neutrality").

## Architecture

```text
RunSpec ──(LLM_RANDOM present ∧ noSubstrate ≥ 0? rate)──► DecisionPipeline.fromSpec
                                                    │ injects boolean opaqueEnabled
                                                    │ (+ opaqueRate for LlmRandom)
                                                    ▼
StepContext (newState, newGUITree)
            ──► LlmNewStateStage ─┐
            ──► LlmStagnationStage├─► LlmGate.allows(ctx, opaqueEnabled)
            ──► LlmRandomStage ───┘        │  bufferEmpty ∧ (actions>2 ∨ (opaqueEnabled ∧ isOpaque(state) ∧ hasDynamicRegion(tree)))
                   │ rate = opaqueDynamic ? opaqueRate : percentage; draw only if rate>0
                   │ edgeBandsOff = opaqueEnabled ∧ isOpaqueDynamic(ctx)   (all three stages)
                   ▼
              LlmEngine.selectAction(…, edgeBandsOff) ──► CoordinateMapper.map(…, edgeBandsOff) ──► LlmTapAction
                                                             │ edgeBandsOff: no top/bottom band, x=0 or y=0 rejected
                                                             │ otherwise: bands as before

StatefulAgent.resolveNewAction ──► sink.beginStep(..., isOpaque(newState), hasDynamicRegion(newGUITree)) ──► dec.opaque:1, dec.dyn:1
ApeAgent.requestRestart ─────────► sink.restartRequested() ──► RUN_END.counters.restarts
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `LlmGate.isOpaque(State)` | The opaque predicate (INV-RTR-21) | `State` | `boolean` |
| `LlmGate.hasDynamicRegion(GUITree)` | The dynamic-region predicate (INV-RTR-26, D8) | the step's tree, may be null | `boolean` |
| `LlmGate.isOpaqueDynamic(StepContext)` | `isOpaque(newState) ∧ hasDynamicRegion(newGUITree)` — the one definition the gate and `LlmRandom`'s rate share | step context | `boolean` |
| `LlmGate.allows(StepContext, boolean)` | Shared precondition with the opaque-dynamic clause | step context, feature flag | `boolean` |
| `LlmNewStateStage` / `LlmStagnationStage` | Pass the injected flag to the gate | `StepContext` | `StageResult` |
| `LlmRandomStage` | Per-step rate choice, zero-rate no-draw | `StepContext` | `StageResult` |
| The three LLM stages | Compute `edgeBandsOff = opaqueEnabled ∧ isOpaqueDynamic(ctx)` and pass it to the engine (D11) | `StepContext` | argument |
| `LlmEngine.selectAction(…, boolean edgeBandsOff)` | Hands the fact to the mapper unchanged | argument | argument |
| `ToolCallParser.parseJsonString` | Runs the last-resort integer scan also when a tap action parses without a readable `x` or `y` (D12) | response text | `ParsedAction` |
| `CoordinateMapper.map(…, boolean edgeBandsOff)` | Skips the boundary bands and rejects a pixel with either coordinate `0` when set; unchanged otherwise (INV-RTR-27) | pixel, action type, flag | `ModelAction` or null |
| `DecisionPipeline.fromSpec` | Derives opaque routing (`LLM_RANDOM` in the plan ∧ key `>= 0`) and injects the flag and the rate | `RunSpec` | stages |
| `Feature` / `KeyOwnership` / `RunSpec` | Unchanged: the key stays an `LLM_RANDOM` sub-parameter, neutral `-1` | — | — |
| `EventSink.beginStep(…, boolean opaque, boolean dyn)` / `StepRecord` | `dec.opaque`, `dec.dyn` | flags | record fields |
| `EventSink.restartRequested()` / `NdjsonSink.runEnd` | `counters.restarts` | calls | record field |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-RTR-21 opaque predicate | `LlmGate.isOpaque` | `LlmGateTest.opaqueWhenNoTargetedAction`, `…notOpaqueWithOneWidgetAction`, `…emptyActionsNotOpaque` |
| INV-RTR-22 off ⇒ pre-change gate and draws | `LlmGate.allows(ctx, false)`; `LlmRandomStage` with `opaqueEnabled=false` | `LlmGateTest.featureOffIsSizeRule`; `LlmRandomStageTest.featureOffDrawSequenceUnchanged` |
| INV-RTR-23 on ⇒ opaque-dynamic clause, buffer kept | `LlmGate.allows(ctx, true)` | `LlmGateTest.featureOnOpensOpaqueDynamic`, `…featureOnKeepsOpaqueWithoutRegionClosed`, `…bufferClosesOpaque` |
| INV-RTR-26 dynamic region | `LlmGate.hasDynamicRegion` | `DynamicRegionTest` over device fixtures (LibGDX true; progress dialog, text dialog, zxing, Compose splash, ordinary screens false) and synthetic trees (threshold, text, focusability, Compose/WebView ancestors, null tree) |
| INV-RTR-24 rate choice, zero-rate no draw | `LlmRandomStage.decide` | `LlmRandomStageTest.opaqueDynamicStepUsesOpaqueRate`, `…opaqueStepWithoutRegionDrawsNothing`, `…zeroOpaqueRateDrawsNothing` |
| New-State LLM Mode (opaque scenarios) | `LlmNewStateStage` | `LlmNewStateStageTest.opaqueFirstVisit{On,Off}` |
| SataAgent — LLM New-State Hook | same | same |
| INV-RTR-25 plan unchanged at `-1` | no change to `Feature`/`KeyOwnership`/`RunSpec` | `RunSpecResolveTest.sentinelPlanDigestUnchanged` (golden `digest`/`features`/`params` captured from `e93dea86` for the `llm` and `llm_mop` presets); `RunSpecAbortTest` existing sub-parameter cases (`0.5` without `LLM_RANDOM` aborts) |
| INV-RTR-27 no boundary bands on opaque steps with a dynamic region | the three stages compute `edgeBandsOff`; `LlmEngine.selectAction` and `CoordinateMapper.map` take it | `CoordinateMapperOffTreeTapTest`: `edgeBandsOff` → `pixelY = 0.97h` and `0.02h` yield an `LlmTapAction`, `(0,0)` is rejected; without it `0.97h` is still `boundary`; stage tests: the flag reaches the engine only on an opaque step with a region and opaque routing on |
| `llm-infrastructure` ToolCallParser: last-resort scan on a parseable tap without readable coordinates | `ToolCallParser.parseJsonString` | `ToolCallParserTest`: `{"x": {"x": 288, 587}}` → `(288, 587)`, `int_scan`; `{}` arguments → `(0, 0)`, `none`; every existing case unchanged |
| Boundary bands unchanged elsewhere (declared limitation) | `CoordinateMapper.map` with `edgeBandsOff=false` | every existing `CoordinateMapper*Test` case, run with `false` |
| Removal of the seam | delete `Config.llmPercentageNoSubstrate`, `clampLlmPercentageNoSubstrate` | delete `ConfigTest` cases and `LlmRandomStageTest` no-consumer guard |
| INV-SNK-15 `dec.opaque` | `StatefulAgent.resolveNewAction` → `beginStep`; `StepRecord` | `NdjsonSinkTest.opaqueFlag*`; `SinkNeutralityTest` unchanged |
| INV-SNK-17 `dec.dyn` | `StatefulAgent.resolveNewAction` → `beginStep`; `StepRecord` | `NdjsonSinkTest.dynFlag*`; `SinkNeutralityTest` and parity goldens unchanged (the oracle has no trees) |
| INV-SNK-16 `restarts` | `ApeAgent.requestRestart` → `sink.restartRequested()`; `NdjsonSink.runEnd` | `NdjsonSinkTest.restartsCounted`, `…restartsZeroWritten` |
| Device behavior | whole path | tasks group 6 (first gate, done in E5c) and group 12 (revised gate) |

## Goals / Non-Goals

**Goals:**
- Let the LLM act on screens with no widget action whose content is a drawn, touch-taking surface, through the existing off-tree tap, when the plan asks for it — and nowhere else among opaque screens.
- Keep every existing arm — every plan with `ape.llmPercentageNoSubstrate=-1` — identical in plan, gate and draws to jar `e93dea86`, and in actions except where D12 recovers the coordinates of a tap answer that `e93dea86` parsed as `(0,0)`.
- Make opaque steps and forced restarts visible in the trace on every arm.
- Let an answer on an opaque step with a dynamic region reach the whole frame the model sees, edges included (D11).

**Non-Goals:**
- A state signal for opaque screens (screenshot hash or similar). The abstraction is the project's core mechanism; `LlmNewState` therefore fires at most once per opaque state.
- Changing how ephemeral taps reset `graphStableCounter`.
- Naming the kind of surface (game engine, camera, map) by class: those names never reach the compressed tree (D8).
- Changing the coordinate mapper's snap/containment rules, or the boundary bands on any step other than an opaque step with a dynamic region (D11). Where dynamic content shares the screen with widgets the size rule already opens the gate and the off-tree tap already exists. Measuring the bands against the system bars on every step (D11, alternative B) would change every LLM arm already measured and is a follow-up (`followups.md`).
- Setting `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`: about twice the nodes on ordinary screens, and a new abstraction for every arm.
- A screenshot comparison to tell whether a tap on a canvas had an effect: a canvas changes on its own, so a difference cannot be attributed to the tap.
- Choosing the E6 rate. That is a campaign decision made in rv-android.

## Decisions

**D1 — The predicate is "no action requires a target", read from the abstract state.** Alternatives: (a) `getActions().size() <= 2` — wrong when `ape.modelMenuEnabled=false` (a one-button dialog has two actions and is not opaque); (b) widget class names such as `SurfaceView`/`GLSurfaceView` — rejected by the owner; the class the accessibility node reports for a GL surface is not reliable, and E5's trace does not even record it; (c) static analysis (`isWidgetlessSubstrate`, deleted with the full-JSON parser) — describes applications, not screens, and a Compose app that exposes semantics at run time would be misclassified; (d) "no widget action and visited k times" to exclude splash screens — adds a calibration knob for a cost the rate already bounds. The chosen predicate is the explorer's own knowledge: SATA has nothing but leave or open the menu. *Revised by D8:* the predicate stays as the definition of an opaque step (and of `dec.opaque`), but it is no longer the whole trigger — (b) was right about class-name lists and wrong in concluding that nothing in the tree could tell a canvas from a dialog; (d)'s "cost the rate already bounds" did not hold in E5c, where two thirds of the opaque calls went to screens the model cannot act on.

**D2 — Reuse `ape.llmPercentageNoSubstrate`, redefining `-1` from "inherit" to "off".** Alternative: a new boolean `ape.llmOpaqueScreen` and retire the orphan key. Reuse wins because every arm already pushes `-1`, the harness already maps the key and its type/default are pinned by rv-android's tests; the redefinition breaks nothing because nothing read the key. The cost is a semantic amendment recorded in the specs.

**D3 — The key stays a sub-parameter of `LLM_RANDOM`; no feature is added.** An earlier revision made the key the activation key of a new feature `LLM_OPAQUE_SCREEN` so that `RUN_START.features` would state the mechanism. Review by the Study 03 replication session showed it breaks the requirement that matters most: `RunSpec.planValues` (`RunSpec.java:340-354`) keeps the effective values of every **active** feature's keys and `computeDigest` (`:413-429`) hashes them, so today `ape.llmPercentageNoSubstrate=-1` is inside the digest of every LLM arm (its owner `LLM_RANDOM` is active there). Moving it to a feature absent at `-1` would drop it from `planValues` — or, stated explicitly, push it to `inert` (`applyInertRule`, `:306-332`) — and change the digest of every LLM arm with the mechanism off. E5b runs on `e93dea86` to choose the configuration E6 runs on the new jar, so "new jar at `-1`" must equal `e93dea86` in plan as well as in decisions. Keeping the owner achieves that with no code in `runtime/` at all, and the existing sub-parameter rule already gives the right validation: `-1` is inert on a plan without `LLM_RANDOM`, any other value there aborts with `missing_dependency`. The value is still stated in `RUN_START.params` on every LLM arm. The cost: opaque routing needs `ape.llmPercentage > 0`, which every LLM arm has.

**D4 — The coin stays behind the gate; a zero rate draws nothing.** The earlier idea of drawing before the gate would change the draw sequence of every LLM arm even with the feature off. Keeping `gate ∧ rate > 0 ∧ nextDouble() < rate` preserves the pre-change sequence when off (the gate is unchanged and `rate` is the positive assembly rate), and makes `0` mean "no random routing on opaque steps" without consuming draws there.

**D5 — The predicate requires a non-empty action list.** `CoordinateMapper.map` returns null when `actions` is empty (`CoordinateMapper.java:136`), before the tap path, so an LLM call on such a state is discarded by construction. `State` always constructs `MODEL_BACK` (`State.java:63`), so the case should not arise, but the predicate states it rather than relying on that.

**D5b — Boundary bands are not relaxed on opaque steps without a dynamic region, nor on any other step (declared limitation; narrowed by D11).** `CoordinateMapper.map` rejects `pixelY < 0.05h` or `> 0.94h` (`CoordinateMapper.java:130-134`, keys `ape.llmBoundaryTopPct`/`ape.llmBoundaryBottomPct`) before building a tap. Game HUD controls often sit in the bottom band, so correct answers there are lost. Alternatives: disable the bands on opaque steps (taps could hit the navigation bar and leave the app, and the top band is also what rejects the degenerate `(0,0)` emission, which would need its own check); per-opaque band keys (two more knobs before any measurement). Chosen: keep the bands and measure — each rejected answer is an `llm[]` sub-event with `reason:"boundary"` on a step marked `dec.opaque:1`, so E5c yields the rate directly. A campaign can already widen the bands for a whole run through the existing plan keys.

**D6 — Opaque steps are marked on every arm, in `dec`.** The fact describes the screen, so the arm without LLM needs it for the same stratification. It could ride the `STATE` dictionary entry at no per-step cost (a state's action set is fixed); the step record is chosen so an analyst joins the flag to `llm[]` and `out` within one line, as agreed when planning. The field is omitted when false, so its cost falls on opaque steps only.

**D7 — Restarts counted by the sink.** `ApeAgent.requestRestart()` is the single funnel for the three stability hooks (graph, state, activity). The sink counts calls, as it already counts `acts` and `states`, and writes `restarts` beside them in `RUN_END.counters`. `RunCounters` stays the LLM telemetry's snapshot and is not widened.

**D8 — The trigger is an opaque step whose tree holds a dynamic region.** A *dynamic region* is a node of the step's `GUITree`, not inside a subtree rooted at `androidx.compose.ui.platform.ComposeView` or `android.webkit.WebView`, that (a) has class exactly `android.view.View`, (b) has no children, (c) has empty text and empty content description, (d) is focusable, natively clickable (clickable and not `isPatchedClickable()`) or long-clickable, and (e) covers at least half of the root node's bounds (intersection with the root, area ratio `>= 0.5`). Each clause answers a case measured in `evidence.md`:
- (a) `SurfaceView`, `GLSurfaceView`, `TextureView` and plain custom views do not override `getAccessibilityClassName`, so when they reach the tree they are `android.view.View`; widgets (`TextView`, `ImageView`, `FrameLayout`, `ProgressBar`, `WebView`, …) report their own class. A class list of surface types would match nothing, which is why the earlier rejection of (b) in D1 stands for lists and not for this rule.
- (b), (c) The content is drawn, not described: no subtree, no label. A text dialog, a progress dialog and zxing's status line fail here.
- (d) With `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` cleared, a view reaches the tree only when it is important for accessibility; for a surface that means an input listener or focusability — the view takes touch. LibGDX sets both. A camera surface has neither and is absent, which is the desired answer: the preview takes no touch. Clickability means the node's own: `GUITreeBuilder.patchGUITree` (on by default, `ape.patchGUITree`) marks the children of a clickable container clickable and records it with `setPatchedClickable(true)`; a text-less `View` that is only patched-clickable does not take touch itself, so it does not count. The gate was mostly protected anyway (a patched child yields `MODEL_CLICK`, which makes the state not opaque), but `dec.dyn` is written on every step and would over-count.
- (e) The region is the screen's content, not an icon: LibGDX covers the whole root. Half is a margin under that and above any decorative view that survives (b)–(d); it is a constant, not a key (D9).
- The Compose exclusion: Compose semantics nodes also report `android.view.View`, and a splash is a chain of full-screen views. They fail (d) today; the ancestor rule keeps a focusable Compose leaf from passing. `ComponentActivity.setContent` always wraps the composition in a `ComposeView`, so the ancestor is present.
- The WebView exclusion: an HTML `<canvas>` becomes a `View` leaf inside the WebView's virtual tree, and an ad WebView is the case E5c must not repeat (passportreader).
- Known false positive, not excluded: Flutter semantics nodes also report `android.view.View`, so an unlabeled, focusable, full-screen Flutter node would pass. No Flutter app is in the corpus; `dec.dyn` makes it visible if one appears.
The predicate walks the tree once, O(nodes), no IPC; a null tree (tests, the oracle) yields `false`. It is evaluated only after `isOpaque` holds when used by the gate, and on every step for telemetry.

Alternatives: (i) "opaque and visited k times" — E5c's stuck dialogs persist 47–188 steps, as long as a game, so persistence does not separate them; (ii) a second tree fetch with `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` set, to see listener-less surfaces — those are exactly the ones that take no touch; (iii) a window-size rule (dialog windows are smaller than the display) — true for the dialogs but silent on camera and Compose splash, which (d) and the ancestor rule already reject; (iv) the screenshot — a second signal the gate would have to calibrate, when the tree already separates every measured case.

**D9 — The area threshold is a constant.** A plan key would be one more knob with no measurement to set it by, and would enter `RunSpec.planValues`, changing every LLM arm's digest (D3). `dec.dyn` records the verdict on every arm, so a later calibration has data; the constant sits in `LlmGate` with the evidence behind it.

**D10 — `dec.dyn` on every arm, computed from the tree.** Like `dec.opaque` (D6) it describes the screen, so the arm without LLM carries it too and the analysis can split opaque steps into routed and not routed without re-deriving the rule. It is emitted on any step whose tree has a dynamic region, opaque or not: a surface beside widgets is the common case (`evidence.md`) and the count is what a later extension would be judged on. It is the predicate's verdict, not a label of the screen; a trace auditor checks it against screenshots or sources. The oracle and `FakeStepContext` have no tree, so their records never carry it and the parity goldens are unchanged.

**D11 — No boundary bands on opaque steps with a dynamic region.** `CoordinateMapper.map` rejects `pixelY < h × ape.llmBoundaryTopPct` or `> h × ape.llmBoundaryBottomPct` so the model stays off the system bars and the degenerate `(0,0)` answer is caught. The frame it measures against is not the physical screen: `LlmEngine` takes `h` from `ScreenshotStep.deviceDimensions`, which asks `AndroidDevice.getDisplayBounds()` = `Display.getSize()` — the area available to the app, without the navigation bar (1080×1794 on the 1080×1920 emulator) — and `ScreenshotCapture` crops the capture to `Rect(0, 0, w, h)`, so the model sees rows 0…h−1 and `CoordinateNormalizer` clamps every answer into them. The navigation bar is outside that frame in portrait (below `h`) and in landscape (beside `w`, while the bands act on `y`). The bottom band therefore protects no system bar; it removes the bottom 6 % of the app's own content. On shatteredpixeldungeon's title screen that is where "Enter the Dungeon" sits, and all 53 answers there were that button and were rejected (`evidence.md`). The top band still overlaps the status bar (0…63 px) when the app draws under it.

Chosen: on a step where opaque routing is on and `isOpaqueDynamic` holds, the calling stage passes `edgeBandsOff = true` through `LlmEngine.selectAction` to `CoordinateMapper.map`, which then applies neither band and rejects `pixelX == 0 ∨ pixelY == 0` explicitly. The reason is the parser: `ToolCallParser` reads each coordinate with a default of `0` — a `click` whose `x` or `y` is missing or not a number (an empty `arguments`, a `coordinate` key, a nested object) parses as a coordinate `0` on that axis — so a zero on either axis means "no coordinate", not a point the model chose. It is rare (2 of about 20 000 calls in the first 400 E5c traces, both malformed JSON such as `{"x": {"x": 288, 587}}`), and until now the top band caught every such answer by accident, since `y = 0` lies in it; an answer missing only `x` (`(0, y)`) was not caught at all and became a tap on the left edge. A real target at normalized `0` on an axis is under 2 px from the screen edge, so nothing is lost. `LlmEngine.classify` names this rejection `degenerate` on a flagged step (today it does so only for `(0, 0)`); elsewhere the labels are unchanged. Making the parser return no action for a tap without coordinates would fix it at the root, but on every arm, and a parse failure counts against the circuit breaker, so it is a follow-up (`followups.md`). Every other rule of `map` — containment, snap, off-tree synthesis — is unchanged. The stages compute the flag because only they know `opaqueEnabled`, and because `LlmEngine` (package `ape.llm`) calling `LlmGate` (package `agent.pipeline`, which depends on `ape.llm`) would close a cycle. No plan key is added, as in D9; the step is reconstructible from `RUN_START.params`, `dec.opaque` and `dec.dyn`, so no telemetry field is added either.

Consequences: at `-1` the flag is always false and the tree is not read for it (INV-RTR-22); an opaque state offers only `MODEL_BACK`/`MODEL_MENU` and never opens the gate by size, so no path reaches the mapper with the flag set. At a value `>= 0`, every step that is not opaque with a dynamic region maps exactly as before. What is lost on the flagged steps is the top band's cover of the status bar in a game that does not hide it; a tap there has no effect on API 30 and shows as an `llm_tap` that changes nothing.

Alternatives: (A) set `ape.llmBoundaryBottomPct=1.0` in the arms — no code, but it changes the digest of both arms (the `off` arm no longer equals `e93dea86`) and acts on every step, widget screens included; (B) measure the bands against the system bars actually present (apply the bottom band only when the app's frame reaches the physical height), on every step — the correct rule in general, since ordinary screens also lose bottom-navigation and footer buttons to the band (`evidence.md`), but it changes every LLM arm already measured (E5b, E5c); recorded in `followups.md`; (C, chosen) lift the bands on opaque steps with a dynamic region only.

**D12 — The last-resort coordinate scan also runs on a parseable tap without readable coordinates.** `ToolCallParser` reads `x` and `y` with a default of `0`, and its last-resort scan (the first two standalone integers after `"arguments"`) ran only when the fixed JSON did not parse. The pre-parse fixes can produce valid JSON with the coordinates misplaced: the one form measured in E5c, `{"x": {"x": 288, 587}}`, becomes `{"x": {"x": 288, "y": 587}}` under the missing-"y" fix, `x` is then an object and `y` is absent, and the answer parsed as `(0,0)` — discarded as `degenerate` although the model gave `(288, 587)`. Chosen: after a successful parse of a `click`/`long_click`, when `x` or `y` is absent or unreadable as an integer, run the same scan on the original text; use its result (label `int_scan`) when it finds two integers, and keep the parsed action otherwise. It closes the parser's side of the `(0,0)` problem D11 guards against; the zero-axis rejection of D11 stays for what no scan recovers (an empty `arguments`, a single number). Alternative: return no action for a tap without coordinates — cleaner, but a parse failure counts against the circuit breaker on every arm; recorded in `followups.md`.

Scope of the effect: the parser serves every LLM arm, including the `-1` arm, so a run under `-1` is no longer action-for-action identical to `e93dea86` on the rare steps where such an answer occurs (2 of about 20 000 calls in the first 400 E5c traces); the plan digest, the gate and the draw sequence are unaffected, and the steps are countable from `repair:"int_scan"` on sub-events whose response does not fail to parse. The breaker is unaffected: both outcomes are successful calls.

**D13 — The screenshot is captured in the display's current orientation.** `ScreenshotCapture.captureViaSurfaceControl` called `SurfaceControl.screenshot(Rect(0, 0, w, h), w, h, 0)`. With `w`/`h` from `Display.getSize()` — 1794×1080 when the display is at `ROTATION_90` — rotation `0` returns the framebuffer in its natural (portrait) orientation, cropped to the landscape rectangle: the model receives the screen turned 90° and partly cut off. A probe in the session scratchpad that repeats the call on retrowars' menu (`evidence.md`) showed exactly that with `0`, the screen as shown with the display's rotation (`1`), and the screen upside down with `3`. The coordinate mapping already used the landscape frame (task 16.17), so only the image was wrong, on every landscape screen, on every LLM arm, since the capture was written.

Chosen: pass the display's current rotation (`AndroidDevice.getRotation()`, `Surface.ROTATION_0…3`) as the last argument; a value outside `0…3` — the helper returns `-1` when the window manager cannot be asked — falls back to `0`, today's call. In portrait the rotation is `0`, so the call and the image are unchanged there. The rotation is read after the reflective lookup succeeds, so off-device (the JVM tests) nothing changes. The `UiAutomation.takeScreenshot` fallback is left as it is (`followups.md`). Alternatives: rotate the bitmap after capture — an extra full-size copy per call for the same result; capture at the physical size and let the model see the navigation bar — changes the frame every mapping rule is written against.

Scope of the effect: like D12, this changes the `-1` arm too — every LLM call on a landscape screen now sees the screen as shown. Plan digest, gate and draw sequence are unaffected; the answers, and therefore the decisions, on landscape screens differ. It is declared to the Study 03 session with the jar.

## API Design

### `static boolean LlmGate.isOpaque(State state)`

Pre: `state != null`. Post: `true` iff `state.getActions()` is non-empty and no element has `requireTarget()`. Pure, no allocation beyond iteration; an empty list returns `false`.

### `static boolean LlmGate.allows(StepContext ctx, boolean opaqueEnabled)`

Post: `ctx.actionBufferSize() == 0 && (ctx.newState().getActions().size() > 2 || (opaqueEnabled && isOpaqueDynamic(ctx)))`. With `opaqueEnabled == false` the result equals the pre-change expression for every input. The one-argument overload is removed (P3); the three stages pass their injected flag.

### `static boolean LlmGate.hasDynamicRegion(GUITree tree)`

Pre: none (`null` allowed). Post: `false` for a null tree or a root with empty bounds; otherwise `true` iff some node outside every `ComposeView`/`WebView` subtree satisfies D8 (a)–(e) with `DYNAMIC_REGION_MIN_AREA = 0.5`. Pure; a single pass that does not descend into excluded subtrees.

### `static boolean LlmGate.isOpaqueDynamic(StepContext ctx)`

Post: `isOpaque(ctx.newState()) && hasDynamicRegion(ctx.newGUITree())`, in that order (the tree walk only runs on opaque steps).

### Stage constructors

- `LlmNewStateStage(LlmEngine, BooleanSupplier, Consumer<ModelAction>, boolean opaqueEnabled)`
- `LlmStagnationStage(LlmEngine, BooleanSupplier, int restartThreshold, Consumer<ModelAction>, boolean opaqueEnabled)`
- `LlmRandomStage(LlmEngine, BooleanSupplier, double percentage, double opaqueRate, Random, Consumer<ModelAction>)` — `opaqueRate < 0` means the feature is absent; the stage derives `opaqueEnabled = opaqueRate >= 0`.

Each stage calls `engine.selectAction(tree, state, actions, mopData, history, mode, edgeBandsOff)` with `edgeBandsOff = opaqueEnabled && LlmGate.isOpaqueDynamic(ctx)` (D11). `LlmRandomStage` computes it once and uses it for the rate as well.

`LlmRandomStage.decide`:

```text
if !LlmGate.allows(ctx, opaqueEnabled): Continue
edgeBandsOff = opaqueEnabled && LlmGate.isOpaqueDynamic(ctx)
rate = edgeBandsOff ? opaqueRate : percentage
if rate <= 0 || random.nextDouble() >= rate || !breakerAllows: Continue
result = engine.selectAction(…, "random", edgeBandsOff)
… unchanged
```

With the feature absent, `rate == percentage > 0` on every step that passes the gate, so the conjunct order and draws are the pre-change ones.

### `ModelAction LlmEngine.selectAction(GUITree, State, List<ModelAction>, MopData, List<ActionHistoryEntry>, String mode, boolean edgeBandsOff)`

Unchanged except that `edgeBandsOff` is handed to `CoordinateMapper.map`. The overload without it is removed (P3); every test double follows.

### `byte[] ScreenshotCapture.capture(int width, int height)`

Unchanged signature. The SurfaceControl path passes `displayRotation(AndroidDevice.getRotation())` as the rotation, where the package-visible pure `static int displayRotation(int)` returns its argument when it is in `0…3` and `0` otherwise (D13).

### `ModelAction CoordinateMapper.map(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight, boolean edgeBandsOff)`

`edgeBandsOff == false`: exactly the current behavior. `edgeBandsOff == true`: after the `back` branch, `pixelX == 0 || pixelY == 0` returns null; the band check is skipped; everything after it is unchanged.

### `static Verdict LlmEngine.classify(ModelAction match, boolean banned, ParsedAction parsed, boolean edgeBandsOff)`

The `no_match` reason is `degenerate` when `parsed` is `(0, 0)`, or when `edgeBandsOff` holds and either coordinate of `parsed` is `0`; otherwise `boundary`, as before.

### Plan (no change)

`Feature`, `KeyOwnership` and `RunSpec` are untouched. `DecisionPipeline.fromSpec` computes `double opaqueRate = spec.has(Feature.LLM_RANDOM) ? spec.llm().dbl("ape.llmPercentageNoSubstrate") : -1` and `boolean opaqueEnabled = opaqueRate >= 0`.

### `EventSink.beginStep(int step, long tRelMs, String activity, boolean activityHasMop, String stateKey, boolean opaque, boolean dyn)` and `EventSink.restartRequested()`

`NoopSink` implements both as no-ops. `NdjsonSink` stores `opaque` and `dyn` in the pending record (written as `dec.opaque:1` / `dec.dyn:1` when true) and increments a restart count written in `runEnd`.

## Data Flow

1. `Monkey.run` resolves the plan exactly as before; the key is an `LLM_RANDOM` sub-parameter.
2. `DecisionPipeline.fromSpec` derives opaque routing from `spec.has(LLM_RANDOM)` and the key's value, and passes the flag to the three stage constructors and the rate to `LlmRandom`.
3. Each step, `StatefulAgent.resolveNewAction` opens the record with `LlmGate.isOpaque(newState)` and `LlmGate.hasDynamicRegion(newGUITree)`, then the pipeline runs. On an opaque step with a dynamic region and the feature on, an LLM stage may call the engine; a `click` answer becomes an `LlmTapAction`, accepted and resolved through the existing `LlmGate.accept` path.
4. The tap is dispatched; the next step's graph update records an ephemeral edge (a `NEW_ACTION` edge the first time a coordinate is tapped, resetting `graphStableCounter`) and feeds `recordLlmOutcome` with `new_state=false` (the abstract state does not change on a canvas).
5. When a stability hook calls `requestRestart()`, the sink counts it; `RUN_END` writes the total.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `>= 0` on a plan without `LLM_RANDOM` (no LLM, or `llmPercentage=0`) | `RunSpec.resolve` (existing sub-parameter rule) | abort `missing_dependency` naming `LLM_RANDOM` | harness sets `llm_percentage > 0` or the key to `-1` |
| Answer in a boundary band on a step that is not opaque with a dynamic region | `CoordinateMapper.map` | unchanged: `no_match`/`boundary`, stage returns `Continue` | none; counted for the follow-up (D5b) |
| Answer with either coordinate `0` (the parser's default for a missing coordinate) on an opaque step with a dynamic region | `CoordinateMapper.map` (`edgeBandsOff`) | null; `LlmEngine.classify` records `no_match`/`degenerate` | none needed (D11) |
| Engine null on an opaque step (screenshot, transport, parse, `type_text`) | `LlmEngine` | unchanged: stage returns `Continue`, SATA decides | none needed |
| Sink failure writing `opaque`/`restarts` | `NdjsonSink` | unchanged latch: first `Throwable` disables the sink, run continues | none |

## Risks / Trade-offs

- [Splash and loading screens are opaque] → excluded by D8 (no focusable surface leaf); measured by `dec.opaque` without `dec.dyn`.
- [A drawn surface that takes touch through `onTouchEvent` alone, with no listener and not focusable, is absent from the tree] → not routed (false negative). Such views almost always share the screen with widgets (paint, charts), where the size rule already opens the gate.
- [A Compose `Canvas` game, or an HTML `<canvas>` game in a WebView] → excluded by the ancestor rule (false negative), accepted: none is in the corpus, and the WebView case is the ad failure E5c measured.
- [A focusable decorative `View` covering half the screen with an opaque state] → would be routed (false positive); none was observed on device; in the survey of 468 uncompressed grounding dumps the large `View` leaves were surfaces, boards and image viewers, and the one decorative match (a spacer) fails (d). `dec.dyn` on every arm makes it auditable.
- [Game controls at the screen edges] → reachable on opaque steps with a dynamic region (D11); still behind the bands on every other step (D5b).
- [A tap on the status bar of a game that does not hide it, now that the top band is lifted there] → no effect on API 30; visible as an `llm_tap` that changes nothing (D11).
- [Taps at new coordinates reset `graphStableCounter`, delaying SATA's forced restart on canvases where the LLM makes no progress] → Not changed (non-goal); `counters.restarts` measures it per arm. If E6 shows the LLM arm restarting far less on games, a follow-up can treat ephemeral edges on opaque states as `EXISTING` for the counter.
- [Every tap on a canvas counts as unproductive (abstract state unchanged)] → the dead-pair ban only bans an exact coordinate after 5 strikes, so varied coordinates are unaffected; repeated exact coordinates are banned, which is the intended behavior against the known `x∈{499,500}` collapse.
- [Throughput falls on opaque screens: an LLM call costs seconds against the 200 ms throttle] → this is the treatment's cost, not a defect; step counts are not comparable between on and off, and the analysis relies on host-side coverage and violations.
- [Bottom navigation and footer buttons on ordinary screens are in the bottom band] → declared (D5b); countable from `reason:"boundary"` on steps without `dec.dyn`; the general fix (alternative B of D11) is a follow-up.
- [The mechanism is not in `RUN_START.features`] → its value is in `RUN_START.params` on every LLM arm, and the digest separates `-1` from any other value; a reader derives "opaque routing on" from `LLM_RANDOM` ∈ features ∧ value `>= 0`.
- [The `-1` arm differs from `e93dea86` on tap answers D12 now recovers] → rare (2 in ~20 000 E5c calls), a recovered answer instead of a discarded one, identifiable per sub-event by `repair:"int_scan"`; declared to the Study 03 session with the jar.
- [The `-1` arm differs from `e93dea86` on every landscape screen, where the model now sees the screen as shown (D13)] → a correction, not a treatment: identical in portrait, declared to the Study 03 session with the jar; landscape steps are identifiable from the device's rotation, not from the trace.
- [Mixing jars across a campaign] → E5 stays on `e93dea86`; at `-1` the new jar's plan digest equals `e93dea86`'s (INV-RTR-25), and any other value changes it, so a trace states which regime produced it.
- [rv-android spec row stale (`aperv/spec.md:760`)] → follow-up in that repository; no behavior depends on it.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | `LlmGate.isOpaque`/`allows` truth table (buffer × size × opaque × region × flag) | fake `State`/`StepContext` with typed actions and a tree | ~10 |
| Unit | `LlmGate.hasDynamicRegion` on real trees | compressed dumps captured on the API 30 emulator (retrowars menu and in-game, Shattered PD title, mtgfam/smokingtracker/urlchecker progress dialogs, flyingcarpet About, zxing capture ×3, myne splash, ordinary screens), loaded through `GUITreeBuilder`'s XML reader and `patchGUITree`, as production patches every tree | ~12 |
| Unit | `LlmGate.hasDynamicRegion` clause by clause | synthetic trees: area 0.49/0.5, text, content-desc, not focusable, patched-only clickability, child present, `ComposeView`/`WebView` ancestor, null tree, empty root | ~10 |
| Unit | `LlmRandomStage` rate choice, zero-rate no-draw, off-neutral draw sequence | counting `Random` over a mixed opaque/non-opaque fixture sequence; compare draw count and positions with the pre-change rule | ~5 |
| Unit | `LlmNewStateStage`/`LlmStagnationStage` on/off on an opaque state | stub engine | ~4 |
| Unit | digest/features/params of `llm` and `llm_mop` plans with `-1` equal to the `e93dea86` golden; existing sub-parameter abort unchanged | `RunSpecResolveTest`, `RunSpecAbortTest` | ~3 |
| Unit | bands lifted with `edgeBandsOff`, `(0,0)`, `(x,0)` and `(0,y)` rejected and classified `degenerate`, bands kept without it | `CoordinateMapperOffTreeTapTest`, `LlmEngine.classify` test | ~6 |
| Unit | `edgeBandsOff` reaches the engine only on an opaque step with a region and opaque routing on | stage tests with a recording stub engine | ~4 |
| Unit | last-resort scan on a parseable tap without readable coordinates; defaults kept when nothing is recoverable | `ToolCallParserTest` | ~3 |
| Unit | rotation passed to the capture: `0…3` kept, anything else `0` | `ScreenshotCaptureStageTest` | ~2 |
| Device | landscape capture as shown (D13): retrowars at `0.7` and shatteredpixeldungeon at `0.7` and `-1` re-run on the final jar, compared with task 16.17 | standalone runs as in task 16.17 | 3 runs |
| Unit | `dec.opaque` present/absent, `restarts` written including zero | `NdjsonSink` round-trip through `org.json` | ~4 |
| Integration | Sink neutrality with the new field; parity goldens for every preset unchanged | existing `SinkNeutralityTest`, parity oracle | existing |
| Device | retrowars, `llm` preset, same seed: `-1` vs `0.7` — `llm.calls`, `llm_tap`, `restarts`, `dec.opaque` share, host-side coverage and violations | `scripts/run_emulator.sh` + standalone run, or one rv-platform task per value | 2 runs |
| Device | shatteredpixeldungeon and retrowars at `0.7` after D11: `llm_tap` against `boundary` on `dec.dyn` steps, compared with task 13 | standalone runs as in task 13 | 2 runs |

## Open Questions

- Is `0.5` the right area threshold? Nothing measured sits near it (games are 1.0); revisit with `dec.dyn` from the next campaign.

- Should `LlmStagnation` count as its episode trigger the `graphStableCounter` value that taps keep resetting on a canvas? Left as is; revisit with the `restarts` data from the device runs.
- Is the E6 opaque rate equal to `llmPercentage` (same treatment intensity on every screen) or higher (the LLM is the only agent that can act there)? Campaign decision, outside this change.
