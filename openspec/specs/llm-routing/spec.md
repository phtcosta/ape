## Purpose

LLM routing integrates the LLM infrastructure into APE-RV's exploration loop at two specific decision points where SATA's deterministic behavior limits exploration diversity. Rather than replacing SATA or competing with MOP priority scoring, the LLM router operates as a punctual override: when triggered, it captures a screenshot, sends it to the LLM with the current widget list and action history, and directly selects a `ModelAction` to execute. When the LLM is unavailable, times out, or returns an unparseable response, the router returns null and SATA takes over transparently.

The two modes target different exploration bottlenecks:

1. **New-state mode** (`Config.llmOnNewState`): fires on the first visit to each newly discovered state. The `isNewState` flag is captured in `StatefulAgent.updateStateInternal()` **before** `Graph.markVisited()` to ensure accurate first-visit detection. This is the highest-value intervention point because the LLM sees a screen for the first time and can identify the most promising action using visual understanding that SATA lacks. Cost: ~50-100 calls per 10-minute run.

2. **Stagnation mode** (`Config.llmOnStagnation`): checked in `SataAgent.selectNewActionNonnull()` when `graphStableCounter` **equals** half the restart threshold (`graphStableRestartThreshold / 2`), providing exactly one LLM attempt per stagnation phase. The equality check (not greater-than) ensures the hook fires once when the counter hits the midpoint, then never again during that phase — no flags or extra state needed. The `graphStableCounter` is a `protected` field in `StatefulAgent`, increments by 1 each step via `checkStable()`, and resets to 0 on new state discovery. Cost: ~5-15 calls per 10-minute run (one per stagnation phase). Note: `StatefulAgent.onGraphStable()` still handles the restart logic at `counter > threshold` — the LLM hook does NOT modify the restart mechanism.

`LlmRouter` owns the lifecycle of all infrastructure components (`SglangClient`, `LlmCircuitBreaker`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `ApePromptBuilder`). It is instantiated once in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null, and the same instance is used for the entire exploration session. There is no call budget limit — only timeout controls execution duration.

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

- `ModelAction` — matched action from the input actions list, ready to execute (for type_text actions, `resolvedNode.setInputText(text)` is already called before returning)
- `null` — LLM pipeline failed, no matching action found, or blocked by circuit breaker; caller falls back to SATA

### Side-Effects

- **Logging**: `[APE-RV] LLM` prefixed log entries for routing decisions, action selections, and fallbacks
- **Circuit breaker state**: success/failure recorded after each LLM attempt, affecting future routing decisions
- **Call counter**: `totalCalls` incremented at each `selectAction()` invocation for telemetry (no budget limit)
- **Network**: HTTP request to SGLang server (via `SglangClient`)
- **Device framebuffer**: screenshot capture (via `ScreenshotCapture`)

### Error

- All errors are handled internally by `LlmRouter.selectAction()` — no exceptions propagate to `StatefulAgent`.
- Network errors, unparseable responses, AND a null screenshot SHALL trigger `LlmCircuitBreaker.recordFailure()`. A persistent null-screenshot condition therefore opens the breaker and stops further LLM attempts for the recovery window.

---

## Invariants

