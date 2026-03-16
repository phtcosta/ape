## Purpose

LLM routing integrates the LLM infrastructure into APE-RV's exploration loop at two specific decision points where SATA's deterministic behavior limits exploration diversity. Rather than replacing SATA or competing with MOP priority scoring, the LLM router operates as a punctual override: when triggered, it captures a screenshot, sends it to the LLM with the current widget list and action history, and directly selects a `ModelAction` to execute. When the LLM is unavailable, times out, returns an unparseable response, or the call budget is exhausted, the router returns null and SATA takes over transparently.

The two modes target different exploration bottlenecks:

1. **New-state mode** (`Config.llmOnNewState`): fires on the first visit to each newly discovered state. The `isNewState` flag is captured in `StatefulAgent.updateStateInternal()` **before** `Graph.markVisited()` to ensure accurate first-visit detection. This is the highest-value intervention point because the LLM sees a screen for the first time and can identify the most promising action using visual understanding that SATA lacks. Cost: ~50-100 calls per 10-minute run.

2. **Stagnation mode** (`Config.llmOnStagnation`): fires when `graphStableCounter` exceeds half the restart threshold (`graphStableRestartThreshold / 2`), indicating the exploration is stagnating but has not yet reached the restart point. This concentrates LLM calls where there is evidence of SATA being stuck, rather than wasting calls probabilistically. This replaces both the original epsilon-LLM mode (wasteful 5% random trigger) and stuck-recovery mode (too late, only at restart threshold). Cost: ~10-30 calls per 10-minute run, concentrated during stagnation periods.

`LlmRouter` owns the lifecycle of all infrastructure components (`SglangClient`, `LlmCircuitBreaker`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `ApePromptBuilder`). It is instantiated once in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null, and the same instance is used for the entire exploration session. It maintains a call counter to enforce the `Config.llmMaxCalls` budget.

---

## Data Contracts

### Input

- `GUITree tree` — current screen's accessibility tree (source: `MonkeySourceApe.nextEventImpl()`)
- `State state` — current abstract state with visit count, activity, and actions (source: `Model`)
- `List<ModelAction> actions` — valid actions on current state, already priority-ranked by SATA+MOP (source: `StatefulAgent.adjustActionsByGUITree()`)
- `MopData mopData` — static analysis reachability data for monitored operations, may be null (source: `StatefulAgent._mopData`)
- `boolean isNewState` — whether this is the first visit to the current state, captured before `markVisited()` (source: `StatefulAgent._isNewState`)
- `int graphStableCounter` — consecutive steps without graph growth (source: `StatefulAgent.checkStable()`)
- `List<ActionHistoryEntry> recentActions` — last 3-5 executed actions with results (source: `StatefulAgent` ring buffer)

### Output

- `LlmActionResult` containing either:
  - A matched `ModelAction` (`isModelAction()=true`) — widget found in UIAutomator dump, tracked by Model
  - A raw click (`isRawClick()=true`) — LLM targets a dynamic element invisible to UIAutomator (WebView, custom view, canvas); executed via MonkeyTouchEvent, NOT tracked by Model, but effect captured in next GUITree cycle
- `null` — LLM pipeline failed, blocked by circuit breaker, or budget exhausted; caller falls back to SATA

### Side-Effects

- **Logging**: `[APE-RV] LLM` prefixed log entries for routing decisions, action selections, and fallbacks
- **Circuit breaker state**: success/failure recorded after each LLM attempt, affecting future routing decisions
- **Call counter**: incremented after each LLM attempt, checked against `Config.llmMaxCalls` budget
- **Network**: HTTP request to SGLang server (via `SglangClient`)
- **Device framebuffer**: screenshot capture (via `ScreenshotCapture`)

### Error

- All errors are handled internally by `LlmRouter.selectAction()` — no exceptions propagate to `StatefulAgent`
- Network errors and unparseable responses trigger `LlmCircuitBreaker.recordFailure()`
- Non-network, non-parse errors (null screenshot) are logged but do not trigger circuit breaker failure

---

## Invariants

- **INV-RTR-01**: `LlmRouter` SHALL only be instantiated when `Config.llmUrl` is non-null. When `Config.llmUrl` is null, `StatefulAgent` SHALL set its `_llmRouter` field to null and all LLM routing checks SHALL be skipped.
- **INV-RTR-02**: `LlmRouter.selectAction()` SHALL never throw an exception to the caller. All failures SHALL result in a null return with a warning log.
- **INV-RTR-03**: When `LlmActionResult.isModelAction()` is true, the `modelAction` MUST be a member of the `actions` list passed as input (i.e., a valid action on the current state). When `isRawClick()` is true (dynamic element not in UIAutomator dump), the raw click is executed via MonkeyTouchEvent at the device pixel coordinates — not tracked by Model, but its effect is captured in the next GUITree cycle.
- **INV-RTR-04**: LLM routing SHALL NOT modify `ModelAction.priority` values. LLM selects an action directly; it does not boost priorities. The MOP scoring pass runs independently before LLM routing.
- **INV-RTR-05**: The 2 LLM modes are independent: disabling one mode SHALL NOT affect the other. Each mode is gated by its own Config flag.
- **INV-RTR-06**: `LlmRouter.selectAction()` SHALL explicitly null out large intermediate objects (`pngBytes`, `base64Image`, `messages`) in a `finally` block to prevent memory pressure from accumulated screenshots.

