## Purpose

APE-RV's LLM infrastructure provides the low-level building blocks for communicating with an OpenAI-compatible LLM server (vLLM; "SGLang" is a legacy name kept in class names) running a vision-language model (Qwen3-VL). These components handle the full pipeline from screen capture to actionable coordinates: capturing a screenshot on the Android device, compressing and encoding the image for transmission, sending a multimodal chat request over HTTP, parsing the model's tool-call response with fallbacks for Qwen3-VL formatting quirks, converting between normalized coordinates and device pixels in both directions (the model's answer to a pixel, and a widget's pixel to the coordinate the prompt shows), and protecting the exploration loop from cascading LLM failures via a circuit breaker.

The base classes of this capability (`SglangClient`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `LlmCircuitBreaker`, `LlmException`) originate in the rvsmart project and live in `com.android.commands.monkey.ape.llm`, beside the units composed from them (`LlmClient`, `ScreenshotStep`). JSON is built and parsed with `org.json` (`org.json.JSONObject`, `org.json.JSONArray`), which the Android runtime provides, so the capability adds no Maven dependency.

The infrastructure communicates with the server via `java.net.HttpURLConnection` (available in the Android SDK) and uses `org.json` for JSON serialization. Screenshot capture uses `android.view.SurfaceControl` via reflection (hidden API, available from app_process context on API 28+), at the display's current rotation (`AndroidDevice.getRotation()`, read through `Display`, API 30-safe), so a landscape screen reaches the model upright and uncropped.

The infrastructure is designed for graceful degradation: if any component fails (screenshot capture returns null, HTTP times out, response is unparseable), the caller receives a null result and falls back to SATA action selection. The circuit breaker adds a second layer of protection by blocking all LLM attempts for `ape.llmBreakerOpenMs` (default 60 s) after `ape.llmBreakerFailures` (default 3) consecutive failures, preventing the exploration loop from wasting time on a dead server. Both are `LLM` plan keys; the breaker is consulted as a declared gate of the LLM stages (`llm-routing` "Declared LLM Gates", design D-B8).

## Invariants

- **INV-LLM-09**: A successful `ToolCallParser.parse()` SHALL carry exactly one repair-form label from the closed vocabulary `{ none, missing_y, array_xy, quoted_xy, int_scan }`, readable through `ParsedAction.getRepairForm()` without re-parsing the response. `none` means the tool call parsed with no coordinate-structure fix having altered the string. The label SHALL be the highest-precedence fix that altered the string, by the precedence `int_scan > quoted_xy > array_xy > missing_y > none` (`fixMalformedJson` labels the regex fixes; `lastResortIntScan` labels `int_scan`). The cosmetic fixes (leading zero, brace balancing) SHALL NOT be labeled: they never rescue a parse and never alter a coordinate. A `null` return carries no label.
- **INV-LLM-10**: `SglangClient.parseResponse` SHALL preserve each tool call's `arguments` in raw string form on the constructed `ToolCall` (`getRawArguments()`) whenever the envelope carries them: the string verbatim when `arguments` is a JSON string, including an unparseable one; the object's JSON serialization when it is a JSON object; null when absent. A malformed native `arguments` string SHALL NOT be reduced to an empty arguments map without the raw form surviving. `ToolCallParser.parse` Level 1 SHALL run the shared repair pipeline (`parseJsonString`: `fixMalformedJson` → org.json → `lastResortIntScan`) on the raw form before any map-based construction, and SHALL fall back to the map-based construction, labeled `none`, when the raw form is absent or yields no action.

## MODIFIED Requirements

### Requirement: LLM Configuration Keys