- **INV-RTR-01**: `LlmRouter` SHALL only be instantiated when `Config.llmUrl` is non-null. When `Config.llmUrl` is null, `StatefulAgent` SHALL set its `_llmRouter` field to null and all LLM routing checks SHALL be skipped.
- **INV-RTR-02**: `LlmRouter.selectAction()` SHALL never throw an exception to the caller. All failures SHALL result in a null return with a warning log.
- **INV-RTR-03**: The `ModelAction` returned by `selectAction()` MUST be either (a) a member of the input `actions` list — a widget action matched by coordinate — or (b) a synthesized `LlmTapAction` of type `MODEL_LLM_TAP` constructed for the off-tree case. In case (b) the action is intentionally NOT a member of `actions`; it is a targetless action carrying the LLM pixel coordinate, mirroring how `EVENT_TRIGGER_ACTIVITY` (activity-frontier) returns a synthesized action not drawn from the state's action set. When even the off-tree construction does not apply (degenerate coordinate, boundary reject, `type_text`, or `back` with no back action), `selectAction()` returns null and the LLM coordinates are logged for telemetry.
- **INV-RTR-04**: LLM routing SHALL NOT modify `ModelAction.priority` values. LLM selects an action directly; it does not boost priorities. The MOP scoring pass runs independently before LLM routing.
- **INV-RTR-05**: The 2 LLM modes are independent: disabling one mode SHALL NOT affect the other. Each mode is gated by its own Config flag.
- **INV-RTR-06**: `LlmRouter.selectAction()` SHALL explicitly null out large intermediate objects (`pngBytes`, `base64Image`, `messages`) in a `finally` block to prevent memory pressure from accumulated screenshots.
- **INV-RTR-07**: `totalCalls` SHALL be incremented at the start of each `selectAction()` invocation, counting all attempts regardless of success or failure. The circuit breaker independently limits consecutive failures. There is no call budget limit.
- **INV-RTR-08**: `Config.llmPercentage` SHALL be clamped to the closed interval `[0.0, 1.0]` at load time. A configured value `< 0` SHALL become `0.0`; a value `> 1` SHALL become `1.0`.
- **INV-RTR-10**: The `[APE-LLM-CONFIG]` line SHALL be emitted exactly once per run, at `LlmRouter` construction, and SHALL report the effective sampling parameters (the same values placed into the request body by `SglangClient.buildRequestBody`), not the `ape.properties` map contents. When `Config.llmUrl` is null the `LlmRouter` is not constructed (INV-RTR-01) and no `[APE-LLM-CONFIG]` line is emitted.
- **INV-RTR-11**: The sum `timeoutCount + httpErrorCount + connErrorCount + parseErrorCount + imageErrorCount + internalErrorCount + screenshotFailedCount` SHALL equal the number of `selectAction()` attempts abandoned before the mapping step — the attempts that return null **without** emitting an `[APE-LLM-TEL]` line. Exactly one cause counter SHALL be incremented per abandoned attempt (none uncounted, none double-counted). `no_match` outcomes also return null from `selectAction()` but emit an `[APE-LLM-TEL]` line and are counted by `noMatchCount` only; they are outside this sum.
- **INV-RTR-12**: The `[APE-LLM-CONFIG-ACK]` line SHALL be emitted at most once per run, only after a successful `chat()` response. A run with zero successful responses SHALL emit no ACK line (its absence, combined with the failure-cause counters, is itself diagnostic).

---
## Requirements
### Requirement: LlmRouter Lifecycle

`LlmRouter` SHALL be constructed in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null. The constructor SHALL create and wire all infrastructure components:
- `SglangClient` with `Config.llmUrl`, `Config.llmModel`, `Config.llmTemperature`, `Config.llmTopP`, `Config.llmTopK`, maxTokens `1024`, `Config.llmTimeoutMs`
- `LlmCircuitBreaker` with default thresholds (3 failures, 60s recovery)
- `ScreenshotCapture` (no-arg constructor)
- `ImageProcessor` (no-arg constructor)
- `ToolCallParser` (no-arg constructor)
- `CoordinateNormalizer` (static utility, no instantiation)
- `ApePromptBuilder` (no-arg constructor)

All fields SHALL be final. The router instance SHALL be reused for the entire exploration session. A `totalCalls` field SHALL be initialized to 0 and incremented at the start of each `selectAction()` invocation (per INV-RTR-07).

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

#### Scenario: Revisit of known state

- **WHEN** `_isNewState` is `false`
- **THEN** the new-state LLM check SHALL be skipped regardless of other conditions

#### Scenario: LLM returns null on new state

