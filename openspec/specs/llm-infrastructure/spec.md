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

- **INV-LLM-01**: `SglangClient.chat()` SHALL never throw an unchecked exception to the caller. All `IOException` (including `SocketTimeoutException`), `JSONException`, and other exceptions SHALL be caught internally and result in either a null `ChatResponse` or a `ChatResponse` with null tool calls. The added cause classification SHALL happen inside these existing catch blocks and SHALL NOT change the null-return contract.
- **INV-LLM-02**: `ScreenshotCapture.capture()` SHALL return `null` (not throw) when screenshot capture fails for any reason (reflection failure, permission denied, null bitmap).
- **INV-LLM-03**: `ImageProcessor.processScreenshot()` SHALL produce a base64-encoded JPEG string whose decoded image has a longest edge of at most `MAX_EDGE_PX` (1000) pixels, maintaining the original aspect ratio.
- **INV-LLM-04**: `ToolCallParser.parse()` SHALL attempt all 3 fallback levels (native tool calls → XML tags → inline JSON) before returning null. It SHALL never throw an exception to the caller.
- **INV-LLM-05**: `CoordinateNormalizer.normalize()` SHALL clamp output pixel coordinates to `[0, dimension - 1]` for both axes, ensuring valid on-screen coordinates regardless of input values.
- **INV-LLM-06**: `LlmCircuitBreaker` SHALL transition from CLOSED to OPEN after exactly `failureThreshold` (default: 3) consecutive failures. It SHALL remain OPEN for `openDurationMs` (default: 60000ms), then transition to HALF_OPEN to allow a single probe request.
- **INV-LLM-07**: All methods on `LlmCircuitBreaker` SHALL be synchronized for thread safety.
- **INV-LLM-08**: When `chat()` returns null, exactly one cause SHALL be recorded (`timeout`, `http_<status>`, `connection`, or `parse`), reflecting the actual failure point. The cause SHALL be reset at the start of every `chat()` invocation and SHALL be readable by the caller between the null return and the next `chat()` call.
- **INV-LLM-11**: The `tools` array sent on the wire SHALL be exactly the schema supplied to that `chat()` invocation; no tools state SHALL persist on the client across invocations.
- **INV-LLM-12**: When `capture()` returns null, exactly one failure stage SHALL be recorded, reset at the start of every `capture()` invocation; the seam SHALL never alter the null-return contract (INV-LLM-02).

---
## Requirements
### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for Qwen3-VL to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), and `timeoutMs` (int). Both connection and read timeouts SHALL be set to `timeoutMs`. **The `tools` schema SHALL be supplied per invocation** — `chat(messages, tools)` includes the supplied array in that request's body. The `setTools(JSONArray)` method and the run-wide field it installed are **removed**: a single schema fixed at construction cannot track the screen, which is what produced the measured incoherence where the wire always advertised `type_text` while the system message omitted it conditionally. No dual path remains (P3, INV-LLM-11).

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object. It SHALL also extract the response envelope's top-level `model` field into `ChatResponse` (null when the server omits it), so the caller can log the server-reported model (`[APE-LLM-CONFIG-ACK]`, per the `llm-routing` capability). A missing `model` field is not a parse failure.

**Raw-arguments preservation (INV-LLM-10):** for each tool call, the constructed `ToolCall` SHALL carry, in addition to the best-effort parsed arguments map, the arguments in raw string form via `getRawArguments()`:

- `arguments` encoded as a JSON **string** (the standard OpenAI/SGLang encoding): the string verbatim — including when it fails to parse as JSON. A parse failure SHALL still leave the arguments map empty (existing behavior) but SHALL NOT discard the raw string.
- `arguments` encoded as a JSON **object**: the object's serialization (`toString()`).
- `arguments` absent: `getRawArguments()` SHALL return null.

The parsed-map construction is unchanged, including the `"x": [x, y]` array expansion; it feeds `ToolCallParser`'s fallback path.

The failure-cause classification (`getLastErrorCause()`, INV-LLM-08) is unchanged by this revision.

#### Scenario: schema travels with the request, not the client