The following LLM configuration keys SHALL be plan keys, owned by the `LLM` feature family in `KeyOwnership` (`ape.llmPercentage` by its member `LLM_COIN`), resolved by `RunSpec` from `ape.properties` and the preset, and read through `RunContext.current().spec().llm()` (`url()`, `model()`, `promptVariant()`, and the typed getters `dbl`/`integer`/`bool` on the key for the others). `Config` SHALL declare no field for any of them: their former `Config` twins had no reader and are deleted (design D-B24), and their defaults are those of `KeyOwnership.defaultOf`:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.llmUrl` | String | `null` | SGLang base URL; owned by `LLM` with no neutral value |
| `ape.llmOnNewState` | boolean | `true` | Enable new-state LLM mode |
| `ape.llmOnStagnation` | boolean | `true` | Enable stagnation LLM mode |
| `ape.llmModel` | String | `"default"` | Model name for SGLang server |
| `ape.llmTemperature` | double | `0.3` | LLM sampling temperature |
| `ape.llmTopP` | double | `0.6` | Nucleus sampling threshold |
| `ape.llmTopK` | int | `50` | Top-k sampling |
| `ape.llmTimeoutMs` | int | `15000` | HTTP timeout in milliseconds |
| `ape.llmPercentage` | double | `0.02` | The LLM coin `p_L`: probability that a step not taken by a guard or trigger is intended for the LLM (0.0 = no `LlmCoin` stage, 0.9 = the `LLM` block's value) |
| `ape.llmMaxTokens` | int | `1024` | `max_tokens` for the chat completion request (J1c; default = the previously hard-coded value; not causal for truncation — observed `tokens_out` ≈ 25) |
| `ape.llmSnapTolerancePx` | int | `50` | Floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `CoordinateMapper.map` (J1b; lever analyzed and discarded — default not swept) |
| `ape.llmBoundaryTopPct` | double | `0.035` | Status-bar boundary reject band as a fraction of the display height, the `deviceHeight` of the decision (J1b; `0.035` = the campaign AVD's 63 px status bar over its 1794 px display, so a degenerate `(0,0)` is still rejected; set it for a device of another density) |

Three further keys are owned by the plan's `LLM` feature and read through `spec().llm()`, likewise with no `Config`
field (design D-B8):

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.llmGateMinActions` | int | `3` | `MinActionsGate(min)`: an LLM stage calls only on a state with at least `min` actions; `0` = gate not mounted |
| `ape.llmBreakerFailures` | int | `3` | `LlmCircuitBreaker` failure threshold; `0` = `BreakerGate` not mounted, so the breaker is never consulted and emits no `breaker_open` entry |
| `ape.llmBreakerOpenMs` | long | `60000` | `LlmCircuitBreaker` open duration in milliseconds |

Their defaults reproduce the former behaviour: `size() > 2`, 3 failures and 60 s.

The three J1b/J1c keys carry no clamping logic (researcher-facing knobs, like `llmTimeoutMs`/`llmTopK`). `ape.llmMaxTokens` and `ape.llmSnapTolerancePx` reproduce the previously hard-coded behavior bit-for-bit. `ape.llmBoundaryTopPct` does not: its old default `0.05` and the bottom band `ape.llmBoundaryBottomPct=0.94` were fractions of a 1920-high screen applied to the 1794-high display, and `llm-coordinate-single-base` re-derived the band in the display base (`llm-routing` INV-RTR-22). `ape.llmBoundaryBottomPct` is retired: on the display every value below 1.0 rejects app content and every value from 1.0 up never fires, so a properties file that sets it aborts resolution with that reason (`run-spec`, "Total Fail-Fast Validation").

In the run-spec `Feature` model, `ape.llm` is the activation key of the `LLM` group (`guidance-groups`, design
D-B1). `ape.llmUrl` is owned by `LLM` with no neutral value, so `ape.llm=true` without `ape.llmUrl` aborts
resolution as a missing dependency. Every other key in the two tables is a sub-parameter owned by the `LLM`
feature family. When `LLM` is absent from the resolved plan:
- `LlmParams` is null and no LLM object is constructed;
- an explicitly-set LLM sub-parameter is accepted only at its neutral value (INV-RUN-05 of `run-spec`);
- an explicitly enabled mode (`ape.llmOnNewState=true`, `ape.llmOnStagnation=true` or `ape.llmPercentage>0`)
  aborts resolution as a missing dependency.

When `ape.llmPercentage` is `0.0`, no coin step SHALL call the LLM: only the new-state and stagnation triggers
apply. When it is `0.02` (the jar default), the coin SHALL hold on approximately 2 % of the steps that reach the
`LlmCoin` stage.

When the plan does not carry `LLM`, all LLM features SHALL be disabled and no LLM-related objects SHALL be instantiated.

#### Scenario: LLM disabled by default

- **WHEN** `ape.properties` sets neither `ape.llm` nor `ape.llmUrl`
- **THEN** the resolved plan SHALL carry no `LLM` feature, `RunContext.current().spec().llm()` SHALL be null, and `KeyOwnership.defaultOf("ape.llmUrl")` SHALL be `null`
- **AND** no `LlmClient`, `SglangClient`, `LlmEngine` or other LLM unit SHALL be created, and `RunContext.llmEngine()` SHALL be null

