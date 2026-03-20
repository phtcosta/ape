## Purpose

Delta spec for `llm-infrastructure` to support Qwen3.5-4B as the replacement for Qwen3-VL-4B-Instruct. SGLang v0.5.9 broke multimodal for Qwen3-VL (corrupted images). Qwen3.5-4B works on the same SGLang version but requires three infrastructure changes: (1) thinking mode must be explicitly disabled via `chat_template_kwargs`, (2) the `qwen3_coder` tool-call-parser produces a new malformed coordinate format (comma-separated string in x field), and (3) raw image mode (no resize) improves grounding accuracy by +12.8pp and eliminates the 3-space coordinate conversion problem.

---

## MODIFIED Invariants

- **INV-LLM-03**: `ImageProcessor.processScreenshot(byte[], boolean)` SHALL produce a base64-encoded JPEG string. When the `resize` parameter is `true`, the decoded image SHALL have a longest edge of at most `MAX_EDGE_PX` (1000) pixels, maintaining the original aspect ratio. When `resize` is `false` (raw mode), the decoded image SHALL preserve the original dimensions (no resize).

---

## MODIFIED Requirements

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for the VLM to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

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
- Missing "y" key: `{"x": 540, 399}` → `{"x": 540, "y": 399}`
- Array format: `{"x": [540, 399]}` → `{"x": 540, "y": 399}`
- Missing leading zero: `": .91` → `": 0.91`
- Truncated JSON: add missing closing braces
- **Comma-separated string format**: `{"x": "498, 549"}` → `{"x": 498, "y": 549}` (Qwen3.5-4B with `qwen3_coder` parser)

The comma-separated string fix handles the case where both x and y coordinates arrive as a single comma-separated string value in the `"x"` field. This format is specific to Qwen3.5-4B with the `qwen3_coder` tool-call-parser in SGLang. Without this fix, tool call rate drops from ~85% to ~30%.

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
