# Design: llm-coordinate-single-base

## Context

The proposal states the defect and its measured effect. This section records what the investigations of 2026-10-03 and 2026-10-05 established about the code at `e93dea86`, because the decisions below depend on it.

**Three conversions and one band, two bases.**

| Site | Converts | Divides / multiplies by | Source of the dimensions |
|---|---|---|---|
| `ape/llm/ApePromptBuilder.java:131-140` → `:405-406` (shared line formatter), `:638-639` (`v13`), `:717-718` (`v17`) | widget centre → [0,1000) | `rootBounds.right`, `rootBounds.bottom`; constants 1080 × 1920 if the tree or root is null | `tree.getRootNode().getBoundsInScreen()` |
| `ape/agent/StatefulAgent.java:1833-1837` (`recordActionHistory`) | executed widget centre → [0,1000), **not clamped** | `display.right`, `display.bottom` | `AndroidDevice.getDisplayBounds()` |
| `ape/llm/LlmEngine.java:95-97, :157` → `CoordinateMapper.toPixels` → `CoordinateNormalizer.normalize` (`:33-35`) | answer → pixel | `deviceWidth`, `deviceHeight` | `ScreenshotStep.deviceDimensions(tree)` (`:50-68`): display first, root only if the display throws |
| `ape/llm/CoordinateMapper.java:128-135` (boundary reject) | rejects `pixelY < H × 0.05` or `pixelY > H × 0.94` | fractions of `deviceHeight` | the same `deviceDimensions` pair, but the fractions were written for a 1920-high screen (gh6 `design.md:294`: "navigation bar (bottom 6% of screen)") |

`ScreenshotCapture.captureViaSurfaceControl` crops `Rect(0, 0, width, height)` from the display with the same `deviceDimensions` pair, so the image and the answer mapping share a base. `AndroidDevice.getDisplayBounds()` (`ape/AndroidDevice.java:130-140`) is `Display.getSize`: on the campaign AVD (`hw.lcd` 1080 × 1920, density 420) it reports 1080 × 1794, excluding the 126 px navigation bar.

**The root is a window, not the screen.** `GUITreeBuilder.buildGUITree` builds from the `AccessibilityNodeInfo` that `StatefulAgent` obtains with `getRootInActiveWindowSlow()` (`ape/agent/StatefulAgent.java:682,734`), and node bounds are copied raw from `AccessibilityNodeInfo.getBoundsInScreen` (`:603-604`). The root's bounds are therefore the active window's frame:

- a full-screen activity: `[0,0][1080,1920]`, because the DecorView, whose `navigationBarBackground` child sits at `[0,1794][1080,1920]`, extends behind the navigation bar;
- a dialog or popup: its own rectangle (`[28,690][1052,1166]`, `[555,74][1070,368]`, …);
- a window the keyboard resized, or a bottom sheet: a shortened full-width frame (`H < 1794`; the "short window" row of the table below, 3,937 calls).

Evidence from existing data, gathered without new runs:

- **E6 arm 4.** Per matched decision, the list's base for the executed widget was the full window 1080 × 1920 in 50,018 (159 APKs), a window-sized base in 4,802 (any other root: dialogs and popups, keyboard-resized, tall and landscape windows; 149 APKs), the display 1080 × 1794 in 74 (11 APKs), and unresolved in 4,064 (`settings-audit/coordinate_base.out` at `rvsec-study03-replication-package@d8ec828`; the same script rerun on 2026-10-05 over the 163 traces, avare's now holding no LLM answer, gives 49,766 / 4,777 / 4,063 and 158 / 148 APKs, `evidence/audit_rerun.out`). The fallback constant cannot produce a varying base, and a null root cannot occur on this path, so the root node is the source.
- **UIAutomator dumps.** In the 468 dumps of `rvsec-study03-replication-package/data/raw/grounding-screens/`, the root shows `[0,0][1080,1794]` in 310, but `navigationBarBackground` appears as `[0,0][0,0]` in 250 of them and no node ends below y=1794. `uiautomator dump` clips every node to `Display.getSize`, so these dumps cannot show the raw root. They are why the two bases looked identical offline.
- **E6 arm 4, per prompt (2026-10-05, `evidence/coordinate_counterfactual.out`).**
  - In 130,199 of the 130,248 calls (all but 49), the prompt's numbered entries line up 1:1, in order, with the target-requiring actions the trace prints after `Sata Strategy: buffer size`. That gives an exact list base for every decision, not only the matched ones:

    | List base | Calls |
    |---|---:|
    | full 1080 × 1920 | 105,488 |
    | narrow window (W < 1080) | 19,271 |
    | short window (W = 1080, H < 1794) | 3,937 |
    | tall window (1794 < H < 1920) | 944 |
    | landscape | 328 |
    | display | 169 |
    | other | 62 |

  - A replica of `CoordinateMapper.map` plus the dead-pair ban, fed the snap floor and bands from each run's `RUN_START` (the campaign ran `ape.llmSnapTolerancePx=150`), reproduces the recorded result and reason of 130,199 of 130,199 aligned calls.
- **The bottom band covers app content.**
  - With `H = 1794` the band starts at y = 1686.36. A target-requiring widget's centre lies in (1686, 1794) in 32.8 % of states (2,871 of 8,743), in 146 of 162 APKs; in E5b it does in 51 of 60 APKs. These are bottom-navigation items, `next`/`settings` buttons and snackbar actions.
  - No answer can reach y ≥ 1794, because `normalize` clamps to `H − 1`.
  - The band rejected 4,879 E6 answers that were not list copies (3.7 %). Under this change, 2,995 of these land on a tree widget, 1,865 become off-tree taps, and 19 stay rejected by D8. The replay without the band, which does not model D8, gives 3,007 / 1,872, because it lets the 19 act at the clamped edge.
  - It also rejected the 19 answers with `qwen` y ≥ 1000, not by design: `normalize` clamped them to 1793. Ten of them copy a history coordinate the jar printed outside [0,999] (next bullet); 9 point past the image on their own (`evidence/prompt_zero_scan_e6.out`).