#### Scenario: LLM enabled with URL

- **WHEN** `ape.properties` contains `ape.llm=true` and `ape.llmUrl=http://10.0.2.2:30000/v1`
- **THEN** `RunContext.current().spec().llm().url()` SHALL equal `"http://10.0.2.2:30000/v1"`
- **AND** the run SHALL construct its LLM units with the configured URL

#### Scenario: Individual modes toggled

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl=http://10.0.2.2:30000/v1` and `ape.llmOnNewState=false`
- **THEN** the new-state LLM mode SHALL be disabled
- **AND** the stagnation mode SHALL remain enabled (per its default)

#### Scenario: Custom sampling parameters

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl=http://10.0.2.2:30000/v1`, `ape.llmTopP=0.9` and `ape.llmTopK=100`
- **THEN** the resolved plan's `spec().llm().dbl("ape.llmTopP")` SHALL equal `0.9`
- **AND** `spec().llm().integer("ape.llmTopK")` SHALL equal `100`
- **AND** `SglangClient` SHALL use these values in the request body

#### Scenario: Default 2% random routing

- **WHEN** `ape.properties` contains `ape.llm=true` and `ape.llmUrl`, no `ape.preset`, and no `ape.llmPercentage`
- **THEN** the resolved plan's `spec().llm().dbl("ape.llmPercentage")` SHALL be `0.02`, the value of `KeyOwnership.defaultOf("ape.llmPercentage")`
- **AND** the coin SHALL hold on approximately 2 % of the steps that reach the `LlmCoin` stage

#### Scenario: Random routing disabled

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl` and `ape.llmPercentage=0.0`
- **THEN** the resolved plan's `ape.llmPercentage` SHALL be `0.0` and `LLM_COIN` SHALL be absent from its `features`
- **AND** no `LlmCoin` stage SHALL be assembled, while `u_L` is still drawn each step (`decision-pipeline` INV-DP-13)

#### Scenario: High percentage for experiments

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl` and `ape.llmPercentage=0.7`
- **THEN** the resolved plan's `spec().llm().dbl("ape.llmPercentage")` SHALL be `0.7`, the `p_L` injected into the `LlmCoin` stage

#### Scenario: J1b/J1c defaults reproduce the hard-coded values

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl`, no `ape.preset` and none of the three J1b/J1c keys
- **THEN** the resolved plan SHALL carry `ape.llmMaxTokens=1024`, `ape.llmSnapTolerancePx=50` and `ape.llmBoundaryTopPct=0.035` (`spec().llm().integer(…)`/`dbl(…)`), the values of `KeyOwnership.defaultOf` for the three keys
- **AND** request `max_tokens` and the euclidean tolerance SHALL be identical to the pre-J1 hard-coded constants
- **AND** the boundary reject SHALL NOT reproduce the hard-coded `0.05`/`0.94`: it SHALL be the status-bar band of `llm-routing` INV-RTR-22, with no lower band (`llm-coordinate-single-base`)

#### Scenario: J1b/J1c keys configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl`, `ape.llmMaxTokens=2048` and `ape.llmSnapTolerancePx=80`
- **THEN** the resolved plan's `spec().llm().integer("ape.llmMaxTokens")` SHALL be `2048` and `spec().llm().integer("ape.llmSnapTolerancePx")` SHALL be `80`
- **AND** the values SHALL flow into the request body / euclidean tolerance without any code change

#### Scenario: New keys classified in the apePureMode registry

- **WHEN** the J1b/J1c keys are looked for in the `apePureMode` kill-switch registry
- **THEN** no such registry SHALL exist — `rvForcedOffValues`, `rvUnsetKeys` and `rvExemptReasons` are deleted along with the mechanism they served, and the invariant that required them (INV-ARCH-06 of the former `scoring-pipeline` text) is dissolved; its substitute is `run-spec` INV-RUN-05
- **AND** the classification this scenario asked for SHALL instead be made by the `Feature` model, where the compiler can check it rather than a string literal naming a field (next scenario)

#### Scenario: LLM sub-parameters owned by the Feature model

- **WHEN** the run-spec key-ownership totality test runs
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, `llmBoundaryTopPct`, `llmGateMinActions`, `llmBreakerFailures` and `llmBreakerOpenMs` SHALL each be owned by the `LLM` feature as sub-parameters
- **AND** `llmBoundaryBottomPct` SHALL be owned by no feature and SHALL appear among the retired keys
- **AND** with `LLM` absent from the plan, none of them SHALL parameterize any constructed mechanism

