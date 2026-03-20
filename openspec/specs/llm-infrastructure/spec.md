## Purpose

APE-RV's LLM infrastructure provides the low-level building blocks for communicating with an SGLang server running a vision-language model (Qwen3.5-4B or Qwen3-VL). These components handle the full pipeline from screen capture to actionable coordinates: capturing a screenshot on the Android device, compressing and encoding the image for transmission, sending a multimodal chat request over HTTP, parsing the model's tool-call response with fallbacks for VLM formatting quirks, converting normalized coordinates to device pixels, and protecting the exploration loop from cascading LLM failures via a circuit breaker.

All 7 classes in this capability are copied from the rvsmart project (`rvsec-android/rvsmart/src/.../llm/`) with a package rename from `br.unb.cic.rvsmart.llm` to `com.android.commands.monkey.ape.llm`. During copy, `SglangClient` and `ToolCallParser` are converted from Gson (`com.google.gson`) to org.json (`org.json.JSONObject`, `org.json.JSONArray`), which is already available in the Android runtime and used by 24 existing files in APE-RV. This eliminates the need for any new Maven dependency. The remaining 5 classes have no JSON dependency and are copied as-is. Total: ~1000 LOC.

The infrastructure communicates with SGLang via `java.net.HttpURLConnection` (available in the Android SDK) and uses `org.json` for JSON serialization. Screenshot capture uses `android.view.SurfaceControl` via reflection (hidden API, available from app_process context on API 28+).

The infrastructure is designed for graceful degradation: if any component fails (screenshot capture returns null, HTTP times out, response is unparseable), the caller receives a null result and falls back to SATA action selection. The circuit breaker adds a second layer of protection by blocking all LLM attempts for 60 seconds after 3 consecutive failures, preventing the exploration loop from wasting time on a dead server.

---

## Data Contracts

### Input

- `Config.llmUrl: String` — base URL for SGLang server including `/v1` suffix (e.g., `http://10.0.2.2:30000/v1`); null disables all LLM features (source: `ape.properties`)
- `Config.llmModel: String` — model name for SGLang (default: `"default"`; source: `ape.properties`)
- `Config.llmTemperature: double` — sampling temperature for LLM responses (default: `0.3`; source: `ape.properties`)
- `Config.llmTopP: double` — nucleus sampling threshold (default: `0.6`; source: `ape.properties`)
- `Config.llmTopK: int` — top-k sampling (default: `50`; source: `ape.properties`)
- `Config.llmTimeoutMs: int` — HTTP timeout in milliseconds for SGLang requests (default: `15000`; source: `ape.properties`)
- `Config.llmEnableThinking: boolean` — enable VLM thinking mode (default: `false`; source: `ape.properties`)
- `Config.llmImageResize: boolean` — resize screenshots before sending to LLM (default: `false`; source: `ape.properties`)
- `display width/height: int` — device screen dimensions in pixels (source: `AndroidDevice`)
- `PNG bytes: byte[]` — raw screenshot data produced by `ScreenshotCapture` (source: SurfaceControl reflection)

### Output

- `SglangClient.ChatResponse` — parsed LLM response containing text content, optional tool calls, and token usage (`getPromptTokens()`, `getCompletionTokens()` extracted from the OpenAI-compatible `usage` field in the response JSON)
- `ToolCallParser.ParsedAction` — extracted action type, normalized coordinates (x, y in [0,1000) space), optional text (for type_text)
- `int[] pixelCoords` — device-pixel coordinates converted from normalized [0,1000) space
- `String base64Image` — JPEG-compressed, optionally resized, base64-encoded screenshot for LLM consumption

### Side-Effects

- **Network**: HTTP POST to SGLang server at `{llmUrl}/chat/completions` for each LLM call
- **Android device**: SurfaceControl screenshot capture accesses the display framebuffer via reflection

### Error

- `IOException` — SGLang unreachable or HTTP timeout; caught by caller, triggers circuit breaker failure
- `JSONException` — malformed JSON in SGLang response; caught inside SglangClient, returns null
- `null` return from `ScreenshotCapture.capture()` — SurfaceControl reflection failed; no exception propagated
- `null` return from `ToolCallParser.parse()` — LLM response could not be parsed by any of the 3 fallback levels

---

## Invariants