- **The history prints coordinates outside the answer space.** `recordActionHistory` divides the whole centre by the display and does not clamp (`StatefulAgent.java:1836-1837`). A widget centred below the display is shown at y ≥ 1000, one centred left of or above it at a negative value: 2,860 history entries in 2,182 E6 prompts (1.7 %), 134 in 111 E5b prompts (`evidence/prompt_zero_scan_{e6,e5b}.out`).
- **The status bar is 63 px.** `statusBarBackground [0,0][1080,63]` appears in 210 grounding dumps, and 24 dp × 2.625 at 420 dpi gives 63 px. The top band at 0.05 × 1794 = 89.7 px also covers y ∈ [63, 90). E6 has 16 non-copied answers there: 12 would match and 4 would become off-tree taps. Copies also land there: the top-band misses among copies drop from 183 (bottom band removed, top at 0.05) to 70 (top at 0.035). Both E6 `(0,0)` answers are at y = 0, so a 63 px band still rejects them.
- **The data moved under the audit.** The arm-4 trace of `com.ds.avare_404` was overwritten on 2026-10-05 by a run with no LLM answers. The counterfactual excludes it, so it covers 162 traces and 130,248 answers. `evidence/audit_rerun.out` is the audit's own `coordinate_base.py` rerun on 2026-10-05. That script has no exclusion, so it reads 163 traces, but avare's contributes no answer: it counts the same 130,248.

**Why the tests passed.**

- `ApePromptBuilderTest` calls `build(null, …)`, so the dimension block never reads a root.
- `ApePromptBuilderIdentifierTest` builds real lines with a null tree and asserts only that a coordinate is present (`text.contains("@(")`, `:95`), never its value.
- `PromptIntegrationTest:246-299` re-implements the formula with its own constants (`:261-264`, `:292-295`) instead of calling the builder.
- `ScreenshotStep.deviceDimensions` is untestable in the JVM (`ScreenshotStepTest:17`), and so is `LlmEngine.selectAction` (`LlmEngineTest:21`).
- The history normalization has no test.
- The band scenarios (`llm-routing` spec `:275-291`) and `CoordinateMapIntegrationTest:34-36` use a 1080 × 1920 device. `CoordinateMapperOffTreeTapTest:80-97` uses 1794, but asserts the reject at y=1750 and y=80 as intended behavior: under the old reading of the band, a correct test.

Constraints: P1 (no flag, no new abstraction beyond one primitive), P3 (remove the `GUITree` parameter and the inline copies rather than keep them), the compose-don't-duplicate rule (one forward primitive next to its inverse), and the JVM suite (1123 tests, 19 skipped) stays green with the `rearch-01` parity golden suite identical.

## Architecture

```
LlmEngine.selectAction(tree, state, actions, mopData, recentActions, mode)
  │
  ├─ step 2  ScreenshotStep.deviceDimensions(tree) ──► {W, H}            (single source)
  │          ScreenshotStep.capture(W, H) ──► image in W×H space
  │
  ├─ step 4  ApePromptBuilder.build(W, H, state, actions, mopData, image, recentActions)
  │            ├─ element list:  visibleCentre(node bounds, W, H) ──► toNormalized(·, W, H)   (none if off screen)
  │            └─ history:       visibleCentre(entry.bounds, W, H)  ──► toNormalized(·, W, H)
  │
  └─ step 8  answer outside [0,999] (tool ≠ back) ──► no_match / boundary   (CoordinateMapper.inAnswerSpace)
             CoordinateMapper.toPixels(answer, W, H) ──► CoordinateNormalizer.normalize
             CoordinateMapper.map(pixel, …, W, H)
               └─ boundary: pixelY < H × topPct (0.035) ──► null      (no bottom band)

StatefulAgent.recordActionHistory(action)
  ├─ node action    ─► ActionHistoryEntry(type, class, text, new Rect(nodeBounds), typed, result)   (no display read)
  ├─ MODEL_LLM_TAP  ─► ActionHistoryEntry(click|long_click, null, null, Rect[x,y][x+1,y+1], null, result)
  └─ MODEL_MENU     ─► ActionHistoryEntry(menu, …, null bounds)   rendered without coordinates, like back
```

### Key Components

| Component | Responsibility | Input | Output |
|---|---|---|---|
| `CoordinateNormalizer.toNormalized` (new) | The single pixel → [0,1000) conversion | `pixelX, pixelY, width, height` | `int[]{normX, normY}` in [0,999] |
| `CoordinateNormalizer.normalize` (unchanged) | The single [0,1000) → pixel conversion | `qwenX, qwenY, width, height` | `int[]{pixelX, pixelY}` |
| `ApePromptBuilder.build` (signature changes) | Renders list and history in the decision's base | `deviceWidth, deviceHeight, state, actions, mopData, base64Image, recentActions` | `List<Message>` |
| `ApePromptBuilder.visibleCentre` (new, private static) | The pixel a widget is shown at: centre of its bounds ∩ `[0,W)×[0,H)` | `Rect bounds, W, H` | `int[]{x, y}`, or null when the intersection is empty |
| `ApePromptBuilder.ActionHistoryEntry` (fields change) | Carries the executed widget's pixel bounds | `bounds` (`Rect`, pixels) | — |
| `StatefulAgent.recordActionHistory` | Records pixel bounds, no normalization | executed `ModelAction` | appends entry |
| `LlmEngine.selectAction` | Hands the step-2 pair to the builder; refuses an out-of-space answer before it is mapped | `dimensions`, parsed answer | — |
| `CoordinateMapper.inAnswerSpace` (new, static) | Whether an answer lies in the [0,1000) space the prompt and image define | `x, y` | `boolean` |
| `CoordinateMapper.map` (band changes) | One boundary band: the status bar | `pixelY`, `deviceHeight`, `topPct` | null when `pixelY < H × topPct` |
| `ApePromptBuilder.formatHistoryEntry` (all variants) | The only history renderer; `back` and `menu` without coordinates | entry, `W`, `H` | one line or a compact token |

## Mapping: Spec -> Implementation -> Test

