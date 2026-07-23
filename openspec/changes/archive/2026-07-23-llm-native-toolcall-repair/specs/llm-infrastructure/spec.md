# Delta: llm-infrastructure — llm-native-toolcall-repair

## Purpose

The hybrid tool-call parser has two response paths with asymmetric robustness. The XML `<tool_call>` path (Levels 2-3) routes every candidate through `parseJsonString` — `fixMalformedJson` (quoted_xy / array_xy / missing_y / cosmetic) plus the `lastResortIntScan` recovery — and degenerates 3 times in 41,522 calls (0.007%, `cmp_llm_20260721` base run). The native path (Level 1) never reaches that pipeline: `SglangClient.parseResponse` silently drops a malformed `arguments` string to an empty map (`catch (Exception ignored)`), and `ToolCallParser.parse` feeds the surviving map straight to `buildParsedAction`, where absent or non-numeric coordinates default to `(0,0)`. Result: 7,506 of 12,966 native calls (57.9%) collapse to `(0,0)` — 99.96% of all `degenerate` no_match. The model's decision is legible in the raw envelope; the extractor discards it.

This delta unifies the two paths at their source of divergence. `SglangClient` preserves the arguments **raw string** on the `ToolCall` (verbatim when the envelope encodes arguments as a JSON string — kept even, and especially, when unparseable; the object's serialization when it arrives as a JSON object). `ToolCallParser` Level 1 rebuilds the same `{"name": ..., "arguments": ...}` intermediate the XML path extracts from `<tool_call>` tags and parses it through the shared `parseJsonString`, so native malformations get the identical repair treatment and the identical repair-form labeling (INV-LLM-09). When the raw string is absent or unrecoverable, Level 1 falls back to the pre-existing map-based construction — the change can only add recoveries, never lose a parse that succeeds today.

J1b/J1c of the same handoff expose three previously hard-coded values as configuration keys with unchanged defaults: `max_tokens` (1024), the euclidean snap-tolerance floor (50 px), and the boundary reject bands (5% / 94%). The levers were analyzed and discarded (`nomatch_decomposition.md` §3, §7); exposure exists solely so a future probe needs no jar rebuild. The consuming behavior is specified in the `llm-routing` capability.

## Invariants

- **INV-LLM-10** (new): `SglangClient.parseResponse` SHALL preserve the tool-call `arguments` in raw string form on the constructed `ToolCall` whenever the envelope carries them (the string verbatim when `arguments` is a JSON string, including unparseable ones; the object's JSON serialization when it is a JSON object). A malformed native `arguments` string SHALL NOT be reduced to an empty arguments map without the raw form surviving for downstream repair. `ToolCallParser.parse` Level 1 SHALL attempt the shared repair pipeline (`parseJsonString`: `fixMalformedJson` → org.json → `lastResortIntScan`) on the raw form before any map-based construction, and SHALL fall back to the map-based construction when the raw form is absent or yields no action — so every input that parses without this invariant still parses identically with it.

- **INV-LLM-04** (unchanged, restated for scope): `ToolCallParser.parse()` SHALL never throw to the caller; the Level 1 raw-path addition executes entirely inside `parseJsonString` and the internally-guarded `lastResortIntScan`, both of which already honor this contract.

## MODIFIED Requirements

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for Qwen3-VL to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), and `timeoutMs` (int). Both connection and read timeouts SHALL be set to `timeoutMs`. A `setTools(JSONArray)` method SHALL accept the OpenAI function-calling tools schema; when set, the `tools` array is included in every request body.

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object. It SHALL also extract the response envelope's top-level `model` field into `ChatResponse` (null when the server omits it), so the caller can log the server-reported model (`[APE-LLM-CONFIG-ACK]`, per the `llm-routing` capability). A missing `model` field is not a parse failure.

**Raw-arguments preservation (INV-LLM-10):** for each tool call, the constructed `ToolCall` SHALL carry, in addition to the best-effort parsed arguments map, the arguments in raw string form via `getRawArguments()`:

- `arguments` encoded as a JSON **string** (the standard OpenAI/SGLang encoding): the string verbatim — including when it fails to parse as JSON. A parse failure SHALL still leave the arguments map empty (existing behavior) but SHALL NOT discard the raw string.
- `arguments` encoded as a JSON **object**: the object's serialization (`toString()`).
- `arguments` absent: `getRawArguments()` SHALL return null.

The parsed-map construction is unchanged, including the `"x": [x, y]` array expansion; it feeds `ToolCallParser`'s fallback path.

**Failure-cause classification:** when `chat()` returns null, `SglangClient` SHALL classify the underlying failure into exactly one named cause and expose it to the caller via a `getLastErrorCause()` accessor read immediately after the null return. The classification SHALL be:

| Cause | Trigger |
|-------|---------|
| `timeout` | `SocketTimeoutException` from connect or read |
| `http_<status>` | non-200 HTTP status |
| `connection` | any other `IOException` reaching the server |
| `parse` | response body received but the OpenAI envelope is not parseable (bad JSON, missing `choices[0].message`) |

To distinguish `timeout` from `connection`, `sendRequest()` SHALL catch `SocketTimeoutException` before the generic `IOException` and tag it as `timeout`; the generic `IOException` branch SHALL tag `connection`. The non-200 branch SHALL preserve the numeric HTTP status in a dedicated field on `LlmException`, so the caller can log `http_<status>`.

`chat()` SHALL reset the recorded cause at the start of every invocation, so a stale cause from an earlier failure can never be attributed to a later call. Failures occurring after `chat()` returns non-null (tool-call extraction, coordinate mapping) happen outside `SglangClient` and SHALL NOT be attributed through this seam. Cause recording is governed by INV-LLM-08 and preserves INV-LLM-01.

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
- **THEN** `SglangClient` SHALL normalize this to separate x and y values in the parsed arguments map before constructing the `ToolCall` object
- **AND** `getRawArguments()` SHALL carry the arguments' JSON serialization for the downstream repair pipeline

#### Scenario: Malformed native arguments string survives raw

- **WHEN** the envelope carries `"arguments": "{\"x\": 616, 891}"` (missing `"y"` key — invalid JSON)
- **THEN** the parsed arguments map SHALL be empty (the string does not parse)
- **AND** `getRawArguments()` SHALL return `{"x": 616, 891}` verbatim
- **AND** no exception SHALL propagate

#### Scenario: Well-formed native arguments string kept verbatim

- **WHEN** the envelope carries `"arguments": "{\"x\": 540, \"y\": 399}"`
- **THEN** the parsed arguments map SHALL contain `x=540`, `y=399`
- **AND** `getRawArguments()` SHALL return the string verbatim

#### Scenario: Absent arguments give null raw

- **WHEN** the envelope's tool call carries no `arguments` field, or a `ToolCall` is constructed via the two-argument constructor
- **THEN** `getRawArguments()` SHALL return null

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

The four J1b/J1c keys carry no clamping logic (researcher-facing knobs, like `llmTimeoutMs`/`llmTopK`); their defaults SHALL reproduce the previously hard-coded behavior bit-for-bit. All four SHALL be registered as **exempt** in the `apePureMode` RV-flag registry (`rvExemptReasons`, INV-ARCH-06 of `scoring-pipeline`) as LLM sub-params, inert when the LLM masters are forced off.

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

- **WHEN** the `apePureMode` kill-switch registry completeness guard runs (INV-ARCH-06)
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, `llmBoundaryTopPct`, and `llmBoundaryBottomPct` SHALL each be present in `rvExemptReasons` with an LLM sub-param reason
