# Design: llm-coordinate-single-base

## Context

The proposal states the defect and its measured effect. This section records what the investigation of 2026-10-03 established about the code at `e93dea86`, because the decisions below depend on it.

**Three conversions, two bases.**

| Site | Converts | Divides / multiplies by | Source of the dimensions |
|---|---|---|---|
| `ape/llm/ApePromptBuilder.java:131-140` → `:405-406` (shared line formatter), `:638-639` (`v13`), `:717-718` (`v17`) | widget centre → [0,1000) | `rootBounds.right`, `rootBounds.bottom`; constants 1080 × 1920 if the tree or root is null | `tree.getRootNode().getBoundsInScreen()` |
| `ape/agent/StatefulAgent.java:1833-1837` (`recordActionHistory`) | executed widget centre → [0,1000) | `display.right`, `display.bottom` | `AndroidDevice.getDisplayBounds()` |
| `ape/llm/LlmEngine.java:95-97, :157` → `CoordinateMapper.toPixels` → `CoordinateNormalizer.normalize` (`:33-35`) | answer → pixel | `deviceWidth`, `deviceHeight` | `ScreenshotStep.deviceDimensions(tree)` (`:50-68`): display first, root only if the display throws |

`ScreenshotCapture.captureViaSurfaceControl` crops `Rect(0, 0, width, height)` from the display with the same `deviceDimensions` pair, so the image and the answer mapping share a base. `AndroidDevice.getDisplayBounds()` (`ape/AndroidDevice.java:130-140`) is `Display.getSize`: on the campaign AVD (`hw.lcd` 1080 × 1920, density 420) it reports 1080 × 1794, excluding the 126 px navigation bar.

**The root is a window, not the screen.** `GUITreeBuilder.buildGUITree` (`ape/tree/GUITreeBuilder.java:138`) builds from `getRootInActiveWindowSlow()`, and node bounds are copied raw from `AccessibilityNodeInfo.getBoundsInScreen` (`:603-604`). The root's bounds are therefore the active window's frame:

- a full-screen activity: `[0,0][1080,1920]`, because the DecorView, whose `navigationBarBackground` child sits at `[0,1794][1080,1920]`, extends behind the navigation bar;
- a dialog or popup: its own rectangle (`[28,690][1052,1166]`, `[555,74][1070,368]`, …);
- a window the keyboard resized: a shortened frame (bottom ≈ 1050 or 1487 in the observed traces).

Evidence from existing data, gathered without new runs:

- **E6 arm 4.** Per matched decision, the list's base for the executed widget was the full window 1080 × 1920 in 50,018 (159 APKs), a window-sized base in 4,802 (`x < 1080` and/or `y < 1794`; 149 APKs), the display 1080 × 1794 in 74 (11 APKs), and unresolved in 4,064 (`settings-audit/coordinate_base.out` at `rvsec-study03-replication-package@d8ec828`). The fallback constant cannot produce a varying base, and a null root cannot occur on this path, so the root node is the source.
- **UIAutomator dumps.** In the 468 dumps of `rvsec-study03-replication-package/data/raw/grounding-screens/`, the root shows `[0,0][1080,1794]` in 310, but `navigationBarBackground` appears as `[0,0][0,0]` in 250 of them and no node ends below y=1794. `uiautomator dump` clips every node to `Display.getSize`, so these dumps cannot show the raw root. They are why the two bases looked identical offline.

**Why the tests passed.**

- `ApePromptBuilderTest` calls `build(null, …)`, so the dimension block never reads a root.
- `ApePromptBuilderIdentifierTest` builds real lines with a null tree and asserts no coordinate.
- `PromptIntegrationTest:251-294` re-implements the formula with its own constants instead of calling the builder.
- `ScreenshotStep.deviceDimensions` is untestable in the JVM (`ScreenshotStepTest:17`), and so is `LlmEngine.selectAction` (`LlmEngineTest:21`).
- The history normalization has no test.

Constraints: P1 (no flag, no new abstraction beyond one primitive), P3 (remove the `GUITree` parameter and the inline copies rather than keep them), the compose-don't-duplicate rule (one forward primitive next to its inverse), and the JVM suite (1123 tests, 19 skipped) stays green with the `rearch-01` parity golden suite identical.

## Architecture