| Requirement / invariant | Implementation | Test |
|---|---|---|
| llm-prompt "Single Coordinate Base", INV-PRM-06 | `ApePromptBuilder.build(int, int, …)`; all list sites and `formatHistoryEntry` call `visibleCentre` then `CoordinateNormalizer.toNormalized`; an unresolved or wholly off-screen node renders no `@(x,y)` (D11) | `ApePromptBuilderCoordinateBaseTest` (new): full-screen, dialog, edge-cut and off-screen scenarios per coordinate-bearing variant; unresolved node per variant; list/history agreement |
| llm-prompt "Widget List Generation" (modified bullet) | shared line formatter, `buildRvsmartV13UserText`, `buildRvsmartV17UserText` | `ApePromptBuilderCoordinateBaseTest`; `PromptIntegrationTest` rewritten to read production output |
| llm-prompt "Action History" (pixel bounds, build-time normalization) | `ActionHistoryEntry(bounds)`, `formatHistoryEntry`, `StatefulAgent.recordActionHistory` | `ApePromptBuilderTest` history cases updated to bounds inputs; new scenario "history entry normalized at build time" |
| llm-infrastructure "CoordinateNormalizer", INV-LLM-13 | `CoordinateNormalizer.toNormalized` | `CoordinateNormalizerTest`: forward scenarios, clamp, exhaustive round trip for `d ∈ {1080, 1794, 1920, 1999}` |
| llm-routing "Action Selection Pipeline", INV-RTR-21 | `LlmEngine.selectAction` passes the `dimensions` local to capture, build and mapping | not JVM-reachable (see `LlmEngineTest:21`): code inspection in the review task plus the device smoke trace check (tasks group 8) |
| llm-routing "Coordinate-to-ModelAction Mapping", INV-RTR-22 | `CoordinateMapper.map` (one band), `CoordinateMapper.inAnswerSpace`, `LlmEngine` step 8 | `CoordinateMapperOffTreeTapTest` (band cases rewritten), new `CoordinateMapperBoundaryTest`: status-bar reject, bottom-navigation match, the `inAnswerSpace` predicate. The step-8 branch that applies it (no `map` call, `back` exempt) is not JVM-reachable (`LlmEngineTest:21`): inspection in task 6.2 plus the device check |
| llm-infrastructure "LLM Configuration Keys" | `Config.llmBoundaryTopPct = 0.035`; bottom field deleted; `KeyOwnership` `feature` line removed and `RETIRED` entry added; `Feature.own` | `ConfigTest`, `KeyOwnershipTotalityTest`, `RunSpecAbortTest` (iterates the retired keys) |
| run-spec "Total Fail-Fast Validation" | `KeyOwnership.RETIRED.put("ape.llmBoundaryBottomPct", …)` | `RunSpecAbortTest` plus one explicit scenario |
| llm-prompt "Element List of the v13 and v17 Variants" | `buildRvsmartV13UserText`, `buildRvsmartV17UserText` group by resolved node, no ordinal | `ApePromptBuilderElementListTest` (new): widget with click+long-click → one line; two scroll actions on one widget → one line; no line starts with a digit; first-appearance order; `v17` tag sums visits; `v17` screen line counts widgets on both sides |
| exploration "StatefulAgent — Action History Ring Buffer" | `ActionHistoryEntry(bounds)` in pixels, `menu`, LLM tap as a one-pixel rectangle; no display read in `StatefulAgent.recordActionHistory` | `StatefulAgentActionHistoryTest` (new) or the agent's existing history fixture: node click → `[0,105][1080,252]`; LLM tap → `[600,900][601,901]`; menu → no bounds |
| llm-prompt "Action History" (LLM tap, menu, `v17`) | `StatefulAgent.recordActionHistory`, `formatHistoryEntry`, `buildRvsmartV17UserText` | `ApePromptBuilderCoordinateBaseTest`: `v17` history through the formatter; LLM tap entry; menu entry |

## Goals / Non-Goals

**Goals**

1. Every [0,1000) coordinate in a prompt is in the space of the screenshot and of the answer mapping, on every window type (INV-PRM-06, INV-RTR-21).
2. One forward conversion in the jar, defined next to its inverse and tested against it (INV-LLM-13).
3. The list and the history cannot diverge for the same widget, by construction rather than by agreement between two call sites.
4. The prompt builder's coordinate output becomes JVM-testable with real dimensions.
5. The boundary reject keeps the model off system UI and nothing else: on the display base that is the status bar (and a degenerate `(0,0)`), not 107 px of app content (INV-RTR-22).
6. The history tells the model what actually ran: an off-tree tap where it tapped, a menu press as a menu press.
7. The `v13`/`v17` lists show each widget once, without an ordinal nothing reads.
8. A listed coordinate is a point of the widget that is on screen, the point the jar would tap for it; a widget with no part on screen lists none (D11).

**Non-Goals**

- **Reproducing E5b/E6.** No flag keeps the old base. An old arm is reproduced by running its jar (`e93dea86` for E6).
- **Changing the base itself** to the real display size (1920): see D1.
- **`CoordinateMapper` beyond the band**: containment, snap tolerance, off-tree tap synthesis, the dead-pair ban and the preserved `type_text` → long-click defect are untouched.
- **What the list offers.** After this change 6,874 E6 copies still miss their widget (scenario S4 of `evidence/coordinate_counterfactual.out` §6). About 86 % of those (5,891) copy an entry no tool can act on as listed:
  - 5,066 are a `click` on an entry whose only action is a scroll;
  - 576 are a `click` on a long-click-only widget;
  - 227 copy an entry whose node is entirely off screen, which this change lists without a coordinate (D11), so under the same-entry assumption they cannot be copied at all; 128 of them are actions APE had already marked invalid;
  - 22 are a `type_text` on a non-input entry.

  Of the geometric rest, 771 are cases where containment picks a smaller child of the copied container, 159 hit another overlapping or nearby widget, and 53 fall in the status-bar band.

  `v13` lists every target-requiring action with `@(x,y)`, and its lines carry no action type. Scroll is excluded from the LLM's tools by design (gh6: SATA scrolls mechanically, `docs/analise_prompts_sota.md:198,213,540`), so a copied scroll-only entry can only become a `click` that finds no `MODEL_CLICK`. Selection by list index is **not** an option: the author discarded it on 2026-07-29, after an earlier rv-android implementation was abandoned (`rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §[rev2b]). The consistent remedy is to curate the list so that it offers only entries the tools can act on, which the same report already recommends because containers pollute the list. That is a prompt change with its own proposal.
