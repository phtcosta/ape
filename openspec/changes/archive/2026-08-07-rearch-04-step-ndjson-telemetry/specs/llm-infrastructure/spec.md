# llm-infrastructure Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`, over the two `llm-infrastructure` requirements that
normatize renderings this change retires. Neither transport, breaker, timeout, schema-per-request
nor failure-cause behavior changes — this delta moves what two clauses are checked against.

`LlmClient — Transport and Circuit Breaker as One Unit` is the requirement this change would
otherwise contradict most directly. `rearch-03-decision-pipeline` added it, and in adding it moved
the `[APE-LLM-CONFIG]` manifest emission onto `LlmClient`'s constructor — so this change's task 3.5,
which deletes that emitter as subsumed by `RUN_START`, retires a line that a *predecessor's* delta
requires. The `Effective LLM Config Manifest` REMOVED entry in this change's `llm-routing` delta
does not reach it: that entry removes a requirement of a different capability, and the manifest
clause had already been relocated out from under it. This is the general hazard of stacked changes
that the design's Context §8 flagged and could not fully enumerate at authoring time, because
`rearch-03`'s deltas were not written yet.

`ScreenshotCapture — Failure-Stage Cause Seam` deliberately gets **no** operation here. Its honesty
boundary already speaks of "the screenshot-failure diagnostic `LlmTelemetry` emits" rather than
naming a line family, so it survives this change unchanged — the diagnostic is still emitted, still
carries the stage and the foreground activity, and is still the offline join partner for the known
FLAG_SECURE APK list. The retired-rendering mention that does exist sits in `rearch-03`'s delta
*preamble*, which is prose about a change and never syncs into a spec. The invariant that did name
the line, INV-RTR-20, is dispositioned in this change's `llm-routing` delta.

Also unchanged, and worth stating because a reader mid-sweep will wonder: the
`[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` line in `allows()` is **not** a retired
rendering. It is free-text `[APE-RV]` diagnostic output, it is still emitted (`LlmClient.java:114`),
and this change only adds a sibling `llm[]` sub-event beside it (`result:"breaker_open"`) so the
episode is attributable to a step without a text join.

## MODIFIED Requirements

### Requirement: LlmClient — Transport and Circuit Breaker as One Unit

`LlmClient` (package `com.android.commands.monkey.ape.llm`) SHALL compose the existing `SglangClient` (HTTP transport) and `LlmCircuitBreaker` (fault tolerance) into a single unit constructed once per run from the plan's `LlmParams` (base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens`, `timeout_ms`) and owned by `RunContext`. The `SglangClient` and `LlmCircuitBreaker` requirements of this capability are unchanged — `LlmClient` is their composition and the sole holder of their references; no other class SHALL hold either.

`LlmClient` SHALL expose:

- `chat(messages, tools) -> ChatResponse | null` — delegates to `SglangClient.chat` with the per-request tools schema; the failure-cause seam (`getLastErrorCause()`, INV-LLM-08) is readable only at the null-return site, unchanged.
- `allows() -> boolean` — the single breaker consultation per routing decision: it SHALL call `shouldAttempt()` exactly once (preserving the OPEN→HALF_OPEN probe transition), reset the open-episode log latch when the breaker allows, and emit the `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` line at the FIRST breaker-caused decline of each open episode using the side-effect-free `isOpen()` for the emission check — never a second `shouldAttempt()`.
- `recordSuccess()` / `recordFailure()` — breaker outcome recording, with the trip count readable for telemetry.

`LlmClient` SHALL NOT emit a configuration manifest line: the effective sampling parameters are echoed once by `RUN_START` (run-spec capability), which is the record that carries the whole resolved plan rather than the LLM slice of it. It SHALL NOT read static `Config` after bootstrap; every parameter arrives via `LlmParams` — this clause is the requirement's real subject and is unchanged.

The shared-`max_tokens` discipline survives the manifest that used to demonstrate it. `max_tokens` SHALL still be read once and used both in the request body and in the echoed plan, so what the trace reports is what the wire carried; only the record it is checked against moves.

#### Scenario: single breaker consultation per decision
- **WHEN** an LLM stage's trigger evaluation reaches the breaker gate
- **THEN** `LlmClient.allows()` SHALL invoke `shouldAttempt()` exactly once for that decision
- **AND** a decline SHALL be unambiguously breaker-caused (the gate runs only after the other conjuncts passed)

#### Scenario: open-episode line emitted once
- **WHEN** the breaker trips OPEN and five routing decisions are declined during the same open window
- **THEN** exactly one `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` line SHALL be emitted, at the first declined decision
- **AND** the latch SHALL reset when the breaker next allows an attempt

#### Scenario: construction from the plan
- **WHEN** the resolved plan carries the LLM feature with `timeout_ms=15000` and `max_tokens=1024`
- **THEN** `LlmClient` SHALL configure `SglangClient` with those values, and the same values SHALL appear in the `RUN_START` effective-plan echo
- **AND** no manifest line SHALL be emitted at construction
- **AND** no `Config` static SHALL be read by the unit during the run

#### Scenario: composition is the only access path
- **WHEN** any code outside `LlmClient` needs the HTTP client or the breaker
- **THEN** it SHALL go through `LlmClient`'s methods
- **AND** no other class SHALL hold a `SglangClient` or `LlmCircuitBreaker` reference

---

### Requirement: SglangClient — OpenAI-Compatible HTTP Client

`SglangClient` SHALL send HTTP POST requests to `{baseUrl}/chat/completions` using the OpenAI chat completions API format. The request body SHALL be built using `org.json.JSONObject` and `org.json.JSONArray`, and SHALL include model name, sampling parameters (`temperature`, `top_p`, `top_k`, `max_tokens`), a `messages` array, and a `tools` array (OpenAI function-calling schema). The `tools` parameter is **required** for Qwen3-VL to generate structured tool calls when processing multimodal (image+text) input — without it the model returns empty content. Each message MAY contain text content, multimodal content (text + base64 image), or both. The client SHALL support Qwen3-VL's coordinate format where `"x"` may arrive as a `[x, y]` array instead of separate primitives.

The constructor SHALL accept `baseUrl` (String, including `/v1` prefix), `model` (String), `temperature` (double), `topP` (double), `topK` (int), `maxTokens` (int), and `timeoutMs` (int). Both connection and read timeouts SHALL be set to `timeoutMs`. **The `tools` schema SHALL be supplied per invocation** — `chat(messages, tools)` includes the supplied array in that request's body. The `setTools(JSONArray)` method and the run-wide field it installed are **removed**: a single schema fixed at construction cannot track the screen, which is what produced the measured incoherence where the wire always advertised `type_text` while the system message omitted it conditionally. No dual path remains (P3, INV-LLM-11).

Response parsing SHALL use `new JSONObject(responseBody)` to deserialize the response, extract `choices[0].message.content` and `choices[0].message.tool_calls`, and construct a `ChatResponse` object. It SHALL also extract the response envelope's top-level `model` field into `ChatResponse` (null when the server omits it), so the caller can record the server-reported model (the `LLM_ACK` sink record, per the `llm-routing` capability). A missing `model` field is not a parse failure.

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
- **THEN** `ChatResponse` SHALL carry it for the `LLM_ACK` record
- **AND** its absence SHALL NOT be a parse failure