```
LlmEngine.selectAction(tree, state, actions, mopData, recentActions, mode)
  │
  ├─ step 2  ScreenshotStep.deviceDimensions(tree) ──► {W, H}            (single source)
  │          ScreenshotStep.capture(W, H) ──► image in W×H space
  │
  ├─ step 4  ApePromptBuilder.build(W, H, state, actions, mopData, image, recentActions)
  │            ├─ element list:  CoordinateNormalizer.toNormalized(centre(node), W, H)
  │            └─ history:       CoordinateNormalizer.toNormalized(entry.centerX/Y, W, H)
  │
  └─ step 8  CoordinateMapper.toPixels(answer, W, H) ──► CoordinateNormalizer.normalize
             CoordinateMapper.map(pixel, …, W, H)

StatefulAgent.recordActionHistory(action)
  └─ ActionHistoryEntry(type, class, text, centerX_px, centerY_px, typed, result)   (no display read)
```

### Key Components

| Component | Responsibility | Input | Output |
|---|---|---|---|
| `CoordinateNormalizer.toNormalized` (new) | The single pixel → [0,1000) conversion | `pixelX, pixelY, width, height` | `int[]{normX, normY}` in [0,999] |
| `CoordinateNormalizer.normalize` (unchanged) | The single [0,1000) → pixel conversion | `qwenX, qwenY, width, height` | `int[]{pixelX, pixelY}` |
| `ApePromptBuilder.build` (signature changes) | Renders list and history in the decision's base | `deviceWidth, deviceHeight, state, actions, mopData, base64Image, recentActions` | `List<Message>` |
| `ApePromptBuilder.ActionHistoryEntry` (fields change) | Carries the executed widget's pixel centre | `centerX, centerY` (pixels) | — |
| `StatefulAgent.recordActionHistory` | Records pixel centre, no normalization | executed `ModelAction` | appends entry |
| `LlmEngine.selectAction` | Hands the step-2 pair to the builder | `dimensions` | — |

## Mapping: Spec -> Implementation -> Test

| Requirement / invariant | Implementation | Test |
|---|---|---|
| llm-prompt "Single Coordinate Base", INV-PRM-06 | `ApePromptBuilder.build(int, int, …)`; all list sites and `formatHistoryEntry` call `CoordinateNormalizer.toNormalized` | `ApePromptBuilderCoordinateBaseTest` (new): full-screen, dialog and below-edge scenarios per coordinate-bearing variant; list/history agreement |
| llm-prompt "Widget List Generation" (modified bullet) | shared line formatter, `buildRvsmartV13UserText`, `buildRvsmartV17UserText` | `ApePromptBuilderCoordinateBaseTest`; `PromptIntegrationTest` rewritten to read production output |
| llm-prompt "Action History" (pixel centres, build-time normalization) | `ActionHistoryEntry(centerX, centerY)`, `formatHistoryEntry`, `StatefulAgent.recordActionHistory` | `ApePromptBuilderTest` history cases updated to pixel inputs; new scenario "history entry normalized at build time" |
| llm-infrastructure "CoordinateNormalizer", INV-LLM-13 | `CoordinateNormalizer.toNormalized` | `CoordinateNormalizerTest`: forward scenarios, clamp, exhaustive round trip for `d ∈ {1080, 1794, 1920, 1999}` |
| llm-routing "Action Selection Pipeline", INV-RTR-21 | `LlmEngine.selectAction` passes the `dimensions` local to capture, build and mapping | not JVM-reachable (see `LlmEngineTest:21`): code inspection in the review task plus the device smoke trace check (tasks group 6) |

## Goals / Non-Goals

**Goals**

1. Every [0,1000) coordinate in a prompt is in the space of the screenshot and of the answer mapping, on every window type (INV-PRM-06, INV-RTR-21).
2. One forward conversion in the jar, defined next to its inverse and tested against it (INV-LLM-13).
3. The list and the history cannot diverge for the same widget, by construction rather than by agreement between two call sites.
4. The prompt builder's coordinate output becomes JVM-testable with real dimensions.

**Non-Goals**

- **Reproducing E5b/E6.** No flag keeps the old base. An old arm is reproduced by running its jar (`e93dea86` for E6).
- **Changing the base itself** to the real display size (1920): see D1.
- **`CoordinateMapper`**: boundary bands (`llmBoundaryTopPct`/`llmBoundaryBottomPct`), snap tolerance, off-tree tap synthesis, and the preserved `type_text` → long-click defect are untouched.
- **`ScreenshotStep.deviceDimensions`'s own fallback order** (display → root → constants). When the display throws, the root pair still becomes the base for capture, prompt and mapping alike, so the three stay consistent with each other. That path was not observed in the campaign data.
- **`ScreenshotCapture`'s UiAutomation fallback.** It resolves `androidx.test.platform.app.InstrumentationRegistry`, which is not on the `app_process` classpath, so in the aperv deployment it fails and capture returns null. Were it ever to succeed, `UiAutomation.takeScreenshot` returns the real display, and the image would not be in the `W × H` space. Recorded as a risk, not changed here.
- **Telemetry.** No field is added. `llm[].qwen`, `llm[].px` and the `user` dump already let an analysis check the base (`px/qwen` gives the mapping base; the dump gives the list).
- **System message, variant layouts, image processing**: unchanged.

