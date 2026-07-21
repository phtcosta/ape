## Purpose

Delta spec for LLM experiment logging (fixes #1 and #3). Adds a per-run effective-config manifest so every `.trace` is self-describing, and discriminates the collapsed `null` failure count into named causes so a hyperparameter arm's degradation mode (timeout vs. HTTP vs. parse vs. image vs. internal) is countable. Additionally stamps the agent exploration step on the per-call telemetry/error lines — closing the deterministic `[APE-LLM-TEL]` ↔ `[APE-STEP]` ↔ `[APE-OUTCOME]` join — and acknowledges the server-reported model once per run, so an arm's LLM traffic is verifiable end to end. No routing decision, request payload, or action-selection behavior changes — this delta is observability only.

## ADDED Requirements

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

- **INV-RTR-10**: The `[APE-LLM-CONFIG]` line SHALL be emitted exactly once per run, at `LlmRouter` construction, and SHALL report the effective sampling parameters (the same values placed into the request body by `SglangClient.buildRequestBody`), not the `ape.properties` map contents. When `Config.llmUrl` is null the `LlmRouter` is not constructed (INV-RTR-01) and no `[APE-LLM-CONFIG]` line is emitted.

#### Scenario: Manifest records defaults not present in ape.properties

- **WHEN** a run starts with `ape.llmUrl` set but `ape.llmTemperature` / `ape.llmTopP` / `ape.llmTopK` left at their code defaults
- **THEN** exactly one `[APE-LLM-CONFIG]` line SHALL be emitted
- **AND** it SHALL carry the effective default values (e.g. `temperature=0.3`)
- **AND** it SHALL carry `max_tokens=1024` even though `max_tokens` is not an `ape.properties` key

#### Scenario: Manifest records effective clamped percentage

- **WHEN** a run starts with `ape.llmPercentage=1.5` in `ape.properties`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `llm_percentage=1.0` (the effective post-clamp value), even though the config dump would echo the raw `1.5`

### Requirement: Server Model Acknowledgement

`LlmRouter` SHALL emit one `[APE-LLM-CONFIG-ACK] server_model=<model>` line after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `[APE-LLM-CONFIG]` manifest records the model the run *requested*; the ACK records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server.

- **INV-RTR-12**: The `[APE-LLM-CONFIG-ACK]` line SHALL be emitted at most once per run, only after a successful `chat()` response. A run with zero successful responses SHALL emit no ACK line (its absence, combined with the failure-cause counters, is itself diagnostic).

#### Scenario: Server model acknowledged once

- **WHEN** the first successful `chat()` response of a run reports `model=qwen3-vl-8b` and 40 further successful calls follow
- **THEN** exactly one `[APE-LLM-CONFIG-ACK] server_model=qwen3-vl-8b` line SHALL be emitted, after the first successful response

#### Scenario: No acknowledgement without a successful response

- **WHEN** every `chat()` call of a run fails (server down)
- **THEN** zero `[APE-LLM-CONFIG-ACK]` lines SHALL be emitted

## MODIFIED Requirements

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured per-decision telemetry on an `[APE-LLM-TEL]` line and an aggregate `[APE-RV] LLM Summary` line.

**Per-decision structured log** (parseable, follows the `[RVTRACK:LLM]` pattern from rvsmart/rvagent):

| Field | Values |
|-------|--------|
| `step` | agent exploration step of the routing attempt — the same value the step's `[APE-STEP]` line carries, supplied by the calling agent (`getTimestamp()` at selection) |
| `mode` | `new-state`, `stagnation`, `random` |
| `result` | `matched`, `llm_tap`, `no_match` |
| `reason` | emitted only on `no_match`, immediately after `result=`: `degenerate` (parsed coordinate `(0,0)`) or `boundary` (status/nav band) |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

The `step` field makes the LLM call telemetry joinable to the decision and its outcome on one key: `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` (the latter per the scoring-pipeline delta). The value is passed into `selectAction()` by the caller; `LlmRouter` does not read agent state itself.

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate` or `reason=boundary` |

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

**Cause attribution SHALL follow the failure point.** When `chat()` returns null, the cause SHALL be read from `SglangClient.getLastErrorCause()` — the only site where that seam MAY be consulted (the client resets it per invocation, per the `llm-infrastructure` delta; at any other site its value belongs to an earlier call and is stale). Failures occurring after `chat()` returns non-null SHALL be attributed by `LlmRouter` directly, without consulting `getLastErrorCause()`: tool-call extraction failure (`ToolCallParser.parse()` returning null) is `parse`, an `ImageProcessor` null result is `image`, and an unexpected exception in the routing pipeline is `internal`.

At the failure point, `LlmRouter` SHALL emit `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>`, where `step` is the same join key carried by `[APE-LLM-TEL]` — a failed attempt is thereby attributable to the step whose selection it interrupted. The `null` return contract (INV-RTR-02) is unchanged; only the attribution is added.

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>` |

The circuit-breaker-blocked line SHALL be emitted at the **first** routing-predicate decline of each open episode (the moment a predicate declines a call because the breaker does not allow the attempt); subsequent declines within the same open episode SHALL NOT re-emit it (at `llmPercentage=0.7` a 60s open window would otherwise produce tens of identical lines). The emission check SHALL use a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` call — `shouldAttempt()` carries the OPEN→HALF_OPEN probe transition — and SHALL distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached).

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

The aggregate `null` field is replaced by the named cause counters. The `decisions` denominator for the `[APE-RV] LLM Decision ratio` line SHALL be `matched + llm_tap + no_match + (timeout + http_error + conn_error + parse_error + image_error + internal_error + screenshot_failed)`. This is identical in value to the pre-change `matched + llm_tap + no_match + null`: the retired `nullCount` was incremented on every abandoned attempt including screenshot failures (`screenshot_failed` was a subset of `null`); under the new scheme a screenshot failure increments `screenshotFailedCount` only, so the seven cause counters form a partition of the retired `null` count and the reported ratio (`matched / decisions`) is unchanged in meaning.

`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps; it is maintained separately from `matched` and `no_match` so the off-tree effect is countable post-hoc from the summary line alone.

`screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null (e.g. FLAG_SECURE windows). It is a peer cause counter (no longer a subset of an aggregate `null`), maintained separately so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone.

- **INV-RTR-02** (unchanged): `LlmRouter.selectAction()` SHALL never throw an exception to the caller. All failures SHALL result in a null return; the added `[APE-LLM-ERROR]` line and cause counters do not change the null-return contract.
- **INV-RTR-11**: The sum `timeoutCount + httpErrorCount + connErrorCount + parseErrorCount + imageErrorCount + internalErrorCount + screenshotFailedCount` SHALL equal the number of `selectAction()` attempts abandoned before the mapping step — the attempts that return null **without** emitting an `[APE-LLM-TEL]` line. Exactly one cause counter SHALL be incremented per abandoned attempt (none uncounted, none double-counted). `no_match` outcomes also return null from `selectAction()` but emit an `[APE-LLM-TEL]` line and are counted by `noMatchCount` only; they are outside this sum.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=42`
- **AND** the `[APE-STEP]` and `[APE-OUTCOME]` lines of the same decision SHALL carry `step=42`, so call cost (`tokens_in/out`, `time_ms`), decision, and outcome join without timestamp reconstruction

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

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call
- **THEN** `[APE-LLM-ERROR] cause=parse ...` SHALL be emitted and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)

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
