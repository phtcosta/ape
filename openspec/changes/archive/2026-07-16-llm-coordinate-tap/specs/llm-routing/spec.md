## Purpose

This delta extends `llm-routing` so the LLM arm can act on UI elements that are absent from the `GUITree` (the `AccessibilityNodeInfo` tree). When the vision model returns a valid, in-bounds coordinate that lands on an element with no backing accessibility node — game canvases, `SurfaceView`, Compose surfaces without semantics, custom-drawn keyboards — coordinate matching finds no candidate `ModelAction`. Before this change, `mapToModelAction()` returned null, `selectAction()` recorded `no_match`, and the LLM decision was discarded (SATA fallback). This delta makes `mapToModelAction()` synthesize a targetless `MODEL_LLM_TAP` action (`LlmTapAction`) carrying the LLM pixel coordinate, so the tap is dispatched as a raw `MonkeyTouchEvent` and recorded as a model-visible `StateTransition` edge labeled `DecisionSource.LLM`.

The behavior is surgical. It fires only for the off-tree case: an in-bounds, non-degenerate coordinate whose `actionType` is `click` or `long_click` and for which neither bounds-containment nor Euclidean fallback found a widget. Degenerate `(0,0)` emissions and boundary-band (status/nav bar) coordinates continue to return null (`no_match`). `type_text` off-tree also continues to return null, because a raw coordinate has no `EditText` node to receive injected text.

## Invariants

- **INV-RTR-03** (restated): The `ModelAction` returned by `selectAction()` MUST be either (a) a member of the input `actions` list — a widget action matched by coordinate — or (b) a synthesized `LlmTapAction` of type `MODEL_LLM_TAP` constructed for the off-tree case. In case (b) the action is intentionally NOT a member of `actions`; it is a targetless action carrying the LLM pixel coordinate, mirroring how `EVENT_TRIGGER_ACTIVITY` (activity-frontier) returns a synthesized action not drawn from the state's action set. When even the off-tree construction does not apply (degenerate coordinate, boundary reject, `type_text`, or `back` with no back action), `selectAction()` returns null and the LLM coordinates are logged for telemetry.

## MODIFIED Requirements

### Requirement: Action Selection Pipeline

`LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions, String mode)` SHALL return `ModelAction` or `null`. Pipeline:

1. `totalCalls++` — counts this attempt regardless of outcome (per INV-RTR-07).
2. `ScreenshotCapture.capture(deviceWidth, deviceHeight)` → PNG bytes. If null → `breaker.recordFailure()`, log the screenshot failure, return null. A persistent null-capture condition therefore opens the breaker and halts retries for the recovery window.
3. `ImageProcessor.processScreenshot(pngBytes)` → base64 JPEG. If null → return null.
4. `ApePromptBuilder.build(...)` → messages.
5. `SglangClient.chat(messages)` → `ChatResponse`. If IOException → `breaker.recordFailure()`, return null.
6. `ToolCallParser.parse(response)` → `ParsedAction`. If null → `breaker.recordFailure()`, return null.
7. `CoordinateNormalizer.normalize(parsedAction.x, parsedAction.y, deviceWidth, deviceHeight)` → pixel coords.
8. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions, state, deviceWidth, deviceHeight)` → matched widget `ModelAction`, synthesized `LlmTapAction`, or null.
9. `breaker.recordSuccess()`.
10. Outcome classification and return:
    - A **matched widget** `ModelAction` → `result=matched`, return it.
    - A synthesized `LlmTapAction` (type `MODEL_LLM_TAP`, off-tree case) → `result=llm_tap`, increment `llmTapCount`, return it.
    - `null` → `result=no_match`, return null (SATA fallback). The `no_match` telemetry SHALL carry `reason=degenerate` when the parsed coordinate is `(0,0)`, else `reason=boundary`.

**type_text handling**: When `mapToModelAction` finds a matching input widget and `parsedAction.text` is non-null, `selectAction()` calls `match.getResolvedNode().setInputText(text)` before returning. A `type_text` action that matches no input widget returns null (`no_match`) — it is NOT converted to an off-tree tap, because a raw coordinate has no node to receive the text.

**Memory cleanup**: Steps 2-8 SHALL be wrapped in a `try-finally` block that nulls out `pngBytes`, `base64Image`, and `messages`.

**Error behavior**: Any step failure → log warning, record circuit breaker failure for network-related failures AND for a null screenshot, return null (SATA fallback).

#### Scenario: Full pipeline success

- **WHEN** `selectAction()` is called with a valid GUITree and SGLang is responsive
- **AND** the LLM returns `click` at normalized coordinates `(450, 300)`
- **AND** the nearest ModelAction's GUITreeNode bounds contain the pixel coordinates
- **THEN** that ModelAction SHALL be returned
- **AND** the telemetry line SHALL carry `result=matched`
- **AND** `breaker.recordSuccess()` SHALL be called

#### Scenario: Off-tree element becomes a coordinate tap

- **WHEN** the LLM pipeline succeeds with a `click` at in-bounds pixel `(600, 900)` on a 1080x1794 device
- **AND** `mapToModelAction()` finds no widget containing the point and none within Euclidean tolerance
- **THEN** `selectAction()` SHALL return an `LlmTapAction` of type `MODEL_LLM_TAP` carrying `(600, 900)`
- **AND** the telemetry line SHALL carry `result=llm_tap`
- **AND** the `llmTapCount` summary counter SHALL be incremented
- **AND** `breaker.recordSuccess()` SHALL be called

#### Scenario: Degenerate coordinate stays no_match

- **WHEN** the LLM returns `click` and the parsed coordinate is `(0, 0)`
- **THEN** `mapToModelAction()` SHALL return null (boundary reject, `pixelY=0 < deviceHeight * 0.05`)
- **AND** `selectAction()` SHALL return null
- **AND** the telemetry line SHALL carry `result=no_match reason=degenerate`

#### Scenario: Screenshot capture fails trips the breaker

- **WHEN** `ScreenshotCapture.capture()` returns null (secure window)
- **THEN** `selectAction()` SHALL return null immediately
- **AND** no HTTP request SHALL be made
- **AND** `breaker.recordFailure()` SHALL be called

#### Scenario: SGLang timeout

- **WHEN** `SglangClient.chat()` throws IOException due to timeout
- **THEN** `breaker.recordFailure()` SHALL be called
- **AND** `selectAction()` SHALL return null

---

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * 0.05` (status bar, and any degenerate `(0,0)` emission) or `pixelY > deviceHeight * 0.94` (navigation bar), `mapToModelAction` SHALL return null and log the boundary reject. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates.

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"long_click"`, coordinate matching SHALL proceed normally (bounds containment → Euclidean fallback); a matching `MODEL_LONG_CLICK` action SHALL be preferred, falling back to `MODEL_CLICK` when only that is available.
- If `actionType` equals `"type_text"`, only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) SHALL be considered. A match injects text via `action.getResolvedNode().setInputText(text)`. No off-tree tap is synthesized for `type_text`.

**Bounds containment (primary matching strategy)**: For each action where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within `getBoundsInScreen()`. If exactly one contains the point, return it; if several do, return the smallest-area (most specific) widget.

**Euclidean distance (fallback matching)**: If no action's bounds contain the point, return the action whose resolved-node center is nearest to `(pixelX, pixelY)`, provided that distance is within `max(50, min(nodeWidth, nodeHeight) / 2)` pixels.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within Euclidean tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject runs first, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `mapToModelAction(0, 0, "back", null, actions, state, 1080, 1920)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: Click coordinates inside button bounds