## Decisions

### D1. The base is the display pair `ScreenshotStep.deviceDimensions` returns

That pair is already what the model sees (capture crops `W × H`) and what its answer is multiplied by. Making the prompt follow it changes one consumer. Making anything else the base would change two.

*Alternatives.*
- (a) **Root node for list and answer.** Wrong for every non-full-screen window: the image is the whole display, so an answer read from the image would be scaled into a dialog's rectangle.
- (b) **Real display size (`getRealSize`, 1920) everywhere.** This changes the capture crop (the image would include the navigation bar), the meaning of the calibrated boundary bands (0.94 × 1794 = 1686 vs 0.94 × 1920 = 1805), and the off-tree tap validity domain (`LlmTapAction.clipToDisplay` with `getDisplayBounds`). Larger blast radius, no measured benefit; rejected.

### D2. Dimensions are arguments of `build`; the `GUITree` parameter is removed

`LlmEngine` already holds the pair. Passing it makes the one-base property a matter of data flow inside one method (`selectAction`), which a reader can check by inspection.

*Alternatives.*
- **The builder calls `ScreenshotStep` itself.** That is a second determination per decision, which can disagree (for example after a rotation between calls), and it couples the builder to Android reflection, making it JVM-untestable.
- **Keep `tree` for future use.** P3: it has no other use in `build`.

### D3. One forward primitive, `CoordinateNormalizer.toNormalized`, int-only, multiply-first

It sits next to `normalize` so both directions are read and tested together. The arithmetic is `(int)(p * 1000.0 / d)` clamped to [0,999], the form the history already used. The builder's copies used `(int)((p / (double) d) * 1000)`. The two forms were compared exhaustively for every integer pixel `0 <= p < d` and every `d` in [500, 2000): they agree everywhere. Consolidating therefore changes no number for a given divisor. Every coordinate change this change causes comes from the divisor (D1), which keeps the before/after comparison attributable to one cause.

*Alternative.* A `Rect`-taking overload. It is not needed: centres come from `Rect.centerX()`/`centerY()` (D4). An int-only signature keeps `CoordinateNormalizer` free of Android types, like its inverse.

### D4. Centres come from `Rect.centerX()` / `Rect.centerY()`

The platform method replaces three inline `(left + right) / 2` expressions in the builder and one in `StatefulAgent`, so the centre is computed one way everywhere without adding a helper. `centerX()` is `(left + right) >> 1`. It equals `/ 2` for non-negative sums and floors instead of truncating for negative ones, a case the clamp maps to 0 either way.

### D5. History entries carry pixel centres; the builder normalizes them

The history is recorded when an action executes and shown in a later prompt. Normalizing at record time requires a divisor at record time, which `StatefulAgent` would have to find on its own. That is the second determination this change removes. Normalizing at build time uses the same call and the same pair as the list, so agreement is structural. `StatefulAgent` no longer reads `AndroidDevice.getDisplayBounds()` for the prompt.