- **INV-LLM-01**: `SglangClient.chat()` SHALL never throw an unchecked exception to the caller. All `IOException`, `JSONException`, and other exceptions SHALL be caught internally and result in either a null `ChatResponse` or a `ChatResponse` with null tool calls.
- **INV-LLM-02**: `ScreenshotCapture.capture()` SHALL return `null` (not throw) when screenshot capture fails for any reason (reflection failure, permission denied, null bitmap).
- **INV-LLM-03**: `ImageProcessor.processScreenshot(byte[], boolean)` SHALL produce a base64-encoded JPEG string. When the `resize` parameter is `true`, the decoded image SHALL have a longest edge of at most `MAX_EDGE_PX` (1000) pixels, maintaining the original aspect ratio. When `resize` is `false` (raw mode), the decoded image SHALL preserve the original dimensions (no resize).
- **INV-LLM-04**: `ToolCallParser.parse()` SHALL attempt all 3 fallback levels (native tool calls → XML tags → inline JSON) before returning null. It SHALL never throw an exception to the caller.
- **INV-LLM-05**: `CoordinateNormalizer.normalize()` SHALL clamp output pixel coordinates to `[0, dimension - 1]` for both axes, ensuring valid on-screen coordinates regardless of input values.
- **INV-LLM-06**: `LlmCircuitBreaker` SHALL transition from CLOSED to OPEN after exactly `failureThreshold` (default: 3) consecutive failures. It SHALL remain OPEN for `openDurationMs` (default: 60000ms), then transition to HALF_OPEN to allow a single probe request.
- **INV-LLM-07**: All methods on `LlmCircuitBreaker` SHALL be synchronized for thread safety.

---

## ADDED Requirements

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for the VLM to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support coordinate formats where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

When `Config.llmEnableThinking` is `false`, `SglangClient` SHALL include `"chat_template_kwargs": {"enable_thinking": false}` in the request body JSON. When `Config.llmEnableThinking` is `true`, this field SHALL be omitted (letting the model use its default thinking behavior). This parameter is required for Qwen3.5-4B which has thinking mode enabled by default; without it, latency increases from ~2s to 5-13s and coordinate accuracy degrades.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), `timeoutMs` (int), and `enableThinking` (boolean). Both connection and read timeouts SHALL be set to `timeoutMs`. A `setTools(JSONArray)` method SHALL accept the OpenAI function-calling tools schema; when set, the `tools` array is included in every request body.

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object.

#### Scenario: Thinking mode disabled (default)

- **WHEN** `SglangClient` is constructed with `enableThinking=false`
- **AND** `chat(messages)` is called
- **THEN** the request body JSON SHALL contain `"chat_template_kwargs": {"enable_thinking": false}`

#### Scenario: Thinking mode enabled

- **WHEN** `SglangClient` is constructed with `enableThinking=true`
- **AND** `chat(messages)` is called
- **THEN** the request body JSON SHALL NOT contain `"chat_template_kwargs"`

#### Scenario: Successful multimodal chat request

- **WHEN** `SglangClient.chat(messages)` is called with a message list containing one system message (text) and one user message (text + base64 image)
- **AND** SGLang is reachable at the configured URL
- **THEN** an HTTP POST SHALL be sent to `{baseUrl}/chat/completions`
- **AND** the request body SHALL contain `"model"`, `"temperature"`, `"top_p"`, `"top_k"`, `"max_tokens"`, and `"messages"` fields
- **AND** the returned `ChatResponse` SHALL contain the model's text content and any tool calls

#### Scenario: SGLang unreachable

- **WHEN** `SglangClient.chat(messages)` is called and the HTTP connection times out
- **THEN** `null` SHALL be returned
- **AND** no exception SHALL propagate to the caller

#### Scenario: Qwen3-VL array coordinate format

- **WHEN** the model returns tool call arguments with `"x": [540, 399]` instead of `"x": 540, "y": 399`
- **THEN** `SglangClient` SHALL normalize this to separate x and y values before constructing the `ToolCall` object

#### Scenario: Qwen3.5-4B comma-separated string coordinate format (native tool call)

- **WHEN** the model returns native tool call arguments with `"x": "498, 549"` (a String value containing comma-separated coordinates)
- **THEN** `SglangClient.parseArgsObject()` SHALL split the string on the comma, parse both parts as numbers, and store `x=498.0` and `y=549.0` in the arguments map
- **AND** the resulting `ToolCall` SHALL have correct separate x and y values

---

### Requirement: ScreenshotCapture — SurfaceControl Screenshot

