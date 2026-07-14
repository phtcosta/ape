## MODIFIED Requirements

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured telemetry for each LLM routing decision using the `[APE-RV] LLM` prefix. The following events SHALL be logged:

**Per-call structured log** (parseable, follows `[RVTRACK:LLM]` pattern from rvsmart/rvagent):
```
[APE-RV] LLM iter=<N> mode=<mode> tokens_in=<N> tokens_out=<N> time_ms=<N> result=<result> [widget=<id>|coords=(<x>,<y>)]
```

| Field | Values |
|-------|--------|
| `mode` | `new-state`, `stagnation` |
| `result` | `model_action`, `no_match`, `null`, `timeout`, `breaker_open`, `parse_failed` |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-RV] LLM <step> failed: <reason>` |

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary: calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> avg_ms=<N> matched=<N> no_match=<N> null=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

`screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null (e.g. FLAG_SECURE windows). It SHALL be maintained as its own counter, separate from the aggregate `null` count (which also covers HTTP, parse, and mapping failures), so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone. Screenshot failures still count toward `null` as today (the step yields no action) — `screenshot_failed` attributes the cause.

#### Scenario: Successful action logged

- **WHEN** `selectAction()` returns a non-null ModelAction of type `MODEL_CLICK` targeting widget `btn_encrypt`
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM selected: MODEL_CLICK on btn_encrypt at (540, 960)`

#### Scenario: Circuit breaker event logged

- **WHEN** `shouldRouteNewState()` is called but the circuit breaker is OPEN with 2 trips
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM circuit breaker OPEN, skipping (trips=2)`

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200`
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM mode=stagnation, state=MainActivity#abc123`

#### Scenario: Screenshot failures counted separately

- **WHEN** a run ends after 5 LLM routing attempts of which 3 were abandoned at screenshot capture (secure window) and 1 failed at parse
- **THEN** the summary line SHALL report `null=4 screenshot_failed=3`
