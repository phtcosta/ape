## Purpose

APE-RV's LLM infrastructure provides the low-level building blocks for communicating with an SGLang server running a vision-language model (Qwen3-VL). These components handle the full pipeline from screen capture to actionable coordinates: capturing a screenshot on the Android device, compressing and encoding the image for transmission, sending a multimodal chat request over HTTP, parsing the model's tool-call response with fallbacks for Qwen3-VL formatting quirks, converting normalized coordinates to device pixels, and protecting the exploration loop from cascading LLM failures via a circuit breaker.

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
- `display width/height: int` — device screen dimensions in pixels (source: `AndroidDevice`)
- `PNG bytes: byte[]` — raw screenshot data produced by `ScreenshotCapture` (source: SurfaceControl reflection)

### Output

- `SglangClient.ChatResponse` — parsed LLM response containing text content, optional tool calls, and token usage (`getPromptTokens()`, `getCompletionTokens()` extracted from the OpenAI-compatible `usage` field in the response JSON)
- `ToolCallParser.ParsedAction` — extracted action type, normalized coordinates (x, y in [0,1000) Qwen3-VL space), optional text (for type_text)
- `int[] pixelCoords` — device-pixel coordinates converted from normalized [0,1000) space
- `String base64Image` — JPEG-compressed, resized, base64-encoded screenshot for LLM consumption

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
- **INV-LLM-03**: `ImageProcessor.processScreenshot()` SHALL produce a base64-encoded JPEG string whose decoded image has a longest edge of at most `MAX_EDGE_PX` (1000) pixels, maintaining the original aspect ratio.
- **INV-LLM-04**: `ToolCallParser.parse()` SHALL attempt all 3 fallback levels (native tool calls → XML tags → inline JSON) before returning null. It SHALL never throw an exception to the caller.
- **INV-LLM-05**: `CoordinateNormalizer.normalize()` SHALL clamp output pixel coordinates to `[0, dimension - 1]` for both axes, ensuring valid on-screen coordinates regardless of input values.
- **INV-LLM-06**: `LlmCircuitBreaker` SHALL transition from CLOSED to OPEN after exactly `failureThreshold` (default: 3) consecutive failures. It SHALL remain OPEN for `openDurationMs` (default: 60000ms), then transition to HALF_OPEN to allow a single probe request.
- **INV-LLM-07**: All methods on `LlmCircuitBreaker` SHALL be synchronized for thread safety.

---

## ADDED Requirements

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), and a `messages` array. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), and `timeoutMs` (int). Both connection and read timeouts SHALL be set to `timeoutMs`.

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object.

#### Scenario: Successful multimodal chat request

- **WHEN** `SglangClient.chat(messages)` is called with a message list containing one system message (text) and one user message (text + base64 image)
- **AND** SGLang is reachable at the configured URL
- **THEN** an HTTP POST SHALL be sent to `{baseUrl}/chat/completions`
- **AND** the request body SHALL contain `"model"`, `"temperature"`, `"top_p"`, `"top_k"`, `"max_tokens"`, and `"messages"` fields
- **AND** the returned `ChatResponse` SHALL contain the model's text content and any tool calls

#### Scenario: SGLang unreachable

- **WHEN** `SglangClient.chat(messages)` is called and the HTTP connection times out
- **THEN** `null` SHALL be returned (or a ChatResponse with null content)
- **AND** no exception SHALL propagate to the caller

#### Scenario: Qwen3-VL array coordinate format

- **WHEN** the model returns tool call arguments with `"x": [540, 399]` instead of `"x": 540, "y": 399`
- **THEN** `SglangClient` SHALL normalize this to separate x and y values before constructing the `ToolCall` object

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

`ImageProcessor.processScreenshot(byte[] pngBytes)` SHALL decode the PNG, resize to fit within `MAX_EDGE_PX` (1000) pixels on the longest edge while maintaining aspect ratio, compress to JPEG at quality 80, and return the result as a base64-encoded string (no data URI prefix). If the image is already smaller than `MAX_EDGE_PX`, no resize SHALL occur.

#### Scenario: Large screenshot resized

- **WHEN** `processScreenshot(pngBytes)` is called with a 1080x1920 PNG
- **AND** the longest edge (1920) exceeds `MAX_EDGE_PX` (1000)
- **THEN** the image SHALL be resized to 563x1000 (maintaining aspect ratio)
- **AND** the output SHALL be a base64-encoded JPEG string

#### Scenario: Small screenshot not resized

- **WHEN** `processScreenshot(pngBytes)` is called with a 480x800 PNG
- **AND** the longest edge (800) is less than `MAX_EDGE_PX` (1000)
- **THEN** no resize SHALL occur
- **AND** the image SHALL be compressed to JPEG at quality 80 and base64-encoded

#### Scenario: Null input

- **WHEN** `processScreenshot(null)` is called
- **THEN** `null` SHALL be returned

---

### Requirement: ToolCallParser — 3-Level Fallback Parser

`ToolCallParser.parse(ChatResponse response)` SHALL extract a tool call from the LLM response using a 3-level fallback strategy:

1. **Native format**: Check `response.getToolCalls()` for pre-parsed tool calls from SGLang
2. **XML tag format**: Search response text for `<tool_call>JSON</tool_call>` or `<function_call>JSON</function_call>` tags (Qwen3-VL generates this ~50% of the time)
3. **Inline JSON format**: Find the first balanced JSON object containing both `"name"` and `"arguments"` keys

Before parsing JSON at any level, the parser SHALL apply Qwen3-VL malformed JSON fixes:
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces

JSON parsing SHALL use `new JSONObject(fixedJson)` and field extraction via `obj.optString("name")`, `obj.optInt("x")`, etc.

The returned `ParsedAction` SHALL contain `actionType` (String), `x` and `y` (int, in [0,1000) normalized Qwen3-VL space), and optional `text` (String, for type_text actions).

#### Scenario: Native tool call format

- **WHEN** `parse(response)` is called and `response.getToolCalls()` contains a valid tool call with `name="click"` and `arguments={"x": 540, "y": 399}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`

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

When `Config.llmUrl` is `null`, all LLM features SHALL be disabled and no LLM-related objects SHALL be instantiated.

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