`ScreenshotCapture.capture(int width, int height)` SHALL capture a screenshot of the device display and return it as a PNG byte array. The primary capture method SHALL use `android.view.SurfaceControl.screenshot(Rect, int, int, int)` via reflection (hidden API, available from `app_process` context). If reflection fails, a fallback to `UiAutomation.takeScreenshot()` SHALL be attempted.

#### Scenario: Successful capture via SurfaceControl

- **WHEN** `capture(1080, 1920)` is called on an Android device with API 28+
- **AND** `SurfaceControl.screenshot()` is accessible via reflection
- **THEN** a non-null byte array containing valid PNG data SHALL be returned
- **AND** the PNG dimensions SHALL match the requested width and height

#### Scenario: SurfaceControl reflection fails

- **WHEN** `SurfaceControl.screenshot()` is not accessible (e.g., API restriction)
- **THEN** the UiAutomation fallback SHALL be attempted
- **AND** if both methods fail, `null` SHALL be returned
- **AND** no exception SHALL propagate

---

### Requirement: ImageProcessor — Screenshot Compression and Encoding

`ImageProcessor.processScreenshot(byte[] pngBytes, boolean resize)` SHALL decode the PNG, optionally resize, compress to JPEG at quality 80, and return the result as a base64-encoded string (no data URI prefix).

When `resize` is `true`, the image SHALL be resized to fit within `MAX_EDGE_PX` (1000) pixels on the longest edge while maintaining aspect ratio. If the image is already smaller than `MAX_EDGE_PX`, no resize SHALL occur.

When `resize` is `false` (raw mode), the image SHALL NOT be resized — it is compressed to JPEG at quality 80 at its original resolution and base64-encoded. Raw mode eliminates the 3-space coordinate conversion problem (resized image space, Qwen normalized space, device pixel space) by keeping the image at device resolution (typically 1080x1920).

The `resize` parameter SHALL be sourced from `Config.llmImageResize`.

#### Scenario: Raw mode (no resize)

- **WHEN** `processScreenshot(pngBytes, false)` is called with a 1080x1920 PNG
- **THEN** the image SHALL NOT be resized
- **AND** the output SHALL be a base64-encoded JPEG at 1080x1920 resolution

#### Scenario: Resize mode (legacy behavior)

- **WHEN** `processScreenshot(pngBytes, true)` is called with a 1080x1920 PNG
- **AND** the longest edge (1920) exceeds `MAX_EDGE_PX` (1000)
- **THEN** the image SHALL be resized to 563x1000 (maintaining aspect ratio)
- **AND** the output SHALL be a base64-encoded JPEG string

#### Scenario: Small screenshot not resized in either mode

- **WHEN** `processScreenshot(pngBytes, true)` is called with a 480x800 PNG
- **THEN** no resize SHALL occur (already fits within MAX_EDGE_PX)

#### Scenario: Null input

- **WHEN** `processScreenshot(null, false)` is called
- **THEN** `null` SHALL be returned

---

### Requirement: ToolCallParser — 3-Level Fallback Parser

`ToolCallParser.parse(ChatResponse response)` SHALL extract a tool call from the LLM response using a 3-level fallback strategy:

1. **Native format**: Check `response.getToolCalls()` for pre-parsed tool calls from SGLang
2. **XML tag format**: Search response text for `<tool_call>JSON</tool_call>` or `<function_call>JSON</function_call>` tags
3. **Inline JSON format**: Find the first balanced JSON object containing both `"name"` and `"arguments"` keys

Before parsing JSON at any level, the parser SHALL apply malformed JSON fixes:
- Comma-separated string format: `{"x": "498, 549"}` → `{"x": 498, "y": 549}` (Qwen3.5-4B with `qwen3_coder` parser)
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces

The comma-separated string fix handles the case where both x and y coordinates arrive as a single comma-separated string value in the `"x"` field. This format is specific to Qwen3.5-4B with the `qwen3_coder` tool-call-parser in SGLang. Without this fix, tool call rate drops from ~85% to ~30%.

JSON parsing SHALL use `new JSONObject(fixedJson)` and field extraction via `obj.optString("name")`, `obj.optInt("x")`, etc.

The returned `ParsedAction` SHALL contain `actionType` (String — one of "click", "long_click", "type_text", "back"), `x` and `y` (int, in [0,1000) normalized space), and optional `text` (String, for type_text actions).

#### Scenario: Native tool call format

- **WHEN** `parse(response)` is called and `response.getToolCalls()` contains a valid tool call with `name="click"` and `arguments={"x": 540, "y": 399}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`

