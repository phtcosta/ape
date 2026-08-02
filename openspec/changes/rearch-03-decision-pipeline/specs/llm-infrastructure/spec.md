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