- **Landscape.** 328 E6 answers (31 APKs) were given on a 1794 × 1080 display. Under this change, copies there reach the named widget 47 % of the time, against 42 % as run (49 % with the clamped centre; 4 landscape copies are of off-screen entries). Capture passes rotation `0` (`ScreenshotCapture.java:80-81`), and whether the crop matches `getSize` in landscape is unverified on device. One base keeps the list and the mapping consistent with each other, not necessarily with the image.
- **`ScreenshotStep.deviceDimensions`'s own fallback order** (display → root → constants). When the display throws, the root pair still becomes the base for capture, prompt and mapping alike, so the three stay consistent with each other. That path was not observed in the campaign data.
- **`ScreenshotCapture`'s UiAutomation fallback.** It resolves `androidx.test.platform.app.InstrumentationRegistry`, which is not on the `app_process` classpath, so in the aperv deployment it fails and capture returns null. Were it ever to succeed, `UiAutomation.takeScreenshot` returns the real display, and the image would not be in the `W × H` space. Recorded as a risk, not changed here.
- **Telemetry.** No field is added. `llm[].qwen`, `llm[].px` and the `user` dump already let an analysis check the base (`px/qwen` gives the mapping base; the dump gives the list).
- **System message, variant layouts, image processing**: unchanged. One instruction goes stale: three system messages, `v17`'s among them, tell the model to avoid the navigation bar (`ApePromptBuilder.java:212,289,310`), while the screenshot, cropped to the display, no longer contains the navigation bar. It is left as it is, because the system message is out of scope and the line asks for nothing the model can do in the image.

## Decisions

### D1. The base is the display pair `ScreenshotStep.deviceDimensions` returns

That pair is already what the model sees (capture crops `W × H`) and what its answer is multiplied by. Making the prompt follow it changes one consumer. Making anything else the base would change two.

*Alternatives.*
- (a) **Root node for list and answer.** Wrong for every non-full-screen window: the image is the whole display, so an answer read from the image would be scaled into a dialog's rectangle.
- (b) **Real display size (`getRealSize`, 1920) everywhere.** This would put the navigation bar inside the image, and the model's answer space with it. That makes the old bottom band meaningful again, but only by showing the model 126 px it must not tap. It also changes the capture crop and the off-tree tap validity domain (`LlmTapAction.clipToDisplay` with `getDisplayBounds`). Larger blast radius, no measured benefit; rejected. D7 re-derives the band for the base D1 keeps instead.

### D2. Dimensions are arguments of `build`; the `GUITree` parameter is removed

`LlmEngine` already holds the pair. Passing it makes the one-base property a matter of data flow inside one method (`selectAction`), which a reader can check by inspection.

*Alternatives.*
- **The builder calls `ScreenshotStep` itself.** That is a second determination per decision, which can disagree (for example after a rotation between calls), and it couples the builder to Android reflection, making it JVM-untestable.
- **Keep `tree` for future use.** P3: it has no other use in `build`.

### D3. One forward primitive, `CoordinateNormalizer.toNormalized`, int-only, multiply-first

It sits next to `normalize` so both directions are read and tested together. The arithmetic is `(int)(p * 1000.0 / d)` clamped to [0,999]: the history's form, which had no clamp, plus the clamp the builder's copies had. The builder's copies used `(int)((p / (double) d) * 1000)`. The two forms were compared exhaustively for every integer pixel `0 <= p < d` and every `d` in [500, 2000): they agree everywhere. Consolidating therefore changes no number for a given divisor. Every coordinate change this change causes comes from the divisor (D1), which keeps the before/after comparison attributable to one cause.

*Alternative.* A `Rect`-taking overload. It is not needed: the builder's `visibleCentre` produces the pixel from a `Rect` (D4, D11). An int-only signature keeps `CoordinateNormalizer` free of Android types, like its inverse.

### D4. Centres come from `Rect.centerX()` / `Rect.centerY()` of the visible part

One private helper in the builder, `visibleCentre(Rect, W, H)`, intersects the bounds with the display and returns `centerX()`/`centerY()` of the result, or null (D11). It replaces three inline `(left + right) / 2` expressions in the builder; the one in `StatefulAgent` disappears, because the agent stores bounds (D5). `centerX()` is `(left + right) >> 1`, which equals `/ 2` here: after the intersection both edges are non-negative.

### D5. History entries carry pixel bounds; the builder normalizes them

The history is recorded when an action executes and shown in a later prompt. Normalizing at record time requires a divisor at record time, which `StatefulAgent` would have to find on its own. That is the second determination this change removes. Normalizing at build time uses the same call and the same pair as the list, so agreement is structural. `StatefulAgent` no longer reads `AndroidDevice.getDisplayBounds()` for the prompt.

The entry stores the node's bounds (a copy of the `Rect`) rather than a centre, because the point shown is the centre of the visible part (D11), which depends on the display of the decision that renders it. Storing the bounds lets the builder apply the one helper to the list and the history alike.

