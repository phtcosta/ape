## ADDED Requirements

### Requirement: LlmClient — Transport and Circuit Breaker as One Unit

`LlmClient` (package `com.android.commands.monkey.ape.llm`) SHALL compose the existing `SglangClient` (HTTP transport) and `LlmCircuitBreaker` (fault tolerance) into a single unit constructed once per run from the plan's `LlmParams` (base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens`, `timeout_ms`) and owned by `RunContext`. The `SglangClient` and `LlmCircuitBreaker` requirements of this capability are unchanged — `LlmClient` is their composition and the sole holder of their references; no other class SHALL hold either.

`LlmClient` SHALL expose:

- `chat(messages, tools) -> ChatResponse | null` — delegates to `SglangClient.chat` with the per-request tools schema; the failure-cause seam (`getLastErrorCause()`, INV-LLM-08) is readable only at the null-return site, unchanged.
- `allows() -> boolean` — the single breaker consultation per routing decision: it SHALL call `shouldAttempt()` exactly once (preserving the OPEN→HALF_OPEN probe transition), reset the open-episode log latch when the breaker allows, and emit the `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` line at the FIRST breaker-caused decline of each open episode using the side-effect-free `isOpen()` for the emission check — never a second `shouldAttempt()`.
- `recordSuccess()` / `recordFailure()` — breaker outcome recording, with the trip count readable for telemetry.

`LlmClient` SHALL emit the `[APE-LLM-CONFIG]` effective-manifest line exactly once at construction, with the same fields as before (the values it will actually send, including the shared `max_tokens`). It SHALL NOT read static `Config` after bootstrap; every parameter arrives via `LlmParams`.

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
- **THEN** `LlmClient` SHALL configure `SglangClient` with those values and emit one `[APE-LLM-CONFIG]` line carrying them
- **AND** no `Config` static SHALL be read by the unit during the run

#### Scenario: composition is the only access path
- **WHEN** any code outside `LlmClient` needs the HTTP client or the breaker
- **THEN** it SHALL go through `LlmClient`'s methods
- **AND** no other class SHALL hold a `SglangClient` or `LlmCircuitBreaker` reference

## MODIFIED Requirements

### Requirement: SglangClient — Per-Request Tool Schema

**Why this requirement is restated here at all.** Its behavior is untouched by this change — the
per-request schema, the removal of the constructor-time single-schema path, and the null-`tools`
programming-error clause all stand exactly as written. What moves is one cross-reference: the
sentence explaining *why* the caller decides per request cites the `llm-routing` capability's
`"LlmRouter Lifecycle"` requirement by name, and this change REMOVES that requirement when it
dismantles the 996-LOC class. Synced unchanged, the main spec would point at a requirement that no
longer exists. The clause it is really pointing at survives — this change's own REMOVED entry says
so, listing per-request tool schema selection among the behaviors that "survive verbatim in the
replacement requirements" — so the reference is re-anchored onto `"LLM Unit Lifecycle and
Ownership"`, the requirement that replaced it. Nothing else in the body is edited.

`SglangClient` SHALL accept the OpenAI function-calling tools schema **per invocation**: `chat(messages, tools)` includes the supplied `tools` array in that request's body. The constructor-time single-schema path (`setTools(JSONArray)` installing one run-wide array included in every request) is removed — no dual path remains (P3). The `tools` parameter stays required in every request for Qwen3-VL to generate structured tool calls on multimodal input; what changes is that the caller now decides per request which tools exist, so the wire schema can track the screen (the router omits `type_text` on screens without input fields, matching the system message — `llm-routing` capability, "LLM Unit Lifecycle and Ownership"). Previously the schema was built once at router construction and always advertised `type_text`, contradicting the conditional system message.

A null or empty `tools` argument is a programming error at the call site, not a supported mode: the router always supplies one of its two prebuilt schemas.

#### Scenario: request body carries the supplied schema

- **WHEN** `chat(messages, toolsWithoutTypeText)` is invoked
- **THEN** the request body's `tools` array SHALL be exactly the supplied schema (no `type_text` entry)
- **AND** a subsequent `chat(messages, toolsWithTypeText)` SHALL carry `type_text` — the schema is per-request state, not client state

#### Scenario: no run-wide schema survives

- **WHEN** two consecutive `chat()` invocations supply different schemas
- **THEN** neither request SHALL be influenced by the other's schema (nothing is cached on the client between invocations)


The failure-stage seam is unchanged in mechanism. Only its honesty-boundary note is re-anchored: it attributed the joinable stage+activity pair to "the router's `[APE-LLM-ERROR] cause=screenshot` line", and this change dismantles `LlmRouter` — that line is emitted by `LlmTelemetry`, and the seam is read by `ScreenshotStep` inside `LlmEngine`'s pipeline. No field, no cause value, and no null-return contract moves.

### Requirement: ScreenshotCapture — Failure-Stage Cause Seam

`ScreenshotCapture` SHALL record which capture stage failed when `capture()` returns null, readable by the caller between the null return and the next `capture()` invocation (the same seam pattern as `SglangClient.getLastErrorCause`, INV-LLM-08): `surface_control` when the SurfaceControl reflection path returned null or threw, `uiautomation` when the UiAutomation fallback also produced no bitmap. The cause SHALL be reset at the start of every `capture()` invocation, so a stale stage from an earlier failure can never be attributed to a later call. The null-return contract (INV-LLM-02) is unchanged — the seam adds attribution, not new failure behavior.

Honesty boundary: the Android API returns null for FLAG_SECURE, reflection unavailability, and permission denial without distinguishing them — the seam names the failing **stage**, not the OS-level reason; joining the stage + foreground activity (both carried on the screenshot-failure diagnostic `LlmTelemetry` emits) with the known FLAG_SECURE APK list is an offline step. `OutOfMemoryError` is an `Error` and escapes the `catch (Exception)` blocks — it is NOT conflated into the null return and the seam makes no claim about it.

#### Scenario: FLAG_SECURE failure names its stage

- **WHEN** `capture()` returns null because both the SurfaceControl path and the UiAutomation fallback yielded no bitmap on a FLAG_SECURE window
- **THEN** the failure-cause seam SHALL report the stage of the last attempted path (`uiautomation`)
- **AND** the caller (`ScreenshotStep`, within `LlmEngine`'s pipeline) SHALL be able to read it before the next `capture()` call

#### Scenario: cause reset per invocation

- **WHEN** `capture()` fails once and a later `capture()` succeeds
- **THEN** the seam SHALL NOT report the earlier failure's stage after the successful call