- **WHEN** `mapToModelAction(200, 230, "click", null, actions, state, 1080, 1920)` is called
- **AND** action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `mapToModelAction(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no action's bounds contain `(600, 900)`
- **AND** the nearest action's bounds center is `(300, 400)` (distance ~583px, beyond any tolerance)
- **THEN** `mapToModelAction` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`
- **AND** the action's `requireTarget()` SHALL be `false` and `getTarget()` SHALL be `null`

#### Scenario: Off-tree long_click builds a long-press tap

- **WHEN** `mapToModelAction(600, 900, "long_click", null, actions, state, 1080, 1794)` is called
- **AND** no widget contains the point and none is within tolerance
- **THEN** `mapToModelAction` SHALL return an `LlmTapAction` with `longClick=true`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `mapToModelAction(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `mapToModelAction` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: Boundary reject — status bar (no tap synthesized)

- **WHEN** `mapToModelAction(540, 50, "click", null, actions, state, 1080, 1920)` is called
- **AND** `pixelY (50) < deviceHeight * 0.05 (96)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

---

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured per-decision telemetry on an `[APE-LLM-TEL]` line and an aggregate `[APE-RV] LLM Summary` line. The per-decision line SHALL include a `result` field with one of the following values:

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate` (parsed coordinate `(0,0)`) or `reason=boundary` (status/nav band) |

Routing attempts abandoned before the mapping step (null screenshot, HTTP/parse failure) do not emit an `[APE-LLM-TEL]` line; they are counted in the aggregate summary only.

The aggregate summary SHALL report separate counters:
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> null=<N> screenshot_failed=<N> breaker_trips=<N>
```
`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps; it is maintained separately from `matched` and `no_match` so the off-tree effect is countable post-hoc from the summary line alone. `llmTapCount` SHALL be included in the `decisions` denominator used for the `[APE-RV] LLM Decision ratio` line — `decisions = matched + llm_tap + no_match + null` — so that the denominator is stable across this change (an off-tree event formerly counted under `no_match` is now counted under `llm_tap`, both inside `decisions`) and the reported ratio (`matched / decisions`) remains a widget-match rate comparable to pre-change runs.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: Off-tree tap logged

- **WHEN** `selectAction()` returns an `LlmTapAction` at pixel `(600, 900)`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=llm_tap`
- **AND** the summary SHALL count it under `llm_tap=<N>`

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate and another for a `pixelY=1700` navigation-band coordinate on a 1794px-tall device
- **THEN** the first `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=degenerate`
- **AND** the second SHALL carry `result=no_match reason=boundary`