*Alternative.* Keep normalized history and hand `StatefulAgent` the dimensions. That needs a channel from `ScreenshotStep` (owned by `RunContext`'s LLM units) into the agent for a value it would use once. It is more coupling for a weaker guarantee.

### D6. No flag, no kill switch

This is a defect repair to a mechanism that had a stated intended behavior (gh6 design `:277`). A flag that selects the defective base would exist only to reproduce E6, which is reproducible from its jar (P1). The comparability consequence is documented in the proposal instead.

## API Design

### `static int[] CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)`

- **Pre:** `width > 0`, `height > 0` (the caller passes `deviceDimensions`, positive by contract).
- **Post:** returns `{clamp((int)(pixelX * 1000.0 / width), 0, 999), clamp((int)(pixelY * 1000.0 / height), 0, 999)}`. For `0 <= p < d < 2000`, `normalize(toNormalized(p)) ∈ [p − 2, p]` per axis (INV-LLM-13).
- **Errors:** none thrown for positive dimensions. Pixels outside `[0, d)` are clamped.

### `List<SglangClient.Message> ApePromptBuilder.build(int deviceWidth, int deviceHeight, State state, List<ModelAction> actions, MopData mopData, String base64Image, List<ActionHistoryEntry> recentActions)`

- **Pre:** `deviceWidth > 0`, `deviceHeight > 0`. Every other argument may be null, as today.
- **Post:** INV-PRM-01..06 hold. No coordinate is computed from any other divisor.
- **Errors:** never throws (unchanged contract).

### `ApePromptBuilder.ActionHistoryEntry(String actionType, String widgetClass, String widgetText, int centerX, int centerY, String typedText, String result)`

`centerX`/`centerY` are screen pixels of the executed widget's centre (0 when the action has no resolved node, e.g. `back`, which the formatter renders without coordinates as today). The fields `normX`/`normY` are replaced, not kept alongside (P3).

## Data Flow

1. Step N executes a model action. `StatefulAgent.recordActionHistory` stores `bounds.centerX()`, `bounds.centerY()` of its resolved node in the ring buffer (max 5).
2. Step M > N reaches an LLM stage. `LlmEngine.selectAction` computes `{W, H}` once and captures the image at `W × H`.
3. `ApePromptBuilder.build(W, H, …)` renders every listed widget's centre and every history entry's centre through `toNormalized(·, W, H)`.
4. The model answers `(x, y)`. `CoordinateMapper.toPixels(x, y, W, H)` inverts it. A copied coordinate returns within 2 px of the centre, toward the origin.

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Display throws | `AndroidDevice.getDisplayBounds` in `deviceDimensions` | unchanged: root node, then constants | the chosen pair is still used by all three steps, so they stay consistent |
| Node bounds unreadable | `getBoundsInScreen` in builder / history | unchanged: coordinates omitted (list) or entry centre 0 (history) | prompt still valid (INV-PRM-01) |
| Widget centre outside display | node behind the navigation bar, off-screen scroll child | clamp to 999 / 0 | listed; a copied coordinate maps to the display edge |

## Risks / Trade-offs

- **[Arm comparability]** → Prompts differ from E5b/E6 on nearly every screen, so any cross-study comparison must treat this as a new arm version. Mitigation: proposal and the `RUN_START` build stamp identify the jar. No flag (D6).
- **[Fine-tuned models trained on old-base prompts]** (branch `laya`, on hold) → a model fine-tuned to copy old-base coordinates may systematically mis-copy under the new base. Mitigation: out of scope here. Regenerate training prompts with a jar carrying this change before resuming that line.
- **[Widgets below the display edge clamp to 999]** → several such widgets would share one y coordinate. They are behind the navigation bar and not tappable anyway; the off-tree tap domain already excludes them.
- **[UiAutomation fallback image is not in W × H]** → dead in `app_process` today (needs `androidx.test`). If it ever becomes reachable, the image base would diverge. Mitigation: recorded here and in Non-Goals; the device smoke would show it as visual answers drifting.
- **[INV-RTR-21 has no JVM test]** → `ScreenshotStep` is `final` and its first call reaches Android reflection. Mitigation: the property is one local variable flowing to three calls inside one method, checked in review. The device smoke trace check verifies the outcome (list base equals mapping base).

## Testing Strategy

| Layer | What to test | How | Count |
|---|---|---|---|
| Unit | `toNormalized` scenarios, clamp, exhaustive round trip | `CoordinateNormalizerTest`, plain JVM | ~5 |
| Unit | list coordinates per variant on full-screen and dialog geometry; below-edge clamp; list/history agreement; history pixel → normalized | `ApePromptBuilderCoordinateBaseTest` (new), real `GUITreeNode`/`ModelAction` built as in `ApePromptBuilderIdentifierTest` | ~8 |
| Unit (updated) | existing history and integration tests switched to pixel inputs and production output | `ApePromptBuilderTest`, `PromptIntegrationTest` | existing |
| Regression | decision sequences unchanged under a scripted LLM | `rearch-01` parity golden suite | existing |
| Device | on the RVSec AVD with the campaign's server (vLLM v0.29.0, `--enable-auto-tool-choice --tool-call-parser hermes`, no `tool_choice` in the request): list coordinate of each executed widget equals `toNormalized(centre, 1080, 1794)`; copied answers land inside the widget | smoke run, trace check script in the scratchpad (same join as `coordinate_base.py`) | 1 run |

## Open Questions

- **Should `ScreenshotStep` become substitutable so the pipeline is JVM-testable?** That would let INV-RTR-21 be pinned by a unit test instead of review plus smoke. Recommendation: not in this change. It touches the unit-ownership design (`llm-routing` "LLM Unit Lifecycle and Ownership") for a property that is one local variable.
- **Should a study that adopts this jar re-derive the copy/visual split?** With one base, `coordinate_base.py`'s "copied" classification becomes exact, which makes the split a cleaner metric than it was in E6. That is a decision for the study, not the jar.