---

## ADDED Requirements

### Requirement: LlmRouter Lifecycle

`LlmRouter` SHALL be constructed in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null. The constructor SHALL create and wire all infrastructure components:
- `SglangClient` with `Config.llmUrl`, `Config.llmModel`, `Config.llmTemperature`, `Config.llmTopP`, `Config.llmTopK`, maxTokens `1024`, `Config.llmTimeoutMs`
- `LlmCircuitBreaker` with default thresholds (3 failures, 60s recovery)
- `ScreenshotCapture` (no-arg constructor)
- `ImageProcessor` (no-arg constructor)
- `ToolCallParser` (no-arg constructor)
- `CoordinateNormalizer` (static utility, no instantiation)
- `ApePromptBuilder` (no-arg constructor)

All fields SHALL be final. The router instance SHALL be reused for the entire exploration session. A `callCount` field SHALL be initialized to 0 and incremented after each `selectAction()` attempt.

#### Scenario: LLM URL configured

- **WHEN** `Config.llmUrl` equals `"http://10.0.2.2:30000/v1"`
- **THEN** `StatefulAgent` SHALL create a `LlmRouter` instance
- **AND** `_llmRouter` SHALL be non-null for the session

#### Scenario: LLM URL not configured

- **WHEN** `Config.llmUrl` is null
- **THEN** `StatefulAgent` SHALL set `_llmRouter` to null
- **AND** no LLM infrastructure objects SHALL be created

---

### Requirement: New-State LLM Mode

When `Config.llmOnNewState` is `true` and the current state is being visited for the first time, the LLM router SHALL be consulted before the SATA strategy chain in `SataAgent.selectNewActionNonnull()`.

The `isNewState` flag is captured in `StatefulAgent.updateStateInternal()` as `boolean isNewState = (newState.getVisitedCount() == 0)` **before** the call to `getGraph().markVisited(newState, timestamp)`. This ensures accurate first-visit detection despite `markVisited()` incrementing the visit count.

The check SHALL occur after `adjustActionsByGUITree()` has assigned priorities (including MOP boosts) and before `selectNewActionFromBuffer()`.

#### Scenario: First visit to new state with LLM enabled

- **WHEN** `SataAgent.selectNewActionNonnull()` is called
- **AND** `Config.llmOnNewState` is `true`
- **AND** `_isNewState` is `true`
- **AND** `_llmRouter` is non-null
- **AND** `_llmRouter.shouldRouteNewState(true)` returns `true`
- **THEN** `_llmRouter.selectAction(newGUITree, newState, actions, _mopData, recentActions)` SHALL be called
- **AND** if the result is non-null, it SHALL be returned immediately (SATA chain skipped)

#### Scenario: First visit but circuit breaker open

- **WHEN** `_isNewState` is `true`
- **AND** the circuit breaker is OPEN
- **THEN** `shouldRouteNewState(true)` SHALL return `false`
- **AND** the SATA strategy chain SHALL execute normally

#### Scenario: First visit but budget exhausted

- **WHEN** `_isNewState` is `true`
- **AND** `callCount >= Config.llmMaxCalls`
- **THEN** `shouldRouteNewState(true)` SHALL return `false`

#### Scenario: Revisit of known state

- **WHEN** `_isNewState` is `false`
- **THEN** the new-state LLM check SHALL be skipped regardless of other conditions

#### Scenario: LLM returns null on new state

- **WHEN** `selectAction()` returns `null` (LLM failure or unparseable response)
- **THEN** execution SHALL fall through to the SATA strategy chain (buffer → ABA → trivial → greedy)
- **AND** a warning SHALL be logged: `[APE-RV] LLM new-state returned null, falling back to SATA`

---

### Requirement: Stagnation LLM Mode

When `Config.llmOnStagnation` is `true` and `graphStableCounter` exceeds half the restart threshold, the LLM router SHALL be consulted to attempt breaking out of stagnation before the exploration reaches the restart point.

The trigger condition is: `graphStableCounter > Config.graphStableRestartThreshold / 2`. This is evaluated during the stability check phase, earlier than the existing restart mechanism which fires at `counter >= threshold`.

