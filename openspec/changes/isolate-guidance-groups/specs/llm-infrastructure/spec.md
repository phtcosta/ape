# Delta: LLM Infrastructure (isolate-guidance-groups)

## Purpose

**The screenshot bug.** `ScreenshotCapture` passed rotation `0` to `SurfaceControl.screenshot`
(`llm/ScreenshotCapture.java:79-81`). On a display turned to landscape, `width × height` is already the
landscape frame (1794 × 1080), and the framebuffer came back in its natural portrait orientation, cropped to that
rectangle. The model therefore saw landscape screens turned 90° and partly cut off. The dimensions, the prompt's
`@(x,y)` list, `CoordinateMapper` and the tree bounds were right; only the image was wrong (plan §2.4).
`AndroidDevice.getRotation()` called `IWindowManager.getRotation()`, which does not exist on API 30.

**The fix and its source.** The fix was written and verified on device (retrowars, 2026-09-27) on the branch
`llm-opaque-screen`, which was abandoned and never merged: `f993081f` and `3b35073a`. This change ports **only the
code files** of those two commits (`ScreenshotCapture.java`, `AndroidDevice.java` and the
`ScreenshotCaptureStageTest` cases). It does not merge the branch (D-A9). Neither file changed between
`e93dea86` and `eea2b1a9`.

**The breaker constants.** The circuit breaker's two constants (3 failures, 60 s) become plan keys owned by
`LLM`, with defaults that keep the behaviour (`llm-routing` "Declared LLM Gates", design D-B8).

## Invariants

- **INV-LLM-14**: Every SurfaceControl capture SHALL pass `ScreenshotCapture.displayRotation(AndroidDevice.getRotation())`
  as the rotation argument. `displayRotation(r)` SHALL return `r` when `0 <= r <= 3` and `0` otherwise.
  `AndroidDevice.getRotation()` SHALL return
  `DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY).getRotation()`, which is the display
  `getDisplayBounds()` reads its size from, and SHALL return `-1` when that read throws. A portrait display
  (rotation 0) is therefore captured exactly as before this change.
- **INV-LLM-15**: `LlmCircuitBreaker`'s failure threshold and open duration SHALL be the plan's
  `ape.llmBreakerFailures` and `ape.llmBreakerOpenMs`, injected through `LlmParams` when `LlmClient` is
  constructed. With the defaults `3` and `60000`, every breaker transition SHALL be identical to the former
  hard-coded constants.

## MODIFIED Requirements

### Requirement: ScreenshotCapture — SurfaceControl Screenshot

`ScreenshotCapture.capture(int width, int height)` SHALL capture a screenshot of the device display and return it
as a PNG byte array. The primary capture method SHALL use `android.view.SurfaceControl.screenshot(Rect, int, int,
int)` via reflection (a hidden API, available from the `app_process` context). It SHALL pass
`Rect(0, 0, width, height)`, `width`, `height` and the display's current rotation,
`displayRotation(AndroidDevice.getRotation())` (INV-LLM-14). If reflection fails, a fallback to
`UiAutomation.takeScreenshot()` SHALL be attempted.

`width × height` is the frame `Display.getSize()` reports for the current orientation, so the capture SHALL be
taken in that orientation too. `displayRotation` clamps the value to `Surface.ROTATION_0…ROTATION_270` (`0..3`). Any
other value, including the `-1` that `getRotation()` returns when the display cannot be read, becomes `0`, which
is the call made before this change. `AndroidDevice.getRotation()` SHALL read the rotation from the real default
display (`getRealDisplay(DEFAULT_DISPLAY).getRotation()`), the same display `getDisplayBounds()` reads its size
from, so the size and the rotation describe one orientation. It SHALL NOT call `IWindowManager.getRotation()`,
which does not exist on API 30.

The same `getRotation()` value SHALL be written on every step as `dec.rot`, a value in `-1..3`: the rotation
`0..3`, `0` included, or `-1` when unreadable (`event-sink`). `dec.rot` records the raw value; the capture uses
`displayRotation` of it. Whether a call saw a landscape screen can then be read from the trace.

#### Scenario: Successful capture via SurfaceControl

- **WHEN** `capture(1080, 1920)` is called on an Android device with API 28+ whose display is in portrait (`getRotation()` returns `0`)
- **AND** `SurfaceControl.screenshot()` is accessible via reflection
- **THEN** a non-null byte array containing valid PNG data SHALL be returned
- **AND** the PNG dimensions SHALL match the requested width and height
- **AND** the rotation argument SHALL be `0`, so the call is identical to the call made before this change

