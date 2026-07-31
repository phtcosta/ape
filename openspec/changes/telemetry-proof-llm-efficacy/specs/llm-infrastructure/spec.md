## ADDED Requirements

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

---

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

## Invariants

- **INV-LLM-11**: The `tools` array sent on the wire SHALL be exactly the schema supplied to that `chat()` invocation; no tools state SHALL persist on the client across invocations.
- **INV-LLM-12**: When `capture()` returns null, exactly one failure stage SHALL be recorded, reset at the start of every `capture()` invocation; the seam SHALL never alter the null-return contract (INV-LLM-02).
