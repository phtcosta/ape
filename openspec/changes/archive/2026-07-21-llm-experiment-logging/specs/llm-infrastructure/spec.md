## Purpose

Delta spec for LLM failure-cause classification (fix #3). `SglangClient.chat()` currently catches every exception and returns null, destroying the cause. This delta classifies the cause into a named category and exposes it to the caller, without changing the null-return contract (INV-LLM-01 is preserved). The client's classification covers only failures it can observe (transport, HTTP status, OpenAI-envelope parsing); tool-call extraction runs in `LlmRouter` after a successful `chat()` and is attributed router-side (see the `llm-routing` delta).

## MODIFIED Requirements

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for Qwen3-VL to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), and `timeoutMs` (int). Both connection and read timeouts SHALL be set to `timeoutMs`. A `setTools(JSONArray)` method SHALL accept the OpenAI function-calling tools schema; when set, the `tools` array is included in every request body.

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object. It SHALL also extract the response envelope's top-level `model` field into `ChatResponse` (null when the server omits it), so the caller can log the server-reported model (`[APE-LLM-CONFIG-ACK]`, per the `llm-routing` delta). A missing `model` field is not a parse failure.

**Failure-cause classification:** when `chat()` returns null, `SglangClient` SHALL classify the underlying failure into exactly one named cause and expose it to the caller via a `getLastErrorCause()` accessor read immediately after the null return. The classification SHALL be:

| Cause | Trigger |
|-------|---------|
| `timeout` | `SocketTimeoutException` from connect or read |
| `http_<status>` | non-200 HTTP status |
| `connection` | any other `IOException` reaching the server |
| `parse` | response body received but the OpenAI envelope is not parseable (bad JSON, missing `choices[0].message`) |

To distinguish `timeout` from `connection`, `sendRequest()` SHALL catch `SocketTimeoutException` before the generic `IOException` and tag it as `timeout`; the generic `IOException` branch SHALL tag `connection`. The non-200 branch SHALL preserve the numeric HTTP status in a dedicated field on `LlmException` (today the status exists only interpolated into the message string, which is insufficient for classification), so the caller can log `http_<status>`.

`chat()` SHALL reset the recorded cause at the start of every invocation, so a stale cause from an earlier failure can never be attributed to a later call. Failures occurring after `chat()` returns non-null (tool-call extraction, coordinate mapping) happen outside `SglangClient` and SHALL NOT be attributed through this seam.

- **INV-LLM-01** (unchanged): `SglangClient.chat()` SHALL never throw an unchecked exception to the caller. All `IOException` (including `SocketTimeoutException`), `JSONException`, and other exceptions SHALL be caught internally and result in either a null `ChatResponse` or a `ChatResponse` with null tool calls. The added cause classification SHALL happen inside these existing catch blocks and SHALL NOT change the null-return contract.
- **INV-LLM-08**: When `chat()` returns null, exactly one cause SHALL be recorded (`timeout`, `http_<status>`, `connection`, or `parse`), reflecting the actual failure point. The cause SHALL be reset at the start of every `chat()` invocation and SHALL be readable by the caller between the null return and the next `chat()` call.

#### Scenario: Successful multimodal chat request

- **WHEN** `SglangClient.chat(messages)` is called with a message list containing one system message (text) and one user message (text + base64 image)
- **AND** SGLang is reachable at the configured URL
- **THEN** an HTTP POST SHALL be sent to `{baseUrl}/chat/completions`
- **AND** the request body SHALL contain `"model"`, `"temperature"`, `"top_p"`, `"top_k"`, `"max_tokens"`, and `"messages"` fields
- **AND** the returned `ChatResponse` SHALL contain the model's text content, any tool calls, and the server-reported model identifier when the response carries one

#### Scenario: SGLang unreachable

- **WHEN** `SglangClient.chat(messages)` is called and the HTTP connection times out
- **THEN** `null` SHALL be returned (or a ChatResponse with null content)
- **AND** no exception SHALL propagate to the caller

#### Scenario: Timeout classified distinctly from connection failure

- **WHEN** `SglangClient.chat(messages)` is called and the read times out
- **THEN** `null` SHALL be returned (INV-LLM-01 preserved)
- **AND** the exposed last error cause SHALL be `timeout`, distinct from the `connection` cause reported when the host is unreachable

#### Scenario: HTTP status preserved in the cause

- **WHEN** `SglangClient.chat(messages)` is called and the server returns HTTP 500
- **THEN** `null` SHALL be returned
- **AND** the exposed last error cause SHALL carry the status, enabling the caller to log `cause=http_500`

#### Scenario: Qwen3-VL array coordinate format

- **WHEN** the model returns tool call arguments with `"x": [540, 399]` instead of `"x": 540, "y": 399`
- **THEN** `SglangClient` SHALL normalize this to separate x and y values before constructing the `ToolCall` object