- **WHEN** `selectAction()` returns `null` (LLM failure or unparseable response)
- **THEN** execution SHALL fall through to the SATA strategy chain (buffer → ABA → trivial → greedy)
- **AND** a warning SHALL be logged: `[APE-RV] LLM new-state returned null, falling back to SATA`

---

### Requirement: Stagnation LLM Mode

When `Config.llmOnStagnation` is `true` and `graphStableCounter` **equals** half the restart threshold, the LLM router SHALL be consulted exactly once to attempt breaking out of stagnation.

The trigger condition is: `graphStableCounter == Config.graphStableRestartThreshold / 2` (equality). Since the counter increments by 1 each step, equality is always reached. The hook fires once at the midpoint; on the next step the counter is midpoint+1 and the condition is false. No flags or extra state needed.

#### Scenario: LLM provides escape action at stagnation midpoint

- **WHEN** `graphStableCounter` equals `graphStableRestartThreshold / 2`
- **AND** `Config.llmOnStagnation` is `true`
- **AND** `_llmRouter` is non-null and circuit breaker allows
- **THEN** `_llmRouter.selectAction(...)` SHALL be called
- **AND** if the result is non-null, the action SHALL be used
- **AND** `graphStableCounter` SHALL be reset to 0 (exploration unblocked)

#### Scenario: LLM fails at midpoint, stagnation continues to restart

- **WHEN** `graphStableCounter` equals `graphStableRestartThreshold / 2`
- **AND** LLM returns null (failure, timeout, or circuit breaker)
- **THEN** counter continues incrementing (midpoint+1, midpoint+2, ...)
- **AND** the stagnation hook SHALL NOT fire again (equality no longer holds)
- **AND** if `graphStableCounter` eventually reaches `graphStableRestartThreshold`, `requestRestart()` SHALL be called (existing behavior)

#### Scenario: Stagnation mode disabled

- **WHEN** `Config.llmOnStagnation` is `false`
- **THEN** the LLM SHALL NOT be consulted regardless of counter value
- **AND** the existing restart behavior SHALL proceed unchanged

---

### Requirement: Action Selection Pipeline

`LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions)` SHALL return `ModelAction` or `null`. Pipeline:

1. `totalCalls++` — counts this attempt regardless of outcome (per INV-RTR-07).
2. `ScreenshotCapture.capture(deviceWidth, deviceHeight)` → PNG bytes. If null → `breaker.recordFailure()`, log `[APE-RV] LLM screenshot capture failed, skipping LLM step`, return null. A persistent null-capture condition therefore opens the breaker and halts retries for the recovery window.
4. `ImageProcessor.processScreenshot(pngBytes)` → base64 JPEG. If null → return null.
5. `ApePromptBuilder.build(tree, state, actions, mopData, base64Image, recentActions)` → messages.
6. `SglangClient.chat(messages)` → `ChatResponse`. If IOException → `breaker.recordFailure()`, return null.
7. `ToolCallParser.parse(response)` → `ParsedAction`. If null → `breaker.recordFailure()`, return null.
8. `CoordinateNormalizer.normalize(parsedAction.x, parsedAction.y, deviceWidth, deviceHeight)` → pixel coords.
9. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions, state, deviceWidth, deviceHeight)` → matched widget `ModelAction`, synthesized `LlmTapAction`, or null.
10. `breaker.recordSuccess()`.
11. Outcome classification and return:
    - A **matched widget** `ModelAction` → `result=matched`, return it.
    - A synthesized `LlmTapAction` (type `MODEL_LLM_TAP`, off-tree case) → `result=llm_tap`, increment `llmTapCount`, return it.
    - `null` → `result=no_match`, return null (SATA fallback). The `no_match` telemetry SHALL carry `reason=degenerate` when the parsed coordinate is `(0,0)`, else `reason=boundary`.

**type_text handling**: When `mapToModelAction` finds a matching input widget and `parsedAction.text` is non-null, `selectAction()` calls `match.getResolvedNode().setInputText(text)` before returning. The caller receives a ready-to-execute ModelAction. A `type_text` action that matches no input widget returns null (`no_match`) — it is NOT converted to an off-tree tap, because a raw coordinate has no node to receive the text.

**Memory cleanup**: Steps 3-9 SHALL be wrapped in a `try-finally` block that nulls out `pngBytes`, `base64Image`, and `messages`.

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
- **AND** after the breaker's failure threshold of consecutive null captures, `shouldRoute*()` SHALL return false (breaker OPEN), stopping per-step retries

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
- If `actionType` equals `"long_click"`, coordinate matching SHALL proceed normally (bounds containment → Euclidean fallback), but if a matching widget has a MODEL_LONG_CLICK action available, that action SHALL be preferred. If only MODEL_CLICK is available, it SHALL be returned as fallback.
- If `actionType` equals `"type_text"`, only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) SHALL be considered for matching. When a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**Bounds containment (primary matching strategy)**: For each action where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Euclidean distance (fallback matching)**: If no action's bounds contain the point, compute the Euclidean distance from `(pixelX, pixelY)` to the center of each valid action's resolved node bounds. Return the action with the minimum distance if that distance is within a proportional tolerance: `max(50, min(nodeWidth, nodeHeight) / 2)` pixels. This ensures larger widgets accept more coordinate imprecision.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within Euclidean tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject runs first, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

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

- **WHEN** `mapToModelAction(540, 50, "click", null, actions, state, 1080, 1920)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * 0.05 (96)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed
- **AND** the boundary reject SHALL be logged

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * 0.94 (1804.8)`
- **THEN** `mapToModelAction` SHALL return null

---

### Requirement: Effective LLM Config Manifest

`LlmRouter` SHALL emit exactly one `[APE-LLM-CONFIG]` line at construction time, recording the **effective** LLM configuration actually in use for the run. The values SHALL be read from the parameters passed to the `SglangClient` / router (the effective values), NOT from the `ape.properties` `configurations` map. The config dump (`Config.printConfigurations`) is insufficient for arm attribution on three grounds: it echoes the raw property string rather than the effective value (e.g. `llmPercentage` is clamped to `[0,1]` in its field initializer — the dump would show `1.5`, the manifest shows the effective `1.0`), it runs only at `tearDown()` and is lost when the platform kills the process at the time limit, and the router-hardcoded `max_tokens` never appears in it at all. The manifest line makes each run's `.trace` self-describing from its first seconds, independent of how the run ends.

The line SHALL carry the following fields:

| Field | Source |
|-------|--------|
| `model` | effective model name passed to `SglangClient` |
| `temperature` | effective `temperature` |
| `top_p` | effective `top_p` |
| `top_k` | effective `top_k` |
| `max_tokens` | effective `max_tokens` (currently the router-set `1024`, not a `Config` key) |
| `timeout_ms` | effective `Config.llmTimeoutMs` passed to `SglangClient` (connect and read timeout; shapes the `timeout` failure-cause rate across arms) |
| `prompt_variant` | `ApePromptBuilder.getPromptVariant()` |
| `llm_percentage` | `Config.llmPercentage` (post-clamp, per INV-RTR-08; the clamp lives in the `Config` field initializer, so any read is post-clamp) |
| `on_new_state` | `Config.llmOnNewState` |
| `on_stagnation` | `Config.llmOnStagnation` |
| `stagnation_threshold` | `Config.graphStableRestartThreshold` (the stagnation hook fires at `threshold / 2`; config-settable, gates stagnation-mode LLM call frequency) |
| `url` | `Config.llmUrl` |

Emission is governed by INV-RTR-10.

#### Scenario: Manifest records defaults not present in ape.properties

- **WHEN** a run starts with `ape.llmUrl` set but `ape.llmTemperature` / `ape.llmTopP` / `ape.llmTopK` left at their code defaults
- **THEN** exactly one `[APE-LLM-CONFIG]` line SHALL be emitted
- **AND** it SHALL carry the effective default values (e.g. `temperature=0.3`)
- **AND** it SHALL carry `max_tokens=1024` even though `max_tokens` is not an `ape.properties` key

#### Scenario: Manifest records effective clamped percentage

- **WHEN** a run starts with `ape.llmPercentage=1.5` in `ape.properties`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `llm_percentage=1.0` (the effective post-clamp value), even though the config dump would echo the raw `1.5`

---

### Requirement: Server Model Acknowledgement

`LlmRouter` SHALL emit one `[APE-LLM-CONFIG-ACK] server_model=<model>` line after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `[APE-LLM-CONFIG]` manifest records the model the run *requested*; the ACK records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server. Emission is governed by INV-RTR-12.

#### Scenario: Server model acknowledged once

- **WHEN** the first successful `chat()` response of a run reports `model=qwen3-vl-8b` and 40 further successful calls follow
- **THEN** exactly one `[APE-LLM-CONFIG-ACK] server_model=qwen3-vl-8b` line SHALL be emitted, after the first successful response

#### Scenario: No acknowledgement without a successful response

- **WHEN** every `chat()` call of a run fails (server down)
- **THEN** zero `[APE-LLM-CONFIG-ACK]` lines SHALL be emitted

---

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured per-decision telemetry on an `[APE-LLM-TEL]` line and an aggregate `[APE-RV] LLM Summary` line.

**Per-decision structured log** (parseable, follows the `[RVTRACK:LLM]` pattern from rvsmart/rvagent):

| Field | Values |
|-------|--------|
| `step` | agent exploration step of the routing attempt — the same value the step's `[APE-STEP]` line carries, supplied by the calling agent (`getTimestamp()` at selection) |
| `mode` | `new-state`, `stagnation`, `random` |
| `result` | `matched`, `llm_tap`, `no_match` |
| `reason` | emitted only on `no_match`, immediately after `result=`: `degenerate` (parsed coordinate `(0,0)`) or `boundary` (status/nav band) |
| `repair` | emitted only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse. Records that the model's tool call needed a pre-parse repair to become executable, keeping raw tool-call fidelity measurable after the parser is hardened |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

The `step` field makes the LLM call telemetry joinable to the decision and its outcome on one key: `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` (the latter per the scoring-pipeline capability). The value is passed into `selectAction()` by the caller; `LlmRouter` does not read agent state itself.

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate` or `reason=boundary` |