#### Scenario: LLM mode without a URL aborts

- **WHEN** `ape.properties` contains `ape.llm=true` and no `ape.llmUrl`, or `ape.llmOnStagnation=true` without `ape.llm=true`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM` (instead of silently loading a mode that can never fire)

### Requirement: SglangClient — Per-Request Tool Schema

`SglangClient` SHALL accept the OpenAI function-calling tools schema **per invocation**: `chat(messages, tools)` includes the supplied `tools` array in that request's body. The constructor-time single-schema path (`setTools(JSONArray)` installing one run-wide array included in every request) is removed — no dual path remains (P3). The `tools` parameter stays required in every request for Qwen3-VL to generate structured tool calls on multimodal input; the caller decides per request which tools exist, so the wire schema tracks the screen: `LlmClient.chat(messages, hasInputField)` passes the schema without `type_text` on screens without input fields, matching the system message (`llm-routing` capability, "LLM Unit Lifecycle and Ownership").

A null or empty `tools` argument is a programming error at the call site, not a supported mode: `LlmClient` always supplies one of its two prebuilt schemas.

#### Scenario: request body carries the supplied schema

- **WHEN** `chat(messages, toolsWithoutTypeText)` is invoked
- **THEN** the request body's `tools` array SHALL be exactly the supplied schema (no `type_text` entry)
- **AND** a subsequent `chat(messages, toolsWithTypeText)` SHALL carry `type_text` — the schema is per-request state, not client state

#### Scenario: no run-wide schema survives

- **WHEN** two consecutive `chat()` invocations supply different schemas
- **THEN** neither request SHALL be influenced by the other's schema (nothing is cached on the client between invocations)

### Requirement: LlmClient — Transport and Circuit Breaker as One Unit

`LlmClient` (package `com.android.commands.monkey.ape.llm`) SHALL compose the existing `SglangClient` (HTTP transport) and `LlmCircuitBreaker` (fault tolerance) into a single unit constructed once per run from the plan's `LlmParams` (base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens`, `timeout_ms`) and owned by `RunContext`. The `SglangClient` and `LlmCircuitBreaker` requirements of this capability are unchanged — `LlmClient` is their composition and the sole holder of their references; no other class SHALL hold either.

`LlmClient` SHALL expose:

- `chat(messages, hasInputField) -> ChatResponse | null` — delegates to `SglangClient.chat` with the tools schema `hasInputField` selects among the two built at construction (with and without `type_text`, INV-LLM-11); the failure-cause seam (`getLastErrorCause()`, INV-LLM-08) is readable only at the null-return site, unchanged.
- `allows() -> boolean` — the single breaker consultation per routing decision: it SHALL call `shouldAttempt()` exactly once (preserving the OPEN→HALF_OPEN probe transition), reset the open-episode log latch when the breaker allows, and at the FIRST breaker-caused decline of each open episode print the Logger line `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` and write the `llm[]` sub-event `{"result":"breaker_open","trips":<N>}` through the sink's `llmBreakerOpen`, using the side-effect-free `isOpen()` for the emission check — never a second `shouldAttempt()`.
- `recordSuccess()` / `recordFailure()` — breaker outcome recording, with the trip count readable for telemetry.

`LlmClient` SHALL NOT emit a configuration manifest line: the effective sampling parameters are echoed once by `RUN_START` (run-spec capability), which is the record that carries the whole resolved plan rather than the LLM slice of it. It SHALL NOT read static `Config` after bootstrap; every parameter arrives via `LlmParams` — this clause is the requirement's real subject and is unchanged.

The shared-`max_tokens` discipline survives the manifest that used to demonstrate it. `max_tokens` SHALL still be read once and used both in the request body and in the echoed plan, so what the trace reports is what the wire carried; only the record it is checked against moves.

#### Scenario: single breaker consultation per decision
- **WHEN** an LLM stage's trigger evaluation reaches the breaker gate
- **THEN** `LlmClient.allows()` SHALL invoke `shouldAttempt()` exactly once for that decision
- **AND** a decline SHALL be unambiguously breaker-caused (the gate runs only after the other conjuncts passed)

#### Scenario: open-episode line emitted once
- **WHEN** the breaker trips OPEN and five routing decisions are declined during the same open window
- **THEN** exactly one `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` line and one `breaker_open` sub-event SHALL be emitted, at the first declined decision
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