#### Scenario: SurfaceControl reflection fails

- **WHEN** `SurfaceControl.screenshot()` is not accessible (e.g., API restriction)
- **THEN** the UiAutomation fallback SHALL be attempted
- **AND** if both methods fail, `null` SHALL be returned
- **AND** no exception SHALL propagate

#### Scenario: landscape screen captured upright

- **WHEN** the display is turned to landscape, `getRotation()` returns `1`, and `capture(1794, 1080)` is called
- **THEN** `SurfaceControl.screenshot` SHALL be invoked with `Rect(0, 0, 1794, 1080)`, `1794`, `1080`, `1`
- **AND** the PNG SHALL be 1794 × 1080 and show the screen in the orientation the display shows

#### Scenario: unreadable rotation falls back to zero

- **WHEN** `getRealDisplay(DEFAULT_DISPLAY).getRotation()` throws, so `AndroidDevice.getRotation()` returns `-1`
- **THEN** `displayRotation(-1)` SHALL return `0` and the capture SHALL proceed with rotation `0`
- **AND** `displayRotation(4)` SHALL also return `0`, while `displayRotation(3)` SHALL return `3`

---

### Requirement: LlmCircuitBreaker — Fault Tolerance

`LlmCircuitBreaker` SHALL implement a 3-state circuit breaker (CLOSED, OPEN, HALF_OPEN) that protects the
exploration loop from cascading LLM failures. Its two parameters SHALL come from the plan, through
`LlmParams` (INV-LLM-15):
- `failureThreshold` = `ape.llmBreakerFailures`, default `3`;
- `openDurationMs` = `ape.llmBreakerOpenMs`, default `60000`.

The breaker SHALL have no constructor that supplies defaults of its own. When `ape.llmBreakerFailures` is `0`,
the `BreakerGate` is not mounted (`llm-routing` "Declared LLM Gates"): the breaker is then never consulted, so
`shouldAttempt()` is never called, no breaker decision gates a call, and no `llm[]` entry with
`result:"breaker_open"` is ever emitted; `RUN_END.counters.gateBreaker` stays `0`.

**State transitions:**
- CLOSED: normal operation. `recordFailure()` increments the failure counter. When the counter reaches `failureThreshold` (default: 3), transition to OPEN.
- OPEN: all requests are blocked, and `shouldAttempt()` returns false. After `openDurationMs` (default: 60000 ms), transition to HALF_OPEN.
- HALF_OPEN: one probe request allowed. `recordSuccess()` → CLOSED. `recordFailure()` → OPEN (reset timer).

`recordSuccess()` SHALL always reset failure counter and transition to CLOSED regardless of current state.

#### Scenario: Trip after 3 failures

- **WHEN** `recordFailure()` is called 3 times consecutively from CLOSED state, with `ape.llmBreakerFailures` at its default `3`
- **THEN** `shouldAttempt()` SHALL return `false`
- **AND** `getStateName()` SHALL return `"OPEN"`
- **AND WHEN** the plan sets `ape.llmBreakerFailures=5`
- **THEN** the breaker SHALL still be `"CLOSED"` after 4 consecutive failures and `"OPEN"` after the 5th

#### Scenario: Recovery after timeout

- **WHEN** the circuit breaker is OPEN, with `ape.llmBreakerOpenMs` at its default `60000`
- **AND** 60 seconds have elapsed since the last failure
- **THEN** `shouldAttempt()` SHALL return `true`
- **AND** `getStateName()` SHALL return `"HALF_OPEN"`

#### Scenario: Successful probe closes breaker

- **WHEN** the circuit breaker is HALF_OPEN
- **AND** `recordSuccess()` is called
- **THEN** `getStateName()` SHALL return `"CLOSED"`
- **AND** failure counter SHALL be reset to 0

#### Scenario: Failed probe reopens breaker

- **WHEN** the circuit breaker is HALF_OPEN
- **AND** `recordFailure()` is called
- **THEN** `getStateName()` SHALL return `"OPEN"`
- **AND** the open duration timer SHALL be reset

#### Scenario: Success resets from any state