The `repair` field is orthogonal to `result`: a repaired tool call yields a normal `matched`, `llm_tap`, or `no_match` outcome and additionally carries `repair=<form>`. It marks a fidelity property of the model's output (the tool call was malformed but recoverable), not the routing outcome.

Routing attempts abandoned before the mapping step (null screenshot, image-processing failure, HTTP/timeout/connection failure, parse failure, unexpected internal error) do not emit an `[APE-LLM-TEL]` line; they are counted in the aggregate summary only. Each such abandoned attempt SHALL additionally emit one `[APE-LLM-ERROR]` line naming its cause — except the screenshot-capture failure, which keeps its existing `[APE-RV] LLM screenshot failed` line (Action Selection Pipeline requirement) and is counted by `screenshot_failed` only, with no `[APE-LLM-ERROR]` line.

**Discriminated failure causes:** the previously-collapsed `null` outcome SHALL be attributed to exactly one named cause. `LlmRouter` SHALL maintain a separate counter per cause in place of the single opaque `nullCount`:

| Cause | Meaning | Counter |
|-------|---------|---------|
| `timeout` | connect/read timeout (`SocketTimeoutException`) | `timeoutCount` |
| `http_<status>` | server returned a non-200 HTTP status | `httpErrorCount` |
| `connection` | other I/O failure reaching the server | `connErrorCount` |
| `parse` | response received but unusable: OpenAI envelope not parseable (client-side) or no tool call extractable from a successful response (router-side) | `parseErrorCount` |
| `image` | `ImageProcessor` returned null while preparing the screenshot payload | `imageErrorCount` |
| `internal` | unexpected exception caught by the `selectAction()` catch-all | `internalErrorCount` |
| `screenshot` | `ScreenshotCapture` returned null (e.g. FLAG_SECURE) | `screenshotFailedCount` (unchanged) |