- **WHEN** `chat(messages, toolsWithoutTypeText)` is invoked and then `chat(messages, toolsWithTypeText)`
- **THEN** each request body's `tools` array SHALL be exactly the schema supplied to that invocation
- **AND** no `setTools` entry point SHALL exist on the client

#### Scenario: server-reported model still extracted

- **WHEN** the response envelope carries a top-level `model` field
- **THEN** `ChatResponse` SHALL carry it for the `[APE-LLM-CONFIG-ACK]` line
- **AND** its absence SHALL NOT be a parse failure

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

1. **Native format**: Check `response.getToolCalls()` for tool calls extracted from the envelope by `SglangClient`
2. **XML tag format**: Search response text for `<tool_call>JSON</tool_call>` or `<function_call>JSON</function_call>` tags (Qwen3-VL generates this ~50% of the time)
3. **Inline JSON format**: Find the first balanced JSON object containing both `"name"` and `"arguments"` keys

**Level 1 SHALL run the same repair pipeline as Levels 2-3 (INV-LLM-10).** When the tool call carries a raw arguments form (`getRawArguments() != null`), Level 1 SHALL rebuild the XML-path intermediate — `{"name": <quoted name>, "arguments": <raw>}`, with the name embedded via `JSONObject.quote` — and parse it through the shared `parseJsonString`, so the pre-parse fixes, the last-resort integer extraction, and the repair-form labeling (INV-LLM-09) apply identically to native tool calls. When the raw form is null or the shared pipeline yields no action, Level 1 SHALL fall back to constructing the action from the pre-parsed arguments map with repair-form label `none` — the pre-delta behavior, preserved verbatim so no input that parses today is lost.

Before parsing JSON at any level, the parser SHALL apply Qwen3-VL malformed JSON fixes:
- Quoted-collapsed-XY: `{"x": "540, 399}` or `{"x": "540, 399"}` → `{"x": 540, "y": 399}` (both coordinates collapsed into one string under `"x"`, opening quote always present, closing quote optional). This fix SHALL run before the missing-"y"-key fix, because the leading quote otherwise defeats that pattern and leaves an unterminated string for `org.json`.
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces

When, after all fixes, `org.json` still cannot parse an object that names a tap action (`click`, `long_click`), the parser SHALL apply a last-resort recovery: extract the first two standalone integers (1–4 digit runs) appearing in the `arguments` region and use them as `(x, y)`. This recovers coordinate malformations not covered by a specific fix pattern without depending on the exact malformed form. It SHALL be attempted only after the regex fixes fail to yield a parseable object, and SHALL itself return null (never throw) if no gated action name or fewer than two integers are present. The gate is restricted to the two tap actions because they are the only ones whose recovery is a complete, correctly-executable action: `scroll` is not in the advertised toolset and has no router dispatch (it would execute as a tap — a wrong gesture), a `type_text` without its unrecoverable `text` is a wasted step, and `back` has no coordinate semantics.

JSON parsing SHALL use `new JSONObject(fixedJson)` and field extraction via `obj.optString("name")`, `obj.optInt("x")`, etc.

The returned `ParsedAction` SHALL contain `actionType` (String — one of "click", "long_click", "scroll", "type_text", "back"), `x` and `y` (int, in [0,1000) normalized Qwen3-VL space), optional `text` (String, for type_text actions), and a repair-form label (per INV-LLM-09) naming the fix a successful parse required, or `none`.

#### Scenario: Native tool call format

- **WHEN** `parse(response)` is called and `response.getToolCalls()` contains a tool call with `name="click"` and raw arguments `{"x": 540, "y": 399}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`
- **AND** its repair-form label SHALL be `none`

#### Scenario: Native missing-y string repaired (the dominant degenerate form)

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": 616, 891}` (unparseable — its arguments map is empty)
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=616`, `y=891` and repair-form label `missing_y`
- **AND** the parse SHALL NOT collapse to `(0,0)`

#### Scenario: Native quoted-collapsed-XY repaired despite a valid arguments map

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": "540, 399"}` (valid JSON — the map holds the string under `x`)
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `quoted_xy`
- **AND** NOT `(0,0)` from `Integer.parseInt` failing on the map value

#### Scenario: Native array coordinates labeled