- **WHEN** `recordSuccess()` is called regardless of current state
- **THEN** the state SHALL transition to CLOSED
- **AND** failure counter SHALL be 0

---

### Requirement: LLM Configuration Keys

`Config.java` SHALL declare the following `public static final` fields for LLM configuration, loaded from `ape.properties` at class-loading time:

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
| `ape.llmSnapTolerancePx` | int | `50` | Floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `mapToModelAction` (J1b; lever analyzed and discarded — default not swept) |
| `ape.llmBoundaryTopPct` | double | `0.035` | Status-bar boundary reject band as a fraction of the display height, the `deviceHeight` of the decision (J1b; `0.035` = the campaign AVD's 63 px status bar over its 1794 px display, so a degenerate `(0,0)` is still rejected; set it for a device of another density) |

Three further keys are owned by the plan's `LLM` feature and read through `spec().llm()`, not through `Config`
(design D-B8):

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
- **THEN** `Config.llmUrl` SHALL be `null` and the plan SHALL carry no `LLM` feature
- **AND** no `SglangClient`, `LlmRouter`, or other LLM objects SHALL be created

#### Scenario: LLM enabled with URL

- **WHEN** `ape.properties` contains `ape.llm=true` and `ape.llmUrl=http://10.0.2.2:30000/v1`
- **THEN** `Config.llmUrl` SHALL equal `"http://10.0.2.2:30000/v1"`
- **AND** the run SHALL construct its LLM units with the configured URL

#### Scenario: Individual modes toggled

- **WHEN** `ape.properties` contains `ape.llm=true`, `ape.llmUrl=http://10.0.2.2:30000/v1` and `ape.llmOnNewState=false`
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
- **AND** the coin SHALL hold on approximately 2 % of the steps that reach the `LlmCoin` stage

#### Scenario: Random routing disabled

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.0`
- **THEN** `Config.llmPercentage` SHALL be `0.0`
- **AND** no `LlmCoin` stage SHALL be assembled, while `u_L` is still drawn each step (`decision-pipeline` INV-DP-13)

#### Scenario: High percentage for experiments

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`

#### Scenario: J1b/J1c defaults reproduce the hard-coded values

- **WHEN** `ape.properties` contains none of the three J1b/J1c keys
- **THEN** `Config.llmMaxTokens` SHALL be `1024`, `Config.llmSnapTolerancePx` SHALL be `50`, and `Config.llmBoundaryTopPct` SHALL be `0.035`
- **AND** request `max_tokens` and the euclidean tolerance SHALL be identical to the pre-J1 hard-coded constants
- **AND** the boundary reject SHALL NOT reproduce the hard-coded `0.05`/`0.94`: it SHALL be the status-bar band of `llm-routing` INV-RTR-22, with no lower band (`llm-coordinate-single-base`)

#### Scenario: J1b/J1c keys configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048` and `ape.llmSnapTolerancePx=80`
- **THEN** `Config.llmMaxTokens` SHALL be `2048` and `Config.llmSnapTolerancePx` SHALL be `80`
- **AND** the values SHALL flow into the request body / euclidean tolerance without any code change

#### Scenario: New keys classified in the apePureMode registry

- **WHEN** the J1b/J1c keys are looked for in the `apePureMode` kill-switch registry
- **THEN** no such registry SHALL exist — `rvForcedOffValues`, `rvUnsetKeys` and `rvExemptReasons` are deleted along with the mechanism they served, and INV-ARCH-06 is dissolved (`scoring-pipeline` capability)
- **AND** the classification this scenario asked for SHALL instead be made by the `Feature` model, where the compiler can check it rather than a string literal naming a field (next scenario)

#### Scenario: LLM sub-parameters owned by the Feature model

- **WHEN** the run-spec key-ownership totality test runs
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, `llmBoundaryTopPct`, `llmGateMinActions`, `llmBreakerFailures` and `llmBreakerOpenMs` SHALL each be owned by the `LLM` feature as sub-parameters
- **AND** `llmBoundaryBottomPct` SHALL be owned by no feature and SHALL appear among the retired keys
- **AND** with `LLM` absent from the plan, none of them SHALL parameterize any constructed mechanism

#### Scenario: LLM mode without a URL aborts

- **WHEN** `ape.properties` contains `ape.llm=true` and no `ape.llmUrl`, or `ape.llmOnStagnation=true` without `ape.llm=true`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM` (instead of silently loading a mode that can never fire)