#### Scenario: LLM provides escape action during stagnation

- **WHEN** `graphStableCounter` exceeds `graphStableRestartThreshold / 2`
- **AND** `Config.llmOnStagnation` is `true`
- **AND** `_llmRouter` is non-null and circuit breaker allows and budget not exhausted
- **THEN** `_llmRouter.selectAction(newGUITree, newState, actions, _mopData, recentActions)` SHALL be called
- **AND** if the result is non-null, the action SHALL be used as the next action
- **AND** `graphStableCounter` SHALL be reset to 0 (exploration unblocked)

#### Scenario: LLM fails during stagnation, eventually reaches restart

- **WHEN** `graphStableCounter` exceeds `graphStableRestartThreshold / 2`
- **AND** LLM returns null (failure, timeout, or circuit breaker)
- **THEN** normal stagnation logic continues
- **AND** if `graphStableCounter` eventually reaches `graphStableRestartThreshold`, `requestRestart()` SHALL be called (existing behavior)

#### Scenario: Stagnation mode disabled

- **WHEN** `Config.llmOnStagnation` is `false`
- **AND** `graphStableCounter` exceeds any threshold
- **THEN** the LLM SHALL NOT be consulted
- **AND** the existing restart behavior SHALL proceed unchanged

---

### Requirement: Action Selection Pipeline

`LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions)` SHALL execute the following pipeline:

1. Check `callCount < Config.llmMaxCalls`. If not → return null (budget exhausted).
2. `ScreenshotCapture.capture(deviceWidth, deviceHeight)` → PNG bytes. If null → return null.
3. `ImageProcessor.processScreenshot(pngBytes)` → base64 JPEG. If null → return null.
4. `ApePromptBuilder.build(tree, state, actions, mopData, base64Image, recentActions)` → messages.
5. `SglangClient.chat(messages)` → `ChatResponse`. If IOException → `breaker.recordFailure()`, return null.
6. `ToolCallParser.parse(response)` → `ParsedAction`. If null → `breaker.recordFailure()`, return null.
7. `CoordinateNormalizer.normalize(parsedAction.x, parsedAction.y, deviceWidth, deviceHeight)` → pixel coords.
8. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions)` → ModelAction|null.
9. `breaker.recordSuccess()`, `callCount++`.
10. If ModelAction found → log `[APE-RV] LLM selected: <action>`, return `LlmActionResult(modelAction, pixelX, pixelY, actionType, text)`.
11. If no match (dynamic element) → log `[APE-RV] LLM raw click at (<pixelX>,<pixelY>)`, return `LlmActionResult(null, pixelX, pixelY, actionType, text)` — caller executes raw click via MonkeyTouchEvent.

**Memory cleanup**: Steps 2-8 SHALL be wrapped in a `try-finally` block that nulls out `pngBytes`, `base64Image`, and `messages` to prevent memory pressure.

**Error behavior**: Any step failure → log warning, record circuit breaker failure if network-related, return null (SATA fallback).

#### Scenario: Full pipeline success

- **WHEN** `selectAction()` is called with a valid GUITree and SGLang is responsive
- **AND** the LLM returns `click` at normalized coordinates `(450, 300)`
- **AND** the nearest ModelAction's GUITreeNode bounds contain the normalized pixel coordinates
- **THEN** that ModelAction SHALL be returned
- **AND** `breaker.recordSuccess()` SHALL be called
- **AND** `callCount` SHALL be incremented

#### Scenario: Screenshot capture fails

- **WHEN** `ScreenshotCapture.capture()` returns null
- **THEN** `selectAction()` SHALL return null immediately
- **AND** no HTTP request SHALL be made
- **AND** circuit breaker SHALL NOT be affected (not a network error)

#### Scenario: SGLang timeout

- **WHEN** `SglangClient.chat()` throws IOException due to timeout
- **THEN** `breaker.recordFailure()` SHALL be called
- **AND** `selectAction()` SHALL return null

#### Scenario: Budget exhausted

- **WHEN** `callCount` equals `Config.llmMaxCalls`
- **THEN** `selectAction()` SHALL return null immediately without capturing screenshot
- **AND** a log SHALL be emitted: `[APE-RV] LLM budget exhausted (N/N calls)`

---

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions)` SHALL map LLM output coordinates to the nearest valid `ModelAction` in the current state.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * 0.05` (status bar) or `pixelY > deviceHeight * 0.94` (navigation bar), `mapToModelAction` SHALL return null and log `[APE-RV] LLM click in system UI, rejecting`. This prevents the LLM from wasting budget on system UI elements.

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"long_click"`, coordinate matching SHALL proceed normally (bounds containment → Euclidean fallback), but if a matching widget has a MODEL_LONG_CLICK action available, that action SHALL be preferred. If only MODEL_CLICK is available, it SHALL be returned as fallback.
- If `actionType` equals `"type_text"`, only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) SHALL be considered for matching. When a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**Bounds containment (primary matching strategy)**: For each action where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Euclidean distance (fallback matching)**: If no action's bounds contain the point, compute the Euclidean distance from `(pixelX, pixelY)` to the center of each valid action's resolved node bounds. Return the action with the minimum distance if that distance is within a proportional tolerance: `max(50, min(nodeWidth, nodeHeight) / 2)` pixels. This ensures larger widgets accept more coordinate imprecision.