*Alternative.* Keep normalized history and hand `StatefulAgent` the dimensions. That needs a channel from `ScreenshotStep` (owned by `RunContext`'s LLM units) into the agent for a value it would use once. It is more coupling for a weaker guarantee.

### D6. No flag, no kill switch

This is a defect repair to a mechanism that had a stated intended behavior (gh6 design `:277`). A flag that selects the defective base would exist only to reproduce E6, which is reproducible from its jar (P1). The comparability consequence is documented in the proposal instead.

### D7. The boundary band is re-derived in the display base: one top band, no bottom band

The band's purpose, as gh6 wrote it, is to keep the model off system UI. On the display base D1 keeps, the system UI inside `[0, H)` is the status bar, 63 px on the campaign AVD. The navigation bar is outside the image and outside every pixel `normalize` can produce. So:

- **`ape.llmBoundaryBottomPct` is retired** through `KeyOwnership.RETIRED`, with a reason naming this change.
  - Every value below 1.0 rejects app content; every value from 1.0 up never fires. No value of the key does what its description says.
  - P3 deletes it rather than parking it at 1.0, where it would be a knob with no effect.
  - A properties file that still sets it aborts resolution, so a configuration written for the old band cannot silently run under the new one.
- **`ape.llmBoundaryTopPct` defaults to `0.035`**, which is 63 / 1794 rounded down to the band edge `62.79`. It rejects `y ≤ 62`: the status bar and every degenerate `(0,0)`, the second purpose the spec names. The key stays: the status bar height depends on density, so a study on another AVD sets it, which is what J1b exposed it for.
- **Not derived at runtime.** Reading the status bar height from the system (`status_bar_height` dimen, or `WindowInsets`) would make the band device-correct without a key. It adds reflection to a JVM-tested unit for a value the campaign AVD fixes. Rejected for now, and recorded as an open question.

*Alternatives.*
- **Keep `0.94` and accept the loss.** Fixing the list without the band leaves copied answers at 83.6 % of their widget, against 87.2 % with D7 (both with the clamped whole centre; 87.1 % with D11 as well). The bands reject 2,177 copies under the display base alone, against 70 with D7 (`evidence/coordinate_counterfactual.out` S1/S3): once the list stops shifting copies upward, copies of bottom widgets fall into the old band (925 copies that reached their widget as run are rejected by it). It also keeps rejecting the 3.7 % of answers that are not list copies. Rejected.
- **Bottom default 1.0, key kept.** This is behaviorally the same as retiring the key, but it leaves a key no value of which is meaningful (P3). Rejected.

### D8. An answer outside [0,999] is refused before it is mapped

`normalize` clamps, so an answer of y = 1040 became pixel 1793 and was then rejected by the bottom band. That was not by design, but it was the right outcome: the answer is outside the image. With the bottom band gone, the same answer would act on whatever widget sits at the screen's edge.

Of the 19 such E6 answers, 10 copy an out-of-space coordinate the jar itself printed in the history (Context). D5 and D11 stop printing those, so after this change the check is for answers that point past the image on their own: 9 in E6, none in E5b. It stays because it is a predicate and one branch, and because without it such an answer becomes a tap on a widget the model never named. Its wiring is in `LlmEngine.selectAction`, which is not reachable from a JVM test (`LlmEngineTest:21`): the unit test pins `inAnswerSpace`, and the branch itself is checked by inspection (task 6.2).

`CoordinateMapper.inAnswerSpace(x, y)` is `0 <= x <= 999 && 0 <= y <= 999`. For every tool except `back`, `LlmEngine` step 8 does not map an answer that fails it. The decision is `no_match` with reason `boundary`, which `classify` already assigns to a null match that is neither banned nor `(0,0)`. The reason set does not grow. The 19 E6 answers this affects were `boundary` before and stay `boundary`.

*Alternative.* Drop the clamp in `normalize`. The clamp also protects the inverse's other callers and the round-trip property (INV-LLM-13). Changing a primitive to get a policy is the wrong direction. Rejected.

### D9. History entries record what ran, for actions without a resolved node

D5 rewrites `recordActionHistory` to store pixels, so the branch that handled nodeless actions is rewritten anyway. Today it records a `MODEL_LLM_TAP` and a `MODEL_MENU` as `click @(0,0)`. In the prompt, the model then sees a click at the screen's corner that never happened. This is frequent: every one of the 47,573 history entries rendered `@(0,0)` in E6 arm-4 prompts is a class-less `click`, and they appear in 31,466 of 130,266 prompts (24.2 %); in E5b `v13_A`, in 2,157 of 9,586 (22.5 %). The model copied one twice, both `degenerate` answers (`evidence/coordinate_counterfactual.out:172`, `evidence/prompt_zero_scan_e6.out`, `evidence/prompt_zero_scan_e5b.out`).

- **LLM tap.** The entry stores the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()/getPixelY()`, whose visible centre is `(x,y)`, and `click` or `long_click` from `isLongClick()`. The class and text stay null, so it renders as `- click @(x,y) → <result>`.
- **Menu press.** The entry's type is `menu`, and `formatHistoryEntry` renders it without coordinates, as it already renders `back`.
- **`v17`.** Its compact `Recent:` line renders each entry inline from the raw fields (`ApePromptBuilder.java:686`), which is how `back@(0,0)` appears. It moves onto a compact form produced by the same formatter, so there is one place where a history entry becomes text.

Scroll actions are still recorded as `click`, by the `default` branch of the type switch. That is out of scope here: it belongs with the list-curation change, which decides whether scroll-only entries reach the prompt at all (Non-Goals).

This is a change to prompt content beyond the base. It is included because it is the same code, it fixes coordinates the model is shown, and leaving it would carry `@(0,0)` into the new pixel field.

### D10. The `v13`/`v17` lists have one line per widget and no ordinal

`buildRvsmartV13UserText` (`ApePromptBuilder.java:626-650`) and `buildRvsmartV17UserText` (`:692-731`) iterate `state.getActions()` and write a line for every target-requiring action, prefixed `N. `. Two facts make both properties pure overhead:

- **Every field of a `v13` line belongs to the widget, not to the action.** The fields are class, identifier, coordinate, MOP marker and the static metadata of `buildWidgetMetadata`. A widget with click and long-click actions, or with two scroll directions, is written twice with the same text. In E6, 25.3 % of lines share their node's bounds with an earlier line, and 74.2 % of prompts contain such a repeat (`evidence/curation_e6.out`, which identifies widgets by bounds).
- **Nothing reads the ordinal.** The tools take `x, y` only (`LlmClient.java:158-167`). The parser extracts coordinates. `CoordinateMapper.map` matches the pixel against the `actions` list it is given (`state.getActions()`, `LlmRandomStage.java:85-86`), not against prompt lines. The ordinal is a leftover of rvsmart V13's "simple numbered element list" (`:615`).

The lists therefore render one line per widget and drop the ordinal:

- A widget is the action's resolved `GUITreeNode`, compared by identity. All actions on one node of the current tree share the node.
  - Identity is stricter than the bounds criterion of the evidence. Of the 279,463 E6 lines that repeat an earlier line's bounds, 274,830 (24.9 % of all lines) also share its target `Name` and collapse. The other 4,633, in 4,434 prompts, are distinct nodes with identical bounds (a container and its single child) and keep a line each (`evidence/dup_identity_e6.out`).
  - Conversely, 17,865 lines share a `Name` with an earlier line but resolved to a different node, because `State.resolveAction` samples one alias per action. They keep a line each, which is correct: the mapper matches each action against its own resolved node.
- Lines keep the order of each widget's first action in `actions`.
- An action with no resolved node keeps a line of its own, with **no** `@(x,y)`. At `e93dea86` none of the three list sites omits it: `normX`/`normY` start at 0 and are always appended (`ApePromptBuilder.java:398,438`; `:634,646`; `:713,726`), so such a line reads `@(0,0)`. That contradicts the main spec's "Unresolved action node" scenario, which has stood since gh6 and which no test exercises. The repair applies to all three sites ("Single Coordinate Base"). It changes no measured prompt: E6 and E5b have no unresolved listed action, because `StatefulAgent.validateAllNewActions` resolves every action of the state against the latest tree and `GUITree.pickNodes` returns at least one node or throws. The only `@(0,0)` list lines in E6 (25, one prompt of `org.glpi.inventory.agent`) are resolved WebView nodes whose centre lies off-screen at negative coordinates, which the clamp mapped to 0. Under D11 such a node is listed at the centre of its visible part, or without a coordinate when no part of it is on screen.
- `v17`'s status tag (`[UNTESTED]`, `[TESTED-Nx]`, `[WELL-TESTED]`) uses the sum of `getVisitedCount()` over the widget's target-requiring actions. Its screen-info line counts widgets on both sides of the ratio and reads `elements tested`, so the numerator and the denominator share a unit. The `MOP NAVIGATION` block (`buildExplorationContext`) is shared with the default-branch variants, keeps counting actions, and is unchanged.

The evidence is consistent with the model already choosing per widget, which would leave choice unaffected: `click` copies of repeated widgets occur at 1.05× the per-widget rate, against 0.68× the per-line rate. That holds for `click` only. For `type_text` the ratios are 2.37× and 1.70× (`evidence/curation_e6.out` §C), confounded by input widgets, which nearly always carry both a click and a long-click. Whether the shorter list changes what the model picks is measured on device, not assumed. Mapping is unaffected because it never used lines. Exploration is unaffected because `ModelAction`s, SATA, visit counts and dead-pair keys never read the prompt.

*Not done here:* dropping widgets whose only actions are scrolls. The model copies them as a `click` in 3.9 % of calls (5,066). About 52 % of those copies end `dead_pair` and 3.4 % of the executed ones reach a new state; for the 1,305 that are not containers it is 71.3 % and 1.6 %, the highest `dead_pair` share of any `click` copy category (`evidence/curation_e6.out` §D, lines 78-83). Whether to hide them is a separate decision about what the LLM is shown, and is left open.

### D11. A widget is listed at the centre of its visible part, and not at all when off screen

The list used the centre of the node's whole bounds, clamped to the display by the arithmetic. That is not a point of the widget the model can see, nor the point the jar taps. `MonkeySourceApe.generateClickEventAt` (`:368-440`) taps a node action at the centre of the node's bounds intersected with the visible window (`getVisibleBounds`, `:299-333`; `exactCenterX/Y`, so up to half a pixel from the integer centre the list uses), and drops the action when the intersection is empty (`:375-380`; `exploration` "Off-Screen Action Handling", which the code labels INV-EXPL-19). A dropped action is still marked visited (`StatefulAgent.java:852`) and still counts a dead-pair strike. The list now follows the same rule, against the display the screenshot shows:

- the listed pixel is `visibleCentre(bounds, W, H)`: `bounds ∩ [0,W)×[0,H)` (`Rect.intersect`), then `centerX()`/`centerY()`;
- when the intersection is empty, the line keeps its place and carries no `@(x,y)`, as an unresolved node does (D10). Whether such a line should be listed at all is the list-curation question this change leaves open;
- the history renders `entry.bounds` through the same helper (D5), so a widget and the entry of a click on it still show one coordinate.

The jar's tap intersects with the window root as well as the display. The list uses the display only, because the builder no longer receives the tree (D2). The two points differ only for a node that extends beyond its own window. The alignment bounds the root's right and bottom edges, which gives a lower bound: in E6, 151 lines in 44 calls have a different visible centre under the two clips, and 83 of them belong to a node outside its window root, which the list shows with a coordinate and the dispatcher drops (`evidence/dup_identity_e6.out`; the root's left and top edges are not recoverable from the trace).

*Evidence* (E6 arm 4; `evidence/dup_identity_e6.out`, `evidence/coordinate_counterfactual.out` S3 versus S4). 23,080 listed lines (2.1 %, in 3.9 % of prompts) belong to a node entirely off screen and 58,765 (5.3 %) to a node cut by the display edge. 227 of the 53,352 copied answers copied an off-screen entry. With the clamped centre they mapped to another widget (103), tapped off-tree at the edge (83), were rejected by the band (17), or snapped to the intended node (24), an action the dispatcher then drops; 128 of the 227 were actions APE had already marked invalid. Holding the copied entry fixed, copies mapping to their widget are 87.1 % under this rule against 87.2 % with the clamped centre: the 24 snaps, plus one edge-cut copy that now maps to a neighbour. The 24 are not a cost of D11 but a hazard of D7. Their nodes lie in the strip behind the navigation bar, which `validateBounds` accepts because it overlaps the 1920-high root (`MonkeySourceApe.java:621-626`), and only an answer clamped to y = 1793 with no bottom band can snap to them. As run, the band rejected 191 of the 227 off-screen copies and 3 reached their widget (transitions S0 → S4). Each such snap would have executed nothing while counting a visit and a strike. D11 is what keeps D7 from creating them. Of the 1,756 copies of edge-cut entries, 60 change outcome. E5b is unchanged at 89.4 % (2 off-screen copies).

*Alternatives.*
- **Clamped whole centre** (the previous design of this change). It lists a point outside the widget's visible part, and for an off-screen node a point on another widget or on nothing, which is the failure D8 refuses for answers. Rejected.
- **Drop the off-screen line.** That decides what the list offers, which is the open curation question. Not here.
- **Clip to the window root as the dispatcher does.** Needs the tree back in `build`, which D2 removed. The display clip is what the screenshot shows, and the two differ on at least 151 E6 lines (0.01 %).

## API Design

### `static int[] CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)`

- **Pre:** `width > 0`, `height > 0` (the caller passes `deviceDimensions`, positive by contract).
- **Post:** returns `{clamp((int)(pixelX * 1000.0 / width), 0, 999), clamp((int)(pixelY * 1000.0 / height), 0, 999)}`. For `0 <= p < d < 2000`, `normalize(toNormalized(p)) ∈ [p − 2, p]` per axis (INV-LLM-13).
- **Errors:** none thrown for positive dimensions. Pixels outside `[0, d)` are clamped.

### `List<SglangClient.Message> ApePromptBuilder.build(int deviceWidth, int deviceHeight, State state, List<ModelAction> actions, MopData mopData, String base64Image, List<ActionHistoryEntry> recentActions)`

- **Pre:** `deviceWidth > 0`, `deviceHeight > 0`. Every other argument may be null, as today.
- **Post:** INV-PRM-01..06 hold. No coordinate is computed from any other divisor.
- **Errors:** never throws (unchanged contract).

### `ApePromptBuilder.ActionHistoryEntry(String actionType, String widgetClass, String widgetText, Rect bounds, String typedText, String result)`

- `bounds` is in screen pixels.
  - For a node action, it is a copy of the executed widget's bounds.
  - For an LLM tap, it is `[x,y][x+1,y+1]` at the tap's own pixel.
  - For `back` and `menu`, it is null and nothing is rendered.
- An entry whose bounds do not intersect the display renders without coordinates (D11).
- `actionType` takes one of `click`, `long_click`, `type_text`, `back`, `menu`.
- The fields `normX`/`normY` are replaced, not kept alongside (P3).

### `private static int[] ApePromptBuilder.visibleCentre(Rect bounds, int width, int height)`

- **Post:** null when `bounds` is null or does not intersect `[0,width)×[0,height)`; otherwise `{c.centerX(), c.centerY()}` of the intersection `c`, a pixel inside the display. Does not modify `bounds`.
- **Use:** every list site and `formatHistoryEntry`; its result goes to `CoordinateNormalizer.toNormalized`.

### `static boolean CoordinateMapper.inAnswerSpace(int x, int y)`

- **Post:** `0 <= x <= 999 && 0 <= y <= 999`.
- **Use:** `LlmEngine` step 8 skips `map` for a non-`back` answer that fails it. `toPixels` still runs, because it is pure and the sub-event's `px` and the `nearest` fields need a pixel. The clamped pixel is recorded for telemetry and is never matched or tapped.

### `CoordinateMapper.map` (boundary)

- **Pre:** unchanged.
- **Post:** returns null without matching when `pixelY < deviceHeight * topPct` (`ape.llmBoundaryTopPct`, default `0.035`). There is no lower-edge reject.

## Data Flow

1. Step N executes a model action. `StatefulAgent.recordActionHistory` stores a copy of its resolved node's bounds in the ring buffer (max 5).
2. Step M > N reaches an LLM stage. `LlmEngine.selectAction` computes `{W, H}` once and captures the image at `W × H`.
3. `ApePromptBuilder.build(W, H, …)` renders every listed widget and every history entry at `visibleCentre(bounds, W, H)` through `toNormalized(·, W, H)`, or without coordinates when that is null.
4. The model answers `(x, y)`. `CoordinateMapper.toPixels(x, y, W, H)` inverts it. A copied coordinate returns within 2 px of the visible centre, toward the origin, inside the widget.

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Display throws | `AndroidDevice.getDisplayBounds` in `deviceDimensions` | unchanged: root node, then constants | the chosen pair is still used by all three steps, so they stay consistent |
| Node unresolved or bounds unreadable | `getResolvedNode` / `getBoundsInScreen` in builder / history | list: `@(x,y)` omitted at all three list sites (a change: at `e93dea86` they wrote `@(0,0)`); history: a node action whose bounds throw is recorded with null bounds and renders without coordinates (at `e93dea86` it rendered `@(0,0)`) | prompt still valid (INV-PRM-01) |
| Widget partly or wholly outside the display | node behind the navigation bar, off-screen scroll child, off-canvas menu | centre of the visible part; no coordinate when nothing is visible (D11) | line still listed; a copy lands inside the visible part |
| Answer outside [0,999] | model points past the image | `inAnswerSpace` false → `no_match` / `boundary` | SATA fallback, as for any `no_match` |
| Old properties file sets `ape.llmBoundaryBottomPct` | hand-written or stale config | `RETIRED_KEY` abort with the reason | edit the file |

## Risks / Trade-offs

- **[Arm comparability]** → Prompts differ from E5b/E6 on nearly every screen, so any cross-study comparison must treat this as a new arm version. Mitigation: proposal and the `RUN_START` build stamp identify the jar. No flag (D6).
- **[Fine-tuned models trained on old-base prompts]** (branch `laya`, on hold) → a model fine-tuned to copy old-base coordinates may systematically mis-copy under the new base. Mitigation: out of scope here. Regenerate training prompts with a jar carrying this change before resuming that line.
- **[Off-screen widgets listed without a coordinate]** → 2.1 % of E6 lines lose their `@(x,y)` and stay listed. The model can still name them only visually, and they are not on the image. Mitigation: whether to list them is the open curation question (Open Questions); the 227 copies they drew in E6 mostly acted on another widget or were dropped (D11).
- **[UiAutomation fallback image is not in W × H]** → dead in `app_process` today (needs `androidx.test`). If it ever becomes reachable, the image base would diverge. Mitigation: recorded here and in Non-Goals; the device smoke would show it as visual answers drifting.
- **[Top band is AVD-specific]** → `0.035` is the campaign AVD's status bar (420 dpi). Another density makes it slightly too wide or too narrow. Mitigation: the key stays (D7), and the open question below records the runtime alternative.
- **[Answers that the old bottom band absorbed now act]** → 4,879 E6 answers that were not list copies were rejected, and under this change they match (2,995) or tap off-tree (1,865); the 19 outside [0,999] stay rejected (D8). Some of those taps hit empty space near the bottom edge. They are subject to the dead-pair ban like any other tap. The device check (task 8.2) counts them per height band, so the shift is visible rather than assumed.
- **[Offline analyses assume one line per action]** → `coordinate_base.py` and `evidence/coordinate_counterfactual.py` align prompt lines 1:1 with target-requiring actions. On traces of this jar they must align by widget: one line per distinct (target `Name`, resolved-node bounds) pair of the listing, which is what node identity produces in the trace's terms. Mitigation: recorded in the proposal's Impact; the device check (task 8.2) is written against the new alignment.
- **[The live gain depends on the dead-pair ban, which was calibrated under the defect]** → `CoordinateMapper.java:36-41` sets k = 5 from the cal_a1 runs, where it refused 27.5 % of decisions against a stated 30 % ceiling. In E6 it already refused 40.7 % (53,007 of 130,199, `evidence/coordinate_counterfactual.out` §1). A matched widget's ban key is `state|xpath|type`, while an off-tree tap's key carries its exact pixel (`banKey`). Imprecise copies spread their strikes over neighbours and pixels; correct ones concentrate them on the intended widget's key, which reaches five sooner. The counterfactual holds the strike record fixed, so its live gain (+7,085) is an upper figure; with the as-run refusal share it is about +4,400 (proposal). Mitigation: none in this change, which does not touch the ban; the device check (task 8.2) reports the `dead_pair` share, so the shift is measured.
- **[No bottom band on the dimension fallback path]** → when `Display.getSize` throws, `deviceDimensions` uses the root's edges or the 1080 × 1920 constants, the screenshot then includes the navigation bar, and the retired `0.94` band rejected most of it (y > 1804.8). After this change nothing rejects a tap on the navigation bar's home or recents keys on that path. It was not observed in E6 or E5b. Mitigation: recorded; the band is not reinstated for a path the campaign never took.
- **[Retiring a key breaks stale configs]** → an `ape.properties` that sets `ape.llmBoundaryBottomPct` aborts. rv-android does not set it. Mitigation: the abort names the reason, which is the point of `RETIRED`.
- **[INV-RTR-21 has no JVM test]** → `ScreenshotStep` is `final` and its first call reaches Android reflection. Mitigation: the property is one local variable flowing to three calls inside one method, checked in review. The device smoke trace check verifies the outcome (list base equals mapping base).

## Testing Strategy

| Layer | What to test | How | Count |
|---|---|---|---|
| Unit | `toNormalized` scenarios, clamp, exhaustive round trip | `CoordinateNormalizerTest`, plain JVM | ~5 |
| Unit | band: `(540,40)` rejected, `(540,80)` and `(540,1750)` proceed, `(0,0)` rejected; `inAnswerSpace` edges | `CoordinateMapperBoundaryTest` (new), `CoordinateMapperOffTreeTapTest` (two cases rewritten) | ~6 |
| Unit | config: top default 0.035, bottom key absent and retired | `ConfigTest`, `KeyOwnershipTotalityTest`, `RunSpecAbortTest` | ~3 |
| Unit | history: LLM tap entry at its pixel, menu entry without coordinates, `v17` through the formatter | `ApePromptBuilderCoordinateBaseTest` | ~3 |
| Unit | element list: one line per widget, no ordinal, order, `v17` visit sum, `v17` screen line in widgets | `ApePromptBuilderElementListTest` | ~6 |
| Unit | list coordinates per variant on full-screen and dialog geometry; edge-cut widget at its visible centre; off-screen and unresolved nodes render no `@(` per variant; list/history agreement; history bounds → normalized | `ApePromptBuilderCoordinateBaseTest` (new), real `GUITreeNode`/`ModelAction` built as in `ApePromptBuilderIdentifierTest` | ~8 |
| Unit (updated) | existing history and integration tests switched to bounds inputs and production output | `ApePromptBuilderTest`, `PromptIntegrationTest` | existing |
| Regression | decision sequences unchanged under a scripted LLM | `rearch-01` parity golden suite | existing |
| Device | Run on the RVSec AVD against the campaign's server (vLLM v0.29.0, `--enable-auto-tool-choice --tool-call-parser hermes`, no `tool_choice` in the request). It checks the mechanism, not the effect: at about 27 LLM calls per minute (E6: 130,248 answers over 162 runs of 30 min), a 5-minute run yields about 130 calls and 55 copies, too few to measure a change in the copy hit rate. The effect is measured by an arm comparison in the study that adopts the jar. Checks: (a) the list coordinate of each executed widget equals `toNormalized(visibleCentre(bounds, W, H))` with the call's own display, and no off-screen widget carries one; (b) copied answers land inside the widget; (c) `boundary` outcomes of `click`/`long_click` answers occur only at y < 63 or for out-of-space answers. `classify` also labels a `type_text` with no input widget as `boundary`, so those are excluded from this check | smoke run; trace check adapted from `evidence/coordinate_counterfactual.py` (same alignment) | 1 run |

## Open Questions

- **Should `ScreenshotStep` become substitutable so the pipeline is JVM-testable?** That would let INV-RTR-21 be pinned by a unit test instead of review plus smoke. Recommendation: not in this change. It touches the unit-ownership design (`llm-routing` "LLM Unit Lifecycle and Ownership") for a property that is one local variable.
- **Should the top band be derived at runtime** from the status bar height instead of a fraction? Recommendation: not here (D7). A study on another density sets the key.
- **Should list curation be its own change?** The residual misses (Non-Goals) point to it: 5,066 of 6,874 are copies of scroll-only entries, and 23,080 E6 lines (2.1 %) belong to off-screen widgets that this change lists without a coordinate. Index selection is excluded by the author's decision of 2026-07-29, so the remedy stays inside the coordinate design. Recommendation: a separate proposal that drops from the prompt the entries no tool can act on. `evidence/coordinate_counterfactual.py` already classifies these misses, so it can count how many decisions such a change would touch. What the model would choose instead cannot be estimated offline.
- **Should a study that adopts this jar re-derive the copy/visual split?** With one base, `coordinate_base.py`'s "copied" classification becomes exact, which makes the split a cleaner metric than it was in E6. That is a decision for the study, not the jar. The rv-android calibration report's split (14.89 % yield for "exact copies", answers within 3 px of a listed centre, against 31.61 % for visual hits; `rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §12-bis.9) was measured with this defect active. Under it, a copy of a full-screen widget lands more than 3 px from the centre for every centre at y ≥ 21 px, so that bin held almost no copies of full-screen widgets. This change does not rely on that figure.