#### Scenario: Comma-separated string coordinate format (Qwen3.5-4B)

- **WHEN** the response contains tool call arguments with `"x": "498, 549"` (string value, comma-separated)
- **THEN** the parser SHALL extract `x=498` and `y=549`
- **AND** return a valid `ParsedAction`

#### Scenario: Comma-separated string with spaces

- **WHEN** the response contains `"x": " 498 , 549 "` (string with extra whitespace)
- **THEN** the parser SHALL trim whitespace and extract `x=498`, `y=549`

#### Scenario: XML tag format fallback

- **WHEN** `response.getToolCalls()` is empty
- **AND** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": 540, "y": 399}}</tool_call>`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`

#### Scenario: Malformed JSON with missing y key

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 540, 399}}`
- **THEN** the parser SHALL fix the JSON to `{"name": "click", "arguments": {"x": 540, "y": 399}}`
- **AND** return a valid `ParsedAction`

#### Scenario: type_text action

- **WHEN** the response contains `{"name": "type_text", "arguments": {"x": 300, "y": 500, "text": "user@example.com"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="type_text"`, `x=300`, `y=500`, `text="user@example.com"`

#### Scenario: long_click action

- **WHEN** the response contains `{"name": "long_click", "arguments": {"x": 450, "y": 600}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="long_click"`, `x=450`, `y=600`

#### Scenario: All levels fail

- **WHEN** the response contains no parseable tool call at any level
- **THEN** `null` SHALL be returned
- **AND** no exception SHALL propagate

---

### Requirement: CoordinateNormalizer — Qwen Coordinates to Device Pixels

`CoordinateNormalizer.normalize(int qwenX, int qwenY, int deviceWidth, int deviceHeight)` SHALL convert coordinates from Qwen3-VL's normalized [0, 1000) space to device pixel coordinates using the formula:

```
pixelX = clamp((int)((qwenX / 1000.0) * deviceWidth), 0, deviceWidth - 1)
pixelY = clamp((int)((qwenY / 1000.0) * deviceHeight), 0, deviceHeight - 1)
```

The method SHALL return an `int[2]` array containing `[pixelX, pixelY]`.

Reference: Qwen3-VL coordinate convention — https://github.com/QwenLM/Qwen3-VL/issues/1486

#### Scenario: Center of 1080x1920 display

- **WHEN** `normalize(500, 500, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[540, 960]`

#### Scenario: Edge clamping

- **WHEN** `normalize(1050, -10, 1080, 1920)` is called
- **THEN** `pixelX` SHALL be clamped to `1079` (deviceWidth - 1)
- **AND** `pixelY` SHALL be clamped to `0`

#### Scenario: Zero coordinates

- **WHEN** `normalize(0, 0, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[0, 0]`

---

### Requirement: LlmCircuitBreaker — Fault Tolerance

`LlmCircuitBreaker` SHALL implement a 3-state circuit breaker (CLOSED, OPEN, HALF_OPEN) that protects the exploration loop from cascading LLM failures.

**State transitions:**
- CLOSED: normal operation. `recordFailure()` increments failure counter. When counter reaches `failureThreshold` (default: 3), transition to OPEN.
- OPEN: all requests blocked. `shouldAttempt()` returns false. After `openDurationMs` (default: 60000ms), transition to HALF_OPEN.
- HALF_OPEN: one probe request allowed. `recordSuccess()` → CLOSED. `recordFailure()` → OPEN (reset timer).

`recordSuccess()` SHALL always reset failure counter and transition to CLOSED regardless of current state.

#### Scenario: Trip after 3 failures

- **WHEN** `recordFailure()` is called 3 times consecutively from CLOSED state
- **THEN** `shouldAttempt()` SHALL return `false`
- **AND** `getStateName()` SHALL return `"OPEN"`

#### Scenario: Recovery after timeout

- **WHEN** the circuit breaker is OPEN
- **AND** 60 seconds have elapsed since the last failure
- **THEN** `shouldAttempt()` SHALL return `true`
- **AND** `getStateName()` SHALL return `"HALF_OPEN"`

#### Scenario: Successful probe closes breaker

- **WHEN** the circuit breaker is HALF_OPEN
- **AND** `recordSuccess()` is called
- **THEN** `getStateName()` SHALL return `"CLOSED"`
- **AND** failure counter SHALL be reset to 0

#### Scenario: Failed probe reopens breaker

- **WHEN** the circuit breaker is HALF_OPEN
- **AND** `recordFailure()` is called
- **THEN** `getStateName()` SHALL return `"OPEN"`
- **AND** the open duration timer SHALL be reset

#### Scenario: Success resets from any state

- **WHEN** `recordSuccess()` is called regardless of current state
- **THEN** the state SHALL transition to CLOSED
- **AND** failure counter SHALL be 0

---

### Requirement: LlmException — LLM Error Type

`LlmException` SHALL be a `RuntimeException` subclass with two constructors: `LlmException(String message)` and `LlmException(String message, Throwable cause)`. It is used internally by `SglangClient` for error handling. Per INV-LLM-01, `LlmException` SHALL never propagate to callers of `SglangClient.chat()`.

---

### Requirement: LLM Configuration Keys

`Config.java` SHALL declare the following `public static final` fields for LLM configuration, loaded from `ape.properties` at class-loading time:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.llmUrl` | String | `null` | SGLang base URL (null = LLM disabled) |
| `ape.llmOnNewState` | boolean | `true` | Enable new-state LLM mode |
| `ape.llmOnStagnation` | boolean | `true` | Enable stagnation LLM mode |
| `ape.llmModel` | String | `"default"` | Model name for SGLang server |
| `ape.llmTemperature` | double | `0.3` | LLM sampling temperature |
| `ape.llmTopP` | double | `0.6` | Nucleus sampling threshold |
| `ape.llmTopK` | int | `50` | Top-k sampling |
| `ape.llmTimeoutMs` | int | `15000` | HTTP timeout in milliseconds |
| `ape.llmMaxCalls` | int | `200` | Maximum LLM calls per session |
| `ape.llmEnableThinking` | boolean | `false` | Enable VLM thinking mode (Qwen3.5-4B default: ON; disable for lower latency and better accuracy) |
| `ape.llmImageResize` | boolean | `false` | Resize screenshots before sending to LLM (false = raw mode, send at device resolution) |

When `Config.llmUrl` is `null`, all LLM features SHALL be disabled and no LLM-related objects SHALL be instantiated.

#### Scenario: Thinking disabled by default

- **WHEN** `ape.properties` does not contain `ape.llmEnableThinking`
- **THEN** `Config.llmEnableThinking` SHALL be `false`
- **AND** `SglangClient` SHALL include `chat_template_kwargs: {"enable_thinking": false}` in requests

#### Scenario: Image resize disabled by default (raw mode)

- **WHEN** `ape.properties` does not contain `ape.llmImageResize`
- **THEN** `Config.llmImageResize` SHALL be `false`
- **AND** `ImageProcessor` SHALL skip resize (raw mode)

#### Scenario: Legacy resize mode enabled

- **WHEN** `ape.properties` contains `ape.llmImageResize=true`
- **THEN** `Config.llmImageResize` SHALL be `true`
- **AND** `ImageProcessor` SHALL resize to max-edge 1000px

#### Scenario: LLM disabled by default

- **WHEN** `ape.properties` does not contain `ape.llmUrl`
- **THEN** `Config.llmUrl` SHALL be `null`
- **AND** no `SglangClient`, `LlmRouter`, or other LLM objects SHALL be created

#### Scenario: LLM enabled with URL

- **WHEN** `ape.properties` contains `ape.llmUrl=http://10.0.2.2:30000/v1`
- **THEN** `Config.llmUrl` SHALL equal `"http://10.0.2.2:30000/v1"`
- **AND** `StatefulAgent` SHALL instantiate `LlmRouter` with the configured URL

#### Scenario: Individual modes toggled

- **WHEN** `ape.properties` contains `ape.llmUrl=http://10.0.2.2:30000/v1` and `ape.llmOnNewState=false`
- **THEN** the new-state LLM mode SHALL be disabled
- **AND** the stagnation mode SHALL remain enabled (per its default)

#### Scenario: Call budget exhausted

- **WHEN** `LlmRouter` has made 200 calls (equal to `Config.llmMaxCalls`)
- **THEN** all subsequent `shouldRouteNewState()` and `shouldRouteStagnation()` SHALL return `false`
- **AND** pure SATA+MOP behavior SHALL continue for the remainder of the session

#### Scenario: Custom sampling parameters

- **WHEN** `ape.properties` contains `ape.llmTopP=0.9` and `ape.llmTopK=100`
- **THEN** `Config.llmTopP` SHALL equal `0.9`
- **AND** `Config.llmTopK` SHALL equal `100`
- **AND** `SglangClient` SHALL use these values in the request body