**No match (dynamic element)**: If no action's bounds contain the point AND no action is within Euclidean tolerance, return null from `mapToModelAction`. However, `selectAction()` SHALL still return a valid `LlmActionResult` with `isRawClick()=true` — the LLM likely targeted a dynamic element (WebView content, custom view, canvas) invisible to UIAutomator. The caller SHALL execute a raw MonkeyTouchEvent at the device pixel coordinates. The effect (screen change) is captured in the next GUITree cycle.

#### Scenario: LLM says "back"

- **WHEN** `mapToModelAction(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: Click coordinates inside button bounds

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: Click near a button (fallback to Euclidean distance)

- **WHEN** `mapToModelAction(310, 230, "click", null, actions)` is called
- **AND** no action's bounds contain `(310, 230)`
- **AND** action A has bounds center at `(200, 225)`, node size 200x50, tolerance = max(50, 25) = 50px, distance ~112px
- **AND** action B has bounds center at `(320, 240)`, node size 100x40, tolerance = max(50, 20) = 50px, distance ~14px
- **THEN** action B SHALL be returned (within tolerance, closest)

#### Scenario: No action within tolerance (dynamic element)

- **WHEN** `mapToModelAction(50, 50, "click", null, actions)` is called
- **AND** the nearest action's bounds center is at `(300, 400)` (distance ~420px, well beyond any tolerance)
- **THEN** `mapToModelAction` SHALL return `null`
- **AND** `selectAction()` SHALL return `LlmActionResult(null, 50, 50, "click", null)` with `isRawClick()=true`
- **AND** the caller SHALL execute a raw MonkeyTouchEvent at device pixel (50, 50)

#### Scenario: type_text targets EditText

- **WHEN** `mapToModelAction(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")` to inject the text

#### Scenario: long_click targets widget

- **WHEN** `mapToModelAction(200, 230, "long_click", null, actions)` is called
- **AND** action A has resolved node bounds `[100, 200, 300, 250]` (contains point) with actionType MODEL_LONG_CLICK
- **AND** action B has bounds `[100, 200, 300, 250]` (same widget) with actionType MODEL_CLICK
- **THEN** action A (MODEL_LONG_CLICK) SHALL be returned (preferred over MODEL_CLICK)

#### Scenario: Boundary reject — status bar

- **WHEN** `mapToModelAction(540, 50, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * 0.05 (96)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** a log SHALL be emitted: `[APE-RV] LLM click in system UI, rejecting`

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * 0.94 (1804.8)`
- **THEN** `mapToModelAction` SHALL return null

---

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured telemetry for each LLM routing decision using the `[APE-RV] LLM` prefix. The following events SHALL be logged:

**Per-call structured log** (parseable, follows `[RVTRACK:LLM]` pattern from rvsmart/rvagent):
```
[APE-RV] LLM iter=<N> mode=<mode> tokens_in=<N> tokens_out=<N> time_ms=<N> result=<result> [widget=<id>|coords=(<x>,<y>)]
```

| Field | Values |
|-------|--------|
| `mode` | `new-state`, `stagnation` |
| `result` | `model_action`, `raw_click`, `null`, `timeout`, `breaker_open`, `budget_exhausted`, `parse_failed` |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Budget exhausted | `[APE-RV] LLM budget exhausted (<callCount>/<maxCalls> calls)` |
| Pipeline step failed | `[APE-RV] LLM <step> failed: <reason>` |

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary: calls=<N>/<budget> tokens_in=<N> tokens_out=<N> time_ms=<N> avg_ms=<N> model=<N> raw=<N> null=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

#### Scenario: Successful action logged

- **WHEN** `selectAction()` returns a non-null ModelAction of type `MODEL_CLICK` targeting widget `btn_encrypt`
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM selected: MODEL_CLICK on btn_encrypt at (540, 960)`

#### Scenario: Circuit breaker event logged

- **WHEN** `shouldRouteNewState()` is called but the circuit breaker is OPEN with 2 trips
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM circuit breaker OPEN, skipping (trips=2)`

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200`
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM mode=stagnation, state=MainActivity#abc123`