- **WHEN** `response.getToolCalls()` contains `name="click"` whose envelope arguments were the object `{"x": [540, 399]}`
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `array_xy`

#### Scenario: Native unrecoverable tap falls to the integer scan

- **WHEN** `response.getToolCalls()` contains `name="click"` with raw arguments `{"x": = 265, "y": 687}` (unparseable after every regex fix)
- **THEN** a `ParsedAction` SHALL be returned with `x=265`, `y=687` and repair-form label `int_scan`

#### Scenario: Native fallback preserves pre-delta behavior

- **WHEN** `response.getToolCalls()` contains `name="back"` with raw arguments that neither parse nor qualify for the integer scan (gate admits only tap actions)
- **THEN** Level 1 SHALL fall back to the arguments-map construction and return a `ParsedAction` with `actionType="back"` and repair-form label `none`
- **AND** no exception SHALL propagate

#### Scenario: Native tool call without raw form uses the map path

- **WHEN** `response.getToolCalls()` contains a `ToolCall` constructed without raw arguments (`getRawArguments() == null`) carrying map `{x=540, y=399}`
- **THEN** a `ParsedAction` SHALL be returned with `x=540`, `y=399` and repair-form label `none` (pre-delta path, unchanged)

#### Scenario: XML tag format fallback

- **WHEN** `response.getToolCalls()` is empty
- **AND** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": 540, "y": 399}}</tool_call>`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=540`, `y=399`
- **AND** its repair-form label SHALL be `none`