A `parse` failure is now the residual after `ToolCallParser` recovery: it is counted only when `parse()` returns null despite the added quoted-collapsed-XY fix and last-resort integer extraction (per the `llm-infrastructure` capability). A recovered tool call is not a `parse` failure — it is a successful decision carrying `repair=<form>`.

**Cause attribution SHALL follow the failure point.** When `chat()` returns null, the cause SHALL be read from `SglangClient.getLastErrorCause()` — the only site where that seam MAY be consulted (the client resets it per invocation, per the `llm-infrastructure` capability; at any other site its value belongs to an earlier call and is stale). Failures occurring after `chat()` returns non-null SHALL be attributed by `LlmRouter` directly, without consulting `getLastErrorCause()`: tool-call extraction failure (`ToolCallParser.parse()` returning null) is `parse`, an `ImageProcessor` null result is `image`, and an unexpected exception in the routing pipeline is `internal`.

At the failure point, `LlmRouter` SHALL emit `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>`, where `step` is the same join key carried by `[APE-LLM-TEL]` — a failed attempt is thereby attributable to the step whose selection it interrupted. The `null` return contract (INV-RTR-02) is unchanged; only the attribution is added.

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>` |

The circuit-breaker-blocked line SHALL be emitted at the **first** routing-predicate decline of each open episode (the moment a predicate declines a call because the breaker does not allow the attempt); subsequent declines within the same open episode SHALL NOT re-emit it (at `llmPercentage=0.7` a 60s open window would otherwise produce tens of identical lines). The emission check SHALL use a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` call — `shouldAttempt()` carries the OPEN→HALF_OPEN probe transition — and SHALL distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached).

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> repaired=<N> timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

The aggregate `null` field is replaced by the named cause counters. The `decisions` denominator for the `[APE-RV] LLM Decision ratio` line SHALL be `matched + llm_tap + no_match + (timeout + http_error + conn_error + parse_error + image_error + internal_error + screenshot_failed)`. This is identical in value to the pre-change `matched + llm_tap + no_match + null`: the retired `nullCount` was incremented on every abandoned attempt including screenshot failures (`screenshot_failed` was a subset of `null`); under the new scheme a screenshot failure increments `screenshotFailedCount` only, so the seven cause counters form a partition of the retired `null` count and the reported ratio (`matched / decisions`) is unchanged in meaning.

`repaired=<N>` (`repairedCount`) counts successful decisions whose tool call required a `ToolCallParser` repair (repair-form label other than `none`). It is NOT a cause counter and is NOT part of the `decisions` denominator: a repaired decision is already counted under exactly one of `matched` / `llm_tap` / `no_match`. It is a subset overlay on those outcomes, maintained separately so the base-versus-v2 tool-call fidelity contrast — how often the model emitted a malformed-but-recoverable call — is countable post-hoc from the summary line alone.

`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps; it is maintained separately from `matched` and `no_match` so the off-tree effect is countable post-hoc from the summary line alone.

`screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null (e.g. FLAG_SECURE windows). It is a peer cause counter (no longer a subset of an aggregate `null`), maintained separately so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=42`
- **AND** the `[APE-STEP]` and `[APE-OUTCOME]` lines of the same decision SHALL carry `step=42`, so call cost (`tokens_in/out`, `time_ms`), decision, and outcome join without timestamp reconstruction

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the resulting coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=61 result=matched repair=quoted_xy`
- **AND** the aggregate summary SHALL count the decision under both `matched=<N>` and `repaired=<N>`
- **AND** no `[APE-LLM-ERROR] cause=parse` line SHALL be emitted for step 61

