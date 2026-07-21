# Delta: llm-routing — llm-toolcall-parse-recovery

## Purpose

`LlmRouter` emits the per-decision `[APE-LLM-TEL]` line and the aggregate `LLM Summary` that make
the LLM arm's behavior reconstructable from a trace. When `ToolCallParser` recovers a malformed
tool call (per the `llm-infrastructure` capability), the recovery is invisible in this telemetry —
a repaired decision looks identical to a clean one, so hardening the parser would erase the raw
tool-call fidelity signal (base 4B versus fine-tuned v2). This delta adds a `repair=<form>` field to
the per-decision line, emitted only when a repair produced the action, and a `repaired=<N>` counter
to the summary. The addition is orthogonal to the failure-cause attribution: `cause=parse` still
fires only when recovery genuinely fails to extract an action, and a recovered decision is a normal
`matched` / `llm_tap` outcome that additionally names the repair it required.

## Invariants

- **INV-RTR-13** (new): When a routed decision's `ParsedAction` carries a repair-form label other
  than `none` (per `llm-infrastructure` INV-LLM-09), the decision's `[APE-LLM-TEL]` line SHALL carry
  `repair=<form>` and the `repairedCount` aggregate SHALL be incremented exactly once for that
  decision. A decision whose label is `none` SHALL NOT carry the `repair` field and SHALL NOT
  increment `repairedCount`. `repair` is additive telemetry on a successful decision: it never
  suppresses an `[APE-LLM-ERROR]` line and never alters the `cause=parse` attribution, which
  continues to fire only when `ToolCallParser.parse()` returns null.

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
| `repair` | emitted only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse. Records that the model's tool call needed a pre-parse repair to become executable, keeping raw tool-call fidelity measurable after the parser is hardened |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

The `step` field makes the LLM call telemetry joinable to the decision and its outcome on one key: `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` (the latter per the scoring-pipeline capability). The value is passed into `selectAction()` by the caller; `LlmRouter` does not read agent state itself.

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate` or `reason=boundary` |

The `repair` field is orthogonal to `result`: a repaired tool call yields a normal `matched`, `llm_tap`, or `no_match` outcome and additionally carries `repair=<form>`. It marks a fidelity property of the model's output (the tool call was malformed but recoverable), not the routing outcome.

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

A `parse` failure is now the residual after `ToolCallParser` recovery: it is counted only when `parse()` returns null despite the added quoted-collapsed-XY fix and last-resort integer extraction (per the `llm-infrastructure` capability). A recovered tool call is not a `parse` failure — it is a successful decision carrying `repair=<form>`.

**Cause attribution SHALL follow the failure point.** When `chat()` returns null, the cause SHALL be read from `SglangClient.getLastErrorCause()` — the only site where that seam MAY be consulted (the client resets it per invocation, per the `llm-infrastructure` capability; at any other site its value belongs to an earlier call and is stale). Failures occurring after `chat()` returns non-null SHALL be attributed by `LlmRouter` directly, without consulting `getLastErrorCause()`: tool-call extraction failure (`ToolCallParser.parse()` returning null) is `parse`, an `ImageProcessor` null result is `image`, and an unexpected exception in the routing pipeline is `internal`.

At the failure point, `LlmRouter` SHALL emit `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>`, where `step` is the same join key carried by `[APE-LLM-TEL]` — a failed attempt is thereby attributable to the step whose selection it interrupted. The `null` return contract (INV-RTR-02) is unchanged; only the attribution is added.

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>` |

The circuit-breaker-blocked line SHALL be emitted at the **first** routing-predicate decline of each open episode (the moment a predicate declines a call because the breaker does not allow the attempt); subsequent declines within the same open episode SHALL NOT re-emit it (at `llmPercentage=0.7` a 60s open window would otherwise produce tens of identical lines). The emission check SHALL use a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` call — `shouldAttempt()` carries the OPEN→HALF_OPEN probe transition — and SHALL distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached).

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> repaired=<N> timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

The aggregate `null` field is replaced by the named cause counters. The `decisions` denominator for the `[APE-RV] LLM Decision ratio` line SHALL be `matched + llm_tap + no_match + (timeout + http_error + conn_error + parse_error + image_error + internal_error + screenshot_failed)`. This is identical in value to the pre-change `matched + llm_tap + no_match + null`: the retired `nullCount` was incremented on every abandoned attempt including screenshot failures (`screenshot_failed` was a subset of `null`); under the new scheme a screenshot failure increments `screenshotFailedCount` only, so the seven cause counters form a partition of the retired `null` count and the reported ratio (`matched / decisions`) is unchanged in meaning.

`repaired=<N>` (`repairedCount`) counts successful decisions whose tool call required a `ToolCallParser` repair (repair-form label other than `none`). It is NOT a cause counter and is NOT part of the `decisions` denominator: a repaired decision is already counted under exactly one of `matched` / `llm_tap` / `no_match`. It is a subset overlay on those outcomes, maintained separately so the base-versus-v2 tool-call fidelity contrast — how often the model emitted a malformed-but-recoverable call — is countable post-hoc from the summary line alone.

`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps; it is maintained separately from `matched` and `no_match` so the off-tree effect is countable post-hoc from the summary line alone.

`screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null (e.g. FLAG_SECURE windows). It is a peer cause counter (no longer a subset of an aggregate `null`), maintained separately so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=42`
- **AND** the `[APE-STEP]` and `[APE-OUTCOME]` lines of the same decision SHALL carry `step=42`, so call cost (`tokens_in/out`, `time_ms`), decision, and outcome join without timestamp reconstruction

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the resulting coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=61 result=matched repair=quoted_xy`
- **AND** the aggregate summary SHALL count the decision under both `matched=<N>` and `repaired=<N>`
- **AND** no `[APE-LLM-ERROR] cause=parse` line SHALL be emitted for step 61

#### Scenario: Clean tool call omits the repair field

- **WHEN** at step 62 the model returns a well-formed `{"name": "click", "arguments": {"x": 540, "y": 399}}` that resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL NOT carry a `repair` field
- **AND** the decision SHALL NOT increment `repairedCount`

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

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** `[APE-LLM-ERROR] cause=parse ...` SHALL be emitted and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)
- **AND** no `repair` field SHALL be emitted (there was no successful decision to annotate)

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
