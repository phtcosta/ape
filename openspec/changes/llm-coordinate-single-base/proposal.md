## Why

The LLM arm uses the [0,1000) coordinate space in three places, and they disagree about what 1000 means. The element list in the prompt divides a widget's centre by the right and bottom edges of the **active window's root node**. The action history, in the same prompt, divides by the **display** (`Display.getSize`). The model's answer is turned back into a pixel by multiplying by the display, the same rectangle the screenshot is captured at. An answer the model reads off the image lands where the model meant. An answer it **copies from the element list** lands somewhere else.

The bases differ on almost every screen of the campaign emulator, in two ways:

- **Full-screen activity windows.** The DecorView extends behind the navigation bar, so the root's raw bounds end at y=1920. The display reports 1794. A copied coordinate lands above the widget's centre by 6.56 % (1 − 1794/1920) of the centre's distance from the top of the screen: about 12 px near the top, about 106 px near the bottom.
- **Dialogs, popups, and windows the keyboard resized.** The root is the window itself, e.g. `[28,690][1052,1166]`. The list then divides by 1052 × 1166. Both axes are off, and the error reaches hundreds of pixels: a widget centred at y=1144 is listed as 901 and a copy is tapped at y≈1616.

The defect dates from `b2852ddf` (gh6, 2026-03-17). That change's design (`openspec/changes/archive/2026-03-17-gh6-aperv-llm-integration/design.md:277`) planned one base for the prompt and the answer. The router read the display under a comment saying "same as ApePromptBuilder", while the builder read the root. `llm-tap-display-bounds` (2026-07-21) moved the off-tree tap's validity domain to the display, but kept the root as correct for node actions and did not touch the prompt.

**Measured effect.** The Study 03 settings audit (`rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4, script `settings-audit/coordinate_base.py`) quantified it on E6 arm 4, the jar at `e93dea86`, 130,986 LLM decisions:

- 41.1 % of answers are copied from the list.
- Base of the list, per matched decision: full-window 1080 × 1920 in 50,018 (159 APKs), window-sized in 4,802 (149 APKs), display 1080 × 1794 in 74 (11 APKs), unresolved in 4,064 (`coordinate_base.out` at `rvsec-study03-replication-package@d8ec828`).
- Decisions sent to a different target than the model chose: lower bound 1.3 %, central estimate 4.1 %, ceiling 6.0 %. E5b v13_A (the arm E6 inherits): 2.3 % / 7.1 % / 9.3 %.
- In window states, 52.1 % of copied answers that acted became off-tree `llm_tap`s (14.6 % of visual ones), and 13.3 % fell into the boundary reject band (1.7 % of visual ones).

**Scope.** Any fix changes what the LLM arm measures. It applies to future studies only. E6 will not be re-run, and the defect is not reported in the Study 03 paper for now (author's decision, 2026-10-03; the author may revisit it).

The defect went unnoticed for three reasons:

- `ApePromptBuilderTest` always calls `build(null, …)`, so the root read never runs. `ApePromptBuilderIdentifierTest` passes real actions with a null tree and asserts no coordinate.
- `PromptIntegrationTest` re-implements the normalization formula in the test instead of calling production code.
- `ScreenshotStep.deviceDimensions` and the history normalization in `StatefulAgent` have no tests. The `llm-prompt` spec writes `deviceWidth`/`deviceHeight` without naming their source and asserts "the SAME coordinate space the LLM responds in". Nothing checks that assertion.

## What Changes

- **One base, carried from one source.** The `{width, height}` that `ScreenshotStep.deviceDimensions` returns is already used to capture the screenshot and to map the answer. It becomes the only base for the element list and the action history too. `LlmEngine` passes it to `ApePromptBuilder.build`. The builder no longer reads the tree's root node for dimensions. The `GUITree` parameter is removed from `build`, because dimensions were its only use (P3).
- **One normalization primitive.** `CoordinateNormalizer` gains the forward direction, pixel → [0,1000), next to the existing inverse `normalize`. The three inline copies in `ApePromptBuilder` (the shared line formatter used by `ape_current`, `ape_reasoning` and `compact_v1`, plus the `v13` and `v17` builders) and the history computation in `StatefulAgent.recordActionHistory` are deleted. Every [0,1000) value the prompt shows comes from that one primitive.
- **History carries pixels, the builder normalizes.** `ActionHistoryEntry` stores the executed widget's pixel centre instead of a pre-normalized pair. `StatefulAgent` stops calling `AndroidDevice.getDisplayBounds()` for the prompt. The same widget gets the same `@(x,y)` in the list and in the history of one prompt, by construction.
- **BREAKING (experimental comparability):** the list's coordinates change on every screen where the root and the display disagree, which on the campaign emulator is nearly every screen. Prompts are not byte-comparable with E5b/E6. An arm run with this jar is a new arm version, not a re-run of the inherited one.

Not changed: the system message, the variants' layouts, the screenshot path, `CoordinateMapper` (boundary bands, snap tolerance, off-tree tap synthesis), the off-tree tap validity domain, and node-action dispatch.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `llm-prompt`: "Widget List Generation" names the source of `deviceWidth`/`deviceHeight` (the display dimensions `ScreenshotStep` reported for the same decision, never the root node). "Action History" receives pixel centres and normalizes them with the same primitive and dimensions. New invariant: one base per prompt.
- `llm-infrastructure`: "CoordinateNormalizer — Qwen Coordinates to Device Pixels" gains the forward conversion and a round-trip property that ties the two directions together.
- `llm-routing`: "Action Selection Pipeline" states that the dimensions determined in step 2 are the ones the prompt in step 4 and the mapping in step 8 use. The tree is not a dimension source for the prompt.

## Impact

- **Java:** `ape/llm/ApePromptBuilder.java` (`build` signature, three list sites, history formatting), `ape/llm/CoordinateNormalizer.java` (new forward method), `ape/llm/LlmEngine.java` (passes dimensions), `ape/agent/StatefulAgent.java` (`recordActionHistory` stores pixels), `ApePromptBuilder.ActionHistoryEntry` (fields become pixel centres). `ScreenshotStep`, `ScreenshotCapture`, `CoordinateMapper` and `MonkeySourceApe` are untouched.
- **Tests:** `ApePromptBuilderTest`, `ApePromptBuilderIdentifierTest`, `PromptIntegrationTest`, `CoordinateNormalizerTest`, and the pipeline stage tests that build `ActionHistoryEntry` (`LlmNewStateStageTest`, `LlmStagnationStageTest`, `BudgetStageTest`, `DecisionPipelineFromSpecTest`, `oracle/ScriptedLlm`). The `rearch-01` parity golden suite uses a scripted LLM that ignores the prompt text, so its decision sequences must compare identical.
- **rv-android / experiments:** no interface change. Any study that compares an arm run with this jar against E5b/E6 must treat it as a different arm version. Fine-tuning data generated from prompts of earlier jars (branch `laya`, on hold) uses the old bases.
- **Telemetry:** no new field. The `llm[].user` dump changes only in the coordinate values it shows. Analyses that infer the list's base from it (such as `coordinate_base.py`) will see the display base everywhere.
- **Depends on:** nothing pending. Implementation is not scheduled.