#### Scenario: Clean tool call omits the repair field

- **WHEN** at step 62 the model returns a well-formed `{"name": "click", "arguments": {"x": 540, "y": 399}}` that resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL NOT carry a `repair` field
- **AND** the decision SHALL NOT increment `repairedCount`

#### Scenario: Off-tree tap logged

- **WHEN** `selectAction()` returns an `LlmTapAction` at pixel `(600, 900)`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=llm_tap`
- **AND** the summary SHALL count it under `llm_tap=<N>`

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate and another for a `pixelY=1700` navigation-band coordinate on a 1794px-tall device
- **THEN** the first `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=degenerate`
- **AND** the second SHALL carry `result=no_match reason=boundary`

#### Scenario: Timeout and HTTP failure discriminated

- **WHEN** one routing attempt fails because the read times out and a later attempt fails because the server returns HTTP 500
- **THEN** the first SHALL emit `[APE-LLM-ERROR] cause=timeout ...` and increment `timeoutCount`
- **AND** the second SHALL emit `[APE-LLM-ERROR] cause=http_500 ...` and increment `httpErrorCount`
- **AND** the summary SHALL report `timeout=1 http_error=1` (not a single `null=2`)

#### Scenario: Router-side parse failure attributed without the client seam

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** `[APE-LLM-ERROR] cause=parse ...` SHALL be emitted and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)
- **AND** no `repair` field SHALL be emitted (there was no successful decision to annotate)

#### Scenario: Circuit breaker event logged once per open episode

- **WHEN** the breaker trips to OPEN with 2 trips recorded and routing predicates subsequently decline 5 calls during the same open window
- **THEN** exactly one `[APE-RV] LLM circuit breaker OPEN, skipping (trips=2)` line SHALL be emitted, at the first declined call
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200`
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM mode=stagnation, state=MainActivity#abc123`

#### Scenario: Screenshot failures counted separately

- **WHEN** a run ends after 5 LLM routing attempts of which 3 were abandoned at screenshot capture (secure window) and 1 failed at parse
- **THEN** the summary line SHALL report `parse_error=1 screenshot_failed=3`
- **AND** the screenshot failures SHALL NOT have emitted `[APE-LLM-ERROR]` lines (their existing `[APE-RV] LLM screenshot failed` line stands)

### Requirement: Probabilistic LLM Routing

`LlmRouter.shouldRouteRandom()` SHALL return `true` when `random.nextDouble() < Config.llmPercentage`, where `random` is the Monkey-seeded `java.util.Random` instance injected via the LlmRouter constructor. This ensures reproducible coin flips when the `--seed` CLI flag is set. The method is also subject to the same guard as existing routing predicates: circuit breaker must allow attempts.

When `Config.llmPercentage` is `0.0`, the method SHALL always return `false` (short-circuit, no random call).

`SataAgent.selectNewActionNonnull()` SHALL check `shouldRouteRandom()` after the stagnation hook and before SATA algorithmic strategies. The check SHALL use the same guard conditions as existing hooks: `actionBufferSize() == 0` AND `newState.getActions().size() > 2`.

When the random hook fires and LLM returns a non-null action, the telemetry mode label SHALL be `"random"`.

#### Scenario: Default 2% routing

- **WHEN** `Config.llmPercentage` is `0.02` (default)
- **AND** neither new-state nor stagnation triggered on this step
- **AND** `random.nextDouble()` returns a value < 0.02
- **AND** the circuit breaker allows attempts
- **AND** circuit breaker allows
- **THEN** `shouldRouteRandom()` SHALL return `true`
- **AND** the LLM call SHALL use mode `"random"` for telemetry

#### Scenario: Disabled