#### Scenario: Malformed JSON with missing y key

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 540, 399}}`
- **THEN** the parser SHALL fix the JSON to `{"name": "click", "arguments": {"x": 540, "y": 399}}`
- **AND** return a valid `ParsedAction` with `x=540`, `y=399` and repair-form label `missing_y`

#### Scenario: Quoted-collapsed-XY, closing quote absent

- **WHEN** `response.getContent()` contains `<tool_call>{"name": "click", "arguments": {"x": "500, 527}}</tool_call>` (opening quote, no closing quote)
- **THEN** the parser SHALL fix the value to `{"x": 500, "y": 527}` before `org.json` sees it
- **AND** return a `ParsedAction` with `actionType="click"`, `x=500`, `y=527` and repair-form label `quoted_xy`
- **AND** no exception SHALL propagate and `parse()` SHALL NOT return null

#### Scenario: Quoted-collapsed-XY, closing quote present

- **WHEN** the response contains `{"name": "click", "arguments": {"x": "820, 590"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="click"`, `x=820`, `y=590` and repair-form label `quoted_xy`

#### Scenario: Bare collapsed coordinates unaffected by the quoted fix

- **WHEN** the response contains `{"name": "click", "arguments": {"x": 932, 71}}` (bare, no quotes)
- **THEN** the quoted-collapsed-XY fix SHALL NOT alter the string
- **AND** the missing-"y"-key fix SHALL produce a `ParsedAction` with `x=932`, `y=71` and repair-form label `missing_y`

#### Scenario: Last-resort integer extraction

- **WHEN** the response contains `{"name": "click", "arguments": {"x": = 265, "y": 687}}` (equals-sign malformation — unparseable by `org.json` after every regex fix)
- **THEN** the parser SHALL return a `ParsedAction` with `actionType="click"`, `x=265`, `y=687` and repair-form label `int_scan`

#### Scenario: Last-resort gate excludes non-tap actions

- **WHEN** the response contains an unparseable `{"name": "scroll", "arguments": ...}` object whose `arguments` region holds two legible integers
- **THEN** `null` SHALL be returned (the gate admits only `click`/`long_click`)
- **AND** no exception SHALL propagate

#### Scenario: type_text action

- **WHEN** the response contains `{"name": "type_text", "arguments": {"x": 300, "y": 500, "text": "user@example.com"}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="type_text"`, `x=300`, `y=500`, `text="user@example.com"` and repair-form label `none`

#### Scenario: long_click action

- **WHEN** the response contains `{"name": "long_click", "arguments": {"x": 450, "y": 600}}`
- **THEN** a `ParsedAction` SHALL be returned with `actionType="long_click"`, `x=450`, `y=600` and repair-form label `none`

#### Scenario: All levels fail

- **WHEN** the response contains no parseable tool call at any level and no known action name for last-resort extraction
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

#### Scenario: LlmException stays internal to SglangClient
- **WHEN** an internal error occurs inside `SglangClient.chat()` and an `LlmException` is raised
- **THEN** it SHALL be caught within `SglangClient` and SHALL NOT propagate to the caller (per INV-LLM-01)

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
| `ape.llmPercentage` | double | `0.02` | Probability of routing to LLM on each step (0.0 = disabled, 0.7 = 70%, 0.99 = nearly every step) |
| `ape.llmMaxTokens` | int | `1024` | `max_tokens` for the chat completion request (J1c; default = the previously hard-coded value; not causal for truncation — observed `tokens_out` ≈ 25) |
| `ape.llmSnapTolerancePx` | int | `50` | Floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `mapToModelAction` (J1b; lever analyzed and discarded — default not swept) |
| `ape.llmBoundaryTopPct` | double | `0.05` | Top boundary reject band as a fraction of screen height (J1b; policy lever, default not swept) |
| `ape.llmBoundaryBottomPct` | double | `0.94` | Bottom boundary reject band as a fraction of screen height (J1b; policy lever, default not swept) |

The four J1b/J1c keys carry no clamping logic (researcher-facing knobs, like `llmTimeoutMs`/`llmTopK`); their defaults SHALL reproduce the previously hard-coded behavior bit-for-bit. In the run-spec `Feature` model, `ape.llmUrl` is the activation key of the `LLM` feature and every other key in this table is a sub-parameter owned by the `LLM` feature family: when `LLM` is absent from the resolved plan, `LlmParams` is null, no LLM object is constructed, and an explicitly-set LLM sub-parameter is accepted only at its neutral value (INV-RUN-05 of `run-spec`) — an explicitly enabled mode (`ape.llmOnNewState=true`, `ape.llmOnStagnation=true`, or `ape.llmPercentage>0`) without `ape.llmUrl` aborts resolution as a missing dependency.

When `Config.llmPercentage` is `0.0`, no random LLM calls SHALL occur — only new-state and stagnation modes apply. When `Config.llmPercentage` is `0.02` (default), approximately 2% of non-event steps SHALL attempt LLM calls.

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

#### Scenario: Custom sampling parameters

- **WHEN** `ape.properties` contains `ape.llmTopP=0.9` and `ape.llmTopK=100`
- **THEN** `Config.llmTopP` SHALL equal `0.9`
- **AND** `Config.llmTopK` SHALL equal `100`
- **AND** `SglangClient` SHALL use these values in the request body

#### Scenario: Default 2% random routing

- **WHEN** `ape.properties` does not contain `ape.llmPercentage`
- **THEN** `Config.llmPercentage` SHALL be `0.02`
- **AND** approximately 2% of non-event steps SHALL attempt LLM calls

#### Scenario: Random routing disabled

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.0`
- **THEN** `Config.llmPercentage` SHALL be `0.0`
- **AND** `LlmRouter.shouldRouteRandom()` SHALL always return `false`

#### Scenario: High percentage for experiments

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`

#### Scenario: J1b/J1c defaults reproduce the hard-coded values

- **WHEN** `ape.properties` contains none of the four J1b/J1c keys
- **THEN** `Config.llmMaxTokens` SHALL be `1024`, `Config.llmSnapTolerancePx` SHALL be `50`, `Config.llmBoundaryTopPct` SHALL be `0.05`, and `Config.llmBoundaryBottomPct` SHALL be `0.94`
- **AND** router behavior (request `max_tokens`, boundary rejects, euclidean tolerance) SHALL be identical to the pre-delta hard-coded constants

#### Scenario: J1b/J1c keys configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048` and `ape.llmSnapTolerancePx=80`
- **THEN** `Config.llmMaxTokens` SHALL be `2048` and `Config.llmSnapTolerancePx` SHALL be `80`
- **AND** the values SHALL flow into the request body / euclidean tolerance without any code change

#### Scenario: New keys classified in the apePureMode registry

- **WHEN** the four J1b/J1c keys are looked for in the `apePureMode` kill-switch registry
- **THEN** no such registry SHALL exist — `rvForcedOffValues`, `rvUnsetKeys` and `rvExemptReasons` are deleted along with the mechanism they served, and INV-ARCH-06 is dissolved (`scoring-pipeline` capability)
- **AND** the classification this scenario asked for SHALL instead be made by the `Feature` model, where the compiler can check it rather than a string literal naming a field (next scenario)

#### Scenario: LLM sub-parameters owned by the Feature model

- **WHEN** the run-spec key-ownership totality test runs
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, `llmBoundaryTopPct`, and `llmBoundaryBottomPct` SHALL each be owned by the `LLM` feature as sub-parameters
- **AND** with `LLM` absent from the plan, none of them SHALL parameterize any constructed mechanism

#### Scenario: LLM mode without a URL aborts

- **WHEN** `ape.properties` contains `ape.llmOnStagnation=true` and no `ape.llmUrl`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM` (instead of silently loading a mode that can never fire)

### Requirement: SglangClient — Per-Request Tool Schema

`SglangClient` SHALL accept the OpenAI function-calling tools schema **per invocation**: `chat(messages, tools)` includes the supplied `tools` array in that request's body. The constructor-time single-schema path (`setTools(JSONArray)` installing one run-wide array included in every request) is removed — no dual path remains (P3). The `tools` parameter stays required in every request for Qwen3-VL to generate structured tool calls on multimodal input; what changes is that the caller now decides per request which tools exist, so the wire schema can track the screen (the router omits `type_text` on screens without input fields, matching the system message — `llm-routing` capability, "LlmRouter Lifecycle"). Previously the schema was built once at router construction and always advertised `type_text`, contradicting the conditional system message.

A null or empty `tools` argument is a programming error at the call site, not a supported mode: the router always supplies one of its two prebuilt schemas.

#### Scenario: request body carries the supplied schema

- **WHEN** `chat(messages, toolsWithoutTypeText)` is invoked
- **THEN** the request body's `tools` array SHALL be exactly the supplied schema (no `type_text` entry)
- **AND** a subsequent `chat(messages, toolsWithTypeText)` SHALL carry `type_text` — the schema is per-request state, not client state

#### Scenario: no run-wide schema survives

- **WHEN** two consecutive `chat()` invocations supply different schemas
- **THEN** neither request SHALL be influenced by the other's schema (nothing is cached on the client between invocations)

### Requirement: ScreenshotCapture — Failure-Stage Cause Seam

`ScreenshotCapture` SHALL record which capture stage failed when `capture()` returns null, readable by the caller between the null return and the next `capture()` invocation (the same seam pattern as `SglangClient.getLastErrorCause`, INV-LLM-08): `surface_control` when the SurfaceControl reflection path returned null or threw, `uiautomation` when the UiAutomation fallback also produced no bitmap. The cause SHALL be reset at the start of every `capture()` invocation, so a stale stage from an earlier failure can never be attributed to a later call. The null-return contract (INV-LLM-02) is unchanged — the seam adds attribution, not new failure behavior.

Honesty boundary: the Android API returns null for FLAG_SECURE, reflection unavailability, and permission denial without distinguishing them — the seam names the failing **stage**, not the OS-level reason; joining the stage + foreground activity (carried on the router's `[APE-LLM-ERROR] cause=screenshot` line) with the known FLAG_SECURE APK list is an offline step. `OutOfMemoryError` is an `Error` and escapes the `catch (Exception)` blocks — it is NOT conflated into the null return and the seam makes no claim about it.

#### Scenario: FLAG_SECURE failure names its stage

- **WHEN** `capture()` returns null because both the SurfaceControl path and the UiAutomation fallback yielded no bitmap on a FLAG_SECURE window
- **THEN** the failure-cause seam SHALL report the stage of the last attempted path (`uiautomation`)
- **AND** the caller (LlmRouter) SHALL be able to read it before the next `capture()` call

#### Scenario: cause reset per invocation

- **WHEN** `capture()` fails once and a later `capture()` succeeds
- **THEN** the seam SHALL NOT report the earlier failure's stage after the successful call

