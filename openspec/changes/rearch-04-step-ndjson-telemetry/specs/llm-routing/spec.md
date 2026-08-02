# llm-routing Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: LLM telemetry stops being its own stdout line family and becomes **sub-events of the step record** (event-sink capability). The `[APE-LLM-TEL]` and `[APE-LLM-ERROR]` lines are retired — each routing attempt appends one entry to the current step's `llm[]` array, so the call↔decision↔outcome join exists by construction instead of by a shared `step=` key. The aggregate summary lines are retired in favor of `RUN_END` counters; the `[APE-LLM-CONFIG]` manifest is subsumed by the `RUN_START` effective-plan echo (run-spec capability); the server-model acknowledgement becomes the `LLM_ACK` record. All counters, fields, and semantics (cause partition, repair provenance, dead-pair overlay, breaker-episode latch) are preserved — only the encoding changes. The `mopWeight*`-style behavior of the router is untouched: telemetry never decides (INV-SNK-07).

## MODIFIED Requirements

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL record structured per-attempt telemetry as `llm[]` sub-events on the current step's `StepRecord` (event-sink capability) and aggregate counters reported in `RUN_END`.

**Per-attempt sub-event** (one array entry per routing attempt, in occurrence order within the step):

| Field | Values |
|-------|--------|
| `call` | running call counter for the run |
| `mode` | `new_state`, `stagnation`, `random` |
| `tool` | the parsed tool name (`click`, `long_click`, `type_text`, `back`) |
| `qwen` / `px` | `[x,y]` model-space and mapped pixel coordinates |
| `result` | `matched`, `llm_tap`, `no_match` for completed mappings; `error` for attempts abandoned before the mapping step; `breaker_open` for the once-per-open-episode breaker decline |
| `reason` | only on `no_match`: `dead_pair`, `degenerate`, or `boundary` |
| `repair` | only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse |
| `mcls` / `ncls` / `ndist` / `widgets` | matched class, nearest class, nearest distance, candidate widget count |
| `tok` | `[prompt_tokens, completion_tokens]` (0 if unavailable) |
| `ms` | wall-clock milliseconds for the full pipeline (screenshot → response) |
| `text` | only for text-entry decisions: the typed text (JSON-escaped by the serializer) |
| `cause` / `detail` | only on `result:"error"`: exactly one named cause per abandoned attempt — `timeout`, `http_<status>`, `connection`, `parse`, `image`, `internal` — attributed at the failure point (client seam `getLastErrorCause()` consulted only when `chat()` returned null; router-attributed otherwise). The screenshot-capture failure keeps its counter (`screenshot_failed`) and its existing free-text line, with no `error` sub-event |
| `trips` | only on `result:"breaker_open"`: the breaker trip count; emitted at the first routing-predicate decline of each open episode only, using a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` |
| `sys` / `user` / `resp` / `tool_calls` | prompt and response dumps, present when the prompt-dump flag is on (default on — recordability parity with the retired unconditional `[APE-LLM-PROMPT]`/`[APE-LLM-RESPONSE]` lines) |

No sub-event carries a step, activity, or variant field: the step and activity are the parent record's envelope, and the prompt variant is run-constant in the `RUN_START` plan echo. The `repair` field remains orthogonal to `result` (a repaired call yields a normal outcome plus `repair=<form>`).

**Counters**: the per-cause failure counters (partitioning the retired `null`), `matched`/`llm_tap`/`no_match`/`dead_pair`/`repaired`, token and latency totals, and `breaker_trips` SHALL be maintained exactly as before and exposed via an accessor consumed by the `RUN_END` emitter. The decisions denominator (`matched + llm_tap + no_match + Σ failure causes`) is derived by consumers; no ratio is stored.

#### Scenario: Matched call recorded on its step

- **WHEN** the new-state hook routes to the LLM at step 42 and the call maps to a widget
- **THEN** the `s:42` record's `llm` array SHALL contain the entry with `result:"matched"`, its coordinates, `tok`, and `ms`
- **AND** no `[APE-LLM-TEL]` line SHALL be emitted

#### Scenario: Repaired tool call recorded and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the coordinate resolves to a widget
- **THEN** the step-61 sub-event SHALL carry `result:"matched"` and `repair:"quoted_xy"`
- **AND** the run counters SHALL count the decision under both `matched` and `repaired`
- **AND** no `error` sub-event with `cause:"parse"` SHALL exist for step 61

#### Scenario: no_match reasons separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate, another for a navigation-band coordinate, and a third by the dead-pair ban
- **THEN** their sub-events SHALL carry `reason:"degenerate"`, `reason:"boundary"`, and `reason:"dead_pair"` respectively, each with `result:"no_match"`

#### Scenario: Failure causes discriminated without a join

- **WHEN** one attempt times out and a later attempt gets HTTP 500
- **THEN** the first step's record SHALL carry `{"result":"error","cause":"timeout",...}` and the second's `{"result":"error","cause":"http_500",...}`
- **AND** `RUN_END` counters SHALL report `timeout:1` and `http_error:1`

#### Scenario: Breaker episode recorded once

- **WHEN** the breaker trips to OPEN with 2 trips and predicates decline 5 calls during the same open window
- **THEN** exactly one sub-event `{"result":"breaker_open","trips":2}` SHALL be recorded, on the step of the first decline
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Screenshot failure stays out of the error sub-events

- **WHEN** a run ends after 5 attempts of which 3 were abandoned at screenshot capture and 1 failed at parse
- **THEN** `RUN_END` counters SHALL report `parse_error:1 screenshot_failed:3`
- **AND** the screenshot failures SHALL have produced no `error` sub-event (their free-text line stands)

#### Scenario: Prompt dump follows the flag

- **WHEN** the prompt-dump flag is at its default (on)
- **THEN** each completed call's sub-event SHALL carry `sys`/`user`/`resp` with the prompt and response text JSON-escaped
- **AND** with the flag off, the same sub-event SHALL omit those fields and nothing else changes

### Requirement: Server Model Acknowledgement

The router SHALL emit one `{"type":"LLM_ACK","server_model":"<model>"}` record (event-sink capability) after the first successful `chat()` response of the run, carrying the model identifier reported by the server in the response envelope (`unknown` when the response omits the field). The `RUN_START` plan echo records the model the run *requested*; `LLM_ACK` records what the server *actually served* — the pair proves an arm talked to the intended model without inspecting the server. At most one `LLM_ACK` per run; a run with zero successful responses emits none (its absence, with the failure-cause counters, is itself diagnostic).

#### Scenario: ACK emitted once as a sink record

- **WHEN** the first successful response reports `qwen3-vl-8b` and 200 further calls succeed
- **THEN** exactly one `LLM_ACK` record SHALL appear in the trace
- **AND** no `[APE-LLM-CONFIG-ACK]` line SHALL be emitted

## REMOVED Requirements

### Requirement: Effective LLM Config Manifest

**Reason**: subsumed by the `RUN_START` effective-plan echo (run-spec capability, rearch-02): every field of the retired `[APE-LLM-CONFIG]` line (model, temperature, top_p, top_k, max_tokens, timeout_ms, prompt_variant, llm_percentage, on_new_state, on_stagnation, stagnation_threshold, url) is carried by the effective plan in the first trace record. The manifest emitter in the router constructor is deleted outright — no shim, no dual emission (P3). INV-RTR-10 is retired with it; the self-description property it provided is now a property of `RUN_START`.