- **WHEN** `Config.llmPercentage` is `0.0`
- **THEN** `shouldRouteRandom()` SHALL always return `false`

#### Scenario: High percentage (70%)

- **WHEN** `Config.llmPercentage` is `0.7`
- **AND** neither new-state nor stagnation triggered
- **THEN** `shouldRouteRandom()` SHALL return `true` approximately 70% of the time

#### Scenario: Priority order preserved

- **WHEN** `isNewState` is `true` and `Config.llmOnNewState` is `true`
- **AND** `Config.llmPercentage` is `0.7`
- **THEN** the new-state hook SHALL fire (not the random hook)
- **AND** only one LLM call SHALL be made for that step

---

### Requirement: Config — llmPercentage clamping

`Config.llmPercentage` SHALL be clamped to `[0.0, 1.0]` at load (`Config.java:153`). This rejects malformed probabilities: a value `> 1.0` would otherwise make `shouldRouteRandom()` always fire (since `random.nextDouble() < 1.5` is always true), and a negative value is meaningless.

#### Scenario: Value above 1 is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=1.5`
- **THEN** `Config.llmPercentage` SHALL be `1.0`

#### Scenario: Negative value is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=-0.2`
- **THEN** `Config.llmPercentage` SHALL be `0.0`

#### Scenario: In-range value unchanged
- **WHEN** `ape.properties` sets `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`

### Requirement: Config — llmPercentageNoSubstrate seam

`Config.llmPercentageNoSubstrate` (double) SHALL be declared in `Config.java` and loaded via `ape.llmPercentageNoSubstrate`, default `-1`. The value `-1` is a sentinel meaning "inherit `Config.llmPercentage`" — it does NOT mean a routing percentage of −1. The flag SHALL be registered in the `apePureMode` RV-flag registry (INV-ARCH-06 of `scoring-pipeline`) as an **exempt** RV flag — consistent with the other LLM sampling params (`llmModel`, `llmPromptVariant`, `llmTemperature`, …), whose off-value shape does not fit the boolean/weight buckets. It is inert in the `ape_pure` arm regardless of its value because `apePureMode` forces the LLM masters off (`llmOnNewState`/`llmOnStagnation → false`, `llmPercentage → 0`) and leaves `llmUrl` unset.

- The `-1` sentinel SHALL be exempt from the `[0.0, 1.0]` clamp applied to `llmPercentage` (INV-RTR-08): clamping would collapse the sentinel to `0.0`. When the configured value is `-1`, it SHALL pass through unclamped.
- When the configured value is `>= 0`, it SHALL be clamped to `[0.0, 1.0]` exactly like `llmPercentage`.

This change adds NO consumer of `llmPercentageNoSubstrate`. `LlmRouter`, `shouldRouteRandom()`, the routing predicates, and all telemetry SHALL be unchanged; routing SHALL continue to use `Config.llmPercentage` unconditionally. The seam exists so round-2 adaptive routing (F′) can, without a protocol change, read `isWidgetlessSubstrate()` at load and substitute this percentage for widgetless apps.

- **INV-RTR-09**: In this change `llmPercentageNoSubstrate` SHALL have no effect on any routing decision; it SHALL be loaded and exposed only. Its `-1` default SHALL pass through the clamp unchanged; a configured value `>= 0` SHALL be clamped to `[0.0, 1.0]`.

#### Scenario: default sentinel not clamped
- **WHEN** `ape.properties` does not set `ape.llmPercentageNoSubstrate`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL equal `-1` (not clamped to `0.0`)

#### Scenario: configured value clamped
- **WHEN** `ape.properties` sets `ape.llmPercentageNoSubstrate=1.5`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL be `1.0`

#### Scenario: no routing behaviour change
- **WHEN** `ape.llmPercentageNoSubstrate=0.7` and an app is widgetless
- **THEN** `shouldRouteRandom()` SHALL still use `Config.llmPercentage` (unchanged); the no-substrate value SHALL NOT be consumed

